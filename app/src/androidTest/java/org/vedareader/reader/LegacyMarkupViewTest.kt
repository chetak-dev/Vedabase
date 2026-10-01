package org.vedareader.reader

import android.view.ViewGroup
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Small synthetic grammar fixtures, not a claim that every literal angle tag is removed.
 * Real WebViews in both production book layouts; no database, network or script-setting overrides.
 */
@RunWith(AndroidJUnit4::class)
class LegacyMarkupViewTest {
    @Test
    fun pagedLegacyMarkupRendersDecorationsAndTextWithoutExposingKnownMarkers() {
        assertLegacyDocument(paged = true)
    }

    @Test
    fun scrollingLegacyMarkupRendersDecorationsAndTextWithoutExposingKnownMarkers() {
        assertLegacyDocument(paged = false)
    }

    private fun assertLegacyDocument(paged: Boolean) {
        val source = listOf(
            Paragraph(1, "<LS:1.25><SP:2p><SB:0.5p><SD:NO><LH:0.25>" +
                "<UN+>Underline नमूना ā<UN> plain <SO+>Strike sample<SO-> end", "", ""),
            Paragraph(2, "<HD+>Visible note<HD><PN:\"Blue background\"> panel</PN>" +
                "<NT:1,2,Note> note</NT></SS><BUILDVIEW><HS>space<SH>word", "", ""),
            // Deliberately unclosed: generated inline state must not reach the next record.
            Paragraph(3, "<CS:\"Bold_Italic_Underline_Strikeout\">Combined sample", "", ""),
            Paragraph(4, "<TA:2,50%,50%; HG:0.02><RO,HE><CE:VA:TO>Cell alpha<CE>Cell beta</TA>", "", ""),
            Paragraph(5, "<script>window.legacyExecuted=true</script>" +
                "<img src=\"https://invalid.example/x\" onerror=\"window.legacyExecuted=true\">" +
                "<u onclick=bad()>Literal underline</u><aa>", "", ""),
            Paragraph(6, "Normal final record", "", ""),
        )
        val original = source.map { it.copy() }
        val html = FolioText.document(source, "en", ReaderPreferences(), bookMode = true, scrollingBook = !paged)
        assertTrue(html.contains("<u>Underline नमूना ā</u>"))
        assertTrue(html.contains("<s>Strike sample</s>"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img src="))
        assertTrue(html.contains("default-src 'none'"))
        val ready = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var reader: OfflineReaderView? = null
            try {
                scenario.onActivity { activity ->
                    val view = OfflineReaderView(activity)
                    reader = view
                    view.paginatedBook = paged
                    view.scrollingBook = !paged
                    view.onRendered = { ready.countDown() }
                    view.onRenderFailed = { failed.set(true); ready.countDown() }
                    activity.setContentView(view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    view.render("legacy-$paged", html, 0) { null }
                }
                assertTrue("The legacy document must reach its native paint callback", ready.await(20, TimeUnit.SECONDS))
                assertFalse("The synthetic document must render, not an error page", failed.get())
                val view = requireNotNull(reader)
                scenario.onActivity {
                    assertEquals("Reader", view.title)
                    assertEquals(paged, view.paginatedBook)
                    assertEquals(!paged, view.scrollingBook)
                    assertTrue(view.settings.javaScriptEnabled) // Both modes already enable native queries.
                    assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
                    assertFalse(view.settings.allowFileAccess)
                    assertFalse(view.settings.allowContentAccess)
                    assertFalse(view.settings.domStorageEnabled)
                    assertTrue(view.settings.blockNetworkLoads)
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
                }

                // Native find proves real rendered Unicode/content, not just progress==100.
                for (text in listOf("Underline नमूना ā", "Strike sample", "Visible note", "Combined sample",
                    "Cell alpha", "Cell beta", "Normal final record")) {
                    assertEquals("Rendered text: $text", 1, find(scenario, view, text))
                }
                for (marker in listOf("<UN+>", "<UN>", "<SO+>", "<SO->", "<LS:", "<SP:", "<SB:",
                    "<SD:", "<LH:", "<HD", "<PN:", "<NT:", "</SS>", "<BUILDVIEW>", "<HS>", "<SH>",
                    "<CS:", "<TA:", "<RO,HE>", "<CE:")) {
                    assertEquals("Recognized Folio marker must not be visible: $marker", 0, find(scenario, view, marker))
                }
                // Unknown/hostile HTML stays literal and escaped, rather than being stripped or run.
                assertEquals(1, find(scenario, view, "<script>"))
                assertEquals(1, find(scenario, view, "<aa>"))
                scenario.onActivity { view.clearMatches() }
                val result = evaluate(scenario, view, """
                    (function(){function element(s){return document.querySelector(s);}
                        function style(s){return getComputedStyle(element(s));}
                        return {underline:style('#record-1 u').textDecorationLine,
                            strike:style('#record-1 s').textDecorationLine,
                            combinedBold:style('#record-3 strong').fontWeight,
                            combinedItalic:style('#record-3 em').fontStyle,
                            combinedUnderline:style('#record-3 u').textDecorationLine,
                            combinedStrike:style('#record-3 s').textDecorationLine,
                            first:element('#record-1').textContent,note:element('#record-2').textContent,
                            literal:element('#record-5').textContent,
                            leaked:document.querySelectorAll('#record-4 u,#record-4 s,#record-6 strong,#record-6 em,#record-6 u,#record-6 s').length,
                            normalDecoration:style('#record-6 .folio-block').textDecorationLine,
                            tables:document.querySelectorAll('table').length,rows:document.querySelectorAll('tr').length,
                            cells:Array.from(document.querySelectorAll('td')).map(function(e){return e.textContent;}),
                            records:Array.from(document.querySelectorAll('.record[data-record]')).map(function(e){return Number(e.dataset.record);}),
                            active:document.querySelectorAll('script,img,iframe,[onerror],[onclick]').length,
                            executed:window.legacyExecuted===true,fonts:document.fonts.status};})()
                """.trimIndent())
                assertEquals("underline", result.getString("underline"))
                assertEquals("line-through", result.getString("strike"))
                assertEquals("700", result.getString("combinedBold"))
                assertEquals("italic", result.getString("combinedItalic"))
                assertEquals("underline", result.getString("combinedUnderline"))
                assertEquals("line-through", result.getString("combinedStrike"))
                assertEquals("Underline नमूना ā plain Strike sample end", result.getString("first"))
                assertEquals("Visible note panel note\u00a0space\u00adword", result.getString("note"))
                assertEquals(source[4].text, result.getString("literal"))
                assertEquals(0, result.getInt("leaked"))
                assertEquals("none", result.getString("normalDecoration"))
                assertEquals(1, result.getInt("tables"))
                assertEquals(1, result.getInt("rows"))
                val cells = result.getJSONArray("cells")
                assertEquals(listOf("Cell alpha", "Cell beta"), (0 until cells.length()).map { cells.getString(it) })
                val records = result.getJSONArray("records")
                assertEquals(source.map { it.record }, (0 until records.length()).map { records.getInt(it) })
                assertEquals(0, result.getInt("active"))
                assertFalse(result.getBoolean("executed"))
                assertEquals("loaded", result.getString("fonts"))
                assertEquals(original, source)
            } finally {
                scenario.onActivity {
                    reader?.let { view ->
                        view.setFindListener(null)
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.release()
                        assertTrue(view.isReleased)
                    }
                }
            }
        }
    }

    private fun find(scenario: ActivityScenario<ComponentActivity>, view: OfflineReaderView, text: String): Int {
        val done = CountDownLatch(1)
        val matches = AtomicInteger(-1)
        scenario.onActivity {
            view.setFindListener { _, count, complete ->
                if (complete) { matches.set(count); done.countDown() }
            }
            view.findAllAsync(text)
        }
        assertTrue("Native find must complete for $text", done.await(10, TimeUnit.SECONDS))
        scenario.onActivity { view.setFindListener(null) }
        return matches.get()
    }

    private fun evaluate(scenario: ActivityScenario<ComponentActivity>, view: OfflineReaderView, script: String): JSONObject {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        scenario.onActivity {
            assertFalse(view.isReleased)
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        assertTrue("Native formatting query must complete", done.await(10, TimeUnit.SECONDS))
        return JSONObject(requireNotNull(result.get()))
    }
}