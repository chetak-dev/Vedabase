package org.vedareader.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic, separately owned SQLite files only. No repository, corpus, registry or preferences. */
@RunWith(AndroidJUnit4::class)
class IvdIntroductionTest {
    private lateinit var root: File
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun setUp() {
        // Instrumentation executes with the target app's UID, not the test APK's storage UID.
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        root = File(cache, "ivd-introduction-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun tearDown() {
        try {
            fixtures.forEach { if (it.database.isOpen) it.database.close() }
            fixtures.forEach {
                assertEquals(it.size, it.file.length())
                assertArrayEquals("Fixture must remain byte-for-byte read-only", it.hash, sha256(it.file))
            }
        } finally {
            fixtures.forEach { if (it.database.isOpen) it.database.close() }
            if (::root.isInitialized) {
                check(root.name.startsWith("ivd-introduction-"))
                check(UUID.fromString(root.name.removePrefix("ivd-introduction-")).toString() ==
                    root.name.removePrefix("ivd-introduction-"))
                check(root.deleteRecursively())
            }
        }
    }

    @Test
    fun collectionWithFourFrontMatterChaptersAndBgOneVersesReturnsOriginalIdsWithoutMutatingNodes() = runBlocking {
        val fixture = fixture()
        val supplied = bgChildren.toMutableList()
        val original = supplied.toList()
        val result = fixture.loader.records(book.record, supplied)
        assertEquals(listOf(120, 160, 210, 250), result.toList())
        assertEquals(original, supplied)
        original.indices.forEach { assertSame(original[it], supplied[it]) }
        assertTrue(result.all { id -> supplied.any { it.record == id } })
        assertFalse(chapter.record in result)
        assertTrue(fixture.database.isReadOnly)
        assertTrue(fixture.database.isOpen) // Loader must not close the caller's connection.
    }

    @Test
    fun onlyActualBookCollectionOrCantoParentsAreAcceptedRegardlessOfNumericLevelOrTitle() = runBlocking {
        for (semantic in listOf("LE_Book", " \tLE_Collection\r\n", "LE_Canto")) {
            val fixture = fixture(listOf(Heading(book.copy(title = "Chapter 1"), semantic)) + standard.drop(1))
            assertEquals(semantic, frontIds, fixture.loader.records(book.record, bgChildren))
        }
        for (semantic in listOf("LE_Chapter", "LE_Section", "LE_Verse_Number", "LE_Writings", "LE_Unknown", "le_book", "")) {
            val fixture = fixture(listOf(Heading(book, semantic)) + standard.drop(1))
            assertEquals(semantic, emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
        }
    }

    @Test
    fun noNonFrontMatterChapterReturnsEmptyEvenWhenAnOrdinarySectionHasAChapterTitle() = runBlocking {
        val fixture = fixture(standard.map { if (it.node == chapter) Heading(chapter, "LE_Section") else it })
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
        val withoutChapter = fixture(standard.filter { it.node != chapter && it.node.parent != chapter.record })
        assertEquals(emptySet<Int>(), withoutChapter.loader.records(book.record, bgChildren.dropLast(1)))
    }

    @Test
    fun fewerThanTwoMatchesAndEmptyChildrenDoNotMakeAGroup() = runBlocking {
        val single = listOf(bgChildren.first(), chapter)
        val fixture = fixture(listOf(Heading(book, "LE_Book"), Heading(single.first()), Heading(chapter, "LE_Chapter")))
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, single))
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, emptyList()))
        val empty = fixture(listOf(Heading(book, "LE_Book")))
        assertEquals(emptySet<Int>(), empty.loader.records(book.record, emptyList()))
    }

    @Test
    fun matchingSectionsOrChaptersAfterFirstUnrecognizedChapterNeverJoinTheGroup() = runBlocking {
        val earlyChapter = chapter.copy(record = 200, end = 209, children = 0)
        val source = listOf(bgChildren[0], bgChildren[1], earlyChapter, bgChildren[2], bgChildren[3])
        for (type in listOf("LE_Section", "LE_Chapter")) {
            // No verse rows are needed for an unrecognized semantic chapter to be the boundary.
            val fixture = fixture(listOf(Heading(book, "LE_Collection")) + source.map {
                Heading(it, if (it == earlyChapter) "LE_Chapter" else type)
            })
            assertEquals(type, listOf(120, 160), fixture.loader.records(book.record, source).toList())
        }
    }

    @Test
    fun semanticChapterCalledPrefaceWithDirectVersesIsTheBoundaryNotAFrontMatterMember() = runBlocking {
        val preface = bgChildren[2]
        // Numeric levels and advertised child counts are identical to the eligible front matter.
        val verse = ContentNode(211, preface.record, "Synthetic verse", 219, 0, 7)
        val conflict = fixture(standard.map {
            if (it.node == preface) Heading(it.node, " \tLE_Chapter\r\n") else it
        } + Heading(verse, "LE_Verse_Number"))
        assertEquals(listOf(120, 160), conflict.loader.records(book.record, bgChildren).toList())

        val earlyPreface = bgChildren[1].copy(title = "Bg Preface")
        val source = bgChildren.map { if (it.record == earlyPreface.record) earlyPreface else it }
        val tooEarly = fixture(standard.map {
            if (it.node.record == earlyPreface.record) Heading(earlyPreface, "LE_Chapter") else it
        } + Heading(verse.copy(record = 161, parent = earlyPreface.record, end = 169), "LE_Verse_Number"))
        assertEquals(emptySet<Int>(), tooEarly.loader.records(book.record, source))
    }

    @Test
    fun semanticSectionWithDirectVersesIsExcludedButDoesNotEstablishAChapterBoundary() = runBlocking {
        val preface = bgChildren[2]
        val verse = ContentNode(211, preface.record, "Synthetic verse", 219, 0, 7)
        val fixture = fixture(standard.map {
            if (it.node == preface) Heading(preface, "LE_Section") else it
        } + Heading(verse, "LE_Verse_Number"))
        assertEquals(listOf(120, 160, 250), fixture.loader.records(book.record, bgChildren).toList())
    }

    @Test
    fun nonVerseChildrenAndIndirectOrOtherParentVersesDoNotDisqualifyFrontMatter() = runBlocking {
        val preface = bgChildren[2].copy(children = 1)
        val prose = ContentNode(211, preface.record, "Verse-like title", 219, 1, 7)
        val indirectVerse = ContentNode(212, prose.record, "Synthetic verse", 218, 0, 7)
        val source = bgChildren.map { if (it.record == preface.record) preface else it }
        val fixture = fixture(standard.map {
            if (it.node.record == preface.record) Heading(preface, "LE_Chapter") else it
        } + listOf(Heading(prose, "LE_Section"), Heading(indirectVerse, "LE_Verse_Number")))
        assertEquals(frontIds, fixture.loader.records(book.record, source))
    }

    @Test
    fun recognizedSectionAndChapterHeadingsCanBeMixedBeforeTheActualChapter() = runBlocking {
        val fixture = fixture(standard.map {
            if (it.node == bgChildren[1] || it.node == bgChildren[3]) Heading(it.node, "LE_Section") else it
        })
        assertEquals(frontIds, fixture.loader.records(book.record, bgChildren))
    }

    @Test
    fun recognizedChaptersAloneDoNotSupplyANonFrontMatterChapterBoundary() = runBlocking {
        val source = bgChildren.dropLast(1)
        val fixture = fixture(listOf(Heading(book, "LE_Collection")) + source.map { Heading(it, "LE_Chapter") })
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, source))
    }

    @Test
    fun exactHeadingsAllowCaseUnicodeWhitespaceDashesAndExplicitBookPrefixes() = runBlocking {
        val titles = listOf("\u00a0— SeTTing\u00a0the\tScene —\u00a0", "Bg Dedication", " SB — PREFACE ",
            "bG\u2011Introduction", "–Foreword–", "Acknowledgments", "Acknowledgements", "Author Note",
            "SB — Author’s Note", "Disciple Succession", "The Disciplic Succession", "CC Preface",
            "ISO Introduction", "NOI Foreword", "NOD Dedication", "KB Preface", "TLC Introduction")
        val source = titledSections(titles)
        for (type in listOf("LE_Section", "LE_Chapter")) {
            val fixture = fixture(listOf(Heading(book, "LE_Collection")) +
                source.map { Heading(it, type) } + Heading(chapter, "LE_Chapter"))
            assertEquals(type, source.map { it.record }, fixture.loader.records(book.record, source + chapter).toList())
        }
        assertEquals(titles, source.map { it.title }) // Normalization is classification-only.
    }

    @Test
    fun substringsArbitraryPrefixesAndProseAreNotFrontMatter() = runBlocking {
        val titles = listOf("Introduction to a chapter", "Not a Preface", "A Dedication", "Chapter 1 Introduction",
            "SB Preface revised", "Background Introduction", "SBG Dedication", "BgDedication", "Reintroduction",
            "Preface notes", "The author discusses the introduction", "Foreword and acknowledgments",
            "Setting the Scene in context", "Arbitrary — Dedication", "", "नमूना")
        val source = titledSections(listOf("Dedication") + titles + "Preface")
        val fixture = sectionsFixture(source)
        assertEquals(listOf(source.first().record, source.last().record),
            fixture.loader.records(book.record, source + chapter).toList())
    }

    @Test
    fun unknownAndNonfrontMatterEntriesRemainUnchangedWithNoPhantomIds() = runBlocking {
        val source = titledSections(listOf("Contents", "Preface", "Unrelated नमूना", "Introduction", "Appendix"))
        val supplied = (source + chapter).toMutableList()
        val snapshot = supplied.toList()
        val fixture = sectionsFixture(source)
        val ids = fixture.loader.records(book.record, supplied)
        assertEquals(listOf(source[1].record, source[3].record), ids.toList())
        assertEquals(listOf(source[0], source[2], source[4], chapter), supplied.filterNot { it.record in ids })
        assertEquals(snapshot, supplied)
        assertTrue(ids.all { id -> snapshot.count { it.record == id } == 1 })
    }

    @Test
    fun nestedWorksVersesAndUnknownSemanticTypesAreNotReclassifiedByTheirTitles() = runBlocking {
        val types = listOf("LE_Book", "LE_Collection", "LE_Canto", "LE_Writings", "LE_Verse_Number", "LE_Verse_Section", "LE_Unknown")
        val source = titledSections(listOf("Preface") + types.map { "Introduction" } + "Dedication")
        val headings = source.mapIndexed { index, node ->
            Heading(node, if (index in 1..types.size) types[index - 1] else "LE_Section")
        }
        val fixture = fixture(listOf(Heading(book, "LE_Book")) + headings + Heading(chapter, "LE_Chapter"))
        assertEquals(listOf(source.first().record, source.last().record),
            fixture.loader.records(book.record, source + chapter).toList())
    }

    @Test
    fun indirectChaptersAndOtherParentsNeverEstablishTheBoundary() = runBlocking {
        val section = chapter.copy(children = 1)
        val nestedChapter = chapter.copy(record = 410, parent = section.record, end = 450)
        val otherBook = book.copy(record = 1000, end = 1999)
        val otherChapter = chapter.copy(record = 1100, parent = 1000, end = 1500)
        val source = bgChildren.dropLast(1) + section
        val fixture = fixture(listOf(Heading(book, "LE_Book")) + source.map { Heading(it) } + listOf(
            Heading(nestedChapter, "LE_Chapter"), Heading(otherBook, "LE_Book"), Heading(otherChapter, "LE_Chapter")))
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, source))
    }

    @Test
    fun completeDirectChildListIsRequiredIncludingUnknownEntriesAndTheRealFirstChapter() = runBlocking {
        val unknown = ContentNode(300, book.record, "Unknown", 310, 0, 7)
        val source = bgChildren.dropLast(1) + unknown + chapter
        val fixture = fixture(standard + Heading(unknown))
        assertEquals(frontIds, fixture.loader.records(book.record, source))
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
        assertEquals(emptySet<Int>(), fixture.loader.records(book.record, source.filterNot { it == chapter }))
        val hiddenFirstChapter = fixture(standard.map {
            if (it.node == bgChildren[1]) Heading(it.node.copy(title = "Unrecognized chapter"), "LE_Chapter") else it
        })
        assertEquals(emptySet<Int>(), hiddenFirstChapter.loader.records(book.record, bgChildren.filterNot { it == bgChildren[1] }))
    }

    @Test
    fun staleForgedDuplicateOrReorderedSuppliedMetadataFailsClosed() = runBlocking {
        val fixture = fixture()
        val first = bgChildren.first()
        val replacements = listOf(first.copy(record = 121), first.copy(parent = 0), first.copy(end = 138),
            first.copy(title = "Introduction"), first.copy(children = 1), first.copy(level = 42), first.copy(code = "foreign"))
        val malformed = replacements.map { listOf(it) + bgChildren.drop(1) } + listOf(
            bgChildren.reversed(), bgChildren + first, bgChildren.drop(1),
            bgChildren.dropLast(1) + chapter.copy(record = 9999, end = 9999),
        )
        for (source in malformed) assertEquals(source.toString(), emptySet<Int>(), fixture.loader.records(book.record, source))
        assertEquals(frontIds, fixture.loader.records(book.record, bgChildren))
    }

    @Test
    fun malformedDatabaseRangesFailEvenWhenTheSuppliedNodesMatchThemExactly() = runBlocking {
        val first = bgChildren.first()
        val malformed = listOf(first.copy(record = 99), first.copy(end = first.record - 1),
            first.copy(end = bgChildren[1].record), first.copy(end = book.end + 1), first.copy(parent = first.record))
        for (replacement in malformed) {
            val source = listOf(replacement) + bgChildren.drop(1)
            val fixture = fixture(listOf(Heading(book, "LE_Book")) + source.map {
                Heading(it, if (it == chapter) "LE_Chapter" else "LE_Section")
            })
            assertEquals(replacement.toString(), emptySet<Int>(), fixture.loader.records(book.record, source))
        }
        val invalidParent = fixture(listOf(Heading(book.copy(end = 99), "LE_Book")) + standard.drop(1))
        assertEquals(emptySet<Int>(), invalidParent.loader.records(book.record, bgChildren))
        // Validation must continue after enough candidates and after the first chapter.
        val tail = ContentNode(900, book.record, "Unrelated tail", 1001, 0, 7)
        val invalidTail = fixture(standard + Heading(tail))
        assertEquals(emptySet<Int>(), invalidTail.loader.records(book.record, bgChildren + tail))
    }

    @Test
    fun nullFractionalNegativeAndOverflowingNumericMetadataCannotBecomeValidIds() = runBlocking {
        for (record in listOf(book.record, bgChildren.first().record, chapter.record)) {
            for (column in listOf("record", "parent", "next_sibling", "node_children", "level")) {
                val fixture = fixture {
                    execSQL("UPDATE contents SET $column=NULL WHERE record=?", arrayOf(record))
                }
                assertEquals("Null $column at $record", emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
            }
        }
        for (value in listOf(-1L, Int.MAX_VALUE.toLong() + 1, 4294967416L, 120.5)) {
            val fixture = fixture {
                execSQL("UPDATE contents SET record=? WHERE record=120", arrayOf(value))
                execSQL("UPDATE texts SET recid=? WHERE recid=120", arrayOf(value))
            }
            assertEquals(value.toString(), emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
        }
    }

    @Test
    fun duplicateParentChildOrTextRowsAreAmbiguousAndFailClosed() = runBlocking {
        for (record in listOf(book.record, bgChildren.first().record, chapter.record)) {
            for (table in listOf("contents", "texts")) {
                val fixture = fixture {
                    val key = if (table == "contents") "record" else "recid"
                    execSQL("INSERT INTO $table SELECT * FROM $table WHERE $key=?", arrayOf(record))
                }
                assertEquals("Duplicate $table at $record", emptySet<Int>(), fixture.loader.records(book.record, bgChildren))
            }
        }
    }

    @Test
    fun missingOrBlankSemanticMetadataNeverConcealsAChapter() = runBlocking {
        for (record in listOf(book.record, bgChildren.first().record, chapter.record)) {
            val missing = fixture { execSQL("DELETE FROM texts WHERE recid=?", arrayOf(record)) }
            assertEquals(emptySet<Int>(), missing.loader.records(book.record, bgChildren))
            val blank = fixture { execSQL("UPDATE texts SET levelname=NULL WHERE recid=?", arrayOf(record)) }
            assertEquals(emptySet<Int>(), blank.loader.records(book.record, bgChildren))
        }
        val fixture = fixture()
        for (parent in listOf(-1, 0, 99, 1000)) {
            assertEquals(emptySet<Int>(), fixture.loader.records(parent, bgChildren))
        }
    }

    @Test
    fun separateLibrariesWithReusedIdsNeverShareClassificationOrAcceptStaleChildren() = runBlocking {
        val first = fixture()
        val renamed = bgChildren.map { if (it == chapter) it else it.copy(title = "Unrelated ${it.record}") }
        val second = sectionsFixture(renamed.dropLast(1))
        val differentSemantics = fixture(standard.map { if (it.node == book) Heading(book, "LE_Chapter") else it })
        repeat(3) {
            assertEquals(frontIds, first.loader.records(book.record, bgChildren))
            assertEquals(emptySet<Int>(), second.loader.records(book.record, bgChildren))
            assertEquals(emptySet<Int>(), second.loader.records(book.record, renamed))
            assertEquals(emptySet<Int>(), differentSemantics.loader.records(book.record, bgChildren))
        }
        first.database.close()
        SQLiteDatabase.openDatabase(first.file.absolutePath, null, READ_ONLY).use { reopened ->
            assertTrue(reopened.isReadOnly)
            assertEquals(frontIds, IvdIntroduction(reopened).records(book.record, bgChildren))
        }
    }

    @Test
    fun closedConnectionFailsClosedAndSchemaErrorsExplainLoadFailure() = runBlocking {
        val closed = fixture()
        closed.database.close()
        assertEquals(emptySet<Int>(), closed.loader.records(book.record, bgChildren))
        val malformed = fixture { execSQL("DROP TABLE texts") }
        val failure = try {
            malformed.loader.records(book.record, bgChildren)
            null
        } catch (error: ReadingException) { error }
        assertEquals(ReadingFailure.LOAD, failure?.reason)
        assertTrue(failure?.cause is android.database.sqlite.SQLiteException)
    }

    private data class Heading(val node: ContentNode, val levelName: String = "LE_Section")
    private data class Fixture(val file: File, val database: SQLiteDatabase, val size: Long, val hash: ByteArray) {
        val loader = IvdIntroduction(database)
    }

    private fun sectionsFixture(sections: List<ContentNode>): Fixture = fixture(
        listOf(Heading(book, "LE_Book")) + sections.map { Heading(it) } + Heading(chapter, "LE_Chapter"),
    )

    private fun fixture(headings: List<Heading> = standard, modify: SQLiteDatabase.() -> Unit = {}): Fixture {
        val file = File(root, "${UUID.randomUUID()}.ivd")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            database.beginTransaction()
            try {
                // Nonunique indexes deliberately allow corrupt duplicate-row fixtures, while valid
                // fixtures use the same indexed record/parent/header access paths as the loader.
                database.execSQL("CREATE TABLE contents (record INTEGER,parent INTEGER,title TEXT,next_sibling INTEGER," +
                    "node_children INTEGER,level INTEGER,node_code TEXT)")
                database.execSQL("CREATE INDEX contents_record ON contents(record)")
                database.execSQL("CREATE INDEX contents_parent_record ON contents(parent,record)")
                database.execSQL("CREATE TABLE texts (recid INTEGER,levelname TEXT)")
                database.execSQL("CREATE INDEX texts_recid ON texts(recid)")
                // Insertion order is intentionally NOT source order. No plain text is needed/read.
                for ((node, semantic) in headings.reversed()) {
                    database.execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?)",
                        arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.code))
                    database.execSQL("INSERT INTO texts VALUES (?,?)", arrayOf(node.record, semantic))
                }
                database.modify()
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        check(file.setReadOnly()) // Writable construction is finished before any loader call.
        val size = file.length()
        val hash = sha256(file)
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, READ_ONLY)
        return Fixture(file, database, size, hash).also {
            fixtures += it
            assertTrue(database.isReadOnly)
        }
    }

    companion object {
        private const val READ_ONLY = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        private val book = ContentNode(100, 0, "Generated BG example", 999, 5, 7)
        private val chapter = ContentNode(400, 100, "Bg1: Synthetic chapter", 799, 2, 7)
        private val bgChildren = listOf(
            ContentNode(120, 100, "Setting the Scene", 139, 0, 7),
            ContentNode(160, 100, "Bg Dedication", 179, 0, 7),
            ContentNode(210, 100, "Bg Preface", 229, 0, 7),
            ContentNode(250, 100, "Bg Introduction", 269, 0, 7), chapter,
        )
        private val frontIds = linkedSetOf(120, 160, 210, 250)
        private val standard = listOf(Heading(book, "LE_Collection")) +
            bgChildren.map { Heading(it, "LE_Chapter") } + listOf(
                Heading(ContentNode(410, chapter.record, "Bg 1.1", 419, 0, 7), "LE_Verse_Number"),
                Heading(ContentNode(420, chapter.record, "Bg 1.2", 429, 0, 7), "LE_Verse_Number"),
            )

        private fun titledSections(titles: List<String>) = titles.mapIndexed { index, title ->
            ContentNode(110 + index * 10, book.record, title, 119 + index * 10, 0, 7)
        }

        private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    }
}