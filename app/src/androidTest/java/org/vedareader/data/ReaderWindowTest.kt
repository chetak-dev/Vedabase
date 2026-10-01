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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.IvdBookReader.PartitionProfile
import org.vedareader.reader.FolioText
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Independent window-profile checks. Default tests own UUID fixtures only. The opt-in check reads
 * only an existing active import: no staging, registration, preference writes, renderer or device UI.
 */
@RunWith(AndroidJUnit4::class)
class ReaderWindowTest {
    private lateinit var root: File
    private lateinit var isolated: IsolatedContext
    private val snapshots = mutableListOf<Snapshot>()

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "reader-window-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        isolated = IsolatedContext(target, root)
    }

    @After
    fun tearDown() {
        try {
            for ((file, size, hash) in snapshots) {
                assertEquals(size, file.length())
                assertArrayEquals("Synthetic source changed", hash, sha256(file))
            }
        } finally {
            try {
                if (::isolated.isInitialized) isolated.deleteOwnedPreferences()
            } finally {
                if (::root.isInitialized) {
                    UUID.fromString(root.name.removePrefix("reader-window-"))
                    check(root.name.startsWith("reader-window-"))
                    check(root.deleteRecursively())
                }
            }
        }
    }

    @Test
    fun middleFirstSelectionCoversHeadersVersesInterveningProseAndTailsExactlyOnce() = runBlocking {
        val shape = Shape(List(29) { 4 }, gapRows = 1)
        assertEquals(158, shape.chapter.end)
        val front = ContentNode(2, 1, "Front matter", 6, 0, 99)
        val after = ContentNode(160, 1, "Short chapter", 162, 0, 99)
        val lastVerse = ContentNode(161, 160, "Last verse", 162, 0, 99)
        val headings = shape.headings + listOf(front to CHAPTER, after to CHAPTER, lastVerse to VERSE) +
            shape.verses.map { it.copy(record = it.record + 1, parent = it.record) to SUBSECTION }
        val file = fixture(headings, (1..shape.book.end).toList(), body = { "Generated $it नमूना ā\u0000tail" })
        open(file).use { database ->
            val reader = IvdBookReader(database)
            val selected = reader.readWindow(115) // Cold body/Bookmark selection in verse 21, not a heading.
            assertEquals(73..132, selected.section.node.let { it.record..it.end })
            assertContract(database, selected, 115)
            val windows = walk(reader, database, 1)
            assertEquals(listOf(1..1, 2..6, 7..9, 10..72, 73..132, 133..158, 159..159, 160..162, 163..163),
                windows.map { it.section.node.let { node -> node.record..node.end } })
            assertEquals(listOf(12, 12, 5), windows.filter { it.section.node.record in 10..158 }.map(::verseCount))
            val rows = windows.flatMap { it.section.paragraphs }
            assertEquals((1..163).toList(), rows.map { it.record })
            assertEquals(rows.size, rows.map { it.record }.toSet().size)
            assertTrue("Source text/styles/levels changed", sourceParagraphs(database, 1, 163) == rows)
            assertSame(selected.position.outline, windows.first().position.outline)

            // Every opening, subsection, body, gap and tail resolves to the SAME fixed partition.
            for (record in 1..163) {
                val result = reader.readWindow(record)
                val expected = windows.single { record in it.section.node.record..it.section.node.end }
                assertEquals(expected.section, result.section)
                assertContract(database, result, record)
            }
            for (expected in windows) {
                // Fresh caches must reproduce first/last boundaries, independent of selection order.
                for (record in listOf(expected.section.node.record, expected.section.node.end)) {
                    val cold = IvdBookReader(database).readWindow(record)
                    assertNotSame(selected.position.outline, cold.position.outline)
                    assertEquals(expected.section, cold.section)
                    assertEquals(expected.firstEntry, cold.firstEntry)
                    assertEquals(expected.lastEntry, cold.lastEntry)
                }
            }
            var current = windows.last()
            for (index in windows.indices.reversed()) {
                assertEquals(windows[index].section, current.section)
                current.section.previous?.let { previous ->
                    val byStart = reader.readWindow(previous.record)
                    val byEnd = reader.readWindow(previous.end)
                    assertEquals(previous, byStart.section.node)
                    assertEquals(byStart.section, byEnd.section)
                    assertEquals(byEnd.lastEntry, byEnd.position.index)
                    assertEquals(previous.end, byEnd.anchorRecord)
                    assertEquals(current.section.node, byEnd.section.next)
                    current = byEnd // The existing controller can apply enterAtEnd to this full range.
                }
            }
            assertNull(current.section.previous)
        }
    }

    @Test
    fun shortChaptersStayWholeAndTwelveVerseTargetCountsEntriesNotHeadersOrSubsections() = runBlocking {
        for (count in listOf(1, 12, 13, 25)) {
            val shape = Shape(List(count) { 3 })
            val file = fixture(shape.headings + shape.verses.map {
                it.copy(record = it.record + 1, parent = it.record) to SUBSECTION
            }, (1..shape.book.end).toList())
            open(file).use { database ->
                val reader = IvdBookReader(database)
                val legacyEntry = reader.read(shape.verses.last().end)
                val legacy = reader.readResource(shape.chapter.record)
                val windows = walk(reader, database, 1).filter { it.section.node.record in 10..shape.chapter.end }
                assertEquals((0 until count).chunked(12).map { it.size }, windows.map(::verseCount))
                assertEquals(shape.chapter, legacy.section.node)
                assertEquals(count, verseCount(legacy))
                assertEquals(legacy.section.paragraphs, windows.flatMap { it.section.paragraphs })
                assertEquals(legacyEntry, reader.read(shape.verses.last().end))
                assertSame(legacy.position.outline, windows.first().position.outline)
                if (count <= 12) assertEquals(legacy.section, windows.single().section)
                assertEquals(shape.chapter.record, windows.first().section.node.record)
                assertEquals(shape.chapter.end, windows.last().section.node.end)
            }
        }
    }

    @Test
    fun encodedByteTargetHonoursUtf8Utf16AndTextAfterNulAtAndAboveTheBoundary() = runBlocking {
        // SQLite length(TEXT) sees zero, but encoded size is 24,000 UTF-8 / 48,000 UTF-16 bytes.
        val body = "\u0000" + "x".repeat(23_999)
        for (encoding in listOf("UTF-8", "UTF-16le", "UTF-16be")) {
            for (overflow in listOf(false, true)) {
                val shape = Shape(List(4) { 2 }, headerRows = 1, tailRows = 0)
                val bodies = shape.verses.mapIndexed { index, node ->
                    node.end to (body + if (overflow && index == 1) "x" else "")
                }.toMap()
                val file = fixture(shape.headings, (1..shape.book.end).toList(), encoding, body = { bodies[it].orEmpty() })
                open(file).use { database ->
                    val reader = IvdBookReader(database)
                    val windows = walk(reader, database, 1).filter { it.section.node.record in 10..shape.chapter.end }
                    val expected = when {
                        encoding != "UTF-8" -> listOf(1, 1, 1, 1)
                        overflow -> listOf(1, 1, 2)
                        else -> listOf(2, 2)
                    }
                    assertEquals(expected, windows.map(::verseCount))
                    if (!overflow) assertEquals(48_000L, encodedBytes(database, windows.first().section.node))
                    assertEquals(4, verseCount(reader.readResource(shape.verses.last().end)))
                    for (window in windows) {
                        for (row in window.section.paragraphs) assertEquals(bodies[row.record].orEmpty(), row.text)
                        if (encodedBytes(database, window.section.node) > 48_000) {
                            assertEquals(window.firstEntry, window.lastEntry)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rowTargetAcceptsExactly384RowsButSplitsBeforeAWholeEntryWouldExceedIt() = runBlocking {
        for (firstRows in listOf(191, 192)) {
            val shape = Shape(listOf(firstRows, 192, 1), headerRows = 1, tailRows = 0)
            open(fixture(shape)).use { database ->
                val reader = IvdBookReader(database)
                val windows = walk(reader, database, 1).filter { it.section.node.record in 10..shape.chapter.end }
                assertEquals(if (firstRows == 191) listOf(2, 1) else listOf(1, 2), windows.map(::verseCount))
                assertEquals(if (firstRows == 191) 384 else 193, windows.first().section.paragraphs.size)
                assertTrue(windows.all { it.section.paragraphs.size <= 384 })
                assertEquals(shape.chapter, reader.readResource(shape.verses.last().record).section.node)
                assertEquals((10..shape.chapter.end).toList(), windows.flatMap { it.section.paragraphs.map { row -> row.record } })
            }
        }
    }

    @Test
    fun sparseRecordSpansIncludingIntMaxUseActualRowCountsAndKeepExactAnchors() = runBlocking {
        val book = ContentNode(1, 0, "Sparse", Int.MAX_VALUE, 0, 99)
        val chapter = ContentNode(10, 1, "Chapter", Int.MAX_VALUE, 0, 99)
        val verses = listOf(ContentNode(20, 10, "First", 999_999, 0, 99),
            ContentNode(1_000_000, 10, "Last", Int.MAX_VALUE, 0, 99))
        val ids = listOf(1, 10, 20, 1_000_000, Int.MAX_VALUE)
        open(fixture(listOf(book to BOOK, chapter to CHAPTER) + verses.map { it to VERSE }, ids)).use { database ->
            val reader = IvdBookReader(database)
            for (record in listOf(88, 999_999, 1_000_000, Int.MAX_VALUE)) {
                val result = reader.readWindow(record)
                assertEquals(chapter, result.section.node)
                assertContract(database, result, record)
                assertEquals(ids.drop(1), result.section.paragraphs.map { it.record })
                assertNull(result.section.next)
            }
            assertEquals(ids, walk(reader, database, 1).flatMap { it.section.paragraphs.map { row -> row.record } })
        }
    }

    @Test
    fun longVerseLoadsAloneWithoutSlicingItsRowsOrLargeUnicodeParagraph() = runBlocking {
        val shape = Shape(listOf(2, 400, 2))
        val longVerse = shape.verses[1]
        val body = "ā".repeat(1_100_000) + "\u0000नमूना🕉tail" // Beyond CursorWindow, below unchanged hard guards.
        val file = fixture(shape.headings, (1..shape.book.end).toList(), body = { if (it == longVerse.end) body else "x" })
        open(file).use { database ->
            val reader = IvdBookReader(database)
            val result = reader.readWindow(longVerse.end)
            assertEquals(longVerse.record..longVerse.end, result.section.node.let { it.record..it.end })
            assertEquals(result.firstEntry, result.lastEntry)
            assertEquals(longVerse, result.source)
            assertEquals(400, result.section.paragraphs.size)
            assertTrue("Long Unicode paragraph changed", body == result.section.paragraphs.last().text)
            assertContract(database, result, longVerse.end)
            assertEquals(result.section, reader.readWindow(longVerse.record).section)
            assertEquals(result.section.paragraphs, reader.read(longVerse.record).section.paragraphs)
            assertEquals(result.section.node, reader.readWindow(checkNotNull(result.section.next).record).section.previous)
            assertEquals(result.section.node, reader.readWindow(checkNotNull(result.section.previous).end).section.next)
            assertEquals(shape.chapter, reader.readResource(longVerse.record).section.node)
        }
    }

    @Test
    fun proseRetainsExistingOutlineChunksEvenWhenOneChunkExceedsTheSoftByteTarget() = runBlocking {
        val book = ContentNode(1, 0, "Prose", 800, 0, 99)
        open(fixture(listOf(book to BOOK), (1..800).toList(), body = { "x".repeat(200) })).use { database ->
            val reader = IvdBookReader(database)
            val outline = reader.read(700).position.outline
            val windows = walk(reader, database, 1)
            assertEquals(listOf(1..256, 257..512, 513..768, 769..800), windows.map { it.section.node.let { n -> n.record..n.end } })
            for (window in windows) {
                assertSame(outline, window.position.outline)
                assertEquals(window.firstEntry, window.lastEntry)
                assertEquals(outline.entries[window.firstEntry].node, window.section.node)
            }
            assertEquals(51_200L, encodedBytes(database, windows.first().section.node))
            assertEquals((1..800).toList(), windows.flatMap { it.section.paragraphs.map { row -> row.record } })
            assertEquals(book, reader.readResource(700).section.node)
        }
    }

    @Test
    fun hardRowAndCharacterLimitsStillAcceptTheBoundaryAndRejectAnOversizedWholeVerse() = runBlocking {
        for (rows in listOf(12_000, 12_001)) {
            val shape = Shape(listOf(rows, 1))
            open(fixture(shape)).use { database ->
                val reader = IvdBookReader(database)
                val header = reader.readWindow(10)
                val verse = shape.verses.first()
                assertEquals(verse.record, checkNotNull(header.section.next).record)
                if (rows == 12_000) {
                    val result = reader.readWindow(verse.end)
                    assertEquals(rows, result.section.paragraphs.size)
                    assertEquals(result.firstEntry, result.lastEntry)
                    assertContract(database, result, verse.end)
                } else {
                    expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.readWindow(verse.end) }
                    expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.readResource(verse.record) }
                }
                val next = reader.readWindow(shape.verses.last().record)
                assertEquals(header.section.next, next.section.previous)
                assertSame(header.position.outline, next.position.outline)
            }
        }
        for (characters in listOf(4_000_000, 4_000_001)) {
            val shape = Shape(listOf(2, 1))
            val body = "\u0000" + "x".repeat(characters - 1)
            val file = fixture(shape.headings, (1..shape.book.end).toList(), body = {
                if (it == shape.verses.first().end) body else ""
            })
            open(file).use { database ->
                val reader = IvdBookReader(database)
                val header = reader.readWindow(10)
                if (characters == 4_000_000) {
                    val result = reader.readWindow(shape.verses.first().end)
                    assertEquals(result.firstEntry, result.lastEntry)
                    assertTrue("Character-boundary text changed", body == result.section.paragraphs.last().text)
                    assertContract(database, result, result.anchorRecord)
                } else expectFailure(ReadingFailure.SECTION_TOO_LARGE) { reader.readWindow(shape.verses.first().end) }
                assertEquals(header.section.next, reader.readWindow(shape.verses.last().record).section.previous)
            }
        }
    }

    @Test
    fun profilesNeverShareWrongRangesRegardlessOfLoadOrderAndDoNotCacheParagraphs() = runBlocking {
        val shape = Shape(List(30) { 4 })
        open(fixture(shape)).use { database ->
            for (windowFirst in listOf(false, true)) {
                val cache = IvdBookReader.OutlineCache()
                val reader = IvdBookReader(database, cache)
                val record = shape.verses[20].end
                if (windowFirst) reader.readWindow(record) else reader.readResource(record)
                val legacy = reader.readResource(record)
                val window = reader.readWindow(record)
                assertSame(legacy.position.outline, window.position.outline)
                assertEquals(shape.chapter, legacy.section.node)
                assertEquals(30, verseCount(legacy))
                assertEquals(12, verseCount(window))
                assertTrue(legacy.section.node != window.section.node)
                val outline = window.position.outline
                val legacyRanges = cache.resources(database, outline) { error("Legacy cache miss") }
                val windowRanges = cache.resources(database, outline, PartitionProfile.READER_WINDOW) { error("Window cache miss") }
                assertNotSame(legacyRanges, windowRanges)
                assertTrue(windowRanges.size > legacyRanges.size)
                val repeated = IvdBookReader(database, cache).readWindow(record)
                assertSame(window.section.node, repeated.section.node)
                assertNotSame(window.section.paragraphs, repeated.section.paragraphs)
                assertEquals(window, repeated)
                assertEquals(legacy, reader.readResource(record))
                assertSame(windowRanges, cache.resources(database, outline, PartitionProfile.READER_WINDOW) { error("Cache miss") })
            }
        }
    }

    @Test
    fun concurrentMixedProfilesConvergeWithoutCrossPublishing() = runBlocking {
        val shape = Shape(List(80) { 8 })
        open(fixture(shape)).use { database ->
            val reader = IvdBookReader(database)
            val record = shape.verses[20].end
            val results = (0 until 8).map { index -> async {
                if (index % 2 == 0) reader.readResource(record) else reader.readWindow(record)
            } }.awaitAll()
            for ((index, result) in results.withIndex()) {
                assertSame(results.first().position.outline, result.position.outline)
                assertSame(results[index % 2].section.node, result.section.node)
                assertEquals(if (index % 2 == 0) 80 else 12, verseCount(result))
                assertContract(database, result, record, window = index % 2 != 0)
            }
        }
    }

    @Test
    fun bothPartitionsShareTheFourOutlineLruAndAreRebuiltAfterEvictionOrClear() = runBlocking {
        val shape = Shape(List(13) { 2 })
        val offsets = (0..4).map { it * 200 }
        val headings = offsets.flatMap { offset -> shape.headings.map { (node, level) ->
            node.copy(record = node.record + offset, end = node.end + offset,
                parent = if (node.parent == 0) 0 else node.parent + offset) to level
        } }
        open(fixture(headings, offsets.flatMap { offset -> (1..shape.book.end).map { it + offset } })).use { database ->
            val cache = IvdBookReader.OutlineCache()
            val reader = IvdBookReader(database, cache)
            val selected = shape.verses.last().end
            val first = reader.readWindow(selected)
            val firstLegacy = reader.readResource(selected)
            val second = reader.readWindow(selected + 200)
            reader.readResource(selected + 200)
            for (offset in listOf(400, 600)) { reader.readWindow(selected + offset); reader.readResource(selected + offset) }
            assertSame(first.position.outline, reader.readResource(selected).position.outline) // Touch via legacy profile.
            reader.readWindow(selected + 800)
            assertSame(first.position.outline, reader.readWindow(selected).position.outline)
            val rebuilt = reader.readWindow(selected + 200)
            assertNotSame(second.position.outline, rebuilt.position.outline)
            assertEquals(second.section, rebuilt.section)
            assertEquals(shape.chapter.copy(record = 210, end = shape.chapter.end + 200, parent = 201),
                reader.readResource(selected + 200).section.node)
            cache.clear()
            val reopened = reader.readWindow(selected)
            assertNotSame(first.position.outline, reopened.position.outline)
            assertEquals(first.position.outline.entries, reopened.position.outline.entries)
            assertEquals(first.section, reopened.section)
            assertEquals(firstLegacy.section, reader.readResource(selected).section)
        }
    }

    @Test
    fun repositoryOptInKeepsLibraryIdentityLegacyApiAndExactBodyAnchors() = runBlocking {
        val shape = Shape(List(25) { 3 })
        val files = listOf(fixture(shape), fixture(shape.headings, (1..shape.book.end).toList(), body = { "Other $it" }))
        val preferences = isolated.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        val store = LibraryStore(isolated)
        val libraries = files.map { file ->
            LibraryInfo(file.nameWithoutExtension, "Generated", "en", file.name, file.length(), shape.book.end.toLong(), "Synthetic")
                .also(store::addLibrary)
        }
        val before = preferences.all.toMap()
        val repository = IvdRepository(isolated, store)
        try {
            val record = shape.verses[20].end
            val first = repository.readerWindow(libraries[0].id, record)
            val legacy = repository.bookResource(libraries[0].id, record)
            val second = repository.readerWindow(libraries[1].id, record)
            assertEquals(record, first.anchorRecord)
            assertEquals(shape.verses[20], first.source)
            assertEquals(12, verseCount(first))
            assertEquals(25, verseCount(legacy))
            assertSame(first.position.outline, legacy.position.outline)
            assertNotSame(first.position.outline, second.position.outline)
            assertEquals("Other $record", second.section.paragraphs.single { it.record == record }.text)
            assertEquals("x", first.section.paragraphs.single { it.record == record }.text)
            assertEquals(first, repository.readerWindow(libraries[0].id, record))
            assertEquals(shape.verses[20], repository.bookSection(libraries[0].id, record).section.node)
        } finally { repository.close() }
        assertEquals(before, preferences.all)
    }

    @Test
    fun canceledColdOutlineAndWindowMetadataPublishNeitherPartialResultsNorWindowPartitions() = runBlocking {
        val shape = Shape(List(200) { 4 })
        open(fixture(shape)).use { database ->
            val cache = IvdBookReader.OutlineCache()
            val reader = IvdBookReader(database, cache)
            var returned = false
            val cold = launch(start = CoroutineStart.UNDISPATCHED) { reader.readWindow(10); returned = true }
            assertTrue(cold.isActive)
            cold.cancelAndJoin()
            assertFalse(returned)
            var absent = false
            try { cache.get(database, 1) { absent = true; throw CacheMiss() } } catch (_: CacheMiss) { /* Expected. */ }
            assertTrue("Canceled outline was cached", absent)

            val legacy = reader.readResource(10)
            val outline = legacy.position.outline
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { reader.readWindow(500); returned = true }
            assertTrue(pending.isActive) // Warm outline; now suspended in the window size scan.
            pending.cancelAndJoin()
            assertFalse(returned)
            assertPartitionAbsent(cache, database, outline)
            assertEquals(legacy, reader.readResource(10))
            val retry = reader.readWindow(500)
            assertSame(outline, retry.position.outline)
            assertContract(database, retry, 500)
            assertEquals(IvdBookReader(database).readWindow(500).section, retry.section)
        }
    }

    @Test
    fun clearDuringWindowMetadataBuildCannotRepublishOrAttachToAnOldSnapshot() = runBlocking {
        val shape = Shape(List(200) { 4 })
        open(fixture(shape)).use { database ->
            val cache = IvdBookReader.OutlineCache()
            val reader = IvdBookReader(database, cache)
            val old = reader.readResource(10)
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                try { reader.readWindow(500); null } catch (error: ReadingException) { error.reason }
            }
            assertTrue(pending.isActive)
            cache.clear()
            assertEquals(ReadingFailure.LOAD, pending.await())
            val retry = reader.readWindow(500)
            assertNotSame(old.position.outline, retry.position.outline)
            assertEquals(old.position.outline.entries, retry.position.outline.entries)
            assertContract(database, retry, 500)
            assertEquals(old.section, reader.readResource(10).section)
        }
    }

    @Test
    fun cancellationDuringLargeEntryLoadOrBeforeRequestNeverReturnsPartialText() = runBlocking {
        val shape = Shape(listOf(2, 2))
        val body = "x".repeat(800_000)
        open(fixture(shape.headings, (1..shape.book.end).toList(), body = {
            if (it == shape.verses.first().end) body else "x"
        })).use { database ->
            val reader = IvdBookReader(database)
            val header = reader.readWindow(10) // Prime window metadata, not the oversized entry's text.
            var returned = false
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                reader.readWindow(shape.verses.first().end)
                returned = true
            }
            assertTrue(pending.isActive) // Two-row entry: the first yield is largeParagraph byte assembly.
            pending.cancelAndJoin()
            assertFalse(returned)
            val cancelled = Job().also { it.cancel() }
            var propagated = false
            try { withContext(cancelled) { reader.readWindow(10); returned = true } }
            catch (_: CancellationException) { propagated = true }
            assertTrue(propagated)
            assertFalse(returned)
            val retry = reader.readWindow(shape.verses.first().end)
            assertSame(header.position.outline, retry.position.outline)
            assertTrue("Partial paragraph returned", body == retry.section.paragraphs.last().text)
            assertContract(database, retry, retry.anchorRecord)
        }
    }

    @Test
    fun malformedOrOversizedMetadataOutsideSelectedWindowStillFailsClosed() = runBlocking {
        val shape = Shape(List(30) { 4 })
        val late = shape.verses.last().record
        val mutations = listOf<Pair<ReadingFailure, SQLiteDatabase.() -> Unit>>(
            ReadingFailure.LOAD to { execSQL("UPDATE contents SET parent=1 WHERE record=?", arrayOf(late)) },
            ReadingFailure.LOAD to { execSQL("INSERT INTO texts SELECT * FROM texts WHERE recid=?", arrayOf(late + 1)) },
            ReadingFailure.LOAD to { execSQL("UPDATE texts SET levelname=? WHERE recid=?", arrayOf(BOOK, late + 1)) },
            ReadingFailure.SECTION_TOO_LARGE to { execSQL("UPDATE texts SET levelname=? WHERE recid=?", arrayOf("x".repeat(257), late + 1)) },
        )
        for ((reason, mutate) in mutations) {
            open(fixture(shape.headings, (1..shape.book.end).toList(), mutate = mutate)).use { database ->
                expectFailure(reason) { IvdBookReader(database).readWindow(shape.verses.first().record) }
            }
        }
        // An oversized title elsewhere in the work is shortened for display, never a reason to fail.
        open(fixture(shape.headings, (1..shape.book.end).toList(),
            mutate = { execSQL("UPDATE contents SET title=? WHERE record=?", arrayOf("x".repeat(8_193), late)) })).use { database ->
            val window = IvdBookReader(database).readWindow(shape.verses.first().record)
            assertEquals(512, window.position.outline.entries.single { it.source.record == late }.source.title.length)
        }
    }

    /** Explicit opt-in, with an optional staged emulator copy. Never imports or copies a source. */
    @Test
    fun optInActiveMadhyaImportLoadsFewerCompleteEntriesRowsAndHtmlBytesThanLegacyChapter() = runBlocking {
        assumeTrue("Explicit opt-in required", InstrumentationRegistry.getArguments().getString("checkReaderWindow") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val store = LibraryStore(context)
        val active = store.libraries().singleOrNull { it.id == store.activeId }
        val staged = InstrumentationRegistry.getArguments().getString("readerWindowPath")
        assumeTrue("No active registered import or staged source", active != null || staged != null)
        val directory = if (staged == null) File(context.noBackupFilesDir, "libraries").canonicalFile
            else File("/data/local/tmp").canonicalFile
        val file = if (staged == null) File(directory, checkNotNull(active).fileName).canonicalFile else File(staged).canonicalFile
        check(file.parentFile == directory && file.extension == "ivd") { "Active source must remain inside its library directory" }
        assumeTrue("Active import unavailable", file.isFile && file.canRead())
        val size = file.length()
        val modified = file.lastModified()
        try {
            open(file).use { database ->
                assertTrue(database.isReadOnly)
                val record = database.rawQuery("SELECT record FROM contents WHERE simpletitle=? LIMIT 2", arrayOf("madhya 1.1")).use {
                    if (!it.moveToFirst()) null else it.getInt(0).also { _ -> assertFalse("Ambiguous Madhya reference", it.moveToNext()) }
                }
                assumeTrue("Active import has no Madhya 1.1", record != null)
                val selected = checkNotNull(record)
                val legacyStart = System.nanoTime()
                val legacy = IvdBookReader(database).readResource(selected)
                val legacyMs = (System.nanoTime() - legacyStart) / 1_000_000.0
                val windowReader = IvdBookReader(database) // Separate cold outline/partition, same read-only source.
                val windowStart = System.nanoTime()
                val window = windowReader.readWindow(selected)
                val windowMs = (System.nanoTime() - windowStart) / 1_000_000.0
                val warmStart = System.nanoTime()
                val warm = windowReader.readWindow(selected)
                val warmMs = (System.nanoTime() - warmStart) / 1_000_000.0
                assertEquals(284, verseCount(legacy))
                assertTrue(verseCount(window) in 1..12)
                assertTrue("Selected source changed", legacy.source == window.source)
                assertTrue("Outline entries changed", legacy.position.outline.entries == window.position.outline.entries)
                assertTrue("Warm resource changed", window == warm)
                assertContract(database, legacy, selected, window = false)
                assertContract(database, window, selected)
                assertTrue(window.section.node.record >= legacy.section.node.record && window.section.node.end < legacy.section.node.end)
                assertTrue("Selected source rows changed", window.section.paragraphs == legacy.section.paragraphs.filter {
                    it.record in window.section.node.record..window.section.node.end
                })
                val legacyBytes = encodedBytes(database, legacy.section.node)
                val windowBytes = encodedBytes(database, window.section.node)
                val legacyHtmlBytes = htmlBytes(legacy)
                val windowHtmlBytes = htmlBytes(window)
                assertTrue(window.section.paragraphs.size < legacy.section.paragraphs.size)
                assertTrue(windowBytes < legacyBytes)
                assertTrue(windowHtmlBytes < legacyHtmlBytes)
                val report = "ReaderWindow Madhya1: legacy verses=${verseCount(legacy)} rows=${legacy.section.paragraphs.size} " +
                    "encodedBytes=$legacyBytes htmlBytes=$legacyHtmlBytes uncachedMs=$legacyMs; " +
                    "window verses=${verseCount(window)} rows=${window.section.paragraphs.size} " +
                    "encodedBytes=$windowBytes htmlBytes=$windowHtmlBytes uncachedMs=$windowMs warmMs=$warmMs"
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$report\n") })
                // Data/HTML-generation evidence only, not tap-to-paint timing or identical page breaks.
            }
        } finally {
            assertTrue("Source size changed", size == file.length())
            assertTrue("Source timestamp changed", modified == file.lastModified())
            assertTrue("Library preferences changed", before == preferences.all)
        }
    }

    private fun assertContract(database: SQLiteDatabase, resource: BookResource, requested: Int, window: Boolean = true) {
        val entries = resource.position.outline.entries
        val members = entries.subList(resource.firstEntry, resource.lastEntry + 1)
        assertEquals(requested, resource.anchorRecord)
        assertEquals(entries.indexOfFirst { requested in it.node.record..it.node.end }, resource.position.index)
        assertEquals(entries[resource.position.index].source, resource.source)
        assertEquals(members.first().node.record, resource.section.node.record)
        assertEquals(members.last().node.end, resource.section.node.end)
        assertEquals(entries.getOrNull(resource.firstEntry - 1)?.node?.end, resource.section.previous?.end)
        assertEquals(entries.getOrNull(resource.lastEntry + 1)?.node?.record, resource.section.next?.record)
        assertTrue(members.filter { it.kind == ReadingUnitKind.VERSE }.all { it.node == it.source })
        val ids = database.rawQuery("SELECT recid FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid",
            arrayOf(resource.section.node.record.toString(), resource.section.node.end.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
        }
        assertEquals(ids, resource.section.paragraphs.map { it.record })
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(resource.section.paragraphs.size <= 12_000)
        assertTrue(resource.section.paragraphs.sumOf { it.text.codePointCount(0, it.text.length).toLong() } <= 4_000_000)
        val bytes = encodedBytes(database, resource.section.node)
        assertTrue(bytes <= 16_000_000)
        if (window) {
            assertTrue(verseCount(resource) <= 12)
            if (resource.section.paragraphs.size > 384 || bytes > 48_000) assertEquals(resource.firstEntry, resource.lastEntry)
        }
        assertTrue(database.isReadOnly)
    }

    private suspend fun walk(reader: IvdBookReader, database: SQLiteDatabase, start: Int): List<BookResource> {
        val result = mutableListOf<BookResource>()
        var current = reader.readWindow(start)
        assertNull(current.section.previous)
        while (true) {
            assertContract(database, current, current.anchorRecord)
            assertTrue("Window cycle", result.size < current.position.outline.entries.size)
            result.lastOrNull()?.let { previous ->
                assertSame(previous.position.outline, current.position.outline)
                assertEquals(previous.lastEntry + 1, current.firstEntry)
                assertEquals(previous.section.node.end.toLong() + 1, current.section.node.record.toLong())
                assertEquals(previous.section.node, current.section.previous)
            }
            result += current
            val next = current.section.next ?: break
            current = reader.readWindow(next.record)
            assertEquals(next, current.section.node)
        }
        assertEquals(0, result.first().firstEntry)
        assertEquals(current.position.outline.entries.lastIndex, current.lastEntry)
        return result
    }

    private suspend fun assertPartitionAbsent(cache: IvdBookReader.OutlineCache, database: SQLiteDatabase, outline: BookOutline) {
        var absent = false
        try {
            cache.resources(database, outline, PartitionProfile.READER_WINDOW) { absent = true; throw CacheMiss() }
        } catch (_: CacheMiss) { /* Probe must not publish anything itself. */ }
        assertTrue("Canceled window partition was cached", absent)
    }

    private suspend fun expectFailure(reason: ReadingFailure, action: suspend () -> BookResource) {
        var result: BookResource? = null
        val failure = try { result = action(); null } catch (error: ReadingException) { error.reason }
        assertNull("No partial result on $reason", result)
        assertEquals(reason, failure)
    }

    private class CacheMiss : RuntimeException()
    private data class Snapshot(val file: File, val size: Long, val hash: ByteArray)

    private data class Shape(val verseRows: List<Int>, val headerRows: Int = 3, val gapRows: Int = 0, val tailRows: Int = 2) {
        val starts = verseRows.runningFold(10 + headerRows) { start, count -> start + count + gapRows }.dropLast(1)
        val verses = verseRows.mapIndexed { index, count -> ContentNode(starts[index], 10, "Verse $index", starts[index] + count - 1, 0, 99) }
        val chapter = ContentNode(10, 1, "Chapter", verses.last().end + tailRows, 0, 99)
        val book = ContentNode(1, 0, "Work", chapter.end + 5, 0, 99)
        val headings = listOf(book to BOOK, chapter to CHAPTER) + verses.map { it to VERSE }
    }

    private fun fixture(shape: Shape): File = fixture(shape.headings, (1..shape.book.end).toList())

    private fun fixture(
        headings: List<Pair<ContentNode, String>>,
        records: List<Int>,
        encoding: String = "UTF-8",
        body: (Int) -> String = { "x" },
        mutate: SQLiteDatabase.() -> Unit = {},
    ): File {
        val directory = File(root, "libraries").also { check(it.isDirectory || it.mkdirs()) }
        val file = File(directory, "${UUID.randomUUID()}.ivd")
        check(encoding in setOf("UTF-8", "UTF-16le", "UTF-16be"))
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
            database.execSQL("PRAGMA encoding='$encoding'")
            database.rawQuery("PRAGMA encoding", null).use { check(it.moveToFirst()); assertEquals(encoding, it.getString(0)) }
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            database.beginTransaction()
            try {
                database.execSQL("CREATE TABLE contents(record INTEGER,parent INTEGER,title TEXT,next_sibling INTEGER," +
                    "node_children INTEGER,level INTEGER,simpletitle TEXT,node_code TEXT)")
                database.execSQL("CREATE INDEX contents_record ON contents(record)")
                database.execSQL("CREATE INDEX contents_parent_record ON contents(parent,record)")
                database.execSQL("CREATE TABLE texts(recid INTEGER,plain TEXT,stylename TEXT,levelname TEXT)")
                database.execSQL("CREATE INDEX texts_record ON texts(recid)")
                val levels = headings.associate { it.first.record to it.second }
                database.compileStatement("INSERT INTO texts VALUES (?,?,' Body ā ',?)").use { statement ->
                    for (record in records.reversed()) {
                        statement.bindLong(1, record.toLong())
                        statement.bindString(2, body(record))
                        statement.bindString(3, levels[record] ?: "Text")
                        statement.executeInsert()
                    }
                }
                for ((node, _) in headings.reversed()) database.execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code))
                database.mutate()
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
        check(file.setReadOnly())
        snapshots += Snapshot(file, file.length(), sha256(file))
        return file
    }

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix$name"
            ownedPreferences += owned
            return baseContext.getSharedPreferences(owned, mode)
        }
        fun deleteOwnedPreferences() {
            ownedPreferences.forEach { check(it.startsWith(prefix)); check(baseContext.deleteSharedPreferences(it)) }
        }
    }

    companion object {
        private const val BOOK = "LE_Book"
        private const val CHAPTER = "LE_Chapter"
        private const val VERSE = "LE_Verse_Number"
        private const val SUBSECTION = "LE_Verse_Section"

        private fun open(file: File) = SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

        private fun verseCount(resource: BookResource) = resource.position.outline.entries
            .subList(resource.firstEntry, resource.lastEntry + 1).count { it.kind == ReadingUnitKind.VERSE }

        private fun encodedBytes(database: SQLiteDatabase, node: ContentNode): Long = database.rawQuery(
            "SELECT coalesce(sum(length(CAST(plain AS BLOB))),0) FROM texts WHERE recid BETWEEN ? AND ?",
            arrayOf(node.record.toString(), node.end.toString()),
        ).use { check(it.moveToFirst()); it.getLong(0) }

        private fun sourceParagraphs(database: SQLiteDatabase, start: Int, end: Int): List<Paragraph> = database.rawQuery(
            "SELECT recid,plain,stylename,levelname FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid",
            arrayOf(start.toString(), end.toString()),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Paragraph(cursor.getInt(0), cursor.getString(1).orEmpty(),
                cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty()))
        } }

        private fun htmlBytes(resource: BookResource) = FolioText.document(resource.section.paragraphs,
            "en", ReaderPreferences(), bookMode = true).toByteArray(Charsets.UTF_8).size

        private fun sha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                var count = input.read(buffer)
                while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) }
            }
            return digest.digest()
        }
    }
}