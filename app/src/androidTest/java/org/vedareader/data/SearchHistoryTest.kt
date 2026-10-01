package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.reader.ReadingMode
import java.io.File
import java.util.UUID

/** LibraryStore's real JSON/prefs path, exclusively under UUID-prefixed test preference names. */
@RunWith(AndroidJUnit4::class)
class SearchHistoryTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var preferences: SharedPreferences
    private lateinit var store: LibraryStore
    private lateinit var baseline: Map<String, *>
    private val first = "synthetic-history-a"
    private val second = "synthetic-history-b"

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
        assertSame(context, context.applicationContext)
        preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        store = LibraryStore(context)
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)
        // These are synthetic registry/reader sentinels, not snapshots of private production data.
        store.addLibrary(LibraryInfo(first, "Synthetic history fixture", "en", "$first.ivd", 0, 0, "Synthetic only"))
        store.savePreferences(ReaderPreferences(fontSize = 23))
        store.readingMode = ReadingMode.BOOK
        store.saveLocation(ReadingLocation(first, "Synthetic saved anchor", 40, 69, 46, 137))
        check(preferences.edit().putString("fixture-owner", root.name)
            .putString("saved", "[]").putBoolean("unrelated-sentinel", true).commit())
        baseline = unrelatedPreferences()
    }

    @After
    fun tearDown() {
        try {
            if (::baseline.isInitialized) assertEquals("History must not change registry/reading preferences", baseline, unrelatedPreferences())
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
    fun absentHistoryIsEmptyAndReadingItDoesNotCreateAPreference() {
        val before = preferences.all.toMap()
        assertEquals(emptyList<String>(), store.recentSearches(first))
        assertEquals(emptyList<String>(), store.recentSearches(second))
        assertEquals(before, preferences.all)
    }

    @Test
    fun savingDeduplicatesBeforeTheEightEntryBoundAndRetainsCallerRecencyOrder() {
        val queries = (1..12).map { "synthetic query $it" }
        val supplied = listOf(queries[0], queries[0], queries[1], queries[0]) + queries.drop(2)
        store.saveRecentSearches(first, supplied)
        val expected = queries.take(8)
        assertEquals(expected, store.recentSearches(first))
        assertEquals(expected, LibraryStore(context).recentSearches(first))
        assertEquals(8, store.recentSearches(first).size)
        // Moving an existing query to the front must not leave its old duplicate behind.
        store.saveRecentSearches(first, listOf(queries[5]) + expected)
        assertEquals(listOf(queries[5]) + expected.filterNot { it == queries[5] }, store.recentSearches(first))
    }

    @Test
    fun unicodeAndLengthBoundaryRoundTripWithoutNormalizationAndInvalidEntriesDoNotConsumeTheBound() {
        val decomposed = "a\u0304"
        val valid = listOf("Kṛṣṇa कृष्ण", decomposed, "ā", "x".repeat(SearchText.MAX_QUERY_CHARACTERS))
        store.saveRecentSearches(first, listOf("", " \n\t", "x".repeat(SearchText.MAX_QUERY_CHARACTERS + 1)) + valid + valid)
        assertEquals(valid, store.recentSearches(first))
        assertEquals(valid, LibraryStore(context).recentSearches(first))
        assertEquals(decomposed, store.recentSearches(first)[1])
    }

    @Test
    fun historiesAreLibraryScopedAndClearDoesNotModifyAnotherLibraryOrAnyReaderPreference() {
        store.saveRecentSearches(first, listOf("first query", "shared query"))
        store.saveRecentSearches(second, listOf("second query", "shared query"))
        assertEquals(listOf("first query", "shared query"), store.recentSearches(first))
        assertEquals(listOf("second query", "shared query"), store.recentSearches(second))
        val before = preferences.all.toMap()
        store.saveRecentSearches(first, emptyList())
        assertEquals(emptyList<String>(), LibraryStore(context).recentSearches(first))
        assertEquals(listOf("second query", "shared query"), LibraryStore(context).recentSearches(second))
        assertEquals(before.filterKeys { it != "searchHistory:$first" }, preferences.all.filterKeys { it != "searchHistory:$first" })
        val cleared = preferences.all.toMap()
        store.saveRecentSearches(first, emptyList())
        assertEquals(cleared, preferences.all)
    }

    @Test
    fun nonStringJsonValuesNeverBecomeSuggestedQueries() {
        check(preferences.edit().putString("searchHistory:$first", "[null,17,true,{},[],\"valid query\"]").commit())
        assertEquals(listOf("valid query"), store.recentSearches(first))
    }

    @Test
    fun malformedNonArrayOversizedAndWrongTypedHistoryAreEmptyWithoutRepairingPreferencesOnRead() {
        store.saveRecentSearches(second, listOf("other library must survive"))
        val malformed = listOf("not JSON", "{", "{\"query\":\"not an array\"}", "[\"unfinished", "[" + " ".repeat(16_384) + "]")
        for (raw in malformed) {
            check(preferences.edit().putString("searchHistory:$first", raw).commit())
            val before = preferences.all.toMap()
            assertEquals(emptyList<String>(), LibraryStore(context).recentSearches(first))
            assertEquals(before, preferences.all)
            assertEquals(listOf("other library must survive"), store.recentSearches(second))
        }
        check(preferences.edit().putInt("searchHistory:$first", 17).commit())
        val before = preferences.all.toMap()
        assertEquals(emptyList<String>(), store.recentSearches(first))
        assertEquals(before, preferences.all)
        // An explicit save can recover bad history; a read alone must never mutate it.
        store.saveRecentSearches(first, listOf("recovered synthetic query"))
        assertEquals(listOf("recovered synthetic query"), LibraryStore(context).recentSearches(first))
    }

    private fun unrelatedPreferences() = preferences.all.filterKeys { !it.startsWith("searchHistory:") }.toMap()

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val prefix = "search-history-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix$name"
            ownedPreferences += isolated
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun deleteOwnedPreferences() {
            for (name in ownedPreferences) { check(name.startsWith(prefix)); check(baseContext.deleteSharedPreferences(name)) }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }
}