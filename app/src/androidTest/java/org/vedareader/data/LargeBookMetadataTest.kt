package org.vedareader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic, UUID-owned files only by default. The separately gated real check never imports,
 * copies, registers, edits preferences, prepares storage, or opens a writable source connection.
 */
@RunWith(AndroidJUnit4::class)
class LargeBookMetadataTest {
    private lateinit var root: File
    private val snapshots = mutableListOf<Snapshot>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "large-book-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @After
    fun tearDown() {
        try {
            for (snapshot in snapshots) {
                assertEquals(snapshot.size, snapshot.file.length())
                assertArrayEquals("Synthetic source changed", snapshot.hash, sha256(snapshot.file))
            }
        } finally {
            if (::root.isInitialized) {
                check(root.name.startsWith("large-book-"))
                UUID.fromString(root.name.removePrefix("large-book-"))
                check(root.deleteRecursively())
            }
        }
    }

    @Test
    fun moreThanTwentyThousandHeadingsOpenSmallVerseAndPreserveEveryParagraphAcrossChapters() = runBlocking {
        val layout = Layout()
        assertEquals(24_005, layout.headingCount)
        val file = largeFixture(layout)
        openReadOnly(file).use { database ->
            val reader = IvdBookReader(database)
            val verse = reader.read(layout.firstVerse)
            val outline = verse.position.outline
            assertEquals(10, outline.book.record)
            assertEquals(layout.cantoEnd, outline.book.end)
            assertEquals(ReadingUnitKind.VERSE, verse.section.kind)
            assertEquals(layout.firstVerse..layout.firstVerse + 10, verse.section.node.let { it.record..it.end })
            assertEquals(11, verse.section.paragraphs.size)
            assertComplete(database, verse.section)
            assertEquals(layout.verseCounts.sum(), outline.entries.count { it.kind == ReadingUnitKind.VERSE })
            assertEquals(4_005, outline.entries.size) // Subsections are validated, not separate entries.
            assertTrue(outline.entries.zipWithNext().all { (a, b) -> a.node.end + 1 == b.node.record })

            // Cold resource construction must also succeed, rather than relying on a verse cache hit.
            val resourceReader = IvdBookReader(database)
            val selected = resourceReader.readResource(layout.firstVerse + 8)
            assertEquals(layout.firstVerse + 8, selected.anchorRecord)
            assertEquals(verse.section.node, selected.source)
            assertEquals(layout.chapterStarts.first(), selected.section.node.record)
            assertEquals(layout.chapterEnd(0), selected.section.node.end)
            assertEquals(11_001, selected.section.paragraphs.size)
            assertComplete(database, selected.section)
            assertTrue(verse.section.paragraphs == selected.section.paragraphs.filter {
                it.record in layout.firstVerse..layout.firstVerse + 10
            })

            val allRecords = mutableListOf<Int>()
            var resource = resourceReader.readResource(10)
            assertNull(resource.section.previous)
            var resources = 0
            while (true) {
                assertSame(selected.position.outline, resource.position.outline)
                assertComplete(database, resource.section)
                allRecords += resource.section.paragraphs.map { it.record }
                assertTrue(++resources <= 5)
                val next = resource.section.next ?: break
                val previous = resource
                resource = resourceReader.readResource(next.record)
                assertEquals(next, resource.section.node)
                assertEquals(previous.section.node, resource.section.previous)
                assertEquals(previous.lastEntry + 1, resource.firstEntry)
            }
            assertEquals(5, resources) // Canto header plus four complete chapters, never the next canto.
            assertEquals((10..layout.cantoEnd).toList(), allRecords)
            assertEquals(allRecords.size, allRecords.toSet().size)
            assertSame(outline, reader.read(layout.lastVerse + 10).position.outline)
            assertTrue(database.isReadOnly)
        }
    }

