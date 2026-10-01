package org.vedareader.reader

import org.junit.Assert.*
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure palette/CSS contracts. No Android, database, source corpus, or preference writes. */
class ReaderStyleTest {
    @Test
    fun lightRemainsReviewedDefaultAndAllPapersHaveReadableTextAndHeaderContrast() {
        assertEquals(ReaderStyle(), ReaderStyle(ReaderPaper.LIGHT, 100))
        assertEquals(100, ReaderPreferences().lineSpacing)
        val light = ReaderPaperAppearance.palette(ReaderPaper.LIGHT)
        assertEquals(ReaderPalette(ReaderPaperAppearance.Paper, ReaderPaperAppearance.Ink,
            ReaderPaperAppearance.Header, ReaderPaperAppearance.OnHeader, ReaderPaperAppearance.Accent,
            ReaderPaperAppearance.Link, ReaderPaperAppearance.Muted, ReaderPaperAppearance.Rule,
            ReaderPaperAppearance.Chapter, false), light)
        assertEquals("#FFFFFF", light.Paper)
        assertEquals("#F6ECD9", ReaderStyle(ReaderPaper.SEPIA).palette.Paper)
        assertEquals("#E4EFE2", ReaderStyle(ReaderPaper.GREEN).palette.Paper)
        assertEquals("#2F3136", ReaderStyle(ReaderPaper.GRAY).palette.Paper)
        assertEquals("#000000", ReaderStyle(ReaderPaper.DARK).palette.Paper)
        for (paper in ReaderPaper.entries) {
            val palette = ReaderPaperAppearance.palette(paper)
            assertEquals(paper == ReaderPaper.DARK || paper == ReaderPaper.GRAY, palette.dark)
            val pairs = listOf(palette.Ink to palette.Paper, palette.Link to palette.Paper,
                palette.Accent to palette.Paper, palette.Muted to palette.Paper,
                palette.OnHeader to palette.Header, palette.Ink to palette.Chapter)
            for ((ink, background) in pairs) {
                assertTrue(ink, ink.matches(Regex("#[0-9a-fA-F]{6}")))
                assertTrue(background, background.matches(Regex("#[0-9a-fA-F]{6}")))
                assertTrue("$paper: $ink on $background", contrast(ink, background) >= 4.5)
            }
        }
    }

