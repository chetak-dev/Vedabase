package org.vedareader.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Version-1, local-only user metadata; never opens a library, preferences, or legacy saved passages.
 * All instances in this process share a lock, including readers (AtomicFile reads can recover .bak).
 * IDs are unique within a library; paragraph identity is (libraryId, position), not title or mode.
 *
 * Invalid stored data is LOAD, invalid arguments/storage failures are SAVE, and new data exceeding
 * a size/count bound is LIMIT. Nothing is silently truncated, coerced, dropped, or repaired.
 * Cancellation before commit aborts. Once commit begins it finishes/rolls back non-cancellably;
 * a verified committed result is returned even if cancellation races the dispatcher hand-off.
 */
class BookmarkStore internal constructor(
    context: Context,
    private val atomicFileFactory: (File) -> AtomicFile,
) {
    constructor(context: Context) : this(context, { AtomicFile(it) })

    private val context = context.applicationContext

    suspend fun load(): List<Bookmark> = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            readSnapshot(files()).bookmarks
        }
    }

    suspend fun add(bookmark: Bookmark): List<Bookmark> = mutate { current ->
        validate(bookmark)
        // Keep the original ID, date, text and exact location, even at the entry limit.
        if (current.any { it.location.libraryId == bookmark.location.libraryId &&
                it.location.position == bookmark.location.position }) return@mutate current
        require(current.none { it.id == bookmark.id && it.location.libraryId == bookmark.location.libraryId })
        limit(current.size < MAX_ENTRIES)
        (current + bookmark).sortedWith(order)
    }

    suspend fun remove(id: String, libraryId: String): List<Bookmark> = mutate { current ->
        text(id, MAX_ID, nonBlank = true)
        text(libraryId, MAX_ID, nonBlank = true)
        current.filterNot { it.id == id && it.location.libraryId == libraryId }
    }

    private suspend fun mutate(change: (List<Bookmark>) -> List<Bookmark>): List<Bookmark> {
        var committed: List<Bookmark>? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val files = files()
                    // A failed read is always LOAD, and MUST precede any mutation or journal creation.
                    val old = readSnapshot(files)
                    val next = typed(BookmarkIssue.SAVE) { change(old.bookmarks) }
                    if (next == old.bookmarks) return@withLock old.bookmarks
                    val bytes = typed(BookmarkIssue.SAVE) { encode(next) }
                    currentCoroutineContext().ensureActive() // Last cancellable checkpoint.
                    withContext(NonCancellable) {
                        persist(files, old.bytes, bytes)
                        committed = next
                        next
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext(IO) can discard a successful result on return to a cancelled caller.
            // Do not misreport a completed durable transaction as an uncommitted operation.
            return committed ?: throw cancelled
        }
    }

    private data class Files(val data: AtomicFile, val rollback: AtomicFile)
    private data class Snapshot(val bookmarks: List<Bookmark>, val bytes: ByteArray?)

    private fun files(): Files = typed(BookmarkIssue.LOAD) {
        val base = File(context.noBackupFilesDir, "user-state/bookmarks.json")
        Files(atomicFileFactory(base), atomicFileFactory(File(base.path + ".rollback")))
    }

    private fun readSnapshot(files: Files): Snapshot = try {
        // An interrupted verified-write transaction is rolled back before serving any new data.
        // Empty rollback bytes mean the previous main file did not exist, NOT an empty JSON file.
        readBytes(files.rollback)?.let { previous ->
            if (previous.isNotEmpty()) decode(previous)
            restore(files.data, previous.takeIf { it.isNotEmpty() })
            deleteChecked(files.rollback)
        }
        val bytes = readBytes(files.data)
        Snapshot(bytes?.let(::decode).orEmpty().sortedWith(order), bytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Even a persisted count/string/byte limit violation is a failed LOAD, not a new-data LIMIT.
        throw BookmarkException(BookmarkIssue.LOAD, failure)
    }

    private fun persist(files: Files, previous: ByteArray?, next: ByteArray) {
        var mainAttempted = false
        try {
            // AtomicFile.finishWrite can only log sync/rename failures, and may discard its .bak.
            // Keep an independently synced, verified rollback image until the main bytes are verified.
            // These sidecars are all inside the one user-state bookmark path, never source storage.
            writeVerified(files.rollback, previous ?: byteArrayOf())
            mainAttempted = true
            writeVerified(files.data, next)
            deleteChecked(files.rollback)
        } catch (failure: Exception) {
            try {
                if (mainAttempted) restore(files.data, previous)
                deleteChecked(files.rollback)
            } catch (rollbackFailure: Exception) {
                // Retain the rollback image if storage prevents restoration; a later load retries it.
                failure.addSuppressed(rollbackFailure)
            }
            throw BookmarkException(BookmarkIssue.SAVE, failure)
        }
    }

    private fun writeVerified(file: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            stream.flush()
            // Unlike AtomicFile's logging-only sync helper, FileDescriptor.sync throws on failure.
            stream.fd.sync()
            file.finishWrite(stream)
            val actual = readBytes(file)
            if (actual == null || !actual.contentEquals(bytes)) throw IOException("Bookmark write verification failed")
        } catch (failure: Exception) {
            try {
                file.failWrite(stream)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun restore(file: AtomicFile, previous: ByteArray?) {
        if (previous == null) deleteChecked(file) else writeVerified(file, previous)
    }

    private fun deleteChecked(file: AtomicFile) {
        file.delete()
        if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))
                .any { !isMissing(it) }) throw IOException("Bookmark transaction cleanup failed")
    }

    private fun isMissing(file: File): Boolean = try {
        Os.stat(file.path)
        false
    } catch (failure: ErrnoException) {
        // File.exists() can return false for EACCES/ENOTDIR, incorrectly hiding a failed read/delete.
        if (failure.errno != OsConstants.ENOENT) throw failure
        true
    }

    private fun readBytes(file: AtomicFile): ByteArray? {
        val input = try {
            // Use the platform's normal .bak recovery, not a direct read of the base file.
            file.openRead()
        } catch (missing: FileNotFoundException) {
            val base = file.baseFile
            val errno = missing.cause as? ErrnoException
            if (errno != null && errno.errno != OsConstants.ENOENT) throw missing
            // A directory in place of a file, inaccessible parent, or existing backup isn't absence.
            if (!isMissing(base) || !isMissing(File(base.path + ".bak"))) throw missing
            return null
        }
        return input.use {
            if (it.channel.size() > MAX_BYTES) throw IOException("Bookmark file is too large")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() > MAX_BYTES - count) throw IOException("Bookmark file is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): List<Bookmark> {
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return JsonReader(StringReader(utf8)).use { reader ->
            reader.isLenient = false
            var bookmarks: List<Bookmark>? = null
            reader.objectFields(setOf("version", "bookmarks")) { name ->
                when (name) {
                    "version" -> require(reader.integer() == 1L)
                    "bookmarks" -> {
                        val values = ArrayList<Bookmark>()
                        val positions = HashSet<Pair<String, Int>>()
                        val ids = HashSet<Pair<String, String>>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            limit(values.size < MAX_ENTRIES)
                            val bookmark = reader.bookmark()
                            validate(bookmark)
                            require(positions.add(bookmark.location.libraryId to bookmark.location.position))
                            require(ids.add(bookmark.location.libraryId to bookmark.id))
                            values += bookmark
                        }
                        reader.endArray()
                        bookmarks = values
                    }
                }
            }
            require(reader.peek() == JsonToken.END_DOCUMENT)
            requireNotNull(bookmarks)
        }
    }

    private fun JsonReader.bookmark(): Bookmark {
        var id: String? = null
        var location: ReadingLocation? = null
        var bookRecord: Int? = null
        var bookTitle: String? = null
        var excerpt: String? = null
        var createdAt: Long? = null
        objectFields(setOf("id", "location", "bookRecord", "bookTitle", "excerpt", "createdAt")) {
            when (it) {
                "id" -> id = string()
                "location" -> location = location()
                "bookRecord" -> bookRecord = int()
                "bookTitle" -> bookTitle = string()
                "excerpt" -> excerpt = string()
                "createdAt" -> createdAt = integer()
            }
        }
        return Bookmark(requireNotNull(id), requireNotNull(location), requireNotNull(bookRecord),
            requireNotNull(bookTitle), requireNotNull(excerpt), requireNotNull(createdAt))
    }

    private fun JsonReader.location(): ReadingLocation {
        var libraryId: String? = null
        var title: String? = null
        var start: Int? = null
        var end: Int? = null
        var position: Int? = null
        var scrollY: Int? = null
        objectFields(setOf("libraryId", "title", "start", "end", "position", "scrollY")) {
            when (it) {
                "libraryId" -> libraryId = string()
                "title" -> title = string()
                "start" -> start = int()
                "end" -> end = int()
                "position" -> position = int()
                "scrollY" -> scrollY = int()
            }
        }
        return ReadingLocation(requireNotNull(libraryId), requireNotNull(title), requireNotNull(start),
            requireNotNull(end), requireNotNull(position), requireNotNull(scrollY))
    }

    private fun JsonReader.objectFields(expected: Set<String>, field: (String) -> Unit) {
        beginObject()
        val seen = HashSet<String>()
        while (hasNext()) {
            val name = nextName()
            require(name in expected && seen.add(name))
            field(name)
        }
        endObject()
        require(seen == expected)
    }

    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }

    private fun JsonReader.integer(): Long {
        require(peek() == JsonToken.NUMBER)
        val token = nextString()
        // Do NOT use nextInt/nextLong: they may accept fractional/exponent tokens via a double.
        require(token == "0" || (token.isNotEmpty() && token[0] in '1'..'9' && token.all { it in '0'..'9' }))
        return requireNotNull(token.toLongOrNull())
    }

    private fun JsonReader.int(): Int {
        val value = integer()
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }

    private fun validate(bookmark: Bookmark) {
        text(bookmark.id, MAX_ID, nonBlank = true)
        text(bookmark.location.libraryId, MAX_ID, nonBlank = true)
        text(bookmark.location.title, MAX_TITLE)
        text(bookmark.bookTitle, MAX_TITLE)
        text(bookmark.excerpt, MAX_EXCERPT)
        val location = bookmark.location
        require(location.start in 1..MAX_RECORD && location.end in location.start..MAX_RECORD)
        require(location.position in location.start..location.end)
        require(bookmark.bookRecord in 1..location.position)
        require(location.scrollY >= 0 && bookmark.createdAt >= 0)
    }

    private fun text(value: String, maximum: Int, nonBlank: Boolean = false) {
        limit(value.length <= maximum) // UTF-16 code units, matching Kotlin String.length.
        require(!nonBlank || value.isNotBlank())
        // Reject lone UTF-16 surrogates rather than silently replacing them during UTF-8 encoding.
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate())
            } else require(!character.isLowSurrogate())
        }
    }

    private fun encode(bookmarks: List<Bookmark>): ByteArray {
        limit(bookmarks.size <= MAX_ENTRIES)
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                limit(count < MAX_BYTES)
                super.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                limit(length <= MAX_BYTES - count)
                super.write(bytes, offset, length)
            }
        }
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.beginObject().name("version").value(1L).name("bookmarks").beginArray()
            for (bookmark in bookmarks) {
                writer.beginObject().name("id").value(bookmark.id).name("location").beginObject()
                val location = bookmark.location
                writer.name("libraryId").value(location.libraryId).name("title").value(location.title)
                    .name("start").value(location.start.toLong()).name("end").value(location.end.toLong())
                    .name("position").value(location.position.toLong()).name("scrollY").value(location.scrollY.toLong())
                    .endObject().name("bookRecord").value(bookmark.bookRecord.toLong())
                    .name("bookTitle").value(bookmark.bookTitle).name("excerpt").value(bookmark.excerpt)
                    .name("createdAt").value(bookmark.createdAt).endObject()
            }
            writer.endArray().endObject()
        }
        return output.toByteArray()
    }

    private inline fun <T> typed(issue: BookmarkIssue, action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BookmarkException) {
        throw failure
    } catch (failure: Exception) {
        throw BookmarkException(issue, failure)
    }

    private fun limit(allowed: Boolean) {
        if (!allowed) throw BookmarkException(BookmarkIssue.LIMIT)
    }

    private companion object {
        val mutex = Mutex()
        val order = compareByDescending<Bookmark> { it.createdAt }.thenBy { it.id }
        const val MAX_ENTRIES = 2_000
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_RECORD = 20_000_000
        const val MAX_ID = 128
        const val MAX_TITLE = 8_192
        const val MAX_EXCERPT = 500
    }
}