    @Test
    fun exactlyOneHundredThousandDistributedHeadingsFitButOneMoreFailsWithoutPartialResults() = runBlocking {
        val layout = Layout(listOf(5_555, 5_555, 5_556))
        assertEquals(100_000, layout.headingCount)
        for (overflow in listOf(false, true)) {
            val file = largeFixture(layout) { shape ->
                if (overflow) {
                    // A valid nested subsection: no sibling, depth, entry or text limit is exceeded.
                    Writer(this).use { writer ->
                        writer.heading(shape.lastVerse + 10, shape.lastVerse + 9, shape.lastVerse + 10, SECTION)
                    }
                }
            }
            openReadOnly(file).use { database ->
                val reader = IvdBookReader(database)
                if (overflow) {
                    expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.read(layout.firstVerse) }
                } else {
                    val result = reader.read(layout.firstVerse)
                    assertEquals(11, result.section.paragraphs.size)
                    assertEquals(16_666, result.position.outline.entries.count { it.kind == ReadingUnitKind.VERSE })
                    assertComplete(database, result.section)
                    assertSame(result.position.outline, reader.read(layout.lastVerse).position.outline)
                }
            }
        }
    }

    @Test
    fun directSiblingBudgetRemainsTwentyThousandEvenWhenTotalMetadataFits() = runBlocking {
        for (children in listOf(20_000, 20_001)) {
            val file = flatFixture(children) {
                // Exercise the paged direct-child scan in usesCantoScopes, not only outline counts.
                execSQL("UPDATE texts SET levelname=? WHERE recid=1", arrayOf(COLLECTION))
            }
            openReadOnly(file).use { database ->
                val reader = IvdBookReader(database)
                if (children == 20_000) {
                    val result = reader.read(2)
                    assertEquals(20_001, result.position.outline.entries.size)
                    assertComplete(database, result.section)
                } else expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.readResource(2) }
            }
        }
    }

    @Test
    fun entryBudgetRemainsFortyThousandIndependentlyOfMetadataAndSiblingBudgets() = runBlocking {
        for (count in listOf(40_000, 40_001)) {
            val file = fixture {
                Writer(this).use { writer ->
                    for (record in 1..count) writer.text(record)
                    writer.heading(1, 0, count, BOOK)
                    writer.heading(2, 1, 20_001, CHAPTER)
                    for (record in 3..20_001) writer.heading(record, 2, record, SECTION)
                    writer.heading(20_002, 1, count, CHAPTER)
                    for (record in 20_003..count) writer.heading(record, 20_002, record, SECTION)
                }
            }
            openReadOnly(file).use { database ->
                val reader = IvdBookReader(database)
                if (count == 40_000) assertEquals(count, reader.read(3).position.outline.entries.size)
                else expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.read(3) }
            }
        }
    }

    @Test
    fun malformedDescendantsAfterTwentyThousandHeadingsAreNotSkippedInsideVerses() = runBlocking {
        val layout = Layout()
        val last = layout.lastVerse
        val mutations = listOf(
            "UPDATE contents SET next_sibling=${last + 4} WHERE record=${last + 1}", // Overlapping siblings.
            "UPDATE contents SET parent=${last + 9} WHERE record=${last + 9}", // Self cycle off selected path.
            "UPDATE contents SET parent=${layout.chapterStarts.last()} WHERE record=${last + 9}", // Bypasses verse.
            "UPDATE texts SET levelname='$VERSE' WHERE recid=${last + 9}", // Semantic verse inside verse.
        )
        for (mutation in mutations) {
            val file = largeFixture(layout) { execSQL(mutation) }
            openReadOnly(file).use { database ->
                expectFailure(ReadingFailure.LOAD) { IvdBookReader(database).readResource(layout.firstVerse) }
            }
        }
    }

    @Test
    fun independentWorksAfterTwentyThousandHeadingsStillRequireAnExplicitSelection() = runBlocking {
        val layout = Layout()
        for (level in listOf(BOOK, COLLECTION)) {
            val file = largeFixture(layout) {
                execSQL("UPDATE texts SET levelname=? WHERE recid=?", arrayOf(level, layout.lastVerse + 9))
            }
            openReadOnly(file).use { database ->
                expectFailure(ReadingFailure.CHOOSE_SECTION) { IvdBookReader(database).read(layout.firstVerse) }
            }
        }
    }

    @Test
    fun escapedChildrenOfLateVerseSubsectionsAreStillCheckedOutsideTheCantoRange() = runBlocking {
        val layout = Layout()
        val file = largeFixture(layout) {
            Writer(this).use { writer ->
                writer.heading(layout.cantoEnd + 3, layout.lastVerse + 9, layout.cantoEnd + 3, SECTION)
            }
        }
        openReadOnly(file).use { database ->
            expectFailure(ReadingFailure.LOAD) { IvdBookReader(database).read(layout.firstVerse) }
        }
    }

    @Test
    fun duplicateContentsOrJoinedTextAtEarlyAndLateKeysetBoundariesCannotDisappear() = runBlocking {
        val layout = Layout()
        // Each <=512-row query consumes 511 rows plus one checked lookahead.
        for (offset in listOf(510, 20_439)) {
            for (table in listOf("contents", "texts")) {
                val file = largeFixture(layout) {
                    val record = rawQuery("SELECT record FROM contents WHERE record BETWEEN 10 AND ? " +
                        "ORDER BY record LIMIT 1 OFFSET $offset", arrayOf(layout.cantoEnd.toString())).use {
                        check(it.moveToFirst()); it.getInt(0)
                    }
                    val key = if (table == "contents") "record" else "recid"
                    execSQL("INSERT INTO $table SELECT * FROM $table WHERE $key=?", arrayOf(record))
                }
                openReadOnly(file).use { database ->
                    expectFailure(ReadingFailure.LOAD) { IvdBookReader(database).read(layout.firstVerse) }
                }
            }
        }
    }

    @Test
    fun encodedMetadataFieldBoundariesAreAcceptedInUtf8AndUtf16WithoutTruncatingTitles() = runBlocking {
        val title = "ā".repeat(4_096) // Exactly 8,192 encoded bytes in each tested database encoding.
        for (encoding in listOf("UTF-8", "UTF-16le", "UTF-16be")) {
            val width = if (encoding == "UTF-8") 1 else 2
            val code = "x".repeat(256 / width)
            val file = fixture(encoding) {
                Writer(this).use { writer -> writer.text(1); writer.heading(1, 0, 1, BOOK) }
                execSQL("UPDATE contents SET title=?,node_code=?", arrayOf(title, code))
                execSQL("UPDATE texts SET levelname=?", arrayOf(BOOK.padEnd(256 / width)))
            }
            openReadOnly(file).use { database ->
                val result = IvdBookReader(database).read(1)
                assertEquals(title, result.section.node.title)
                assertEquals(code, result.section.node.code)
                assertComplete(database, result.section)
            }
        }
    }

    @Test
    fun enormousLateCodesAndLevelsFailBeforeEnteringCursorWindowEvenAfterEmbeddedNulWhileTitlesAreShortened() = runBlocking {
        val layout = Layout()
        val value = "prefix\u0000" + "x".repeat(3_000_000)
        for (field in listOf("node_code", "levelname")) {
            val file = largeFixture(layout) {
                if (field == "levelname") execSQL("UPDATE texts SET levelname=? WHERE recid=?", arrayOf(value, layout.lastVerse + 9))
                else execSQL("UPDATE contents SET $field=? WHERE record=?", arrayOf(value, layout.lastVerse + 9))
            }
            openReadOnly(file).use { database ->
                expectFailure(ReadingFailure.SECTION_TOO_LARGE) { IvdBookReader(database).read(layout.firstVerse) }
            }
        }
        // Glossary-style entries keep their whole definition in the title: shorten it, never refuse the book.
        // (SQLite text functions stop at an embedded NUL, so the title probe is a plain oversized string.)
        val longTitle = "prefix" + "x".repeat(3_000_000)
        val file = largeFixture(layout) { execSQL("UPDATE contents SET title=? WHERE record=?", arrayOf(longTitle, layout.lastVerse)) }
        openReadOnly(file).use { database ->
            val result = IvdBookReader(database).read(layout.firstVerse)
            val shortened = result.position.outline.entries.first { it.source.record == layout.lastVerse }.source.title
            assertEquals(512, shortened.length)
            assertTrue(shortened.startsWith("prefix"))
        }
    }

    @Test
    fun justOverPerFieldBudgetsFailInInitialLookupAndAncestorQueriesTooExceptTitles() = runBlocking {
        val mutations = listOf(
            "UPDATE contents SET node_code=? WHERE record=1" to "x".repeat(257),
            "UPDATE texts SET levelname=? WHERE recid=2" to "x".repeat(257),
        )
        for ((sql, value) in mutations) {
            val file = flatFixture(1) { execSQL(sql, arrayOf(value)) }
            openReadOnly(file).use { database ->
                expectFailure(ReadingFailure.SECTION_TOO_LARGE) { IvdBookReader(database).read(2) }
            }
        }
        for (record in listOf(1, 2)) {
            val file = flatFixture(1) { execSQL("UPDATE contents SET title=? WHERE record=$record", arrayOf("x".repeat(8_193))) }
            openReadOnly(file).use { database ->
                val result = IvdBookReader(database).read(2)
                val node = if (record == 2) result.section.node else result.position.outline.book
                assertEquals("x".repeat(512), node.title)
            }
        }
    }

    @Test
    fun aggregateMetadataBudgetAcceptsEightMillionEncodedBytesButRejectsOneMore() = runBlocking {
        for (overflow in listOf(false, true)) {
            val file = flatFixture(1_000) {
                // 10,007 level bytes + 999 * 7,990 title bytes + 7,983 title bytes = 8,000,000.
                execSQL("UPDATE contents SET title=''")
                execSQL("UPDATE contents SET title=? WHERE record>1", arrayOf("x".repeat(7_990)))
                execSQL("UPDATE contents SET title=? WHERE record=1001", arrayOf("x".repeat(if (overflow) 7_984 else 7_983)))
            }
            openReadOnly(file).use { database ->
                database.rawQuery("SELECT sum(length(CAST(c.title AS BLOB))+length(CAST(c.node_code AS BLOB))+" +
                    "length(CAST(t.levelname AS BLOB))) FROM contents c JOIN texts t ON t.recid=c.record", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(if (overflow) 8_000_001L else 8_000_000L, it.getLong(0))
                }
                val reader = IvdBookReader(database)
                if (overflow) expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.read(2) }
                else {
                    val result = reader.read(2)
                    assertEquals(1_001, result.position.outline.entries.size)
                    assertEquals(7_990, result.section.node.title.length)
                    assertComplete(database, result.section)
                }
            }
        }
    }

    @Test
    fun oversizedSemanticMetadataOnAnUnindexedBodyRowCannotEscapeThePagedSizeScan() = runBlocking {
        val layout = Layout()
        val file = largeFixture(layout) {
            execSQL("UPDATE texts SET levelname=? WHERE recid=?", arrayOf("x".repeat(3_000_000), layout.lastVerse + 10))
        }
        openReadOnly(file).use { database ->
            expectFailure(ReadingFailure.SECTION_TOO_LARGE) { IvdBookReader(database).read(layout.firstVerse) }
        }
    }

    @Test
    fun cancellationAtHeadingPageYieldDoesNotPublishAPartialOutlineAndRetrySucceeds() = runBlocking {
        val layout = Layout()
        openReadOnly(largeFixture(layout)).use { database ->
            val reader = IvdBookReader(database)
            var returned = false
            // Short ancestry/direct lists cannot yield first: this suspends in loadHeadings.
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                reader.readResource(layout.firstVerse)
                returned = true
            }
            assertTrue(pending.isActive)
            pending.cancelAndJoin()
            assertFalse(returned)
            val result = reader.readResource(layout.firstVerse)
            assertEquals(layout.cantoEnd, result.position.outline.book.end)
            assertEquals(4_005, result.position.outline.entries.size)
            assertComplete(database, result.section)
        }
    }

    @Test
    fun clearingCacheDuringPagedHeadingScanPreventsStaleOutlinePublication() = runBlocking {
        val layout = Layout()
        openReadOnly(largeFixture(layout)).use { database ->
            val cache = IvdBookReader.OutlineCache()
            val reader = IvdBookReader(database, cache)
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                try { reader.read(layout.firstVerse); null } catch (error: ReadingException) { error.reason }
            }
            assertTrue(pending.isActive)
            cache.clear()
            assertEquals(ReadingFailure.LOAD, pending.await())
            val result = reader.read(layout.firstVerse)
            assertEquals(4_005, result.position.outline.entries.size)
            assertComplete(database, result.section)
        }
    }

    /** Opt in with checkLargeMadhya=true. Prefer an already staged /data/local/tmp/vedabase.ivd;
     * largeMadhyaPath may name another .ivd directly in that directory. Otherwise use only the
     * target app's active LibraryStore registration under no_backup/libraries. Never stage here.
     */
    @Test
    fun optInRealMadhyaOneOneAndItsWholeChapterRemainReadOnlyAndComplete() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in required", arguments.getString("checkLargeMadhya") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val preferencesBefore = preferences.all.toMap()
        val file = realSource(context, arguments.getString("largeMadhyaPath"))
        val size = file.length()
        val modified = file.lastModified()
        try {
            openReadOnly(file).use { database ->
                assertTrue(database.isReadOnly)
                val record = database.rawQuery("SELECT record,next_sibling FROM contents WHERE simpletitle=? LIMIT 2",
                    arrayOf("madhya 1.1")).use {
                    assertTrue("Madhya 1.1 reference missing", it.moveToFirst())
                    val found = it.getInt(0)
                    assertEquals(227_161, found)
                    assertEquals(227_171, it.getInt(1))
                    assertFalse("Ambiguous Madhya reference", it.moveToNext())
                    found
                }
                database.rawQuery("SELECT count(*) FROM contents WHERE record BETWEEN 227105 AND 290385", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(38_739, it.getInt(0))
                }
                val reader = IvdBookReader(database)
                val verse = reader.read(record)
                assertEquals(227_105, verse.position.outline.book.record)
                assertEquals(290_385, verse.position.outline.book.end)
                assertEquals(11, verse.section.paragraphs.size)
                assertEquals(854L, characters(verse.section))
                assertComplete(database, verse.section)
                // Exercise cold resource construction independently, not only the cached outline.
                val resource = IvdBookReader(database).readResource(record)
                assertEquals(record, resource.anchorRecord)
                assertEquals(verse.section.node, resource.source)
                assertEquals(227_158, resource.section.node.record)
                assertEquals(229_914, resource.section.node.end)
                assertEquals(2_757, resource.section.paragraphs.size)
                assertEquals(284_727L, characters(resource.section))
                assertComplete(database, resource.section)
                assertTrue("Original verse paragraphs changed", verse.section.paragraphs ==
                    resource.section.paragraphs.filter { it.record in 227_161..227_171 })
                assertTrue(database.isReadOnly)
            }
        } finally {
            // Do not print source text, paths, registration data, or preference values on failure.
            assertTrue("Source size changed", size == file.length())
            assertTrue("Source timestamp changed", modified == file.lastModified())
            assertTrue("Library preferences changed", preferencesBefore == preferences.all)
        }
    }

    private fun realSource(context: Context, explicitPath: String?): File {
        val staged = File(explicitPath ?: "/data/local/tmp/vedabase.ivd").canonicalFile
        check(staged.parentFile == File("/data/local/tmp").canonicalFile && staged.extension == "ivd") {
            "Staged source must be an .ivd directly under /data/local/tmp"
        }
        if (explicitPath != null || staged.isFile && staged.canRead()) {
            check(staged.isFile && staged.canRead()) { "Requested staged source is unavailable" }
            return staged
        }
        val store = LibraryStore(context)
        val active = store.libraries().singleOrNull { it.id == store.activeId }
            ?: error("No readable staged corpus or active registered import; stage explicitly before opting in")
        val directory = File(context.noBackupFilesDir, "libraries").canonicalFile
        val source = File(directory, active.fileName).canonicalFile
        check(source.parentFile == directory && source.extension == "ivd" && source.isFile && source.canRead()) {
            "Active registered import is unavailable or outside its library directory"
        }
        return source
    }

    private suspend fun expectFailure(reason: ReadingFailure, action: suspend () -> Any?) {
        var returned = false
        val failure = try { action(); returned = true; null } catch (error: ReadingException) { error.reason }
        assertFalse("No partial result on $reason", returned)
        assertEquals(reason, failure)
    }

    private fun assertComplete(database: SQLiteDatabase, section: ReadingSection) {
        val expected = database.rawQuery("SELECT recid,plain,stylename,levelname FROM texts " +
            "WHERE recid BETWEEN ? AND ? ORDER BY recid", arrayOf(section.node.record.toString(), section.node.end.toString())).use {
            cursor -> buildList {
                while (cursor.moveToNext()) add(Paragraph(cursor.getInt(0), cursor.getString(1).orEmpty(),
                    cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty()))
            }
        }
        assertEquals(expected.map { it.record }, section.paragraphs.map { it.record })
        assertTrue("Source text/styles/levels changed", expected == section.paragraphs)
        assertTrue(section.paragraphs.size <= 12_000)
        assertTrue(characters(section) <= 4_000_000)
    }

    private fun characters(section: ReadingSection) = section.paragraphs.sumOf {
        it.text.codePointCount(0, it.text.length).toLong()
    }

    private data class Layout(val verseCounts: List<Int> = List(4) { 1_000 }) {
        val chapterStarts = verseCounts.runningFold(11) { start, count -> start + 1 + count * 11 }.dropLast(1)
        val cantoEnd = 10 + verseCounts.sumOf { 1 + it * 11 }
        val firstVerse = chapterStarts.first() + 1
        val lastVerse = chapterStarts.last() + 1 + (verseCounts.last() - 1) * 11
        val headingCount = 1 + verseCounts.size + verseCounts.sum() * 6
        fun chapterEnd(index: Int) = chapterStarts[index] + verseCounts[index] * 11
    }

    private fun largeFixture(layout: Layout, mutate: SQLiteDatabase.(Layout) -> Unit = {}): File = fixture {
        val end = layout.cantoEnd + 19
        Writer(this).use { writer ->
            for (record in 1..end) writer.text(record)
            writer.heading(1, 0, end, COLLECTION)
            writer.heading(10, 1, layout.cantoEnd, CANTO)
            writer.heading(layout.cantoEnd + 10, 1, end, CANTO)
            for ((index, start) in layout.chapterStarts.withIndex()) {
                writer.heading(start, 10, layout.chapterEnd(index), CHAPTER)
                repeat(layout.verseCounts[index]) { verseIndex ->
                    val verse = start + 1 + verseIndex * 11
                    writer.heading(verse, start, verse + 10, VERSE)
                    for (offset in listOf(1, 3, 5, 7, 9)) writer.heading(verse + offset, verse, verse + offset + 1, SUBSECTION)
                }
            }
        }
        mutate(layout)
    }

    private fun flatFixture(children: Int, mutate: SQLiteDatabase.() -> Unit = {}): File = fixture {
        Writer(this).use { writer ->
            for (record in 1..children + 1) writer.text(record)
            writer.heading(1, 0, children + 1, BOOK)
            for (record in 2..children + 1) writer.heading(record, 1, record, SECTION)
        }
        mutate()
    }

    private fun fixture(encoding: String = "UTF-8", populate: SQLiteDatabase.() -> Unit): File {
        val file = File(root, "${UUID.randomUUID()}.ivd")
        check(encoding in setOf("UTF-8", "UTF-16le", "UTF-16be"))
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            database.execSQL("PRAGMA encoding='$encoding'")
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            database.beginTransaction()
            try {
                // Non-unique indexes deliberately allow hostile duplicate contents AND text fixtures.
                database.execSQL("CREATE TABLE contents(record INTEGER,parent INTEGER,title TEXT,next_sibling INTEGER," +
                    "node_children INTEGER,level INTEGER,simpletitle TEXT,node_code TEXT)")
                database.execSQL("CREATE INDEX contents_record ON contents(record)")
                database.execSQL("CREATE INDEX contents_parent_record ON contents(parent,record)")
                database.execSQL("CREATE TABLE texts(recid INTEGER,plain TEXT,stylename TEXT,levelname TEXT)")
                database.execSQL("CREATE INDEX texts_record ON texts(recid)")
                database.populate()
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        check(file.setReadOnly())
        snapshots += Snapshot(file, file.length(), sha256(file))
        return file
    }

    private class Writer(database: SQLiteDatabase) : AutoCloseable {
        private val text = database.compileStatement("INSERT INTO texts VALUES (?,?,' Body ā ','Text')")
        private val node = database.compileStatement("INSERT INTO contents VALUES (?,?,?, ?,0,999,'','')")
        private val level = database.compileStatement("UPDATE texts SET levelname=? WHERE recid=?")

        fun text(record: Int) {
            text.bindLong(1, record.toLong())
            text.bindString(2, "Generated $record नमूना ā a\u0304 🕉\u0000tail\r\n")
            text.executeInsert()
        }

        fun heading(record: Int, parent: Int, end: Int, semanticLevel: String) {
            node.bindLong(1, record.toLong())
            node.bindLong(2, parent.toLong())
            node.bindString(3, "Heading $record")
            node.bindLong(4, end.toLong())
            node.executeInsert()
            level.bindString(1, semanticLevel)
            level.bindLong(2, record.toLong())
            level.executeUpdateDelete()
        }

        override fun close() { text.close(); node.close(); level.close() }
    }

    private data class Snapshot(val file: File, val size: Long, val hash: ByteArray)

    companion object {
        private const val BOOK = "LE_Book"
        private const val COLLECTION = "LE_Collection"
        private const val CANTO = "LE_Canto"
        private const val CHAPTER = "LE_Chapter"
        private const val VERSE = "LE_Verse_Number"
        private const val SECTION = "LE_Section"
        private const val SUBSECTION = "LE_Verse_Section"

        private fun openReadOnly(file: File) = SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

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