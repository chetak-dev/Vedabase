package org.vedareader.reader

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic grouped resources only. Production Folio CSS/fonts, controller and native host.
 * No database, screenshots, JS bridges, network, settings changes or animation-duration sleeps.
 */
@RunWith(AndroidJUnit4::class)
class ScrollingBookTest {
    @Test
    fun opensMiddleSourceRecordAfterFontsAndOffsetHavePainted() {
        Fixture(initialAnchor = 112).use { fixture ->
            fixture.awaitReady(0)
            fixture.fence()
            val layout = fixture.json("""
                (function(){var v=visualViewport,e=document.getElementById('record-112'),r=e.getBoundingClientRect();
                    var f=document.querySelector('main.book-flow'),s=getComputedStyle(f);
                    return {fonts:document.fonts.status,width:v.width,y:v.pageTop,target:r.top-v.offsetTop+v.pageTop,
                        visible:r.bottom-v.offsetTop>0&&r.top-v.offsetTop<v.height,
                        columns:s.columnCount,columnWidth:s.columnWidth,flowHeight:f.getBoundingClientRect().height,
                        height:v.height,scrolling:document.body.classList.contains('book-scrolling')};})()
            """)
            assertEquals("loaded", layout.getString("fonts"))
            assertTrue(layout.getBoolean("scrolling"))
            assertTrue(layout.getBoolean("visible"))
            assertEquals("auto", layout.getString("columns"))
            assertEquals("auto", layout.getString("columnWidth"))
            assertTrue(layout.getDouble("flowHeight") > layout.getDouble("height"))
            fixture.ui {
                assertScrollMode()
                assertEquals(112, requireNotNull(view.initialBookAnchor))
                assertTrue(view.scrollY > view.viewportHeight)
                val scale = view.width / layout.getDouble("width")
                assertEquals(layout.getDouble("target") * scale, view.scrollY.toDouble(), 2.0)
                assertEquals(layout.getDouble("y") * scale, view.scrollY.toDouble(), 2.0)
                assertEquals(view.scrollY, paints.single().offset)
                assertEquals(view.readingAnchor, state!!.location.position)
                assertTrue(state!!.document!!.section.paragraphs.any { it.record == view.readingAnchor })
                assertEquals(1, loads)
                assertEquals(0, nextCalls + previousCalls)
                assertFalse(state!!.enterAtEnd)
            }
        }
    }

    @Test
    fun verticalScrollPublishesVisibleSourceWithoutReloadAndHorizontalSwipesAreIgnored() {
        Fixture().use { fixture ->
            fixture.awaitReady(0)
            lateinit var retained: OfflineReaderView
            lateinit var document: ReaderDocument
            var initial = 0
            fixture.ui {
                retained = view
                document = state!!.document!!
                initial = view.readingAnchor
                gesture(forward = true)
            }
            fixture.await("Vertical scroll must update the source anchor") {
                host.settledView === retained && view.scrollY > 0 && view.readingAnchor > initial &&
                    state!!.location.position == view.readingAnchor
            }
            fixture.quiet()
            fixture.ui {
                val anchor = state!!.location.position
                view.onScrollPosition(view.scrollY + 1)
                assertEquals("Pixel offsets must not overwrite source position", anchor, state!!.location.position)
                gesture(forward = true, horizontal = true)
                gesture(forward = false, horizontal = true)
                view.scrollTo(500, view.scrollY)
                view.onReaderTap()
            }
            fixture.fence()
            fixture.ui {
                assertSame(retained, view)
                assertSame(document, state!!.document)
                assertEquals(1, host.childCount)
                assertEquals(1, loads)
                assertEquals(0, view.scrollX)
                assertEquals(0, nextCalls + previousCalls)
                assertEquals("The explicit reader-tap callback is now forwarded in scrolling mode", 1, taps)
                assertNull(view.bookPageInfo)
                assertEquals(0, status.pageCount)
                assertEquals(view.readingAnchor, status.anchorRecord)
                assertFalse(view.turnBookPage(true) { fail("Scroll mode cannot turn a column") })
            }
            val visible = fixture.json("""
                (function(){var v=visualViewport;return {record:Array.from(document.querySelectorAll('.record[data-record]'))
                    .filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&
                        r.bottom-v.offsetTop>.5&&r.top-v.offsetTop<v.height-.5;})[0].dataset.record};})()
            """)
            fixture.ui { assertEquals(visible.getInt("record"), view.readingAnchor) }
        }
    }

