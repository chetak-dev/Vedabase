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
import java.io.File
import kotlin.system.measureTimeMillis

/** Explicit opt-in: read the active import without copying or changing any user state. */
@RunWith(AndroidJUnit4::class)
class SearchFeedbackCheck {
    @Test fun realTargetsAndTimingRemainReadOnly() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkSearchFeedback") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context)
        val library = store.libraries().single { it.id == store.activeId }
        val directory = File(context.noBackupFilesDir, "libraries").canonicalFile
        val file = File(directory, library.fileName).canonicalFile
        check(file.parentFile == directory)
        val size = file.length()
        val modified = file.lastModified()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            IvdSearch(db, library.id).use { engine ->
                for ((index, request) in listOf(
                    SearchRequest("Madhya 1.1", target = SearchTarget.VERSE_REFERENCE),
                    SearchRequest("CC Madhya 1.1", target = SearchTarget.VERSE_REFERENCE),
                    SearchRequest("krsna", setOf(1428), target = SearchTarget.VERSE_WORDS),
                    SearchRequest("krsna", target = SearchTarget.VERSE_WORDS),
                    SearchRequest("devotional service"),
                    SearchRequest("the", setOf(1428)),
                ).withIndex()) {
                    lateinit var result: SearchPage
                    val ms = measureTimeMillis { result = engine.searchPage(request) }
                    assertTrue("Diagnostic $index must find actual source", result.hits.isNotEmpty())
                    if (index < 2) {
                        assertEquals(listOf(227161), result.hits.map { it.location.position })
                        assertEquals(0, engine.lastReadMetrics?.postingMetadataQueries)
                        assertEquals(0, engine.lastReadMetrics?.plainTextReads)
                    }
                    report("query=$index hits=${result.hits.size} ms=$ms metrics=${engine.lastReadMetrics}")
                }
                for (request in listOf(SearchRequest("devot", setOf(1428)),
                    SearchRequest("CC Madhya 1.", target = SearchTarget.VERSE_REFERENCE))) {
                    val result = engine.searchSuggestions(request)
                    assertTrue(result.isNotEmpty())
                    report("suggestions=${result.size} metrics=${engine.lastReadMetrics}")
                }
            }
            db.rawQuery("SELECT total_changes()", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        assertEquals(size, file.length()); assertEquals(modified, file.lastModified())
    }
    private fun report(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "SEARCH_FEEDBACK $value\n") })
}