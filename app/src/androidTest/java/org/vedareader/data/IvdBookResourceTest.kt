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

/** UUID-owned synthetic databases/preferences only. No renderer, device navigation or user import. */
@RunWith(AndroidJUnit4::class)
class IvdBookResourceTest {
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
            for ((file, size, hash) in sources) {
                assertEquals(size, file.length())
                assertArrayEquals("Source database changed", hash, sha256(file))
            }
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
    fun adjacentShortVersesHeaderPurportsAndProseShareOneResourceWithoutChangingLegacyEntries() = runBlocking {
        val legacy = repository.bookSection(library.id, middle.record)
        val result = read(middle.record)
        assertSame(legacy.position.outline, result.position.outline)
        assertEquals(chapter, result.section.node)
        assertEquals(ReadingUnitKind.CHAPTER, result.section.kind)
        assertEquals(work.displayTitle, result.section.parentTitle)
        assertTrue(result.firstEntry < result.position.index && result.position.index < result.lastEntry)
        val members = result.position.outline.entries.subList(result.firstEntry, result.lastEntry + 1)
        assertEquals(listOf(first, middle, last), members.filter { it.kind == ReadingUnitKind.VERSE }.map { it.source })
        assertEquals(standardRows.filter { it.record in chapter.record..chapter.end }.map { it.paragraph() }, result.section.paragraphs)
        assertEquals("<PS:\"PA_Titles\">TEXT 1", result.section.paragraphs.single { it.record == 51 }.text)
        assertEquals("<PS:\"PA_Titles\">TEXTS 2–3", result.section.paragraphs.single { it.record == 71 }.text)
        for (node in listOf(first, middle, last)) {
            val resource = read(node.record)
            assertEquals(result.section, resource.section)
            assertContract(resource, node.record)
            val entry = repository.bookSection(library.id, node.record)
            assertEquals(node, entry.section.node)
            assertEquals(repository.readingSection(library.id, node.record).paragraphs, entry.section.paragraphs)
            assertEquals(ReadingUnitKind.VERSE, entry.section.kind)
            assertEquals(entry.position.outline.entries.getOrNull(entry.position.index - 1)?.node, entry.section.previous)
            assertEquals(entry.position.outline.entries.getOrNull(entry.position.index + 1)?.node, entry.section.next)
        }
        assertEquals(legacy, repository.bookSection(library.id, middle.record))
    }

    @Test
    fun coldMiddleVerseOpeningAndNestedBodySelectionsKeepTheirOwnAnchorsAndOriginalSource() = runBlocking {
        val opening = read(middle.record)
        assertEquals(middle.record, opening.anchorRecord)
        assertEquals(middle, opening.source)
        assertEquals(middle, opening.position.outline.entries[opening.position.index].node)
        assertEquals(chapter.record, opening.section.node.record)
        for (record in listOf(71, 80, 85, 88, 99)) {
            val body = read(record)
            assertContract(body, record)
            assertEquals(opening.section, body.section)
            assertEquals(opening.position.index, body.position.index)
            assertEquals(middle, body.source)
        }
        val header = read(chapter.record)
        assertContract(header, chapter.record)
        assertEquals(header.firstEntry, header.position.index)
        assertEquals(chapter, header.source)
        assertEquals(UNICODE, opening.section.paragraphs.single { it.record == 88 }.text)
    }

    @Test
    fun semanticFrontMatterChapterHeadersAndAppendicesHaveSelectionIndependentBoundaries() = runBlocking {
        val resources = walk()
        assertEquals(expectedRanges, resources.map { it.section.node.record..it.section.node.end })
        for (record in work.record..work.end) {
            val result = read(record)
            val expected = resources.single { record in it.section.node.record..it.section.node.end }
            assertEquals(expected.section, result.section)
            assertEquals(expected.firstEntry, result.firstEntry)
            assertEquals(expected.lastEntry, result.lastEntry)
            assertContract(result, record)
        }
        assertEquals(preface, read(preface.record).section.node)
        assertEquals(introduction, read(introduction.record).section.node)
        assertEquals(chapter, read(first.record).section.node)
        assertEquals(nextChapter, read(nextVerse.record).section.node)
        assertEquals(appendix, read(appendixSection.record).section.node)
        assertEquals(work, read(appendixSection.record).position.outline.book)
        val paragraphs = resources.flatMap { it.section.paragraphs }
        assertEquals(standardRows.filter { it.record <= work.end }.map { it.paragraph() }, paragraphs)
        assertEquals(paragraphs.size, paragraphs.map { it.record }.distinct().size)
        assertTrue(paragraphs.none { it.record >= otherWork.record })
    }

