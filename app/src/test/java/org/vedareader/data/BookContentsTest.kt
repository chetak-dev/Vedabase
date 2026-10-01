package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata-only fixtures: no Android, repository, source text or database. */
class BookContentsTest {
    @Test
    fun repeatedProseChunksBecomeOneHeadingWithTheFirstValidJumpNotTheSourceId() {
        val book = node(100, 199, "Book")
        val prose = node(110, 199, "Introduction", parent = 100)
        val outline = BookOutline(book, listOf(
            entry(book, 100, 119),
            entry(prose, 120, 139),
            entry(prose, 140, 169),
            entry(prose, 170, 199),
        ))

        val contents = bookContents(outline)

        assertEquals(listOf(100, 110), contents.map { it.source.record })
        val introduction = contents.last()
        assertSame(prose, introduction.source)
        assertEquals(120, introduction.jumpRecord) // 110 is a heading, not an outline-entry start.
        assertTrue(introduction.children.isEmpty())
        assertCompleteMapping(outline, contents)
    }

    @Test
    fun sourceOrderAndDuplicateTitlesArePreservedRatherThanAlphabetizedOrMerged() {
        val book = node(100, 499, "Book")
        // Child counts and numeric levels are not a second source of hierarchy.
        val front = node(110, 149, "Z front matter", 100).copy(children = 9, level = 99)
        val chapter = node(150, 349, "A chapter", 100)
        val first = node(160, 249, "Same title", 150)
        val second = node(250, 349, "Same title", 150)
        val appendix = node(350, 499, "B appendix", 100)
        val outline = BookOutline(book, listOf(
            entry(book, 100, 109), entry(front), entry(chapter, 150, 159, ReadingUnitKind.CHAPTER),
            entry(first, kind = ReadingUnitKind.VERSE), entry(second, kind = ReadingUnitKind.VERSE), entry(appendix),
        ))

        val contents = bookContents(outline)

        assertEquals(listOf(book, front, chapter, appendix), contents.map { it.source })
        assertTrue(contents[1].children.isEmpty()) // Front matter reads directly despite stale metadata.
        assertEquals(listOf(first, second), contents[2].children.map { it.source })
        assertEquals(listOf(160, 250), contents[2].children.map { it.jumpRecord })
        assertCompleteMapping(outline, contents)
    }

    @Test
    fun nestedChaptersUseVerifiedParentsThenTheSmallestContainingSourceRange() {
        val book = node(100, 299, "Book")
        val outer = node(110, 279, "Outer chapter", 100)
        val inner = node(140, 219, "Inner chapter", 110).copy(level = 80)
        val missingParent = node(160, 189, "Nested verse", 999).copy(level = 1)
        val direct = node(190, 219, "Direct child", 140)
        val sibling = node(220, 279, "Outer verse", 110)
        val tail = node(280, 299, "Appendix", 100)
        val outline = BookOutline(book, listOf(
            entry(book, 100, 109), entry(outer, 110, 139, ReadingUnitKind.CHAPTER),
            entry(inner, 140, 159, ReadingUnitKind.CHAPTER),
            entry(missingParent, kind = ReadingUnitKind.VERSE), entry(direct, kind = ReadingUnitKind.VERSE),
            entry(sibling, kind = ReadingUnitKind.VERSE), entry(tail),
        ))

        val contents = bookContents(outline)

        assertEquals(listOf(book, outer, tail), contents.map { it.source })
        val children = contents[1].children
        assertEquals(listOf(inner, sibling), children.map { it.source })
        assertEquals(listOf(missingParent, direct), children.first().children.map { it.source })
        assertEquals(listOf(160, 190), children.first().children.map { it.jumpRecord })
        assertCompleteMapping(outline, contents)
    }

    @Test
    fun combinedAndBlankVerseHeadingsRemainSingleLeavesWithUnmodifiedSourceRanges() {
        val book = node(100, 199, "Book")
        val chapter = node(110, 199, "Chapter", 100)
        val combined = node(120, 169, "Bg 1.16, Bg 1.17, Bg 1.18", 110)
        val blank = node(170, 199, "", 110)
        val outline = BookOutline(book, listOf(
            entry(book, 100, 109), entry(chapter, 110, 119, ReadingUnitKind.CHAPTER),
            entry(combined, kind = ReadingUnitKind.VERSE), entry(blank, kind = ReadingUnitKind.VERSE),
        ))

        val contents = bookContents(outline)
        val leaves = contents[1].children

        assertEquals(listOf(combined, blank), leaves.map { it.source })
        assertSame(combined, leaves[0].source)
        assertEquals("Bg 1.16, Bg 1.17, Bg 1.18", leaves[0].source.title)
        assertEquals("BG 1.16-18", leaves[0].source.displayTitle)
        assertEquals("", leaves[1].source.title)
        assertEquals(listOf(120, 170), leaves.map { it.jumpRecord })
        assertTrue(leaves.all { it.children.isEmpty() })
        assertCompleteMapping(outline, contents)
    }

