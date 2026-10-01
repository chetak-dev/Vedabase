package org.vedareader.reader

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Synthetic native fixtures only. MATCH_PARENT WebViews, real Folio fonts/CSP and bounded,
 * single-flight probes. No repository/controller, imported database, permission or settings writes.
 */
@RunWith(AndroidJUnit4::class)
class ReaderStyleTest {
    @Test
    fun appearanceInterruptingATurnKeepsItsPaintedAnchorRatherThanTheLogicalSelection() {
        Fixture(ReadingMode.BOOK).use { fixture ->
            fixture.awaitReady()
            val before = JSONObject(fixture.eval(BookPagination.measure(null)))
            val paintedAnchor = BookAnchor(before.getInt("record"), before.getInt("fragment"), before.getInt("fragments"))
            assertTrue("Opening column includes a previous paragraph", paintedAnchor.record < MIDDLE)
            fixture.ui {
                host.next()
                applyStyle(ReaderStyle(ReaderPaper.DARK, 150))
            }
            fixture.await("Interrupted turn finishes once") { host.settledView != null && !reader.isReaderStylePending }
            val after = JSONObject(fixture.eval(BookPagination.measure(paintedAnchor)))
            fixture.ui {
                val info = requireNotNull(reader.bookPageInfo)
                assertEquals((after.getInt("targetPage") + 1).coerceAtMost(info.count - 1), info.page)
                assertEquals(info.nativeOffset, reader.scrollX)
            }
        }
    }

    @Test
    fun pagedStyleChangesRealColorsLineHeightsAndCountWithoutReloadingOrLosingTheFragment() {
        Fixture(ReadingMode.BOOK).use { fixture ->
            fixture.awaitReady()
            repeat(2) { fixture.turn(true) }
            val before = fixture.awaitLayout(MIDDLE, ReaderStyle())
            val retained = fixture.identity()
            var count = 0
            fixture.ui { count = status.pageCount; assertTrue(status.pageIndex > 0) }

            val dark = ReaderStyle(ReaderPaper.DARK, 150)
            fixture.ui {
                applyStyle(dark)
                assertTrue(reader.isReaderStylePending)
                assertNull(host.settledView)
                assertEquals(Color.parseColor(dark.palette.Paper), (host.background as ColorDrawable).color)
                assertFalse(status.canNext)
                assertFalse(status.canPrevious)
            }
            val after = fixture.awaitLayout(MIDDLE, dark)
            assertEquals(before.getDouble("fragmentFraction"), after.getDouble("fragmentFraction"),
                1.0 / after.getInt("fragments") + .02)
            assertEquals("none", after.getString("texture"))
            fixture.ui { assertTrue(status.pageCount > count); assertEquals(0, reader.scrollY) }
            fixture.assertRetained(retained)

            val sepia = ReaderStyle(ReaderPaper.SEPIA, 85)
            fixture.ui { applyStyle(sepia) }
            val smaller = fixture.awaitLayout(MIDDLE, sepia)
            assertTrue(smaller.getString("texture").contains("radial-gradient"))
            fixture.ui { assertTrue(status.pageCount < count); assertTrue(status.pageIndex > 0) }
            fixture.assertRetained(retained)
        }
    }

