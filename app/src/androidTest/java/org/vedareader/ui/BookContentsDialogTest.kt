package org.vedareader.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.BookEntry
import org.vedareader.data.BookContentsItem
import org.vedareader.data.bookContents
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.ContentNode
import org.vedareader.data.ReadingUnitKind

/** The dialog itself, with synthetic metadata and recorded callbacks; no reader/DB I/O. */
@RunWith(AndroidJUnit4::class)
class BookContentsDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = ContentNode(100, 0, "Synthetic contents", 2599, 25, 1)
    private val front = ContentNode(110, 100, "Preface", 199, 0, 2)
    private val chapters = List(24) { index ->
        val start = 200 + index * 100
        ContentNode(start, book.record, "Chapter ${index + 1}", start + 99, 2, 2)
    }
    private val verses = chapters.flatMap { chapter -> listOf(
        ContentNode(chapter.record + 10, chapter.record, "Bg 1.16, Bg 1.17, Bg 1.18", chapter.record + 49, 0, 3),
        ContentNode(chapter.record + 50, chapter.record, "", chapter.end, 0, 3),
    ) }
    private val outline = BookOutline(book, buildList {
        add(BookEntry(book.copy(end = 109), book, ReadingUnitKind.SECTION, ""))
        add(BookEntry(front, front, ReadingUnitKind.SECTION, book.title))
        chapters.forEach { chapter ->
            add(BookEntry(chapter.copy(end = chapter.record + 9), chapter, ReadingUnitKind.CHAPTER, book.title))
            verses.filter { it.parent == chapter.record }.forEach { verse ->
                add(BookEntry(verse, verse, ReadingUnitKind.VERSE, chapter.title))
            }
        }
    })

    @Test
    fun selectedChapterStartsCollapsedAndExpansionNeverNavigatesButVerseAndReadStartDo() {
        val selected = verses[1]
        val fixture = showDialog(selected.record)
        val chapter = chapters.first()
        assertChapter(chapter, expanded = false)
        assertChapter(chapters[1], expanded = false)
        compose.onNodeWithTag("book-entry-${selected.record}").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-${chapter.record}").assertDoesNotExist()

        compose.onNodeWithTag("book-chapter-${chapter.record}").performClick()
        assertChapter(chapter, expanded = true)
        compose.onNodeWithTag("book-entry-${chapter.record}").assertIsDisplayed()
            .assertTextEquals(label(R.string.book_contents_read_start))
        compose.runOnIdle { assertTrue(fixture.selections.isEmpty()); assertEquals(0, fixture.dismissals) }
        scrollTo("book-entry-${selected.record}")
        compose.onNodeWithTag("book-entry-${selected.record}").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).performClick()
        compose.runOnIdle { assertEquals(listOf(selected.record), fixture.selections) }

        // The callback-only harness deliberately leaves the dialog mounted to exercise collapse.
        scrollTo("book-chapter-${chapter.record}")
        compose.onNodeWithTag("book-chapter-${chapter.record}").performClick()
        assertChapter(chapter, expanded = false)
        compose.onNodeWithTag("book-entry-${selected.record}").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-${verses.first().record}").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-${chapter.record}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(selected.record), fixture.selections) }

        compose.onNodeWithTag("book-chapter-${chapter.record}").performClick()
        compose.onNodeWithTag("book-entry-${chapter.record}").performClick()
        compose.runOnIdle {
            assertEquals(listOf(selected.record, chapter.record), fixture.selections)
            assertEquals(0, fixture.dismissals)
        }
    }

    @Test
    fun combinedAndBlankLabelsUseOriginalIdsAndFrontMatterReadsWithoutExpansion() {
        val fixture = showDialog(front.record)
        compose.onNodeWithTag("book-entry-${front.record}").assertIsDisplayed()
            .assertTextEquals("Preface").performClick()
        compose.onNodeWithTag("book-chapter-${front.record}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(front.record), fixture.selections) }

        val chapter = chapters.first()
        scrollTo("book-chapter-${chapter.record}")
        compose.onNodeWithTag("book-chapter-${chapter.record}").performClick()
        val combined = verses.first()
        val blank = verses[1]
        scrollTo("book-entry-${combined.record}")
        compose.onNodeWithTag("book-entry-${combined.record}").assertIsDisplayed()
            .assertTextEquals("BG 1.16 - 18").performClick()
        scrollTo("book-entry-${blank.record}")
        compose.onNodeWithTag("book-entry-${blank.record}").assertIsDisplayed()
            .assertTextEquals(compose.activity.getString(R.string.browse_untitled, blank.record)).performClick()
        compose.onNodeWithTag("book-entry-16").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-17").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-18").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(front.record, combined.record, blank.record), fixture.selections)
            assertEquals("Bg 1.16, Bg 1.17, Bg 1.18", combined.title)
            assertEquals("", blank.title)
        }
    }

    @Test
    fun longContentsScrollsAndCloseReopenResetsAllExpandedChaptersWithoutNavigation() {
        val fixture = showDialog(verses[1].record)
        val initialScroll = scrollPosition()
        val first = chapters.first()
        compose.onNodeWithTag("book-chapter-${first.record}").performClick()
        assertChapter(first, expanded = true)
        val last = chapters.last()
        scrollTo("book-chapter-${last.record}")
        assertChapter(last, expanded = false)
        compose.onNodeWithTag("book-chapter-${last.record}").performClick()
        scrollTo("book-entry-${verses.last().record}")
        compose.onNodeWithTag("book-entry-${verses.last().record}").assertIsDisplayed()
        assertTrue("Exercise real lazy-list scrolling", scrollPosition() > initialScroll)
        compose.onNodeWithText(label(R.string.reader_close_contents)).performClick()
        compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.dismissals); assertTrue(fixture.selections.isEmpty()) }

        compose.onNodeWithTag("reopen-contents").performClick()
        awaitContents()
        assertEquals(initialScroll, scrollPosition(), .001f)
        assertChapter(first, expanded = false) // Even the selected verse's parent resets.
        compose.onNodeWithTag("book-entry-${verses[1].record}").assertDoesNotExist()
        scrollTo("book-chapter-${last.record}")
        assertChapter(last, expanded = false)
        compose.onNodeWithTag("book-entry-${last.record}").assertDoesNotExist()
        compose.onNodeWithTag("book-entry-${verses.last().record}").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.dismissals); assertTrue(fixture.selections.isEmpty()) }
    }

    @Test
    fun slowContentsPreparationIsOffMainAndCanBeDismissedWithoutPublishingLateRows() {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicBoolean(false)
        val offMain = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val fixture = showDialog(verses.last().record, waitForContents = false) { source ->
            offMain.set(Looper.myLooper() != Looper.getMainLooper())
            started.set(true)
            try { gate.await(); bookContents(source) } finally { finished.set(true) }
        }
        try {
            compose.waitUntil(5_000) { started.get() }
            assertTrue(offMain.get())
            compose.onNodeWithTag("reader-contents-loading").assertIsDisplayed()
            compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.reader_close_contents)).performClick()
            compose.waitUntil(5_000) { finished.get() }
            gate.complete(Unit)
            compose.onNodeWithTag("reader-contents-loading").assertDoesNotExist()
            compose.onNodeWithTag("reader-contents-error").assertDoesNotExist()
            compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, fixture.dismissals); assertTrue(fixture.selections.isEmpty()) }
        } finally { gate.cancel() }
    }

    @Test
    fun preparationFailureCanRetryAndLandAtTheSelectedCollapsedChapter() {
        val attempts = AtomicInteger()
        val fixture = showDialog(verses.last().record, waitForContents = false) { source ->
            if (attempts.incrementAndGet() == 1) error("Synthetic preparation failure")
            bookContents(source)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-contents-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-contents-error").assertIsDisplayed()
        compose.onNodeWithTag("reader-contents-retry").performClick()
        awaitContents()
        assertChapter(chapters.last(), expanded = false)
        compose.onNodeWithTag("book-entry-${verses.last().record}").assertDoesNotExist()
        compose.onNodeWithTag("reader-contents-loading").assertDoesNotExist()
        compose.onNodeWithTag("reader-contents-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts.get()); assertTrue(fixture.selections.isEmpty()) }
    }

    private fun showDialog(selected: Int, waitForContents: Boolean = true,
        loader: suspend (BookOutline) -> List<BookContentsItem> = { bookContents(it) }): Fixture {
        val fixture = Fixture()
        val position = BookPosition(outline, outline.entries.indexOfFirst { it.node.record == selected })
        compose.setContent {
            VedaPreviewTheme {
                TextButton(onClick = { fixture.open = true }, modifier = Modifier.testTag("reopen-contents")) {
                    Text("Open synthetic contents")
                }
                if (fixture.open) HierarchicalBookContentsDialog(position,
                    onDismiss = { fixture.dismissals++; fixture.open = false },
                    onSelect = { fixture.selections += it }, loadContents = loader)
            }
        }
        if (waitForContents) awaitContents()
        return fixture
    }

    private fun awaitContents() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-book-contents-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-book-contents-list").assertIsDisplayed()
    }

    private fun assertChapter(chapter: ContentNode, expanded: Boolean) {
        compose.onNodeWithTag("book-chapter-${chapter.record}").assertIsDisplayed().assertTextEquals(chapter.title)
            .assertContentDescriptionEquals(compose.activity.getString(
                if (expanded) R.string.book_contents_collapse else R.string.book_contents_expand, chapter.title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                label(if (expanded) R.string.book_contents_expanded else R.string.book_contents_collapsed)))
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("reader-book-contents-list")
        .performScrollToNode(hasTestTag(tag))

    private fun scrollPosition(): Float = compose.onNodeWithTag("reader-book-contents-list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun label(id: Int) = compose.activity.getString(id)

    private class Fixture {
        var open by mutableStateOf(true)
        val selections = mutableListOf<Int>()
        var dismissals = 0
    }
}