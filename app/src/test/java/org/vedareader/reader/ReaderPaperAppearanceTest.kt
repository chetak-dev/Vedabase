package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure JVM checks; no Android/Compose runtime, source corpus, or device is needed. */
class ReaderPaperAppearanceTest {
    @Test
    fun readerPaletteMatchesTheRequestedPaperAndInk() {
        assertEquals("#FFFFFF", ReaderPaperAppearance.Paper)
        assertEquals("#1F1F1F", ReaderPaperAppearance.Ink)
        assertEquals("#B34E15", ReaderPaperAppearance.Header)
        assertEquals("#FFFFFF", ReaderPaperAppearance.OnHeader)
        assertEquals("#5B4636", ReaderPaperAppearance.Accent)
        assertEquals("#4E4E4E", ReaderPaperAppearance.Link)
        assertEquals("#6A6A6A", ReaderPaperAppearance.Muted)
        assertEquals("#DCDCDC", ReaderPaperAppearance.Rule)
        assertEquals("#F6F6F6", ReaderPaperAppearance.Chapter)
        assertEquals(listOf(ReaderPaper.LIGHT, ReaderPaper.SEPIA, ReaderPaper.GREEN, ReaderPaper.GRAY, ReaderPaper.DARK), ReaderPaper.entries)
    }

    @Test
    fun bodyLabelsLinksAndHeaderMeetNormalTextContrastInEveryTheme() {
        for (paper in ReaderPaper.entries) {
            val palette = ReaderPaperAppearance.palette(paper)
            val pairs = listOf(
                palette.Ink to palette.Paper, palette.Muted to palette.Paper, palette.OnHeader to palette.Header,
                palette.Accent to palette.Paper, palette.Link to palette.Paper, palette.Ink to palette.Chapter,
                palette.Muted to palette.Chapter, palette.Ink to palette.Rule,
            )
            for ((foreground, background) in pairs) {
                val ratio = contrast(foreground, background)
                assertTrue("$paper: $foreground on $background has contrast $ratio; requires at least 4.5:1", ratio >= 4.5)
            }
            // Reference links are a little lighter than body ink, never louder than it.
            val inkContrast = contrast(palette.Ink, palette.Paper)
            val linkContrast = contrast(palette.Link, palette.Paper)
            assertTrue("$paper link must be lighter than ink", linkContrast < inkContrast)
            assertTrue("$paper link must stay close to ink", linkContrast >= inkContrast * .45)
            assertEquals(paper == ReaderPaper.GRAY || paper == ReaderPaper.DARK, palette.dark)
        }
    }

    @Test
    fun homeAndChapterUseTheBrightPaletteIndependentlyOfReaderLight() {
        assertEquals("#B34E15", ReaderAppearance.Header)
        assertEquals("#FFF9EF", ReaderAppearance.Paper)
        assertEquals("#FBEEDC", ReaderAppearance.Chapter)
        assertEquals("#2F2B26", ReaderAppearance.Ink)
        assertEquals("#8A5A12", ReaderAppearance.Accent)
        assertEquals("#8A4A0E", ReaderAppearance.Link)
        assertEquals("#FFFFFF", ReaderAppearance.OnHeader)
        assertEquals("#6C6053", ReaderAppearance.Muted)
        assertEquals("#E6CFAE", ReaderAppearance.Rule)
    }

    @Test
    fun defaultAndExplicitLightCssUseReaderPaletteInEveryReadingMode() {
        assertEquals(ReaderPaper.LIGHT, ReaderPreferences().paper)
        for ((bookMode, scrollingBook) in listOf(false to false, true to false, true to true)) {
            val defaultCss = stylesheet(ReaderPreferences(), bookMode, scrollingBook)
            assertEquals(defaultCss, stylesheet(ReaderPreferences(paper = ReaderPaper.LIGHT), bookMode, scrollingBook))
            assertCssPalette(defaultCss, ReaderPaperAppearance.Paper, ReaderPaperAppearance.Ink,
                ReaderPaperAppearance.Link, ReaderPaperAppearance.Rule, ReaderPaperAppearance.Accent,
                ReaderPaperAppearance.Muted, "light")
            for (legacyColor in listOf(ReaderAppearance.Paper, ReaderAppearance.Ink,
                ReaderAppearance.Link, ReaderAppearance.Chapter, ReaderAppearance.Accent, ReaderAppearance.Muted)) {
                assertFalse("Reader CSS still contains home color $legacyColor", defaultCss.contains(legacyColor, ignoreCase = true))
            }
        }
    }