    @Test
    fun scrollingBurstsAndNativeZoomKeepFractionAndPauseBlocksOldQueriesUntilCssAcknowledgement() {
        Fixture(ReadingMode.VERSE).use { fixture ->
            fixture.awaitReady()
            fixture.seek(MIDDLE, .37)
            val before = fixture.awaitLayout(MIDDLE, ReaderStyle())
            assertEquals(.37, before.getDouble("fraction"), .01)
            val retained = fixture.identity()
            fixture.eval("""
                (function(){window.__styleChanges=0;
                    new MutationObserver(function(){window.__styleChanges++;}).observe(
                        document.getElementById('reader-live-style'),{childList:true});return true;})()
            """.trimIndent())
            val finalStyle = ReaderStyle(ReaderPaper.SEPIA, 150)
            fixture.ui {
                applyStyle(ReaderStyle(ReaderPaper.DARK, 85)); submitZoom(130)
                applyStyle(ReaderStyle(ReaderPaper.LIGHT, 120)); submitZoom(90)
                applyStyle(finalStyle); submitZoom(120)
                assertTrue(reader.isReaderStylePending)
            }
            val after = fixture.awaitLayout(MIDDLE, finalStyle, 120)
            assertEquals(before.getDouble("fraction"), after.getDouble("fraction"), .02)
            assertTrue(after.getDouble("y") > before.getDouble("y"))
            assertEquals("One UI burst should write only the latest CSS", "1", fixture.eval("window.__styleChanges"))
            fixture.assertRetained(retained)
            fixture.ui { repeat(10) { applyStyle(finalStyle); submitZoom(120) } }
            fixture.fence()
            assertEquals("Equal normalized requests are no-ops", "1", fixture.eval("window.__styleChanges"))

            // Exercise the pause/resume barrier independently of JS timing. Native measurements
            // are real; only callback delivery is held. Old success/error must never publish.
            val held = mutableListOf<(String) -> Unit>()
            var hold = false
            var ready = 0
            var changes = 0
            var failures = 0
            var queries = 0
            lateinit var probe: ScrollBookLayout
            fixture.ui {
                val view = reader
                probe = ScrollBookLayout(view) { script, receive ->
                    queries++
                    if (hold) held += receive else view.evaluateJavascript(script) { receive(it) }
                }
                probes += probe
                probe.start(null, view.scrollY, false, { ready++ }, { changes++ }, { failures++ })
            }
            fixture.await("Independent layout settled") { probe.settled && ready == 1 }
            fixture.ui { hold = true; probe.reflow() }
            fixture.await("Exactly one outstanding measure") { held.size == 1 }
            var notifications = 0
            var count = 0
            fixture.ui {
                notifications = ready + changes
                count = queries
                probe.pauseReflow()
                repeat(10) { probe.reflow(); probe.observe() }
                held.single()("{\"error\":true}") // Stale even though this callback reports failure.
            }
            fixture.frames(5)
            fixture.ui {
                assertEquals(count, queries)
                assertEquals(notifications, ready + changes)
                assertEquals(0, failures)
                assertFalse(probe.settled)
                hold = false
                probe.resumeReflow()
            }
            fixture.await("Only resumed layout may settle") { probe.settled }
            fixture.ui { assertEquals(1, ready); assertEquals(0, failures); probe.stop() }
        }
    }