    @Test
    fun onlyFreshOutwardBoundaryGestureNavigatesAndPreviousPaintsEndBeforeAcknowledgement() {
        Fixture(initialIndex = 1).use { fixture ->
            fixture.awaitReady(1)
            // Reaching the bottom during a drag (or its fling) is not a next-resource request.
            fixture.ui { view.scrollTo(0, view.scrollRange / 2) }
            fixture.quiet()
            fixture.ui { gesture(true, beforeUp = { view.scrollTo(0, view.scrollRange) }) }
            fixture.quiet()
            fixture.ui { assertEquals(0, nextCalls + previousCalls); assertEquals(1, loads) }
            val pending = CompletableDeferred<Unit>()
            fixture.ui {
                pendingLoad = pending
                gesture(true)
                repeat(4) { host.next(); host.previous(); gesture(true) }
                assertTrue(host.isBusy)
                assertEquals(1, nextCalls)
                assertEquals(2, loads)
                pendingLoad = null
                pending.complete(Unit)
            }
            fixture.awaitReady(2)
            fixture.ui {
                // Anchor alignment includes the document's top margin; zero pixels is not
                // necessarily the first source heading's position.
                assertEquals(state!!.location.start, view.readingAnchor)
                assertTrue(view.scrollY in 0 until view.viewportHeight)
            }
            // No neighbour at the actual book end; layout and an outward swipe do nothing.
            fixture.ui { view.scrollTo(0, view.scrollRange) }
            fixture.quiet()
            fixture.ui { gesture(true); assertEquals(1, nextCalls) }
            fixture.quiet()
            // Dispatching a synthetic UP and teleporting in the same UI callback races
            // Chromium's queued native touch processing. Settle that gesture first.
            fixture.ui { view.scrollTo(0, 0) }
            fixture.quiet()
            fixture.ui { assertEquals(0, view.scrollY); gesture(false) }
            fixture.awaitReady(1)
            fixture.fence()
            fixture.ui {
                assertScrollMode()
                assertEquals(view.scrollRange, view.scrollY)
                assertEquals(view.scrollRange, paints.last().offset)
                assertTrue(paints.last().atEnd)
                assertFalse(state!!.enterAtEnd)
                assertEquals(view.readingAnchor, state!!.location.position)
                assertEquals(1, previousCalls)
                assertEquals(3, loads)
                assertEquals(1, host.childCount)
                view.scrollTo(0, view.scrollRange / 2)
            }
            fixture.quiet()
            fixture.ui { gesture(false, beforeUp = { view.scrollTo(0, 0) }) }
            fixture.quiet()
            fixture.ui { assertEquals(1, previousCalls); gesture(false) }
            fixture.awaitReady(0)
            fixture.ui {
                assertEquals(2, previousCalls)
                assertEquals(4, loads)
                assertEquals(view.scrollRange, view.scrollY)
                assertFalse(state!!.enterAtEnd)
            }
        }
    }

