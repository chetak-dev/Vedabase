package org.vedareader.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightStore
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.FolioText
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState

/** Real screen/native host, synthetic callback owner. No MainActivity, store, preferences or DB. */
@RunWith(AndroidJUnit4::class)
class ReaderAnnotationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun highlightRequestsSaveImmediatelyInCurrentDefaultAndCreationNeverForcesATray() {
        val fixture = showReader()
        select(note = false)
        compose.waitUntil(5_000) { fixture.acknowledged.isNotEmpty() }
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(SELECTION to false), fixture.highlightRequests)
            assertEquals(HighlightColor.GREEN, fixture.items.single().color)
            assertTrue(fixture.items.single().highlighted)
            assertTrue(fixture.notesCreated.isEmpty())
            assertEquals(0, fixture.recolorCalls)
        }
    }

    @Test
    fun onlyReadingSettingsOffersFourLabelled48dpDefaultColourChoices() {
        val fixture = showReader()
        compose.onNodeWithTag("reader-text-size").performClick()
        for (color in HighlightColor.entries) {
            val choice = compose.onNodeWithTag("highlight-color-${color.name}").performScrollTo()
            choice.assertIsDisplayed().assertContentDescriptionEquals(compose.activity.getString(when (color) {
                HighlightColor.YELLOW -> R.string.highlight_color_yellow
                HighlightColor.GREEN -> R.string.highlight_color_green
                HighlightColor.BLUE -> R.string.highlight_color_blue
                HighlightColor.PINK -> R.string.highlight_color_pink
            }))
            val bounds = choice.getUnclippedBoundsInRoot()
            assertTrue(bounds.right.value - bounds.left.value >= 47.9f)
            assertTrue(bounds.bottom.value - bounds.top.value >= 47.9f)
            choice.performClick().assertIsSelected()
            for (other in HighlightColor.entries.filter { it != color }) {
                compose.onNodeWithTag("highlight-color-${other.name}").assertIsNotSelected()
            }
            choice.performClick() // A second click is not another persistence request.
            compose.onNodeWithTag("reader-text-size-done").assertIsDisplayed()
        }
        compose.onNodeWithTag("reader-text-size-done").performClick()
        select(note = false)
        compose.runOnIdle { assertEquals(HighlightColor.PINK, fixture.items.single().color) }
        compose.runOnIdle { host().onHighlightTapped(fixture.items.single().id) }
        compose.onNodeWithTag("highlight-palette").assertIsDisplayed()
        for (color in HighlightColor.entries) compose.onNodeWithTag("highlight-color-${color.name}").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(HighlightColor.entries.toList(), fixture.defaultChanges)
            assertEquals(0, fixture.recolorCalls)
        }
    }

    @Test
    fun noteSelectionAndCancelNeverRequestAHighlightOrPersistAnAnnotation() {
        val fixture = showReader()
        select(note = true)
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
        compose.onNodeWithTag("note-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Draft only कृष्ण")
        compose.onNodeWithTag("note-editor-cancel").performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(fixture.items.isEmpty())
            assertTrue(fixture.highlightRequests.isEmpty())
            assertTrue(fixture.notesCreated.isEmpty())
            assertTrue(fixture.notesEdited.isEmpty())
        }
    }

    @Test
    fun savingStandaloneNoteCreatesOnlyNoteThenTapOpensExistingEditorWithoutForcedReopen() {
        val fixture = showReader()
        select(note = true)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("  Independent note कृष्ण  ")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.waitUntil(5_000) { fixture.acknowledged.isNotEmpty() }
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(SELECTION to "Independent note कृष्ण"), fixture.notesCreated)
            assertTrue(fixture.highlightRequests.isEmpty())
            val note = fixture.items.single()
            assertFalse(note.highlighted)
            assertFalse(note.span.highlighted)
            assertTrue(note.span.noted)
            host().onHighlightTapped(note.id)
        }
        compose.onNodeWithTag("note-editor-field").assertTextContains("Independent note कृष्ण")
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.onNodeWithTag("note-editor-cancel").performClick()
        compose.runOnIdle { assertEquals(1, fixture.items.size); assertTrue(fixture.notesEdited.isEmpty()) }
    }

    @Test
    fun noteOnExactExistingRangeUsesEditAndPreservesExplicitHighlightAndColor() {
        val fixture = Fixture().apply { items = listOf(annotation("old", highlighted = true, note = "Old reflection", color = HighlightColor.BLUE)) }
        showReader(fixture)
        select(note = true)
        compose.onNodeWithTag("note-editor-field").assertTextContains("Old reflection").performTextReplacement("Edited reflection")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("old" to "Edited reflection"), fixture.notesEdited)
            assertTrue(fixture.notesCreated.isEmpty())
            assertTrue(fixture.highlightRequests.isEmpty())
            assertEquals(annotation("old", true, "Edited reflection", HighlightColor.BLUE), fixture.items.single())
        }
    }

    @Test
    fun clearingStandaloneNoteRemovesItsAnnotationWithoutCreatingAHighlight() {
        val fixture = Fixture().apply { items = listOf(annotation("note-only", false, "Remove this note")) }
        showReader(fixture)
        compose.runOnIdle { host().onHighlightTapped("note-only") }
        compose.onNodeWithTag("note-editor-field").performTextReplacement("")
        compose.onNodeWithTag("note-editor-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("note-only" to ""), fixture.notesEdited)
            assertTrue(fixture.items.isEmpty())
            assertTrue(fixture.highlightRequests.isEmpty())
            assertTrue(fixture.notesCreated.isEmpty())
        }
    }

    @Test
    fun selectionCoordinatesEditorIdentityAndDraftSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val fixture = showReader(restoration = restoration)
        select(note = true)
        val draft = "Retain this unfinished note कृष्ण a\u0304"
        compose.onNodeWithTag("note-editor-field").performTextReplacement(draft)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("note-editor-field").assertTextContains(draft)
        compose.runOnIdle { assertTrue(fixture.notesCreated.isEmpty()); assertTrue(fixture.items.isEmpty()) }
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.runOnIdle { assertEquals(listOf(SELECTION to draft), fixture.notesCreated) }
    }

    @Test
    fun existingAnnotationIdAndDraftSurviveRestorationWithoutCreatingADuplicate() {
        val restoration = StateRestorationTester(compose)
        val fixture = Fixture().apply { items = listOf(annotation("existing", false, "Original")) }
        showReader(fixture, restoration)
        select(note = true)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Changed but not yet saved")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("note-editor-field").assertTextContains("Changed but not yet saved")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("existing" to "Changed but not yet saved"), fixture.notesEdited)
            assertTrue(fixture.notesCreated.isEmpty())
            assertFalse(fixture.items.single().highlighted)
        }
    }

    @Test
    fun failedSaveRetainsDraftShowsErrorAndAllowsRetry() {
        val fixture = showReader(Fixture().apply { failWrites = true })
        select(note = true)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Do not lose this draft")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.onNodeWithTag("note-editor-field").assertTextContains("Do not lose this draft")
        compose.onNodeWithTag("note-editor-error").assertIsDisplayed()
        compose.onNodeWithTag("note-editor-save").assertIsEnabled()
        compose.runOnIdle { assertTrue(fixture.items.isEmpty()); fixture.failWrites = false }
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Do not lose this draft", fixture.items.single().note) }
    }

    @Test
    fun saveDoesNotDiscardDraftBeforeCommitAndOversizedInputIsVisibleRatherThanTruncated() {
        val fixture = showReader(Fixture().apply { holdWrites = true })
        select(note = true)
        val oversized = "x".repeat(HighlightStore.MAX_NOTE + 1)
        compose.onNodeWithTag("note-editor-field").performTextReplacement(oversized)
        compose.onNodeWithTag("note-editor-field").assertTextContains(oversized)
        compose.onNodeWithTag("note-editor-error").assertIsDisplayed()
        compose.onNodeWithTag("note-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Pending commit")
        compose.onNodeWithTag("note-editor-save").performClick()
        compose.onNodeWithTag("note-editor-field").assertTextContains("Pending commit")
        compose.onNodeWithTag("note-editor-save").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.items.isEmpty()); fixture.items = listOf(annotation("saved", false, "Pending commit")) }
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
    }

    @Test
    fun savedNoticeWithExplicitUndoIdDispatchesUndoWithoutOpeningDetails() {
        val fixture = showReader()
        select(note = false)
        compose.onNodeWithText(compose.activity.getString(R.string.annotation_undo)).performClick()
        compose.runOnIdle { assertEquals(listOf(fixture.items.single().id), fixture.undone) }
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
    }

    private fun showReader(fixture: Fixture = Fixture(), restoration: StateRestorationTester? = null): Fixture {
        val node = ContentNode(10, 0, "Synthetic annotations", 10, 0, 1)
        val section = ReadingSection(node, ReadingUnitKind.SECTION, node.title,
            listOf(Paragraph(10, SOURCE, "PA_Purport", "")))
        val position = BookPosition(BookOutline(node, listOf(BookEntry(node, node, ReadingUnitKind.SECTION, node.title))), 0)
        val document = ReaderDocument(section, FolioText.document(section.paragraphs, "en", ReaderPreferences(),
            bookMode = true, scrollingBook = true), position, BookResource(section, position, 0, 0, 10))
        val state = ReaderState(LOCATION, "en", document, loading = false)
        val content: @Composable () -> Unit = {
            VedaPreviewTheme {
                ReviewedReaderScreen(state, {}, {}, {}, {}, {}, highlights = fixture.items,
                    defaultHighlightColor = fixture.color,
                    onDefaultHighlightColorChange = { fixture.color = it; fixture.defaultChanges += it },
                    onHighlightCreate = { selection, note ->
                        fixture.highlightRequests += selection to note
                        fixture.items = listOf(annotation("mark-${++fixture.token}", true, color = fixture.color))
                        fixture.created = HighlightCreation(fixture.token, fixture.items.single().id, false)
                        fixture.notice = HighlightNotice(fixture.token, HighlightMessage.SAVED, fixture.items.single().id)
                    },
                    onHighlightRecolor = { _, _ -> fixture.recolorCalls++ },
                    onNoteCreate = { selection, note ->
                        fixture.notesCreated += selection to note
                        if (fixture.failWrites) fixture.notice = HighlightNotice(++fixture.token, HighlightMessage.FAILED)
                        else if (!fixture.holdWrites) {
                            fixture.items = listOf(annotation("note-${++fixture.token}", false, note))
                            fixture.created = HighlightCreation(fixture.token, fixture.items.single().id, true)
                        }
                    },
                    onHighlightNote = { id, note ->
                        fixture.notesEdited += id to note
                        fixture.items = fixture.items.mapNotNull {
                            if (it.id != id) it else if (!it.highlighted && note.isEmpty()) null else it.copy(note = note)
                        }
                    },
                    highlightCreated = fixture.created,
                    onHighlightCreatedShown = { fixture.acknowledged += it; fixture.created = null },
                    highlightNotice = fixture.notice,
                    onHighlightNoticeShown = { fixture.notice = null },
                    onUndoHighlight = { fixture.undone += it })
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        compose.waitUntil(20_000) { compose.runOnIdle { findHost(compose.activity.window.decorView)?.settledView?.title == "Reader" } }
        return fixture
    }

    private fun select(note: Boolean) = compose.runOnIdle { host().onHighlightSelected(SELECTION, note) }
    private fun host(): ReaderPageHost = requireNotNull(findHost(compose.activity.window.decorView))
    private fun findHost(view: View): ReaderPageHost? {
        if (view is ReaderPageHost) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private class Fixture {
        var items by mutableStateOf(emptyList<Highlight>())
        var color by mutableStateOf(HighlightColor.GREEN)
        var created by mutableStateOf<HighlightCreation?>(null)
        var notice by mutableStateOf<HighlightNotice?>(null)
        var token = 0L
        var recolorCalls = 0
        var failWrites = false
        var holdWrites = false
        val highlightRequests = mutableListOf<Pair<HighlightSelection, Boolean>>()
        val notesCreated = mutableListOf<Pair<HighlightSelection, String>>()
        val notesEdited = mutableListOf<Pair<String, String>>()
        val defaultChanges = mutableListOf<HighlightColor>()
        val acknowledged = mutableListOf<Long>()
        val undone = mutableListOf<String>()
    }

    companion object {
        private const val SOURCE = "Synthetic कृष्ण ā passage for independent annotations."
        private val LOCATION = ReadingLocation("synthetic-annotation-ui", "Synthetic passage", 10, 10, 10)
        private val SELECTION = HighlightSelection(10, 0, 10, SOURCE.length, SOURCE)
        private fun annotation(id: String, highlighted: Boolean, note: String = "", color: HighlightColor = HighlightColor.GREEN) =
            Highlight(id, LOCATION, "Synthetic book", 10, 0, 10, SOURCE.length, SOURCE, color, 100L, note, highlighted)
    }
}