    @Test
    fun initialEndLatestStyleAndZoomSurviveBothModesIncomingPreparationAndRelease() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE)) {
            val latest = ReaderStyle(ReaderPaper.DARK, 150)
            Fixture(mode, atEnd = true, pending = listOf(ReaderStyle(ReaderPaper.SEPIA, 85), latest),
                initialZoom = 125).use { fixture ->
                fixture.awaitReady()
                fixture.awaitLayout(LAST, latest, 125)
                fixture.ui {
                    assertEquals(1, paints.size)
                    assertTrue(paints.single().atEnd)
                    assertFalse(paints.single().pending)
                    assertEquals(125, paints.single().zoom)
                    assertFalse(state.enterAtEnd)
                }
                val retained = fixture.identity()
                if (mode == ReadingMode.BOOK) repeat(2) { fixture.turn(false) }
                else fixture.seek(LAST, .37)
                fixture.awaitLayout(LAST, latest, 125)
                val next = ReaderStyle(ReaderPaper.SEPIA, 85)
                fixture.ui { applyStyle(next) }
                fixture.awaitLayout(LAST, next, 125)
                fixture.ui {
                    if (mode == ReadingMode.BOOK) assertTrue(status.pageIndex < status.pageCount - 1)
                    else assertTrue(reader.scrollY in 1 until reader.scrollRange)
                }
                fixture.assertRetained(retained)

                fixture.ui {
                    val outgoing = reader
                    applyStyle(latest)
                    // An explicit synthetic replacement (not a style-triggered load) tests storage
                    // and propagation to both sides of the existing bounded double buffer.
                    state = state.copy(document = requireNotNull(state.document).copy(), enterAtEnd = true)
                    host.submit(state, zoom) { imageRequests++; null }
                    assertEquals(2, host.childCount)
                    assertSame(outgoing, host.currentView)
                    watchPaint(host.getChildAt(1) as OfflineReaderView)
                    applyStyle(next)
                }
                fixture.awaitReady(expectedPaints = 2)
                fixture.awaitLayout(LAST, next, 125)
                fixture.ui { assertTrue(paints.last().atEnd); assertEquals(1, host.childCount) }

                var reports = 0
                fixture.ui {
                    val view = reader
                    applyStyle(latest)
                    reports = statusReports
                    host.release()
                    view.setReaderStyle(ReaderStyle())
                    view.setReaderTextZoom(80)
                    assertTrue(view.isReleased)
                }
                fixture.frames(5)
                fixture.ui { assertEquals(reports, statusReports); assertEquals(0, host.childCount); assertEquals(2, paints.size) }
            }
        }
    }

    @Test
    fun legacyStaticStyleKeepsJavascriptOffAndLiveMismatchFailsWithoutReloadOrPermissionChanges() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: OfflineReaderView
            val rendered = CountDownLatch(1)
            var created = false
            var paints = 0
            var failures = 0
            val style = ReaderStyle(ReaderPaper.DARK, 150)
            try {
                scenario.onActivity { activity ->
                    view = OfflineReaderView(activity)
                    created = true
                    view.layoutParams = FrameLayout.LayoutParams(-1, -1)
                    view.setReaderStyle(style) // Before configuration/render, with JS still disabled.
                    view.onRendered = { paints++; rendered.countDown() }
                    view.onRenderFailed = { failures++; rendered.countDown() }
                    activity.setContentView(FrameLayout(activity).apply { addView(view) })
                    view.render("legacy-style", FolioText.document(listOf(
                        Paragraph(10, "Synthetic नमूना śabda style", "PA_Purport", "")), "en", ReaderPreferences()), 0) { null }
                }
                assertTrue(rendered.await(20, TimeUnit.SECONDS))
                val found = CountDownLatch(1)
                var matches = 0
                var url = ""
                scenario.onActivity {
                    assertEquals(1, paints)
                    assertEquals(0, failures)
                    assertFalse(view.settings.javaScriptEnabled)
                    assertRestrictions(view)
                    url = requireNotNull(view.url)
                    view.setFindListener { _, count, done -> if (done) { matches = count; found.countDown() } }
                    view.findAllAsync("नमूना śabda") // An error page/progress=100 is not rendered source text.
                }
                assertTrue(found.await(20, TimeUnit.SECONDS))
                assertEquals(1, matches)
                scenario.onActivity {
                    view.setReaderStyle(ReaderStyle(ReaderPaper.SEPIA, 85))
                    assertEquals("Unsupported legacy live style must not be silently ignored", 1, failures)
                    assertFalse(view.settings.javaScriptEnabled)
                    assertRestrictions(view)
                    assertEquals(url, view.url)
                    assertEquals(1, paints)
                }
            } finally { scenario.onActivity { if (created) view.release() } }
        }
    }

    @Test
    fun missingOwnedStyleFailsTheExistingRenderGuardInsteadOfMutatingOtherDomOrReloading() {
        Fixture(ReadingMode.BOOK).use { fixture ->
            fixture.awaitReady()
            repeat(2) { fixture.turn(true) } // Initial page can include the preceding paragraph tail.
            fixture.awaitLayout(MIDDLE, ReaderStyle())
            fixture.eval("document.getElementById('reader-live-style').remove()")
            lateinit var view: OfflineReaderView
            fixture.ui { view = reader; applyStyle(ReaderStyle(ReaderPaper.DARK, 125)) }
            fixture.await("Missing style must reach render failure", allowFailure = true) { status.renderFailed }
            fixture.ui {
                assertTrue(view.isReleased)
                assertNull(host.currentView)
                assertEquals(1, paints.size)
                assertEquals(0, imageRequests)
            }
        }
    }

    private data class Paint(val zoom: Int, val pending: Boolean, val atEnd: Boolean)
    private data class Identity(val view: OfflineReaderView, val document: ReaderDocument, val url: String, val dom: String)

    private class Fixture(mode: ReadingMode, atEnd: Boolean = false, pending: List<ReaderStyle> = emptyList(),
        initialZoom: Int = 100) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var host: ReaderPageHost
        var state = documentState(mode, atEnd)
        var zoom = initialZoom
        var style = ReaderStyle()
        var status = ReaderPageStatus()
        var statusReports = 0
        var imageRequests = 0
        val paints = mutableListOf<Paint>()
        val probes = mutableListOf<ScrollBookLayout>()
        val reader: OfflineReaderView get() = requireNotNull(host.currentView ?: host.getChildAt(0) as? OfflineReaderView)

        init {
            scenario.onActivity { activity ->
                host = ReaderPageHost(activity)
                val root = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                root.addView(host, FrameLayout.LayoutParams(
                    minOf((360 * density).roundToInt(), activity.resources.displayMetrics.widthPixels),
                    (360 * density).roundToInt()))
                activity.setContentView(root)
                host.onStatusChanged = { status = it; statusReports++; assertTrue(host.childCount <= 2) }
                host.onNextUnit = { fail("Style must not request database/next-resource loading") }
                host.onPreviousUnit = { fail("Style must not request database/previous-resource loading") }
                host.onBookAnchor = { record ->
                    state = state.copy(location = state.location.copy(position = record), enterAtEnd = false)
                    host.submit(state, zoom) { imageRequests++; null }
                }
                host.setReaderStyle(style)
                host.submit(state, zoom) { imageRequests++; null }
                val view = host.getChildAt(0) as OfflineReaderView
                assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, view.layoutParams.width)
                assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, view.layoutParams.height)
                watchPaint(view)
                pending.forEach { applyStyle(it); assertSame(view, host.getChildAt(0)) }
            }
        }

        fun watchPaint(view: OfflineReaderView) {
            val ready = view.onRendered
            view.onRendered = {
                if (!view.isReleased) {
                    val info = view.bookPageInfo
                    paints += Paint(view.settings.textZoom, view.isReaderStylePending,
                        if (info != null) info.page == info.count - 1 else view.scrollY == view.scrollRange)
                }
                ready()
            }
        }

        fun ui(block: Fixture.() -> Unit) { scenario.onActivity { block(this) } }
        fun applyStyle(value: ReaderStyle) { style = value.normalized(); host.setReaderStyle(value) }
        fun submitZoom(value: Int) { zoom = value; host.submit(state, value) { imageRequests++; null } }

        fun await(message: String, allowFailure: Boolean = false, condition: Fixture.() -> Boolean) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            ui {
                val deadline = SystemClock.uptimeMillis() + 20_000L
                val poll = object : Runnable {
                    override fun run() {
                        try {
                            check(allowFailure || !status.renderFailed) { "Native render failure: $status" }
                            if (condition()) { done.countDown(); return }
                            check(SystemClock.uptimeMillis() < deadline) { "$message: $status" }
                            host.postOnAnimation(this)
                        } catch (error: Throwable) { failure = error; done.countDown() }
                    }
                }
                poll.run()
            }
            assertTrue(message, done.await(25, TimeUnit.SECONDS))
            failure?.let { throw AssertionError(message, it) }
        }

        fun awaitReady(expectedPaints: Int = 1) = await("Latest style must paint before initial-ready") {
            host.settledView != null && !state.enterAtEnd && paints.size == expectedPaints
        }

        fun turn(forward: Boolean) {
            var target = 0
            ui { target = status.pageIndex + if (forward) 1 else -1; if (forward) host.next() else host.previous() }
            await("Native page turn") { host.settledView != null && status.pageIndex == target }
        }

        fun seek(record: Int, fraction: Double) {
            val json = JSONObject(eval(layoutScript(record)))
            ui { reader.scrollTo(0, ((json.getDouble("target") + json.getDouble("recordHeight") * fraction) *
                reader.nativeContentScale).roundToInt()) }
        }

        fun awaitLayout(record: Int, expected: ReaderStyle, expectedZoom: Int = zoom): JSONObject {
            var result: JSONObject? = null
            var failure: Throwable? = null
            var inFlight = false
            var previous: String? = null
            var since = SystemClock.uptimeMillis()
            await("Stable record $record at $expected / zoom $expectedZoom") {
                failure?.let { throw it }
                if (result != null) return@await true
                val view = host.settledView
                if (!inFlight && view != null && view.settledReadingRecord == record) {
                    inFlight = true
                    view.evaluateJavascript(layoutScript(record)) receive@{ raw ->
                        inFlight = false
                        if (view.isReleased) return@receive
                        try {
                            val json = JSONObject(raw)
                            val scale = view.nativeContentScale
                            val pitch = json.getDouble("pitch")
                            val info = view.bookPageInfo
                            val geometry = if (info != null) info.anchorRecord == record &&
                                info.count == ceil((json.getDouble("extent") - 1).coerceAtLeast(pitch) / pitch).toInt() &&
                                info.nativeOffset == (info.page * pitch * scale).roundToInt() &&
                                view.scrollX == info.nativeOffset && view.scrollY == 0 &&
                                abs(json.getDouble("x") * scale - view.scrollX) < 2 &&
                                status.pageCount == info.count && status.referenceRecord == info.referenceRecord
                            else view.readingAnchor == record && view.scrollX == 0 &&
                                abs(json.getDouble("y") * scale - view.scrollY) < 2 &&
                                status.referenceRecord == view.readingReference
                            val factor = expected.normalized().lineSpacing / 100.0
                            val valid = !view.isReaderStylePending && json.getBoolean("visible") && geometry &&
                                json.getString("fonts") == "loaded" && view.settings.textZoom == expectedZoom &&
                                abs(json.getDouble("font") - 20.0 * expectedZoom / 100) < .1 &&
                                abs(json.getDouble("line") / json.getDouble("font") - 1.3 * factor) < .01 &&
                                abs(json.getDouble("devaLine") / json.getDouble("devaFont") - 1.4 * factor) < .01 &&
                                abs(json.getDouble("headingLine") / json.getDouble("headingFont") - 1.2 * factor) < .01 &&
                                json.getString("paper") == rgb(expected.palette.Paper) &&
                                json.getString("ink") == rgb(expected.palette.Ink) &&
                                json.getString("sourceScript") == "undefined" && json.getInt("styleCount") == 1 &&
                                json.getString("csp") == BookPagination.CSP &&
                                abs(json.getDouble("dpr") * json.getDouble("visualScale") - scale) <= .00001
                            val key = "$raw/${view.scrollX}/${view.scrollY}/${status.anchorRecord}/${status.pageCount}"
                            val now = SystemClock.uptimeMillis()
                            if (!valid || key != previous) { previous = key; since = now }
                            if (valid && now - since >= 200L) { assertRestrictions(view); result = json }
                        } catch (error: Throwable) { failure = error }
                    }
                }
                false
            }
            fence()
            return requireNotNull(result)
        }

        fun identity(): Identity {
            val dom = eval(DOM_WITHOUT_STYLE)
            var identity: Identity? = null
            ui { identity = Identity(reader, requireNotNull(state.document), requireNotNull(reader.url), dom) }
            return requireNotNull(identity)
        }

        fun assertRetained(identity: Identity) {
            ui {
                assertSame(identity.view, reader)
                assertSame(identity.document, state.document)
                assertSame(identity.document.html, state.document!!.html)
                assertEquals(identity.url, reader.url)
                assertEquals(1, host.childCount)
                assertEquals(1, paints.size)
                assertEquals(0, imageRequests)
            }
            assertEquals("Only the app-owned style may change, not source DOM", identity.dom, eval(DOM_WITHOUT_STYLE))
        }

        fun eval(script: String): String {
            val done = CountDownLatch(1)
            var result: String? = null
            ui { reader.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        fun fence() {
            val done = CountDownLatch(1)
            ui {
                reader.postVisualStateCallback(901L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { done.countDown() }
                })
                reader.invalidate()
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
        }

        fun frames(count: Int) {
            val done = CountDownLatch(count)
            ui {
                val frame = object : Runnable {
                    override fun run() { done.countDown(); if (done.count > 0) host.postOnAnimation(this) }
                }
                host.postOnAnimation(frame)
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
        }

        override fun close() {
            try { ui { probes.forEach { it.stop() }; host.release() } } finally { scenario.close() }
        }
    }

    companion object {
        private const val MIDDLE = 25
        private const val LAST = 39
        private const val DOM_WITHOUT_STYLE = """
            (function(){var clone=document.documentElement.cloneNode(true);
                clone.querySelector('#reader-live-style').textContent='';return clone.outerHTML;})()
        """

        private fun assertRestrictions(view: OfflineReaderView) {
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.domStorageEnabled)
            assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
            @Suppress("DEPRECATION")
            assertFalse(view.settings.databaseEnabled)
        }

        private fun rgb(hex: String): String = Color.parseColor(hex).let {
            "rgb(${Color.red(it)}, ${Color.green(it)}, ${Color.blue(it)})"
        }

        private fun documentState(mode: ReadingMode, atEnd: Boolean): ReaderState {
            val root = ContentNode(1, 0, "Synthetic style work", LAST, 1, 1)
            val chapter = ContentNode(10, root.record, "Synthetic style chapter", LAST, 2, 2)
            val opening = root.copy(end = 9)
            val entries = listOf(BookEntry(opening, root, ReadingUnitKind.SECTION, "")) + List(2) { index ->
                val node = ContentNode(10 + index * 15, chapter.record, "Synthetic unit $index", 24 + index * 15, 0, 3)
                BookEntry(node, node, ReadingUnitKind.VERSE, chapter.title)
            }
            val position = BookPosition(BookOutline(root, entries), 2)
            val paragraphs = (10..LAST).map { record ->
                when (record) {
                    10 -> Paragraph(record, "Synthetic chapter", "PA_Chapter", "LE_Chapter")
                    11 -> Paragraph(record, "नमूना हरे कृष्ण", "PA_DevaNagariQ", "")
                    else -> Paragraph(record, "Synthetic record $record नमूना śabda. " +
                        "Original synthetic prose retains its paragraph and fragment through reader appearance changes. "
                            .repeat(if (record == MIDDLE || record == LAST) 64 else 2), "PA_Purport", "")
                }
            }
            val section = ReadingSection(chapter, ReadingUnitKind.CHAPTER, root.title, paragraphs, previous = opening)
            val anchor = if (atEnd) LAST else MIDDLE
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true,
                scrollingBook = mode == ReadingMode.VERSE)
                .replace("</head>", "<script>window.__readerSourceRan=1;</script></head>")
            val document = ReaderDocument(section, html, position, BookResource(section, position, 1, 2, anchor))
            return ReaderState(chapter.location("synthetic-style").copy(position = anchor), "en", document,
                loading = false, mode = mode, enterAtEnd = atEnd)
        }

        private fun layoutScript(record: Int): String = """
            (function(){var e=document.getElementById('record-$record'),v=visualViewport,r=e.getBoundingClientRect();
                var s=getComputedStyle(e.querySelector('.folio-block')),body=getComputedStyle(document.body),
                    deva=getComputedStyle(document.querySelector('#record-11 .folio-block')),
                    heading=getComputedStyle(document.querySelector('#record-10 .folio-block')),
                    flow=document.querySelector('.book-flow'),root=document.scrollingElement,boxes=Array.from(e.getClientRects());
                function visible(b){return b.width>0&&b.height>0&&b.right-v.offsetLeft>.5&&b.left-v.offsetLeft<v.width-.5&&
                    b.bottom-v.offsetTop>.5&&b.top-v.offsetTop<v.height-.5;}
                var fragment=boxes.findIndex(visible);
                return {font:parseFloat(s.fontSize),line:parseFloat(s.lineHeight),fonts:document.fonts.status,
                    devaFont:parseFloat(deva.fontSize),devaLine:parseFloat(deva.lineHeight),
                    headingFont:parseFloat(heading.fontSize),headingLine:parseFloat(heading.lineHeight),
                    paper:body.backgroundColor,ink:s.color,texture:getComputedStyle(document.documentElement).backgroundImage,
                    csp:document.querySelector('meta[http-equiv="Content-Security-Policy"]').content,
                    sourceScript:typeof window.__readerSourceRan,styleCount:document.querySelectorAll('#reader-live-style').length,
                    dpr:devicePixelRatio,visualScale:v.scale,pitch:flow.getBoundingClientRect().width,
                    x:v.pageLeft,y:v.pageTop,target:r.top-v.offsetTop+v.pageTop,recordHeight:r.height,
                    fraction:Math.max(0,Math.min(1,(v.offsetTop-r.top)/r.height)),
                    fragments:boxes.length,fragmentFraction:Math.max(0,fragment)/Math.max(1,boxes.length),
                    extent:Math.max(root.scrollWidth,document.documentElement.scrollWidth,document.body.scrollWidth,v.width),
                    visible:fragment>=0};})()
        """.trimIndent()
    }
}