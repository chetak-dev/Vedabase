package org.vedareader.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only opt in with instrumentation argument measureImportedBook=true; ordinary runs skip before
 * accessing LibraryStore. Uses existing registrations and OPEN_READONLY connections, never import,
 * prepareStorage, registry edits or DB writes. Reports metadata/counts/timing, never scripture.
 * "Cold" means a fresh repository/outline cache, NOT cold OS/SQLite storage caches: discovery SQL
 * has already run. Timings label bookSection/resource separately, excluding coverage queries and
 * assertions. Also verifies selected BG 1.16–18 shares a complete Chapter 1 resource, not one verse.
 */
@RunWith(AndroidJUnit4::class)
class BookReaderImportedCheck {
    @Test
    fun measureImportedBookAndVerifyCompleteSemanticCoverage() = runBlocking {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments()
            .getString("measureImportedBook") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = LibraryStore(context)
        val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val preferencesBefore = preferences.all.toMap()
        val librariesBefore = store.libraries()
        val sources = mutableListOf<SourceStamp>()
        val repository = IvdRepository(context, store)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "BOOK_CHECK $message\n")
        })
        try {
            // Prefer the active import, but find the code in another registered import if necessary.
            // Missing corpus is a failure after explicit opt-in, not a misleading successful skip.
            val imported = findImportedBook(context, librariesBefore.sortedBy { it.id != store.activeId }, sources)
            openReadOnly(imported.file).use { database ->
                assertTrue("Diagnostic connection must be read-only", database.isReadOnly)
                val root = imported.root
                assertEquals(COLLECTION, root.levelName)
                val headings = database.rawQuery(
                    "$SELECT WHERE c.record BETWEEN ? AND ? ORDER BY c.record",
                    arrayOf(root.record.toString(), root.end.toString()),
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.heading()) } }
                assertTrue("Duplicate contents/text heading records", headings.map { it.record }.distinct().size == headings.size)
                val direct = headings.filter { it.parent == root.record }
                val verseParents = headings.filter { it.levelName == VERSE }.map { it.parent }.toSet()
                // Front matter is also LE_Chapter: actual verse chapters must have semantic verse children.
                val verseChapters = direct.filter { it.levelName == CHAPTER && it.record in verseParents }
                assertTrue("Need at least two semantic verse chapters", verseChapters.size >= 2)
                val firstChapter = verseChapters[0]
                val secondChapter = verseChapters[1]
                val firstVerse = headings.first { it.parent == firstChapter.record && it.levelName == VERSE }
                val secondVerse = headings.first { it.parent == secondChapter.record && it.levelName == VERSE }
                val frontMatter = direct.filter { it.levelName == CHAPTER && it.record < firstChapter.record }
                val appendices = direct.filter { it.levelName == CANTO && it.record > verseChapters.last().record }
                assertTrue("Need semantic front matter", frontMatter.isNotEmpty())
                assertTrue("Need appendix cantos alongside the chapters", appendices.isNotEmpty())

                suspend fun measured(label: String, record: Int): BookReadingSection {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result = repository.bookSection(imported.library.id, record)
                    val milliseconds = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    report("$label ms=$milliseconds entries=${result.position.outline.entries.size} " +
                        "rows=${result.section.paragraphs.size} root=${result.position.outline.book.record}")
                    assertEquals("Same collection root at $label", root.record, result.position.outline.book.record)
                    assertEquals("Same collection parent at $label", root.parent, result.position.outline.book.parent)
                    assertEquals("Same collection end at $label", root.end, result.position.outline.book.end)
                    return result
                }

                report("cold=repository-outline-cache-only; discovery/OS caches are not reset")
                val cold = measured("verse cold", firstVerse.record)
                val outline = cold.position.outline
                val rowCounts = verifyOutline(database, root, headings, outline)
                assertEquals(VERSE, headings.first { it.record == cold.section.node.record }.levelName)
                assertEquals(ReadingUnitKind.VERSE, cold.section.kind)
                assertEquals(firstVerse.record, cold.section.node.record)
                assertEquals(firstVerse.end, cold.section.node.end)
                verifySection(database, cold, firstVerse.record, rowCounts)
                repeat(3) { run ->
                    val warm = measured("verse warm=$run", firstVerse.record)
                    assertSame("Warm reads reuse the complete outline", outline, warm.position.outline)
                    verifySection(database, warm, firstVerse.record, rowCounts)
                }

                verifyChapterResource(database, repository, imported.library.id, outline, firstChapter, headings, rowCounts, ::report)

                val anchors = buildList {
                    add("book opening" to root.record)
                    frontMatter.forEachIndexed { index, heading -> add("frontmatter=$index" to heading.record) }
                    add("chapter2 heading" to secondChapter.record)
                    add("chapter2 verse" to secondVerse.record)
                    direct.filter { it.levelName == CHAPTER && it.record > verseChapters.last().record }
                        .forEachIndexed { index, heading -> add("postscript=$index" to heading.record) }
                    appendices.forEachIndexed { index, heading ->
                        add("appendix=$index heading" to heading.record)
                        add("appendix=$index end" to heading.end)
                    }
                    add("book end" to root.end)
                }
                for ((label, record) in anchors) {
                    val result = measured("$label warm", record)
                    assertSame("Anchor must not switch the outline at $label", outline, result.position.outline)
                    verifySection(database, result, record, rowCounts)
                    if (record == root.record) assertTrue("Opening has a previous entry", result.section.previous == null)
                    if (record == root.end) assertTrue("Ending crosses into another work", result.section.next == null)
                }

                // Re-enter cold from an actual appendix descendant, ruling out order/cache-dependent roots.
                val appendix = appendices.first()
                val appendixDescendant = headings.firstOrNull {
                    it.record > appendix.record && it.record <= appendix.end
                }?.record ?: appendix.end
                repository.close()
                val appendixCold = measured("appendix descendant cold", appendixDescendant)
                assertTrue("Cold appendix must reconstruct identical entry ranges",
                    outline.entries == appendixCold.position.outline.entries)
                verifySection(database, appendixCold, appendixDescendant, rowCounts)
                val appendixWarm = measured("appendix descendant warm", appendixDescendant)
                assertSame(appendixCold.position.outline, appendixWarm.position.outline)
                verifySection(database, appendixWarm, appendixDescendant, rowCounts)

                val counts = countRecords(database, root.record, root.end)
                report("coverage entries=${outline.entries.size} verses=${headings.count { it.levelName == VERSE }} " +
                    "sourceRecids=${counts.first} uniqueSourceRecids=${counts.second} " +
                    "rangeRecids=${rowCounts.sum()} frontmatter=${frontMatter.size} appendixCantos=${appendices.size}")
                assertTrue(database.isReadOnly)
            }
        } finally {
            repository.close()
            // Boolean assertions deliberately avoid dumping preferences/library metadata on failure.
            assertTrue("LibraryStore preferences changed", preferencesBefore == preferences.all)
            assertTrue("Library registrations changed", librariesBefore == store.libraries())
            for (source in sources) {
                assertTrue("Imported database size changed", source.size == source.file.length())
                assertTrue("Imported database modification time changed", source.modified == source.file.lastModified())
            }
        }
    }

    private suspend fun verifyChapterResource(
        database: SQLiteDatabase,
        repository: IvdRepository,
        library: String,
        outline: BookOutline,
        chapter: Heading,
        headings: List<Heading>,
        rowCounts: List<Long>,
        report: (String) -> Unit,
    ) {
        val verses = headings.filter { it.parent == chapter.record && it.levelName == VERSE }
        assertEquals("Gita Chapter 1 must retain its 39 original verse units", 39, verses.size)
        val nodes = verses.associate { it.record to checkNotNull(repository.node(library, it.record)) }
        assertEquals("BG 1.1", nodes.getValue(verses.first().record).displayTitle)
        assertEquals("BG 1.46", nodes.getValue(verses.last().record).displayTitle)
        val selected = verses.singleOrNull { nodes.getValue(it.record).displayTitle == "BG 1.16 - 18" }
            ?: error("Expected the original combined BG 1.16–18 source unit in Chapter 1")

        suspend fun measured(label: String, record: Int): BookResource {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = repository.bookResource(library, record)
            val milliseconds = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            report("resource $label ms=$milliseconds rows=${result.section.paragraphs.size} " +
                "firstEntry=${result.firstEntry} lastEntry=${result.lastEntry} selectedEntry=${result.position.index} " +
                "anchor=${result.anchorRecord} root=${result.position.outline.book.record}")
            assertSame("Resource and legacy reads must share the outline", outline, result.position.outline)
            assertEquals("Requested source record changed", record, result.anchorRecord)
            val entry = outline.entries[result.position.index]
            assertTrue("Position must describe the requested entry, not the resource opening", record in entry.node.record..entry.node.end)
            assertEquals(entry.source, result.source)
            assertEquals(chapter.record, result.section.node.record)
            assertEquals(chapter.end, result.section.node.end)
            assertEquals(ReadingUnitKind.CHAPTER, result.section.kind)
            assertEquals(outline.entries[result.firstEntry].node.record, result.section.node.record)
            assertEquals(outline.entries[result.lastEntry].node.end, result.section.node.end)
            assertTrue(result.position.index in result.firstEntry..result.lastEntry)
            return result
        }

        val result = measured("selected BG1.16 first-plan", selected.record)
        assertEquals(nodes.getValue(selected.record), result.source)
        assertEquals(selected.record, outline.entries[result.position.index].node.record)
        assertTrue("Initial verse must not be changed to the chapter header", result.position.index > result.firstEntry)
        val members = outline.entries.subList(result.firstEntry, result.lastEntry + 1)
        assertEquals("Every Chapter 1 original verse unit must be present once, through BG 1.46",
            verses.map { it.record to it.end },
            members.filter { it.kind == ReadingUnitKind.VERSE }.map { it.source.record to it.source.end })
        assertEquals(rowCounts.subList(result.firstEntry, result.lastEntry + 1).sum(), result.section.paragraphs.size.toLong())
        val expected = database.rawQuery("SELECT recid,plain,stylename,levelname FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid",
            arrayOf(chapter.record.toString(), chapter.end.toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Paragraph(cursor.getInt(0), cursor.getString(1).orEmpty(),
                cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty()))
        } }
        assertEquals("Resource omitted/reordered/duplicated source records", expected.map { it.record }, result.section.paragraphs.map { it.record })
        // Boolean assertions prevent a failing test from printing the imported scripture paragraphs.
        assertTrue("Resource must preserve all source text/styles/levels, including hidden opening labels",
            expected == result.section.paragraphs)
        assertTrue("Source paragraph UTF-8 changed", expected.zip(result.section.paragraphs).all { (source, loaded) ->
            source.text.toByteArray(Charsets.UTF_8).contentEquals(loaded.text.toByteArray(Charsets.UTF_8))
        })
        for ((label, record) in listOf("chapter header" to chapter.record, "BG1.1" to verses.first().record,
            "BG1.46" to verses.last().record, "selected verse end" to selected.end)) {
            val same = measured(label, record)
            assertEquals(result.firstEntry, same.firstEntry)
            assertEquals(result.lastEntry, same.lastEntry)
            assertTrue("Selection-dependent resource text at $label", result.section == same.section)
        }
        result.section.previous?.let { previous ->
            val byOpening = repository.bookResource(library, previous.record)
            val byEnd = repository.bookResource(library, previous.end)
            assertSame(outline, byEnd.position.outline)
            assertEquals(previous, byOpening.section.node)
            assertEquals(previous, byEnd.section.node)
            assertEquals(previous.end, byEnd.anchorRecord)
            assertEquals(byEnd.lastEntry, byEnd.position.index)
            assertEquals(result.section.node, byEnd.section.next)
            assertEquals(result.firstEntry - 1, byEnd.lastEntry)
        }
        result.section.next?.let { next ->
            val neighbor = repository.bookResource(library, next.record)
            assertSame(outline, neighbor.position.outline)
            assertEquals(next, neighbor.section.node)
            assertEquals(result.section.node, neighbor.section.previous)
            assertEquals(result.lastEntry + 1, neighbor.firstEntry)
        }
        report("resource coverage chapter1VerseUnits=${verses.size} first=BG1.1 last=BG1.46 selected=BG1.16-18 " +
            "sourceRows=${expected.size} requestedAnchorRetained=true")
    }

    private fun verifyOutline(
        database: SQLiteDatabase,
        root: Heading,
        headings: List<Heading>,
        outline: BookOutline,
    ): List<Long> {
        val entries = outline.entries
        assertTrue("Empty outline", entries.isNotEmpty())
        assertEquals(root.record, entries.first().node.record)
        assertEquals(root.end, entries.last().node.end)
        assertTrue("Outline must partition every inclusive ID with no gaps/overlaps", entries.zipWithNext().all { (a, b) ->
            a.node.end.toLong() + 1 == b.node.record.toLong()
        })
        val sources = headings.associateBy { it.record }
        for (entry in entries) {
            val source = checkNotNull(sources[entry.source.record]) { "Entry source is not a contents heading" }
            assertEquals(source.parent, entry.source.parent)
            assertEquals(source.end, entry.source.end)
            assertTrue("Entry escaped its real source heading", entry.node.record >= source.record &&
                entry.node.end in entry.node.record..source.end)
            val expectedKind = when (source.levelName) {
                VERSE -> ReadingUnitKind.VERSE
                CHAPTER -> ReadingUnitKind.CHAPTER
                else -> ReadingUnitKind.SECTION
            }
            assertEquals(expectedKind, entry.kind)
            if (entry.kind == ReadingUnitKind.VERSE) {
                assertEquals("Do not split or synthesize a verse start", source.record, entry.node.record)
                assertEquals("Do not split or truncate a verse end", source.end, entry.node.end)
            }
        }
        assertEquals("Every real verse range must appear once, in source order",
            headings.filter { it.levelName == VERSE }.map { it.record to it.end },
            entries.filter { it.kind == ReadingUnitKind.VERSE }.map { it.node.record to it.node.end })

        // Count actual rows, NOT end-start+1: source recids can be sparse. Independent indexed queries
        // over each inclusive outline range must account for exactly the complete source query count.
        val sourceCounts = countRecords(database, root.record, root.end)
        assertTrue("No source records", sourceCounts.first > 0)
        assertEquals("Duplicate source recids", sourceCounts.first, sourceCounts.second)
        val rangeCounts = entries.map { entry ->
            val counts = countRecords(database, entry.node.record, entry.node.end)
            assertEquals("Duplicate recids within a range", counts.first, counts.second)
            counts.first
        }
        assertEquals("All source recids covered exactly once by outline ranges", sourceCounts.first, rangeCounts.sum())
        return rangeCounts
    }

    private fun verifySection(database: SQLiteDatabase, result: BookReadingSection, requested: Int, rowCounts: List<Long>) {
        val node = result.section.node
        assertTrue("Requested position is outside selected entry", requested in node.record..node.end)
        val expected = database.rawQuery("SELECT recid FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid",
            arrayOf(node.record.toString(), node.end.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }
        assertEquals("Loaded row count differs from range query", rowCounts[result.position.index], result.section.paragraphs.size.toLong())
        assertEquals("Loaded section omitted/reordered source recids", expected, result.section.paragraphs.map { it.record })
    }

    private fun countRecords(database: SQLiteDatabase, start: Int, end: Int): Pair<Long, Long> = database.rawQuery(
        "SELECT count(*),count(DISTINCT recid) FROM texts WHERE recid BETWEEN ? AND ?",
        arrayOf(start.toString(), end.toString()),
    ).use { check(it.moveToFirst()); it.getLong(0) to it.getLong(1) }

    private fun findImportedBook(context: Context, libraries: List<LibraryInfo>, sources: MutableList<SourceStamp>): ImportedBook {
        val directory = File(context.noBackupFilesDir, "libraries").canonicalFile
        for (library in libraries) {
            val file = File(directory, library.fileName).canonicalFile
            check(file.parentFile == directory && file.isFile) { "Registered library must be an existing private file" }
            sources += SourceStamp(file, file.length(), file.lastModified())
            val root = openReadOnly(file).use { database ->
                assertTrue(database.isReadOnly)
                database.rawQuery("$SELECT WHERE c.node_code=? ORDER BY c.record LIMIT 2", arrayOf("bgaiiwa")).use { cursor ->
                    if (!cursor.moveToFirst()) null else cursor.heading().also {
                        assertTrue("Ambiguous book code in imported library", !cursor.moveToNext())
                    }
                }
            }
            if (root != null) return ImportedBook(library, file, root)
        }
        error("Opted-in check requires an already imported library containing code bgaiiwa")
    }

    private fun openReadOnly(file: File) = SQLiteDatabase.openDatabase(file.absolutePath, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun Cursor.heading() = Heading(getInt(0), getInt(1), getInt(2), getString(3).orEmpty().trim())
    private data class Heading(val record: Int, val parent: Int, val end: Int, val levelName: String)
    private data class ImportedBook(val library: LibraryInfo, val file: File, val root: Heading)
    private data class SourceStamp(val file: File, val size: Long, val modified: Long)

    companion object {
        private const val COLLECTION = "LE_Collection"
        private const val CANTO = "LE_Canto"
        private const val CHAPTER = "LE_Chapter"
        private const val VERSE = "LE_Verse_Number"
        private const val SELECT = "SELECT c.record,c.parent,c.next_sibling,t.levelname " +
            "FROM contents c LEFT JOIN texts t ON t.recid=c.record"
    }
}