    @Test
    fun spacingClampsWithoutOverflowAndCssIsClosedAndLocaleIndependent() {
        assertEquals(85, ReaderStyle.MIN_LINE_SPACING)
        assertEquals(150, ReaderStyle.MAX_LINE_SPACING)
        assertEquals(5, ReaderStyle.LINE_SPACING_STEP)
        assertEquals(150L, ReaderMotion.pageTurn)
        assertEquals(170L, ReaderMotion.transition)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for ((input, expected) in listOf(Int.MIN_VALUE to 85, 84 to 85, 85 to 85,
                100 to 100, 117 to 117, 150 to 150, 151 to 150, Int.MAX_VALUE to 150)) {
                for (paper in ReaderPaper.entries) {
                    val raw = ReaderStyle(paper, input)
                    val normalized = raw.normalized()
                    assertEquals(expected, normalized.lineSpacing)
                    assertEquals(paper, normalized.paper)
                    assertEquals(normalized, normalized.normalized())
                    val css = raw.css()
                    assertEquals(normalized.css(), css)
                    val factor = "$expected".padStart(3, '0').let { "${it.dropLast(2)}.${it.takeLast(2)}" }
                    assertTrue(css.contains("--reader-line-factor: $factor;"))
                    assertTrue(css.contains("--reader-paper: ${raw.palette.Paper};"))
                    assertTrue(css.contains("--reader-ink: ${raw.palette.Ink};"))
                    for (unsafe in listOf("<", ">", "url(", "@import", "javascript", "expression(", "http", "NaN", "Infinity")) {
                        assertFalse("CSS must contain only app-owned values: $unsafe", css.contains(unsafe, true))
                    }
                    assertEquals(raw.palette.dark, css.contains("--reader-texture: none;"))
                }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test
    fun bookStylesUseOnlyVariablesAndPreserveSourceBodyFontsAndSecurityInBothLayouts() {
        for (scrolling in listOf(false, true)) {
            val default = document(ReaderPreferences(), book = true, scrolling = scrolling)
            assertEquals(default, document(ReaderPreferences(20, ReaderPaper.LIGHT, 100), true, scrolling))
            val rules = default.substringAfter("<style>").substringBefore("</style>")
            assertFalse("Book rules must not pin default ink", rules.contains(ReaderPaperAppearance.Ink))
            assertFalse("Book rules must not pin default paper", rules.contains(ReaderPaperAppearance.Paper))
            for (variable in listOf("paper", "ink", "link", "rule", "accent", "muted", "scheme", "texture")) {
                assertTrue(variable, rules.contains("var(--reader-$variable)"))
            }
            for (height in listOf("1.3", "1.4", "1.2")) {
                assertTrue(rules.contains("line-height: calc($height * var(--reader-line-factor));"))
            }
            for (paper in ReaderPaper.entries) {
                val html = document(ReaderPreferences(paper = paper, lineSpacing = 150), true, scrolling)
                assertEquals(default.substringAfter("<body"), html.substringAfter("<body"))
                assertEquals(BookPagination.CSP, html.substringAfter("Content-Security-Policy\" content=\"").substringBefore('"'))
                assertEquals(1, Regex(Regex.escape(ReaderStyle.STYLE_OPEN)).findAll(html).count())
                assertTrue(html.indexOf(ReaderStyle.STYLE_OPEN) < html.indexOf("</head>"))
                assertTrue(html.contains(ReaderStyle(paper, 150).css()))
                assertFalse(html.contains("<script", true))
                assertTrue(html.contains("&lt;script&gt;"))
                assertTrue(html.contains("नमूना śabda"))
                assertEquals(rules, html.substringAfter("<style>").substringBefore("</style>"))
            }
        }
    }

    @Test
    fun preLoadReplacementTouchesOnlyOwnedHeadStyleAndLegacyDefaultMarkupStaysStatic() {
        val html = document(ReaderPreferences(), book = true)
        val style = ReaderStyle(ReaderPaper.DARK, 85)
        val changed = requireNotNull(style.documentHtml(html, book = true))
        assertEquals(html.substringAfter("<body"), changed.substringAfter("<body"))
        assertEquals(stripLiveStyle(html), stripLiveStyle(changed))
        assertTrue(changed.contains(style.css()))
        assertNull(style.documentHtml(html.replace(ReaderStyle.STYLE_OPEN, "<style>"), book = true))
        assertNull(style.documentHtml(html.replace("</head>", "${ReaderStyle.STYLE_OPEN}</style></head>"), book = true))
        assertNull(style.documentHtml("<body>not an app document</body>", book = true))

        val legacy = document(ReaderPreferences(), book = false)
        assertEquals(legacy, document(ReaderPreferences(20, ReaderPaper.LIGHT, 100), false))
        assertFalse(legacy.contains(ReaderStyle.STYLE_ID))
        assertFalse(legacy.contains("--reader-"))
        assertTrue(legacy.contains("line-height: 1.3;"))
        assertTrue(legacy.contains("background: ${ReaderPaperAppearance.Paper};"))
        val staticDark = requireNotNull(style.documentHtml(legacy, book = false))
        assertEquals(legacy.substringAfter("<body"), staticDark.substringAfter("<body"))
        assertEquals(legacy.substringBefore("<style>"), staticDark.substringBefore("<style>"))
        assertFalse(staticDark.contains(ReaderStyle.STYLE_ID))
        assertFalse(staticDark.contains("<script", true))
        assertTrue(staticDark.contains("--reader-line-factor: 0.85;"))
    }

    private fun document(preferences: ReaderPreferences, book: Boolean, scrolling: Boolean = false) =
        FolioText.document(listOf(Paragraph(10, "नमूना śabda <script>source()</script>", "PA_Purport", "")),
            "en", preferences, bookMode = book, scrollingBook = scrolling)

    private fun stripLiveStyle(html: String) = html.substringBefore(ReaderStyle.STYLE_OPEN) +
        html.substringAfter(ReaderStyle.STYLE_OPEN).substringAfter("</style>")

    private fun contrast(a: String, b: String): Double {
        fun luminance(hex: String): Double {
            fun channel(offset: Int): Double {
                val value = hex.substring(offset, offset + 2).toInt(16) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(1) + .7152 * channel(3) + .0722 * channel(5)
        }
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }
}