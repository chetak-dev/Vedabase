package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Entirely synthetic UUID-isolated fixtures. Never imports or reads a real library or reader prefs. */
@RunWith(AndroidJUnit4::class)
class BookmarkStoreTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var preferences: SharedPreferences
    private lateinit var preferencesBefore: Map<String, *>
    private lateinit var source: File
    private lateinit var notes: File
    private lateinit var sourceBefore: ByteArray
    private lateinit var notesBefore: ByteArray
    private lateinit var store: BookmarkStore
    private lateinit var file: File
    private val library = "synthetic-library-a"
    private val otherLibrary = "synthetic-library-b"

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
        assertSame(context, context.applicationContext)
        preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        val legacy = JSONArray().put(JSONObject().put("id", "legacy-only")
            .put("location", JSONObject().put("library", library).put("title", "Synthetic legacy")
                .put("start", 1).put("end", 20).put("position", 10).put("scroll", 71))
            .put("note", "Synthetic legacy note, never migrate"))
        check(preferences.edit().putString("saved", legacy.toString())
            .putString("libraries", "synthetic registry sentinel")
            .putString("active", library).putString("readingMode", "BOOK")
            .putInt("fontSize", 23).putString("paper", "SEPIA")
            .putString("position:$library", "synthetic position sentinel")
            .putString("searchHistory:$library", "[\"synthetic history\"]")
            .putString("bookNotes", "synthetic note sentinel").commit())
        preferencesBefore = preferences.all.toMap()
        context.rejectPreferenceAccess = true
        sourceBefore = "Synthetic source sentinel, NOT a database. कृष्ण".toByteArray(Charsets.UTF_8)
        notesBefore = "Synthetic independent notes sentinel".toByteArray(Charsets.UTF_8)
        source = File(context.noBackupFilesDir, "libraries/synthetic.ivd")
        check(source.parentFile!!.mkdirs())
        source.writeBytes(sourceBefore)
        notes = File(context.noBackupFilesDir, "book-notes.json").also { it.writeBytes(notesBefore) }
        file = File(context.noBackupFilesDir, "user-state/bookmarks.json")
        store = BookmarkStore(context)
    }

    @After
    fun tearDown() {
        try {
            if (::preferencesBefore.isInitialized) assertEquals(preferencesBefore, preferences.all)
            if (::source.isInitialized) assertArrayEquals(sourceBefore, source.readBytes())
            if (::notes.isInitialized) assertArrayEquals(notesBefore, notes.readBytes())
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
    fun absentLoadIsEmptyAndDoesNotCreateFilesOrReadLegacyPreferences() = runBlocking {
        assertEquals(emptyList<Bookmark>(), store.load())
        assertEquals(emptyList<Bookmark>(), BookmarkStore(context).load())
        assertEquals(emptyList<Bookmark>(), store.remove("missing", library))
        assertFalse(file.parentFile!!.exists())
        assertEquals(preferencesBefore, preferences.all)
    }

    @Test
    fun unicodeExactAnchorsAndAllBoundaryValuesSurviveANewStore() = runBlocking {
        val bookmark = sample().copy(
            id = "i".repeat(128),
            location = ReadingLocation("l".repeat(128), "क".repeat(8192), 1, 20_000_000, 20_000_000, Int.MAX_VALUE),
            bookRecord = 20_000_000,
            bookTitle = "b".repeat(8192),
            excerpt = "e".repeat(500),
            createdAt = Long.MAX_VALUE,
        )
        val unicode = sample(2).copy(location = ReadingLocation(library, "Kṛṣṇa कृष्ण a\u0304 ā 🎵", 1, 2, 2, 137),
            bookTitle = "বাংলা \"title\"\nline", excerpt = "\t\\\u0000", createdAt = 0)
        assertEquals(listOf(bookmark), store.add(bookmark))
        val expected = listOf(bookmark, unicode)
        assertEquals(expected, store.add(unicode))
        assertEquals(expected, BookmarkStore(context).load())
        val json = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(1, json.getInt("version"))
        assertEquals(2, json.getJSONArray("bookmarks").length())
        assertEquals(setOf("bookmarks.json"), file.parentFile!!.list()!!.toSet())
    }

    @Test
    fun duplicateParagraphKeepsOriginalDespiteDifferentIdTitleRangeScrollAndTimestamp() = runBlocking {
        val original = sample()
        store.add(original)
        val bytes = file.readBytes()
        val replacement = original.copy(id = "new-id", location = original.location.copy(title = "other mode title",
            start = original.location.position, end = original.location.position, scrollY = 999),
            bookRecord = original.location.position, bookTitle = "Changed book", excerpt = "Changed", createdAt = Long.MAX_VALUE)
        assertEquals(listOf(original), store.add(replacement))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf(original), BookmarkStore(context).load())
    }

    @Test
    fun recordsAndIdsInDifferentLibrariesDoNotCollideAndRemovalRequiresBothFields() = runBlocking {
        val first = sample()
        val second = first.copy(location = first.location.copy(libraryId = otherLibrary))
        store.add(first)
        val both = store.add(second)
        assertEquals(listOf(first, second), both) // Equal date and ID retain stable order.
        val before = file.readBytes()
        assertEquals(both, store.remove(first.id, "wrong-library"))
        assertEquals(both, store.remove("wrong-id", library))
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(second), store.remove(first.id, library))
        assertEquals(listOf(second), BookmarkStore(context).load())
        assertEquals(listOf(second), store.remove(first.id, library))
        assertEquals(emptyList<Bookmark>(), store.remove(second.id, otherLibrary))
        assertEquals(emptyList<Bookmark>(), BookmarkStore(context).load())
    }

    @Test
    fun aReusedIdCannotDeleteTwoDifferentParagraphsInOneLibrary() = runBlocking {
        val original = sample()
        store.add(original)
        val before = file.readBytes()
        expectIssue(BookmarkIssue.SAVE) { store.add(sample(2).copy(id = original.id)) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(original), store.load())
    }

    @Test
    fun sortingIsDescendingTimestampThenAscendingIdAndStableAcrossRestart() = runBlocking {
        val a = sample(1).copy(id = "a", createdAt = 100)
        val b = sample(2).copy(id = "b", createdAt = 100)
        val latest = sample(3).copy(id = "z", createdAt = Long.MAX_VALUE)
        val oldest = sample(4).copy(createdAt = 0)
        val input = listOf(b, oldest, latest, a)
        // Also test ordering of valid, externally unsorted persisted data without rewriting on load.
        putRaw(document(input))
        val before = file.readBytes()
        val expected = listOf(latest, a, b, oldest)
        assertEquals(expected, store.load())
        assertArrayEquals(before, file.readBytes())
        assertEquals(expected, store.add(a))
        assertEquals(expected, BookmarkStore(context).load())
    }

    @Test
    fun malformedRootsEntriesAndWrongJsonTypesFailClosedWithoutAnyOverwrite() = runBlocking {
        val valid = document(listOf(sample()))
        val bad = mutableListOf("", "{", "[]", "null", "true", "{}", "{\"version\":1}",
            "{\"version\":1,\"bookmarks\":{}}", "{\"version\":1,\"bookmarks\":null}",
            "{\"version\":1,\"bookmarks\":[null]}", "{\"version\":1,\"bookmarks\":[17]}",
            "{\"version\":1,\"bookmarks\":[{}]}", valid + "{}", valid + " garbage",
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":true"),
            valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"version\":1", "\"version\":1e0"),
            valid.replace("\"version\":1", "\"unknown\":0,\"version\":1"),
            valid.replace("\"version\"", "'version'"),
            valid.replace("\"version\"", "version"),
            valid.replace("\"version\":1", "\"version\":/*comment*/1"))
        for (field in listOf("id", "bookTitle", "excerpt", "location", "bookRecord", "createdAt")) {
            bad += rootJson(listOf(entry(sample()).also { it.remove(field) })).toString()
            for (value in listOf(JSONObject.NULL, true, JSONArray(), JSONObject())) {
                bad += rootJson(listOf(entry(sample()).put(field, value))).toString()
            }
        }
        for (field in listOf("libraryId", "title", "start", "end", "position", "scrollY")) {
            bad += rootJson(listOf(entry(sample()).also { it.getJSONObject("location").remove(field) })).toString()
            for (value in listOf(JSONObject.NULL, true, JSONArray(), JSONObject())) {
                bad += rootJson(listOf(entry(sample()).also { it.getJSONObject("location").put(field, value) })).toString()
            }
        }
        bad += rootJson(listOf(entry(sample()).put("id", 17))).toString()
        bad += rootJson(listOf(entry(sample()).put("bookTitle", 17))).toString()
        bad += rootJson(listOf(entry(sample()).put("excerpt", 17))).toString()
        bad += rootJson(listOf(entry(sample()).also { it.getJSONObject("location").put("title", 17) })).toString()
        bad += rootJson(listOf(entry(sample()).also { it.getJSONObject("location").put("libraryId", 17) })).toString()
        for (raw in bad) assertUnreadableAndUnchanged(raw.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun numericStringsFractionsExponentsAndOverflowAreNeverCoerced() = runBlocking {
        val valid = document(listOf(sample()))
        for ((field, original) in listOf("start" to "1", "end" to "20000000", "position" to "1",
            "scrollY" to "0", "bookRecord" to "1", "createdAt" to "100")) {
            for (token in listOf("\"1\"", "1.0", "1.5", "1e0", "1E10", "-1", "+1", "01",
                "9223372036854775808", "18446744073709551616", "9999999999999999999999999999999999")) {
                val raw = valid.replace("\"$field\":$original", "\"$field\":$token")
                assertFalse("Fixture must replace $field", raw == valid)
                assertUnreadableAndUnchanged(raw.toByteArray(Charsets.UTF_8))
            }
            if (field != "createdAt") {
                val raw = valid.replace("\"$field\":$original", "\"$field\":2147483648")
                assertUnreadableAndUnchanged(raw.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun invalidBoundsAndTimestampsFailBothNewArgumentsAndPersistedEntries() = runBlocking {
        val valid = sample()
        val invalid = listOf(
            valid.copy(location = valid.location.copy(start = 0)),
            valid.copy(location = valid.location.copy(start = -1)),
            valid.copy(location = valid.location.copy(start = 20_000_001)),
            valid.copy(location = valid.location.copy(end = 0)),
            valid.copy(location = valid.location.copy(start = 10, end = 9, position = 10)),
            valid.copy(location = valid.location.copy(end = 20_000_001)),
            valid.copy(location = valid.location.copy(position = 0)),
            valid.copy(location = valid.location.copy(start = 2, position = 1)),
            valid.copy(location = valid.location.copy(end = 1, position = 2)),
            valid.copy(location = valid.location.copy(position = Int.MAX_VALUE)),
            valid.copy(location = valid.location.copy(scrollY = -1)),
            valid.copy(bookRecord = 0), valid.copy(bookRecord = -1), valid.copy(bookRecord = 2),
            valid.copy(createdAt = -1), valid.copy(createdAt = Long.MIN_VALUE),
            valid.copy(id = ""), valid.copy(id = " \n\t"),
            valid.copy(location = valid.location.copy(libraryId = "")),
            valid.copy(location = valid.location.copy(libraryId = " \n\t")),
        )
        for (bookmark in invalid) {
            putRaw(document(listOf(valid)))
            val before = file.readBytes()
            expectIssue(BookmarkIssue.SAVE) { store.add(bookmark) }
            assertArrayEquals(before, file.readBytes())
            assertUnreadableAndUnchanged(document(listOf(bookmark)).toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun stringLimitsAndIllFormedUnicodeAreRejectedWithoutTruncation() = runBlocking {
        val valid = sample()
        val tooLong = listOf(valid.copy(id = "i".repeat(129)),
            valid.copy(location = valid.location.copy(libraryId = "l".repeat(129))),
            valid.copy(location = valid.location.copy(title = "t".repeat(8193))),
            valid.copy(bookTitle = "b".repeat(8193)), valid.copy(excerpt = "e".repeat(501)))
        for (bookmark in tooLong) {
            putRaw(document(listOf(valid)))
            val before = file.readBytes()
            expectIssue(BookmarkIssue.LIMIT) { store.add(bookmark) }
            assertArrayEquals(before, file.readBytes())
            assertUnreadableAndUnchanged(document(listOf(bookmark)).toByteArray(Charsets.UTF_8))
        }
        putRaw(document(listOf(valid)))
        val before = file.readBytes()
        for (badText in listOf("\uD800", "\uDC00", "\uD800x")) {
            expectIssue(BookmarkIssue.SAVE) { store.add(valid.copy(excerpt = badText)) }
            assertArrayEquals(before, file.readBytes())
        }
        assertUnreadableAndUnchanged(document(listOf(valid)).replace("\"excerpt\":\"Synthetic excerpt\"",
            "\"excerpt\":\"\\ud800\"").toByteArray(Charsets.UTF_8))
        assertUnreadableAndUnchanged(byteArrayOf(0xC3.toByte(), 0x28))
    }

    @Test
    fun invalidRemovalArgumentsNeverChangeTheExistingList() = runBlocking {
        store.add(sample())
        val before = file.readBytes()
        expectIssue(BookmarkIssue.SAVE) { store.remove("", library) }
        expectIssue(BookmarkIssue.SAVE) { store.remove(sample().id, " ") }
        expectIssue(BookmarkIssue.LIMIT) { store.remove("i".repeat(129), library) }
        expectIssue(BookmarkIssue.LIMIT) { store.remove(sample().id, "l".repeat(129)) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun duplicatePersistedParagraphOrIdIsCorruptionNotSilentDataLoss() = runBlocking {
        val first = sample()
        assertUnreadableAndUnchanged(document(listOf(first, first.copy(id = "another-id"))).toByteArray(Charsets.UTF_8))
        assertUnreadableAndUnchanged(document(listOf(first, sample(2).copy(id = first.id))).toByteArray(Charsets.UTF_8))
    }

    @Test
    fun twoThousandEntriesAreAllowedButNewOrStoredOverflowIsNot() = runBlocking {
        val entries = (1..2000).map { sample(it) }
        putRaw(document(entries))
        assertEquals(2000, store.load().size)
        val before = file.readBytes()
        assertEquals(2000, store.add(entries.first().copy(id = "dedup-at-capacity")).size)
        expectIssue(BookmarkIssue.LIMIT) { store.add(sample(2001)) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(1999, store.remove(entries.first().id, library).size)
        assertEquals(2000, store.add(sample(2001)).size)
        assertEquals(2000, BookmarkStore(context).load().size)
        assertUnreadableAndUnchanged(document(entries + sample(2001)).toByteArray(Charsets.UTF_8))
    }

    @Test
    fun fileByteLimitIsInclusiveAndOversizedReadsNeverPermitMutation() = runBlocking {
        val compact = document(emptyList()).toByteArray(Charsets.UTF_8)
        val exactly = compact + ByteArray(MAX_BYTES - compact.size) { ' '.code.toByte() }
        putBytes(exactly)
        assertEquals(emptyList<Bookmark>(), store.load())
        assertArrayEquals(exactly, file.readBytes())
        assertUnreadableAndUnchanged(exactly + byteArrayOf(' '.code.toByte()))
    }

    @Test
    fun serializedLimitCountsUtf8BytesNotCharactersAndFailedAddKeepsAllOldEntries() = runBlocking {
        val entries = (1..82).map { sample(it).copy(location = sample(it).location.copy(title = "क".repeat(8192)),
            bookTitle = "क".repeat(8192), excerpt = "क".repeat(500)) }
        val extra = sample(83).copy(location = sample(83).location.copy(title = "क".repeat(8192)),
            bookTitle = "क".repeat(8192), excerpt = "क".repeat(500))
        val before = document(entries).toByteArray(Charsets.UTF_8)
        val after = document(entries + extra).toByteArray(Charsets.UTF_8)
        assertTrue(before.size <= MAX_BYTES)
        assertTrue(after.size > MAX_BYTES)
        assertTrue(document(entries + extra).length < MAX_BYTES)
        putBytes(before)
        assertEquals(82, store.load().size)
        expectIssue(BookmarkIssue.LIMIT) { store.add(extra) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(82, BookmarkStore(context).load().size)
        assertFalse(File(file.path + ".rollback").exists())
    }

    @Test
    fun concurrentInstancesCannotLoseAddsDeduplicationOrIndependentRemovals() = runBlocking {
        coroutineScope {
            (1..48).flatMap { index -> List(2) { async(Dispatchers.Default) { BookmarkStore(context).add(sample(index)) } } }
                .awaitAll()
        }
        assertEquals((1..48).toSet(), store.load().map { it.location.position }.toSet())
        assertEquals(48, store.load().size)
        coroutineScope {
            val removals = (1..48 step 2).map { index -> async(Dispatchers.Default) {
                BookmarkStore(context).remove(sample(index).id, library)
            } }
            val additions = (49..72).map { index -> async(Dispatchers.Default) { BookmarkStore(context).add(sample(index)) } }
            (removals + additions).awaitAll()
        }
        val expected = (2..48 step 2).toSet() + (49..72).toSet()
        assertEquals(expected, BookmarkStore(context).load().map { it.location.position }.toSet())
        assertEquals(expected.size, store.load().size)
    }

    @Test
    fun nativeAtomicBackupRecoveryWorksWithCorruptOrAbsentBase() = runBlocking {
        val bookmark = sample()
        val valid = document(listOf(bookmark)).toByteArray(Charsets.UTF_8)
        putRaw("corrupt main")
        val backup = File(file.path + ".bak")
        backup.writeBytes(valid)
        assertEquals(listOf(bookmark), store.load())
        assertArrayEquals(valid, file.readBytes())
        assertFalse(backup.exists())
        assertTrue(file.delete())
        backup.writeBytes(valid)
        assertEquals(listOf(bookmark), BookmarkStore(context).load())
        assertArrayEquals(valid, file.readBytes())
    }

    @Test
    fun corruptAtomicBackupIsNotReplacedByMutation() = runBlocking {
        putRaw(document(listOf(sample())))
        val corrupt = "corrupt backup".toByteArray(Charsets.UTF_8)
        File(file.path + ".bak").writeBytes(corrupt)
        expectIssue(BookmarkIssue.LOAD) { store.add(sample(2)) }
        assertArrayEquals(corrupt, file.readBytes()) // Default AtomicFile recovery promotes .bak.
        expectIssue(BookmarkIssue.LOAD) { store.remove(sample().id, library) }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test
    fun directoryInsteadOfTheMainFileIsALoadErrorAndIsNeverDeleted() = runBlocking {
        assertTrue(file.mkdirs())
        val sentinel = File(file, "do-not-delete").also { it.writeText("synthetic") }
        expectIssue(BookmarkIssue.LOAD) { store.load() }
        expectIssue(BookmarkIssue.LOAD) { store.add(sample()) }
        expectIssue(BookmarkIssue.LOAD) { store.remove(sample().id, library) }
        assertEquals("synthetic", sentinel.readText())
        assertFalse(File(file.path + ".rollback").exists())
    }

    @Test
    fun unreadableStorageIsNeverMistakenForAbsenceAndNeverStartsAWrite() = runBlocking {
        val writes = AtomicInteger()
        val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun openRead(): FileInputStream {
                if (path == file) {
                    throw FileNotFoundException("Synthetic access denial").apply {
                        initCause(ErrnoException("open", OsConstants.EACCES))
                    }
                }
                return super.openRead()
            }
            override fun startWrite(): FileOutputStream {
                writes.incrementAndGet()
                return super.startWrite()
            }
        } }
        // Even when exists() would say false, EACCES is LOAD, not an empty first-use store.
        expectIssue(BookmarkIssue.LOAD) { failing.load() }
        expectIssue(BookmarkIssue.LOAD) { failing.add(sample()) }
        expectIssue(BookmarkIssue.LOAD) { failing.remove(sample().id, library) }
        assertFalse(file.exists())
        putRaw(document(listOf(sample())))
        val before = file.readBytes()
        expectIssue(BookmarkIssue.LOAD) { failing.load() }
        expectIssue(BookmarkIssue.LOAD) { failing.add(sample(2)) }
        expectIssue(BookmarkIssue.LOAD) { failing.remove(sample().id, library) }
        assertEquals(0, writes.get())
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".rollback").exists())
    }

    @Test
    fun startWriteFailureFromAFileBlockingTheParentReturnsSaveAndPreservesOldBytes() = runBlocking {
        store.add(sample())
        val before = file.readBytes()
        // A real AtomicFile.startWrite failure without chmod, root assumptions, or real app paths.
        val blocker = File(root, "synthetic-parent-blocker").also { it.writeText("not a directory") }
        val failOnce = AtomicBoolean(true)
        val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun startWrite(): FileOutputStream {
                if (path == file && failOnce.getAndSet(false)) {
                    return AtomicFile(File(blocker, "cannot-create.json")).startWrite()
                }
                return super.startWrite()
            }
        } }
        expectIssue(BookmarkIssue.SAVE) { failing.add(sample(2)) }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(sample()), BookmarkStore(context).load())
        assertEquals("not a directory", blocker.readText())
        assertEquals(setOf("bookmarks.json"), file.parentFile!!.list()!!.toSet())
    }

    @Test
    fun failureAfterPartialStartWriteCallsFailWriteAndRestoresOriginal() = runBlocking {
        store.add(sample())
        val before = file.readBytes()
        val failOnce = AtomicBoolean(true)
        val failCalls = AtomicInteger()
        val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun startWrite(): FileOutputStream {
                if (path == file && failOnce.getAndSet(false)) {
                    super.startWrite().use { it.write("partial synthetic write".toByteArray(Charsets.UTF_8)) }
                    throw IOException("Synthetic partial-write failure")
                }
                return super.startWrite()
            }
            override fun failWrite(stream: FileOutputStream?) {
                if (path == file) failCalls.incrementAndGet()
                super.failWrite(stream)
            }
        } }
        expectIssue(BookmarkIssue.SAVE) { failing.remove(sample().id, library) }
        assertTrue(failCalls.get() > 0)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(sample()), BookmarkStore(context).load())
    }

    @Test
    fun silentFinishWriteFailureAndWrongPersistedBytesAreNotReportedAsSuccess() = runBlocking {
        for (corruptAfterFinish in listOf(false, true)) {
            putRaw(document(listOf(sample())))
            val before = file.readBytes()
            val failOnce = AtomicBoolean(true)
            val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
                override fun finishWrite(stream: FileOutputStream?) {
                    if (path == file && failOnce.getAndSet(false)) {
                        if (corruptAfterFinish) {
                            super.finishWrite(stream)
                            path.writeText("synthetic silently damaged commit")
                        } else stream?.close() // Simulates a silently unsuccessful rename.
                    } else super.finishWrite(stream)
                }
            } }
            expectIssue(BookmarkIssue.SAVE) { failing.add(sample(2)) }
            assertArrayEquals(before, file.readBytes())
            assertEquals(listOf(sample()), BookmarkStore(context).load())
            assertEquals(setOf("bookmarks.json"), file.parentFile!!.list()!!.toSet())
        }
    }

    @Test
    fun failedFirstCommitRestoresAbsenceInsteadOfCreatingAnEmptyOrPartialFile() = runBlocking {
        val failOnce = AtomicBoolean(true)
        val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun finishWrite(stream: FileOutputStream?) {
                super.finishWrite(stream)
                if (path == file && failOnce.getAndSet(false)) path.writeText("damaged first commit")
            }
        } }
        expectIssue(BookmarkIssue.SAVE) { failing.add(sample()) }
        assertFalse(file.exists())
        assertEquals(emptyList<Bookmark>(), BookmarkStore(context).load())
        assertTrue(file.parentFile!!.list()!!.isEmpty())
    }

    @Test
    fun ifImmediateRollbackAlsoFailsTheVerifiedOldImageSurvivesForNextLoad() = runBlocking {
        store.add(sample())
        val before = file.readBytes()
        val finished = AtomicBoolean(false)
        val failing = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun startWrite(): FileOutputStream {
                if (path == file && finished.get()) throw IOException("Synthetic temporary rollback failure")
                return super.startWrite()
            }
            override fun finishWrite(stream: FileOutputStream?) {
                super.finishWrite(stream)
                if (path == file) {
                    finished.set(true)
                    path.writeText("synthetic bad commit")
                }
            }
        } }
        expectIssue(BookmarkIssue.SAVE) { failing.add(sample(2)) }
        assertArrayEquals(before, File(file.path + ".rollback").readBytes())
        assertEquals(listOf(sample()), BookmarkStore(context).load())
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".rollback").exists())
    }

    @Test
    fun interruptedTransactionsRecoverOldContentOrPriorAbsenceAndRejectCorruptJournal() = runBlocking {
        val original = document(listOf(sample())).toByteArray(Charsets.UTF_8)
        val journal = File(file.path + ".rollback")
        putRaw(document(listOf(sample(2))))
        journal.writeBytes(original)
        assertEquals(listOf(sample()), BookmarkStore(context).load())
        assertArrayEquals(original, file.readBytes())
        assertFalse(journal.exists())
        putRaw(document(listOf(sample(2))))
        journal.writeBytes(byteArrayOf())
        assertEquals(emptyList<Bookmark>(), BookmarkStore(context).load())
        assertFalse(file.exists())
        assertFalse(journal.exists())
        putBytes(original)
        journal.writeText("corrupt rollback metadata")
        expectIssue(BookmarkIssue.LOAD) { store.load() }
        expectIssue(BookmarkIssue.LOAD) { store.add(sample(2)) }
        expectIssue(BookmarkIssue.LOAD) { store.remove(sample().id, library) }
        assertArrayEquals(original, file.readBytes())
        assertEquals("corrupt rollback metadata", journal.readText())
    }

    @Test
    fun cancellationBeforeCommitNeverStartsAWriteAndAllIoRunsOffTheMainThread() = runBlocking {
        store.add(sample())
        val before = file.readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val cancellation = AtomicBoolean(false)
        val gated = BookmarkStore(context) { path ->
            assertFalse(Looper.getMainLooper() == Looper.myLooper())
            object : AtomicFile(path) {
                override fun openRead(): FileInputStream {
                    if (path == file) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return super.openRead()
                }
                override fun startWrite(): FileOutputStream {
                    writes.incrementAndGet()
                    return super.startWrite()
                }
            }
        }
        val job = launch(Dispatchers.Default) {
            try { gated.add(sample(2)) } catch (_: CancellationException) { cancellation.set(true) }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
            job.join()
        }
        assertTrue(cancellation.get())
        assertEquals(0, writes.get())
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(file.path + ".rollback").exists())
    }

    @Test
    fun cancellationDuringCommitReturnsTheVerifiedResultRatherThanFalsePartialFailure() = runBlocking {
        store.add(sample())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<List<Bookmark>?>(null)
        val failure = AtomicReference<Exception?>(null)
        val gated = BookmarkStore(context) { path -> object : AtomicFile(path) {
            override fun finishWrite(stream: FileOutputStream?) {
                if (path == file) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                super.finishWrite(stream)
            }
        } }
        val job = launch(Dispatchers.Default) {
            try { result.set(gated.add(sample(2))) } catch (error: Exception) { failure.set(error) }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
            job.join()
        }
        assertNull(failure.get())
        assertEquals(listOf(sample(), sample(2)), result.get())
        assertEquals(result.get(), BookmarkStore(context).load())
        assertFalse(File(file.path + ".rollback").exists())
    }

    private fun sample(position: Int = 1) = Bookmark(
        id = "bookmark-$position",
        location = ReadingLocation(library, "Synthetic title", 1, 20_000_000, position),
        bookRecord = 1,
        bookTitle = "Synthetic book",
        excerpt = "Synthetic excerpt",
        createdAt = 100,
    )

    private fun entry(bookmark: Bookmark): JSONObject {
        val location = bookmark.location
        return JSONObject().put("id", bookmark.id).put("location", JSONObject()
            .put("libraryId", location.libraryId).put("title", location.title)
            .put("start", location.start).put("end", location.end).put("position", location.position)
            .put("scrollY", location.scrollY)).put("bookRecord", bookmark.bookRecord)
            .put("bookTitle", bookmark.bookTitle).put("excerpt", bookmark.excerpt).put("createdAt", bookmark.createdAt)
    }

    private fun rootJson(entries: List<JSONObject>): JSONObject = JSONObject().put("version", 1)
        .put("bookmarks", JSONArray().also { values -> entries.forEach { values.put(it) } })

    private fun document(bookmarks: List<Bookmark>): String = rootJson(bookmarks.map(::entry)).toString()

    private fun putRaw(raw: String) = putBytes(raw.toByteArray(Charsets.UTF_8))

    private fun putBytes(bytes: ByteArray) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.writeBytes(bytes)
    }

    private suspend fun assertUnreadableAndUnchanged(bytes: ByteArray) {
        putBytes(bytes)
        expectIssue(BookmarkIssue.LOAD) { BookmarkStore(context).load() }
        expectIssue(BookmarkIssue.LOAD) { BookmarkStore(context).add(sample(3000)) }
        expectIssue(BookmarkIssue.LOAD) { BookmarkStore(context).remove(sample().id, library) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(setOf("bookmarks.json"), file.parentFile!!.list()!!.toSet())
    }

    private suspend fun expectIssue(issue: BookmarkIssue, action: suspend () -> Any?) {
        var caught: BookmarkException? = null
        try { action() } catch (failure: BookmarkException) { caught = failure }
        assertNotNull("Expected $issue", caught)
        assertEquals(issue, caught?.issue)
    }

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "bookmark-store-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        var rejectPreferenceAccess = false
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(!rejectPreferenceAccess) { "BookmarkStore must not read or write SharedPreferences" }
            val isolated = "$prefix$name"
            ownedPreferences += isolated
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun deleteOwnedPreferences() {
            for (name in ownedPreferences) {
                check(name.startsWith(prefix))
                check(baseContext.deleteSharedPreferences(name))
            }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}