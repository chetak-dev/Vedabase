package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences

/** Pure production-HTML checks; no Android runtime, source corpus or external assets. */
class PaperTextureTest {
    private val source = listOf(
        Paragraph(10, "Bg 2.8", "PA_Verse_Number_Heading", "LE_Verse_Number"),
        Paragraph(11, "हरे कृष्ण", "PA_DevaNagariQ", ""),
        Paragraph(12, "<CS:\"Italic\">śabda</CS> & a\u0304", "PA_Purport", ""),
    )

    @Test
    fun lightAndSepiaUseOnlyCompositedRootGradientTextureInBothLayouts() {
        for (paper in listOf(ReaderPaper.LIGHT, ReaderPaper.SEPIA)) {
            for (scrolling in listOf(false, true)) {
                val stylesheet = css(document(paper, scrolling))
                val rules = textureRules(stylesheet)
                assertEquals(1, rules.size)
                val rule = rules.single()
                assertEquals("html:has(body.book-reading)", rule.groupValues[1].trim())
                val declarations = declarations(rule.groupValues[2])
                assertEquals(listOf("background-image", "background-size"), declarations.keys.toList())
                val image = declarations.getValue("background-image")
                assertTrue("Texture must follow the live paper choice", image.startsWith("var(--reader-texture"))
                val texture = ReaderCssTestSupport.resolveValue(stylesheet, image)
                assertEquals(3, Regex("radial-gradient\\(").findAll(texture).count())
                assertEquals(1, Regex("linear-gradient\\(").findAll(texture).count())
                assertEquals("31px 29px, 37px 41px, 23px 43px, 100vw 100vh", declarations["background-size"])
                // A fixed attachment forces main-thread scrolling, which judders during page turns.
                assertFalse(stylesheet.contains("background-attachment", ignoreCase = true))
                assertFalse("Texture must not download or embed an image", texture.contains("url(", ignoreCase = true))
                assertFalse(texture.contains("@import", ignoreCase = true))
            }
        }
    }