    @Test
    fun previousAndNextAreWholeNeighborResourcesAndPreviousCanBeLoadedAtItsEnd() = runBlocking {
        val forward = walk()
        var current = forward.last()
        assertNull(current.section.next)
        for (index in forward.indices.reversed()) {
            assertEquals(forward[index].section, current.section)
            assertEquals(forward.getOrNull(index - 1)?.section?.node, current.section.previous)
            assertEquals(forward.getOrNull(index + 1)?.section?.node, current.section.next)
            current.section.previous?.let { previous ->
                val byOpening = read(previous.record)
                val byEnd = read(previous.end)
                assertEquals(previous, byOpening.section.node)
                assertEquals(byOpening.section, byEnd.section)
                assertEquals(byEnd.lastEntry, byEnd.position.index)
                assertEquals(previous.end, byEnd.anchorRecord)
                assertEquals(previous.end, byEnd.section.paragraphs.last().record)
                assertEquals(current.section.node, byEnd.section.next)
                // An end-intent caller gets every final paragraph, not just the last outline entry.
                current = byEnd
            }
        }
        assertNull(current.section.previous)
        assertEquals(work.record, current.section.node.record)
    }

    @Test
    fun longPurportAndFollowingVerseFlowInTheSameCompleteSourceDocument() = runBlocking {
        val book = ContentNode(1, 0, "Long generated work", 1405, 1, 1)
        val chapterNode = ContentNode(10, 1, "Generated chapter", 1400, 2, 99)
        val longVerse = ContentNode(20, 10, "Long verse", 1199, 1, 2)
        val purport = ContentNode(35, 20, "PURPORT", 1199, 0, 8)
        val following = ContentNode(1200, 10, "Next verse", 1390, 0, 3)
        val body = UNICODE.repeat(12_000)
        val fixture = registerFixture {
            populate(listOf(book to BOOK, chapterNode to CHAPTER, longVerse to VERSE, purport to VERSE_SECTION,
                following to VERSE), (1..1405).toList())
            execSQL("UPDATE texts SET plain=? WHERE recid=1100", arrayOf(body))
        }
        val result = read(1100, fixture)
        assertContract(result, 1100)
        assertEquals(chapterNode, result.section.node)
        assertEquals((10..1400).toList(), result.section.paragraphs.map { it.record })
        assertEquals(body, result.section.paragraphs.single { it.record == 1100 }.text)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), result.section.paragraphs.single { it.record == 1100 }.text.toByteArray(Charsets.UTF_8))
        assertEquals(result.section, read(following.record, fixture).section)
        assertEquals(listOf(longVerse, following), result.position.outline.entries.filter { it.kind == ReadingUnitKind.VERSE }.map { it.node })
    }

    @Test
    fun massiveChapterUsesFixedGreedyEntryWindowsWithoutSplittingAnyOriginalVerse() = runBlocking {
        val book = ContentNode(1, 0, "Large work", 14039, 1, 1)
        val chapterNode = ContentNode(10, 1, "Large chapter", 14029, 70, 2)
        val verses = (0 until 70).map { ContentNode(20 + 200 * it, 10, "Verse $it", 219 + 200 * it, 0, 7) }
        val fixture = registerFixture {
            populate(listOf(book to BOOK, chapterNode to CHAPTER) + verses.map { it to VERSE }, (1..14039).toList())
        }
        // Starting near the end must not create a selection-centred or differently sized window.
        val selected = read(13020, fixture)
        assertEquals(11820..14029, selected.section.node.record..selected.section.node.end)
        assertContract(selected, 13020)
        val resources = walk(fixture)
        assertEquals(listOf(1..9, 10..11819, 11820..14029, 14030..14039), resources.map { it.section.node.record..it.section.node.end })
        assertEquals((1..14039).toList(), resources.flatMap { it.section.paragraphs.map { row -> row.record } })
        for (resource in resources) {
            assertTrue(resource.section.paragraphs.size <= 12_000)
            val entries = resource.position.outline.entries.subList(resource.firstEntry, resource.lastEntry + 1)
            for (entry in entries.filter { it.kind == ReadingUnitKind.VERSE }) {
                assertEquals(entry.source, entry.node)
                assertTrue(resource.section.paragraphs.any { it.record == entry.node.record })
                assertTrue(resource.section.paragraphs.any { it.record == entry.node.end })
            }
        }
        repository.close()
        repository = IvdRepository(context, store) // A closed repository cannot be reused.
        val cold = read(13020, fixture)
        assertNotSame(selected.position.outline, cold.position.outline)
        assertEquals(selected.section, cold.section)
        assertEquals(selected.firstEntry, cold.firstEntry)
        assertEquals(selected.lastEntry, cold.lastEntry)
        assertEquals(verses, cold.position.outline.entries.filter { it.kind == ReadingUnitKind.VERSE }.map { it.node })
    }

    @Test
    fun legacyProseChunksRecombineAtChapterScopeAndStillMapToTheirActualSource() = runBlocking {
        val book = ContentNode(1, 0, "Prose", 12520, 1, 1)
        val chapterNode = ContentNode(10, 1, "Prose chapter", 12509, 0, 4)
        val fixture = registerFixture { populate(listOf(book to BOOK, chapterNode to CHAPTER), (1..12520).toList()) }
        val legacy = repository.bookSection(fixture.id, 12000)
        assertTrue(legacy.section.paragraphs.size <= 256)
        val result = read(12000, fixture)
        assertEquals(chapterNode, result.source)
        assertTrue(result.firstEntry < result.lastEntry)
        assertEquals(legacy.position.index, result.position.index)
        val resources = walk(fixture)
        assertEquals(listOf(1..9, 10..11785, 11786..12509, 12510..12520), resources.map { it.section.node.record..it.section.node.end })
        assertEquals((1..12520).toList(), resources.flatMap { it.section.paragraphs.map { row -> row.record } })
        assertEquals(legacy, repository.bookSection(fixture.id, 12000))
    }

    @Test
    fun characterWindowsUseUnicodeCharacterCountsRatherThanUtf8ByteCounts() = runBlocking {
        val body = "ā".repeat(1_400_000)
        val fixture = textBudgetFixture(listOf(body, body, body))
        val firstResource = read(21, fixture)
        val secondResource = read(61, fixture)
        assertEquals(10..59, firstResource.section.node.record..firstResource.section.node.end)
        assertEquals(60..99, secondResource.section.node.record..secondResource.section.node.end)
        assertTrue(firstResource.section.paragraphs.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } > 4_000_000)
        assertEquals(listOf(21, 41), firstResource.section.paragraphs.filter { it.text.isNotEmpty() }.map { it.record })
        for (resource in listOf(firstResource, secondResource)) {
            assertContract(resource, resource.anchorRecord)
            resource.section.paragraphs.filter { it.text.isNotEmpty() }.forEach { assertEquals(body, it.text) }
        }
        assertEquals(firstResource.section.node, secondResource.section.previous)
        assertEquals(secondResource.section.node, firstResource.section.next)
        assertEquals(firstResource.section, read(41, fixture).section)
    }

    @Test
    fun exactlyFourMillionCharactersAcrossAdjacentEntriesFitInOneResource() = runBlocking {
        val body = "ā".repeat(2_000_000)
        val fixture = textBudgetFixture(listOf(body, body))
        val result = read(41, fixture)
        assertEquals(10..79, result.section.node.record..result.section.node.end)
        assertEquals(4_000_000L, result.section.paragraphs.sumOf { it.text.codePointCount(0, it.text.length).toLong() })
        assertEquals(listOf(body, body), result.section.paragraphs.filter { it.text.isNotEmpty() }.map { it.text })
        assertEquals(result.section, read(21, fixture).section)
        assertContract(result, 41)
    }

    @Test
    fun exactlyTwelveThousandRowsInOneVerseRemainWholeEvenWhenHeaderMustBeSeparate() = runBlocking {
        val fixture = verseRowsFixture(11_999)
        val verse = checkNotNull(repository.node(fixture.id, 20))
        val result = read(20, fixture)
        assertEquals(12_000, result.section.paragraphs.size)
        assertEquals(verse.record..verse.end, result.section.node.record..result.section.node.end)
        assertEquals(result.firstEntry, result.lastEntry)
        assertEquals(verse, result.source)
        assertEquals(repository.bookSection(fixture.id, 20).section.paragraphs, result.section.paragraphs)
        assertContract(result, 20)
    }

    @Test
    fun anOversizedIndivisibleVerseFailsButDoesNotPreventReadingItsHeaderOrNextResource() = runBlocking {
        val fixtures = listOf(verseRowsFixture(12_000), textBudgetFixture(listOf("x".repeat(4_000_001), "Next complete text")))
        for (fixture in fixtures) {
            val header = read(10, fixture)
            assertEquals(10..19, header.section.node.record..header.section.node.end)
            val oversized = checkNotNull(header.section.next)
            assertEquals(20, oversized.record)
            expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(oversized.record, fixture) }
            expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(oversized.end, fixture) }
            val next = read(oversized.end + 1, fixture)
            assertEquals(oversized, next.section.previous)
            assertTrue(next.section.paragraphs.isNotEmpty())
            assertContract(next, oversized.end + 1)
            assertSame(header.position.outline, next.position.outline)
            assertEquals(header.section, read(10, fixture).section)
        }
    }

    @Test
    fun oneOversizedProseParagraphIsNeverTruncatedOrSplit() = runBlocking {
        val fixture = registerFixture {
            populate(listOf(ContentNode(1, 0, "Prose", 3, 0, 1) to BOOK), listOf(1, 2, 3))
            execSQL("UPDATE texts SET plain=? WHERE recid=2", arrayOf("x".repeat(4_000_001)))
        }
        val header = read(1, fixture)
        assertEquals(listOf(1..1, 2..2, 3..3), header.position.outline.entries.map { it.node.record..it.node.end })
        assertEquals(1..1, header.section.node.record..header.section.node.end)
        expectFailure(ReadingFailure.SECTION_TOO_LARGE) { read(2, fixture) }
        assertEquals(listOf(3), read(3, fixture).section.paragraphs.map { it.record })
    }

    @Test
    fun embeddedNulDoesNotHideAnOverBudgetSuffixFromResourceGrouping() = runBlocking {
        val body = "prefix\u0000" + "x".repeat(2_100_000)
        val fixture = textBudgetFixture(listOf(body, body))
        val firstResource = read(21, fixture)
        val secondResource = read(41, fixture)
        assertEquals(10..39, firstResource.section.node.record..firstResource.section.node.end)
        assertEquals(40..79, secondResource.section.node.record..secondResource.section.node.end)
        assertEquals(body, firstResource.section.paragraphs.single { it.record == 21 }.text)
        assertEquals(body, secondResource.section.paragraphs.single { it.record == 41 }.text)
        assertContract(firstResource, 21)
        assertContract(secondResource, 41)
    }

    @Test
    fun conservativeNulSizeCannotRejectAnIndivisibleEntryThatFitsTheActualCharacterGuard() = runBlocking {
        val body = "ā".repeat(2_100_000) + "\u0000नमूना🕉"
        val fixture = textBudgetFixture(listOf(body, "Following complete text"))
        val result = read(21, fixture)
        assertTrue(body.toByteArray(Charsets.UTF_8).size > 4_000_000)
        assertTrue(body.codePointCount(0, body.length) < 4_000_000)
        assertEquals(20..39, result.section.node.record..result.section.node.end)
        assertEquals(result.firstEntry, result.lastEntry)
        assertEquals(body, result.section.paragraphs.single { it.record == 21 }.text)
        assertContract(result, 21)
        assertEquals(result.section.node, read(41, fixture).section.previous)
        assertEquals(10..19, read(10, fixture).section.node.let { it.record..it.end })
    }

    @Test
    fun multilingualParagraphsIncludingNulAreByteExactInUtf8AndUtf16Sources() = runBlocking {
        val body = "ā\u0000नमूना🕉 a\u0304\r\n".repeat(20_000)
        for (encoding in listOf("UTF-8", "UTF-16le", "UTF-16be")) {
            val fixture = textBudgetFixture(listOf(body, body), encoding)
            val result = read(41, fixture)
            assertEquals(10..79, result.section.node.record..result.section.node.end)
            assertEquals(result.section, read(21, fixture).section)
            for (record in listOf(21, 41)) {
                val text = result.section.paragraphs.single { it.record == record }.text
                assertEquals(body, text)
                assertArrayEquals(body.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun sparseRangesAndEmptyResourcesCountActualRowsNotInclusiveRecordSpan() = runBlocking {
        val ids = listOf(1, 5, 25, 40, 50, 70, 100, 150, 155, 200, 202, 230, 240)
        val fixture = registerFixture { populate(standardHeadings, ids) }
        assertEquals(ids.filter { it <= work.end }, walk(fixture).flatMap { it.section.paragraphs.map { row -> row.record } })
        val selection = read(88, fixture)
        assertEquals(middle, selection.source)
        assertEquals(chapter, selection.section.node)
        assertContract(selection, 88)
        assertTrue(read(220, fixture).section.paragraphs.isEmpty())
        val hugeSparse = registerFixture {
            populate(listOf(ContentNode(1, 0, "Sparse work", Int.MAX_VALUE, 1, 1) to BOOK,
                ContentNode(10, 1, "Sparse chapter", Int.MAX_VALUE, 2, 2) to CHAPTER,
                ContentNode(20, 10, "First", 999_999, 0, 3) to VERSE,
                ContentNode(1_000_000, 10, "Last", Int.MAX_VALUE, 0, 3) to VERSE), listOf(1, 10, 20, 1_000_000, Int.MAX_VALUE))
        }
        val end = read(Int.MAX_VALUE, hugeSparse)
        assertEquals(10..Int.MAX_VALUE, end.section.node.record..end.section.node.end)
        assertEquals(listOf(10, 20, 1_000_000, Int.MAX_VALUE), end.section.paragraphs.map { it.record })
        assertNull(end.section.next)
        assertContract(end, Int.MAX_VALUE)
    }

    @Test
    fun appendixCantoSubchaptersStayInTheMixedChapterCollectionWithoutJoiningAcrossSubchapters() = runBlocking {
        val book = ContentNode(1, 0, "Collection", 100, 2, 99)
        val chapterNode = ContentNode(10, 1, "Not a chapter title", 39, 1, 3)
        val appendixNode = ContentNode(50, 1, "Not a canto title", 99, 1, 1)
        val subchapter = ContentNode(60, 50, "Subchapter", 89, 1, 7)
        val fixture = registerFixture {
            populate(listOf(book to COLLECTION, chapterNode to CHAPTER, ContentNode(20, 10, "", 39, 0, 99) to VERSE,
                appendixNode to " \t$CANTO\r\n", subchapter to " \t$CHAPTER\r\n",
                ContentNode(70, 60, "", 79, 0, 2) to VERSE), (1..100).toList())
        }
        val cold = read(75, fixture)
        assertEquals(book, cold.position.outline.book)
        assertEquals(subchapter, cold.section.node)
        assertEquals(50..59, read(55, fixture).section.node.let { it.record..it.end })
        assertEquals(90..99, read(95, fixture).section.node.let { it.record..it.end })
        assertEquals((1..100).toList(), walk(fixture).flatMap { it.section.paragraphs.map { row -> row.record } })
        assertSame(cold.position.outline, read(25, fixture).position.outline)
    }

    @Test
    fun cantoOnlyCollectionsKeepEachSelectedDirectCantoAsAHardSessionBoundary() = runBlocking {
        val book = ContentNode(1, 0, "Collection", 199, 2, 1)
        val firstCanto = ContentNode(10, 1, "First", 89, 2, 99)
        val secondCanto = ContentNode(100, 1, "Second", 189, 1, 3)
        val fixture = registerFixture {
            populate(listOf(book to COLLECTION, firstCanto to CANTO,
                ContentNode(20, 10, "Chapter", 59, 1, 2) to CHAPTER,
                ContentNode(30, 20, "Verse", 49, 0, 4) to VERSE,
                ContentNode(60, 10, "Prose", 79, 0, 3) to "LE_Section",
                secondCanto to CANTO, ContentNode(110, 100, "Verse", 149, 0, 4) to VERSE), (1..199).toList())
        }
        val firstResources = walk(fixture, firstCanto.record)
        assertEquals(listOf(10..19, 20..59, 60..89), firstResources.map { it.section.node.record..it.section.node.end })
        assertEquals(firstCanto, read(35, fixture).position.outline.book)
        assertNull(firstResources.last().section.next)
        val second = read(120, fixture)
        assertEquals(secondCanto, second.position.outline.book)
        assertEquals(secondCanto, second.section.node)
        assertNull(second.section.previous)
        assertNull(second.section.next)
        for (record in listOf(1, 5, 95, 195)) expectFailure(ReadingFailure.CHOOSE_SECTION) { read(record, fixture) }
    }

    @Test
    fun enclosingAnthologyChaptersCannotAbsorbNestedIndependentWorks() = runBlocking {
        val anthology = ContentNode(1, 0, "Anthology", 199, 1, 1)
        val outerChapter = ContentNode(10, 1, "Outer chapter", 189, 1, 2)
        val inner = ContentNode(20, 10, "Independent work", 129, 2, 3)
        for (level in listOf(BOOK, COLLECTION)) {
            val fixture = registerFixture {
                populate(listOf(anthology to COLLECTION, outerChapter to CHAPTER, inner to level,
                    ContentNode(30, 20, "First", 49, 0, 4) to VERSE,
                    ContentNode(50, 20, "Second", 79, 0, 4) to VERSE), (1..199).toList())
            }
            val result = read(55, fixture)
            assertEquals(inner, result.position.outline.book)
            assertEquals(inner, result.section.node)
            assertEquals((20..129).toList(), result.section.paragraphs.map { it.record })
            assertNull(result.section.previous)
            assertNull(result.section.next)
            for (record in listOf(1, 10, 130, 189)) expectFailure(ReadingFailure.CHOOSE_SECTION) { read(record, fixture) }
        }
    }

    @Test
    fun invalidSelectionsUnindexedWorkMarkersAndDuplicateRowsStillFailClosed() = runBlocking {
        for (record in listOf(-1, 0, 270, Int.MAX_VALUE)) expectFailure(ReadingFailure.LOAD) { read(record) }
        assertEquals(otherWork, read(otherVerse.record).position.outline.book)
        assertNull(read(otherVerse.record).section.previous)
        assertNull(read(otherVerse.record).section.next)
        for (mutation in listOf("UPDATE texts SET levelname='LE_Book' WHERE recid=88",
            "UPDATE contents SET next_sibling=79 WHERE record=50",
            "INSERT INTO texts VALUES (88,'Duplicate','Body','Text')")) {
            val fixture = registerFixture { insertStandardFixture(); execSQL(mutation) }
            expectFailure(ReadingFailure.LOAD) { read(70, fixture) }
        }
    }

    @Test
    fun resourcesDoNotConfuseIdenticalRecordIdsInDifferentLibrariesOrCacheParagraphLists() = runBlocking {
        val firstResult = read(88)
        val fixture = registerFixture {
            insertStandardFixture()
            execSQL("UPDATE contents SET title='Another work' WHERE record=1")
            execSQL("UPDATE texts SET plain='Other library only' WHERE recid=88")
        }
        val second = read(88, fixture)
        assertNotSame(firstResult.position.outline, second.position.outline)
        assertEquals("Another work", second.position.outline.book.title)
        assertEquals("Other library only", second.section.paragraphs.single { it.record == 88 }.text)
        val repeated = read(88)
        assertSame(firstResult.position.outline, repeated.position.outline)
        assertNotSame(firstResult.section.paragraphs, repeated.section.paragraphs)
        assertEquals(firstResult.section, repeated.section)
        assertEquals(UNICODE, repeated.section.paragraphs.single { it.record == 88 }.text)
    }

    @Test
    fun concurrentSelectionsConvergeOnOneOutlineAndOneFixedResourcePartition() = runBlocking {
        val records = listOf(40, 50, 59, 70, 80, 88, 100, 129, 139)
        val results = records.map { record -> async { read(record) } }.awaitAll()
        results.forEachIndexed { index, result ->
            assertContract(result, records[index])
            assertSame(results.first().position.outline, result.position.outline)
            assertSame(results.first().section.node, result.section.node)
            assertEquals(results.first().section, result.section)
        }
    }

    @Test
    fun resourceMetadataSharesTheFourWorkLruAndCloseInvalidatesItWithoutChangingRetainedSnapshots() = runBlocking {
        val books = (0..4).map { ContentNode(1000 + 100 * it, 0, "Work $it", 1009 + 100 * it, 0, 1) }
        val fixture = registerFixture { populate(books.map { it to BOOK }, books.flatMap { (it.record..it.end).toList() }) }
        val firstResult = read(books[0].record, fixture)
        val second = read(books[1].record, fixture)
        read(books[2].record, fixture)
        read(books[3].record, fixture)
        assertSame(firstResult.position.outline, read(books[0].record, fixture).position.outline)
        read(books[4].record, fixture)
        assertSame(firstResult.position.outline, read(books[0].record, fixture).position.outline)
        assertNotSame(second.position.outline, read(books[1].record, fixture).position.outline)
        val retainedEntries = firstResult.position.outline.entries.toList()
        repository.close()
        repository = IvdRepository(context, store)
        val reopened = read(books[0].record, fixture)
        assertNotSame(firstResult.position.outline, reopened.position.outline)
        assertEquals(retainedEntries, reopened.position.outline.entries)
        assertEquals(retainedEntries, firstResult.position.outline.entries)
        assertEquals(firstResult.section, reopened.section)
    }

    @Test
    fun cancellationDuringResourceMetadataLeavesNoPartialResourceAndCanRetryUsingTheLegacyOutline() = runBlocking {
        val fixture = registerFixture { populate(listOf(ContentNode(1, 0, "Prose", 2000, 0, 1) to BOOK), (1..2000).toList()) }
        openReadOnly(fixture).use { database ->
            val reader = IvdBookReader(database)
            val outline = reader.read(1).position.outline
            var returned = false
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { reader.readResource(1000); returned = true }
            assertTrue(pending.isActive)
            pending.cancelAndJoin()
            assertFalse(returned)
            val result = reader.readResource(1000)
            assertSame(outline, result.position.outline)
            assertEquals((1..2000).toList(), result.section.paragraphs.map { it.record })
            assertContract(result, 1000)
            assertTrue(database.isReadOnly)
        }
    }

    @Test
    fun clearingCacheDuringResourceMetadataCannotRepublishAnOldSnapshot() = runBlocking {
        val fixture = registerFixture { populate(listOf(ContentNode(1, 0, "Prose", 2000, 0, 1) to BOOK), (1..2000).toList()) }
        openReadOnly(fixture).use { database ->
            val cache = IvdBookReader.OutlineCache()
            val reader = IvdBookReader(database, cache)
            val old = reader.read(1).position.outline
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                try { reader.readResource(1000); null } catch (error: ReadingException) { error.reason }
            }
            assertTrue(pending.isActive)
            cache.clear()
            assertEquals(ReadingFailure.LOAD, pending.await())
            val result = reader.readResource(1000)
            assertNotSame(old, result.position.outline)
            assertEquals(old.entries, result.position.outline.entries)
            assertEquals(2000, result.section.paragraphs.size)
        }
    }

    @Test
    fun preCancelledResourceRequestPropagatesCancellation() = runBlocking {
        val cancelled = Job().also { it.cancel() }
        var returned = false
        var cancellation = false
        try {
            withContext(cancelled) { read(88); returned = true }
        } catch (_: CancellationException) { cancellation = true }
        assertTrue(cancellation)
        assertFalse(returned)
        assertEquals(chapter, read(88).section.node)
    }

    @Test
    fun resourceModelRejectsMismatchedRangesAndAnchorsOutsideTheSelectedOutlineEntry() = runBlocking {
        val resource = read(middle.record)
        val mutations = listOf<() -> BookResource>(
            { resource.copy(firstEntry = resource.position.index) },
            { resource.copy(lastEntry = resource.firstEntry) },
            { resource.copy(anchorRecord = chapter.record) },
            { resource.copy(section = resource.section.copy(node = chapter.copy(end = chapter.end - 1))) },
        )
        for (mutation in mutations) {
            var rejected = false
            try { mutation() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Invalid resource must not be constructible", rejected)
        }
    }

    private suspend fun read(record: Int, fixture: LibraryInfo = library): BookResource = repository.bookResource(fixture.id, record)

    private fun assertContract(resource: BookResource, requested: Int) {
        val entries = resource.position.outline.entries
        assertEquals(requested, resource.anchorRecord)
        assertEquals(entries.indexOfFirst { requested in it.node.record..it.node.end }, resource.position.index)
        assertTrue(resource.position.index in resource.firstEntry..resource.lastEntry)
        assertEquals(entries[resource.firstEntry].node.record, resource.section.node.record)
        assertEquals(entries[resource.lastEntry].node.end, resource.section.node.end)
        assertEquals(entries[resource.position.index].source, resource.source)
        assertEquals(entries.getOrNull(resource.firstEntry - 1)?.node?.end, resource.section.previous?.end)
        assertEquals(entries.getOrNull(resource.lastEntry + 1)?.node?.record, resource.section.next?.record)
        assertTrue(resource.section.paragraphs.size <= 12_000)
        assertTrue(resource.section.paragraphs.sumOf { it.text.codePointCount(0, it.text.length).toLong() } <= 4_000_000)
        assertTrue(resource.section.paragraphs.zipWithNext().all { (left, right) -> left.record < right.record })
    }

    private suspend fun walk(fixture: LibraryInfo = library, start: Int = 1): List<BookResource> {
        val result = mutableListOf<BookResource>()
        var current = read(start, fixture)
        assertNull(current.section.previous)
        while (true) {
            assertContract(current, current.anchorRecord)
            assertTrue("Resource cycle", result.size < current.position.outline.entries.size)
            result.lastOrNull()?.let { previous ->
                assertSame(previous.position.outline, current.position.outline)
                assertEquals(previous.lastEntry + 1, current.firstEntry)
                assertEquals(previous.section.node.end.toLong() + 1, current.section.node.record.toLong())
                assertEquals(previous.section.node, current.section.previous)
            }
            result += current
            val next = current.section.next ?: break
            current = read(next.record, fixture)
            assertEquals(next, current.section.node)
        }
        assertEquals(0, result.first().firstEntry)
        assertEquals(current.position.outline.entries.lastIndex, result.last().lastEntry)
        return result
    }

    private suspend fun expectFailure(reason: ReadingFailure, action: suspend () -> BookResource) {
        var result: BookResource? = null
        val failure = try { result = action(); null } catch (error: ReadingException) { error }
        assertNull("No partial resource on $reason", result)
        assertEquals(reason, failure?.reason)
    }

    private fun textBudgetFixture(bodies: List<String>, encoding: String = "UTF-8"): LibraryInfo = registerFixture(encoding) {
        val end = 39 + 20 * bodies.size
        val book = ContentNode(1, 0, "Text budget", end + 10, 1, 1)
        val chapterNode = ContentNode(10, 1, "Chapter", end, bodies.size, 2)
        val verses = bodies.indices.map { ContentNode(20 + 20 * it, 10, "Verse $it", 39 + 20 * it, 0, 3) }
        populate(listOf(book to BOOK, chapterNode to CHAPTER) + verses.map { it to VERSE },
            listOf(1, 10, end, end + 10) + verses.flatMap { listOf(it.record, it.record + 1) }, emptyText = true)
        bodies.forEachIndexed { index, body -> execSQL("UPDATE texts SET plain=? WHERE recid=?", arrayOf(body, 21 + 20 * index)) }
    }

    private fun verseRowsFixture(bodyRows: Int): LibraryInfo = registerFixture {
        val verse = ContentNode(20, 10, "Whole verse", 20 + bodyRows, 0, 3)
        val following = ContentNode(verse.end + 1, 10, "Following", verse.end + 5, 0, 3)
        val book = ContentNode(1, 0, "Row budget", following.end + 10, 1, 1)
        val chapterNode = ContentNode(10, 1, "Chapter", following.end, 2, 2)
        populate(listOf(book to BOOK, chapterNode to CHAPTER, verse to VERSE, following to VERSE),
            listOf(1, 10, book.end) + (verse.record..following.end).toList())
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
        val fixture = LibraryInfo(id, "Generated resource fixture", "sa-Latn", file.name, file.length(), count, "Synthetic only")
        store.addLibrary(fixture)
        expectedPreferences = preferences.all.toMap()
        return fixture
    }

    private fun openReadOnly(fixture: LibraryInfo) = SQLiteDatabase.openDatabase(
        File(File(context.noBackupFilesDir, "libraries"), fixture.fileName).absolutePath, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    private data class SourceSnapshot(val file: File, val size: Long, val hash: ByteArray)
    private data class Row(val record: Int, val text: String?, val style: String? = "Body", val level: String? = "Text") {
        fun paragraph() = Paragraph(record, text.orEmpty(), style.orEmpty().trim(), level.orEmpty())
    }

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "ivd-book-resource-test-${root.name}-"
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
        private const val BOOK = "LE_Book"
        private const val COLLECTION = "LE_Collection"
        private const val CHAPTER = "LE_Chapter"
        private const val CANTO = "LE_Canto"
        private const val VERSE = "LE_Verse_Number"
        private const val VERSE_SECTION = "LE_Verse_Section"
        private const val UNICODE = " \tGenerated नमूना বাংলা ā a\u0304 🕉 <PS:\"Fixture\"> & data\r\n"
        private val work = ContentNode(1, 0, "01. Generated work", 229, 5, 99)
        private val preface = ContentNode(5, 1, "Front matter", 19, 0, 87)
        private val introduction = ContentNode(25, 1, "Introduction", 34, 0, 8)
        private val chapter = ContentNode(40, 1, "Chapter ā", 139, 4, 3)
        private val first = ContentNode(50, 40, "First verse", 69, 3, 80)
        private val middle = ContentNode(70, 40, "Verses 2–3", 99, 2, 2)
        private val last = ContentNode(100, 40, "Last verse", 119, 0, 99)
        private val nextChapter = ContentNode(150, 1, "Next chapter", 189, 1, 1)
        private val nextVerse = ContentNode(155, 150, "Next verse", 179, 0, 999)
        private val appendix = ContentNode(200, 1, "Appendix", 219, 1, 3)
        private val appendixSection = ContentNode(202, 200, "Appendix prose", 209, 0, 90)
        private val otherWork = ContentNode(230, 0, "Other work", 269, 1, 1)
        private val otherVerse = ContentNode(240, 230, "Other verse", 259, 0, 2)
        private val standardHeadings = listOf(work to COLLECTION, preface to CHAPTER, introduction to CHAPTER,
            chapter to CHAPTER, first to VERSE, ContentNode(51, 50, "TEXT", 54, 0, 4) to VERSE_SECTION,
            ContentNode(55, 50, "TRANSLATION", 58, 0, 4) to VERSE_SECTION,
            ContentNode(59, 50, "PURPORT", 69, 0, 4) to VERSE_SECTION,
            middle to VERSE, ContentNode(71, 70, "TEXT", 74, 0, 4) to VERSE_SECTION,
            ContentNode(80, 70, "PURPORT", 99, 1, 4) to VERSE_SECTION,
            ContentNode(85, 80, "Nested", 89, 0, 8) to VERSE_SECTION, last to VERSE,
            ContentNode(120, 40, "Prose", 129, 0, 2) to "LE_Section", nextChapter to CHAPTER, nextVerse to VERSE,
            appendix to CANTO, appendixSection to "LE_Section", otherWork to BOOK, otherVerse to VERSE)
        private val expectedRanges = listOf(1..4, 5..19, 20..24, 25..34, 35..39, 40..139, 140..149,
            150..189, 190..199, 200..219, 220..229)
        private val standardRows = (1..269).map { record ->
            val heading = standardHeadings.firstOrNull { it.first.record == record }
            when (record) {
                51 -> Row(record, "<PS:\"PA_Titles\">TEXT 1", "PA_Titles", VERSE_SECTION)
                71 -> Row(record, "<PS:\"PA_Titles\">TEXTS 2–3", "PA_Titles", VERSE_SECTION)
                88 -> Row(record, UNICODE, " \tPA_Fixture ā\r\n", " \tText नमूना\r\n")
                89 -> Row(record, null, null, null)
                90 -> Row(record, " \t ", " \t\r\n", " \t ")
                else -> Row(record, "Generated $record", if (heading == null) "Body" else "PA_Titles", heading?.second ?: "Text")
            }
        }

        private fun SQLiteDatabase.insertStandardFixture() {
            standardHeadings.reversed().forEach { insertNode(it.first) }
            standardRows.reversed().forEach { insertRow(it) }
        }

        private fun SQLiteDatabase.populate(headings: List<Pair<ContentNode, String>>, records: List<Int>, emptyText: Boolean = false) {
            val levels = headings.associate { it.first.record to it.second }
            headings.reversed().forEach { insertNode(it.first) }
            records.reversed().forEach { insertRow(Row(it, if (emptyText) "" else "Generated $it", level = levels[it] ?: "Text")) }
        }

        private fun SQLiteDatabase.insertNode(node: ContentNode) {
            execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code))
        }

        private fun SQLiteDatabase.insertRow(row: Row) {
            execSQL("INSERT INTO texts VALUES (?,?,?,?)", arrayOf(row.record, row.text, row.style, row.level))
        }

        private fun sha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                var count = input.read(buffer)
                while (count >= 0) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return digest.digest()
        }
    }
}