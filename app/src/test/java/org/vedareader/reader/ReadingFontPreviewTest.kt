package org.vedareader.reader

import org.junit.Assert.*
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences

class ReadingFontPreviewTest {
    @Test
    fun legacyReadingFontFlagKeepsTheSingleBundledTypefaceBodyAndNavigationLayout() {
        val source = listOf(
            Paragraph(1, "Kṛṣṇa ā a\u0301 <IT+>quoted<IT-> <BD+><IT+>bold italic<IT-><BD->", "PA_Purport", ""),
            Paragraph(2, "Synthetic translation", "PA_Translation", ""),
            Paragraph(3, "कृष्ण धर्मक्षेत्रे", "PA_DevaNagariQ", ""),
            Paragraph(4, "<FT:\"@import url(https://invalid.example/source-font.css)\">" +
                "<FT:\"url(https://reader.local/fonts/arbitrary.ttf)\"><PT:99>Original body", "", ""),
        )
        val originalSource = source.toList()
        val faces = listOf(
            Triple("FiraSansCondensed-Regular.ttf", "400", "normal"),
            Triple("FiraSansCondensed-Italic.ttf", "400", "italic"),
            Triple("FiraSansCondensed-Medium.ttf", "500", "normal"),
            Triple("FiraSansCondensed-SemiBold.ttf", "600", "normal"),
            Triple("FiraSansCondensed-Bold.ttf", "700", "normal"),
            Triple("FiraSansCondensed-BoldItalic.ttf", "700", "italic"),
            Triple("NotoSerifDevanagari.ttf", "100 900", "normal"),
        )
        val urls = faces.map { "https://reader.local/fonts/${it.first}" }
        val expectedFaces = faces.map { (file, weight, style) ->
            val family = if (file == "NotoSerifDevanagari.ttf") "Reader Serif Devanagari" else "Fira Sans Condensed"
            "@font-face { font-family: '$family'; font-style: $style; font-weight: $weight; " +
                "src: url('https://reader.local/fonts/$file') format('truetype'); }"
        }
        assertEquals("Fira Sans Condensed", ReferenceTypography.LATIN)
        assertEquals("Reader Serif Devanagari", ReferenceTypography.DEVANAGARI)
        assertEquals(urls.joinToString(" "), ReferenceTypography.CSP_FONTS)
        assertEquals(expectedFaces, ReferenceTypography.CSS.trim().lines())
        for ((compactVerse, bookMode) in listOf(false to false, true to false, false to true, true to true)) {
            val original = FolioText.document(source, "en", ReaderPreferences(),
                compactVerse = compactVerse, bookMode = bookMode)
            for (legacyFlag in listOf(false, true)) {
                val preview = FolioText.document(source, "en", ReaderPreferences(),
                    compactVerse = compactVerse, bookMode = bookMode, useReadingFont = legacyFlag)
                assertEquals("The legacy flag must not change fonts, body, CSP, or layout", original, preview)
            }
            val stylesheet = original.substringAfter("<style>").substringBefore("</style>")
            assertEquals(expectedFaces, Regex("@font-face \\{[^}]*}").findAll(stylesheet).map { it.value }.toList())
            assertEquals(urls, Regex("url\\('([^']+)'\\)").findAll(stylesheet).map { it.groupValues[1] }.toList())
            assertTrue(stylesheet.contains("font-family: 'Fira Sans Condensed', 'Reader Serif Devanagari',"))
            assertTrue(stylesheet.contains("font-family: 'Reader Serif Devanagari', \"Noto Serif Devanagari\","))
            assertTrue(stylesheet.contains(".folio-block.translation { font-weight: 700; }"))
            assertTrue(stylesheet.contains("font-size: 20px;"))
            assertTrue(stylesheet.contains("padding: .65rem 16px 1.25rem;"))
            assertEquals(bookMode, stylesheet.contains("book-flow"))
            assertEquals(bookMode, stylesheet.contains("column-count"))
            if (bookMode) {
                assertTrue(stylesheet.contains("height: calc(100vh - 32px); margin: 16px 0; padding: 0;"))
                assertTrue(stylesheet.contains("padding-inline: 16px;"))
                assertEquals(urls.joinToString(" "), original.substringAfter("font-src ").substringBefore(';'))
            }
            assertTrue(original.contains("Kṛṣṇa ā a\u0301 <em>quoted</em> <strong><em>bold italic</em></strong>"))
            assertTrue(original.contains("कृष्ण धर्मक्षेत्रे"))
            assertTrue(original.contains("Synthetic translation"))
            assertTrue(original.contains("Original body"))
            for (forbidden in listOf("@import", "invalid.example", "arbitrary.ttf", "Literata", "Georgia", "<script")) {
                assertFalse(forbidden, original.contains(forbidden, ignoreCase = true))
            }
        }
        assertEquals(originalSource, source)
    }
}
