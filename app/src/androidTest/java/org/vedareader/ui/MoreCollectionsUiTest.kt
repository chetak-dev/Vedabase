package org.vedareader.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.Bookmark
import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor
import org.vedareader.data.LibraryInfo
import org.vedareader.data.ReadingLocation

/** Synthetic owner snapshots only: note changes never touch persistence or reader state. */
@RunWith(AndroidJUnit4::class)
class MoreCollectionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val library = LibraryInfo("active", "Synthetic library", "en", "synthetic.ivd", 0, 0, "")
    private val location = ReadingLocation(library.id, "Synthetic reference", 100, 199, 120)
    private val longNote = (1..14).joinToString("\n") { "Full reflection line $it — कृष्ण" }
    private val standalone = annotation("standalone", false, longNote, 300)
    private val both = annotation("both", true, "A reflection with a highlight", 200)
    private val filled = annotation("filled", true, "", 100)

    @Test
    fun tabTitlesAndIndependentFiltersExcludeForeignAndUnfilledAnnotations() {
        val foreign = standalone.copy(id = "foreign", location = location.copy(libraryId = "other"), createdAt = 400)
        val fixture = show(Fixture(listOf(standalone, both, filled, foreign), selectedTab = 1))
        compose.onNodeWithTag("bookmarks-title").assertTextEquals(label(R.string.highlights_tab))
        reveal("highlight-both")
        reveal("highlight-filled")
        compose.onNodeWithTag("highlight-standalone").assertDoesNotExist()
        compose.onNodeWithTag("highlight-foreign").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-tab-notes").performClick()
        compose.onNodeWithTag("bookmarks-title").assertTextEquals(label(R.string.notes_tab))
        reveal("highlight-standalone")
        reveal("highlight-both")
        compose.onNodeWithTag("highlight-filled").assertDoesNotExist()
        compose.onNodeWithTag("highlight-foreign").assertDoesNotExist()
        compose.runOnIdle { fixture.highlights = fixture.highlights.copy(items = listOf(standalone)); fixture.tab = 1 }
        compose.onNodeWithTag("highlights-empty").assertIsDisplayed()
        compose.onNodeWithTag("highlight-standalone").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-tab-bookmarks").performClick()
        compose.onNodeWithTag("bookmarks-title").assertTextEquals(label(R.string.bookmark_title))
    }

    @Test
    fun selectedMoreButtonNavigatesLandingAndHomeIsNotBack() {
        val fixture = show(Fixture())
        compose.onNodeWithTag("bookmarks-nav-bookmarks").assertIsSelected().performClick()
        compose.onNodeWithTag("bookmarks-nav-library").performClick()
        compose.onNodeWithTag("bookmarks-nav-search").performClick()
        compose.onNodeWithTag("bookmarks-back").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.more)
            assertEquals(1, fixture.homes)
            assertEquals(1, fixture.backs)
            assertEquals(1, fixture.searches)
        }
    }

    @Test
    fun explicitEditOpensEntireNoteAndWaitsForCommittedSnapshot() {
        val fixture = show(Fixture(listOf(standalone)))
        openEditor(standalone)
        assertDraft(longNote)
        val draft = "$longNote\nThe editable ending beyond the sixth line."
        compose.onNodeWithTag("note-editor-field").performTextReplacement(draft)
        compose.onNodeWithTag("note-editor-save").performClick().assertIsNotEnabled()
        assertDraft(draft)
        compose.runOnIdle {
            assertEquals(listOf(standalone.id to draft), fixture.edits)
            assertEquals(longNote, fixture.highlights.items.single().note)
            assertTrue(fixture.opened.isEmpty())
            fixture.highlights = fixture.highlights.copy(busy = false,
                items = listOf(standalone.copy(note = draft)))
        }
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle { assertFalse(fixture.highlights.items.single().highlighted) }
    }

    @Test
    fun failedWriteKeepsDraftAndAllowsRetryEvenAfterLoadFailureFlag() {
        val fixture = show(Fixture(listOf(both)))
        openEditor(both)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Retain this unsaved note कृष्ण")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.runOnIdle {
            fixture.highlights = fixture.highlights.copy(busy = false, failed = true,
                notice = HighlightNotice(9, HighlightMessage.FAILED))
        }
        compose.onNodeWithTag("note-editor-error").assertIsDisplayed()
        assertDraft("Retain this unsaved note कृष्ण")
        compose.onNodeWithTag("note-editor-save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.edits.size)
            fixture.highlights = fixture.highlights.copy(busy = false, failed = false,
                items = listOf(both.copy(note = fixture.edits.last().second)),
                notice = HighlightNotice(10, HighlightMessage.SAVED))
        }
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(fixture.highlights.items.single().highlighted)
            assertEquals(both.color, fixture.highlights.items.single().color)
        }
    }

    @Test
    fun editorIdentityAndFullDraftSurviveRestorationAndCancelDoesNotWrite() {
        val restoration = StateRestorationTester(compose)
        val fixture = show(Fixture(listOf(standalone)), restoration)
        openEditor(standalone)
        val draft = "$longNote\nUnsubmitted ending"
        compose.onNodeWithTag("note-editor-field").performTextReplacement(draft)
        restoration.emulateSavedInstanceStateRestore()
        assertDraft(draft)
        compose.onNodeWithTag("note-editor-cancel").performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.edits.isEmpty()); assertEquals(longNote, fixture.highlights.items.single().note) }
    }

    @Test
    fun notesRemovalClearsOnlyNoteAndHighlightsRemovalClearsOnlyFill() {
        val fixture = show(Fixture(listOf(both)))
        reveal("highlight-remove-both")
        compose.onNodeWithTag("highlight-remove-both").assertContentDescriptionEquals(label(R.string.annotation_remove_note)).performClick()
        compose.onNodeWithTag("highlights-remove-dialog").assertIsDisplayed()
        compose.onNodeWithTag("highlights-remove-cancel").performClick()
        compose.runOnIdle { assertTrue(fixture.removedNotes.isEmpty()) }
        compose.onNodeWithTag("highlight-remove-both").performClick()
        compose.onNodeWithTag("highlights-remove-confirm").performClick()
        compose.onNodeWithTag("notes-empty").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(both), fixture.removedNotes)
            assertTrue(fixture.removedHighlights.isEmpty())
            assertEquals(both.copy(note = ""), fixture.highlights.items.single())
            fixture.highlights = fixture.highlights.copy(items = listOf(both)); fixture.tab = 1
        }
        reveal("highlight-remove-both")
        compose.onNodeWithTag("highlight-remove-both").assertContentDescriptionEquals(label(R.string.annotation_remove_highlight)).performClick()
        compose.onNodeWithTag("highlights-remove-confirm").performClick()
        compose.onNodeWithTag("highlights-empty").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(both), fixture.removedHighlights)
            assertEquals(both.copy(highlighted = false), fixture.highlights.items.single())
        }
        compose.onNodeWithTag("bookmarks-tab-notes").performClick()
        reveal("highlight-both")
    }

    @Test
    fun clearingStandaloneNoteWaitsForRemovalAndDoesNotCreateFill() {
        val fixture = show(Fixture(listOf(standalone)))
        openEditor(standalone)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("")
        compose.onNodeWithTag("note-editor-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(standalone.id to ""), fixture.edits)
            fixture.highlights = fixture.highlights.copy(items = emptyList(), busy = false)
        }
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("notes-empty").assertIsDisplayed()
    }

    @Test
    fun duplicateBookTitlesHaveIndependentStableHeadersCountsAndOriginalRows() {
        val newest = bookmark("new", 10, 300)
        val oldest = bookmark("old", 10, 100)
        val differentBook = bookmark("different", 20, 200)
        val foreign = bookmark("foreign", 10, 400).copy(location = location.copy(libraryId = "other"))
        val fixture = show(Fixture(selectedTab = 0).apply {
            bookmarks = BookmarkUiState(loading = false, items = listOf(oldest, differentBook, foreign, newest))
        })
        fun heading(record: Int, count: String) {
            compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmarks-book-active:$record"))
            compose.onNodeWithTag("bookmarks-book-count-active:$record", useUnmergedTree = true).assertTextEquals(count)
            compose.onNodeWithTag("bookmarks-book-active:$record").assertExists()
        }
        heading(10, "2")
        heading(20, "1")
        compose.onNodeWithTag("bookmarks-book-other:10").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-open-different"))
        compose.onNodeWithTag("bookmark-open-different").performClick()
        compose.runOnIdle { assertSame(differentBook, fixture.openedBookmarks.single()) }
        // A refreshed snapshot with a new display title still groups by source book identity.
        compose.runOnIdle {
            fixture.bookmarks = fixture.bookmarks.copy(items = listOf(differentBook, oldest, newest.copy(bookTitle = "Renamed book")))
        }
        heading(10, "2")
        heading(20, "1")
    }

    private fun show(fixture: Fixture, restoration: StateRestorationTester = StateRestorationTester(compose)): Fixture {
        restoration.setContent {
            VedaPreviewTheme {
                BookmarksScreen(fixture.bookmarks, library, onBack = { fixture.backs++ },
                    onSearch = { fixture.searches++ }, onOpen = { fixture.openedBookmarks += it }, onRemove = {},
                    onRetry = {}, onFeedbackShown = {}, highlights = fixture.highlights,
                    onOpenHighlight = { fixture.opened += it }, tab = fixture.tab, onTabChange = { fixture.tab = it },
                    onMore = { fixture.more++ }, onHome = { fixture.homes++ },
                    onEditNote = { id, note ->
                        fixture.edits += id to note
                        fixture.highlights = fixture.highlights.copy(busy = true)
                    }, onRemoveNote = { annotation ->
                        fixture.removedNotes += annotation
                        fixture.highlights = fixture.highlights.copy(items = fixture.highlights.items.mapNotNull {
                            if (it.id != annotation.id) it else if (it.highlighted) it.copy(note = "") else null
                        })
                    }, onRemoveHighlight = { annotation ->
                        fixture.removedHighlights += annotation
                        fixture.highlights = fixture.highlights.copy(items = fixture.highlights.items.mapNotNull {
                            if (it.id != annotation.id) it else if (it.hasNote) it.copy(highlighted = false) else null
                        })
                    })
            }
        }
        return fixture
    }

    private fun reveal(tag: String) = compose.onNodeWithTag("highlights-list").performScrollToNode(hasTestTag(tag))

    private fun openEditor(annotation: Highlight) {
        reveal("note-edit-${annotation.id}")
        val edit = compose.onNodeWithTag("note-edit-${annotation.id}").assertContentDescriptionEquals(label(R.string.note_edit))
        val bounds = edit.getUnclippedBoundsInRoot()
        assertTrue(bounds.right.value - bounds.left.value >= 47.9f)
        assertTrue(bounds.bottom.value - bounds.top.value >= 47.9f)
        edit.performClick()
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
    }

    private fun assertDraft(expected: String) {
        assertEquals(expected, compose.onNodeWithTag("note-editor-field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    private fun annotation(id: String, highlighted: Boolean, note: String, time: Long) = Highlight(
        id, location, "Synthetic book", 120, 0, 120, 18, "Synthetic passage.", HighlightColor.BLUE, time,
        note = note, highlighted = highlighted)

    private fun bookmark(id: String, bookRecord: Int, time: Long) = Bookmark(
        id, location.copy(title = "Reference $id"), bookRecord, "Identical book title", "", time)

    private fun label(id: Int) = compose.activity.getString(id)

    private class Fixture(items: List<Highlight> = emptyList(), selectedTab: Int = 2) {
        var tab by mutableStateOf(selectedTab)
        var highlights by mutableStateOf(HighlightUiState(items = items, loading = false))
        var bookmarks by mutableStateOf(BookmarkUiState(loading = false))
        var backs = 0
        var homes = 0
        var searches = 0
        var more = 0
        val edits = mutableListOf<Pair<String, String>>()
        val removedNotes = mutableListOf<Highlight>()
        val removedHighlights = mutableListOf<Highlight>()
        val opened = mutableListOf<Highlight>()
        val openedBookmarks = mutableListOf<Bookmark>()
    }
}