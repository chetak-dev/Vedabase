package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences

/** Synthetic source only; display labels never replace the retained source HTML or records. */
class BookReferenceFormatTest {
    @Test
    fun bgReferenceUsesOnlyVerseNumberMetadataAndRetainsSourceHtmlUnicodeAndIds() {
        val source = "<PS:\"Verse Number Heading\"><CS:\"Italic\">Bg 1.4</CS>"
        val unicode = "Kṛṣṇa कृष्ण বাংলা ā a\u0304"
        val metadata = listOf(
            Triple("", "LE_Verse_Number", "verse-number"),
            Triple("", "LE_Verse_Number_Heading", "verse-number-heading"),
            Triple("LE_Verse_Number", "", "verse-number"),
            Triple("PA_Verse_Number_Heading", "", "verse-number-heading"),
            Triple("PA_Purport", "LE_Verse_Number", "verse-number"),
        )
        for ((style, level, kind) in metadata) {
            val paragraphs = listOf(
                Paragraph(10, source, style, level),
                Paragraph(11, "<CS:\"Italic\">$unicode</CS> & original", "", ""),
            )
            val original = paragraphs.map { it.copy() }
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true)
            val sourceHtml = block("<em>Bg 1.4</em>", "verse-number-heading")
            assertEquals(sourceHtml, FolioText.fragment(source))
            assertEquals(
                record(paragraphs[0], kind, "<div class=\"source-book-heading\">$sourceHtml</div>" +
                    block("TEXT 4 ( BG 1.4 )", "verse-number-heading")) +
                    record(paragraphs[1], "text", block("<em>$unicode</em> &amp; original", "text")),
                main(html),
            )
            assertAnchors(paragraphs, html)
            assertEquals(original, paragraphs)
        }
    }

    @Test
    fun combinedBgReferencesDisplayOneRangeWithoutReplacingOriginalHeadingHtml() {
        for (reference in listOf(
            "Bg 1.16, Bg 1.17, Bg 1.18",
            "Bg 1.16 - 18, Bg 1.17, Bg 1.18",
            "Bg 1.16–18",
            "01. Bg 1.16, Bg 1.17, Bg 1.18",
        )) {
            val heading = Paragraph(16, "<PS:\"Verse Number Heading\">$reference",
                "PA_Verse_Number_Heading", "LE_Verse_Number")
            val original = heading.copy()
            val html = FolioText.document(listOf(heading), "en", ReaderPreferences(), bookMode = true)
            assertEquals(record(heading, "verse-number",
                "<div class=\"source-book-heading\">${block(reference, "verse-number-heading")}</div>" +
                    block("TEXT 16-18 ( BG 1.16-18 )", "verse-number-heading")), main(html))
            assertAnchors(listOf(heading), html)
            assertEquals(original, heading)
        }
    }

    @Test
    fun everyBookGetsTheSameTextLabelFromItsCombinedOrSingleReference() {
        for ((reference, label) in listOf(
            "SB 1.10.34, SB 1.10.35, SB 1.10.34-35" to "TEXT 34-35 ( SB 1.10.34-35 )",
            "SB 1.1.1" to "TEXT 1 ( SB 1.1.1 )",
            "Madhya 1.11\u201312" to "TEXT 11-12 ( Madhya 1.11-12 )",
            "CB \u0100di-kha\u1e47\u1e0da 1.1-2" to "TEXT 1-2 ( CB \u0100di-kha\u1e47\u1e0da 1.1-2 )",
        )) {
            val heading = Paragraph(16, "<PS:\"Verse Number Heading\">$reference", "PA_Verse_Number_Heading", "LE_Verse_Number")
            val html = FolioText.document(listOf(heading), "en", ReaderPreferences(), bookMode = true)
            assertEquals(record(heading, "verse-number",
                "<div class=\"source-book-heading\">${block(reference, "verse-number-heading")}</div>" +
                    block(label, "verse-number-heading")), main(html))
        }
    }

    @Test
    fun unknownMalformedAndProseReferencesKeepOriginalHtmlWithoutDisplayLabels() {
        val references = listOf(
            "Bg 1.", "Bg 0.4", "Bg 1.0", "Bg 1.18 - 16",
            "Bg 1.16, Bg 1.18", "Bg 1.16, Bg 2.17", "Bg 1.4 trailing prose कृष्ण a\u0304", "Verse 4", "SB 5.1 Summary",
        )
        for (reference in references) {
            val heading = Paragraph(10, "<PS:\"Verse Number Heading\">$reference",
                "PA_Verse_Number_Heading", "LE_Verse_Number")
            val html = FolioText.document(listOf(heading), "en", ReaderPreferences(), bookMode = true)
            assertEquals(record(heading, "verse-number", FolioText.fragment(heading.text)), main(html))
            assertFalse(main(html).contains("source-book-heading"))
            assertAnchors(listOf(heading), html)
        }
        val prose = listOf(
            Paragraph(20, "Bg 1.4", "", "") to "text",
            // Inline PS alone is not record metadata, even when its text is an exact reference.
            Paragraph(21, "<PS:\"Verse Number Heading\">Bg 1.4", "", "") to "text",
            Paragraph(22, "Bg 1.4", "PA_Purport", "") to "purport purport-content",
            Paragraph(23, "Bg 1.4", "PA_Verse_Number_Heading", "LE_Purport") to "purport purport-content",
            Paragraph(24, "Bg 1.4", "PA_Verse_Number_Heading", "LE_Chapter") to "chapter",
            Paragraph(25, "Bg 1.4", "PA_Titles", "LE_Verse_Section") to "text-section",
        )
        for ((paragraph, classes) in prose) {
            val html = FolioText.document(listOf(paragraph), "en", ReaderPreferences(), bookMode = true)
            val sourceHtml = if (paragraph.text.startsWith("<PS:")) FolioText.fragment(paragraph.text)
                else block(paragraph.text, classes.substringBefore(' '))
            assertEquals(record(paragraph, classes, sourceHtml), main(html))
            assertFalse(main(html).contains("source-book-heading"))
            assertFalse(main(html).contains("TEXT 4 ( BG 1.4 )"))
            assertAnchors(listOf(paragraph), html)
        }
    }

    @Test
    fun verseModeNeverNormalizesReferencesAndKeepsCompactLabelBehavior() {
        val paragraphs = listOf(
            Paragraph(10, "<PS:\"Verse Number Heading\">Bg 1.4", "PA_Verse_Number_Heading", "LE_Verse_Number"),
            Paragraph(16, "<PS:\"Verse Number Heading\">Bg 1.16, Bg 1.17, Bg 1.18", "PA_Verse_Number_Heading", ""),
        )
        for (compact in listOf(false, true)) {
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(), compactVerse = compact)
            val repeated = if (compact) " repeated-label" else ""
            assertEquals(record(paragraphs[0], "verse-number$repeated", FolioText.fragment(paragraphs[0].text)) +
                record(paragraphs[1], "verse-number-heading$repeated", FolioText.fragment(paragraphs[1].text)), main(html))
            assertFalse(html.contains("source-book-heading"))
            assertFalse(main(html).contains("TEXT 4 ( BG 1.4 )"))
            assertFalse(main(html).contains("TEXT 16-18 ( BG 1.16-18 )"))
            assertTrue(html.contains(".verse-reading .record.repeated-label { display: none; }"))
            assertAnchors(paragraphs, html)
        }
    }

    @Test
    fun bookSectionStylesKeepSynonymsOpenNeutralVerseInkAndSharedDefaultTypography() {
        val paragraphs = listOf(
            Paragraph(10, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
            Paragraph(11, "<CS:\"Italic\">śabda</CS> — word.", "PA_Synonyms", ""),
            Paragraph(12, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(13, "Sample.", "PA_Translation", ""),
            Paragraph(14, "PURPORT", "PA_Titles", "LE_Verse_Section"),
            Paragraph(15, "Note.", "PA_Purport", ""),
        )
        val html = FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true)
        assertEquals(record(paragraphs[0], "text-section book-synonyms-heading", block("SYNONYMS", "text-section")) +
            record(paragraphs[1], "synonyms", block("<em>śabda</em> — word.", "synonyms")) +
            record(paragraphs[2], "text-section book-translation-heading", block("TRANSLATION", "text-section")) +
            record(paragraphs[3], "translation", block("Sample.", "translation")) +
            record(paragraphs[4], "text-section book-purport-heading", block("PURPORT", "text-section")) +
            record(paragraphs[5], "purport purport-content", block("Note.", "purport")), main(html))
        assertFalse(main(html).contains("<details"))
        assertFalse(main(html).contains("<summary"))
        assertAnchors(paragraphs, html)
        val css = html.substringAfter("<style>").substringBefore("</style>").replace(Regex("\\s+"), " ")
        for (rule in listOf(
            ".book-flow .source-book-heading { display: none; }",
            ".book-flow .record.book-synonyms-heading { display: none; }",
            ".book-flow .folio-block.verse, .book-flow .folio-block.verse-line, .book-flow .folio-block.verse-in-purport { color: inherit; }",
            ".book-flow .book-translation-heading .folio-block { color: inherit; font-size: 1em; font-weight: 700; }",
            ".book-flow .book-purport-heading .folio-block { color: inherit; font-size: 1em; font-weight: 400; }",
            ".folio-block.verse-number, .folio-block.verse-number-heading, .folio-block.text-section { text-align: center; font-weight: 700; }",
            ".folio-block.translation { font-weight: 700; }",
            "font-family: 'Fira Sans Condensed', 'Reader Serif Devanagari',",
            "font-size: 20px;",
            "padding: .65rem 16px 1.25rem;",
            ".book-flow > .record, .book-flow > .verse-group { padding-inline: 16px; }",
        )) assertTrue(rule, css.contains(rule))
        assertEquals(20, ReaderPreferences().fontSize)
        val verse = FolioText.document(paragraphs, "en", ReaderPreferences(), compactVerse = true)
        assertTrue(main(verse).contains("<details class=\"synonyms-group\"><summary"))
        assertFalse(main(verse).contains("book-translation-heading"))
        assertFalse(main(verse).contains("book-purport-heading"))
    }

    private fun main(html: String) = html.substringAfter("<main").substringAfter('>').substringBefore("</main>")

    private fun block(text: String, kind: String) = "<div class=\"folio-block $kind\" dir=\"auto\">$text</div>"

    private fun record(paragraph: Paragraph, classes: String, content: String) =
        "<div class=\"record $classes\" id=\"record-${paragraph.record}\" " +
            "data-record=\"${paragraph.record}\" dir=\"auto\">$content</div>"

    private fun assertAnchors(paragraphs: List<Paragraph>, html: String) {
        val content = main(html)
        assertEquals(paragraphs.map { it.record }, Regex("data-record=\"(\\d+)\"").findAll(content)
            .map { it.groupValues[1].toInt() }.toList())
        for (paragraph in paragraphs) {
            assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(content).count())
            assertEquals(1, Regex("data-record=\"${paragraph.record}\"").findAll(content).count())
        }
    }
}