    @Test
    fun bookOpeningAndDisjointChapterRangesStayRootsEvenWithAnInvalidParentReference() {
        val book = node(100, 399, "Book")
        val first = node(110, 199, "First chapter", 100)
        val firstVerse = node(120, 199, "First verse", 110)
        // It names an existing parent, but its entire range is outside that parent's range.
        val second = node(200, 299, "Second chapter", 110)
        val secondVerse = node(210, 299, "Second verse", 200)
        val appendix = node(300, 399, "Appendix", 100)
        val outline = BookOutline(book, listOf(
            entry(book, 100, 109), entry(first, 110, 119, ReadingUnitKind.CHAPTER),
            entry(firstVerse, kind = ReadingUnitKind.VERSE), entry(second, 200, 209, ReadingUnitKind.CHAPTER),
            entry(secondVerse, kind = ReadingUnitKind.VERSE), entry(appendix),
        ))

        val contents = bookContents(outline)

        assertEquals(listOf(book, first, second, appendix), contents.map { it.source })
        assertTrue("Beginning of book must not wrap/hide all chapters", contents.first().children.isEmpty())
        assertEquals(listOf(firstVerse), contents[1].children.map { it.source })
        assertEquals(listOf(secondVerse), contents[2].children.map { it.source })
        assertTrue(contents.last().children.isEmpty())
        assertCompleteMapping(outline, contents)
    }

    @Test
    fun interleavedOpeningAndTrailingChunksOmitNoSourceAndDoNotDuplicateParents() {
        val book = node(100, 299, "Book")
        val chapter = node(120, 249, "Chapter", 100)
        val verse = node(140, 159, "Verse", 120)
        val prose = node(180, 219, "Untitled-looking prose", 120)
        val entries = listOf(
            entry(book, 100, 119), entry(chapter, 120, 139, ReadingUnitKind.CHAPTER),
            entry(verse, kind = ReadingUnitKind.VERSE), entry(chapter, 160, 179, ReadingUnitKind.CHAPTER),
            entry(prose, 180, 199), entry(prose, 200, 219),
            entry(chapter, 220, 249, ReadingUnitKind.CHAPTER), entry(book, 250, 299),
        )
        val outline = BookOutline(book, entries)
        val before = outline.entries.toList()

        val contents = bookContents(outline)

        assertEquals(listOf(book, chapter), contents.map { it.source })
        assertEquals(listOf(verse, prose), contents.last().children.map { it.source })
        assertEquals(listOf(100, 120, 140, 180), flatten(contents).map { it.jumpRecord })
        assertEquals(before, outline.entries)
        assertEquals(contents, bookContents(outline))
        assertCompleteMapping(outline, contents)
    }

    private fun assertCompleteMapping(outline: BookOutline, contents: List<BookContentsItem>) {
        val items = flatten(contents)
        val sources = outline.entries.distinctBy { it.source.record }
        assertEquals("Every original heading occurs exactly once", sources.size, items.size)
        assertEquals(sources.map { it.source.record }.toSet(), items.map { it.source.record }.toSet())
        assertEquals(items.size, items.map { it.jumpRecord }.toSet().size)
        for (item in items) {
            val first = outline.entries.first { it.source.record == item.source.record }
            assertSame(first.source, item.source)
            assertEquals(first.node.record, item.jumpRecord)
            assertTrue(item.jumpRecord in item.source.record..item.source.end)
        }
    }

    private fun flatten(items: List<BookContentsItem>): List<BookContentsItem> =
        items.flatMap { listOf(it) + flatten(it.children) }

    private fun node(record: Int, end: Int, title: String, parent: Int = 0) =
        ContentNode(record, parent, title, end, 0, 2)

    private fun entry(source: ContentNode, start: Int = source.record, end: Int = source.end,
        kind: ReadingUnitKind = ReadingUnitKind.SECTION) =
        BookEntry(source.copy(record = start, end = end), source, kind, "Synthetic parent")
}