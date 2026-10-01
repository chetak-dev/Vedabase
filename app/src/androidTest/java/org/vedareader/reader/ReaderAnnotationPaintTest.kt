package org.vedareader.reader

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSpan
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Production painter and CSP inside an isolated native document; synthetic text only, no bridge. */
@RunWith(AndroidJUnit4::class)
class ReaderAnnotationPaintTest {
    @Test
    fun noteOnlyIsTransparentAndUnderlinedWithoutChangingUnicodeTextOrLayoutInEitherMode() {
        for (paged in listOf(false, true)) Fixture(paged).use { fixture ->
            val before = fixture.snapshot()
            val note = HighlightSpan("note", 10, 0, 11, TAIL.length, HighlightColor.BLUE, noted = true, highlighted = false)
            assertEquals("1", fixture.eval(ReaderHighlights.apply(listOf(note))))
            val after = fixture.snapshot()
            assertUnchanged(before, after)
            assertTrue(after.getInt("notes") > 0)
            assertEquals(after.getInt("marks"), after.getInt("notes"))
            assertEquals("rgba(0, 0, 0, 0)", after.getString("background"))
            assertEquals("underline", after.getString("decoration"))
            assertEquals("2px", after.getString("thickness"))
            assertNotEquals("rgba(0, 0, 0, 0)", after.getString("underlineColor"))
            assertTrue(after.getString("text").contains("कृष्ण a\u0304 🎵"))
            fixture.assertSecurity()
        }
    }

    @Test
    fun explicitHighlightWithNoteKeepsFillAndBothLegacySpanDefaultsRemainHighlighted() {
        Fixture(false).use { fixture ->
            val before = fixture.snapshot()
            val noted = HighlightSpan("highlight-note", 10, 0, 10, 9, HighlightColor.BLUE, noted = true)
            assertTrue(noted.highlighted)
            fixture.eval(ReaderHighlights.apply(listOf(noted)))
            val painted = fixture.snapshot()
            assertUnchanged(before, painted)
            assertEquals(0, painted.getInt("notes"))
            assertEquals("rgb(188, 219, 255)", painted.getString("background"))
            assertEquals("underline", painted.getString("decoration"))
            fixture.eval(ReaderHighlights.apply(listOf(noted.copy(noted = false))))
            val plain = fixture.snapshot()
            assertEquals("rgb(188, 219, 255)", plain.getString("background"))
            assertEquals("none", plain.getString("decoration"))
            assertUnchanged(before, plain)
        }
    }

    @Test
    fun repeatedNestedOverlapsUnwrapEveryOldMarkAndPreserveSearchAndSourceMarkup() {
        Fixture(false).use { fixture ->
            val before = fixture.snapshot()
            val outer = HighlightSpan("outer", 10, 0, 11, TAIL.length, HighlightColor.YELLOW)
            val inner = HighlightSpan("inner", 10, 2, 10, 9, HighlightColor.GREEN, noted = true, highlighted = false)
            fixture.eval(ReaderHighlights.apply(listOf(outer, inner)))
            assertTrue(fixture.snapshot().getInt("nested") > 0) // Proves the fixture exercises nested unwrapping.
            repeat(4) {
                fixture.eval(ReaderHighlights.apply(listOf(inner, outer)))
                assertUnchanged(before, fixture.snapshot())
                fixture.eval(ReaderHighlights.apply(listOf(inner)))
                val noteOnly = fixture.snapshot()
                assertEquals(0, noteOnly.getInt("nested"))
                assertEquals("0", fixture.eval("document.querySelectorAll('mark.user-hl[data-hl=outer]').length"))
                assertUnchanged(before, noteOnly)
            }
            assertEquals("0", fixture.eval(ReaderHighlights.apply(emptyList())))
            val restored = fixture.snapshot()
            assertEquals(0, restored.getInt("marks"))
            assertEquals(before.getString("html"), restored.getString("html"))
            assertUnchanged(before, restored)
            assertTrue("Search-hit marks must not be unwrapped", restored.getInt("search") > 0)
        }
    }

    @Test
    fun annotationWithoutFillOrNoteDoesNotPaintAndDarkPaperNoteHasNoFill() {
        Fixture(false, ReaderPaper.DARK).use { fixture ->
            val before = fixture.snapshot()
            val empty = HighlightSpan("empty", 10, 0, 10, 9, HighlightColor.PINK, highlighted = false)
            assertEquals("0", fixture.eval(ReaderHighlights.apply(listOf(empty))))
            assertEquals(0, fixture.snapshot().getInt("marks"))
            fixture.eval(ReaderHighlights.apply(listOf(empty.copy(noted = true))))
            val note = fixture.snapshot()
            assertEquals("rgba(0, 0, 0, 0)", note.getString("background"))
            assertEquals("underline", note.getString("decoration"))
            assertUnchanged(before, note)
        }
    }

