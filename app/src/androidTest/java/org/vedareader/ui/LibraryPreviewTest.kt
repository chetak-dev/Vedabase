package org.vedareader.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.ImportProgress
import org.vedareader.data.ImportStage
import org.vedareader.data.LibraryInfo

/** Checkpoint UI using synthetic state only; no documents or original app data are accessed. */
class LibraryPreviewTest {
    @get:Rule
    val compose = createComposeRule()
    private val library = LibraryInfo("fixture", "Synthetic library", "en", "fixture.ivd", 32768, 3, "Fixture data only")
    private val collections = listOf(
        ContentNode(10, 0, "01. Synthetic book", 19, 2, 1, "bgaiiwa"),
        ContentNode(20, 0, "02. नमूना पुस्तक", 29, 1, 1, "sbbp"),
        ContentNode(30, 0, "03. Synthetic letters", 39, 3, 1, "cl"),
    )
    private val state = mutableStateOf(LibraryUiState(loading = false))
    private val chosenLanguages = mutableListOf<String>()
    private val selectedLibraries = mutableListOf<String>()
    private var cancelled = false
    private var retried = false
    private var searches = 0
    private val bookmarkTabs = mutableListOf<Int>()
    private var moreOpens = 0

    @Before
    fun showPreview() {
        state.value = LibraryUiState(loading = false, libraries = listOf(library), active = library, collections = collections)
        compose.setContent {
            VedaPreviewTheme {
                LibraryScreen(state.value, onChooseDocument = { chosenLanguages += it },
                    onSelectLibrary = { selectedLibraries += it },
                    onCancelImport = { cancelled = true }, onRetry = { retried = true },
                    onDismissError = { state.value = state.value.copy(error = null) }, onSearch = { searches++ },
                    onBookmarks = { bookmarkTabs += it }, onMore = { moreOpens++ })
            }
        }
    }

