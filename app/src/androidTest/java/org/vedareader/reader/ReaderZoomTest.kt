package org.vedareader.reader

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
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Direct native host, synthetic resources and production Folio CSS/fonts. No controller, database,
 * preference writes, Compose screen, reloads or JS bridges. Bounded, single-flight DOM inspections.
 */
@RunWith(AndroidJUnit4::class)
class ReaderZoomTest {
    @Test
    fun pagedZoomEnlargesAndShrinksNativeFontAndColumnsKeepingTheMiddleRecordAndDocument() {
        DirectHost(ReadingMode.BOOK, pendingZooms = listOf(125, 80, 100)).use { fixture ->
            fixture.awaitReady()
            // Get beyond the first fragment, whose column may also contain the preceding record.
            repeat(2) { fixture.turn(forward = true) }
            fixture.awaitLayout(MIDDLE, 100)
            val retained = fixture.identity()
            var originalCount = 0
            fixture.ui { originalCount = status.pageCount; assertTrue(status.pageIndex > 0) }

            fixture.ui { submitZoom(125) }
            val enlarged = fixture.awaitLayout(MIDDLE, 125)
            assertEquals(25.0, enlarged.getDouble("font"), .1)
            fixture.ui {
                assertTrue(status.pageCount > originalCount)
                assertTrue(status.pageIndex > 0)
                assertEquals(0, view.scrollY)
            }
            fixture.assertRetained(retained)

            fixture.ui { submitZoom(80) }
            val smaller = fixture.awaitLayout(MIDDLE, 80)
            assertEquals(16.0, smaller.getDouble("font"), .1)
            fixture.ui {
                assertTrue(status.pageCount < originalCount)
                assertTrue(status.pageIndex > 0)
                assertEquals(MIDDLE, status.anchorRecord)
            }
            fixture.assertRetained(retained)
        }
    }

    @Test
    fun scrollingZoomKeepsMiddleParagraphFractionRatherThanItsOldAbsoluteOffset() {
        DirectHost(ReadingMode.VERSE, pendingZooms = listOf(125, 80, 100)).use { fixture ->
            fixture.awaitReady()
            fixture.seek(MIDDLE, .37)
            val before = fixture.awaitLayout(MIDDLE, 100)
            val fraction = before.getDouble("fraction")
            assertEquals(.37, fraction, .01)
            val retained = fixture.identity()
            var originalOffset = 0
            fixture.ui { originalOffset = view.scrollY; assertTrue(originalOffset > view.viewportHeight) }

            fixture.ui { submitZoom(125) }
            val enlarged = fixture.awaitLayout(MIDDLE, 125)
            assertEquals(25.0, enlarged.getDouble("font"), .1)
            assertEquals(fraction, enlarged.getDouble("fraction"), .02)
            fixture.ui {
                assertTrue(view.scrollY > originalOffset)
                assertEquals(MIDDLE, view.readingAnchor)
                assertEquals(0, status.pageCount)
                assertNull(view.bookPageInfo)
            }
            fixture.assertRetained(retained)

            fixture.ui { submitZoom(80) }
            val smaller = fixture.awaitLayout(MIDDLE, 80)
            assertEquals(16.0, smaller.getDouble("font"), .1)
            assertEquals(fraction, smaller.getDouble("fraction"), .02)
            fixture.ui {
                assertTrue(view.scrollY in 1 until originalOffset)
                assertEquals(MIDDLE, status.anchorRecord)
                assertEquals(0, view.scrollX)
            }
            fixture.assertRetained(retained)
        }
    }