    @Test
    fun modeKeyChangesButLiveAnchorDoesNotAndBothLayoutsKeepTheSameBookTypography() {
        Fixture(initialAnchor = 112).use { fixture ->
            fixture.awaitReady(0)
            val style = fixture.eval(TYPOGRAPHY)
            lateinit var scrolling: OfflineReaderView
            var selected = 0
            fixture.ui {
                scrolling = view
                selected = view.readingAnchor
                val document = state!!.document
                controller.saveBookAnchor(113)
                assertSame(scrolling, host.settledView)
                assertSame(document, state!!.document)
                assertEquals(1, loads)
                controller.saveBookAnchor(selected)
                // Isolate the mode key: no new document identity/loading phase can hide it.
                host.submit(state!!.copy(mode = ReadingMode.BOOK), 100) { null }
                assertTrue(host.isBusy)
                assertEquals(2, host.childCount)
                val incoming = (0 until host.childCount).map { host.getChildAt(it) }
                    .filterIsInstance<OfflineReaderView>().single { it !== scrolling }
                assertTrue(incoming.paginatedBook)
                assertFalse(incoming.scrollingBook)
                // Replace the transient same-HTML request with the controller's proper paged HTML.
                controller.toggleMode()
            }
            fixture.awaitReady(0)
            lateinit var paged: OfflineReaderView
            fixture.ui {
                paged = view
                assertNotSame(scrolling, paged)
                assertTrue(scrolling.isReleased)
                assertEquals(ReadingMode.BOOK, state!!.mode)
                assertFalse(view.scrollingBook)
                assertTrue(view.paginatedBook)
                assertTrue(view.settings.javaScriptEnabled)
                assertEquals(0, view.scrollY)
                assertTrue(status.pageCount > 1)
                assertEquals(selected, requireNotNull(view.initialBookAnchor))
            }
            assertEquals(style, fixture.eval(TYPOGRAPHY))
            fixture.ui { selected = view.bookPageInfo!!.anchorRecord; controller.toggleMode() }
            fixture.awaitReady(0)
            fixture.ui {
                assertNotSame(paged, view)
                assertTrue(paged.isReleased)
                assertScrollMode()
                assertEquals(selected, requireNotNull(view.initialBookAnchor))
                assertEquals(3, loads)
                assertEquals(1, host.childCount)
                // Header/accessibility next-section actions go directly to the resource neighbour,
                // even mid-resource; they must never call the paginated page-turn API.
                host.next()
            }
            fixture.awaitReady(1)
            fixture.ui { assertEquals(1, nextCalls); assertEquals(4, loads); assertScrollMode() }
        }
    }

    @Test
    fun querySlotSurvivesReplacementAndReleaseInvalidatesQueriesAndCapturedHostCallbacks() {
        Fixture().use { fixture ->
            fixture.awaitReady(0)
            fixture.quiet()
            val pending = mutableListOf<(String) -> Unit>()
            lateinit var probe: ScrollBookLayout
            var notifications = 0
            fixture.ui {
                probe = ScrollBookLayout(view) { _, receive -> pending += receive }
                probe.start(112, 0, false, { notifications++ }, { notifications++ }, { notifications++ })
            }
            fixture.await("One native query must be pending") { pending.size == 1 }
            fixture.ui {
                probe.start(113, 0, false, { notifications++ }, { notifications++ }, { notifications++ })
                repeat(20) { probe.observe() }
            }
            fixture.fence()
            fixture.ui {
                assertEquals("Replacement must not overlap the old native query", 1, pending.size)
                pending[0]("{\"error\":true}") // An old document's failure must not fail its replacement.
            }
            fixture.await("Replacement starts only after the old query returns") { pending.size == 2 }
            fixture.ui {
                val old = view
                val anchor = old.onReadingAnchor
                val ready = old.onRendered
                val failed = old.onRenderFailed
                val next = old.onNextUnit
                val reports = anchorReports.size
                val statuses = statusReports
                probe.stop()
                host.release()
                pending[1]("{\"error\":true}")
                anchor(113); ready(); failed(); next()
                assertTrue(old.isReleased)
                assertEquals(0, notifications)
                assertEquals(reports, anchorReports.size)
                assertEquals(statuses, statusReports)
                assertEquals(0, nextCalls)
                assertNull(host.currentView)
                assertEquals(0, host.childCount)
            }
        }
    }

