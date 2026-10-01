package org.vedareader.data

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.reader.FolioText
import java.io.File
import kotlin.system.measureTimeMillis

/** Opt-in read-only diagnostic on an explicitly staged local test copy; never a registry import. */
@RunWith(AndroidJUnit4::class)
class SearchImportedCheck {
    @Test
    fun actualIndexScopesReferencesSuggestionsAndReaderAnchors() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkSearchCorpus") == "true")
        val file = File("/data/local/tmp/vedareader-search-source.ivd")
        check(file.isFile)
        val size = file.length()
        val modified = file.lastModified()
        val db = SQLiteDatabase.openDatabase(file.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        val engine = IvdSearch(db, "diagnostic-only")
        try {
            lateinit var books: List<SearchBook>
            val catalogMs = measureTimeMillis { books = engine.searchBooks() }
            assertTrue(books.size > 700)
            assertTrue(books.any { it.node.record == 1428 && it.node.end == 13063 })
            report("catalog books=${books.size} ms=$catalogMs")
            val requests = listOf(
                SearchRequest("krsna", setOf(1428)),
                SearchRequest("krsna consciousness", setOf(1428), SearchMode.EXACT_PHRASE),
                SearchRequest("arjuna", setOf(1428, 13064)),
                SearchRequest("devotional service"),
                SearchRequest("BG 2.8", setOf(1428)),
                SearchRequest("BG 1.17", setOf(1428)),
            )
            for ((index, request) in requests.withIndex()) {
                lateinit var page: SearchPage
                val ms = measureTimeMillis { page = engine.searchPage(request) }
                assertTrue("Expected real matches for diagnostic $index", page.hits.isNotEmpty())
                assertTrue(page.hits.size <= 30)
                assertEquals(page.hits.map { it.location.position }.distinct(), page.hits.map { it.location.position })
                for (hit in page.hits) {
                    if (request.bookRecords.isNotEmpty()) assertTrue(request.bookRecords.any { id ->
                        val book = books.single { it.node.record == id }.node
                        hit.location.position in book.record..book.end
                    })
                    assertTrue(hit.location.position in hit.location.start..hit.location.end)
                    assertTrue(hit.snippet.length <= 260)
                    assertTrue(hit.highlights.all { it.start >= 0 && it.endExclusive <= hit.snippet.length })
                }
                if (index == 5) assertEquals(1865, page.hits.first().location.position)
                if (index == 0 || index == 4) {
                    val hit = page.hits.first()
                    val resource = IvdBookReader(db).readResource(hit.location.position)
                    assertEquals(hit.location.position, resource.anchorRecord)
                    val paragraph = resource.section.paragraphs.single { it.record == hit.location.position }
                    assertTrue(FolioText.plain(paragraph.text).contains(hit.snippet))
                }
                if (index == 0 && page.nextAfter != null) {
                    val next = engine.searchPage(request, requireNotNull(page.nextAfter))
                    assertTrue(next.hits.none { later -> page.hits.any { it.location.position == later.location.position } })
                }
                report("query=$index hits=${page.hits.size} more=${page.nextAfter != null} ms=$ms")
            }
            for (prefix in listOf("devot", "BG 2.", "krsna consc")) {
                lateinit var suggestions: List<SearchSuggestion>
                val request = SearchRequest(prefix, setOf(1428))
                val ms = measureTimeMillis { suggestions = engine.searchSuggestions(request) }
                assertTrue("Expected real scoped completions", suggestions.isNotEmpty())
                assertTrue(suggestions.size <= 8)
                assertTrue(engine.searchPage(request.copy(query = suggestions.first().query)).hits.isNotEmpty())
                report("suggestion items=${suggestions.size} ms=$ms")
            }
            db.rawQuery("SELECT total_changes()", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { engine.close(); db.close() }
        assertEquals(size, file.length())
        assertEquals(modified, file.lastModified())
    }

    private fun report(message: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("stream", "SEARCH_CHECK $message\n") })
}