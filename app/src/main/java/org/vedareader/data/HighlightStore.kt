package org.vedareader.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Local-only user highlights, one JSON file under no_backup/user-state next to the bookmarks.
 * Every mutation rewrites the file atomically under one process-wide lock; invalid stored entries
 * fail the load rather than being silently dropped. Never touches a library or preferences.
 */
class HighlightStore(context: Context) {
    private val context = context.applicationContext

    suspend fun load(): List<Highlight> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun add(highlight: Highlight): List<Highlight> = mutate { current ->
        validate(highlight)
        val existing = current.firstOrNull {
            it.location.libraryId == highlight.location.libraryId && it.record == highlight.record &&
                it.start == highlight.start && it.endRecord == highlight.endRecord && it.end == highlight.end
        }
        require(current.none { it.id == highlight.id && it.location.libraryId == highlight.location.libraryId && it !== existing })
        when {
            !highlight.highlighted && !highlight.hasNote -> current // A cancelled/empty draft is not an annotation.
            existing != null -> current.map {
                if (it !== existing) it else it.copy(
                    highlighted = it.highlighted || highlight.highlighted,
                    color = if (!it.highlighted && highlight.highlighted) highlight.color else it.color,
                    note = if (it.hasNote) it.note else highlight.note.trim(),
                )
            }
            else -> {
                if (current.size >= MAX_ENTRIES) throw IOException("Highlight limit reached")
                (current + highlight.copy(note = highlight.note.trim())).sortedWith(order)
            }
        }
    }

    suspend fun recolor(id: String, libraryId: String, color: HighlightColor): List<Highlight> = mutate { current ->
        current.map { if (it.id == id && it.location.libraryId == libraryId) it.copy(color = color) else it }
    }

    suspend fun setNote(id: String, libraryId: String, note: String): List<Highlight> = mutate { current ->
        require(note.length <= MAX_NOTE) { "Note exceeds $MAX_NOTE characters" }
        val trimmed = note.trim()
        current.mapNotNull {
            if (it.id != id || it.location.libraryId != libraryId) it
            else if (!it.highlighted && trimmed.isEmpty()) null
            else it.copy(note = trimmed)
        }
    }

    /** Removes only the marker fill; independent notes remain anchored and underlined. */
    suspend fun removeHighlightOnly(id: String, libraryId: String): List<Highlight> = mutate { current ->
        current.mapNotNull {
            if (it.id != id || it.location.libraryId != libraryId) it
            else if (it.hasNote) it.copy(highlighted = false)
            else null
        }
    }

    suspend fun remove(id: String, libraryId: String): List<Highlight> = mutate { current ->
        current.filterNot { it.id == id && it.location.libraryId == libraryId }
    }

    private suspend fun mutate(change: (List<Highlight>) -> List<Highlight>): List<Highlight> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read()
                val next = change(current)
                if (next != current) write(next)
                next
            }
        }

    private fun file(): AtomicFile {
        val base = File(context.noBackupFilesDir, "user-state/highlights.json")
        base.parentFile?.mkdirs()
        return AtomicFile(base)
    }

    private fun read(): List<Highlight> {
        val bytes = try {
            file().readFully()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        if (bytes.size > MAX_BYTES) throw IOException("Highlight file is too large")
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val entries = root.getJSONArray("highlights")
        if (entries.length() > MAX_ENTRIES) throw IOException("Highlight limit reached")
        return List(entries.length()) { decode(entries.getJSONObject(it)) }.onEach(::validate).sortedWith(order)
    }

    private fun write(highlights: List<Highlight>) {
        val bytes = encode(highlights).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Highlight file is too large")
        val file = file()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun encode(highlights: List<Highlight>): String {
        val entries = JSONArray()
        for (highlight in highlights) {
            val location = highlight.location
            entries.put(JSONObject()
                .put("id", highlight.id)
                .put("location", JSONObject().put("libraryId", location.libraryId).put("title", location.title)
                    .put("start", location.start).put("end", location.end).put("position", location.position))
                .put("bookTitle", highlight.bookTitle)
                .put("record", highlight.record).put("start", highlight.start)
                .put("endRecord", highlight.endRecord).put("end", highlight.end)
                .put("text", highlight.text).put("color", highlight.color.name)
                .put("createdAt", highlight.createdAt).put("note", highlight.note)
                .put("highlighted", highlight.highlighted))
        }
        return JSONObject().put("version", 1).put("highlights", entries).toString()
    }

    private fun decode(value: JSONObject): Highlight {
        val location = value.getJSONObject("location")
        return Highlight(
            value.getString("id"),
            ReadingLocation(location.getString("libraryId"), location.getString("title"),
                location.getInt("start"), location.getInt("end"), location.getInt("position")),
            value.getString("bookTitle"), value.getInt("record"), value.getInt("start"),
            value.getInt("endRecord"), value.getInt("end"), value.getString("text"),
            HighlightColor.valueOf(value.getString("color")), value.getLong("createdAt"), value.optString("note"),
            if (value.has("highlighted")) {
                val highlighted = value.get("highlighted")
                require(highlighted is Boolean) { "Invalid highlighted flag" }
                highlighted
            } else true,
        )
    }

    private fun validate(highlight: Highlight) {
        require(highlight.id.isNotBlank() && highlight.id.length <= MAX_ID)
        require(highlight.location.libraryId.isNotBlank() && highlight.location.libraryId.length <= MAX_ID)
        require(highlight.record > 0 && highlight.endRecord >= highlight.record)
        require(highlight.start >= 0 && highlight.end >= 0)
        require(highlight.record != highlight.endRecord || highlight.end > highlight.start)
        require(highlight.text.length <= MAX_TEXT && highlight.bookTitle.length <= MAX_TITLE)
        require(highlight.location.title.length <= MAX_TITLE && highlight.note.length <= MAX_NOTE)
    }

    companion object {
        private val mutex = Mutex()
        private val order = compareByDescending<Highlight> { it.createdAt }.thenBy { it.id }
        const val MAX_ENTRIES = 5_000
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_ID = 128
        const val MAX_TITLE = 8_192
        const val MAX_TEXT = 1_000
        const val MAX_NOTE = 4_000
    }
}