    @Test
    fun scrollingUsesClosedNativeJsAndExactFontAllowlistWhileLegacyVerseStaysJsOff() {
        Fixture(sourceScript = true).use { fixture ->
            fixture.awaitReady(0)
            assertEquals("false", fixture.eval("typeof window.__sourceScriptRan !== 'undefined'"))
            assertEquals("\"loaded\"", fixture.eval("document.fonts.status"))
            fixture.ui {
                assertScrollMode()
                assertTrue(view.settings.javaScriptEnabled)
                assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(view.settings.domStorageEnabled)
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertTrue(view.settings.blockNetworkLoads)
                val client = view.webViewClient
                fun response(url: String, main: Boolean = false) = requireNotNull(
                    client.shouldInterceptRequest(view, Request(url, main)))
                response(requireNotNull(view.url), main = true).let {
                    try {
                        assertEquals(200, it.statusCode)
                        assertEquals(BookPagination.CSP, it.responseHeaders["Content-Security-Policy"])
                    } finally { it.data.close() }
                }
                response("https://reader.local/fonts/FiraSansCondensed-Regular.ttf").let {
                    try { assertEquals(200, it.statusCode); assertEquals("font/ttf", it.mimeType) }
                    finally { it.data.close() }
                }
                for (address in listOf("https://example.invalid/a.js", "file:///private/file", "content://private/file",
                    "https://reader.local/fonts/not-allowed.ttf", "https://reader.local/fonts/FiraSansCondensed-Regular.ttf?x=1")) {
                    response(address).let { try { assertEquals(403, it.statusCode) } finally { it.data.close() } }
                }
                response("https://reader.local/document/not-this-generation", main = true).let {
                    try { assertEquals(403, it.statusCode) } finally { it.data.close() }
                }
            }
        }
        Fixture(legacy = true).use { fixture ->
            fixture.awaitReady(0)
            fixture.ui {
                assertFalse(view.scrollingBook)
                assertFalse(view.paginatedBook)
                assertFalse(view.settings.javaScriptEnabled)
                assertNull(view.bookPageInfo)
                view.scrollTo(0, view.scrollRange / 2)
                gesture(true, horizontal = true)
            }
            fixture.awaitReady(1)
            fixture.ui { assertEquals(1, nextCalls); assertTrue(anchorReports.isEmpty()) }
        }
    }

    private data class Paint(val offset: Int, val atEnd: Boolean)

    private class Fixture(initialIndex: Int = 0, initialAnchor: Int? = null,
        private val legacy: Boolean = false, private val sourceScript: Boolean = false) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val docs = documents()
        lateinit var host: ReaderPageHost
        lateinit var controller: ReaderController
        var state: ReaderState? = null
        var status = ReaderPageStatus()
        var loads = 0
        var nextCalls = 0
        var previousCalls = 0
        var taps = 0
        var statusReports = 0
        var pendingLoad: CompletableDeferred<Unit>? = null
        val anchorReports = mutableListOf<Int>()
        val paints = mutableListOf<Paint>()
        private val observedViews = mutableSetOf<OfflineReaderView>()
        val view: OfflineReaderView get() = requireNotNull(host.settledView)

        init {
            scenario.onActivity { activity ->
                host = ReaderPageHost(activity)
                val root = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                root.addView(host, FrameLayout.LayoutParams(
                    minOf((360 * density).toInt(), activity.resources.displayMetrics.widthPixels), (360 * density).toInt()))
                activity.setContentView(root)
                host.onStatusChanged = { assertTrue(host.childCount <= 2); status = it; statusReports++ }
                controller = ReaderController(scope,
                    loadDocument = { location, _ -> load(location, scrolling = !legacy) },
                    loadBookDocument = { location, _ -> load(location, scrolling = false) },
                    onChange = { value ->
                        state = value
                        value?.let {
                            host.submit(it, 100) { null }
                            for (index in 0 until host.childCount) {
                                val reader = host.getChildAt(index) as OfflineReaderView
                                if (observedViews.add(reader)) {
                                    val ready = reader.onRendered
                                    reader.onRendered = {
                                        if (!reader.isReleased) {
                                            assertTrue(host.isBusy)
                                            assertFalse(reader.interactionEnabled)
                                            paints += Paint(reader.scrollY, state!!.enterAtEnd)
                                        }
                                        ready()
                                    }
                                }
                            }
                        }
                    })
                host.onNextUnit = { nextCalls++; controller.next() }
                host.onPreviousUnit = { previousCalls++; controller.previous() }
                host.onReaderTap = { taps++ }
                host.onBookAnchor = {
                    assertFalse(host.isBusy)
                    assertEquals(it, view.bookPageInfo?.anchorRecord ?: view.readingAnchor)
                    anchorReports += it
                    controller.saveBookAnchor(it)
                }
                host.onScrollPosition = { assertFalse(host.isBusy); controller.saveScroll(it) }
                val node = docs[initialIndex].section.node
                controller.open("synthetic-scroll", "en",
                    if (initialAnchor == null) node else node.copy(record = initialAnchor, end = initialAnchor))
            }
        }

