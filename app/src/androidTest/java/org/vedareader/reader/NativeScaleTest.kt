package org.vedareader.reader

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Run on a real fractional-density API 35 display (regression target: 1080px / density 2.625).
 * Never sets global DPI, display overrides, synthetic CompositionLocal density, CSS or fonts.
 * Independent MATCH_PARENT AndroidView: no application, search/controller or reader-host state.
 * All DOM probes below are read-only and interpolate only a checked native record integer.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class NativeScaleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun requireActualFractionalDensity() {
        val density = compose.activity.resources.displayMetrics.density.toDouble()
        assumeTrue("Requires a fractional native display density; never modify global DPI for this test",
            abs(density - density.roundToInt()) > .01)
    }

    @Test
    fun lateScrollingAnchorPaintsUsingNativeScaleWithoutWidthRatioDrift() {
        val source = listOf(lines(101, 240), lines(2000, 20), lines(3000, 80))
        Fixture(source, paged = false, anchor = 2000).use { fixture ->
            fixture.awaitReady()
            val dom = fixture.geometry(2000)
            val native = fixture.native()
            fixture.assertScale(dom, native)
            assertTrue("Exercise the proven deep-offset failure, not a near-origin tolerance", native.y > 17_534)
            assertTrue(dom.getBoolean("visible"))
            assertEquals(2000, compose.runOnIdle { fixture.view.readingAnchor })
            assertEquals((dom.getDouble("recordTop") * native.scale).roundToInt(), native.y)
            assertEquals(native.y.toDouble(), dom.getDouble("y") * native.scale, 2.0)
            assertEquals(0, native.x)
            assertEquals(1, fixture.rendered.get())
            // If Chromium exposes the reported discrepancy, explicitly prove this fixture
            // reaches beyond the old publish gate instead of merely passing near the origin.
            val widthScale = native.width / dom.getDouble("width")
            if (abs(widthScale - native.scale) > .001) {
                assertTrue(abs(dom.getDouble("y") * widthScale - native.y) > 2)
            }
        }
    }

    @Test
    fun forwardBackThenDeepColumnsUseAuthoredPitchAndSingleRoundedOffsets() {
        Fixture(listOf(lines(101, 900)), paged = true).use { fixture ->
            fixture.awaitReady()
            val count = fixture.info().count
            assertTrue("Exercise accumulation well beyond the first successful turns", count >= 20)
            fixture.assertPage(0, count)
            fixture.turn(true)
            fixture.assertPage(1, count)
            fixture.turn(false)
            fixture.assertPage(0, count)
            for (page in 1 until count) {
                fixture.turn(true)
                fixture.assertPage(page, count)
            }
            compose.runOnIdle { assertFalse(fixture.view.turnBookPage(true) { error("No blank final column") }) }
            for (page in count - 2 downTo 0) {
                fixture.turn(false)
                fixture.assertPage(page, count)
            }
            compose.runOnIdle { assertFalse(fixture.view.turnBookPage(false) { error("No negative column") }) }
            assertEquals(1, fixture.rendered.get())
        }
    }

    @Test
    fun deepEndLandingAndTextReflowRetainRelativeParagraphFragment() {
        Fixture(listOf(lines(101, 900)), paged = true, atEnd = true).use { fixture ->
            fixture.awaitReady()
            val initial = fixture.info()
            assertTrue(initial.count >= 20)
            fixture.assertPage(initial.count - 1, initial.count)
            repeat(3) { fixture.turn(false) }
            val before = fixture.info()
            compose.runOnIdle { fixture.view.setReaderTextZoom(140) }
            fixture.await("Text zoom reflows the same late paragraph") { fixture.latest.get()?.count != before.count }
            val after = fixture.info()
            assertEquals(101, after.anchorRecord)
            assertTrue(after.count > before.count)
            assertEquals("Keep the approximate relative fragment, not the paragraph's first page",
                before.page.toDouble() / before.count, after.page.toDouble() / after.count, 2.0 / after.count)
            fixture.assertPage(after.page, after.count)
            fixture.turn(false)
            fixture.assertPage(after.page - 1, after.count)
            assertEquals(1, fixture.rendered.get())
        }
    }

    private fun lines(record: Int, count: Int) = Paragraph(record,
        (1..count).joinToString("<CR>") { "Synthetic native scale line $it." }, "PA_Purport", "")

    private data class Native(val width: Int, val height: Int, val x: Int, val y: Int, val scale: Double)

    private inner class Fixture(
        source: List<Paragraph>, paged: Boolean, anchor: Int? = null, atEnd: Boolean = false,
    ) : AutoCloseable {
        lateinit var view: OfflineReaderView
        val rendered = AtomicInteger()
        val latest = AtomicReference<BookPageInfo?>()
        private val failed = AtomicBoolean()
        private val mounted = mutableStateOf(true)

        init {
            val html = FolioText.document(source, "en", ReaderPreferences(), bookMode = true, scrollingBook = !paged)
            compose.setContent {
                if (mounted.value) AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    OfflineReaderView(context).also { reader ->
                        view = reader
                        // Without these, direct AndroidView WRAP_CONTENT can collapse CSS vh.
                        reader.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        reader.paginatedBook = paged
                        reader.scrollingBook = !paged
                        reader.initialBookAnchor = anchor
                        reader.onRendered = { rendered.incrementAndGet() }
                        reader.onRenderFailed = { failed.set(true) }
                        reader.onBookPageChanged = { latest.set(it) }
                        reader.render("native-scale", html, 0, { null }, initialAtEnd = atEnd)
                    }
                }, onRelease = { it.release() }, update = {})
            }
        }

        fun awaitReady() = await("Initial native layout must paint") { rendered.get() == 1 }

        fun await(label: String, condition: () -> Boolean) {
            try {
                compose.waitUntil(20_000) { failed.get() || condition() }
            } catch (error: AssertionError) {
                throw AssertionError("$label; ${diagnostic()}", error)
            }
            assertFalse("$label failed; ${if (failed.get()) diagnostic() else ""}", failed.get())
        }

        fun native() = compose.runOnIdle { Native(view.width, view.height, view.scrollX, view.scrollY, view.nativeContentScale) }
        fun info(): BookPageInfo = compose.runOnIdle { requireNotNull(view.bookPageInfo) }

        fun turn(forward: Boolean) {
            val completed = AtomicInteger()
            compose.runOnIdle { assertTrue(view.turnBookPage(forward) { completed.incrementAndGet() }) }
            await("Turn forward=$forward") { completed.get() > 0 }
            assertEquals(1, completed.get())
        }

        fun assertScale(dom: JSONObject, native: Native) {
            assertTrue(native.scale.isFinite() && native.scale > 0)
            assertTrue(native.width > 0 && native.height > 0)
            assertEquals("Native scale must agree with the independent DOM scale",
                dom.getDouble("dpr") * dom.getDouble("visualScale"), native.scale, .00001)
            assertEquals("loaded", dom.getString("fonts"))
        }

        fun assertPage(page: Int, count: Int) {
            val info = info()
            val native = native()
            val dom = geometry(101)
            assertScale(dom, native)
            assertEquals(page, info.page)
            assertEquals(count, info.count)
            val pitch = dom.getDouble("pitch")
            assertEquals("One authored, unpadded, zero-gap column", "1", dom.getString("columnCount"))
            assertEquals(0.0, dom.getDouble("gap"), 0.0)
            assertEquals("Actual record-fragment spacing must agree with the flow pitch",
                pitch, dom.getDouble("fragmentPitch"), .01)
            assertEquals("A single long paragraph has exactly the real column count", count, dom.getInt("fragments"))
            assertEquals((pitch * native.scale).roundToInt(), info.viewportWidth)
            val offset = (page.toDouble() * pitch * native.scale).roundToInt()
            assertEquals(offset, info.nativeOffset)
            assertEquals(offset, native.x)
            assertEquals(native.x.toDouble(), dom.getDouble("x") * native.scale, 2.0)
            assertEquals(page * pitch, dom.getDouble("x"), 1.0)
            assertEquals(0, native.y)
            assertEquals(0.0, dom.getDouble("y"), .5)
            assertTrue("Every page paints source, not a blank trailing column", dom.getBoolean("visible"))
            assertEquals(101, info.anchorRecord)
        }

        fun geometry(record: Int): JSONObject {
            require(record > 0)
            val result = AtomicReference<String>()
            val done = CountDownLatch(1)
            compose.runOnIdle {
                view.evaluateJavascript("""
                    (function(){var v=visualViewport,f=document.querySelector('main.book-flow'),s=getComputedStyle(f);
                        var e=document.getElementById('record-$record'),boxes=e.getClientRects(),r=e.getBoundingClientRect();
                        return {width:v.width,height:v.height,x:v.pageLeft,y:v.pageTop,dpr:devicePixelRatio,
                            visualScale:v.scale,pitch:f.getBoundingClientRect().width,columnCount:s.columnCount,
                            gap:parseFloat(s.columnGap),fonts:document.fonts.status,extent:document.scrollingElement.scrollWidth,
                            recordTop:r.top-v.offsetTop+v.pageTop,fragments:boxes.length,
                            fragmentPitch:boxes.length>1?boxes[1].left-boxes[0].left:0,
                            visible:Array.from(boxes).some(function(b){return b.width>0&&b.height>0&&
                                b.right-v.offsetLeft>.5&&b.left-v.offsetLeft<v.width-.5&&
                                b.bottom-v.offsetTop>.5&&b.top-v.offsetTop<v.height-.5;})};})()
                """.trimIndent()) { result.set(it); done.countDown() }
            }
            assertTrue("Read-only native geometry", done.await(5, TimeUnit.SECONDS))
            return JSONObject(requireNotNull(result.get()))
        }

        private fun diagnostic(): String = "native=${native()} page=${latest.get()} DOM=${geometry(101)}"

        override fun close() {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }
}