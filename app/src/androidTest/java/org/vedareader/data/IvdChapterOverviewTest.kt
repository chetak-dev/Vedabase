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
import org.vedareader.reader.FolioText
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Generated SQLite only; no importer, corpus, or access to the app's unprefixed preferences. */
@RunWith(AndroidJUnit4::class)
class IvdChapterOverviewTest {
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
    fun directSemanticVersesKeepSourceOrderAndExactUnicodeParagraphsJoinedByBlankLines() = runBlocking {
        val result = requireNotNull(overview())
        assertEquals(chapter, result.chapter)
        assertEquals(verseNodes, result.verses.map { it.node })
        assertEquals(expectedPreviews, result.verses)
        assertTrue(firstVerse.level != secondVerse.level)
        assertEquals(firstVerse.level, preface.level) // Numeric levels cannot classify a verse.
        assertTrue(firstVerse.title > secondVerse.title) // Titles cannot determine source order.

        // An independent literal expectation prevents an equally broken renderer masking a failure.
        assertEquals(UNICODE_PLAIN, FolioText.plain(UNICODE_SOURCE))
        val first = requireNotNull(result.verses.first().translation)
        assertEquals(FIRST_TRANSLATION, first)
        assertArrayEquals(FIRST_TRANSLATION.toByteArray(Charsets.UTF_8), first.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun exactlyEightSourceStylesIncludeGeneralAndSpecialTranslationsRegardlessOfEnglishHeadings() = runBlocking {
        // Deliberately independent of the loader's private allowlist; spelling is part of the contract.
        val styles = listOf("PA_Translation", "PA_Spec_Translation", "PA_Deity_95Book_95Translation",
            "PA_Prema_45Dhvani_Translation", "PA_TKQ_47TLK_Translation", "PA_Sloka_Translation",
            "PA_Purple_95BG_95Translations", "PA_TKQTranslation")
        val nodes = styles.indices.map { index ->
            ContentNode(100 + index * 100, 10, "Generated reference $index", 199 + index * 100, 0, index + 3)
        }
        val expected = styles.indices.map { "Accepted synthetic style $it" }
        val fixture = registerFixture {
            insertHeading(chapter, "LE_Chapter")
            for ((index, node) in nodes.withIndex().reversed()) {
                insertHeading(node, " \tLE_Verse_Number\r\n")
                insertRow(Row(node.record + 1, "TRANSLATION", "PA_Titles", "LE_Verse_Section"))
                insertRow(Row(node.record + 2, expected[index], " \t${styles[index]}\r\n"))
                val excluded = listOf("PA_Titles", "PA_Synonyms", "PA_Purport", "PA_First", "PA_AfterVerse",
                    "PA_Verse", "PA_TranslationHeading", "PA_SpecTranslation", "PA_Translation_Unknown",
                    "pa_translation", "Body", "", null)
                for ((offset, style) in excluded.withIndex()) {
                    insertRow(Row(node.record + 3 + offset, "TRANSLATION: excluded $offset", style, "LE_Translation"))
                }
            }
            // Even this English label is body text when its source paragraph style says translation.
            insertRow(Row(nodes.first().end, "PURPORT", "PA_Spec_Translation"))
        }
        val result = requireNotNull(overview(fixture))
        assertEquals(nodes.mapIndexed { index, node ->
            VersePreview(node, expected[index] + if (index == 0) "\n\nPURPORT" else "")
        }, result.verses)
    }

    @Test
    fun translationsNeverLeakAcrossVerseBoundsChaptersNestedBooksOrIndirectDescendants() = runBlocking {
        val result = requireNotNull(overview())
        assertEquals(expectedPreviews, result.verses)
        assertEquals(SECOND_TRANSLATION, result.verses.single { it.node.record == secondVerse.record }.translation)
        val joined = result.verses.mapNotNull { it.translation }.joinToString("\n")
        for (excluded in listOf("Before first range", "After first range", "Before second range",
            "After second range", "Before combined range", "After combined range", "Chapter prose",
            "Nested book translation", "Indirect descendant translation", "Other chapter translation",
            "Outside chapter")) {
            assertTrue("Leaked $excluded", excluded !in joined)
        }
        assertEquals(listOf(VersePreview(otherVerse, "Other chapter translation")),
            requireNotNull(overview(node = otherChapter)).verses)
        assertEquals(listOf(VersePreview(nestedVerse, "Nested book translation")),
            requireNotNull(overview(node = nestedChapter)).verses)
    }

    @Test
    fun combinedVersesStayOneEntryAndBlankNullOrDuplicateTitlesKeepTheirSourceIds() = runBlocking {
        val result = requireNotNull(overview())
        val combined = result.verses.filter { it.node.record == combinedVerse.record }
        assertEquals(listOf(VersePreview(combinedVerse, COMBINED_TRANSLATION)), combined)
        assertEquals("Verses 3–4", combined.single().node.title)
        val titled = result.verses.filter { it.node.record >= blankVerse.record }
        assertEquals(listOf(blankVerse, nullTitleVerse, duplicateOne, duplicateTwo), titled.map { it.node })
        assertEquals(listOf("", "", "Duplicate reference", "Duplicate reference"), titled.map { it.node.title })
        assertEquals(7, result.verses.map { it.node.record }.toSet().size)
    }

    @Test
    fun absentNullMarkupOnlyAndWhitespaceTranslationsKeepNullPreviewRows() = runBlocking {
        val result = requireNotNull(overview())
        for (node in listOf(blankVerse, nullTitleVerse, duplicateOne, duplicateTwo)) {
            assertEquals(VersePreview(node, null), result.verses.single { it.node.record == node.record })
        }
        assertEquals(7, result.verses.size)
        assertEquals("", FolioText.plain(MARKUP_ONLY))
    }

    @Test
    fun nonChaptersAndProseChaptersWithoutDirectSemanticVersesReturnNull() = runBlocking {
        // This misleadingly titled book has a real semantic verse child, but is not a chapter.
        assertEquals(listOf(nonChapterVerse), repository.children(library.id, nonChapter.record))
        assertNull(overview(node = nonChapter))
        assertTrue(repository.children(library.id, proseChapter.record).isNotEmpty())
        assertNull(overview(node = proseChapter))
        assertTrue(repository.children(library.id, emptyChapter.record).isEmpty())
        assertNull(overview(node = emptyChapter))
        assertNull(overview(node = work))
    }

    @Test
    fun mixedChapterLeavesSeparatelyPassedNonVerseChildrenIntactButOnlyReturnsVersePreviews() = runBlocking {
        val children = repository.children(library.id, chapter.record).toMutableList()
        val original = children.toList()
        val nonVerses = listOf(preface, nestedBook, proseSection, tailSection)
        assertEquals((verseNodes + nonVerses).sortedBy { it.record }, children)
        val result = requireNotNull(overview(children = children))
        assertEquals(expectedPreviews, result.verses)
        assertEquals(original, children)
        assertEquals(nonVerses, children.filter { it.record !in verseNodes.map { verse -> verse.record } })
        assertTrue(result.verses.none { it.node in nonVerses || it.node == indirectVerse || it.node == nestedVerse })
    }

    @Test
    fun reversedOverlappingOutOfParentAndMalformedSuppliedVerseBoundsAreRejected() = runBlocking {
        val boundedChapter = ContentNode(10, 0, "Bounded synthetic chapter", 99, 2, 70)
        val valid = ContentNode(20, 10, "Synthetic verse", 39, 0, 3)
        val malformed = listOf(
            "reversed end" to listOf(valid.copy(end = 19)),
            "end outside chapter" to listOf(valid.copy(end = 100)),
            "start before chapter" to listOf(valid.copy(record = 5, end = 9)),
            "inclusive overlap" to listOf(valid, valid.copy(record = 39, end = 59)),
        )
        for ((label, nodes) in malformed) {
            val fixture = registerFixture {
                insertHeading(boundedChapter, "LE_Chapter")
                nodes.reversed().forEach { insertHeading(it, "LE_Verse_Number") }
            }
            expectRejected(label) { overview(fixture, boundedChapter) }
        }
        val nullEnd = registerFixture {
            insertHeading(boundedChapter, "LE_Chapter")
            insertHeading(valid, "LE_Verse_Number")
            execSQL("UPDATE contents SET next_sibling=NULL WHERE record=?", arrayOf(valid.record))
        }
        expectRejected("null end") { overview(nullEnd, boundedChapter) }

        val children = repository.children(library.id, chapter.record)
        val invalidLists = listOf(
            "reversed source order" to children.reversed(),
            "missing verse" to children.filter { it.record != firstVerse.record },
            "duplicate verse" to (children + firstVerse),
            "wrong parent" to children.map { if (it == firstVerse) it.copy(parent = work.record) else it },
            "supplied end outside chapter" to children.map { if (it == firstVerse) it.copy(end = chapter.end + 1) else it },
        )
        for ((label, invalid) in invalidLists) expectRejected(label) { overview(children = invalid) }
    }

    @Test
    fun repeatedQueriesAndReopensPreserveReadOnlySha256AndOnlyTheIsolatedRegistry() = runBlocking {
        val registry = preferences.all.toMap()
        repeat(3) {
            assertEquals(expectedPreviews, requireNotNull(overview()).verses)
            repository.close()
            assertSourcesUnchanged()
            val directory = File(context.noBackupFilesDir, "libraries")
            assertEquals(setOf(library.fileName), requireNotNull(directory.list()).toSet())
            assertEquals(root.canonicalFile, requireNotNull(context.noBackupFilesDir.parentFile).canonicalFile)
            val reopenedStore = LibraryStore(context)
            assertEquals(listOf(library), reopenedStore.libraries())
            assertEquals(library.id, reopenedStore.activeId)
            assertEquals(root.name, preferences.getString("fixture-owner", null))
            assertEquals(registry, preferences.all)
            repository = IvdRepository(context, reopenedStore)
        }
        assertEquals(expectedPreviews, requireNotNull(overview()).verses)
        assertEquals(registry, preferences.all)
    }

    @Test
    fun oversizedParagraphAggregateAndRecordCountFailRatherThanReturnPartialPreviews() = runBlocking {
        val largeChapter = ContentNode(10, 0, "Large synthetic chapter", 20_000, 1, 60)
        val largeVerse = ContentNode(100, 10, "Large synthetic verse", 19_999, 0, 2)
        val limits = listOf(
            Triple("single paragraph", 1, 200_001),
            Triple("aggregate characters", 11, 190_000),
            Triple("translation rows", 12_001, 1),
        )
        for ((label, count, length) in limits) {
            val fixture = registerFixture {
                insertHeading(largeChapter, "LE_Chapter")
                insertHeading(largeVerse, "LE_Verse_Number")
                val body = "x".repeat(length) // Individual rows remain below CursorWindow limits.
                repeat(count) { insertRow(Row(largeVerse.record + it + 1, body, "PA_Translation")) }
            }
            val error = expectRejected(label) { overview(fixture, largeChapter) }
            assertEquals("Chapter translations exceed the overview limit", error.message)
        }
        assertEquals(expectedPreviews, requireNotNull(overview()).verses)
    }

    private suspend fun overview(
        fixture: LibraryInfo = library,
        node: ContentNode = chapter,
        children: List<ContentNode> = repository.children(fixture.id, node.record),
    ): ChapterOverview? = repository.chapterOverview(fixture.id, node.record, children)

    private suspend fun expectRejected(label: String, action: suspend () -> ChapterOverview?): IllegalStateException {
        var returned: ChapterOverview? = null
        val error = try {
            returned = action()
            null
        } catch (failure: IllegalStateException) { failure }
        assertNull("$label returned a partial overview", returned)
        assertTrue("$label must fail explicitly, not return null", error != null)
        return checkNotNull(error)
    }

    private fun registerFixture(populate: SQLiteDatabase.() -> Unit = { insertStandardFixture() }): LibraryInfo {
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
                db.execSQL("CREATE INDEX contents_parent_record ON contents(parent,record)")
                db.execSQL("CREATE INDEX texts_style_record ON texts(stylename,recid)")
                db.populate()
                db.rawQuery("SELECT count(*) FROM texts", null).use { check(it.moveToFirst()); count = it.getLong(0) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        // Close the writable builder before hashing, registration, or opening the repository.
        check(file.setReadOnly())
        sources += SourceSnapshot(file, file.length(), sha256(file))
        val fixture = LibraryInfo(id, "Generated chapter overview fixture", "sa-Latn", file.name,
            file.length(), count, "Synthetic test data only.")
        store.addLibrary(fixture)
        expectedPreferences = preferences.all.toMap()
        return fixture
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
        private val prefix = "ivd-chapter-overview-test-${root.name}-"
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
            ownedPreferences.forEach {
                check(it.startsWith(prefix))
                check(baseContext.deleteSharedPreferences(it))
            }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    companion object {
        private const val UNICODE_SOURCE = " \t<PS:\"Translation\"><BD+>Generated नमूना हिन्दी বাংলা ā a\u0304 ī i\u0304 ṛ r\u0323 ś ṣ ñ<BD-><CR>line<TB>&amp; <fixture>literal</fixture>  "
        private const val UNICODE_PLAIN = "Generated नमूना हिन्दी বাংলা ā a\u0304 ī i\u0304 ṛ r\u0323 ś ṣ ñ\nline\t&amp; <fixture>literal</fixture>"
        private const val FIRST_TRANSLATION = "$UNICODE_PLAIN\n\nFirst verse middle\n\nStyled under PURPORT\n\nFirst verse inclusive end"
        private const val SECOND_TRANSLATION = "Second verse only\n\nSecond verse inclusive end"
        private const val COMBINED_TRANSLATION = "Combined start\n\nCombined inclusive end"
        private const val MARKUP_ONLY = "<PS:\"Translation\"><BD+><BD-><CR><TB><FC:1><OB:\"OB\";\"synthetic.png\">"
        private val work = ContentNode(1, 0, "Generated work", 1999, 5, 99)
        private val chapter = ContentNode(10, 1, "Generated chapter नमूना", 999, 11, 80)
        private val preface = ContentNode(20, 10, "Verse-looking prose", 39, 0, 7)
        private val firstVerse = ContentNode(100, 10, "Z reference", 139, 2, 7)
        private val translationHeading = ContentNode(110, 100, "TRANSLATION", 119, 0, 4)
        private val purportHeading = ContentNode(120, 100, "PURPORT", 139, 0, 4)
        private val secondVerse = ContentNode(200, 10, "A reference", 239, 0, 99)
        private val combinedVerse = ContentNode(300, 10, "Verses 3–4", 349, 0, 2)
        private val blankVerse = ContentNode(400, 10, "", 419, 0, 10)
        private val nullTitleVerse = ContentNode(440, 10, "", 459, 0, 10)
        private val duplicateOne = ContentNode(480, 10, "Duplicate reference", 499, 0, 10)
        private val duplicateTwo = ContentNode(520, 10, "Duplicate reference", 539, 0, 10)
        private val nestedBook = ContentNode(600, 10, "Nested book", 699, 1, 7)
        private val nestedChapter = ContentNode(620, 600, "Nested chapter", 689, 1, 3)
        private val nestedVerse = ContentNode(630, 620, "Nested verse", 649, 0, 4)
        private val proseSection = ContentNode(720, 10, "Prose section with indirect verse", 799, 1, 7)
        private val indirectVerse = ContentNode(730, 720, "Indirect verse", 759, 0, 7)
        private val tailSection = ContentNode(900, 10, "Closing prose", 949, 0, 7)
        private val otherChapter = ContentNode(1000, 1, "Other chapter", 1299, 1, 3)
        private val otherVerse = ContentNode(1010, 1000, "Other verse", 1039, 0, 4)
        private val proseChapter = ContentNode(1300, 1, "Prose chapter", 1399, 1, 80)
        private val proseChild = ContentNode(1310, 1300, "TRANSLATION", 1319, 0, 7)
        private val nonChapter = ContentNode(1400, 1, "Chapter 2", 1499, 1, 80)
        private val nonChapterVerse = ContentNode(1410, 1400, "Verse under non-chapter", 1439, 0, 7)
        private val emptyChapter = ContentNode(1500, 1, "Empty chapter", 1599, 0, 80)
        private val verseNodes = listOf(firstVerse, secondVerse, combinedVerse, blankVerse, nullTitleVerse, duplicateOne, duplicateTwo)
        private val expectedPreviews = listOf(
            VersePreview(firstVerse, FIRST_TRANSLATION), VersePreview(secondVerse, SECOND_TRANSLATION),
            VersePreview(combinedVerse, COMBINED_TRANSLATION), VersePreview(blankVerse, null),
            VersePreview(nullTitleVerse, null), VersePreview(duplicateOne, null), VersePreview(duplicateTwo, null),
        )

        private fun SQLiteDatabase.insertStandardFixture() {
            val headings = listOf(work to "LE_Book", chapter to " \tLE_Chapter\r\n", preface to "LE_Section",
                translationHeading to "LE_Verse_Section", purportHeading to "LE_Verse_Section",
                nestedBook to "LE_Book", nestedChapter to "LE_Chapter", nestedVerse to "LE_Verse_Number",
                proseSection to "LE_Section", indirectVerse to "LE_Verse_Number", tailSection to "LE_Section",
                otherChapter to "LE_Chapter", otherVerse to "LE_Verse_Number", proseChapter to "LE_Chapter",
                proseChild to "LE_Section", nonChapter to "LE_Book", nonChapterVerse to "LE_Verse_Number",
                emptyChapter to "LE_Chapter") + verseNodes.map { it to " \tLE_Verse_Number\r\n" }
            // Build in reverse order so insertion order is not an accidental expectation.
            headings.reversed().forEach { (node, level) -> insertHeading(node, level) }
            execSQL("UPDATE contents SET title=NULL WHERE record=?", arrayOf(nullTitleVerse.record))
            val rows = listOf(
                Row(21, "Chapter prose", "PA_Translation"),
                Row(99, "Before first range", "PA_Translation"),
                Row(101, UNICODE_SOURCE, " \tPA_Translation\r\n"),
                Row(103, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
                Row(105, "Synthetic synonyms", "PA_Synonyms"),
                Row(117, "First verse middle", "PA_Translation"),
                Row(125, "Styled under PURPORT", "PA_Spec_Translation"),
                Row(127, "Synthetic purport", "PA_Purport"),
                Row(139, "First verse inclusive end", "PA_Translation"),
                Row(140, "After first range", "PA_Translation"),
                Row(199, "Before second range", "PA_Translation"),
                Row(201, "Second verse only", "PA_Translation"),
                Row(239, "Second verse inclusive end", "PA_Translation"),
                Row(240, "After second range", "PA_Translation"),
                Row(299, "Before combined range", "PA_Translation"),
                Row(310, "Combined start", "PA_Translation"),
                Row(349, "Combined inclusive end", "PA_Translation"),
                Row(350, "After combined range", "PA_Translation"),
                Row(401, "No translation: body only"),
                Row(441, null, "PA_Translation"),
                Row(481, MARKUP_ONLY, "PA_Spec_Translation"),
                Row(521, " \t\r\n ", "PA_Translation"),
                Row(631, "Nested book translation", "PA_Translation"),
                Row(731, "Indirect descendant translation", "PA_Translation"),
                Row(1011, "Other chapter translation", "PA_Translation"),
                Row(1311, "Prose chapter translation", "PA_Translation"),
                Row(1411, "Non-chapter translation", "PA_Translation"),
                Row(1600, "Outside chapter", "PA_Translation"),
            )
            rows.reversed().forEach { insertRow(it) }
        }

        private fun SQLiteDatabase.insertHeading(node: ContentNode, level: String) {
            execSQL("INSERT INTO contents (record,parent,title,next_sibling,node_children,level,simpletitle,node_code) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code))
            insertRow(Row(node.record, node.title, "PA_Titles", level))
        }

        private fun SQLiteDatabase.insertRow(row: Row) {
            execSQL("INSERT INTO texts (recid,plain,stylename,levelname) VALUES (?,?,?,?)",
                arrayOf(row.record, row.plain, row.style, row.level))
        }

        private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    }
}