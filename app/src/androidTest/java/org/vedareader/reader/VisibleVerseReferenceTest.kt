package org.vedareader.reader

import android.graphics.Rect
import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
import kotlin.math.roundToInt

/** Synthetic Folio source, production HTML/CSS/fonts and native host/fences only.
 * No corpus, controller changes, test CSS, JS handlers, device settings or timing sleeps.
 */
@RunWith(AndroidJUnit4::class)
class VisibleVerseReferenceTest {
    @Test
    fun pagedPurportTailKeepsItsAnchorWhileNextFullyVisibleHeadingAdvancesReference() {
        Fixture(scrolling = false, paragraphs = passage(leadLines = 12)).use { fixture ->
            fixture.awaitReady()
            fixture.ui {
                assertEquals(100, status.anchorRecord)
                assertEquals(100, status.referenceRecord)
                assertTrue(status.pageCount > 2)
            }
            fixture.turn()
            fixture.fence()
            val heading = fixture.record(200)
            assertTrue("The next verse label must actually be fully painted", heading.getBoolean("full"))
            assertEquals("TEXT 8 ( BG 2.8 )", heading.getString("label"))
            assertEquals("Original source label is hidden, not a geometry candidate", 0, heading.getInt("hiddenRects"))
            assertTrue(fixture.record(101).getBoolean("visible"))
            val before = fixture.json(BookPagination.measure(null))
            fixture.ui {
                val info = requireNotNull(view.bookPageInfo)
                assertEquals(1, info.page)
                assertEquals(101, info.anchorRecord)
                assertEquals(200, info.referenceRecord)
                assertEquals(200, view.readingReference)
                assertEquals(101, status.anchorRecord)
                assertEquals(200, status.referenceRecord)
                assertEquals("Only the purport anchor is persisted", 101, savedAnchors.last().record)
                assertEquals("BG 2.7", sourceTitle(status.anchorRecord))
                assertEquals("BG 2.8", sourceTitle(status.referenceRecord))
                assertEquals(info.nativeOffset, view.scrollX)
                assertEquals(0, view.scrollY)
            }
            // Merely reporting/refreshing the reference must not seek to the next verse or
            // replace the fragment-aware restoration anchor with the display reference.
            fixture.refresh()
            val after = fixture.json(BookPagination.measure(null))
            for (key in listOf("record", "fragment", "fragments")) {
                assertEquals(key, before.getInt(key), after.getInt(key))
            }
            assertEquals(before.getDouble("x"), after.getDouble("x"), .01)
            fixture.ui {
                assertEquals(101, status.anchorRecord)
                assertEquals(200, status.referenceRecord)
                assertTrue(pageReports.any { it.anchorRecord == 101 && it.referenceRecord == 200 })
                assertEquals(1, host.childCount)
            }
        }
    }

    @Test
    fun scrollingFullWindowPublishesReferenceOnlyChangesButRejectsCroppedBottomLabels() {
        Fixture(scrolling = true, paragraphs = passage(leadLines = 120)).use { fixture ->
            fixture.awaitReady()
            lateinit var retained: OfflineReaderView
            fixture.ui {
                retained = view
                val bounds = Rect()
                assertTrue(view.getGlobalVisibleRect(bounds))
                assertEquals("The full-window fixture must not extend below the display", view.height, bounds.height())
                assertEquals(view.width, bounds.width())
            }
            val height = fixture.layout().getDouble("height")
            // Calculate safe native offsets from the *visual* viewport, not window.scrollY.
            fixture.scrollRecordTo(200, height - 2, anchor = 101, reference = 101)
            val cropped = fixture.record(200)
            assertTrue(cropped.getDouble("top") < height)
            assertTrue(cropped.getDouble("bottom") > height)
            assertFalse(cropped.getBoolean("full"))
            fixture.ui {
                assertEquals(101, view.readingAnchor)
                assertEquals(101, view.readingReference)
            }

            fixture.scrollRecordTo(200, height * .55, anchor = 101, reference = 200)
            assertTrue(fixture.record(200).getBoolean("full"))
            fixture.ui {
                assertSame(retained, view)
                assertEquals(101, view.readingAnchor)
                assertEquals(200, view.readingReference)
                assertEquals(101, status.anchorRecord)
                assertEquals(200, status.referenceRecord)
                assertEquals("BG 2.7", sourceTitle(status.anchorRecord))
                assertEquals("BG 2.8", sourceTitle(status.referenceRecord))
                assertEquals(101, savedAnchors.last().record)
                assertEquals(200, savedAnchors.last().reference)
                assertTrue("Same anchor must notify again when only the reference changes",
                    readingReports.zipWithNext().any { (a, b) ->
                        a.record == 101 && a.reference == 101 && b.record == 101 && b.reference == 200
                    })
                assertEquals(0, view.scrollX)
                assertEquals(0, status.pageCount)
            }
            // Returning to a tiny sliver must restore the current purport's display reference,
            // not retain a previous heading or wait for the paragraph anchor to change.
            fixture.scrollRecordTo(200, height - 2, anchor = 101, reference = 101)
            fixture.ui {
                assertSame(retained, view)
                assertEquals(101, savedAnchors.last().record)
                assertEquals(101, savedAnchors.last().reference)
                assertEquals(1, host.childCount)
            }
        }
    }

