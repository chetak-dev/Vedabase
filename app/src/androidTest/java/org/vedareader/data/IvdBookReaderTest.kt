package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** All databases and preferences are UUID-owned synthetic fixtures; never open a user's library. */
@RunWith(AndroidJUnit4::class)
class IvdBookReaderTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var preferences: SharedPreferences
    private lateinit var expectedPreferences: Map<String, *>
    private lateinit var store: LibraryStore
    private lateinit var repository: IvdRepository
    private lateinit var library: LibraryInfo
    private val sources = mutableListOf<SourceSnapshot>()

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
        preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        check(preferences.edit().putString("fixture-owner", root.name).commit())
        store = LibraryStore(context)
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)
        library = registerFixture()
        repository = IvdRepository(context, store)
    }

    @After
    fun tearDown() {
        try {
            if (::repository.isInitialized) repository.close()
            assertSourcesUnchanged()
            if (::expectedPreferences.isInitialized) assertEquals(expectedPreferences, preferences.all)
        } finally {
            try {
                if (::context.isInitialized) context.deleteOwnedPreferences()
            } finally {
                if (::root.isInitialized) {
                    check(UUID.fromString(root.name).toString() == root.name)
                    check(root.deleteRecursively())
                }
            }
        }
    }

    @Test
    fun outlinePartitionsEveryInclusiveSourceRecordExactlyOnceIncludingFrontMatterGapsAndTails() = runBlocking {
        val first = read(work.record)
        val outline = first.position.outline
        assertEquals(work, outline.book)
        assertEquals(expectedRanges, outline.entries.map { it.node.record..it.node.end })
        assertEquals((1..149).toList(), outline.entries.flatMap { (it.node.record..it.node.end).toList() })
        val paragraphs = outline.entries.flatMap { read(it.node.record).section.paragraphs }
        assertEquals(standardRows.filter { it.record <= 149 }.map { it.paragraph() }, paragraphs)
        assertEquals(paragraphs.size, paragraphs.map { it.record }.toSet().size)
        assertTrue(paragraphs.none { it.record >= otherWork.record })
    }

    @Test
    fun everyArbitraryRecordAndNestedSubsectionResolvesToItsContainingEntryNotThePreviousLeaf() = runBlocking {
        val outline = read(38).position.outline
        for (record in 1..149) {
            val result = read(record)
            val index = outline.entries.indexOfFirst { record in it.node.record..it.node.end }
            assertSame(outline, result.position.outline)
            assertEquals(index, result.position.index)
            assertEquals(outline.entries[index].node, result.section.node)
        }
        for (record in listOf(31, 35, 37, 38, 40, 45)) assertEquals(verse, read(record).section.node)
        assertEquals(chapter.copy(record = 46, end = 49), read(48).section.node)
        assertEquals(work.copy(record = 90, end = 94), read(91).section.node)
    }

    @Test
    fun nextAndPreviousWalkTheSameSnapshotThroughIntroductionChapterVerseAndAcrossChapters() = runBlocking {
        val outline = read(1).position.outline
        var current = read(1)
        assertNull(current.section.previous)
        for (index in outline.entries.indices) {
            assertSame(outline, current.position.outline)
            assertEquals(index, current.position.index)
            assertEquals(outline.entries.getOrNull(index - 1)?.node, current.section.previous)
            assertEquals(outline.entries.getOrNull(index + 1)?.node, current.section.next)
            current.section.next?.let { current = read(it.record) }
        }
        assertNull(current.section.next)
        for (index in outline.entries.indices.reversed()) {
            assertEquals(index, current.position.index)
            assertSame(outline, current.position.outline)
            current.section.previous?.let { current = read(it.record) }
        }
        assertEquals(0, current.position.index)
        assertEquals(listOf(ReadingUnitKind.SECTION, ReadingUnitKind.CHAPTER, ReadingUnitKind.SECTION,
            ReadingUnitKind.CHAPTER, ReadingUnitKind.VERSE), outline.entries.take(5).map { it.kind })
        assertEquals(nextChapter.copy(end = 99), read(94).section.next)
        assertEquals(nextVerse, read(95).section.next)
        assertEquals(nextChapter.copy(end = 99), read(100).section.previous)
    }

    @Test
    fun preamblesAndTailsKeepActualSourceHeadingsWhileNormalVersesMatchLegacyExactly() = runBlocking {
        for (record in listOf(1, 22, 25, 46, 58, 80, 90, 95, 118, 130, 144)) {
            val result = read(record)
            val entry = result.position.outline.entries[result.position.index]
            assertEquals(repository.node(library.id, entry.source.record), entry.source)
            assertTrue(entry.node.record >= entry.source.record && entry.node.end <= entry.source.end)
        }
        assertEquals(chapter, read(48).position.let { it.outline.entries[it.index].source })
        assertEquals(work.displayTitle, read(25).section.parentTitle)
        assertEquals(chapter.displayTitle, read(30).section.parentTitle)
        for (node in listOf(verse, combinedVerse, nextVerse)) {
            val book = read(node.record).section
            val legacy = repository.readingSection(library.id, node.record)
            assertEquals(legacy.node, book.node)
            assertEquals(legacy.paragraphs, book.paragraphs)
            assertEquals(legacy.kind, book.kind)
            assertEquals(legacy.parentTitle, book.parentTitle)
            assertSame(node.let { read(it.record).position.outline }, read(node.end).position.outline)
        }
        assertEquals("Verses 2–3", read(60).section.node.title)
    }

    @Test
    fun semanticCollectionStandaloneCantoAndBookRootsIgnoreTitlesAndNumericLevels() = runBlocking {
        for (level in listOf("LE_Collection", "LE_Canto", "LE_Book")) {
            val fixture = registerFixture {
                insertStandardFixture()
                execSQL("UPDATE texts SET levelname=? WHERE recid=1", arrayOf(" \t$level\r\n"))
                execSQL("UPDATE contents SET title='Not a book',level=999 WHERE record=1")
            }
            assertEquals(1, read(38, fixture).position.outline.book.record)
            assertEquals(149, read(38, fixture).position.outline.book.end)
            assertEquals(ReadingUnitKind.VERSE, read(38, fixture).section.kind)
        }
        val noWork = registerFixture {
            insertStandardFixture()
            execSQL("UPDATE texts SET levelname='LE_Writings' WHERE recid=1")
            execSQL("UPDATE contents SET title='Book Collection Canto' WHERE record=1")
        }
        expectFailure(ReadingFailure.CHOOSE_SECTION) { read(30, noWork) }
    }

    @Test
    fun nestedIndependentWorksStayIsolatedAndAnthologyCannotEscapeThroughACanto() = runBlocking {
        val anthology = work.copy(children = 2)
        val book = ContentNode(10, 1, "Chapter-looking title", 59, 1, 42)
        val innerVerse = ContentNode(20, 10, "", 39, 0, 2)
        val canto = ContentNode(70, 1, "Other title", 119, 1, 7)
        val cantoVerse = ContentNode(80, 70, "", 99, 0, 90)
        for (independentLevel in listOf("LE_Book", "LE_Collection")) {
            val fixture = registerFixture {
                populate(listOf(anthology to "LE_Collection", book to independentLevel, innerVerse to "LE_Verse_Number",
                    canto to "LE_Canto", cantoVerse to "LE_Verse_Number", otherWork to "LE_Book"), (1..179).toList())
            }
            // A canto is not an independent work: the anthology must still reject, not skip the book.
            for (record in listOf(1, 5, 60, 70, 87, 119, 130, 149)) {
                expectFailure(ReadingFailure.CHOOSE_SECTION) { read(record, fixture) }
            }
            val outline = read(27, fixture).position.outline
            assertEquals(book, outline.book)
            val records = outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } }
            assertEquals((book.record..book.end).toList(), records)
            assertNull(read(outline.entries.first().node.record, fixture).section.previous)
            assertNull(read(outline.entries.last().node.record, fixture).section.next)
            assertSame(outline, read(book.record, fixture).position.outline)
        }
    }

    @Test
    fun chapterCollectionKeepsFrontMatterPostscriptAndAppendixCantosInOneCompleteBook() = runBlocking {
        val postscript = ContentNode(130, 1, "Not inferred", 134, 0, 99)
        val appendix = ending.copy(title = "Not a canto", children = 1)
        val appendixSection = ContentNode(136, 135, "", 140, 1, 1)
        val nestedCanto = ContentNode(137, 136, "", 139, 1, 80)
        val appendixVerse = ContentNode(138, 137, "", 139, 0, 3)
        val secondAppendix = ContentNode(144, 1, "Chapter-looking", 149, 1, 2)
        val appendixProse = ContentNode(146, 144, "", 148, 0, 1)
        val fixture = registerFixture {
            populate(standardHeadings.filter { it.first != ending } + listOf(postscript to "LE_Chapter",
                appendix to "LE_Canto", appendixSection to "LE_Section", nestedCanto to "LE_Canto",
                appendixVerse to "LE_Verse_Number", secondAppendix to "LE_Canto", appendixProse to "LE_Section"),
                (1..179).toList())
        }
        // Start cold in an appendix (including a nested canto), not just via a cached verse outline.
        for (selected in listOf(138, 147, 30)) {
            repository.close()
            repository = IvdRepository(context, store) // close is terminal; a new owner opens cold.
            val outline = read(selected, fixture).position.outline
            assertEquals(work, outline.book)
            assertEquals((1..149).toList(), outline.entries.flatMap { (it.node.record..it.node.end).toList() })
            val paragraphs = outline.entries.flatMap {
                val loaded = read(it.node.record, fixture)
                assertSame(outline, loaded.position.outline)
                loaded.section.paragraphs.map { row -> row.record }
            }
            assertEquals((1..149).toList(), paragraphs)
            assertEquals(listOf(verse, combinedVerse, nextVerse, appendixVerse),
                outline.entries.filter { it.kind == ReadingUnitKind.VERSE }.map { it.node })
            for (record in listOf(1, 5, 22, 25, 38, 95, 100, 130, 135, 137, 138, 143, 144, 149)) {
                assertSame(outline, read(record, fixture).position.outline)
            }
            assertNull(read(1, fixture).section.previous)
            assertNull(read(149, fixture).section.next)
        }
    }

    @Test
    fun cantoOnlyCollectionUsesTheDirectSelectedCantoForAllDescendantsWithoutCrossingNextCanto() = runBlocking {
        val firstCanto = ContentNode(10, 1, "Chapter-looking", 69, 0, 999)
        val firstChapter = ContentNode(20, 10, "", 49, 1, 2)
        val firstVerse = ContentNode(25, 20, "", 39, 0, 2)
        val nestedCanto = ContentNode(50, 10, "", 59, 1, 2)
        val nestedVerse = ContentNode(52, 50, "", 57, 0, 2)
        val secondCanto = ContentNode(80, 1, "Not a canto", 139, 0, 1)
        val secondChapter = ContentNode(90, 80, "", 129, 1, 2)
        val secondVerse = ContentNode(95, 90, "", 117, 0, 2)
        val fixture = registerFixture {
            populate(listOf(work to "LE_Collection", intro.copy(end = 9) to "LE_Section",
                firstCanto to " \tLE_Canto\r\n", firstChapter to "LE_Chapter", firstVerse to "LE_Verse_Number",
                nestedCanto to "LE_Canto", nestedVerse to "LE_Verse_Number", secondCanto to "LE_Canto",
                secondChapter to "LE_Chapter", secondVerse to "LE_Verse_Number", otherWork to "LE_Book"),
                (1..179).toList())
        }
        // Cold selection from the nested canto must still choose the outer, direct canto.
        for ((rootNode, selected) in listOf(firstCanto to 53, secondCanto to 100)) {
            val outline = read(selected, fixture).position.outline
            assertEquals(rootNode, outline.book)
            assertEquals((rootNode.record..rootNode.end).toList(),
                outline.entries.flatMap { (it.node.record..it.node.end).toList() })
            assertEquals((rootNode.record..rootNode.end).toList(),
                outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } })
            for (record in rootNode.record..rootNode.end) assertSame(outline, read(record, fixture).position.outline)
            assertNull(read(rootNode.record, fixture).section.previous)
            assertNull(read(rootNode.end, fixture).section.next)
        }
        for (record in listOf(1, 5, 9, 70, 79, 140, 149)) {
            expectFailure(ReadingFailure.CHOOSE_SECTION) { read(record, fixture) }
        }
        assertEquals(otherWork, read(160, fixture).position.outline.book)
    }

    @Test
    fun directChapterOrVerseKeepsCollectionScopeEvenWhenCantosHaveChaptersToo() = runBlocking {
        val direct = ContentNode(10, 1, "", 29, 0, 999)
        val canto = ContentNode(40, 1, "", 139, 1, 1)
        val cantoChapter = ContentNode(50, 40, "", 129, 1, 1)
        val cantoVerse = ContentNode(60, 50, "", 79, 0, 1)
        for (directLevel in listOf("LE_Chapter", "LE_Verse_Number")) {
            val fixture = registerFixture {
                populate(listOf(work to "LE_Collection", direct to " \t$directLevel\r\n", canto to "LE_Canto",
                    cantoChapter to "LE_Chapter", cantoVerse to "LE_Verse_Number"), (1..149).toList())
            }
            val outline = read(65, fixture).position.outline
            assertEquals(work, outline.book)
            assertSame(outline, read(15, fixture).position.outline)
            assertSame(outline, read(145, fixture).position.outline)
        }
    }

    @Test
    fun bookAndStandaloneCantoRetainNestedCantosRatherThanSplittingIntoIndependentWorks() = runBlocking {
        val outer = work.copy(children = 1)
        val canto = ContentNode(10, 1, "", 139, 1, 2)
        val cantoVerse = ContentNode(20, 10, "", 39, 0, 3)
        for (outerLevel in listOf("LE_Book", "LE_Canto")) {
            val fixture = registerFixture {
                populate(listOf(outer to outerLevel, canto to "LE_Canto", cantoVerse to "LE_Verse_Number"),
                    (1..149).toList())
            }
            val outline = read(25, fixture).position.outline
            assertEquals(outer, outline.book)
            assertSame(outline, read(1, fixture).position.outline)
            assertEquals((1..149).toList(),
                outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } })
        }
    }

    @Test
    fun scopedCantoCannotTraverseOrSkipNestedIndependentBooksOrCollections() = runBlocking {
        val canto = ContentNode(10, 1, "", 139, 1, 2)
        val independent = ContentNode(20, 10, "", 59, 1, 3)
        val innerVerse = ContentNode(30, 20, "", 49, 0, 4)
        for (level in listOf("LE_Book", "LE_Collection")) {
            val fixture = registerFixture {
                populate(listOf(work to "LE_Collection", canto to "LE_Canto", independent to level,
                    innerVerse to "LE_Verse_Number"), (1..149).toList())
            }
            for (record in listOf(10, 15, 60, 139)) {
                expectFailure(ReadingFailure.CHOOSE_SECTION) { read(record, fixture) }
            }
            val outline = read(35, fixture).position.outline
            assertEquals(independent, outline.book)
            assertEquals((20..59).toList(),
                outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } })
            assertNull(read(20, fixture).section.previous)
            assertNull(read(59, fixture).section.next)
        }
    }

    @Test
    fun malformedRangesOverlapsParentCyclesMissingParentsAndBypassedParentsAreRejected() = runBlocking {
        val mutations = listOf(
            "UPDATE contents SET next_sibling=29 WHERE record=30",
            "UPDATE contents SET next_sibling=60 WHERE record=30",
            "UPDATE contents SET next_sibling=90 WHERE record=60",
            "UPDATE contents SET parent=9999 WHERE record=30",
            "UPDATE contents SET parent=30 WHERE record=30",
            "UPDATE contents SET parent=30 WHERE record=1",
            "UPDATE contents SET parent=1 WHERE record=30",
            "UPDATE contents SET parent=60 WHERE record=30",
            "UPDATE contents SET next_sibling=NULL WHERE record=25",
            "UPDATE contents SET next_sibling=170 WHERE record=1",
            "UPDATE contents SET parent=NULL WHERE record=25",
            "UPDATE contents SET parent='not a parent' WHERE record=1",
            "UPDATE contents SET next_sibling=2147483648 WHERE record=25",
        )
        for (mutation in mutations) {
            val fixture = registerFixture { insertStandardFixture(); execSQL(mutation) }
            expectFailure(ReadingFailure.LOAD) { read(38, fixture) }
        }
    }

    @Test
    fun precedingWorkOverlapAndChildrenLyingOutsideTheirDeclaredWorkAreRejected() = runBlocking {
        val overlap = registerFixture {
            populate(listOf(ContentNode(1, 0, "Earlier overlap", 50, 0, 3) to "LE_Book",
                ContentNode(10, 0, "Selected", 99, 1, 3) to "LE_Book",
                ContentNode(20, 10, "Verse", 29, 0, 5) to "LE_Verse_Number"), (1..99).toList())
        }
        expectFailure(ReadingFailure.LOAD) { read(20, overlap) }
        val outside = registerFixture {
            insertStandardFixture()
            insertNode(ContentNode(200, 25, "Escaped child", 209, 0, 3))
            insertRow(Row(200, "Escaped heading", level = "LE_Section"))
        }
        expectFailure(ReadingFailure.LOAD) { read(30, outside) }
    }

    @Test
    fun nestedVerseRangesAndUnindexedSemanticWorkMarkersFailClosed() = runBlocking {
        val nestedVerse = registerFixture {
            insertStandardFixture()
            execSQL("UPDATE texts SET levelname='LE_Verse_Number' WHERE recid=35")
        }
        expectFailure(ReadingFailure.LOAD) { read(30, nestedVerse) }
        for (level in listOf("LE_Book", "LE_Collection")) {
            val missingHeading = registerFixture {
                insertStandardFixture()
                execSQL("UPDATE texts SET levelname=? WHERE recid=38", arrayOf(level))
            }
            expectFailure(ReadingFailure.LOAD) { read(30, missingHeading) }
        }
    }

    @Test
    fun unindexedCantoMarkersAreSubdivisionsAndDoNotCreateHardBookBoundaries() = runBlocking {
        val fixture = registerFixture {
            insertStandardFixture()
            execSQL("UPDATE texts SET levelname='LE_Canto' WHERE recid IN (23,38,92)")
        }
        val outline = read(38, fixture).position.outline
        assertEquals(work, outline.book)
        assertEquals(expectedRanges, outline.entries.map { it.node.record..it.node.end })
        assertEquals(verse, read(38, fixture).section.node)
        assertEquals((1..149).toList(),
            outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } })
    }

    @Test
    fun duplicateTextIdsIncludingAPagingBoundaryAreRejectedRatherThanSkipped() = runBlocking {
        for (duplicate in listOf(1, 300, 512, 513)) {
            val fixture = registerFixture {
                populate(listOf(ContentNode(1, 0, "Prose", 800, 0, 1) to "LE_Book"), (1..800).toList())
                insertRow(Row(duplicate, "Duplicate"))
            }
            expectFailure(ReadingFailure.LOAD) { read(1, fixture) }
        }
    }

    @Test
    fun missingAndInvalidSelectionsDoNotFallThroughToTheNextWork() = runBlocking {
        for (record in listOf(-1, 0, 180, Int.MAX_VALUE)) expectFailure(ReadingFailure.LOAD) { read(record) }
        assertEquals(otherWork, read(160).position.outline.book)
        assertEquals(otherVerse, read(160).section.node)
        assertNull(read(179).section.next)
    }

    @Test
    fun sparseRecordsEmptySourceHeadingsAndEmptyTailsStillHaveAnExactContiguousPartition() = runBlocking {
        val empty = ContentNode(135, 1, "Empty", 143, 0, 7)
        val sparseIds = listOf(1, 5, 25, 30, 31, 35, 37, 38, 45, 50, 60, 79, 95, 100, 117)
        val fixture = registerFixture { populate(standardHeadings.filter { it.first.record < 150 }, sparseIds) }
        val outline = read(1, fixture).position.outline
        assertEquals(expectedRanges, outline.entries.map { it.node.record..it.node.end })
        val all = outline.entries.flatMap { read(it.node.record, fixture).section.paragraphs.map { row -> row.record } }
        assertEquals(sparseIds, all)
        assertTrue(read(empty.record, fixture).section.paragraphs.isEmpty())
        assertTrue(read(149, fixture).section.paragraphs.isEmpty())
        assertEquals(verse, read(44, fixture).section.node)
    }

    @Test
    fun hugeProseUsesAtMost256RowsPerEntryRatherThanTheLegacyWholeUnitRowLimit() = runBlocking {
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Long prose", 12_500, 0, 1) to "LE_Book"), (1..12_500).toList())
        }
        val outline = read(11_500, fixture).position.outline
        assertEquals(49, outline.entries.size)
        val rows = outline.entries.flatMap {
            val loaded = read(it.node.record, fixture).section.paragraphs
            assertTrue(loaded.size <= 256)
            loaded.map { row -> row.record }
        }
        assertEquals((1..12_500).toList(), rows)
        assertEquals(11_500, read(11_500, fixture).section.paragraphs.single { it.record == 11_500 }.record)
    }

    @Test
    fun characterBudgetChunksAtRecordBoundariesAndNeverTruncatesMultilingualProse() = runBlocking {
        val body = "नमूना ā a\u0304 🕉 ".repeat(10_000)
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 13, 0, 1) to "LE_Book"), listOf(1))
            for (record in 2..13) insertRow(Row(record, body))
        }
        val outline = read(2, fixture).position.outline
        assertTrue(outline.entries.size > 1)
        val records = mutableListOf<Int>()
        for (entry in outline.entries) {
            val section = read(entry.node.record, fixture).section
            assertTrue(section.paragraphs.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= 512_000)
            for (paragraph in section.paragraphs) {
                records += paragraph.record
                if (paragraph.record > 1) assertEquals(body, paragraph.text)
            }
        }
        assertEquals((1..13).toList(), records)
    }

    @Test
    fun aSingleLargeParagraphIsOneCompleteEntryEvenBeyondCursorWindowAndContainsEmbeddedNul() = runBlocking {
        val body = "ā".repeat(1_100_000) + "\u0000नमूना🕉" + "x".repeat(100_000)
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 3, 0, 1) to "LE_Book"), listOf(1, 3))
            insertRow(Row(2, body))
        }
        val result = read(2, fixture)
        assertEquals(listOf(1..1, 2..2, 3..3), result.position.outline.entries.map { it.node.record..it.node.end })
        assertEquals(listOf(2), result.section.paragraphs.map { it.record })
        assertEquals(body, result.section.paragraphs.single().text)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), result.section.paragraphs.single().text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun largeParagraphByteAssemblyHonoursUtf16DatabaseEncodingAsWellAsUtf8() = runBlocking {
        val body = "नमूना ā\u0000🕉".repeat(30_000)
        for (encoding in listOf("UTF-16le", "UTF-16be")) {
            val fixture = registerFixture(encoding) {
                populate(listOf(ContentNode(1, 0, "Prose", 2, 0, 1) to "LE_Book"), listOf(1))
                insertRow(Row(2, body))
            }
            assertEquals(body, read(2, fixture).section.paragraphs.single().text)
        }
    }

    @Test
    fun exactlyFourMillionCharactersAreAcceptedWithoutDroppingTextFollowingEmbeddedNul() = runBlocking {
        val body = "x".repeat(100) + "\u0000" + "y".repeat(3_999_899)
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 2, 0, 1) to "LE_Book"), listOf(1))
            insertRow(Row(2, body))
        }
        assertEquals(4_000_000, body.length)
        assertEquals(body, read(2, fixture).section.paragraphs.single().text)
    }

    @Test
    fun inclusiveRangesAtTheLargestRecordIdDoNotOverflowOrCreatePhantomTailEntries() = runBlocking {
        val highWork = ContentNode(Int.MAX_VALUE - 6, 0, "High records", Int.MAX_VALUE, 1, 1)
        val highVerse = ContentNode(Int.MAX_VALUE - 3, highWork.record, "High verse", Int.MAX_VALUE, 0, 2)
        val fixture = registerFixture {
            populate(listOf(highWork to "LE_Book", highVerse to "LE_Verse_Number"),
                listOf(highWork.record, highVerse.record, Int.MAX_VALUE))
        }
        val result = read(Int.MAX_VALUE, fixture)
        assertEquals(highVerse, result.section.node)
        assertEquals(listOf(highWork.record..highVerse.record - 1, highVerse.record..Int.MAX_VALUE),
            result.position.outline.entries.map { it.node.record..it.node.end })
        assertEquals(listOf(highVerse.record, Int.MAX_VALUE), result.section.paragraphs.map { it.record })
        assertNull(result.section.next)
    }

    @Test
    fun exactlyTwelveThousandVerseRecordsKeepTheOriginalNodeAndEntireLegacyParagraphList() = runBlocking {
        val fixture = largeVerseFixture(11_999, "x")
        val result = read(11_500, fixture)
        val legacy = repository.readingSection(fixture.id, 10)
        assertEquals(12_000, result.section.paragraphs.size)
        assertEquals(legacy.node, result.section.node)
        assertEquals(legacy.paragraphs, result.section.paragraphs)
        assertEquals(1, result.position.outline.entries.count { it.kind == ReadingUnitKind.VERSE })
    }

    @Test
    fun oversizedVerseRecordAndCharacterLimitsRejectWithoutTruncatingOrSplittingTheVerse() = runBlocking {
        val fixtures = listOf(largeVerseFixture(12_000, "x"), largeVerseFixture(80, "x".repeat(50_001)))
        for (fixture in fixtures) {
            // The outline can be used even if a later indivisible verse is too big to load.
            val outline = read(1, fixture).position.outline
            assertEquals(1, outline.entries.count { it.kind == ReadingUnitKind.VERSE })
            expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(10, fixture) }
            assertSame(outline, read(1, fixture).position.outline)
        }
        assertEquals(verse, read(30).section.node)
    }

    @Test
    fun proseParagraphBeyondFourMillionCharactersFailsInsteadOfBeingSplit() = runBlocking {
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 2, 0, 1) to "LE_Book"), listOf(1))
            insertRow(Row(2, "x".repeat(4_000_001)))
        }
        assertEquals(2, read(1, fixture).position.outline.entries.size)
        expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(2, fixture) }
    }

    @Test
    fun nullWhitespaceUnicodeAndOriginalSourceMetadataArePreserved() = runBlocking {
        val rows = read(38).section.paragraphs.associateBy { it.record }
        assertEquals(Paragraph(38, UNICODE, "PA_Fixture ā", " \tText नमूना\r\n"), rows.getValue(38))
        assertEquals(Paragraph(39, "", "", ""), rows.getValue(39))
        assertEquals(Paragraph(40, " \t ", "", " \t "), rows.getValue(40))
        assertEquals("", read(100).section.node.title)
    }

    @Test
    fun outlineIsDefensivelyCopiedAndCannotBeMutatedByACaller() = runBlocking {
        val original = read(1).position.outline
        val copySource = original.entries.toMutableList()
        val snapshot = BookOutline(original.book, copySource)
        copySource.clear()
        assertEquals(original.entries, snapshot.entries)
        var rejected = false
        try {
            (snapshot.entries as MutableList<BookEntry>).clear()
        } catch (_: UnsupportedOperationException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original.entries, snapshot.entries)
    }

    @Test
    fun sameRecordIdsInDifferentLibrariesNeverShareOutlinesOrLoadedText() = runBlocking {
        val first = read(38)
        val secondLibrary = registerFixture {
            insertStandardFixture()
            execSQL("UPDATE contents SET title='Second library' WHERE record=1")
            execSQL("UPDATE texts SET plain='Second library only' WHERE recid=38")
        }
        val second = read(38, secondLibrary)
        assertNotSame(first.position.outline, second.position.outline)
        assertEquals("Second library", second.position.outline.book.title)
        assertEquals("Second library only", second.section.paragraphs.single { it.record == 38 }.text)
        assertEquals(UNICODE, read(38).section.paragraphs.single { it.record == 38 }.text)
        assertSame(first.position.outline, read(38).position.outline)
    }

    @Test
    fun concurrentRepositoryReadsConvergeOnTheSameImmutableOutline() = runBlocking {
        val results = (1..8).map { async { read(30 + it) } }.awaitAll()
        for (result in results) {
            assertSame(results.first().position.outline, result.position.outline)
            assertEquals(verse, result.section.node)
        }
    }

    @Test
    fun cacheIsBoundedToFourWorksAndEvictsTheLeastRecentlyUsedOutline() = runBlocking {
        val roots = (0..4).map { ContentNode(1000 + 100 * it, 0, "Work $it", 1009 + 100 * it, 0, 1) }
        val fixture = registerFixture {
            populate(roots.map { it to "LE_Book" }, roots.flatMap { (it.record..it.end).toList() })
        }
        val first = read(roots[0].record, fixture).position.outline
        val second = read(roots[1].record, fixture).position.outline
        read(roots[2].record, fixture)
        read(roots[3].record, fixture)
        assertSame(first, read(roots[0].record, fixture).position.outline) // Touch makes root 1 the LRU.
        read(roots[4].record, fixture)
        assertSame(first, read(roots[0].record, fixture).position.outline)
        assertNotSame(second, read(roots[1].record, fixture).position.outline)
    }

    @Test
    fun closeInvalidatesCachedOutlinesWhileRetainedSnapshotsAndSourceFilesStayUnchanged() = runBlocking {
        val first = read(38)
        val entries = first.position.outline.entries.toList()
        repeat(2) {
            repository.close()
            assertSourcesUnchanged()
            repository = IvdRepository(context, store)
            val reopened = read(38)
            assertNotSame(first.position.outline, reopened.position.outline)
            assertEquals(entries, reopened.position.outline.entries)
            assertEquals(first.section, reopened.section)
            assertEquals(expectedPreferences, preferences.all)
            assertEquals(setOf(library.fileName), requireNotNull(File(context.noBackupFilesDir, "libraries").list()).toSet())
        }
        assertEquals(entries, first.position.outline.entries)
    }

    @Test
    fun preCancelledRequestPropagatesCancellationWithoutPublishingAPartialSection() = runBlocking {
        val cancelled = Job().also { it.cancel() }
        var returned = false
        var cancellation = false
        try {
            withContext(cancelled) { read(38); returned = true }
        } catch (_: CancellationException) { cancellation = true }
        assertTrue(cancellation)
        assertFalse(returned)
        assertEquals(verse, read(38).section.node)
    }

    @Test
    fun cancellationDuringMetadataScanLeavesNoPartialOutlineAndTheDirectReaderCanRetry() = runBlocking {
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 2000, 0, 1) to "LE_Book"), (1..2000).toList())
        }
        openReadOnly(fixture).use { database ->
            val reader = IvdBookReader(database)
            var returned = false
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { reader.read(1); returned = true }
            assertTrue(pending.isActive) // First metadata page yielded before completion.
            pending.cancelAndJoin()
            assertFalse(returned)
            val result = reader.read(1000)
            assertEquals(8, result.position.outline.entries.size)
            assertEquals(256, result.section.paragraphs.size)
            assertTrue(database.isReadOnly)
        }
    }

    @Test
    fun cancellationDuringLargeParagraphAssemblyDoesNotReturnATruncatedParagraph() = runBlocking {
        val body = "x".repeat(800_000)
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 2, 0, 1) to "LE_Book"), listOf(1))
            insertRow(Row(2, body))
        }
        openReadOnly(fixture).use { database ->
            val reader = IvdBookReader(database)
            val outline = reader.read(1).position.outline
            var returned = false
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { reader.read(2); returned = true }
            assertTrue(pending.isActive)
            pending.cancelAndJoin()
            assertFalse(returned)
            val result = reader.read(2)
            assertSame(outline, result.position.outline)
            assertEquals(body, result.section.paragraphs.single().text)
        }
    }

    @Test
    fun hierarchyDepthAndOutlineMetadataLimitsFailExplicitly() = runBlocking {
        val tooDeep = registerFixture {
            val nodes = (1..66).map { ContentNode(it, if (it == 1) 0 else it - 1, "Level", 100, 1, it) }
            populate(nodes.map { it to if (it.record == 1) "LE_Book" else "LE_Section" }, (1..100).toList())
        }
        expectFailure(ReadingFailure.LOAD) { read(66, tooDeep) }
        val tooMany = registerFixture {
            // Total descendants have a separate budget; this fixture exceeds DIRECT siblings.
            val rootNode = ContentNode(1, 0, "Many siblings", 20_002, 20_001, 1)
            val nodes = (2..20_002).map { ContentNode(it, 1, "", it, 0, 2) }
            populate(listOf(rootNode to "LE_Book") + nodes.map { it to "LE_Section" }, (1..20_002).toList())
        }
        expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(1, tooMany) }
    }

    private suspend fun read(record: Int, fixture: LibraryInfo = library) = repository.bookSection(fixture.id, record)

    private suspend fun expectFailure(reason: ReadingFailure, action: suspend () -> BookReadingSection) {
        var result: BookReadingSection? = null
        val failure = try { result = action(); null } catch (error: ReadingException) { error }
        assertNull("No partial section on $reason", result)
        assertEquals(reason, failure?.reason)
    }

    private fun largeVerseFixture(bodyRows: Int, body: String): LibraryInfo = registerFixture {
        val rootNode = ContentNode(1, 0, "Large work", 20_050, 1, 1)
        val verseNode = ContentNode(10, 1, "Unsplit verse", 20_049, 0, 7)
        populate(listOf(rootNode to "LE_Book", verseNode to "LE_Verse_Number"), listOf(1, 10))
        repeat(bodyRows) { insertRow(Row(11 + it, body)) }
    }

    private fun registerFixture(
        encoding: String = "UTF-8",
        populate: SQLiteDatabase.() -> Unit = { insertStandardFixture() },
    ): LibraryInfo {
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "libraries").also { check(it.isDirectory || it.mkdirs()) }
        val file = File(directory, "$id.ivd")
        var count = 0L
        check(encoding in setOf("UTF-8", "UTF-16le", "UTF-16be"))
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            database.execSQL("PRAGMA encoding='$encoding'")
            database.rawQuery("PRAGMA encoding", null).use { check(it.moveToFirst()); assertEquals(encoding, it.getString(0)) }
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            database.beginTransaction()
            try {
                database.execSQL("CREATE TABLE contents (record INTEGER PRIMARY KEY,parent INTEGER,title TEXT," +
                    "next_sibling INTEGER,node_children INTEGER,level INTEGER,simpletitle TEXT,node_code TEXT)")
                // Indexed but not unique: malformed duplicate-record fixtures must exercise the reader.
                database.execSQL("CREATE TABLE texts (recid INTEGER,plain TEXT,stylename TEXT,levelname TEXT)")
                database.execSQL("CREATE INDEX contents_parent_record ON contents(parent,record)")
                database.execSQL("CREATE INDEX texts_record ON texts(recid)")
                database.populate()
                database.rawQuery("SELECT count(*) FROM texts", null).use { check(it.moveToFirst()); count = it.getLong(0) }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        check(file.setReadOnly())
        sources += SourceSnapshot(file, file.length(), sha256(file))
        val fixture = LibraryInfo(id, "Generated book fixture", "sa-Latn", file.name, file.length(), count, "Synthetic only")
        store.addLibrary(fixture)
        expectedPreferences = preferences.all.toMap()
        return fixture
    }

    private fun openReadOnly(fixture: LibraryInfo) = SQLiteDatabase.openDatabase(
        File(File(context.noBackupFilesDir, "libraries"), fixture.fileName).absolutePath, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    private fun assertSourcesUnchanged() {
        for ((file, size, hash) in sources) {
            assertEquals(size, file.length())
            assertArrayEquals("Read-only fixture changed", hash, sha256(file))
        }
    }

    private data class SourceSnapshot(val file: File, val size: Long, val hash: ByteArray)
    private data class Row(val record: Int, val text: String?, val style: String? = "Body", val level: String? = "Text") {
        fun paragraph() = Paragraph(record, text.orEmpty(), style.orEmpty().trim(), level.orEmpty())
    }

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "ivd-book-reader-test-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix$name"
            ownedPreferences += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun deleteOwnedPreferences() {
            ownedPreferences.forEach { check(it.startsWith(prefix)); check(baseContext.deleteSharedPreferences(it)) }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    companion object {
        private const val UNICODE = " \tGenerated नमूना বাংলা ā a\u0304 🕉 <PS:\"Fixture\"> & data\r\n"
        private val work = ContentNode(1, 0, "01. Not inferred from title", 149, 4, 99)
        private val intro = ContentNode(5, 1, "Preface", 21, 0, 2)
        private val chapter = ContentNode(25, 1, "Chapter ā", 89, 3, 3)
        private val verse = ContentNode(30, 25, "Z verse", 45, 2, 87)
        private val text = ContentNode(31, 30, "TEXT", 34, 0, 4)
        private val purport = ContentNode(35, 30, "PURPORT", 45, 1, 4)
        private val subsection = ContentNode(37, 35, "Nested", 40, 0, 8)
        private val prose = ContentNode(50, 25, "Verse-looking prose", 57, 0, 87)
        private val combinedVerse = ContentNode(60, 25, "Verses 2–3", 79, 0, 2)
        private val nextChapter = ContentNode(95, 1, "Next chapter", 129, 1, 80)
        private val nextVerse = ContentNode(100, 95, "", 117, 0, 99)
        private val ending = ContentNode(135, 1, "Closing prose", 143, 0, 7)
        private val otherWork = ContentNode(150, 0, "Next unrelated work", 179, 1, 99)
        private val otherVerse = ContentNode(155, 150, "Next work verse", 169, 0, 3)
        private val standardHeadings = listOf(work to "LE_Collection", intro to "LE_Chapter", chapter to "LE_Chapter",
            verse to "LE_Verse_Number", text to "LE_Verse_Section", purport to "LE_Verse_Section",
            subsection to "LE_Verse_Section", prose to "LE_Section", combinedVerse to "LE_Verse_Number",
            nextChapter to "LE_Chapter", nextVerse to "LE_Verse_Number", ending to "LE_Section",
            otherWork to "LE_Book", otherVerse to "LE_Verse_Number")
        private val expectedRanges = listOf(1..4, 5..21, 22..24, 25..29, 30..45, 46..49, 50..57, 58..59,
            60..79, 80..89, 90..94, 95..99, 100..117, 118..129, 130..134, 135..143, 144..149)
        private val standardRows = (1..179).map { record ->
            val heading = standardHeadings.firstOrNull { it.first.record == record }
            when (record) {
                38 -> Row(record, UNICODE, " \tPA_Fixture ā\r\n", " \tText नमूना\r\n")
                39 -> Row(record, null, null, null)
                40 -> Row(record, " \t ", " \t\r\n", " \t ")
                else -> Row(record, "Generated $record", if (heading != null) "PA_Titles" else "Body", heading?.second ?: "Text")
            }
        }

        private fun SQLiteDatabase.insertStandardFixture() {
            standardHeadings.reversed().forEach { insertNode(it.first) }
            standardRows.reversed().forEach { insertRow(it) }
        }

        private fun SQLiteDatabase.populate(headings: List<Pair<ContentNode, String>>, records: List<Int>) {
            val levels = headings.associate { it.first.record to it.second }
            headings.reversed().forEach { insertNode(it.first) }
            records.reversed().forEach { insertRow(Row(it, "Generated $it", level = levels[it] ?: "Text")) }
        }

        private fun SQLiteDatabase.insertNode(node: ContentNode) {
            execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code))
        }

        private fun SQLiteDatabase.insertRow(row: Row) {
            execSQL("INSERT INTO texts VALUES (?,?,?,?)", arrayOf(row.record, row.text, row.style, row.level))
        }

        private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    }
}