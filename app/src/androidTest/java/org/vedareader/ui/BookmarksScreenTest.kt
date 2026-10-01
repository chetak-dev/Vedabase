package org.vedareader.ui

import android.annotation.TargetApi
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkIssue
import org.vedareader.data.LibraryInfo
import org.vedareader.data.ReadingLocation
import java.text.DateFormat
import java.util.Date

/** Synthetic UI contracts only. No model, repository, imports, real corpus, or device settings. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class BookmarksScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo("active", "Synthetic library — कृष्ण", "en", "synthetic.ivd", 0, 0, "")
    private val otherLibrary = library.copy(id = "other", title = "Other synthetic library")
    private val older = bookmark("older", title = "BG 2.8", book = "Bhagavad-gītā", excerpt = "A quiet synthetic passage.")
    private val newer = bookmark("newer", title = "ŚB 1.2.3", book = "Śrīmad-Bhāgavatam",
        excerpt = "Kṛṣṇa कृष्ण 🌿 <b>literal, not HTML</b>", createdAt = 1_700_086_400_000L)
    private val foreign = bookmark("foreign", libraryId = "other", title = "Other reference",
        book = "Private other-library book", excerpt = "Other-library-only excerpt.", createdAt = 1_700_172_800_000L)
    private val ready = BookmarkUiState(items = listOf(older, foreign, newer), loading = false)

    @Test
    fun noActiveLibraryExplainsImportRatherThanExposingStoredItemsOrInventingEmptyState() {
        val fixture = show(ready.copy(loading = true, busy = true, error = BookmarkIssue.LOAD), activeLibrary = null)
        compose.onNodeWithTag("bookmarks-import").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-library").assertTextEquals(label(R.string.bookmark_no_library))
        compose.onNodeWithTag("bookmarks-filter").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-empty").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-loading").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-error").assertDoesNotExist()
        for (item in ready.items) compose.onNodeWithTag("bookmark-open-${item.id}").assertDoesNotExist()
        reveal("bookmarks-import-library")
        compose.onNodeWithTag("bookmarks-import-library").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.backs)
            assertEquals(ready.items, fixture.state.items)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.removed.isEmpty())
            assertTrue(fixture.acknowledged.isEmpty())
        }
    }

    @Test
    fun emptyLibraryHasReaderGuidanceAndBookmarksIsTheSelectedIconicTab() {
        show(ready.copy(items = listOf(foreign)))
        compose.onNodeWithTag("bookmarks-title").assertTextEquals(label(R.string.bookmark_title))
        compose.onNodeWithTag("bookmarks-library").assertTextEquals(library.title)
        compose.onNodeWithTag("bookmarks-empty").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.bookmark_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.bookmark_empty_body)).assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-filter-empty").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-nav-bookmarks").assertIsSelected()
            .assertTextContains(label(R.string.more_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithTag("bookmarks-nav-library").assertIsNotSelected()
        compose.onNodeWithTag("bookmarks-nav-search").assertIsNotSelected()
    }

    @Test
    fun navigationOnlyForwardsCallbacksAndNeverOpensOrRemovesAnything() {
        val fixture = show(ready)
        compose.onNodeWithTag("bookmarks-back").assertContentDescriptionEquals(label(R.string.back)).performClick()
        compose.onNodeWithTag("bookmarks-nav-library").performClick()
        compose.onNodeWithTag("bookmarks-nav-search").performClick()
        compose.onNodeWithTag("bookmarks-nav-bookmarks").performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.backs)
            assertEquals(1, fixture.searches)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.removed.isEmpty())
            assertEquals(ready.items, fixture.state.items)
        }
    }

    @Test
    fun loadingBusyAndErrorsNeverPretendThereAreNoBookmarksOrAcknowledgeAnError() {
        val fixture = show(BookmarkUiState())
        compose.onNodeWithTag("bookmarks-loading").assertIsDisplayed()
        assertNoEmptyNotice()
        compose.runOnIdle { fixture.state = BookmarkUiState(loading = false, busy = true) }
        compose.onNodeWithTag("bookmarks-busy").assertIsDisplayed()
        assertNoEmptyNotice()
        for ((issue, resource) in listOf(BookmarkIssue.LOAD to R.string.bookmark_load_error,
            BookmarkIssue.SAVE to R.string.bookmark_save_error, BookmarkIssue.LIMIT to R.string.bookmark_limit_error)) {
            compose.runOnIdle { fixture.state = BookmarkUiState(loading = false, error = issue) }
            compose.onNodeWithTag("bookmarks-error").assertIsDisplayed()
            compose.onNodeWithText(label(resource)).assertIsDisplayed()
            assertNoEmptyNotice()
            compose.onNodeWithTag("bookmarks-retry").assertIsEnabled().performClick()
        }
        compose.runOnIdle {
            assertEquals(3, fixture.retries)
            assertTrue(fixture.acknowledged.isEmpty())
            assertTrue(fixture.removed.isEmpty())
            fixture.state = fixture.state.copy(busy = true)
        }
        compose.onNodeWithTag("bookmarks-retry").assertIsNotEnabled()
        assertNoEmptyNotice()
    }

    @Test
    fun activeLibraryOnlyAndNewestFirstWithoutModifyingTheAllLibraryStore() {
        val fixture = show(ready)
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertDoesNotExist()
        val newestBounds = compose.onNodeWithTag("bookmark-row-${newer.id}").getUnclippedBoundsInRoot()
        val olderBounds = compose.onNodeWithTag("bookmark-row-${older.id}").getUnclippedBoundsInRoot()
        assertTrue("Newest entry must be first", newestBounds.top < olderBounds.top)
        compose.runOnIdle { fixture.library = otherLibrary }
        compose.onNodeWithTag("bookmarks-library").assertTextEquals(otherLibrary.title)
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertDoesNotExist()
        compose.runOnIdle { fixture.library = library }
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertExists()
        compose.runOnIdle { assertSame(ready.items, fixture.state.items) }
    }

    @Test
    fun filterFoldsAccentsAcrossBookReferenceAndExcerptButNeverSearchesAnotherLibrary() {
        val fixture = show(ready)
        filter("bhagavad-gita")
        compose.onNodeWithTag("bookmark-open-${older.id}").assertExists()
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertDoesNotExist()
        filter("sb 1.2.3")
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertExists()
        compose.onNodeWithTag("bookmark-open-${older.id}").assertDoesNotExist()
        filter("KRSNA")
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertExists()
        filter("कृष्ण")
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertExists()
        filter("Other-library-only")
        compose.onNodeWithTag("bookmarks-filter-empty").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-empty").assertDoesNotExist()
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertDoesNotExist()
        reveal("bookmarks-reset-filter")
        compose.onNodeWithTag("bookmarks-reset-filter").performClick()
        assertEditor("", TextRange(0))
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertExists()
        compose.runOnIdle { assertEquals(ready.items, fixture.state.items); assertEquals(0, fixture.searches) }
    }

    @Test
    fun originalUnicodeTitlesAndLocalizedDateTimeAreShownWithoutExcerptAndOpenForwardsTheOriginalObject() {
        val fixture = show(ready.copy(items = listOf(newer)))
        compose.onNodeWithTag("bookmark-book-${newer.id}", useUnmergedTree = true).assertTextEquals(newer.bookTitle)
        compose.onNodeWithTag("bookmark-location-${newer.id}", useUnmergedTree = true).assertTextEquals(newer.location.title)
        // The saved excerpt stays in the model for filtering but is no longer shown as a text card.
        compose.onNodeWithTag("bookmark-excerpt-${newer.id}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(newer.excerpt, substring = true).assertDoesNotExist()
        val expectedDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
            compose.activity.resources.configuration.locales[0]).format(Date(newer.createdAt))
        compose.onNodeWithTag("bookmark-date-${newer.id}", useUnmergedTree = true)
            .assertTextEquals(label(R.string.bookmark_saved_date, expectedDate))
        compose.onNodeWithTag("bookmark-open-${newer.id}").performClick()
        compose.runOnIdle { assertSame(newer, fixture.opened.single()); assertTrue(fixture.removed.isEmpty()) }
    }

    @Test
    fun duplicateTitlesStillIdentifyIndependentRowsByStableBookmarkId() {
        val second = newer.copy(id = "same-title-different-id", createdAt = older.createdAt)
        val fixture = show(ready.copy(items = listOf(newer, second)))
        reveal("bookmark-open-${second.id}")
        compose.onNodeWithTag("bookmark-open-${second.id}").performClick()
        compose.runOnIdle { assertSame(second, fixture.opened.single()) }
        openRemoval(second)
        compose.onNodeWithTag("bookmarks-remove-confirm").performClick()
        compose.runOnIdle { assertSame(second, fixture.removed.single()) }
    }

    @Test
    fun removeRequiresConfirmationCancelAndReopenDoNotRemoveAndOnlyOriginalIsForwarded() {
        val fixture = show(ready.copy(items = listOf(newer)))
        openRemoval(newer)
        compose.onNodeWithText(label(R.string.bookmark_remove_body, newer.location.title, newer.bookTitle)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()); assertTrue(fixture.opened.isEmpty()) }
        compose.onNodeWithTag("bookmarks-remove-cancel").performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        openRemoval(newer)
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()) }
        compose.onNodeWithTag("bookmarks-remove-confirm").performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(newer, fixture.removed.single())
            assertTrue(fixture.opened.isEmpty())
            // This presentation does not optimistically mutate the owner's store.
            assertEquals(listOf(newer), fixture.state.items)
        }
    }

    @Test
    fun twoConfirmEventsBeforeRecompositionStillForwardOnlyOneRemoval() {
        val fixture = show(ready.copy(items = listOf(newer)))
        openRemoval(newer)
        val confirm = requireNotNull(compose.onNodeWithTag("bookmarks-remove-confirm")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { confirm(); confirm() }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { assertSame(newer, fixture.removed.single()) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun nativeBackAfterReopeningDialogCancelsOnlyTheModalThenRootBackNavigates() {
        val fixture = show(ready.copy(items = listOf(newer)))
        filter("krsna") // Open confirmation after actual editor focus/IME use.
        openRemoval(newer)
        compose.onNodeWithTag("bookmarks-remove-cancel").performClick()
        openRemoval(newer)
        waitForNativeFocus(dialog = true)
        nativeBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("bookmarks-remove-dialog").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()); assertEquals(0, fixture.backs) }
        waitForNativeFocus(dialog = false)
        nativeBack()
        compose.runOnIdle { assertEquals(1, fixture.backs); assertTrue(fixture.removed.isEmpty()) }
    }

    @Test
    fun librarySwitchClosesConfirmationEvenWithTheSameIdAndNeverResurrectsIt() {
        val sameIdElsewhere = newer.copy(location = newer.location.copy(libraryId = otherLibrary.id))
        val fixture = show(ready.copy(items = listOf(newer, sameIdElsewhere)))
        openRemoval(newer)
        compose.runOnIdle { fixture.library = otherLibrary }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { fixture.library = library }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { fixture.library = null }
        compose.onNodeWithTag("bookmarks-import").assertExists()
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()); assertEquals(0, fixture.backs) }
    }

    @Test
    fun disappearedOrReplacedItemInvalidatesPendingConfirmationRatherThanRemovingStaleData() {
        val fixture = show(ready.copy(items = listOf(newer)))
        openRemoval(newer)
        compose.runOnIdle { fixture.state = fixture.state.copy(items = emptyList()) }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { fixture.state = fixture.state.copy(items = listOf(newer)) }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        openRemoval(newer)
        val replacement = newer.copy(excerpt = "A changed snapshot of the same id")
        compose.runOnIdle { fixture.state = fixture.state.copy(items = listOf(replacement)) }
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()) }
        openRemoval(replacement)
        compose.onNodeWithTag("bookmarks-remove-confirm").performClick()
        compose.runOnIdle { assertSame(replacement, fixture.removed.single()) }
    }

    @Test
    fun busyLocksOpeningDeletingAndPendingConfirmationButCancelRemainsAvailable() {
        val fixture = show(ready.copy(items = listOf(newer), busy = true))
        compose.onNodeWithTag("bookmarks-busy").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("bookmark-remove-${newer.id}").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.opened.isEmpty()); assertTrue(fixture.removed.isEmpty()); fixture.state = fixture.state.copy(busy = false) }
        openRemoval(newer)
        compose.runOnIdle { fixture.state = fixture.state.copy(busy = true) }
        compose.onNodeWithTag("bookmarks-remove-confirm").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("bookmarks-remove-cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()) }
    }

    @Test
    fun loadOrErrorArrivingDuringConfirmationBlocksRemovalWithoutAcknowledgingIt() {
        val fixture = show(ready.copy(items = listOf(newer)))
        openRemoval(newer)
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = true) }
        compose.onNodeWithTag("bookmarks-remove-confirm").assertIsNotEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = false, error = BookmarkIssue.LOAD) }
        compose.onNodeWithTag("bookmarks-remove-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("bookmarks-remove-cancel").performClick()
        reveal("bookmark-open-${newer.id}")
        compose.onNodeWithTag("bookmark-open-${newer.id}").assertIsNotEnabled()
        compose.onNodeWithTag("bookmark-remove-${newer.id}").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()); assertTrue(fixture.acknowledged.isEmpty()) }
    }

    @Test
    fun characterByCharacterFilterAndMiddleCaretSurviveStoreAndBusyUpdates() {
        val fixture = show(ready, interceptIme = true)
        var expected = ""
        for (letter in "Kṛṣṇa!") {
            compose.onNodeWithTag("bookmarks-filter").performTextInput(letter.toString())
            expected += letter
            assertEditor(expected, TextRange(expected.length))
            compose.runOnIdle { fixture.state = fixture.state.copy(busy = !fixture.state.busy, items = fixture.state.items.reversed()) }
            assertEditor(expected, TextRange(expected.length))
            compose.onNodeWithTag("bookmarks-filter").assertIsFocused()
        }
        compose.onNodeWithTag("bookmarks-filter").performTextReplacement("BG 28")
        compose.onNodeWithTag("bookmarks-filter").performTextInputSelection(TextRange(4))
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = true) }
        assertEditor("BG 28", TextRange(4))
        compose.onNodeWithTag("bookmarks-filter").performTextInput(".")
        assertEditor("BG 2.8", TextRange(5))
        compose.runOnIdle { assertEquals(0, fixture.searches); assertTrue(fixture.opened.isEmpty()) }
    }

    @Test
    fun composingFilterSurvivesChangingResultsWithoutRestartingTheInputConnection() {
        val fixture = show(ready, interceptIme = true)
        compose.onNodeWithTag("bookmarks-filter").performClick()
        compose.waitForIdle()
        val connection = compose.runOnIdle { requireNotNull(fixture.inputConnection) }
        val sessions = compose.runOnIdle { fixture.inputSessions }
        compose.runOnIdle { connection.setComposingText("k", 1) }
        assertEditor("k", TextRange(1))
        compose.runOnIdle { fixture.state = fixture.state.copy(items = listOf(newer), busy = true) }
        compose.runOnIdle { connection.setComposingText("kṛṣ", 1) }
        assertEditor("kṛṣ", TextRange(3))
        compose.runOnIdle { fixture.state = fixture.state.copy(items = ready.items, busy = false) }
        compose.runOnIdle { connection.commitText("kṛṣṇa", 1) }
        assertEditor("kṛṣṇa", TextRange(5))
        compose.runOnIdle { assertSame(connection, fixture.inputConnection); assertEquals(sessions, fixture.inputSessions) }
    }

    @Test
    fun filterAndSelectionRestoreAfterLeavingScreenButLibrarySwitchResetsOnlyLocalUi() {
        val fixture = show(ready, interceptIme = true)
        compose.onNodeWithTag("bookmarks-filter").performTextInput("krsna")
        compose.onNodeWithTag("bookmarks-filter").performTextInputSelection(TextRange(1, 3))
        compose.runOnIdle { fixture.visible = false }
        compose.onNodeWithTag("return-bookmarks").performClick()
        // Leaving a focused editor collapses Android's selection to its end. Preserve that
        // caret and the filter rather than demanding an unfocused selection overlay.
        assertEditor("krsna", TextRange(3))
        compose.runOnIdle { fixture.library = otherLibrary }
        assertEditor("", TextRange(0))
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertExists()
        compose.runOnIdle { assertSame(ready.items, fixture.state.items) }
    }

    @Test
    fun recreationPreservesFilterButDoesNotRestoreDestructiveConfirmation() {
        val restoration = StateRestorationTester(compose)
        val fixture = show(ready.copy(items = listOf(newer)), restoration = restoration)
        filter("krsna")
        openRemoval(newer)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-filter").assertTextContains("krsna")
        compose.runOnIdle { assertTrue(fixture.removed.isEmpty()) }
    }

    @Test
    fun accessibleTargetsAreLargeLabeledAndDeletionIsNotNestedInsideOpen() {
        show(ready.copy(items = listOf(newer)))
        for (tag in listOf("bookmarks-back", "bookmarks-nav-library", "bookmarks-nav-search", "bookmarks-nav-bookmarks",
            "bookmark-open-${newer.id}", "bookmark-remove-${newer.id}")) {
            assertTouchTarget(tag)
        }
        compose.onNodeWithTag("bookmark-row-${newer.id}").assertHasNoClickAction()
        compose.onNodeWithTag("bookmark-open-${newer.id}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextContains(newer.bookTitle).assertTextContains(newer.location.title)
        compose.onNodeWithTag("bookmark-open-${newer.id}").assert(!hasText(newer.excerpt, substring = true))
        val openAction = compose.onNodeWithTag("bookmark-open-${newer.id}").fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals(label(R.string.bookmark_open_location, newer.location.title, newer.bookTitle), openAction.label)
        compose.onNodeWithTag("bookmark-remove-${newer.id}").assertContentDescriptionEquals(
            label(R.string.bookmark_remove_location, newer.location.title, newer.bookTitle))
        compose.onAllNodes(hasTestTag("bookmark-remove-${newer.id}") and hasAnyAncestor(hasTestTag("bookmark-open-${newer.id}")),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("bookmarks-filter").performTextInput("x")
        compose.onNodeWithTag("bookmarks-filter-clear").assertContentDescriptionEquals(label(R.string.bookmark_filter_clear))
        assertTouchTarget("bookmarks-filter-clear")
        compose.onNodeWithTag("bookmarks-filter-clear").performClick()
        compose.onNodeWithTag("bookmarks-filter").performImeAction()
        openRemoval(newer)
        assertTouchTarget("bookmarks-remove-confirm")
        assertTouchTarget("bookmarks-remove-cancel")
    }

    @Test
    fun largeFontsOnNarrowWidthKeepNavigationLabelsUnclippedAndDeleteReachable() {
        val compact = newer.copy(bookTitle = "Synthetic book", location = newer.location.copy(title = "BG 2.8"), excerpt = "कृष्ण 🌿")
        show(ready.copy(items = listOf(compact)), fontScale = 2f, narrow = true)
        for (tag in listOf("bookmarks-back", "bookmarks-nav-library", "bookmarks-nav-search", "bookmarks-nav-bookmarks")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertTouchTarget(tag)
        }
        for (text in listOf(label(R.string.library), label(R.string.search))) assertTextFits(
            compose.onNodeWithText(text, useUnmergedTree = true))
        assertTextFits(compose.onNode(hasText(label(R.string.more_title)) and
            hasAnyAncestor(hasTestTag("bookmarks-nav-bookmarks")), useUnmergedTree = true))
        compose.onNodeWithTag("bookmarks-filter").performTextInput("BG")
        compose.onNodeWithTag("bookmarks-back").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-filter").performImeAction()
        reveal("bookmark-remove-${compact.id}")
        compose.onNodeWithTag("bookmark-remove-${compact.id}").assertIsDisplayed()
        assertTouchTarget("bookmark-remove-${compact.id}")
        compose.onNodeWithTag("bookmark-remove-${compact.id}").performClick()
        compose.onNodeWithTag("bookmarks-remove-confirm").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-remove-cancel").assertIsDisplayed().performClick()
    }

    @Test
    fun feedbackEnumUsesResourcesIncludingReaderLabels() {
        assertEquals(R.string.bookmark_saved, bookmarkMessageResource(BookmarkMessage.SAVED))
        assertEquals(R.string.bookmark_removed, bookmarkMessageResource(BookmarkMessage.REMOVED))
        assertEquals(R.string.bookmark_load_error, bookmarkMessageResource(BookmarkMessage.LOAD_FAILED))
        assertEquals(R.string.bookmark_save_error, bookmarkMessageResource(BookmarkMessage.SAVE_FAILED))
        assertEquals(R.string.bookmark_limit_error, bookmarkMessageResource(BookmarkMessage.LIMIT_REACHED))
        assertEquals("Bookmark this position", label(R.string.bookmark_add))
        assertEquals("Remove bookmark", label(R.string.bookmark_remove))
        assertEquals("Bookmarked", label(R.string.bookmark_marked_state))
        assertEquals("Not bookmarked", label(R.string.bookmark_unmarked_state))
        assertEquals("Saving bookmark…", label(R.string.bookmark_saving))
        assertEquals("Open bookmarks", label(R.string.bookmark_list_open))
    }

    @Test
    fun screenFeedbackIsShownInItsSnackbarWithoutImmediatelyAcknowledging() {
        val fixture = show(ready.copy(feedback = BookmarkFeedback(41, BookmarkMessage.SAVED)))
        compose.onNodeWithTag("bookmarks-snackbar").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.bookmark_saved)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.acknowledged.isEmpty()) }
    }

    @Test
    fun feedbackAcknowledgesOnlyAfterSnackbarFinishesUsingTheLatestCallback() {
        val host = SnackbarHostState()
        val first = mutableListOf<Long>()
        val latest = mutableListOf<Long>()
        var callback by mutableStateOf<(Long) -> Unit>({ first += it })
        compose.setContent {
            VedaPreviewTheme {
                SnackbarHost(host)
                BookmarkFeedbackEffect(BookmarkFeedback(42, BookmarkMessage.SAVED), host, callback)
            }
        }
        compose.onNodeWithText(label(R.string.bookmark_saved)).assertIsDisplayed()
        val data = compose.runOnIdle { requireNotNull(host.currentSnackbarData) }
        compose.runOnIdle {
            assertTrue(first.isEmpty())
            assertEquals(SnackbarDuration.Short, data.visuals.duration)
            callback = { latest += it }
        }
        compose.runOnIdle { assertSame(data, host.currentSnackbarData); data.dismiss() }
        compose.runOnIdle { assertTrue(first.isEmpty()); assertEquals(listOf(42L), latest) }
    }

    @Test
    fun disposingOrReplacingFeedbackDoesNotAcknowledgeAnUnfinishedSnackbar() {
        val host = SnackbarHostState()
        val acknowledged = mutableListOf<Long>()
        var visible by mutableStateOf(true)
        var feedback by mutableStateOf(BookmarkFeedback(51, BookmarkMessage.LOAD_FAILED))
        compose.setContent {
            VedaPreviewTheme {
                SnackbarHost(host)
                if (visible) BookmarkFeedbackEffect(feedback, host) { acknowledged += it }
            }
        }
        compose.onNodeWithText(label(R.string.bookmark_load_error)).assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertTrue(acknowledged.isEmpty()); assertEquals(null, host.currentSnackbarData); visible = true }
        compose.onNodeWithText(label(R.string.bookmark_load_error)).assertIsDisplayed()
        compose.runOnIdle { feedback = BookmarkFeedback(52, BookmarkMessage.REMOVED) }
        compose.onNodeWithText(label(R.string.bookmark_removed)).assertIsDisplayed()
        compose.runOnIdle { assertTrue(acknowledged.isEmpty()); requireNotNull(host.currentSnackbarData).dismiss() }
        compose.runOnIdle { assertEquals(listOf(52L), acknowledged) }
    }

    private fun bookmark(id: String, libraryId: String = library.id, title: String, book: String, excerpt: String,
        createdAt: Long = 1_700_000_000_000L) = Bookmark(
        id = id,
        location = ReadingLocation(libraryId, title, 100, 199, 123, 17),
        bookRecord = 10, bookTitle = book, excerpt = excerpt, createdAt = createdAt,
    )

    private fun show(initial: BookmarkUiState, activeLibrary: LibraryInfo? = library, fontScale: Float = 1f,
        narrow: Boolean = false, interceptIme: Boolean = false,
        restoration: StateRestorationTester = StateRestorationTester(compose)): Fixture {
        val fixture = Fixture(initial, activeLibrary)
        val interceptor = PlatformTextInputInterceptor { request, _ ->
            fixture.inputConnection = request.createInputConnection(EditorInfo())
            fixture.inputSessions++
            awaitCancellation()
        }
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                VedaPreviewTheme {
                    Box(if (narrow) Modifier.widthIn(max = 320.dp).fillMaxHeight() else Modifier) {
                        val screens = rememberSaveableStateHolder()
                        if (fixture.visible) screens.SaveableStateProvider("bookmarks") {
                            BookmarksScreen(fixture.state, fixture.library,
                                onBack = { fixture.backs++ }, onSearch = { fixture.searches++ },
                                onOpen = { fixture.opened += it }, onRemove = { fixture.removed += it },
                                onRetry = { fixture.retries++ }, onFeedbackShown = { fixture.acknowledged += it })
                        } else TextButton(onClick = { fixture.visible = true }, modifier = Modifier.testTag("return-bookmarks")) {
                            Text("Return to synthetic bookmarks")
                        }
                    }
                }
            }
        }
        restoration.setContent { if (interceptIme) InterceptPlatformTextInput(interceptor, content) else content() }
        return fixture
    }

    private fun filter(text: String) {
        compose.onNodeWithTag("bookmarks-filter").performTextReplacement(text)
        compose.onNodeWithTag("bookmarks-filter").performImeAction()
        compose.waitForIdle()
    }

    private fun openRemoval(bookmark: Bookmark) {
        reveal("bookmark-remove-${bookmark.id}")
        compose.onNodeWithTag("bookmark-remove-${bookmark.id}").performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertIsDisplayed()
    }

    private fun reveal(tag: String) = compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag(tag))

    private fun assertNoEmptyNotice() {
        compose.onNodeWithTag("bookmarks-empty").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-filter-empty").assertDoesNotExist()
    }

    private fun assertEditor(text: String, selection: TextRange) {
        val editor = compose.onNodeWithTag("bookmarks-filter")
        editor.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        assertEquals(text, editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun assertTouchTarget(tag: String) {
        val node = compose.onNodeWithTag(tag).assertHasClickAction()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$tag width", (bounds.right - bounds.left).value >= 47.9f)
        assertTrue("$tag height", (bounds.bottom - bounds.top).value >= 47.9f)
    }

    private fun assertTextFits(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { result ->
            assertFalse("Text must not be vertically clipped", result.didOverflowHeight)
            for (line in 0 until result.lineCount) {
                assertFalse("Tab labels must not be ellipsized", result.isLineEllipsized(line))
                assertTrue("Line left", result.getLineLeft(line) >= -1f)
                assertTrue("Line right", result.getLineRight(line) <= result.size.width + 1f)
            }
        }
    }

    @TargetApi(29)
    private fun waitForNativeFocus(dialog: Boolean) {
        // Compose idleness alone does not mean a newly reopened Android dialog owns Back.
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                val activityDecor = compose.activity.window.decorView
                if (dialog) WindowInspector.getGlobalWindowViews().any { it !== activityDecor && it.hasWindowFocus() }
                else activityDecor.hasWindowFocus()
            }
        }
    }

    private fun nativeBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        compose.waitForIdle()
    }

    private fun label(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)

    private class Fixture(initial: BookmarkUiState, activeLibrary: LibraryInfo?) {
        var state by mutableStateOf(initial)
        var library by mutableStateOf(activeLibrary)
        var visible by mutableStateOf(true)
        var backs = 0
        var searches = 0
        var retries = 0
        val opened = mutableListOf<Bookmark>()
        val removed = mutableListOf<Bookmark>()
        val acknowledged = mutableListOf<Long>()
        var inputConnection: InputConnection? = null
        var inputSessions = 0
    }
}