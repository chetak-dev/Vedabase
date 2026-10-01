package org.vedareader.ui

import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.IvdSearch
import org.vedareader.data.SearchRequest
import java.io.File

/** Opt-in local visual review using original database results, not a production import. */
@RunWith(AndroidJUnit4::class)
class SearchPreviewCheck {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureRealSearchStatesForLocalReview() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureSearchPreview") == "true")
        val db = SQLiteDatabase.openDatabase("/data/local/tmp/vedareader-search-source.ivd", null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        val engine = IvdSearch(db, "preview")
        try {
            val books = engine.searchBooks()
            val query = "devotional service"
            val page = engine.searchPage(SearchRequest(query, setOf(1428)))
            val state = mutableStateOf(SearchUiState(libraryId = "preview", libraryTitle = "Vedabase",
                books = books, selectedBooks = setOf(1428)))
            compose.setContent {
                VedaPreviewTheme { SearchScreen(state.value, {}, { state.value = state.value.copy(query = it) },
                    { state.value = state.value.copy(selectedBooks = it) }, {}, {}, {}, {}, {}, {}, {}, {}) }
            }
            capture("search-home")
            compose.runOnIdle { state.value = state.value.copy(query = query, submittedQuery = query,
                results = page.hits, nextAfter = page.nextAfter) }
            compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-result-${page.hits.first().location.position}"))
            compose.onNodeWithTag("search-result-${page.hits.first().location.position}").assertIsDisplayed()
            capture("search-results")
            compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-scope"))
            compose.onNodeWithTag("search-scope").performClick()
            compose.onNodeWithTag("search-book-filter").performTextReplacement("Bhagavad")
            compose.runOnIdle { compose.activity.currentFocus?.clearFocus() }
            capture("search-books")
            compose.onNodeWithTag("search-scope-cancel").performClick()
            val suggestions = engine.searchSuggestions(SearchRequest("devot", setOf(1428)))
            compose.runOnIdle { state.value = state.value.copy(query = "devot", submittedQuery = null,
                results = emptyList(), suggestions = suggestions) }
            compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-query"))
            capture("search-suggestions")
        } finally { engine.close(); db.close() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        // Compose idleness precedes native dialog/IME animations and SurfaceFlinger capture.
        // Wait for real window quiet and fence fresh frames, not just stale screenshot bytes.
        instrument.uiAutomation.waitForIdle(500, 5_000)
        val frames = java.util.concurrent.CountDownLatch(1)
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            decor.invalidate()
            decor.postOnAnimation { decor.postOnAnimation { frames.countDown() } }
        }
        check(frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val target = File(instrument.targetContext.cacheDir, "$name.png")
        val bitmap = requireNotNull(instrument.uiAutomation.takeScreenshot())
        try { target.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}