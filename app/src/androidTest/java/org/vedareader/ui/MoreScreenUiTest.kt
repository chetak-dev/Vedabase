package org.vedareader.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.LibraryInfo

/** Presentation-only route contracts; no activity model, corpus, store, or device settings. */
@RunWith(AndroidJUnit4::class)
class MoreScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo("active", "Synthetic library", "en", "synthetic.ivd", 0, 0, "")

    @Test
    fun fullPageCardsDispatchDistinctTabsAndNavigationRemainsAvailable() {
        val tabs = mutableListOf<Int>()
        var backs = 0
        var homes = 0
        var searches = 0
        compose.setContent {
            VedaPreviewTheme {
                MoreScreen(library, { backs++ }, { homes++ }, { searches++ }, { tabs += it })
            }
        }
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-sheet").assertDoesNotExist()
        compose.onNodeWithTag("more-title").assertTextEquals(label(R.string.more_title))
        compose.onNodeWithTag("more-nav-bookmarks").assertIsSelected().assertTextContains(label(R.string.more_title))
        compose.onNodeWithTag("more-nav-library").assertIsNotSelected()
        compose.onNodeWithTag("more-nav-search").assertIsNotSelected()
        listOf("more-bookmarks", "more-highlights", "more-notes").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
            assertTarget(tag)
        }
        compose.onNodeWithTag("more-back").performClick()
        compose.onNodeWithTag("more-nav-library").performClick()
        compose.onNodeWithTag("more-nav-search").performClick()
        compose.onNodeWithTag("more-nav-bookmarks").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0, 1, 2), tabs)
            assertEquals(1, backs)
            assertEquals(1, homes)
            assertEquals(1, searches)
        }
    }

    @Test
    fun noLibraryStillOffersDestinations() {
        var active by mutableStateOf<LibraryInfo?>(null)
        val tabs = mutableListOf<Int>()
        compose.setContent {
            VedaPreviewTheme { MoreScreen(active, {}, {}, {}, { tabs += it }) }
        }
        compose.onNodeWithTag("more-library").assertTextEquals(label(R.string.bookmark_no_library))
        compose.onNodeWithTag("more-notes").performScrollTo().performClick()
        compose.runOnIdle { active = library }
        compose.runOnIdle { assertEquals(listOf(2), tabs) }
    }

    @Test
    fun largeFontCardsScrollWithoutTruncatedCopyAndEveryRouteHas48dpTarget() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                VedaPreviewTheme {
                    Box(Modifier.widthIn(max = 320.dp).fillMaxHeight()) {
                        MoreScreen(library, {}, {}, {}, {})
                    }
                }
            }
        }
        listOf("more-bookmarks" to R.string.more_bookmarks_body,
            "more-highlights" to R.string.more_highlights_body,
            "more-notes" to R.string.more_notes_body).forEach { (tag, body) ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertTarget(tag)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label(body), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse(layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
            }
        }
        listOf("more-back", "more-nav-library", "more-nav-search", "more-nav-bookmarks").forEach(::assertTarget)
    }

    @Test
    fun homeMoreCallsNewCallbackImmediately() {
        var more = 0
        var legacy = 0
        compose.setContent {
            VedaPreviewTheme {
                LibraryScreen(LibraryUiState(loading = false, active = library),
                    onBookmarks = { legacy++ }, onMore = { more++ })
            }
        }
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("more-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, more); assertEquals(0, legacy) }
    }

    @Test
    fun searchMorePrefersRouteCallbackAndRestoresSavedScrollAfterReturning() {
        var route by mutableStateOf("search")
        var legacy = 0
        val state = SearchUiState(libraryId = library.id, libraryTitle = library.title,
            recent = (0..29).map { "Synthetic previous query $it" })
        compose.setContent {
            VedaPreviewTheme {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(route) {
                    if (route == "search") SearchFixture(state, onMore = { route = "more" },
                        onLegacy = { legacy++ })
                    else MoreScreen(library, { route = "search" }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-recent-20"))
        val before = compose.onNodeWithTag("search-recent-20").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("search-nav-bookmarks").performClick()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-sheet").assertDoesNotExist()
        compose.onNodeWithTag("more-back").performClick()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        val after = compose.onNodeWithTag("search-recent-20").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, after.top.value, .5f)
        compose.runOnIdle { assertEquals(0, legacy) }
    }

    @Test
    fun searchLegacyMoreFallbackOpensBookmarksDirectlyWithoutSheet() {
        val opened = mutableListOf<Int>()
        compose.setContent {
            VedaPreviewTheme { SearchFixture(SearchUiState(libraryId = library.id), onLegacy = { opened += it }) }
        }
        compose.onNodeWithTag("search-nav-bookmarks").performClick()
        compose.onNodeWithTag("more-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(0), opened) }
    }

    @Composable
    private fun SearchFixture(state: SearchUiState, onMore: (() -> Unit)? = null,
        onLegacy: (Int) -> Unit = {}) {
        SearchScreen(state, onBack = {}, onQuery = {}, onBooks = {}, onMode = {}, onSearch = {},
            onSuggestion = {}, onOpenHit = {}, onLoadMore = {}, onRetryCatalog = {}, onCancel = {},
            onClearHistory = {}, onHome = {}, onBookmarks = onLegacy, onMore = onMore)
    }

    private fun assertTarget(tag: String) {
        val bounds = compose.onNodeWithTag(tag).assertHasClickAction().getUnclippedBoundsInRoot()
        assertTrue("$tag width", bounds.right.value - bounds.left.value >= 47.9f)
        assertTrue("$tag height", bounds.bottom.value - bounds.top.value >= 47.9f)
    }

    private fun label(id: Int) = compose.activity.getString(id)
}