    @Test
    fun paperChoiceChangesOnlyCssNotBodyMarkupSourceAnchorsOrSecurityPolicy() {
        val original = source.map { it.copy() }
        val expected = record(10, "verse-number", "<div class=\"source-book-heading\">" +
            block("Bg 2.8", "verse-number") + "</div>" + block("TEXT 8 ( BG 2.8 )", "verse-number-heading")) +
            record(11, "devanagari", block("हरे कृष्ण", "devanagari")) +
            record(12, "purport purport-content", block("<em>śabda</em> &amp; a\u0304", "purport"))
        for (scrolling in listOf(false, true)) {
            // DARK is the texture-free control, with the exact same book layout and content.
            val plain = document(ReaderPaper.DARK, scrolling)
            for (paper in ReaderPaper.entries) {
                val html = document(paper, scrolling)
                assertEquals(expected, main(html))
                assertEquals(body(plain), body(html))
                assertEquals("Only head styles may change, not source markup or security metadata",
                    ReaderCssTestSupport.withoutHeadStyles(plain), ReaderCssTestSupport.withoutHeadStyles(html))
                assertEquals(listOf(10, 11, 12), Regex("data-record=\"(\\d+)\"").findAll(main(html))
                    .map { it.groupValues[1].toInt() }.toList())
                for (paragraph in source) {
                    assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(body(html)).count())
                }
                assertFalse(html.contains("<script", ignoreCase = true))
                assertEquals(BookPagination.CSP,
                    html.substringAfter("Content-Security-Policy\" content=\"").substringBefore('"'))
                assertFalse(css(html).contains("@import", ignoreCase = true))
                val urls = Regex("url\\(['\"]?([^)'\"]+)['\"]?\\)", RegexOption.IGNORE_CASE)
                    .findAll(css(html)).map { it.groupValues[1] }.toList()
                val fonts = ReferenceTypography.CSP_FONTS.split(' ')
                assertTrue("Only the existing bundled font URLs are allowed", urls.isNotEmpty() && fonts.containsAll(urls))
            }
        }
        assertEquals(original, source)
        assertEquals(main(document(ReaderPaper.LIGHT, false)), main(document(ReaderPaper.LIGHT, true)))
    }

    @Test
    fun darkAndLegacyNonBookDocumentsStayPlainWithoutTextureResources() {
        for (scrolling in listOf(false, true)) {
            val dark = css(document(ReaderPaper.DARK, scrolling))
            val texture = textureRules(dark).single()
            assertEquals("html:has(body.book-reading)", texture.groupValues[1].trim())
            val image = declarations(texture.groupValues[2]).getValue("background-image")
            assertTrue(image.startsWith("var(--reader-texture"))
            assertEquals("none", ReaderCssTestSupport.definitions(dark)["--reader-texture"])
            // An unused gradient fallback is harmless. Runtime native style tests check the
            // computed background-image; here check the definition actually selected by var().
            assertEquals("none", ReaderCssTestSupport.resolveValue(dark, image))
            val palette = ReaderCssTestSupport.resolvePalette(dark)
            assertTrue(palette.contains(":root { color-scheme: dark; background: #000000; }"))
            assertEquals("#000000", bodyDeclarations(palette)["background"])
        }
        for (paper in ReaderPaper.entries) {
            val legacy = css(FolioText.document(source, "en", ReaderPreferences(paper = paper)))
            assertTrue(textureRules(legacy).isEmpty())
            // Shared custom-property definitions are not an applied background image.
            assertFalse(legacy.contains("background-attachment:", ignoreCase = true))
        }
    }

    @Test
    fun textureKeepsReviewedReaderColorsSeparateFromBrightAppPaletteAndSharedTypography() {
        // Pure constants consumed by ReaderPaperColors / ReadingColors, not an Android theme mock.
        assertEquals(listOf("#FFFFFF", "#1F1F1F", "#B34E15", "#FFFFFF", "#5B4636",
            "#4E4E4E", "#6A6A6A", "#DCDCDC", "#F6F6F6"), listOf(
            ReaderPaperAppearance.Paper, ReaderPaperAppearance.Ink, ReaderPaperAppearance.Header,
            ReaderPaperAppearance.OnHeader, ReaderPaperAppearance.Accent, ReaderPaperAppearance.Link,
            ReaderPaperAppearance.Muted, ReaderPaperAppearance.Rule, ReaderPaperAppearance.Chapter))
        assertEquals(listOf("#B34E15", "#FFF9EF", "#FBEEDC", "#2F2B26", "#8A5A12", "#8A4A0E", "#FFFFFF", "#6C6053", "#E6CFAE"),
            listOf(ReaderAppearance.Header, ReaderAppearance.Paper, ReaderAppearance.Chapter, ReaderAppearance.Ink,
                ReaderAppearance.Accent, ReaderAppearance.Link, ReaderAppearance.OnHeader, ReaderAppearance.Muted,
                ReaderAppearance.Rule))
        for ((paper, background) in listOf(ReaderPaper.LIGHT to "#FFFFFF", ReaderPaper.SEPIA to "#F6ECD9", ReaderPaper.GREEN to "#E4EFE2")) {
            val stylesheet = css(document(paper, false))
            val nonBookCss = css(FolioText.document(source, "en", ReaderPreferences(paper = paper)))
            val bookBody = bodyDeclarations(ReaderCssTestSupport.resolvePalette(stylesheet))
            val legacyBody = bodyDeclarations(ReaderCssTestSupport.resolvePalette(nonBookCss))
            assertEquals(background, bookBody["background"])
            for (property in listOf("background", "color", "margin", "font-family", "font-size")) {
                assertTrue("Shared body must declare $property", property in legacyBody)
                assertEquals("Shared body $property must retain its value", legacyBody[property], bookBody[property])
            }
            assertEquals("1.00", ReaderCssTestSupport.definitions(stylesheet)["--reader-line-factor"])
            assertEquals("calc(1.3 * var(--reader-line-factor))", bookBody["line-height"])
            val legacyHeight = requireNotNull(legacyBody["line-height"])
            if (legacyHeight.contains("var(--reader-line-factor)")) {
                assertEquals("1.00", ReaderCssTestSupport.definitions(nonBookCss)["--reader-line-factor"])
                assertEquals(bookBody["line-height"], legacyHeight)
            } else assertEquals("1.3", legacyHeight)
        }
    }

    private fun document(paper: ReaderPaper, scrolling: Boolean) = FolioText.document(source, "en",
        ReaderPreferences(paper = paper), bookMode = true, scrollingBook = scrolling)

    private fun css(html: String) = ReaderCssTestSupport.stylesheet(html)
    private fun main(html: String) = html.substringAfter("<main").substringAfter('>').substringBefore("</main>")
    private fun body(html: String): String {
        assertTrue("Expected a complete body", html.contains("<body") && html.contains("</body>"))
        return "<body" + html.substringAfter("<body").substringBefore("</body>") + "</body>"
    }
    private fun declarations(rule: String) = rule.split(';').map(String::trim).filter { it.isNotEmpty() }
        .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    private fun bodyDeclarations(css: String) = declarations(css.substringAfter("body {").substringBefore('}'))
    private fun textureRules(css: String) = Regex("([^{}]+)\\{([^{}]*)}").findAll(css)
        .filter { it.groupValues[2].contains("background-image", ignoreCase = true) }.toList()
    private fun block(text: String, kind: String) = "<div class=\"folio-block $kind\" dir=\"auto\">$text</div>"
    private fun record(id: Int, classes: String, content: String) =
        "<div class=\"record $classes\" id=\"record-$id\" data-record=\"$id\" dir=\"auto\">$content</div>"
}