    @Test
    fun bothLayoutsChooseFirstOfTwoHeadingsAndUseCurrentAnchorForHeadinglessContinuation() {
        assertEquals(7, BookPageInfo(0, 1, 7, 360).referenceRecord)
        assertEquals(7, ReaderPageStatus(anchorRecord = 7).referenceRecord)
        for (value in listOf<Any?>(null, "200", -1, 1.25, 2147483648L, Double.NaN)) {
            assertEquals("Reject invalid native reference: $value", null, BookPagination.parseReferenceRecord(value))
        }
        assertEquals(0, BookPagination.parseReferenceRecord(0))
        assertEquals(Int.MAX_VALUE, BookPagination.parseReferenceRecord(Int.MAX_VALUE.toLong()))
        val paragraphs = listOf(
            // Looks like a reference, but source kind is body: never infer headings from text.
            Paragraph(101, "BG 2.7 — synthetic purport.", "PA_Purport", ""),
            Paragraph(150, "Bg 2.99", "PA_Textnum", "LE_Verse_Number"), // Concealed repeated label.
            heading(200, "Bg 2.8"),
            Paragraph(201, "Short translation.", "PA_Translation", ""),
            // Also exercise the other actual source-kind class, without a BG-shaped title.
            Paragraph(300, "Synthetic next heading", "PA_Verse_Number_Heading", "LE_Verse_Number_Heading"),
            body(301, 120),
        )
        for (scrolling in listOf(false, true)) {
            Fixture(scrolling, paragraphs).use { fixture ->
                fixture.awaitReady()
                assertFalse(fixture.record(150).getBoolean("visible"))
                assertTrue(fixture.record(200).getBoolean("full"))
                assertTrue(fixture.record(300).getBoolean("full"))
                assertTrue(fixture.record(200).getString("kind").split(' ').contains("verse-number"))
                assertTrue(fixture.record(300).getString("kind").split(' ').contains("verse-number-heading"))
                fixture.ui {
                    assertEquals(101, status.anchorRecord)
                    assertEquals("First heading wins, not hidden 150 or last heading 300", 200, status.referenceRecord)
                    assertEquals(101, savedAnchors.last().record)
                }
                if (scrolling) {
                    val height = fixture.layout().getDouble("height")
                    fixture.scrollRecordTo(301, -height * .5, anchor = 301, reference = 301)
                } else fixture.turn()
                fixture.fence()
                assertFalse(fixture.record(200).getBoolean("visible"))
                assertFalse(fixture.record(300).getBoolean("visible"))
                assertTrue(fixture.record(301).getBoolean("visible"))
                fixture.ui {
                    assertEquals(301, status.anchorRecord)
                    assertEquals("Continuation uses the new body anchor, not a stale heading", 301, status.referenceRecord)
                    assertEquals(301, view.readingReference)
                    assertEquals(301, savedAnchors.last().record)
                }
            }
        }
    }

    private data class Paint(val record: Int, val reference: Int, val y: Int)

    private class Fixture(val scrolling: Boolean, paragraphs: List<Paragraph>) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        private val document = document(paragraphs, scrolling)
        lateinit var host: ReaderPageHost
        var status = ReaderPageStatus()
        val savedAnchors = mutableListOf<Paint>()
        val readingReports = mutableListOf<Paint>()
        val pageReports = mutableListOf<BookPageInfo>()
        val view: OfflineReaderView get() = requireNotNull(host.settledView)