    @Test
    fun everyOtherThemeEmitsItsOwnPaletteAndNeverTheWhiteDefault() {
        for ((bookMode, scrollingBook) in listOf(false to false, true to false, true to true)) {
            for (paper in ReaderPaper.entries.drop(1)) {
                val palette = ReaderPaperAppearance.palette(paper)
                val css = stylesheet(ReaderPreferences(paper = paper), bookMode, scrollingBook)
                assertCssPalette(css, palette.Paper, palette.Ink, palette.Link, palette.Rule, palette.Accent, palette.Muted,
                    if (palette.dark) "dark" else "light")
                assertFalse(css.contains(ReaderPaperAppearance.Ink, ignoreCase = true))
            }
        }
    }

    private fun stylesheet(preferences: ReaderPreferences, bookMode: Boolean, scrollingBook: Boolean): String =
        ReaderCssTestSupport.stylesheet(FolioText.document(emptyList(), "en", preferences,
            bookMode = bookMode, scrollingBook = scrollingBook))

    private fun assertCssPalette(
        stylesheet: String, paper: String, ink: String, link: String, rule: String,
        accent: String, muted: String, scheme: String,
    ) {
        // Resolve the actual emitted definitions, not palette constants substituted by the test.
        // This checks both live book CSS and the static legacy document without demanding literals.
        val css = ReaderCssTestSupport.resolvePalette(stylesheet)
        assertTrue(css.contains(":root { color-scheme: $scheme; background: $paper; }"))
        val body = css.substringAfter("body {").substringBefore('}')
        assertTrue(body.contains("background: $paper;"))
        assertTrue(body.contains("color: $ink;"))
        assertTrue(css.contains(".folio-block.synonyms { color: $ink;"))
        assertTrue(css.contains("a { color: $link;"))
        assertTrue(css.contains("border-block: 1px solid $rule;"))
        assertTrue(css.contains("font-style: italic; color: $accent;"))
        assertTrue(css.contains(".folio-block.text-section { color: $muted;"))
    }

    private fun contrast(foreground: String, background: String): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }

    private fun luminance(hex: String): Double {
        fun channel(start: Int): Double {
            val value = hex.substring(start, start + 2).toInt(16) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(1) + .7152 * channel(3) + .0722 * channel(5)
    }
}

/** Test-only inspection of Folio's closed CSS grammar, not a browser/computed-style emulator. */
internal object ReaderCssTestSupport {
    private val style = Regex("<style\\b[^>]*>(.*?)</style>", RegexOption.DOT_MATCHES_ALL)
    private val rule = Regex("([^{}]+)\\{([^{}]*)}")

    fun stylesheet(html: String): String {
        assertTrue("Expected a complete document head", html.contains("</head>"))
        val styles = style.findAll(html.substringBefore("</head>")).toList()
        assertTrue("Expected at least one head stylesheet", styles.isNotEmpty())
        return styles.joinToString("\n") { it.groupValues[1] }
    }

    fun withoutHeadStyles(html: String): String {
        assertTrue("Expected a complete document head", html.contains("</head>"))
        return style.replace(html.substringBefore("</head>"), "") + "</head>" + html.substringAfter("</head>")
    }

    fun definitions(css: String): Map<String, String> = rule.findAll(css)
        .filter { it.groupValues[1].trim() == ":root" }
        .flatMap { it.groupValues[2].split(';').asSequence() }
        .map(String::trim).filter { it.startsWith("--reader-") && ':' in it }
        .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

    fun resolveValue(css: String, value: String): String {
        // A defined variable wins even if the authored fallback contains nested gradients.
        val variable = Regex("var\\(\\s*(--reader-[a-z-]+)\\s*(?:,.*)?\\)", RegexOption.DOT_MATCHES_ALL)
            .matchEntire(value.trim())?.groupValues?.get(1) ?: return value.trim()
        val definitions = definitions(css)
        assertTrue("Missing CSS definition for $variable", variable in definitions)
        return definitions.getValue(variable)
    }

    fun resolvePalette(css: String): String = Regex(
        "var\\(--reader-(?:paper|ink|header|on-header|accent|link|muted|rule|chapter|scheme)\\)",
    ).replace(css) { resolveValue(css, it.value) }
}