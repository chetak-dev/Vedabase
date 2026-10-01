package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic, directly registered SQLite fixtures only: never imports or opens a user corpus. */
@RunWith(AndroidJUnit4::class)
class IvdSectionsTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var store: LibraryStore
    private lateinit var repository: IvdRepository
    private lateinit var library: LibraryInfo
    private lateinit var originalPreferences: SharedPreferences
    private lateinit var originalRegistry: Map<String, *>
    private val sources = mutableListOf<SourceSnapshot>()

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        originalPreferences = target.getSharedPreferences("reader", Context.MODE_PRIVATE)
        originalRegistry = originalPreferences.all.toMap()
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
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
            if (::originalRegistry.isInitialized) assertEquals(originalRegistry, originalPreferences.all)
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
    fun completeVerseContainsAll130IrregularRecordsExactlyOnceIncludingTheEntirePurport() = runBlocking {
        val result = read(verse.record)
        assertEquals(verse, result.node)
        assertEquals(ReadingUnitKind.VERSE, result.kind)
        assertEquals(chapter.displayTitle, result.parentTitle)
        assertEquals(records, result.paragraphs.map { it.record })
        assertEquals(130, result.paragraphs.size)
        assertEquals(130, result.paragraphs.map { it.record }.toSet().size)
        assertEquals(records.drop(40), result.paragraphs.filter { it.record >= purport.record }.map { it.record })
        assertEquals(verse.end, result.paragraphs.last().record)
        assertTrue(records.zipWithNext().map { (a, b) -> b - a }.distinct().size > 1)
        assertTrue(result.paragraphs.none { it.record == verse.record - 1 || it.record == verse.end + 1 })
    }

    @Test
    fun textTranslationPurportAndNestedPurportAllPromoteToTheSameCompleteVerse() = runBlocking {
        val full = read(verse.record)
        for (record in listOf(text.record, translation.record, purport.record, nestedPurport.record)) {
            assertEquals("Requested child $record", full, read(record))
        }
    }

    @Test
    fun verseNeighboursSkipSubsectionsNonVerseSiblingsAndJumpLinksWithoutSplittingCombinedVerses() = runBlocking {
        val units = listOf(verse, untitledVerse, combinedVerse, duplicateOne, duplicateTwo)
        for ((index, node) in units.withIndex()) {
            val result = read(node.record)
            assertEquals(node, result.node)
            assertEquals(ReadingUnitKind.VERSE, result.kind)
            assertEquals(units.getOrNull(index - 1), result.previous)
            if (index < units.lastIndex) assertEquals(units[index + 1], result.next)
        }
        assertEquals("", read(untitledVerse.record).node.title)
        assertEquals(listOf(1207, 1249, 1299), read(combinedVerse.record).paragraphs.map { it.record })
        assertEquals("Verses 3–4", read(combinedVerse.record).node.displayTitle)
        assertEquals(duplicateOne.title, duplicateTwo.title)
        assertTrue(duplicateOne.record != duplicateTwo.record)
        assertEquals(duplicateTwo, read(duplicateOne.record).next)
        assertEquals(duplicateOne, read(duplicateTwo.record).previous)
    }

    @Test
    fun lastChapterVerseAndFirstNextChapterVerseNavigateAcrossSemanticLevelsWithinTheWork() = runBlocking {
        // Numeric levels deliberately differ: semantic source names, not magic numbers, define units.
        assertTrue(duplicateTwo.level != nextChapterVerse.level)
        assertEquals(nextChapterVerse, read(duplicateTwo.record).next)
        assertEquals(duplicateTwo, read(nextChapterVerse.record).previous)
        assertEquals(nextChapter.displayTitle, read(nextChapterVerse.record).parentTitle)
    }

    @Test
    fun chapterNavigationKeepsWholeChaptersAndTreatsTheSourceClassifiedIntroductionAsAChapter() = runBlocking {
        val introduction = read(intro.record)
        assertEquals(ReadingUnitKind.CHAPTER, introduction.kind)
        assertNull(introduction.previous)
        assertEquals(chapter, introduction.next)
        val full = read(chapter.record)
        assertEquals(ReadingUnitKind.CHAPTER, full.kind)
        assertEquals(intro, full.previous)
        assertEquals(nextChapter, full.next)
        assertEquals(allRows.filter { it.record in chapter.record..chapter.end }.map { it.record }.sorted(),
            full.paragraphs.map { it.record })
        assertTrue(full.paragraphs.size > 130)
        assertEquals(chapter, read(nextChapter.record).previous)
        assertNull(read(nextChapter.record).next)
    }

    @Test
    fun lastWorkVerseNeverEntersANestedBookOrAnotherCollection() = runBlocking {
        assertNull(read(verse.record).previous)
        assertNull(read(nextChapterVerse.record).next)
        assertNull(read(nestedBookVerse.record).previous)
        assertNull(read(nestedBookVerse.record).next)
        assertNull(read(otherWorkVerse.record).previous)
        assertNull(read(otherWorkVerse.record).next)
        assertEquals(ReadingUnitKind.VERSE, read(nestedBookVerse.record).kind)
        assertEquals(ReadingUnitKind.VERSE, read(otherWorkVerse.record).kind)
    }

    @Test
    fun blankMetadataAndMultilingualSourceBytesArePreservedWithOnlyStylesTrimmed() = runBlocking {
        val paragraphs = read(verse.record).paragraphs.associateBy { it.record }
        assertEquals(Paragraph(records[7], "", "", ""), paragraphs.getValue(records[7]))
        assertEquals(Paragraph(records[8], " \t ", "", " \t "), paragraphs.getValue(records[8]))
        val unicode = paragraphs.getValue(records[63])
        assertEquals(UNICODE_TEXT, unicode.text)
        assertArrayEquals(UNICODE_TEXT.toByteArray(Charsets.UTF_8), unicode.text.toByteArray(Charsets.UTF_8))
        assertEquals("PA_Fixture ā a\u0304", unicode.style)
        assertEquals(UNICODE_LEVEL, unicode.level)
        assertEquals(work.displayTitle, read(chapter.record).parentTitle)
    }

    @Test
    fun collectionBookAndCantoContainersRequireExplicitSectionSelection() = runBlocking {
        for (node in listOf(work, nestedBook, canto, otherWork)) {
            expectFailure(ReadingFailure.CHOOSE_SECTION) { read(node.record) }
        }
    }

    @Test
    fun moreThanFourMillionCharactersFailsWithoutReturningAPartialSection() = runBlocking {
        val oversized = registerFixture {
            insertHeading(largeWork, "LE_Collection")
            insertHeading(largeVerse, "LE_Verse_Number")
            // Each row stays well below CursorWindow limits; the aggregate exceeds the unit limit.
            val body = "x".repeat(50_001)
            repeat(80) { index -> insertRow(Row(largeVerse.record + index + 1, body)) }
        }
        expectFailure(ReadingFailure.SECTION_TOO_LARGE) {
            repository.readingSection(oversized.id, largeVerse.record)
        }
        assertEquals(records, read(verse.record).paragraphs.map { it.record })
    }

    @Test
    fun moreThanTwelveThousandRecordsFailsWithoutReturningAPartialSection() = runBlocking {
        val oversized = registerFixture {
            insertHeading(largeWork, "LE_Collection")
            insertHeading(largeVerse, "LE_Verse_Number")
            repeat(12_000) { index -> insertRow(Row(largeVerse.record + index + 1, "x")) }
        }
        expectFailure(ReadingFailure.SECTION_TOO_LARGE) {
            repository.readingSection(oversized.id, largeVerse.record)
        }
        assertEquals(130, read(verse.record).paragraphs.size)
    }

    @Test
    fun malformedCyclesParentBoundsMissingParentsAndUnknownRecordsCannotProduceASection() = runBlocking {
        val malformed = registerFixture {
            insertHeading(ContentNode(20001, 20011, "Cycle A", 20099, 1, 42), "LE_Verse_Number")
            insertHeading(ContentNode(20011, 20001, "Cycle B", 20099, 1, 83), "LE_Verse_Section")
            insertHeading(ContentNode(30001, 0, "Short parent", 30019, 1, 17), "LE_Chapter")
            insertHeading(ContentNode(30011, 30001, "Outside parent", 30029, 0, 42), "LE_Verse_Number")
            insertHeading(ContentNode(40011, 40001, "Missing parent", 40019, 0, 42), "LE_Verse_Number")
            insertHeading(ContentNode(50001, 0, "Reversed range", 50000, 0, 42), "LE_Verse_Number")
        }
        for (record in listOf(20001, 20011, 30011, 50001)) {
            var returned: ReadingSection? = null
            val error = try {
                returned = repository.readingSection(malformed.id, record)
                null
            } catch (error: IllegalStateException) { error }
            assertNull("Malformed record $record returned a unit", returned)
            assertTrue("Malformed record $record must be rejected", error != null)
        }
        expectFailure(ReadingFailure.LOAD) { repository.readingSection(malformed.id, 40011) }
        expectFailure(ReadingFailure.LOAD) { read(987654) }
    }

    @Test
    fun emptyUnitsRepeatedReadsAndReopensLeaveAllSourceBytesAndBothRegistriesUnchanged() = runBlocking {
        val expectedRegistry = context.getSharedPreferences("reader", Context.MODE_PRIVATE).all.toMap()
        val expected = read(verse.record)
        repeat(2) {
            val empty = read(emptySection.record)
            assertEquals(emptySection, empty.node)
            assertEquals(ReadingUnitKind.SECTION, empty.kind)
            assertTrue(empty.paragraphs.isEmpty())
            assertNull(empty.previous)
            assertNull(empty.next)
            assertEquals(expected, read(purport.record))
            repository.close()
            assertSourcesUnchanged()
            assertEquals(setOf(library.fileName), sources.single().file.parentFile!!.list()!!.toSet())
            val reopenedStore = LibraryStore(context)
            assertEquals(listOf(library), reopenedStore.libraries())
            assertEquals(library.id, reopenedStore.activeId)
            repository = IvdRepository(context, reopenedStore)
        }
        assertEquals(expected, read(verse.record))
        assertEquals(expectedRegistry, context.getSharedPreferences("reader", Context.MODE_PRIVATE).all)
        assertEquals(originalRegistry, originalPreferences.all)
    }

    private suspend fun read(record: Int) = repository.readingSection(library.id, record)

    private suspend fun expectFailure(reason: ReadingFailure, action: suspend () -> ReadingSection) {
        var returned: ReadingSection? = null
        val failure = try {
            returned = action()
            null
        } catch (error: ReadingException) { error }
        assertNull("No partial unit may escape on $reason", returned)
        assertEquals(reason, failure?.reason)
    }

    private fun registerFixture(extra: SQLiteDatabase.() -> Unit = {}): LibraryInfo {
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "libraries").also { check(it.isDirectory || it.mkdirs()) }
        val file = File(directory, "$id.ivd")
        var count = 0L
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE contents (
                        record INTEGER PRIMARY KEY, parent INTEGER, title TEXT, next_sibling INTEGER,
                        node_children INTEGER, level INTEGER, simpletitle TEXT, node_code TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE texts (recid INTEGER PRIMARY KEY, plain TEXT, stylename TEXT, levelname TEXT)")
                db.execSQL("CREATE TABLE jumplinks (title TEXT, recid INTEGER)")
                for ((node, _) in headings.reversed()) db.insertNode(node)
                db.insertNode(emptySection)
                // Missing title must not prevent classification by the joined source level.
                db.execSQL("UPDATE contents SET title=NULL WHERE record=?", arrayOf(untitledVerse.record))
                for (row in allRows.reversed()) db.insertRow(row)
                db.execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf(verse.title, otherWorkVerse.record))
                db.execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf(combinedVerse.title, purport.record))
                db.extra()
                db.rawQuery("SELECT count(*) FROM texts", null).use { check(it.moveToFirst()); count = it.getLong(0) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // The writable builder is closed before hashing, registration, or any repository reads.
        check(file.setReadOnly())
        sources += SourceSnapshot(file, file.length(), sha256(file))
        return LibraryInfo(id, "Generated semantic section fixture", "sa-Latn", file.name,
            file.length(), count, "Synthetic test data only.").also(store::addLibrary)
    }

    private fun assertSourcesUnchanged() {
        for ((file, size, hash) in sources) {
            assertTrue(file.isFile)
            assertEquals(size, file.length())
            assertArrayEquals("Read-only fixture ${file.name} changed", hash, sha256(file))
            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use {
                assertTrue(it.isReadOnly)
            }
        }
    }

    private data class SourceSnapshot(val file: File, val size: Long, val hash: ByteArray)
    private data class Row(val record: Int, val plain: String?, val style: String? = "Body", val level: String? = "Text")

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "ivd-sections-test-${root.name}-"
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
        fun deleteOwnedPreferences() { ownedPreferences.forEach { check(baseContext.deleteSharedPreferences(it)) } }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    companion object {
        private const val UNICODE_TEXT = " \tGenerated नमूना हिन्दी বাংলা ā a\u0304 ī i\u0304 ṛ r\u0323 ś ṣ ñ\r\n<PS:\"Fixture\">text & data.  "
        private const val UNICODE_LEVEL = " \tLE_Fixture नमूना a\u0304 \r\n"
        private val records = buildList {
            var record = 100
            val gaps = listOf(2, 7, 3, 11, 5)
            repeat(130) { index -> add(record); record += gaps[index % gaps.size] }
        }
        private val work = ContentNode(3, 0, "01. Generated work नमूना", 9999, 6, 9, "TEXT")
        private val intro = ContentNode(11, 3, "02. Introduction", 29, 0, 17, "TEXT")
        private val chapter = ContentNode(31, 3, "03. Chapter ā", 1999, 6, 17, "TEXT")
        private val verse = ContentNode(100, 31, "04. Verse 1", records.last(), 3, 42, "TEXT")
        private val text = ContentNode(records[1], 100, "TEXT", records[19], 0, 83, "TEXT")
        private val translation = ContentNode(records[20], 100, "TRANSLATION", records[39], 0, 83, "TEXT")
        private val purport = ContentNode(records[40], 100, "PURPORT", records.last(), 1, 83, "TEXT")
        private val nestedPurport = ContentNode(records[50], purport.record, "Nested PURPORT", records.last(), 0, 97, "TEXT")
        private val irrelevant = ContentNode(951, 31, "Verse-looking non-verse", 979, 0, 42, "TEXT")
        private val untitledVerse = ContentNode(1007, 31, "", 1099, 0, 42, "TEXT")
        private val combinedVerse = ContentNode(1207, 31, "05. Verses 3–4", 1299, 0, 42, "TEXT")
        private val duplicateOne = ContentNode(1331, 31, "Duplicate title", 1399, 0, 42, "TEXT")
        private val duplicateTwo = ContentNode(1447, 31, "Duplicate title", 1499, 0, 42, "TEXT")
        private val nextChapter = ContentNode(2003, 3, "06. Chapter বাংলা", 2999, 1, 29, "TEXT")
        private val nextChapterVerse = ContentNode(2011, 2003, "Verse in next chapter", 2199, 0, 56, "TEXT")
        private val nestedBook = ContentNode(4001, 3, "Nested separate book", 4999, 1, 21, "TEXT")
        private val nestedBookChapter = ContentNode(4021, 4001, "Nested chapter", 4899, 1, 29, "TEXT")
        private val nestedBookVerse = ContentNode(4111, 4021, "Other book verse", 4199, 0, 56, "TEXT")
        private val canto = ContentNode(6001, 3, "Canto", 6999, 0, 35, "TEXT")
        private val emptySection = ContentNode(7001, 3, "Empty source section", 7009, 0, 65, "TEXT")
        private val otherWork = ContentNode(10003, 0, "Other collection", 10999, 1, 9, "TEXT")
        private val otherChapter = ContentNode(10031, 10003, "Other chapter", 10899, 1, 29, "TEXT")
        private val otherWorkVerse = ContentNode(10111, 10031, "Other collection verse", 10199, 0, 56, "TEXT")
        private val largeWork = ContentNode(60001, 0, "Large synthetic work", 99999, 1, 9, "TEXT")
        private val largeVerse = ContentNode(60011, 60001, "Oversized verse", 90011, 0, 42, "TEXT")
        private val headings = listOf(
            work to "LE_Collection", intro to "LE_Chapter", chapter to "LE_Chapter",
            verse to "LE_Verse_Number", text to "LE_Verse_Section", translation to "LE_Verse_Section",
            purport to "LE_Verse_Section", nestedPurport to "LE_Verse_Section", irrelevant to "LE_Section",
            untitledVerse to "LE_Verse_Number", combinedVerse to "LE_Verse_Number",
            duplicateOne to "LE_Verse_Number", duplicateTwo to "LE_Verse_Number",
            nextChapter to "LE_Chapter", nextChapterVerse to "LE_Verse_Number",
            nestedBook to "LE_Book", nestedBookChapter to "LE_Chapter", nestedBookVerse to "LE_Verse_Number",
            canto to "LE_Canto", otherWork to "LE_Collection", otherChapter to "LE_Chapter",
            otherWorkVerse to "LE_Verse_Number",
        )
        private val allRows: List<Row> = buildList {
            val levels = headings.associate { it.first.record to it.second }
            for ((index, record) in records.withIndex()) add(when (index) {
                7 -> Row(record, null, null, null)
                8 -> Row(record, " \t ", " \r\n\t ", " \t ")
                63 -> Row(record, UNICODE_TEXT, " \tPA_Fixture ā a\u0304\r\n ", UNICODE_LEVEL)
                else -> Row(record, "Generated record $record", level = levels[record] ?: "Text")
            })
            for ((node, level) in headings) if (node.record !in records) {
                add(Row(node.record, "Generated heading ${node.record}", level = level))
            }
            for (record in listOf(verse.record - 1, verse.end + 1, 1099, 1249, 1299, 1399, 1499, 2199, 4199, 10199)) {
                add(Row(record, "Generated boundary record $record"))
            }
        }

        private fun SQLiteDatabase.insertNode(node: ContentNode) {
            execSQL("INSERT INTO contents (record,parent,title,next_sibling,node_children,level,simpletitle,node_code) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code))
        }
        private fun SQLiteDatabase.insertRow(row: Row) {
            execSQL("INSERT INTO texts (recid,plain,stylename,levelname) VALUES (?,?,?,?)",
                arrayOf(row.record, row.plain, row.style, row.level))
        }
        private fun SQLiteDatabase.insertHeading(node: ContentNode, level: String) {
            insertNode(node)
            insertRow(Row(node.record, "Generated heading ${node.record}", level = level))
        }
        private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    }
}