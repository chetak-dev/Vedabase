package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentTitlesTest {
    @Test
    fun completeReferencesAndDuplicateAggregateCollapseWithoutChangingSourceFields() {
        val source = "Bg 1.16, Bg 1.17, Bg 1.18, Bg 1.16-18"
        val node = ContentNode(123, 99, source, 156, 4, 7, "fixture")
        val snapshot = node.copy()

        assertEquals("BG 1.16-18", node.displayTitle)
        assertEquals("BG 1.16-18", node.location("library").title)
        assertEquals(snapshot, node)
        assertEquals(source, node.title)
    }

    @Test
    fun rangeOnlyAndAdjacentRangesUseTheSameDisplay() {
        for (source in listOf(
            "Bg 1.16-18", "BG 1.16 - 18", "bg1.16–18",
            "BG1.16, BG1.17, BG1.18", "Bg 1.16-17, Bg 1.18",
            "Bg 1.16-18, Bg 1.16, Bg 1.17, Bg 1.18",
            "Bg 1.18, Bg 1.16, Bg 1.17, Bg 1.17", "Bg 1.16, 17, 18", "Bg 1.16, 17-18",
        )) assertEquals(source, "BG 1.16-18", ContentTitles.display(source))
    }

    @Test
    fun everyBookCollapsesItsOwnCombinedVersesKeepingTheSourceAbbreviation() {
        assertEquals("SB 1.4.17-18", ContentTitles.display("SB 1.4.17, SB 1.4.18, SB 1.4.17-18"))
        assertEquals("SB 1.16.26-30", ContentTitles.display("SB 1.16.26, SB 1.16.27, SB 1.16.28, SB 1.16.29, SB 1.16.30, SB 1.16.26-30"))
        assertEquals("SB 1.2.28-29", ContentTitles.display("SB 1.2.28-29"))
        assertEquals("Madhya 1.11-12", ContentTitles.display("Madhya 1.11\u201312"))
        assertEquals("CB Madhya-kha\u1e47\u1e0da 1.1-2", ContentTitles.display("CB Madhya-kha\u1e47\u1e0da 1.1-2"))
        assertEquals("CC \u0100di 1.16-17", ContentTitles.display("CC \u0100di 1.16, CC \u0100di 1.17"))
        assertEquals("SB 2.4.2", ContentTitles.display("SB 2.4.2"))
        assertEquals("Madhya 3.18", ContentTitles.display("Madhya 3.18"))
        unchanged("SB 5.1 Summary", "Verse 7", "HBKL 1", "1.2.3", "1.2.3, 1.2.4",
            "\u015ar\u012bmad-Bh\u0101gavatam 1.2.3 -- November 3, 1976, Bombay", "Room Conversation -- November 3, 1976, V\u1e5bnd\u0101vana")
    }

    @Test
    fun ordinarySingleReferenceUppercasesOnlyRecognizedBg() {
        assertEquals("BG 1.16", ContentTitles.display("Bg 1.16"))
        assertEquals("BG 1.16", ContentTitles.display("BG 1.16"))
        assertEquals("BG 1.16", ContentTitles.display("Bg 1.16-16, Bg 1.16"))
    }

    @Test
    fun differentChaptersBooksAndDiscontinuousSetsRemainUnchanged() {
        unchanged(
            "Bg 1.16, Bg 2.17", "Bg 1.16, SB 1.17", "SB 1.1.16, SB 1.2.17",
            "Bg 1.16, Bg 1.18", "Bg 1.16-18, Bg 1.20-21", "SB 1.1.16, Bg 1.17",
        )
    }

    @Test
    fun partialOrMalformedTitlesAreNeverPartlyFormatted() {
        unchanged(
            "Bg 1.16,", ", Bg 1.16", "Bg 1.16,, Bg 1.17", "17, Bg 1.16",
            "Bg 1.18-16", "Bg 1.16-", "Bg 1.16 - Bg 1.18", "Bg 1.16-2.18",
            "Bg 1.16a", "Bg 1.16, Bg 1.17 commentary",
            "Bg 0.16", "Bg 1.0", "Bg 01.16", "Bg 1.016", "Bg 1.16/17",
            "Bg 1.16; Bg 1.17", "Bg 1.2147483648",
            "Bg 1.16—18", "Bg १.१६", "Bg 1.16\nBg 1.17",
        )
    }

    @Test
    fun arbitraryUnicodeAndBookTitlesRetainExistingTrimAndTwoDigitPrefixBehavior() {
        val title = "Śrī नमूना বাংলা — a\u0304"
        assertEquals(title, ContentTitles.display("  02. $title  "))
        assertEquals("Bhagavad-gītā As It Is", ContentTitles.display("01. Bhagavad-gītā As It Is"))
        assertEquals("BG 1.16-18", ContentTitles.display(" 02. Bg 1.16, Bg 1.17, Bg 1.18 "))
        assertEquals("", ContentTitles.display(" \t\n "))
        assertEquals("", ContentTitles.display("02. "))
        unchanged(title, "7. A title", "123. A title", "BG — Introduction", "BG 1.16 — नमूना")
    }

    @Test
    fun largeRangesAreMergedAsIntervalsWithoutIntegerOverflow() {
        assertEquals("BG 1.1-2147483647", ContentTitles.display("Bg 1.1-2147483647, Bg 1.2147483647"))
        assertEquals("BG 1.2147483646-2147483647", ContentTitles.display("Bg 1.2147483646, Bg 1.2147483647"))
    }

    @Test
    fun blankSiblingBetweenConsecutiveReferencesIsInterpolatedOnlyWhenUnambiguous() {
        fun node(record: Int, title: String) = ContentNode(record, 1635, title, record + 10, 4, 7, "")
        val gita = listOf(node(1719, "Bg 1.6"), node(1732, ""), node(1745, "Bg 1.8"))
        val filled = ContentTitles.interpolateBlank(gita)
        assertEquals(listOf("Bg 1.6", "Bg 1.7", "Bg 1.8"), filled.map { it.title })
        assertEquals("BG 1.7", filled[1].displayTitle)
        assertEquals(gita[1].copy(title = "Bg 1.7"), filled[1])
        assertEquals(listOf("SB 1.2.3", "SB 1.2.4", "SB 1.2.5"),
            ContentTitles.interpolateBlank(listOf(node(1, "SB 1.2.3"), node(2, ""), node(3, "SB 1.2.5"))).map { it.title })
        for (siblings in listOf(
            listOf(node(1, "Bg 1.6"), node(2, ""), node(3, "Bg 1.9")),
            listOf(node(1, "Bg 1.6"), node(2, ""), node(3, "Bg 2.8")),
            listOf(node(1, "Bg 1.6"), node(2, ""), node(3, "SB 1.8")),
            listOf(node(1, "Bg 1.6-7"), node(2, ""), node(3, "Bg 1.9")),
            listOf(node(1, ""), node(2, "Bg 1.7")),
            listOf(node(1, "Bg 1.6"), node(2, "")),
            listOf(node(1, "Verse 6"), node(2, ""), node(3, "Verse 8")),
        )) assertEquals(siblings, ContentTitles.interpolateBlank(siblings))
    }

    @Test
    fun collectionNamesDropTheAudioNoteAndSanskritAlias() {
        assertEquals("Bhagavad-g\u012bt\u0101 As It Is", ContentTitles.display(" 02. Bhagavad-g\u012bt\u0101 As It Is, with Audio"))
        assertEquals("\u015ar\u012bmad-Bh\u0101gavatam", ContentTitles.display(" 03. \u015ar\u012bmad-Bh\u0101gavatam (Bh\u0101gavata Pur\u0101\u1e47a) "))
        assertEquals("\u015ar\u012b Caitanya-carit\u0101m\u1e5bta", ContentTitles.display(" 04. \u015ar\u012b Caitanya-carit\u0101m\u1e5bta"))
        assertEquals("Audio Transcripts, 1966\u20141977, In Chronological Order",
            ContentTitles.display("06. Audio Transcripts, 1966\u20141977, In Chronological Order"))
        unchanged("With Audio Ministries", "Bhaktivedanta Audio Ministries (CD, MP3)  Note")
    }

    private fun unchanged(vararg titles: String) {
        for (title in titles) assertEquals(title, title, ContentTitles.display(title))
    }
}