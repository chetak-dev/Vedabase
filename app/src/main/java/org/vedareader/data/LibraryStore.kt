package org.vedareader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vedareader.reader.ReadingMode

/** User data is separate from the read-only source, and every reference is library-scoped. */
class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    init {
        val obsolete = preferences.all.keys.filter { it == "lastReading" || it.startsWith("study:") }
        if (obsolete.isNotEmpty()) preferences.edit().also { editor -> obsolete.forEach(editor::remove) }.apply()
    }

    fun libraries(): List<LibraryInfo> = array("libraries").map { value ->
        LibraryInfo(value.getString("id"), value.getString("title"), value.getString("language"),
            value.getString("file"), value.getLong("size"), value.getLong("records"), value.optString("copyright"))
    }

    @Synchronized
    fun addLibrary(library: LibraryInfo) {
        val previousLibraries = preferences.getString("libraries", null)
        val previousActive = activeId
        val values = JSONArray()
        libraries().forEach { values.put(it.json()) }
        values.put(library.json())
        if (!preferences.edit().putString("libraries", values.toString()).putString("active", library.id).commit()) {
            // A failed commit can still update the in-memory preferences map.
            preferences.edit().putString("libraries", previousLibraries).putString("active", previousActive).commit()
            throw LibraryImportException(ImportFailure.STORAGE)
        }
    }

    var activeId: String?
        get() = preferences.getString("active", null)
        set(value) { preferences.edit().putString("active", value).apply() }

    var readingMode: ReadingMode
        get() = ReadingMode.entries.firstOrNull { it.name == preferences.getString("readingMode", null) } ?: ReadingMode.VERSE
        set(value) { preferences.edit().putString("readingMode", value.name).apply() }

    fun lastLocation(libraryId: String): ReadingLocation? = preferences.getString("position:$libraryId", null)
        ?.let { location(JSONObject(it)) }

    fun saveLocation(location: ReadingLocation) {
        preferences.edit().putString("position:${location.libraryId}", location.json().toString()).apply()
    }

    fun saved(): List<SavedPassage> = array("saved").map {
        SavedPassage(it.getString("id"), location(it.getJSONObject("location")), it.optString("note"))
    }

    fun savePassages(passages: List<SavedPassage>) {
        val result = JSONArray()
        passages.forEach { result.put(JSONObject().put("id", it.id).put("location", it.location.json()).put("note", it.note)) }
        preferences.edit().putString("saved", result.toString()).apply()
    }

    fun readerPreferences() = ReaderPreferences(
        preferences.getInt("textSize", ReaderPreferences.DEFAULT_TEXT_SIZE).coerceIn(14, 30),
        ReaderPaper.entries.firstOrNull { it.name == preferences.getString("paper", "LIGHT") } ?: ReaderPaper.LIGHT,
        // "lineFactor" replaced "lineSpacing" when the base line height was tightened; old values are dropped.
        preferences.getInt("lineFactor", 100).coerceIn(85, 150),
    )

    fun savePreferences(value: ReaderPreferences) {
        preferences.edit().putInt("textSize", value.fontSize.coerceIn(14, 30)).putString("paper", value.paper.name)
            .putInt("lineFactor", value.lineSpacing.coerceIn(85, 150)).remove("lineSpacing").apply()
    }

    /** Applied to new marks only; existing annotations retain their own colour. */
    var highlightColor: HighlightColor
        get() = HighlightColor.entries.firstOrNull { it.name == preferences.getString("highlightColor", null) } ?: HighlightColor.YELLOW
        set(value) { preferences.edit().putString("highlightColor", value.name).apply() }

    /** Local user history only; never part of the read-only Vedabase source or sent online. */
    fun recentSearches(libraryId: String): List<String> = runCatching {
        val raw = preferences.getString("searchHistory:$libraryId", "[]").orEmpty()
        require(raw.length <= 16_384)
        val values = JSONArray(raw)
        (0 until minOf(values.length(), 8)).mapNotNull { (values.opt(it) as? String)?.takeIf { query ->
            query.isNotBlank() && query.length <= SearchText.MAX_QUERY_CHARACTERS
        } }
    }.getOrDefault(emptyList())

    fun saveRecentSearches(libraryId: String, queries: List<String>) {
        val values = JSONArray()
        queries.asSequence().filter { it.isNotBlank() && it.length <= SearchText.MAX_QUERY_CHARACTERS }
            .distinct().take(8).forEach { values.put(it) }
        preferences.edit().putString("searchHistory:$libraryId", values.toString()).apply()
    }

    private fun array(key: String): List<JSONObject> {
        val values = JSONArray(preferences.getString(key, "[]"))
        return (0 until values.length()).map { values.getJSONObject(it) }
    }

    private fun LibraryInfo.json() = JSONObject().put("id", id).put("title", title).put("language", languageTag)
        .put("file", fileName).put("size", size).put("records", records).put("copyright", copyright)

    private fun ReadingLocation.json() = JSONObject().put("library", libraryId).put("title", title)
        .put("start", start).put("end", end).put("position", position).put("scroll", scrollY)

    private fun location(value: JSONObject) = ReadingLocation(value.getString("library"), value.getString("title"),
        value.getInt("start"), value.getInt("end"), value.getInt("position"), value.optInt("scroll"))
}