package org.vedareader.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderPaperAppearance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Reader-local theme and native CSS only; synthetic source, no app/library/settings access. */
@RunWith(AndroidJUnit4::class)
class ReaderPaperThemeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nestedReaderThemeRestoresHomeAndBarAppearanceOnNormalAndFullscreenDisposal() {
        var mounted by mutableStateOf(false)
        var fullscreen by mutableStateOf(false)
        var before: Palette? = null
        var inside: Palette? = null
        var after: Palette? = null
        compose.runOnUiThread {
            // Match MainActivity's default home policy, not a snapshot taken inside the reader.
            compose.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()),
                navigationBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()),
            )
        }
        compose.setContent {
            VedaPreviewTheme {
                Column {
                    ThemeProbe("home-before") { before = it }
                    if (mounted) ReaderPaperTheme {
                        ReaderFullscreen(fullscreen)
                        ThemeProbe("paper") { inside = it }
                    } else Text("Home", Modifier.testTag("home"))
                    ThemeProbe("home-after") { after = it }
                }
            }
        }
        compose.onNodeWithTag("home").assertIsDisplayed()
        val home = compose.runOnIdle { requireNotNull(before) }
        val homeBars = compose.runOnIdle { barAppearance() }
        assertEquals(ReadingColors.Paper, home.surface)
        assertEquals(ReadingColors.Ink, home.ink)
        assertEquals(ReadingColors.Header, home.primary)
        assertEquals(ReadingColors.OnHeader, home.onPrimary)
        assertEquals(ReadingColors.Chapter, home.container)
        assertFalse(homeBars.lightStatus)
        assertFalse(homeBars.lightNavigation)

        for (immersive in listOf(false, true)) {
            compose.runOnIdle { fullscreen = immersive; mounted = true }
            compose.onNodeWithTag("paper").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("An outer sibling must keep the home theme", home, before)
                assertEquals("The reader theme must not leak to a following sibling", home, after)
                val paper = requireNotNull(inside)
                assertEquals(ReaderPaperColors.Paper, paper.surface)
                assertEquals(ReaderPaperColors.Ink, paper.ink)
                assertEquals(ReaderPaperColors.Accent, paper.primary)
                assertEquals(ReaderPaperColors.Paper, paper.onPrimary)
                assertEquals(ReaderPaperColors.Chapter, paper.container)
                assertEquals(ReaderPaperColors.Muted, paper.muted)
                assertEquals(ReaderPaperColors.Rule, paper.rule)
                assertEquals(ReaderPaperColors.Paper, paper.tint)
                val bars = barAppearance()
                assertEquals(ReaderPaperColors.Paper.toArgb(), bars.status)
                assertEquals(ReaderPaperColors.Paper.toArgb(), bars.navigation)
                assertTrue(bars.lightStatus)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) assertTrue(bars.lightNavigation)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    assertEquals(false, bars.statusContrast)
                    assertEquals(false, bars.navigationContrast)
                }
                mounted = false // Dispose both effects, including while fullscreen is active.
            }
            compose.onNodeWithTag("paper").assertDoesNotExist()
            compose.onNodeWithTag("home").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(home, before)
                assertEquals(home, after)
                // Visibility/geometry belong to ReaderFullscreenTest. These are appearance
                // properties only, restored to the pre-reader home baseline, not paper.
                assertEquals("Disposal must restore home bar appearance", homeBars, barAppearance())
            }
        }
    }

    @Test
    fun nativeCssMatchesReaderPaperThemeInPagedAndScrollingDocuments() {
        val source = listOf(
            Paragraph(10, "Synthetic chapter", "", "LE_Chapter"),
            Paragraph(11, "Synthetic body <JL:Jump,\"local\">reference<EL>", "", ""),
            Paragraph(12, "Synthetic śloka कृष्ण", "PA_Verse", "LE_Verse_Text"),
            Paragraph(13, "word — meaning", "PA_Synonyms", ""),
            Paragraph(14, "Synthetic purport", "PA_Purport", ""),
            Paragraph(15, "<JL:Jump,\"local\">Quoted reference<EL>", "PA_Verse_in_purp", ""),
        )
        val original = source.map { it.copy() }
        var paged by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var paper: Palette? = null
        val signals = listOf(false, true).associateWith { CountDownLatch(1) }
        val failures = listOf(false, true).associateWith { AtomicBoolean(false) }
        val views = mutableMapOf<Boolean, OfflineReaderView>()
        compose.setContent {
            VedaPreviewTheme {
                if (mounted) ReaderPaperTheme {
                    val palette = currentPalette()
                    SideEffect { paper = palette }
                    val mode = paged
                    key(mode) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                            OfflineReaderView(context).apply {
                                // Match production ReaderPageHost. A direct AndroidView otherwise
                                // leaves WRAP_CONTENT native params, collapsing WebView's CSS vh.
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                views[mode] = this
                                paginatedBook = mode
                                scrollingBook = !mode
                                onRendered = { signals.getValue(mode).countDown() }
                                onRenderFailed = {
                                    failures.getValue(mode).set(true)
                                    signals.getValue(mode).countDown()
                                }
                                render("paper-$mode", FolioText.document(source, "en", ReaderPreferences(),
                                    bookMode = true, scrollingBook = !mode), 0) { null }
                            }
                        }, onRelease = { it.release() }, update = {})
                    }
                } else Text("Home", Modifier.testTag("home"))
            }
        }
        try {
            for (mode in listOf(false, true)) {
                compose.runOnIdle { paged = mode }
                compose.waitUntil(timeoutMillis = 20_000) { signals.getValue(mode).count == 0L }
                val view = compose.runOnIdle { views.getValue(mode) }
                assertFalse("Native paper failed paged=$mode geometry=" + compose.runOnIdle {
                    "${view.width}x${view.height} scroll=${view.scrollX},${view.scrollY} range=${view.scrollRange} title=${view.title}"
                } + evaluate(view, """(function(){return {w:innerWidth,h:innerHeight,vw:visualViewport.width,
                    vh:visualViewport.height,x:visualViewport.pageLeft,y:visualViewport.pageTop,
                    fonts:document.fonts.status,extent:document.scrollingElement.scrollWidth};})()"""), failures.getValue(mode).get())
                val css = evaluate(view, """
                    (function(){function style(s){return getComputedStyle(document.querySelector(s));}
                        var body=style('body');return {fonts:document.fonts.status,
                            background:body.backgroundColor,root:style('html').backgroundColor,ink:body.color,
                            chapter:style('#record-10 .folio-block').backgroundColor,
                            bodyInk:style('#record-11 .folio-block').color,link:style('#record-11 a').color,
                            verse:style('#record-12 .folio-block').color,synonyms:style('#record-13 .folio-block').color,
                            purport:style('#record-14 .folio-block').color,quote:style('#record-15 a').color,
                            font:body.fontFamily,size:body.fontSize,scriptCount:document.scripts.length};})()
                """.trimIndent())
                val theme = compose.runOnIdle { requireNotNull(paper) }
                assertEquals(rgb(ReaderPaperAppearance.Paper), css.getString("background"))
                assertEquals(rgb(theme.surface), css.getString("background"))
                assertEquals(rgb(theme.surface), css.getString("root"))
                assertEquals(rgb(ReaderPaperAppearance.Ink), css.getString("ink"))
                for (name in listOf("ink", "bodyInk", "verse", "synonyms", "purport", "quote")) {
                    assertEquals("Reader $name in paged=$mode", rgb(theme.ink), css.getString(name))
                }
                assertEquals(rgb(ReaderPaperAppearance.Link), css.getString("link"))
                // Folio chapter backgrounds use the rule color, not the Compose container.
                assertEquals(rgb(theme.rule), css.getString("chapter"))
                assertEquals("loaded", css.getString("fonts"))
                assertEquals("20px", css.getString("size"))
                assertTrue(css.getString("font").contains("Fira Sans Condensed"))
                assertEquals(0, css.getInt("scriptCount"))
                compose.runOnIdle {
                    assertEquals("Reader", view.title)
                    assertEquals(mode, view.paginatedBook)
                    assertEquals(!mode, view.scrollingBook)
                    assertTrue(view.settings.javaScriptEnabled) // Enabled by production, never by this test.
                    assertTrue(view.settings.blockNetworkLoads)
                }
            }
            assertEquals(original, source)
        } finally {
            compose.runOnIdle { mounted = false }
            compose.onNodeWithTag("home").assertIsDisplayed()
            compose.runOnIdle { views.values.forEach { assertTrue(it.isReleased) } }
        }
    }

    @Composable
    private fun ThemeProbe(tag: String, capture: (Palette) -> Unit) {
        val palette = currentPalette()
        SideEffect { capture(palette) }
        Text(tag, Modifier.testTag(tag))
    }

    @Composable
    private fun currentPalette(): Palette = MaterialTheme.colorScheme.let {
        Palette(it.surface, it.onSurface, it.primary, it.onPrimary, it.primaryContainer,
            it.onSurfaceVariant, it.outlineVariant, it.surfaceTint)
    }

    @Suppress("DEPRECATION") // Test the exact appearance state owned/restored by ReaderPaperTheme.
    private fun barAppearance(): Bars {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return Bars(window.statusBarColor, window.navigationBarColor,
            controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else null)
    }

    private fun evaluate(view: OfflineReaderView, script: String): JSONObject {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        compose.runOnIdle {
            assertFalse(view.isReleased)
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        assertTrue("Native CSS query must complete", done.await(10, TimeUnit.SECONDS))
        return JSONObject(requireNotNull(result.get()))
    }

    private fun rgb(hex: String) = rgb(Color(android.graphics.Color.parseColor(hex)))
    private fun rgb(color: Color): String {
        val argb = color.toArgb()
        return "rgb(${(argb ushr 16) and 255}, ${(argb ushr 8) and 255}, ${argb and 255})"
    }

    private data class Palette(val surface: Color, val ink: Color, val primary: Color, val onPrimary: Color,
        val container: Color, val muted: Color, val rule: Color, val tint: Color)
    private data class Bars(val status: Int, val navigation: Int, val lightStatus: Boolean,
        val lightNavigation: Boolean, val statusContrast: Boolean?, val navigationContrast: Boolean?)
}