    @Test
    fun informationKeepsSourceDetailsAndLibraryActionsOffTheHomePage() {
        compose.onNodeWithText("Your library").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(library.title).assertDoesNotExist()
        compose.onNodeWithText(library.copyright).assertDoesNotExist()
        compose.onNodeWithText("Add library").assertDoesNotExist()
        compose.onNodeWithText("Choose existing library").assertDoesNotExist()
        compose.onNodeWithContentDescription("About your library").performClick()
        compose.onNodeWithText("Your library, on this device").assertIsDisplayed()
        compose.onNodeWithText(library.title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(library.copyright).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose existing library").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add library").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Your library").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(chosenLanguages.isEmpty())
            assertTrue(selectedLibraries.isEmpty())
        }
    }

    @Test
    fun homeShowsOnlyCollectionTitlesAndMonogramsWithoutFeaturedCopyCountsOrLanguageBadge() {
        compose.onAllNodesWithText(label(R.string.your_library), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(label(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithTag("library-search-entry").assertIsDisplayed()
        compose.onNodeWithTag("category-filter-row").assertDoesNotExist()
        for (resource in listOf(
            R.string.app_tagline, R.string.library_intro, R.string.offline_library,
            R.string.preview_eyebrow, R.string.preview_featured, R.string.library_featured,
            R.string.explore_collection, R.string.preview_collections,
            R.string.library_checkpoint_notice, R.string.library_footer, R.string.privacy_hint,
            R.string.preview_language, R.string.library_default_language, R.string.library_offline,
            R.string.about_description, R.string.library_info_body, R.string.library_page_entry,
        )) {
            compose.onNodeWithText(label(resource)).assertDoesNotExist()
        }
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        compose.onNodeWithText(resources.getQuantityString(R.plurals.library_collection_count, collections.size, collections.size))
            .assertDoesNotExist()
        compose.onNodeWithText(label(R.string.collection_count, collections.size, "English")).assertDoesNotExist()
        compose.onNodeWithText(library.title).assertDoesNotExist()
        compose.onNodeWithTag("read-section").assertDoesNotExist()

        val subtitles = listOf(label(R.string.home_subtitle_gita), label(R.string.home_subtitle_bhagavatam), null)
        for ((index, pair) in collections.zip(listOf("BG", "SB", "LT")).withIndex()) {
            val (node, monogram) = pair
            compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("collection-${node.record}"))
            // The decorative monogram and a fixed subtitle are allowed; any count, duplicated title, or description is not.
            val expected = listOfNotNull(monogram, node.displayTitle, subtitles[index]).toTypedArray()
            compose.onNodeWithTag("collection-${node.record}").assertIsDisplayed().assertTextEquals(*expected)
            compose.onAllNodesWithText(node.displayTitle, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText(resources.getQuantityString(R.plurals.library_section_count, node.children, node.children))
                .assertDoesNotExist()
        }
        compose.onNodeWithText(label(R.string.library_footer)).assertDoesNotExist()
    }

    @Test
    fun principalScripturesAreTilesAndOtherCollectionsListedBelowWithASearchEntry() {
        compose.onNodeWithText(label(R.string.home_featured)).assertIsDisplayed()
        compose.onNodeWithTag("collection-10").assertIsDisplayed()
        compose.onNodeWithTag("collection-20").assertIsDisplayed()
        compose.onNodeWithTag("library-list").performScrollToNode(hasText(label(R.string.home_group_archive)))
        compose.onNodeWithText(label(R.string.home_group_archive)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.home_collections)).assertDoesNotExist()
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("collection-30"))
        compose.onNodeWithTag("collection-30").assertIsDisplayed().assertTextEquals("LT", collections[2].displayTitle)
        compose.onNodeWithTag("library-search-entry").assertIsDisplayed()
            .assertTextEquals(label(R.string.search_ui_query_hint)).performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test
    fun searchAndBookmarksOpenTheirFeaturesWithoutPlaceholderNotices() {
        compose.onNodeWithTag("library-search").performClick()
        compose.runOnIdle { assertEquals(1, searches) }
        compose.onNodeWithText(label(R.string.library_action_message)).assertDoesNotExist()
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("more-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, moreOpens); assertTrue(bookmarkTabs.isEmpty()) }
        compose.onNodeWithText(label(R.string.library_action_message)).assertDoesNotExist()
    }

    @Test
    fun emptyLibraryHasNoSampleCardsAndChoosesDefaultEnglish() {
        compose.runOnIdle { state.value = LibraryUiState(loading = false) }
        compose.onNodeWithText(label(R.string.your_library)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithTag("choose-database").assertIsDisplayed().performClick()
        // The callback must happen on this click, with no intermediate language confirmation.
        compose.runOnIdle { assertEquals(listOf("en"), chosenLanguages) }
        assertNoLanguageDialog()
        collections.forEach { compose.onNodeWithTag("collection-${it.record}").assertDoesNotExist() }
    }

    @Test
    fun informationImportRequestsEnglishImmediatelyWithOrWithoutAnActiveLibrary() {
        for ((index, active) in listOf(library, null).withIndex()) {
            compose.runOnIdle {
                state.value = LibraryUiState(loading = false, active = active,
                    libraries = listOfNotNull(active), collections = if (active == null) emptyList() else collections)
            }
            compose.onNodeWithContentDescription("About your library").performClick()
            compose.onNodeWithText("Add library").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(List(index + 1) { "en" }, chosenLanguages) }
            compose.onNodeWithText("Your library, on this device").assertDoesNotExist()
            assertNoLanguageDialog()
        }
    }

    @Test
    fun existingLibrarySelectorOnlyShowsActualEnglishLocalesWithoutMutatingInputRegistrations() {
        val hindi = library.copy(id = "fixture-hi", title = "English-labelled Hindi fixture", languageTag = "hi")
        val regionalEnglish = library.copy(id = "fixture-en-gb", title = "Regional fixture", languageTag = "en-GB")
        val otherLanguage = library.copy(id = "fixture-enm", title = "Other language fixture", languageTag = "enm")
        val invalidLanguage = library.copy(id = "fixture-invalid", title = "Invalid locale fixture", languageTag = "en_US")
        val registrations = listOf(hindi, library, otherLanguage, regionalEnglish, invalidLanguage)
        val snapshot = registrations.toList()
        compose.runOnIdle { state.value = state.value.copy(libraries = registrations) }

        compose.onNodeWithContentDescription("About your library").performClick()
        compose.onNodeWithText("Choose existing library").performScrollTo().performClick()

        compose.onNodeWithText("Choose existing library").assertIsDisplayed()
        compose.onNodeWithText(library.title).assertIsDisplayed()
        for (hidden in listOf(hindi, otherLanguage, invalidLanguage)) {
            compose.onNodeWithText(hidden.title).assertDoesNotExist()
        }
        compose.onNodeWithText(regionalEnglish.title).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose existing library").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(regionalEnglish.id), selectedLibraries)
            assertTrue(chosenLanguages.isEmpty())
            assertSame(registrations, state.value.libraries)
            assertEquals(snapshot, registrations)
            assertEquals(snapshot, state.value.libraries)
            assertTrue(hindi in state.value.libraries)
            assertEquals(library, state.value.active)
        }
        assertNoLanguageDialog()
    }

    @Test
    fun copyingShowsProgressAndOffersCancellation() {
        compose.runOnIdle { state.value = state.value.copy(progress = ImportProgress(ImportStage.COPYING, 42)) }
        compose.onNodeWithText("Copying database · 42%").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun finishingCannotCancelCommittedLibrary() {
        compose.runOnIdle { state.value = state.value.copy(progress = ImportProgress(ImportStage.FINISHING)) }
        compose.onNodeWithText("Saving your library…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun failedReopenOffersRetryWithoutClearingExistingLibrary() {
        compose.runOnIdle { state.value = state.value.copy(error = R.string.library_load_error) }
        compose.onNodeWithText("Something needs attention").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertTrue(retried); assertEquals(library, state.value.active) }
    }

    private fun assertNoLanguageDialog() {
        compose.onNodeWithTag("content-language").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.language_title)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.choose_language)).assertDoesNotExist()
    }

    private fun label(@StringRes id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
}