        private suspend fun load(location: ReadingLocation, scrolling: Boolean): ReaderDocument {
            loads++
            pendingLoad?.await()
            val document = docs.first { location.position in it.section.node.record..it.section.node.end }
            val resource = requireNotNull(document.bookResource)
            val outline = resource.position.outline
            val position = BookPosition(outline, outline.entries.indexOfFirst { location.position in it.node.record..it.node.end })
            var html = FolioText.document(document.section.paragraphs, "en", ReaderPreferences(),
                bookMode = !legacy, scrollingBook = scrolling)
            if (sourceScript) html = html.replace("</head>",
                "<script>window.__sourceScriptRan=1;</script></head>")
            return document.copy(html = html, bookPosition = if (legacy) null else position,
                bookResource = if (legacy) null else resource.copy(position = position, anchorRecord = location.position))
        }

        fun ui(block: Fixture.() -> Unit) { scenario.onActivity { block(this) } }

        fun await(message: String, condition: Fixture.() -> Boolean) {
            val complete = CountDownLatch(1)
            var failure: Throwable? = null
            ui {
                val deadline = SystemClock.uptimeMillis() + 20_000L
                val check = object : Runnable {
                    override fun run() {
                        try {
                            if (status.renderFailed || state?.failed == true) error("Reader failed: $message")
                            if (condition()) { complete.countDown(); return }
                            if (SystemClock.uptimeMillis() >= deadline) error("$message; start=${state?.location?.start} " +
                                "position=${state?.location?.position} endIntent=${state?.enterAtEnd} " +
                                "busy=${host.isBusy} next=$nextCalls previous=$previousCalls loads=$loads " +
                                "scroll=${host.currentView?.scrollY}/${host.currentView?.scrollRange} " +
                                "anchor=${host.currentView?.readingAnchor}")
                            host.postOnAnimation(this)
                        } catch (error: Throwable) { failure = error; complete.countDown() }
                    }
                }
                check.run()
            }
            assertTrue(message, complete.await(25, TimeUnit.SECONDS))
            failure?.let { throw AssertionError(message, it) }
        }

        fun awaitReady(index: Int) = await("Resource $index must paint and acknowledge its anchor") {
            val reader = host.settledView
            reader != null && !status.busy && state?.loading == false && state?.enterAtEnd == false &&
                state?.location?.start == docs[index].section.node.record && (legacy ||
                    state?.location?.position == (reader.bookPageInfo?.anchorRecord ?: reader.readingAnchor))
        }

        fun quiet() {
            var previous = -1
            var since = SystemClock.uptimeMillis()
            await("Native scroll/fling must settle before a fresh boundary gesture") {
                val y = view.scrollY
                if (y != previous) { previous = y; since = SystemClock.uptimeMillis() }
                SystemClock.uptimeMillis() - since >= 200
            }
            fence()
        }