    @Test
    fun noteOnlyRemainsHitTestableByTheClosedNativePointQuery() {
        Fixture(false).use { fixture ->
            fixture.eval(ReaderHighlights.apply(listOf(HighlightSpan("note-id", 10, 0, 10, 9,
                HighlightColor.YELLOW, noted = true, highlighted = false))))
            val point = JSONObject(fixture.eval("""
                (function() { var mark = document.querySelector('mark.user-hl');
                    var r = mark.getClientRects()[0], v = window.visualViewport;
                    return { x: r.left + r.width / 2 - (v ? v.offsetLeft : 0),
                        y: r.top + r.height / 2 - (v ? v.offsetTop : 0),
                        width: v ? v.width : innerWidth, height: v ? v.height : innerHeight }; })()
            """.trimIndent()))
            // Map back through the same explicit native viewport contract as production.
            val width = 10_000
            val height = 10_000
            val x = (point.getDouble("x") / point.getDouble("width") * width).toInt()
            val y = (point.getDouble("y") / point.getDouble("height") * height).toInt()
            assertEquals("note-id", ReaderHighlights.parseId(fixture.eval(ReaderHighlights.at(x, y, width, height))))
        }
    }

    private fun assertUnchanged(before: JSONObject, after: JSONObject) {
        assertEquals(before.getString("text"), after.getString("text"))
        assertEquals(before.getString("records"), after.getString("records"))
        assertEquals(before.getInt("search"), after.getInt("search"))
        val left = before.getJSONArray("rects")
        val right = after.getJSONArray("rects")
        assertEquals(left.length(), right.length())
        repeat(left.length()) { record ->
            val a = left.getJSONArray(record)
            val b = right.getJSONArray(record)
            repeat(4) { dimension -> assertEquals(a.getDouble(dimension), b.getDouble(dimension), .1) }
        }
    }

    private class Fixture(paged: Boolean, paper: ReaderPaper = ReaderPaper.LIGHT) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        private lateinit var view: OfflineReaderView
        private val html = FolioText.document(listOf(
            Paragraph(10, "Synthetic <CS:\"Italic\">कृष्ण a\u0304 🎵</CS> immutable prose.", "PA_Purport", ""),
            Paragraph(11, TAIL, "PA_Purport", "")), "en", ReaderPreferences(paper = paper),
            bookMode = true, scrollingBook = !paged, highlight = setOf("synthetic"))

        init {
            val ready = CountDownLatch(1)
            var failed = false
            scenario.onActivity { activity ->
                view = OfflineReaderView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                    paginatedBook = paged
                    scrollingBook = !paged
                    initialBookAnchor = 10
                    onRendered = { ready.countDown() }
                    onRenderFailed = { failed = true; ready.countDown() }
                }
                activity.setContentView(FrameLayout(activity).apply { addView(view) })
                view.setReaderStyle(ReaderStyle(paper))
                view.render("synthetic-annotation", html, 0) { null }
            }
            assertTrue("Synthetic document must reach visual readiness", ready.await(20, TimeUnit.SECONDS))
            assertFalse("Synthetic document must not be a WebView error page", failed)
            scenario.onActivity { assertEquals("Reader", view.title) }
        }

        fun eval(script: String): String {
            val result = AtomicReference<String>()
            val done = CountDownLatch(1)
            scenario.onActivity { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
            assertTrue("Bounded DOM query must complete", done.await(10, TimeUnit.SECONDS))
            return requireNotNull(result.get())
        }

        fun snapshot(): JSONObject = JSONObject(eval("""
            (function() { var flow = document.querySelector('main.book-flow');
                var marks = flow.querySelectorAll('mark.user-hl'), first = marks[0], css = first ? getComputedStyle(first) : null;
                var records = Array.from(flow.querySelectorAll('.record[data-record]'));
                return {text: flow.textContent, html: flow.innerHTML,
                    records: JSON.stringify(records.map(function(r) { return [r.id, r.textContent]; })),
                    rects: records.map(function(r) { var b = r.getBoundingClientRect(); return [b.x, b.y, b.width, b.height]; }),
                    marks: marks.length, notes: flow.querySelectorAll('mark.user-hl.note-only').length,
                    nested: flow.querySelectorAll('mark.user-hl mark.user-hl').length,
                    search: flow.querySelectorAll('mark.search-hit').length,
                    background: css ? css.backgroundColor : '', decoration: css ? css.textDecorationLine : '',
                    thickness: css ? css.textDecorationThickness : '', underlineColor: css ? css.textDecorationColor : ''}; })()
        """.trimIndent()))

        @Suppress("DEPRECATION")
        fun assertSecurity() {
            assertTrue(html.contains("script-src 'none'"))
            assertFalse(html.contains("<script"))
            scenario.onActivity {
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertFalse(view.settings.domStorageEnabled)
                assertFalse(view.settings.databaseEnabled)
                assertTrue(view.settings.blockNetworkLoads)
            }
        }

        override fun close() {
            scenario.onActivity { view.release() }
            scenario.close()
        }
    }

    companion object { private const val TAIL = "Synthetic second record with a complete ending." }
}