        init {
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                // Use the actual safe content window for scrolling, not an oversized fixed view.
                ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
                    val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
                host = ReaderPageHost(activity)
                val size = (360 * activity.resources.displayMetrics.density).roundToInt()
                root.addView(host, FrameLayout.LayoutParams(
                    if (scrolling) FrameLayout.LayoutParams.MATCH_PARENT else minOf(size, activity.resources.displayMetrics.widthPixels),
                    if (scrolling) FrameLayout.LayoutParams.MATCH_PARENT else size))
                activity.setContentView(root)
                ViewCompat.requestApplyInsets(root)
                host.onStatusChanged = { status = it }
                host.onBookAnchor = { savedAnchors += Paint(it, view.readingReference, view.scrollY) }
                val state = ReaderState(document.section.node.location("synthetic-visible-reference"), "en",
                    document = document, loading = false, mode = if (scrolling) ReadingMode.VERSE else ReadingMode.BOOK)
                host.submit(state, 100) { null }
                val reader = host.getChildAt(0) as OfflineReaderView
                // ReaderPageHost supplies production MATCH_PARENT native params before render;
                // WRAP_CONTENT would silently produce a zero CSS viewport despite native size.
                assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, reader.layoutParams.width)
                assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, reader.layoutParams.height)
                val reading = reader.onReadingAnchor
                reader.onReadingAnchor = { record ->
                    readingReports += Paint(record, reader.readingReference, reader.scrollY)
                    reading(record)
                }
                val paged = reader.onBookPageChanged
                reader.onBookPageChanged = { info -> pageReports += info; paged(info) }
            }
        }

        fun sourceTitle(record: Int) = document.bookResource!!.position.outline.entries.single {
            record in it.node.record..it.node.end
        }.source.displayTitle

        fun ui(block: Fixture.() -> Unit) { scenario.onActivity { block(this) } }

        fun await(message: String, condition: Fixture.() -> Boolean) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            ui {
                val deadline = SystemClock.uptimeMillis() + 20_000L
                val poll = object : Runnable {
                    override fun run() {
                        try {
                            check(!status.renderFailed) { "Render failed: $message" }
                            if (condition()) { done.countDown(); return }
                            check(SystemClock.uptimeMillis() < deadline) {
                                "$message; status=$status scroll=${host.currentView?.scrollY}"
                            }
                            host.postOnAnimation(this)
                        } catch (error: Throwable) { failure = error; done.countDown() }
                    }
                }
                poll.run()
            }
            assertTrue(message, done.await(25, TimeUnit.SECONDS))
            failure?.let { throw AssertionError(message, it) }
        }

        fun awaitReady() {
            await("Initial reference and anchor must be fenced and published") {
                host.settledView != null && !status.busy && savedAnchors.isNotEmpty() && status.anchorRecord > 0
            }
            fence()
            assertEquals("loaded", layout().getString("fonts"))
        }

        fun turn() {
            var next = 0
            ui { next = status.pageIndex + 1; assertTrue(status.canNext); host.next() }
            await("Next column must paint before its reference is exposed") {
                host.settledView != null && !status.busy && status.pageIndex == next &&
                    savedAnchors.lastOrNull()?.record == status.anchorRecord
            }
        }

        fun refresh() {
            val done = CountDownLatch(1)
            ui { view.refreshBookLayout { done.countDown() } }
            assertTrue("Anchored refresh", done.await(20, TimeUnit.SECONDS))
            fence()
        }

        fun scrollRecordTo(record: Int, cssTop: Double, anchor: Int, reference: Int) {
            val geometry = layout()
            val targetBox = record(geometry, record)
            var target = 0
            var reports = 0
            ui {
                val scale = view.nativeContentScale
                target = ((targetBox.getDouble("top") + geometry.getDouble("y") - cssTop) * scale).roundToInt()
                assertTrue("Safe interior offset, never native bottom clamping: $target/${view.scrollRange}",
                    target > 0 && target < view.scrollRange)
                reports = readingReports.size
                view.scrollTo(0, target)
            }
            await("Settled reference $reference at native offset $target") {
                host.settledView != null && abs(view.scrollY - target) <= 1 &&
                    status.anchorRecord == anchor && status.referenceRecord == reference &&
                    readingReports.drop(reports).any { it.record == anchor && it.reference == reference && abs(it.y - target) <= 1 } &&
                    savedAnchors.lastOrNull()?.let { it.record == anchor && it.reference == reference && abs(it.y - target) <= 1 } == true
            }
            fence()
            val painted = layout()
            ui {
                assertEquals(painted.getDouble("y") * view.nativeContentScale, view.scrollY.toDouble(), 2.0)
            }
        }

        fun fence() {
            val done = CountDownLatch(1)
            ui {
                view.postVisualStateCallback(801L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { done.countDown() }
                })
                view.invalidate()
            }
            assertTrue("Native visual-state fence", done.await(20, TimeUnit.SECONDS))
        }

        fun json(script: String): JSONObject {
            val done = CountDownLatch(1)
            var result: String? = null
            ui { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("Read-only geometry inspection", done.await(20, TimeUnit.SECONDS))
            return JSONObject(requireNotNull(result))
        }

        fun layout() = json(GEOMETRY)
        fun record(record: Int) = record(layout(), record)
        private fun record(layout: JSONObject, record: Int): JSONObject {
            val records = layout.getJSONArray("records")
            return (0 until records.length()).map { records.getJSONObject(it) }.single { it.getInt("record") == record }
        }

        override fun close() {
            try { scenario.onActivity { host.release() } } finally { scenario.close() }
        }
    }

    companion object {
        private fun heading(record: Int, text: String) =
            Paragraph(record, text, "PA_Verse_Number_Heading", "LE_Verse_Number")

        private fun body(record: Int, lines: Int) = Paragraph(record,
            (1..lines).joinToString("<CR>") { "Synthetic line $it." }, "PA_Purport", "")

        private fun passage(leadLines: Int) = listOf(
            heading(100, "Bg 2.7"), body(101, leadLines), heading(200, "Bg 2.8"), body(201, 120),
        )

        private fun document(paragraphs: List<Paragraph>, scrolling: Boolean): ReaderDocument {
            val starts = if (paragraphs.any { it.record >= 300 }) listOf(100, 200, 300) else listOf(100, 200)
            val root = ContentNode(100, 0, "Synthetic chapter", starts.last() + 99, starts.size, 1)
            val entries = starts.mapIndexed { index, start ->
                val node = ContentNode(start, root.record, "Bg 2.${7 + index}", start + 99, 0, 2)
                BookEntry(node, node, ReadingUnitKind.VERSE, root.title)
            }
            val position = BookPosition(BookOutline(root, entries), 0)
            val section = ReadingSection(root, ReadingUnitKind.CHAPTER, "Synthetic work", paragraphs)
            return ReaderDocument(section,
                FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true, scrollingBook = scrolling),
                position, BookResource(section, position, 0, entries.lastIndex, 100))
        }

        // Independent evidence uses visible label child/text ranges, not production reference output
        // or the hidden original source child's first bounding box. Coordinates follow visual panning.
        private val GEOMETRY = """
            (function(){var v=window.visualViewport,w=v?v.width:innerWidth,h=v?v.height:innerHeight;
                var ox=v?v.offsetLeft:0,oy=v?v.offsetTop:0;
                function full(r){return r.width>0&&r.height>0&&r.left-ox>=0&&r.right-ox<=w&&r.top-oy>=0&&r.bottom-oy<=h;}
                return {width:w,height:h,y:v?v.pageTop:scrollY,fonts:document.fonts.status,
                    records:Array.from(document.querySelectorAll('.record[data-record]')).map(function(e){
                        var boxes=Array.from(e.getClientRects()),r=e.getBoundingClientRect();
                        var label=Array.from(e.children).find(function(c){return c.classList.contains('folio-block')&&getComputedStyle(c).display!=='none';});
                        var lines=[];
                        if(label){var range=document.createRange();range.selectNodeContents(label);
                            lines=Array.from(range.getClientRects()).filter(function(b){return b.width>0&&b.height>0;});}
                        var source=e.querySelector('.source-book-heading');
                        return {record:Number(e.dataset.record),kind:e.className,label:label?label.textContent:'',
                            top:r.top-oy,bottom:r.bottom-oy,hiddenRects:source?source.getClientRects().length:0,
                            full:boxes.length>0&&boxes.every(full)&&lines.length>0&&lines.every(full),
                            visible:boxes.some(function(b){return b.width>0&&b.height>0&&b.right-ox>.5&&
                                b.left-ox<w-.5&&b.bottom-oy>.5&&b.top-oy<h-.5;})};})};})()
        """.trimIndent()
    }
}