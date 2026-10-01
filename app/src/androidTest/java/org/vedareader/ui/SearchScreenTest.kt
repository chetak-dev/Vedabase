package org.vedareader.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchBook
import org.vedareader.data.SearchHighlight
import org.vedareader.data.SearchIssue
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget

/** Synthetic presentation contracts only: no imports, repository, real corpus, or network. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)
class SearchScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val root = SearchBook(ContentNode(100, 0, "Synthetic collection", 999, 2, 1))
    private val first = SearchBook(ContentNode(110, 100, "First source — कृष्ण", 199, 0, 2), root.node.title)
    private val second = SearchBook(ContentNode(210, 100, "Second source", 299, 0, 2), root.node.title)
    private val separate = SearchBook(ContentNode(1100, 0, "Separate work", 1199, 0, 1))
    private val nested = SearchBook(ContentNode(120, 115, "Nested Kṛṣṇa work", 149, 0, 8), "Omitted chapter")
    private val nestedSibling = SearchBook(ContentNode(160, 110, "Nested sibling", 189, 0, 8), first.node.title)
    private val ready = SearchUiState(libraryId = "synthetic-db", libraryTitle = "Synthetic Unicode library",
        books = listOf(root, first, second, separate))
    private val hierarchical = ready.copy(books = ready.books + nested + nestedSibling)

    @Test
    fun noDatabaseExplainsImportAndBothBackActionsWork() {
        val fixture = show(SearchUiState())
        compose.onNodeWithTag("search-import").assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertDoesNotExist()
        compose.onNodeWithTag("search-submit").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.search_ui_back_to_library)).performClick()
        compose.onNodeWithTag("search-back").assertContentDescriptionEquals(label(R.string.search_ui_back)).performClick()
        compose.runOnIdle { assertEquals(2, fixture.backs); assertEquals(0, fixture.searches) }
    }

    @Test
    fun emptySearchHasNoWelcomeCardOrInventedResultsAndIdentifiesOnlyTheActiveDatabase() {
        show(ready)
        compose.onNodeWithTag("search-database").assertTextEquals(label(R.string.search_ui_database, ready.libraryTitle))
        compose.onNodeWithTag("search-welcome").assertDoesNotExist()
        compose.onNodeWithTag("search-more").assertDoesNotExist()
        compose.onNodeWithTag("search-empty").assertDoesNotExist()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsNotEnabled()
        compose.onNodeWithTag("search-scope").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, label(R.string.search_ui_all_database)))
    }

    @Test
    fun queryEditingNeverSubmitsButButtonAndImeDoAndClearIsLabeled() {
        val fixture = show(ready)
        compose.onNodeWithTag("search-query").performTextInput("kṛṣṇa")
        compose.runOnIdle { assertEquals(listOf("kṛṣṇa"), fixture.queries); assertEquals(0, fixture.searches) }
        compose.onNodeWithTag("search-query").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
            .performImeAction()
        compose.runOnIdle { assertEquals(1, fixture.searches) }
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, fixture.searches) }
        reveal("search-query")
        compose.onNodeWithTag("search-clear-query").assertContentDescriptionEquals(label(R.string.search_ui_clear_query)).performClick()
        compose.runOnIdle { assertEquals("", fixture.queries.last()); assertEquals(2, fixture.searches) }
        compose.onNodeWithTag("search-query").performImeAction()
        compose.runOnIdle { assertEquals(2, fixture.searches) }
    }

    @Test
    fun characterByCharacterTypingKeepsCaretAndFocusDuringAsynchronousSuggestions() {
        val fixture = show(ready)
        var expected = ""
        for ((index, character) in "Madhya 1.1, Kṛṣṇa!".withIndex()) {
            compose.onNodeWithTag("search-query").performTextInput(character.toString())
            expected += character
            assertQuery(expected, TextRange(expected.length))
            compose.runOnIdle { fixture.state = fixture.state.copy(suggesting = true) }
            assertQuery(expected, TextRange(expected.length))
            compose.runOnIdle {
                fixture.state = fixture.state.copy(suggesting = false,
                    suggestions = if (index % 2 == 0) listOf(SearchSuggestion("async-$index")) else emptyList())
            }
            assertQuery(expected, TextRange(expected.length))
            compose.onNodeWithTag("search-query").assertIsFocused()
        }
        compose.runOnIdle {
            assertEquals(expected, fixture.queries.last())
            assertEquals(expected.length, fixture.queries.size)
            assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun middleInsertionAndSelectionSurviveEqualModelEchoButExternalReplacementMovesToEnd() {
        val fixture = show(ready.copy(query = "BG 28"))
        compose.onNodeWithTag("search-query").performTextInputSelection(TextRange(4))
        compose.runOnIdle { fixture.state = fixture.state.copy(suggestions = listOf(SearchSuggestion("BG 2.8"))) }
        assertQuery("BG 28", TextRange(4))
        compose.onNodeWithTag("search-query").performTextInput(".")
        assertQuery("BG 2.8", TextRange(5))
        compose.onNodeWithTag("search-query").performTextInputSelection(TextRange(3, 6))
        compose.runOnIdle { fixture.state = fixture.state.copy(suggesting = true) }
        assertQuery("BG 2.8", TextRange(3, 6))
        compose.onNodeWithTag("search-query").performTextInput("1.1")
        assertQuery("BG 1.1", TextRange(6))
        compose.runOnIdle { fixture.state = fixture.state.copy(query = "Madhya 1.1", suggesting = false) }
        assertQuery("Madhya 1.1", TextRange(10))
        compose.runOnIdle { assertEquals(listOf("BG 2.8", "BG 1.1"), fixture.queries) }
    }

    @Test
    fun composingInTheMiddleSurvivesSuggestionUpdatesWithoutRestartingInputSession() {
        val fixture = show(ready.copy(query = "left right"), interceptIme = true)
        compose.onNodeWithTag("search-query").performClick()
        compose.waitForIdle()
        val connection = compose.runOnIdle { requireNotNull(fixture.inputConnection) }
        val sessions = compose.runOnIdle { fixture.inputSessions }
        compose.runOnIdle {
            connection.setSelection(5, 5)
            connection.setComposingText("k", 1)
        }
        assertQuery("left kright", TextRange(6))
        compose.runOnIdle {
            fixture.state = fixture.state.copy(suggesting = true, suggestions = listOf(SearchSuggestion("kṛṣṇa")))
        }
        // setComposingText must replace the previous composing region, not insert after it.
        compose.runOnIdle { connection.setComposingText("kṛṣ", 1) }
        assertQuery("left kṛṣright", TextRange(8))
        compose.runOnIdle { fixture.state = fixture.state.copy(suggesting = false, suggestions = emptyList()) }
        compose.runOnIdle { connection.commitText("kṛṣṇa, ", 1) }
        assertQuery("left kṛṣṇa, right", TextRange(12))
        compose.runOnIdle {
            assertEquals(sessions, fixture.inputSessions)
            assertTrue(connection === fixture.inputConnection)
            assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun querySelectionAndTextSurviveReaderBackAndLibraryChangeResetsEditor() {
        val fixture = show(ready.copy(query = "BG 2.8"))
        compose.onNodeWithTag("search-query").performTextInputSelection(TextRange(3, 5))
        compose.runOnIdle { fixture.visible = false }
        compose.onNodeWithTag("return-search").performClick()
        assertQuery("BG 2.8", TextRange(3, 5))
        compose.runOnIdle { fixture.state = ready.copy(libraryId = "another-library", query = "other") }
        assertQuery("other", TextRange(5))
    }

    @Test
    fun targetOptionsExplainMeaningAndOfferNoSeparateReferenceChip() {
        val fixture = show(ready.copy(mode = SearchMode.EXACT_PHRASE))
        reveal("search-target-ALL_TEXT")
        compose.onNodeWithTag("search-target-ALL_TEXT").assertIsSelected()
        compose.onNodeWithTag("search-target-help").assertTextEquals(label(R.string.search_ui_target_all_text_help))
        reveal("search-target-VERSE_WORDS")
        compose.onNodeWithTag("search-target-VERSE_WORDS").performClick().assertIsSelected()
        compose.onNodeWithTag("search-target-help").assertTextEquals(label(R.string.search_ui_target_verse_words_help))
        compose.onNodeWithTag("search-mode-EXACT_PHRASE").assertIsSelected()
        // References resolve inside All text; the removed chip must not reappear.
        compose.onNodeWithTag("search-target-VERSE_REFERENCE").assertDoesNotExist()
        reveal("search-query")
        compose.onNodeWithTag("search-query").performClick()
        compose.onNodeWithText(label(R.string.search_ui_query_hint), useUnmergedTree = true).assertIsDisplayed()
        reveal("search-target-ALL_TEXT")
        compose.onNodeWithTag("search-target-ALL_TEXT").performClick()
        compose.onNodeWithTag("search-mode-EXACT_PHRASE").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(SearchTarget.VERSE_WORDS, SearchTarget.ALL_TEXT), fixture.targets)
            assertEquals(0, fixture.searches)
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun contextualCatalogResolutionBlocksSubmissionRatherThanSearchingTheWrongScope() {
        val fixture = show(ready.copy(query = "light", catalogLoading = true, resolvingContext = true,
            suggestions = listOf(SearchSuggestion("light")), recent = listOf("earlier query")))
        compose.onNodeWithTag("search-query").performImeAction()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsNotEnabled()
        compose.onNodeWithTag("search-scope").assertIsNotEnabled()
        compose.onNodeWithTag("search-suggestion-0").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.searches) }
        compose.runOnIdle { fixture.state = fixture.state.copy(catalogLoading = false,
            resolvingContext = false, selectedBooks = setOf(first.node.record)) }
        compose.onNodeWithTag("search-submit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fixture.searches) }
    }

    @Test
    fun wordAndPhraseChipsExplainTheirMeaningWithoutSubmitting() {
        val fixture = show(ready)
        reveal("search-mode-EXACT_PHRASE")
        compose.onNodeWithTag("search-mode-ALL_WORDS").assertIsSelected()
        compose.onNodeWithTag("search-mode-help").assertTextEquals(label(R.string.search_ui_all_words_help))
        compose.onNodeWithTag("search-mode-EXACT_PHRASE").performClick().assertIsSelected()
        compose.onNodeWithTag("search-mode-help").assertTextEquals(label(R.string.search_ui_exact_phrase_help))
        compose.onNodeWithTag("search-mode-ALL_WORDS").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(SearchMode.EXACT_PHRASE, SearchMode.ALL_WORDS), fixture.modes)
            assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun singleSourceIsLocalUntilApplyAndUsesOriginalTitleAndId() {
        val fixture = show(ready)
        openPicker()
        book(first.node.record).performClick().assertIsOn()
        compose.runOnIdle { assertTrue(fixture.bookChanges.isEmpty()) }
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.onNodeWithTag("search-scope-dialog").assertDoesNotExist()
        compose.onNodeWithTag("search-scope").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, first.node.title))
        compose.runOnIdle { assertEquals(listOf(setOf(first.node.record)), fixture.bookChanges); assertEquals(0, fixture.searches) }
    }

    @Test
    fun filteredParentSelectionIncludesHiddenChildrenAndAppliesOnlyCanonicalRoots() {
        val fixture = show(ready.copy(books = ready.books + first))
        openPicker()
        filterBooks("First")
        book(root.node.record).performClick().assertIsOn()
        compose.onAllNodesWithTag("search-book-${first.node.record}").assertCountEquals(1)
        book(first.node.record).assertTextContains(root.node.title).assertIsOn()
        filterBooks("Second")
        book(second.node.record).assertIsOn()
        filterBooks("Separate")
        book(separate.node.record).performClick().assertIsOn()
        filterBooks("No synthetic title matches")
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-empty"))
        compose.onNodeWithTag("search-book-empty").assertTextEquals(label(R.string.search_ui_filter_empty))
        compose.onNodeWithTag("search-scope-apply").assertIsEnabled().performClick()
        val expected = setOf(root.node.record, separate.node.record)
        compose.runOnIdle { assertEquals(listOf(expected), fixture.bookChanges); assertEquals(0, fixture.searches) }
        compose.onNodeWithTag("search-scope").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, label(R.string.search_ui_selected_count, 2)))
    }

    @Test
    fun threeLevelsHaveIndependentAccessibleExpansionAndSelectionTargets() {
        val fixture = show(hierarchical)
        openPicker()
        // Rows show only the book name; depth is conveyed by indentation, not labels.
        book(root.node.record).assertTextEquals(root.node.title)
        book(first.node.record).assertTextEquals(first.node.title)
        compose.onNodeWithTag("search-book-${nested.node.record}").assertDoesNotExist()
        expandBook(first.node.record).assertContentDescriptionEquals(label(R.string.search_ui_expand_book, first.node.title))
            .assertHasClickAction().performClick()
        book(nested.node.record).assertTextEquals(nested.node.title).assertIsOff()
        val nestedLeft = book(nested.node.record).getUnclippedBoundsInRoot().left
        val firstLeft = book(first.node.record).getUnclippedBoundsInRoot().left
        assertTrue(nestedLeft > firstLeft)
        book(root.node.record).performClick()
        book(nested.node.record).assertIsOn()
        val expansion = expandBook(first.node.record)
        val expandBounds = expansion.getUnclippedBoundsInRoot()
        assertTrue((expandBounds.right - expandBounds.left).value >= 47.9f)
        assertTrue((expandBounds.bottom - expandBounds.top).value >= 47.9f)
        val selectBounds = book(first.node.record).getUnclippedBoundsInRoot()
        assertTrue(selectBounds.left >= expandBounds.right)
        expandBook(first.node.record).performClick()
        compose.onNodeWithTag("search-book-${nested.node.record}").assertDoesNotExist()
        book(first.node.record).assertIsOn()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(root.node.record)), fixture.bookChanges) }
    }

    @Test
    fun filteredGrandchildExclusionMakesParentsPartialAndActuallyRemovesAncestorScopes() {
        val fixture = show(hierarchical.copy(selectedBooks = setOf(root.node.record)))
        openPicker()
        filterBooks("krsna")
        book(nested.node.record).assertIsOn().performClick()
        // The explanatory exclusion note changes list height; reacquire after lazy relayout.
        book(nested.node.record).assertIsOff()
        assertPartial(first.node.record)
        assertPartial(root.node.record)
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-scope-exclusion-note"))
        compose.onNodeWithTag("search-scope-exclusion-note").assertTextEquals(label(R.string.search_ui_excluded_parent_prose))
        filterBooks("")
        book(second.node.record).assertIsOn()
        expandBook(first.node.record).performClick()
        book(nestedSibling.node.record).assertIsOn()
        book(nested.node.record).assertIsOff()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(nestedSibling.node.record, second.node.record)), fixture.bookChanges) }
    }

    @Test
    fun allChildrenSelectedStillLeavesParentPartialUntilExplicitlySelected() {
        val fixture = show(hierarchical.copy(selectedBooks = setOf(nested.node.record, nestedSibling.node.record)))
        openPicker()
        assertPartial(first.node.record)
        assertPartial(root.node.record)
        book(first.node.record).performClick().assertIsOn()
        book(nested.node.record).assertIsOn()
        book(nestedSibling.node.record).assertIsOn()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(first.node.record)), fixture.bookChanges) }
    }

    @Test
    fun selectingParentAgainRestoresItsProseAndRemovesTheExclusionWarning() {
        val fixture = show(hierarchical.copy(selectedBooks = setOf(root.node.record)))
        openPicker()
        filterBooks("krsna")
        book(nested.node.record).performClick()
        book(root.node.record).performClick().assertIsOn()
        book(nested.node.record).assertIsOn()
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-scope-selection"))
        compose.onNodeWithTag("search-scope-exclusion-note").assertDoesNotExist()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(root.node.record)), fixture.bookChanges) }
    }

    @Test
    fun committedOverlappingScopesAreCountedAndAppliedAsCanonicalRootsOnly() {
        val fixture = show(hierarchical.copy(selectedBooks = setOf(root.node.record, first.node.record,
            nested.node.record, separate.node.record)))
        reveal("search-scope")
        compose.onNodeWithTag("search-scope").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, label(R.string.search_ui_selected_count, 2)))
        openPicker()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(root.node.record, separate.node.record)), fixture.bookChanges) }
    }

    @Test
    fun excludingOnlyNestedChildDisablesApplyUntilExplicitAllDatabaseReset() {
        val fixture = show(ready.copy(books = listOf(root, first, nested), selectedBooks = setOf(root.node.record)))
        openPicker()
        filterBooks("krsna")
        book(nested.node.record).performClick()
        book(nested.node.record).assertIsOff()
        book(root.node.record).assertIsOff()
        compose.onNodeWithTag("search-scope-apply").assertIsNotEnabled()
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-scope-all"))
        compose.onNodeWithTag("search-scope-all").assertIsNotSelected().performClick()
        compose.onNodeWithTag("search-scope-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<Int>()), fixture.bookChanges) }
    }

    @Test
    fun resetThenCancelOrSystemBackDoesNotChangeCommittedBranches() {
        val fixture = show(hierarchical.copy(selectedBooks = setOf(nested.node.record)))
        openPicker()
        compose.onNodeWithTag("search-scope-all").performClick()
        compose.onNodeWithTag("search-scope-cancel").performClick()
        openPicker()
        book(nested.node.record).assertIsOn()
        assertPartial(first.node.record)
        book(root.node.record).performClick()
        systemBack()
        compose.onNodeWithTag("search-scope-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.bookChanges.isEmpty()); assertEquals(0, fixture.backs) }
        openPicker()
        book(nested.node.record).assertIsOn()
        book(nestedSibling.node.record).assertIsOff()
    }

    @Test
    fun pickerApplyAndCancelStayFixedBottomRightWhileKeyboardIsRequestedAndListScrolls() {
        show(hierarchical)
        openPicker()
        filterBooks("Nested")
        compose.onNodeWithTag("search-book-filter").assertIsFocused()
        val before = compose.onNodeWithTag("search-scope-apply").getUnclippedBoundsInRoot()
        book(nestedSibling.node.record).assertIsDisplayed()
        compose.onNodeWithTag("search-scope-apply").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-cancel").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("search-scope-apply").getUnclippedBoundsInRoot())
        val dialog = compose.onNodeWithTag("search-scope-dialog").getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("search-scope-cancel").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("search-book-list").getUnclippedBoundsInRoot()
        assertTrue("Apply sits at the bottom right", before.bottom > list.bottom && before.right > dialog.right - 48.dp)
        assertTrue("Cancel precedes Apply", cancel.right <= before.left)
        compose.onNodeWithTag("search-scope-cancel").performClick()
        compose.onNodeWithTag("search-scope-dialog").assertDoesNotExist()
    }

    @Test
    fun switchingLibraryClosesPickerAndDiscardsItsDraft() {
        val fixture = show(ready)
        openPicker()
        book(root.node.record).performClick()
        compose.runOnIdle { fixture.state = ready.copy(libraryId = "other-library") }
        compose.onNodeWithTag("search-scope-dialog").assertDoesNotExist()
        openPicker()
        book(root.node.record).assertIsOff()
        compose.runOnIdle { assertTrue(fixture.bookChanges.isEmpty()) }
    }

    @Test
    fun cancelDiscardsFilteredDraftAndReopenUsesCommittedSelection() {
        val fixture = show(ready.copy(selectedBooks = setOf(first.node.record)))
        openPicker()
        filterBooks("Second")
        book(second.node.record).performClick().assertIsOn()
        compose.onNodeWithTag("search-scope-cancel").performClick()
        compose.runOnIdle { assertTrue(fixture.bookChanges.isEmpty()) }
        openPicker()
        book(first.node.record).assertIsOn()
        book(second.node.record).assertIsOff()
        compose.onNodeWithTag("search-scope-cancel").performClick()
    }

    @Test
    fun deselectingLastBookDoesNotSilentlyBecomeAllDatabase() {
        val fixture = show(ready.copy(selectedBooks = setOf(first.node.record)))
        openPicker()
        book(first.node.record).performClick().assertIsOff()
        compose.onNodeWithTag("search-scope-apply").assertIsNotEnabled()
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-scope-selection"))
        compose.onNodeWithTag("search-scope-selection").assertTextEquals(label(R.string.search_ui_select_required))
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-scope-all"))
        compose.onNodeWithTag("search-scope-all").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("search-scope-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<Int>()), fixture.bookChanges) }
    }

    @Test
    fun togglingTheSameIdTwiceDoesNotDuplicateOrLeakSelections() {
        val fixture = show(ready)
        openPicker()
        book(first.node.record).performClick().performClick().assertIsOff()
        compose.onNodeWithTag("search-scope-apply").assertIsNotEnabled()
        book(first.node.record).performClick().assertIsOn()
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(first.node.record)), fixture.bookChanges) }
    }

    @Test
    fun filteringByParentFindsNestedBooksWithoutReplacingTheirTitles() {
        show(ready)
        openPicker()
        filterBooks(root.node.title)
        book(first.node.record).assertTextContains(first.node.title).assertTextContains(root.node.title)
        book(second.node.record).assertTextContains(second.node.title).assertTextContains(root.node.title)
        compose.onNodeWithTag("search-book-${separate.node.record}").assertDoesNotExist()
    }

    @Test
    fun dialogDraftAndFilterSurviveSaveableRestorationWithoutApplying() {
        val restoration = StateRestorationTester(compose)
        val fixture = show(ready, restoration)
        openPicker()
        filterBooks("First")
        book(first.node.record).performClick()
        restoration.emulateSavedInstanceStateRestore()
        book(first.node.record).assertIsOn()
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-filter"))
        compose.onNodeWithTag("search-book-filter").assertTextContains("First")
        compose.runOnIdle { assertTrue(fixture.bookChanges.isEmpty()) }
        compose.onNodeWithTag("search-scope-apply").performClick()
        compose.runOnIdle { assertEquals(listOf(setOf(first.node.record)), fixture.bookChanges) }
    }

    @Test
    fun unavailableCatalogCanRetryWhileAllDatabaseSearchRemainsEnabled() {
        val fixture = show(ready.copy(query = "synthetic", catalogLoading = true))
        compose.onNodeWithTag("search-scope").assertIsNotEnabled()
        reveal("search-catalog-loading")
        compose.onNodeWithTag("search-catalog-loading").assertIsDisplayed()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsEnabled().performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(catalogLoading = false, catalogError = true) }
        compose.onNodeWithTag("search-scope").assertIsNotEnabled()
        reveal("search-retry-catalog")
        compose.onNodeWithTag("search-retry-catalog").performClick()
        compose.runOnIdle { assertEquals(1, fixture.catalogRetries); assertEquals(1, fixture.searches) }
        compose.onNodeWithTag("search-scope-dialog").assertDoesNotExist()
    }

    @Test
    fun emptyCatalogStillOffersAnExplicitAllDatabaseChoice() {
        val fixture = show(ready.copy(books = emptyList()))
        openPicker()
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-empty"))
        compose.onNodeWithTag("search-book-empty").assertTextEquals(label(R.string.search_ui_catalog_empty))
        compose.onNodeWithTag("search-scope-apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<Int>()), fixture.bookChanges) }
    }

    @Test
    fun suggestionTapPassesExactControllerObjectAndDoesNotChainQueryAndSearch() {
        val suggestion = SearchSuggestion("synthetic 2.3", "Synthetic reference 2.3", reference = true)
        val fixture = show(ready.copy(query = "syn", suggestions = listOf(suggestion), suggesting = true))
        reveal("search-suggestions-loading")
        compose.onNodeWithTag("search-suggestions-loading").assertIsDisplayed()
        reveal("search-suggestion-0")
        compose.onNodeWithTag("search-suggestion-0").assertTextContains(suggestion.label)
            .assertTextContains(label(R.string.search_ui_reference)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(suggestion), fixture.suggestions)
            assertTrue(fixture.queries.isEmpty()); assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun unavailableSuggestionsDoNotPretendSearchFailedOrDisableSubmission() {
        val fixture = show(ready.copy(query = "synthetic", suggestionsUnavailable = true))
        reveal("search-suggestions-unavailable")
        compose.onNodeWithTag("search-suggestions-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("search-error-UNAVAILABLE").assertDoesNotExist()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fixture.searches) }
    }

    @Test
    fun recentQueryIsOneSuggestionIntentAndClearHistoryIsExplicit() {
        val fixture = show(ready.copy(recent = listOf("कृष्ण", "synthetic")))
        reveal("search-recent-0")
        compose.onNodeWithTag("search-recent-0").performClick()
        reveal("search-clear-history")
        compose.onNodeWithTag("search-clear-history").performClick()
        compose.onNodeWithTag("search-clear-history-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fixture.historyClears) }
        compose.onNodeWithTag("search-clear-history-confirm").performClick()
        compose.onNodeWithTag("search-clear-history-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(SearchSuggestion("कृष्ण")), fixture.suggestions)
            assertTrue(fixture.queries.isEmpty()); assertEquals(0, fixture.searches); assertEquals(1, fixture.historyClears)
        }
    }

    @Test
    fun clearHistoryCancelBackAndLibraryChangeNeverClearOrNavigateAway() {
        val fixture = show(ready.copy(recent = listOf("earlier query")))
        reveal("search-clear-history")
        compose.onNodeWithTag("search-clear-history").performClick()
        compose.onNodeWithTag("search-clear-history-cancel").performClick()
        compose.onNodeWithTag("search-clear-history-dialog").assertDoesNotExist()
        compose.onNodeWithTag("search-clear-history").performClick()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            compose.waitUntil(5_000) { compose.runOnIdle {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() && it !== compose.activity.window.decorView
                }
            } }
        }
        systemBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("search-clear-history-dialog").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("search-clear-history-dialog").assertDoesNotExist()
        compose.onNodeWithTag("search-clear-history").performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(libraryId = "other-library") }
        compose.onNodeWithTag("search-clear-history-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.historyClears); assertEquals(0, fixture.backs) }
    }

    @Test
    fun unicodeHighlightsUseValidatedOffsetsAndPlainTextAndResultOpensExactLocation() {
        val text = "Kṛṣṇa कृष्ण 🌿 <b>literal, not HTML</b>"
        val emoji = text.indexOf("🌿")
        val valid = listOf(SearchHighlight(0, 5), SearchHighlight(6, 11), SearchHighlight(emoji, emoji + 2))
        val invalid = listOf(SearchHighlight(-1, 4), SearchHighlight(0, Int.MAX_VALUE), SearchHighlight(3, 2),
            SearchHighlight(2, 2), SearchHighlight(emoji + 1, emoji + 2), SearchHighlight(emoji, emoji + 1))
        val hit = passage(123).copy(snippet = text, highlights = valid + invalid)
        val fixture = show(searched(listOf(hit)))
        reveal("search-result-123")
        val annotated = compose.onNodeWithTag("search-snippet-123", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(text, annotated.text)
        assertEquals(valid.map { it.start to it.endExclusive }, annotated.spanStyles.map { it.start to it.end })
        assertTrue(annotated.spanStyles.all { it.item.fontWeight == FontWeight.SemiBold })
        compose.onNodeWithTag("search-result-123").assertTextContains(hit.bookTitle).assertTextContains(hit.location.title)
            .assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(listOf(hit), fixture.opened) }
    }

    @Test
    fun longSnippetIsNotEllipsizedOrLimitedToAnArbitraryLineCount() {
        val text = (1..24).joinToString("\n") { "Synthetic line $it — कृष्ण and Kṛṣṇa." }
        show(searched(listOf(passage(123).copy(snippet = text))))
        reveal("search-result-123")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("search-snippet-123", useUnmergedTree = true).assertTextEquals(text)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(Int.MAX_VALUE, layout.layoutInput.maxLines)
        assertTrue(layout.lineCount >= 24)
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun resultCountIsShownCountAndSourceOrderIsNotRelabeledAsRelevance() {
        show(searched(listOf(passage(123), passage(456))))
        compose.onNodeWithTag("search-list").performScrollToNode(hasText(label(R.string.search_ui_passages_shown, 2)))
        compose.onNodeWithText(label(R.string.search_ui_passages_shown, 2)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.search_ui_source_order)).assertExists()
        reveal("search-result-123")
        val firstPosition = scrollPosition()
        reveal("search-result-456")
        assertTrue(scrollPosition() >= firstPosition)
    }

    @Test
    fun editingQueryHidesEvenAnInconsistentStaleResultsPayload() {
        val fixture = show(searched(listOf(passage(123))))
        reveal("search-result-123")
        compose.onNodeWithTag("search-result-123").assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(query = "different") }
        compose.onNodeWithTag("search-result-123").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.search_ui_passages_shown, 1)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fixture.searches) }
    }

    @Test
    fun searchingHasCancelableStatusAndCannotSubmitTwice() {
        val fixture = show(searched(emptyList()).copy(searching = true))
        compose.onNodeWithTag("search-loading").assertIsDisplayed()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsNotEnabled()
        compose.onNodeWithTag("search-empty").assertDoesNotExist()
        compose.onNodeWithTag("search-cancel").performClick()
        compose.onNodeWithTag("search-back").performClick()
        compose.runOnIdle { assertEquals(1, fixture.cancels); assertEquals(1, fixture.backs); assertEquals(0, fixture.searches) }
    }

    @Test
    fun loadingMoreRetainsHitsAndAllowsCancellationButNotAnotherPageRequest() {
        val fixture = show(searched(listOf(passage(123))).copy(nextAfter = 500, loadingMore = true))
        compose.onNodeWithTag("search-loading-more").assertIsDisplayed()
        reveal("search-result-123")
        compose.onNodeWithTag("search-result-123").assertExists()
        reveal("search-more")
        compose.onNodeWithTag("search-more").assertIsNotEnabled()
        compose.onNodeWithTag("search-cancel").performClick()
        compose.runOnIdle { assertEquals(1, fixture.cancels); assertEquals(0, fixture.moreRequests) }
    }

    @Test
    fun exhaustedEmptySearchOffersRefineAndExplicitScopeResetWithoutSearching() {
        val fixture = show(searched(emptyList()).copy(selectedBooks = setOf(first.node.record)))
        reveal("search-empty")
        compose.onNodeWithTag("search-empty").assertExists()
        reveal("search-refine")
        compose.onNodeWithTag("search-refine").performClick()
        compose.onNodeWithTag("search-query").assertIsFocused()
        compose.runOnIdle { assertEquals(0, fixture.searches) }
        // Hide IME without changing the query; the action remains below the empty-state copy.
        compose.onNodeWithTag("search-query").performImeAction()
        reveal("search-reset-scope")
        compose.onNodeWithTag("search-reset-scope").performClick()
        compose.runOnIdle { assertEquals(listOf(emptySet<Int>()), fixture.bookChanges); assertEquals(1, fixture.searches) }
    }

    @Test
    fun zeroVerifiedHitsWithRemainingCandidatesIsNotPresentedAsAnExhaustedSearch() {
        val fixture = show(searched(emptyList()).copy(nextAfter = 600))
        reveal("search-continue")
        compose.onNodeWithTag("search-continue").assertTextEquals(label(R.string.search_ui_continue))
        compose.onNodeWithTag("search-empty").assertDoesNotExist()
        reveal("search-more")
        compose.onNodeWithTag("search-more").assertTextEquals(label(R.string.search_ui_show_more)).performClick()
        compose.runOnIdle { assertEquals(1, fixture.moreRequests); assertEquals(0, fixture.searches) }
    }

    @Test
    fun eachIssueHasAnHonestActionInsteadOfAnEmptyResultClaim() {
        val fixture = show(ready.copy(query = "?", error = SearchIssue.INVALID_QUERY))
        reveal("search-error-INVALID_QUERY")
        compose.onNodeWithText(label(R.string.search_ui_invalid_body)).assertExists()
        compose.onNodeWithTag("search-retry").assertDoesNotExist()
        reveal("search-refine")
        compose.onNodeWithTag("search-refine").performClick()
        compose.onNodeWithTag("search-query").assertIsFocused()
        for (issue in listOf(SearchIssue.TIMEOUT, SearchIssue.UNAVAILABLE)) {
            compose.runOnIdle { fixture.state = searched(emptyList()).copy(error = issue) }
            reveal("search-error-${issue.name}")
            compose.onNodeWithTag("search-error-${issue.name}").assertExists()
            compose.onNodeWithTag("search-empty").assertDoesNotExist()
            reveal("search-retry")
            compose.onNodeWithTag("search-retry").assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(2, fixture.searches); assertEquals(0, fixture.moreRequests) }
    }

    @Test
    fun retryAfterPartialTimeoutUsesTheContinuationCallbackAndKeepsVerifiedHits() {
        val fixture = show(searched(listOf(passage(123))).copy(error = SearchIssue.TIMEOUT, nextAfter = 700))
        reveal("search-retry")
        compose.onNodeWithTag("search-retry").performClick()
        reveal("search-result-123")
        compose.onNodeWithTag("search-result-123").assertExists()
        reveal("search-more")
        compose.onNodeWithTag("search-more").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, fixture.moreRequests); assertEquals(0, fixture.searches) }
    }

    @Test
    fun scrollSurvivesAppendingAndLeavingTheScreenThroughExternalSaveableHolder() {
        val restoration = StateRestorationTester(compose)
        val fixture = show(searched((1..35).map { passage(100 + it) }).copy(nextAfter = 900), restoration)
        reveal("search-result-125")
        val before = scrollPosition()
        assertTrue(before > 0f)
        compose.runOnIdle { fixture.state = fixture.state.copy(loadingMore = true) }
        assertEquals(before, scrollPosition(), .001f)
        compose.runOnIdle { fixture.state = fixture.state.copy(loadingMore = false,
            results = fixture.state.results + passage(1000), nextAfter = null) }
        assertEquals(before, scrollPosition(), .001f)
        compose.runOnIdle { fixture.visible = false }
        compose.onNodeWithTag("return-search").performClick()
        assertEquals(before, scrollPosition(), .001f)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(before, scrollPosition(), .001f)
    }

    @Test
    fun controlsDoNotResetListButExplicitSubmissionDoes() {
        val fixture = show(searched((1..35).map { passage(100 + it) }))
        reveal("search-result-125")
        val before = scrollPosition()
        compose.runOnIdle { fixture.state = fixture.state.copy(mode = SearchMode.EXACT_PHRASE,
            selectedBooks = setOf(root.node.record), target = SearchTarget.VERSE_WORDS) }
        assertEquals(before, scrollPosition(), .001f)
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").performClick()
        compose.onNodeWithTag("search-query").assertIsDisplayed()
        assertEquals(0f, scrollPosition(), .001f)
        compose.runOnIdle { assertEquals(1, fixture.searches) }
    }

    @Test
    fun importantControlsHaveLabelsRolesAndAtLeast48DpBounds() {
        show(ready.copy(query = "synthetic"))
        compose.onNodeWithTag("search-back").assertContentDescriptionEquals(label(R.string.search_ui_back))
        compose.onNodeWithTag("search-clear-query").assertContentDescriptionEquals(label(R.string.search_ui_clear_query))
        compose.onNodeWithTag("search-scope").assertContentDescriptionEquals(label(R.string.search_ui_choose_scope))
        for (tag in listOf("search-back", "search-clear-query", "search-scope", "search-submit",
            "search-mode-ALL_WORDS", "search-mode-EXACT_PHRASE", "search-target-ALL_TEXT",
            "search-target-VERSE_WORDS")) {
            if (tag != "search-back") reveal(tag)
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag height", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("$tag width", bounds.right - bounds.left >= 48.dp)
            compose.onNodeWithTag(tag).assertHasClickAction()
        }
        openPicker()
        book(first.node.record).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertTextEquals(first.node.title)
        for (tag in listOf("search-scope-apply", "search-scope-cancel")) {
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            // Floating dialog coordinates can subtract to 47.99999dp on density 2.625.
            assertTrue("$tag height", (bounds.bottom - bounds.top).value >= 47.9f)
        }
    }

    @Test
    fun largeTextLeavesBackAvailableDuringInputAndActionsRemainScrollable() {
        show(ready, fontScale = 1.8f)
        compose.onNodeWithTag("search-query").performTextInput("synthetic")
        compose.onNodeWithTag("search-back").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("search-query").performImeAction()
        reveal("search-submit")
        compose.onNodeWithTag("search-submit").assertIsDisplayed().assertIsEnabled()
        reveal("search-mode-EXACT_PHRASE")
        compose.onNodeWithTag("search-mode-EXACT_PHRASE").assertIsDisplayed().performClick()
        compose.onNodeWithTag("search-back").assertIsDisplayed()
    }

    private fun show(initial: SearchUiState, restoration: StateRestorationTester = StateRestorationTester(compose),
        fontScale: Float = 1f, interceptIme: Boolean = false): Fixture {
        val fixture = Fixture(initial)
        val interceptor = PlatformTextInputInterceptor { request, _ ->
            fixture.inputConnection = request.createInputConnection(EditorInfo())
            fixture.inputSessions++
            awaitCancellation()
        }
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                VedaPreviewTheme {
                    val screens = rememberSaveableStateHolder()
                    if (fixture.visible) screens.SaveableStateProvider("search") {
                        SearchScreen(
                            state = fixture.state,
                            onBack = { fixture.backs++ },
                            onQuery = { fixture.queries += it; fixture.state = fixture.state.copy(query = it) },
                            onBooks = { fixture.bookChanges += it; fixture.state = fixture.state.copy(selectedBooks = it) },
                            onMode = { fixture.modes += it; fixture.state = fixture.state.copy(mode = it) },
                            onSearch = { fixture.searches++ },
                            onSuggestion = { fixture.suggestions += it },
                            onOpenHit = { fixture.opened += it },
                            onLoadMore = { fixture.moreRequests++ },
                            onRetryCatalog = { fixture.catalogRetries++ },
                            onCancel = { fixture.cancels++ },
                            onClearHistory = { fixture.historyClears++ },
                            onTarget = { fixture.targets += it; fixture.state = fixture.state.copy(target = it) },
                        )
                    } else TextButton(onClick = { fixture.visible = true }, modifier = Modifier.testTag("return-search")) {
                        Text("Return to synthetic search")
                    }
                }
            }
        }
        restoration.setContent {
            if (interceptIme) InterceptPlatformTextInput(interceptor, content) else content()
        }
        return fixture
    }

    private fun searched(hits: List<ScopedSearchHit>) = ready.copy(query = "synthetic", submittedQuery = "synthetic", results = hits)

    private fun passage(position: Int) = ScopedSearchHit(
        ReadingLocation("synthetic-db", "Synthetic location $position", position, position + 1, position),
        "Synthetic source", "A synthetic paragraph at $position. कृष्ण and Kṛṣṇa are preserved.",
    )

    private fun openPicker() {
        reveal("search-scope")
        compose.onNodeWithTag("search-scope").performClick()
        compose.onNodeWithTag("search-scope-dialog").assertIsDisplayed()
    }

    private fun filterBooks(text: String) {
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-filter"))
        compose.onNodeWithTag("search-book-filter").performTextReplacement(text)
        compose.onNodeWithTag("search-book-filter").performImeAction()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        compose.waitForIdle()
    }

    private fun book(id: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-$id"))
        return compose.onNodeWithTag("search-book-$id")
    }

    private fun expandBook(id: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag("search-book-list").performScrollToNode(hasTestTag("search-book-expand-$id"))
        return compose.onNodeWithTag("search-book-expand-$id")
    }

    private fun assertPartial(id: Int) {
        book(id).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
    }

    private fun assertQuery(text: String, selection: TextRange) {
        compose.onNodeWithTag("search-query").assertTextContains(text)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        assertEquals(text, compose.onNodeWithTag("search-query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun systemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        compose.waitForIdle()
    }

    private fun reveal(tag: String) = compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag(tag))

    private fun scrollPosition() = compose.onNodeWithTag("search-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun label(id: Int, vararg arguments: Any) = compose.activity.getString(id, *arguments)

    private class Fixture(initial: SearchUiState) {
        var state by mutableStateOf(initial)
        var visible by mutableStateOf(true)
        val queries = mutableListOf<String>()
        val bookChanges = mutableListOf<Set<Int>>()
        val modes = mutableListOf<SearchMode>()
        val targets = mutableListOf<SearchTarget>()
        val suggestions = mutableListOf<SearchSuggestion>()
        val opened = mutableListOf<ScopedSearchHit>()
        var searches = 0
        var backs = 0
        var cancels = 0
        var moreRequests = 0
        var catalogRetries = 0
        var historyClears = 0
        var inputConnection: InputConnection? = null
        var inputSessions = 0
    }
}