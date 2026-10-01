package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences

/** Shared book formatting, using synthetic source only and no Android runtime. */
class SharedReadingFormatTest {
    private val source = listOf(
        Paragraph(10, "Bg 1.4", "PA_Verse_Number_Heading", "LE_Verse_Number"),
        Paragraph(11, "नमूना कृष्ण", "PA_DevaNagariQ", ""),
        Paragraph(12, "Synthetic śloka ā a\u0304", "PA_Verse", "LE_Verse_Text"),
        Paragraph(13, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
        Paragraph(14, "<CS:\"Italic\">śabda</CS> — synthetic word & meaning.", "PA_Synonyms", ""),
        Paragraph(15, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
        Paragraph(16, "A complete synthetic translation.", "PA_Translation", ""),
        Paragraph(17, "PURPORT", "PA_Titles", "LE_Verse_Section"),
        Paragraph(18, "Synthetic prose with <CS:\"Italic\">original emphasis</CS>.", "PA_Purport", ""),
        Paragraph(19, "Synthetic quoted verse.", "PA_Verse_in_purp", ""),
    ) + List(30) { index ->
        Paragraph(20 + index, "Synthetic full-body paragraph $index. " +
            "The original complete prose remains available in either reading mode. ".repeat(3), "PA_Purport", "")
    }

    @Test
    fun bothModesKeepIdenticalMainHtmlCompleteBodiesAndSourceAnchors() {
        val original = source.map { it.copy() }
        val paged = document(scrolling = false)
        val scrolling = document(scrolling = true)
        assertEquals(main(paged), main(scrolling))
        assertEquals(body(paged), body(scrolling).replace(
            "class=\"book-reading book-scrolling\"", "class=\"book-reading\""))
        assertTrue(body(paged).startsWith("<body class=\"book-reading\""))
        assertTrue(body(scrolling).startsWith("<body class=\"book-reading book-scrolling\""))
        for (html in listOf(paged, scrolling)) {
            val content = main(html)
            assertTrue(content.startsWith("<main class=\"reader book-flow\">"))
            assertFalse(content.contains("<details"))
            assertFalse(content.contains("<summary"))
            assertTrue(content.contains("TEXT 4 ( BG 1.4 )"))
            assertTrue(content.contains("<em>śabda</em> — synthetic word &amp; meaning."))
            assertTrue(content.contains("<em>original emphasis</em>"))
            assertTrue(content.contains("नमूना कृष्ण"))
            assertTrue(content.contains("Synthetic śloka ā a\u0304"))
            assertEquals(source.map { it.record }, Regex("data-record=\"(\\d+)\"").findAll(content)
                .map { it.groupValues[1].toInt() }.toList())
            source.forEach { paragraph ->
                assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(content).count())
            }
            source.filter { it.record >= 20 }.forEach { assertTrue(content.contains(it.text)) }
        }
        assertEquals(original, source)
    }

    @Test
    fun bothModesShareFontsBoldTranslationNormalPurportHeadingAndNeutralItalicVerse() {
        val pagedCss = css(document(scrolling = false))
        val scrollingCss = css(document(scrolling = true))
        val faces = Regex("@font-face\\s*\\{[^}]*}").findAll(pagedCss).map { it.value }.toList()
        assertEquals(7, faces.size)
        assertEquals(faces, Regex("@font-face\\s*\\{[^}]*}").findAll(scrollingCss).map { it.value }.toList())
        assertEquals(20, ReaderPreferences().fontSize)
        for (stylesheet in listOf(pagedCss, scrollingCss)) {
            val normalized = stylesheet.replace(Regex("\\s+"), " ")
            for (rule in listOf(
                "font-family: 'Fira Sans Condensed', 'Reader Serif Devanagari',",
                "font-size: 20px;",
                "padding: .65rem 16px 1.25rem;",
                ".book-flow > .record, .book-flow > .verse-group { padding-inline: 16px; }",
                ".folio-block.translation { font-weight: 700; }",
                ".book-flow .folio-block.text-section { color: inherit; font-size: 1em; letter-spacing: normal; font-weight: 400; }",
                ".book-flow .book-translation-heading .folio-block { color: inherit; font-size: 1em; font-weight: 700; }",
                ".book-flow .book-purport-heading .folio-block { color: inherit; font-size: 1em; font-weight: 400; }",
                ".book-flow .folio-block.verse, .book-flow .folio-block.verse-line, .book-flow .folio-block.verse-in-purport { color: inherit; }",
                ".book-flow .record.book-synonyms-heading { display: none; }",
            )) assertTrue("Missing shared rule: $rule", normalized.contains(rule))
            assertTrue(normalized.contains(".folio-block.verse, .folio-block.verse-in-purport, " +
                ".folio-block.verse-line { text-align: center; font-style: italic;"))
        }
    }

    @Test
    fun scrollingAppendsOnlyAxisAndViewportOverridesAfterAllSharedVisualRules() {
        val pagedCss = css(document(scrolling = false))
        val scrollingCss = css(document(scrolling = true))
        assertTrue("Scrolling must retain the complete shared stylesheet in cascade order", scrollingCss.startsWith(pagedCss))
        assertTrue(pagedCss.contains("column-count: 1; column-gap: 0; column-fill: auto;"))
        assertTrue(pagedCss.contains("height: calc(100vh - 32px); margin: 16px 0; padding: 0;"))
        val override = scrollingCss.removePrefix(pagedCss).trim()
        val rules = Regex("([^{}]+)\\{([^{}]*)}").findAll(override).toList()
        assertEquals(3, rules.size)
        assertEquals(listOf(
            "html:has(body.book-scrolling)",
            "body.book-reading.book-scrolling",
            ".book-scrolling .book-flow.reader",
        ), rules.map { it.groupValues[1].trim() })
        assertEquals(listOf(
            listOf("height: auto"),
            listOf("height: auto"),
            listOf("width: 100%", "height: auto", "column-count: auto", "margin: 0", "padding-block: 16px"),
        ), rules.map { rule -> rule.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() } })
        assertEquals("No additional font, colour, visibility or body rules in the scrolling override",
            "", override.replace(Regex("[^{}]+\\{[^{}]*}"), "").trim())
    }

    private fun document(scrolling: Boolean) = FolioText.document(source, "en", ReaderPreferences(),
        bookMode = true, scrollingBook = scrolling)

    private fun css(html: String) = html.substringAfter("<style>").substringBefore("</style>")
    private fun main(html: String) = "<main" + html.substringAfter("<main").substringBefore("</main>") + "</main>"
    private fun body(html: String) = "<body" + html.substringAfter("<body").substringBefore("</body>") + "</body>"
}