    @Test
    fun rapidZoomsRetainPendingInitialIntentThenCurrentAnchorAndReleaseInvalidatesStaleWork() {
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE)) {
            // All these submissions happen before the first main-frame callback, on one UI turn.
            DirectHost(mode, atEnd = true, pendingZooms = listOf(125, 80, 150)).use { fixture ->
                fixture.awaitReady()
                fixture.awaitLayout(LAST, 150)
                val retained = fixture.identity()
                fixture.ui {
                    assertEquals(1, paints.size)
                    assertEquals(150, paints.single().zoom)
                    assertTrue("Initial end must paint before onRendered", paints.single().atEnd)
                    assertFalse(state.enterAtEnd)
                    if (mode == ReadingMode.BOOK) assertEquals(status.pageCount - 1, status.pageIndex)
                    else assertEquals(view.scrollRange, view.scrollY)
                }

                // Leave the initial end before zooming: the old Session.atEnd must never be reused.
                val record = if (mode == ReadingMode.BOOK) LAST else MIDDLE
                if (mode == ReadingMode.BOOK) repeat(2) { fixture.turn(forward = false) }
                else fixture.seek(record, .37)
                val before = fixture.awaitLayout(record, 150)
                var page = 0
                var offset = 0
                fixture.ui {
                    page = status.pageIndex
                    offset = view.scrollY
                    listOf(125, 80, 140, 90, 150).forEach { submitZoom(it) }
                }
                val after = fixture.awaitLayout(record, 150)
                fixture.ui {
                    assertEquals(150, view.settings.textZoom)
                    if (mode == ReadingMode.BOOK) {
                        assertEquals(page, status.pageIndex)
                        assertTrue(status.pageIndex < status.pageCount - 1)
                    } else {
                        assertEquals(offset.toDouble(), view.scrollY.toDouble(), 2.0)
                        assertTrue(view.scrollY in 1 until view.scrollRange)
                    }
                }
                if (mode == ReadingMode.VERSE) {
                    assertEquals(before.getDouble("fraction"), after.getDouble("fraction"), .02)
                }
                fixture.assertRetained(retained)
                var reports = 0
                fixture.ui { reports = statusReports; repeat(10) { submitZoom(150) } }
                fixture.awaitLayout(record, 150)
                fixture.ui { assertEquals(reports, statusReports); assertEquals(1, paints.size) }

                // Exercise the scrolling reflow's single-flight slot with real native measurements,
                // holding only callback delivery. A stale result must not publish or fail a new zoom.
                var probe: ScrollBookLayout? = null
                val held = mutableListOf<(String) -> Unit>()
                var hold = false
                var queries = 0
                var ready = 0
                var changes = 0
                var failures = 0
                if (mode == ReadingMode.VERSE) {
                    fixture.ui {
                        val reader = view
                        probe = ScrollBookLayout(reader) { script, receive ->
                            queries++
                            if (hold) held += receive
                            else reader.evaluateJavascript(script) { receive(it) }
                        }
                        probes += requireNotNull(probe)
                        probe!!.start(null, reader.scrollY, false, { ready++ }, { changes++ }, { failures++ })
                    }
                    fixture.await("Probe must settle its native layout") { probe!!.settled && ready == 1 }
                    var settledQueries = 0
                    fixture.ui { settledQueries = queries; repeat(10) { probe!!.observe() } }
                    fixture.fence()
                    fixture.fence()
                    fixture.ui {
                        assertEquals("A settled layout must not query indefinitely", settledQueries, queries)
                        hold = true
                        probe!!.reflow()
                        submitZoom(125)
                    }
                    fixture.await("First reflow query is held") { held.size == 1 }
                    fixture.ui {
                        listOf(80, 140, 150).forEach { probe!!.reflow(); submitZoom(it) }
                    }
                    fixture.fence()
                    fixture.ui {
                        assertEquals("Rapid reflows cannot overlap an outstanding query", 1, held.size)
                        held[0]("{\"error\":true}")
                    }
                    fixture.await("Only the latest work may query after stale delivery") { held.size == 2 }
                    fixture.ui { assertEquals(1, ready); assertEquals(0, failures) }
                }

                var notifications = 0
                var hostReports = 0
                fixture.ui {
                    val reader = view
                    val staleReady = reader.onRendered
                    val staleAnchor = reader.onReadingAnchor
                    val stalePage = reader.onBookPageChanged
                    val staleFailure = reader.onRenderFailed
                    val info = reader.bookPageInfo
                    // Paged refresh begins immediately; scrolling has an explicitly held query above.
                    submitZoom(125)
                    notifications = ready + changes + failures
                    hostReports = statusReports
                    probe?.stop()
                    host.release()
                    held.getOrNull(1)?.invoke("{\"error\":true}")
                    reader.setReaderTextZoom(80) // Released views must not even touch WebSettings.
                    staleReady(); staleAnchor(record); info?.let { stalePage(it) }; staleFailure()
                    assertTrue(reader.isReleased)
                }
                fixture.frames(3) // Drain posted host failures/retries without accessing a destroyed WebView.
                fixture.ui {
                    assertEquals(notifications, ready + changes + failures)
                    assertEquals(hostReports, statusReports)
                    assertEquals(1, paints.size)
                    assertEquals(0, host.childCount)
                    assertNull(host.currentView)
                    if (mode == ReadingMode.VERSE) assertEquals(2, held.size)
                }
            }
        }
    }

    private data class Paint(val zoom: Int, val atEnd: Boolean)
    private data class Identity(
        val view: OfflineReaderView, val document: ReaderDocument, val url: String,
        val body: String, val head: String, val style: String,
    )

    private class DirectHost(mode: ReadingMode, atEnd: Boolean = false, pendingZooms: List<Int> = emptyList()) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var host: ReaderPageHost
        var state = documentState(mode, atEnd)
        var zoom = 100
        var status = ReaderPageStatus()
        var statusReports = 0
        val paints = mutableListOf<Paint>()
        val probes = mutableListOf<ScrollBookLayout>()
        val view: OfflineReaderView get() = requireNotNull(host.settledView)

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
                host.onNextUnit = { fail("Zoom must not request another resource") }
                host.onPreviousUnit = { fail("Zoom must not request another resource") }
                host.onBookAnchor = { record ->
                    state = state.copy(location = state.location.copy(position = record), enterAtEnd = false)
                    host.submit(state, zoom) { null } // Acknowledge initial end with the same document.
                }
                submitZoom(100)
                val reader = host.getChildAt(0) as OfflineReaderView
                val ready = reader.onRendered
                reader.onRendered = {
                    if (!reader.isReleased) {
                        val info = reader.bookPageInfo
                        paints += Paint(reader.settings.textZoom, if (info != null) info.page == info.count - 1
                            else reader.scrollY == reader.scrollRange)
                    }
                    ready()
                }
                pendingZooms.forEach {
                    submitZoom(it)
                    assertTrue(host.isBusy)
                    assertSame(reader, host.getChildAt(0))
                    assertEquals(1, host.childCount)
                }
            }
        }

        fun ui(block: DirectHost.() -> Unit) { scenario.onActivity { block(this) } }
        fun submitZoom(value: Int) { zoom = value; host.submit(state, value) { null } }

        fun await(message: String, condition: DirectHost.() -> Boolean) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            ui {
                val deadline = SystemClock.uptimeMillis() + 20_000L
                val poll = object : Runnable {
                    override fun run() {
                        try {
                            check(!status.renderFailed) { "Native render failed: $message" }
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

        fun awaitReady() = await("Host must paint and acknowledge the initial anchor") {
            host.settledView != null && !state.enterAtEnd && paints.size == 1
        }

        fun turn(forward: Boolean) {
            var target = 0
            ui { target = status.pageIndex + if (forward) 1 else -1; if (forward) host.next() else host.previous() }
            await("Native page turn") { host.settledView != null && status.pageIndex == target }
        }

        fun seek(record: Int, fraction: Double) {
            val layout = JSONObject(eval(layoutScript(record)))
            ui {
                val css = layout.getDouble("target") + layout.getDouble("recordHeight") * fraction
                view.scrollTo(0, (css * view.nativeContentScale).roundToInt())
            }
        }

        /** Require actual rendered CSS font, visible record, native/DOM agreement and quiet geometry;
         * settings.textZoom alone, or a host that was already ready before zoom, proves nothing.
         */
        fun awaitLayout(record: Int, expectedZoom: Int): JSONObject {
            var result: JSONObject? = null
            var failure: Throwable? = null
            var inFlight = false
            var previous: String? = null
            var since = SystemClock.uptimeMillis()
            await("Record $record must stay visible at zoom $expectedZoom") {
                failure?.let { throw it }
                if (result != null) return@await true
                val reader = host.settledView
                if (!inFlight && reader != null) {
                    inFlight = true
                    reader.evaluateJavascript(layoutScript(record)) receive@{ raw ->
                        inFlight = false
                        if (reader.isReleased) return@receive
                        try {
                            val json = JSONObject(raw)
                            val width = json.getDouble("width")
                            val pitch = json.getDouble("pitch")
                            val scale = reader.nativeContentScale
                            val scaleAgrees = scale.isFinite() && scale > 0 &&
                                abs(json.getDouble("dpr") * json.getDouble("visualScale") - scale) <= .00001 &&
                                abs(reader.width / scale - width) <= 1.0
                            val info = reader.bookPageInfo
                            val geometry = if (info != null) {
                                pitch > 0 && info.anchorRecord == record &&
                                    info.count == ceil((json.getDouble("extent") - 1.0).coerceAtLeast(pitch) / pitch).toInt() &&
                                    info.viewportWidth == (pitch * scale).roundToInt() &&
                                    info.nativeOffset == (info.page * pitch * scale).roundToInt() &&
                                    reader.scrollX == info.nativeOffset && reader.scrollY == 0 &&
                                    abs(json.getDouble("x") - info.page * pitch) <= 1.0 &&
                                    abs(json.getDouble("x") * scale - reader.scrollX) < 2 &&
                                    abs(json.getDouble("y")) <= .01
                            } else reader.readingAnchor == record && reader.scrollX == 0 &&
                                abs(json.getDouble("y") * scale - reader.scrollY) < 2
                            val valid = !reader.isReaderStylePending &&
                                json.getBoolean("visible") && json.getString("fonts") == "loaded" &&
                                abs(json.getDouble("font") - 20.0 * expectedZoom / 100) < .1 &&
                                reader.settings.textZoom == expectedZoom && scaleAgrees && geometry
                            val key = "$raw/$scale/${reader.scrollX}/${reader.scrollY}/${status.anchorRecord}/${status.pageCount}"
                            val now = SystemClock.uptimeMillis()
                            if (!valid || key != previous) { previous = key; since = now }
                            if (valid && now - since >= 200L) result = json
                        } catch (error: Throwable) { failure = error }
                    }
                }
                false
            }
            fence()
            return requireNotNull(result)
        }

        fun identity(): Identity {
            val dom = domSnapshot()
            var identity: Identity? = null
            ui { identity = Identity(view, requireNotNull(state.document), requireNotNull(view.url),
                dom.getString("body"), dom.getString("head"), dom.getString("style")) }
            return requireNotNull(identity)
        }

        fun assertRetained(identity: Identity) {
            ui {
                assertSame(identity.view, view)
                assertSame(identity.document, state.document)
                assertSame(identity.document.html, state.document!!.html)
                assertEquals(identity.url, view.url)
                assertEquals(1, host.childCount)
                assertEquals("Zoom is not another initial render", 1, paints.size)
            }
            val dom = domSnapshot()
            assertEquals("Native zoom must preserve body markup, Unicode, formatting and record anchors",
                identity.body, dom.getString("body"))
            assertEquals("Native zoom must preserve CSP, fonts and structural head styles",
                identity.head, dom.getString("head"))
            // These are zoom-only tests: paper/spacing stay fixed. Paper-change tests must
            // compare the body and immutable head separately, not freeze the live style text.
            assertEquals("Zoom alone must not change the selected appearance", identity.style, dom.getString("style"))
        }

        private fun domSnapshot(): JSONObject {
            val snapshot = JSONObject(eval("""
                (function(){var styles=document.head.querySelectorAll('style#reader-live-style');
                    if(styles.length!==1)return {error:'Expected exactly one live head style'};
                    var head=document.head.cloneNode(true);
                    head.querySelector('style#reader-live-style').textContent='';
                    return {body:document.body.outerHTML,head:head.outerHTML,style:styles[0].textContent};})()
            """.trimIndent()))
            assertFalse(snapshot.toString(), snapshot.has("error"))
            return snapshot
        }

        fun eval(script: String): String {
            val done = CountDownLatch(1)
            var result: String? = null
            ui { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("Read-only native DOM query", done.await(20, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        fun fence() {
            val done = CountDownLatch(1)
            ui {
                view.postVisualStateCallback(900L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { done.countDown() }
                })
                view.invalidate()
            }
            assertTrue("Native visual fence", done.await(20, TimeUnit.SECONDS))
        }

        fun frames(count: Int) {
            val done = CountDownLatch(count)
            ui {
                val frame = object : Runnable {
                    override fun run() { done.countDown(); if (done.count > 0) host.postOnAnimation(this) }
                }
                host.postOnAnimation(frame)
            }
            assertTrue("Drain released callbacks", done.await(20, TimeUnit.SECONDS))
        }

        override fun close() {
            try { ui { probes.forEach { it.stop() }; host.release() } } finally { scenario.close() }
        }
    }

    companion object {
        private const val MIDDLE = 125
        private const val LAST = 139

        private fun documentState(mode: ReadingMode, atEnd: Boolean): ReaderState {
            val root = ContentNode(100, 0, "Synthetic zoom work", LAST, 1, 1)
            val chapter = ContentNode(100, root.record, "Synthetic zoom chapter", LAST, 2, 2)
            val entries = List(2) { index ->
                val node = ContentNode(100 + index * 20, chapter.record, "Synthetic text $index", 119 + index * 20, 0, 3)
                BookEntry(node, node, ReadingUnitKind.VERSE, chapter.title)
            }
            val position = BookPosition(BookOutline(root, entries), 1)
            val paragraphs = (100..LAST).map { record ->
                Paragraph(record, "Synthetic record $record नमूना ā. " +
                    "Original synthetic prose occupies a paragraph whose reading position survives native font resizing. "
                        .repeat(if (record == MIDDLE || record == LAST) 32 else 2), "PA_Purport", "")
            }
            val section = ReadingSection(chapter, ReadingUnitKind.CHAPTER, root.title, paragraphs)
            val anchor = if (atEnd) LAST else MIDDLE
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(fontSize = 20),
                bookMode = true, scrollingBook = mode == ReadingMode.VERSE)
            val document = ReaderDocument(section, html, position, BookResource(section, position, 0, 1, anchor))
            return ReaderState(chapter.location("synthetic-zoom").copy(position = anchor), "en", document,
                loading = false, mode = mode, enterAtEnd = atEnd)
        }

        private fun layoutScript(record: Int): String = """
            (function(){var e=document.getElementById('record-$record'),v=visualViewport,r=e.getBoundingClientRect();
                var s=getComputedStyle(e.querySelector('.folio-block')),root=document.scrollingElement;
                return {font:parseFloat(s.fontSize),fonts:document.fonts.status,width:v.width,height:v.height,
                    pitch:document.querySelector('.book-flow').getBoundingClientRect().width,
                    dpr:devicePixelRatio,visualScale:v.scale,
                    x:v.pageLeft,y:v.pageTop,target:r.top-v.offsetTop+v.pageTop,recordHeight:r.height,
                    fraction:Math.max(0,Math.min(1,(v.offsetTop-r.top)/r.height)),
                    extent:Math.max(root.scrollWidth,document.documentElement.scrollWidth,document.body.scrollWidth,v.width),
                    visible:Array.from(e.getClientRects()).some(function(b){return b.width>0&&b.height>0&&
                        b.right-v.offsetLeft>.5&&b.left-v.offsetLeft<v.width-.5&&
                        b.bottom-v.offsetTop>.5&&b.top-v.offsetTop<v.height-.5;})};})()
        """.trimIndent()
    }
}