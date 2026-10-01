package org.vedareader.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.vedareader.reader.FolioText
import java.io.File
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

class IvdRepository(private val context: Context, private val store: LibraryStore) {
    private val directory = File(context.noBackupFilesDir, "libraries")
    private val importer = IvdImporter(context, store)
    private val connections = ConcurrentHashMap<String, SQLiteDatabase>()
    private val bookOutlines = IvdBookReader.OutlineCache()
    private val connectionLifecycle = Any()
    private val searchEngines = mutableMapOf<String, IvdSearch>()
    private var closed = false

    suspend fun searchBooks(library: String): List<SearchBook> = withContext(Dispatchers.IO) {
        searchEngine(library).searchBooks()
    }

    suspend fun searchPage(library: String, request: SearchRequest, afterRecord: Int = 0): SearchPage = withContext(Dispatchers.IO) {
        searchEngine(library).searchPage(request, afterRecord)
    }

    suspend fun searchSuggestions(library: String, request: SearchRequest): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        searchEngine(library).searchSuggestions(request)
    }

    suspend fun resolveLink(library: String, link: ReaderLink, near: Int): LinkResolution = withContext(Dispatchers.IO) {
        searchEngine(library).resolveLink(link, near)
    }

    private fun searchEngine(library: String): IvdSearch = synchronized(connectionLifecycle) {
        check(!closed) { "Repository is closed" }
        searchEngines.getOrPut(library) { IvdSearch(database(library), library) }
    }

    suspend fun prepareStorage() = importer.prepareStorage()

    suspend fun import(uri: Uri, language: String, progress: (ImportProgress) -> Unit): LibraryInfo = importer.import(uri, language, progress)

    fun children(library: String, parent: Int): List<ContentNode> = database(library).rawQuery(
        "SELECT record,parent,title,next_sibling,node_children,level,node_code FROM contents WHERE parent=? ORDER BY record",
        arrayOf(parent.toString()),
    ).use { cursor -> ContentTitles.interpolateBlank(buildList { while (cursor.moveToNext()) add(cursor.node()) }) }

    fun node(library: String, record: Int): ContentNode? = database(library).rawQuery(
        "SELECT record,parent,title,next_sibling,node_children,level,node_code FROM contents WHERE record=? LIMIT 1",
        arrayOf(record.toString()),
    ).use { if (it.moveToFirst()) it.node() else null }

    /** Numeric contents level -> semantic name such as LE_Chapter; bounded like the search engine's copy. */
    fun levelNames(library: String): Map<Int, String> = database(library).rawQuery(
        "SELECT id,level FROM levels LIMIT 513", emptyArray(),
    ).use { cursor -> buildMap {
        while (cursor.moveToNext() && size < 512) put(cursor.getInt(0), cursor.getString(1).orEmpty().trim())
    } }

    suspend fun readingSection(library: String, record: Int): ReadingSection = withContext(Dispatchers.IO) {
        IvdSections(database(library)).read(record)
    }

    suspend fun bookSection(library: String, record: Int): BookReadingSection = withContext(Dispatchers.IO) {
        IvdBookReader(database(library), bookOutlines).read(record)
    }

    suspend fun bookResource(library: String, record: Int): BookResource = withContext(Dispatchers.IO) {
        IvdBookReader(database(library), bookOutlines).readResource(record)
    }

    /** Opt in to small, whole-entry windows without changing legacy bookResource callers. */
    suspend fun readerWindow(library: String, record: Int): BookResource = withContext(Dispatchers.IO) {
        IvdBookReader(database(library), bookOutlines).readWindow(record)
    }

    suspend fun introductionRecords(library: String, parent: Int, children: List<ContentNode>): Set<Int> = withContext(Dispatchers.IO) {
        IvdIntroduction(database(library)).records(parent, children)
    }

    suspend fun chapterOverview(library: String, parent: Int, children: List<ContentNode>): ChapterOverview? = withContext(Dispatchers.IO) {
        val chapter = node(library, parent) ?: throw ReadingException(ReadingFailure.LOAD)
        IvdChapterOverview(database(library)).load(chapter, children)
    }

    fun page(location: ReadingLocation): ReadingPage {
        require(location.start > 0 && location.end >= location.start && location.position in location.start..location.end)
        val db = database(location.libraryId)
        val paragraphs = db.rawQuery(
            "SELECT recid,plain,stylename,levelname FROM texts WHERE recid>=? AND recid<=? ORDER BY recid LIMIT 61",
            arrayOf(location.position.toString(), location.end.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(Paragraph(cursor.getInt(0), cursor.getString(1).orEmpty(),
            cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty())) } }
        val previous = db.rawQuery("SELECT recid FROM texts WHERE recid<? AND recid>=? ORDER BY recid DESC LIMIT 60",
            arrayOf(location.position.toString(), location.start.toString())).use { if (it.moveToLast()) it.getInt(0) else null }
        return ReadingPage(paragraphs.take(60), previous, paragraphs.getOrNull(60)?.record)
    }

    suspend fun search(library: String, query: String, limit: Int = 60): SearchResults = withContext(Dispatchers.IO) {
        val terms = WordIndex.terms(query)
        require(terms.size <= 8) { "Use up to eight words in a search" }
        if (terms.isEmpty()) return@withContext SearchResults()
        val db = database(library)
        var matches: BitSet? = null
        for (term in terms) {
            currentCoroutineContext().ensureActive()
            val records = BitSet()
            // Fetch large posting lists in bounded chunks, below Android's CursorWindow limit.
            db.rawQuery("SELECT rowid,indexbase,length(data) FROM words WHERE word=? AND idx IN ('','Devanagari')", arrayOf(term)).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = cursor.getLong(0)
                    val base = cursor.getInt(1)
                    val bytes = cursor.getInt(2)
                    var offset = 0
                    while (offset < bytes) {
                        currentCoroutineContext().ensureActive()
                        db.rawQuery("SELECT substr(data,?,196608) FROM words WHERE rowid=?", arrayOf((offset + 1).toString(), row.toString())).use {
                            if (it.moveToFirst()) WordIndex.addRecords(records, base, it.getBlob(0))
                        }
                        offset += 196608
                    }
                }
            }
            if (matches == null) matches = records else matches.and(records)
            if (matches.isEmpty) break
        }
        val found = requireNotNull(matches)
        // References such as "Bg 1.1" should work even though they are not ordinary prose tokens.
        db.rawQuery("SELECT record FROM contents WHERE simpletitle=? LIMIT 60", arrayOf(WordIndex.simpleTitle(query))).use {
            while (it.moveToNext()) found.set(it.getInt(0))
        }
        val hits = buildList {
            var record = found.nextSetBit(0)
            while (record >= 0 && size < limit) {
                currentCoroutineContext().ensureActive()
                val text = db.rawQuery("SELECT plain FROM texts WHERE recid=? LIMIT 1", arrayOf(record.toString())).use {
                    if (it.moveToFirst()) FolioText.plain(it.getString(0)).take(260) else ""
                }
                if (text.isNotBlank()) add(SearchHit(locationFor(library, record), text))
                record = found.nextSetBit(record + 1)
            }
        }
        SearchResults(hits, found.cardinality())
    }

    fun locationFor(library: String, record: Int): ReadingLocation {
        val nearest = database(library).rawQuery(
            "SELECT record,parent,title,next_sibling,node_children,level,node_code FROM contents WHERE record<=? ORDER BY record DESC LIMIT 1",
            arrayOf(record.toString()),
        ).use { if (it.moveToFirst()) it.node() else null }
        return if (nearest != null) ReadingLocation(library, nearest.displayTitle, nearest.record, maxOf(nearest.end, record), record)
        else ReadingLocation(library, "Vedabase", record, record + 60, record)
    }

    fun resolveLink(library: String, kind: String, target: String): Int? {
        val db = database(library)
        if (kind == "jump") {
            db.rawQuery("SELECT recid FROM jumplinks WHERE title=? LIMIT 1", arrayOf(target)).use {
                if (it.moveToFirst()) return it.getInt(0)
            }
        }
        val title = if (kind == "query") target.substringAfter(',').trim().removeSuffix("]") else target
        return db.rawQuery("SELECT record FROM contents WHERE simpletitle=? LIMIT 1", arrayOf(WordIndex.simpleTitle(title))).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
    }

    fun popup(library: String, title: String): String? = database(library)
        .rawQuery("SELECT plain FROM popup WHERE title=? LIMIT 1", arrayOf(title)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun definitions(library: String, query: String): List<Definition> {
        val db = database(library)
        val dictionaries = db.rawQuery("SELECT id,name FROM dictionary", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1)) }
        }
        return buildList {
            db.rawQuery("SELECT id,word FROM dict_words WHERE simple=? LIMIT 30", arrayOf(WordIndex.simpleTitle(query))).use { words ->
                while (words.moveToNext()) for ((id, name) in dictionaries) {
                    db.rawQuery("SELECT meaning FROM dict_means WHERE dictid=? AND wordid=? LIMIT 100", arrayOf(id.toString(), words.getInt(0).toString())).use {
                        while (it.moveToNext()) add(Definition(words.getString(1), name, FolioText.plain(it.getString(0))))
                    }
                }
            }
        }
    }

    fun objectData(library: String, name: String): ContentObject? = database(library)
        .rawQuery("SELECT objectType,objectData FROM objects WHERE objectName=? LIMIT 1", arrayOf(name)).use {
            if (it.moveToFirst()) ContentObject(if (it.getString(0) == "OB") "image/bmp" else it.getString(0), it.getBlob(1)) else null
        }

    /** Only bounded raster images may be served to the offline reader; never HTML, SVG, or media. */
    fun readerImage(library: String, name: String): ContentObject? = database(library).rawQuery(
        "SELECT objectType,objectData FROM objects WHERE objectName=? " +
            "AND objectType IN ('OB','image/png','image/jpeg','image/gif','image/webp','image/bmp') " +
            "AND length(objectData)<=1048576 LIMIT 1",
        arrayOf(name),
    ).use {
        if (it.moveToFirst()) ContentObject(if (it.getString(0) == "OB") "image/bmp" else it.getString(0), it.getBlob(1)) else null
    }

    fun close() = synchronized(connectionLifecycle) {
        if (!closed) {
            closed = true
            searchEngines.values.forEach { it.close() }
            searchEngines.clear()
            bookOutlines.clear()
            connections.values.forEach { it.close() }
            connections.clear()
        }
    }

    private fun database(id: String): SQLiteDatabase = synchronized(connectionLifecycle) {
        check(!closed) { "Repository is closed" }
        connections.getOrPut(id) {
            val library = store.libraries().firstOrNull { it.id == id } ?: error("Library is no longer available")
            open(File(directory, library.fileName))
        }
    }

    private fun open(file: File) = SQLiteDatabase.openDatabase(file.absolutePath, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    private fun Cursor.node() = ContentNode(getInt(0), getInt(1), getString(2).orEmpty(), getInt(3), getInt(4), getInt(5), getString(6).orEmpty())
}