        fun fence() {
            val done = CountDownLatch(1)
            ui {
                view.postVisualStateCallback(700L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { done.countDown() }
                })
                view.invalidate()
            }
            assertTrue("Visual fence", done.await(20, TimeUnit.SECONDS))
        }

        fun eval(script: String): String {
            val done = CountDownLatch(1)
            var result: String? = null
            ui { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("Read-only native inspection", done.await(20, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        fun json(script: String) = JSONObject(eval(script))

        fun assertScrollMode() {
            assertEquals(ReadingMode.VERSE, state!!.mode)
            assertTrue(view.scrollingBook)
            assertFalse(view.paginatedBook)
            assertNull(view.bookPageInfo)
            assertEquals(0, status.pageCount)
            assertEquals(0, view.scrollX)
            assertEquals(view.readingAnchor, status.anchorRecord)
        }

        fun gesture(forward: Boolean, horizontal: Boolean = false, beforeUp: () -> Unit = {}) {
            val from = if (forward) .85f else .15f
            val to = 1f - from
            val now = SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN, from, 0L),
                Triple(MotionEvent.ACTION_MOVE, (from + to) / 2, 60L),
                Triple(MotionEvent.ACTION_MOVE, to, 120L), Triple(MotionEvent.ACTION_UP, to, 180L))
                .forEach { (action, position, elapsed) ->
                    if (action == MotionEvent.ACTION_UP) beforeUp()
                    val event = MotionEvent.obtain(now, now + elapsed, action,
                        host.width * if (horizontal) position else .5f,
                        host.height * if (horizontal) .55f else position, 0)
                    host.dispatchTouchEvent(event)
                    event.recycle()
                }
        }

        override fun close() {
            try { scenario.onActivity { host.release(); controller.close(); scope.cancel() } }
            finally { scenario.close() }
        }
    }

    private class Request(private val address: String, private val main: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    companion object {
        private val TYPOGRAPHY = """
            (function(){return [101,103,105,107].map(function(id){var e=document.querySelector('#record-'+id+' .folio-block');
                var s=getComputedStyle(e);return {text:e.textContent,family:s.fontFamily,size:s.fontSize,
                    weight:s.fontWeight,style:s.fontStyle,color:s.color,align:s.textAlign};});})()
        """.trimIndent()

        private fun documents(): List<ReaderDocument> {
            val root = ContentNode(100, 0, "Synthetic work", 699, 3, 1)
            val nodes = List(3) { ContentNode(100 + it * 200, root.record, "Synthetic chapter $it", 299 + it * 200, 2, 2) }
            val entries = nodes.flatMapIndexed { index, node -> List(2) { member ->
                val verse = ContentNode(node.record + member * 100, node.record, "Synthetic verse $index.$member",
                    node.record + member * 100 + 99, 0, 3)
                BookEntry(verse, verse, ReadingUnitKind.VERSE, node.title)
            } }
            val outline = BookOutline(root, entries)
            return nodes.mapIndexed { index, node ->
                val paragraphs = (index * 2..index * 2 + 1).flatMap { entry ->
                    val record = entries[entry].node.record
                    listOf(
                        Paragraph(record, "Bg ${index + 1}.${entry + 1}", "PA_Verse_Number_Heading", "LE_Verse_Number"),
                        Paragraph(record + 1, "नमूना ā a\u0304", "PA_DevaNagariQ", ""),
                        Paragraph(record + 2, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
                        Paragraph(record + 3, "<CS:\"Italic\">śabda</CS> — synthetic word.", "PA_Synonyms", ""),
                        Paragraph(record + 4, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
                        Paragraph(record + 5, "A complete synthetic translation.", "PA_Translation", ""),
                        Paragraph(record + 6, "PURPORT", "PA_Titles", "LE_Verse_Section"),
                    ) + List(10) { body -> Paragraph(record + 7 + body,
                        "Synthetic paragraph $body नमूना ā. " +
                            "Original prose flows through adjacent verses without loading another document. ".repeat(3),
                        "PA_Purport", "") }
                }
                val section = ReadingSection(node, ReadingUnitKind.CHAPTER, root.title, paragraphs,
                    nodes.getOrNull(index - 1), nodes.getOrNull(index + 1))
                val position = BookPosition(outline, index * 2)
                ReaderDocument(section, "", position, BookResource(section, position, index * 2, index * 2 + 1, node.record))
            }
        }
    }
}