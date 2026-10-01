package org.vedareader.ui

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ChapterOverview
import org.vedareader.data.ContentNode
import org.vedareader.data.LibraryInfo
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import org.vedareader.data.VersePreview
import org.vedareader.reader.BookPageInfo
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Real controller, Compose controls and secure native host; only synthetic, in-memory source data.
 * No MainActivity, ViewModel, registry, database or preferences I/O. BOOK uses production Folio
 * columns/CSP and closed, read-only test measurements; VERSE never enables JavaScript.
 */
@RunWith(AndroidJUnit4::class)
class ReaderBookScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo("synthetic-book-screen", "Synthetic book library", "en",
        "synthetic-only.ivd", 32_768L, 100L, "Synthetic fixture only")
    private val unicode = "नमूना বাংলা — ā ś ṛ — a\u0304"
    private val book = ContentNode(100, 0, "Synthetic book Ω", 1999, 2, 1, "bgaiiwa")
    private val chapterOne = ContentNode(110, book.record, "Synthetic first chapter", 1399, 12, 2)
    private val chapterTwo = ContentNode(1400, book.record, "Synthetic other chapter", 1999, 2, 2)
    private val firstVerses = List(12) { index ->
        val record = 200 + index * 100
        ContentNode(record, chapterOne.record, "Synthetic verse ${index + 1}", record + 99, 0, 3)
    }
    private val otherVerses = listOf(
        ContentNode(1500, chapterTwo.record, "Synthetic other-chapter opening", 1599, 0, 3),
        ContentNode(1600, chapterTwo.record, "Synthetic other-chapter ending", 1999, 0, 3),
    )
    private val selected = firstVerses[8]
    private val nextVerse = firstVerses[9]
    private val longPurportRecord = selected.record + 2
    private val verses = firstVerses + otherVerses
    private val overview = ChapterOverview(chapterOne, firstVerses.map {
        VersePreview(it, "Synthetic preview ${it.record}: $unicode. " +
            "This original fixture translation keeps the outer chapter list scrollable. ".repeat(3))
    })
    // Exact contiguous coverage, including the book/chapter headings. Unequal record spans
    // distinguish source-entry locations from percentages, record offsets and rendered pages.
    private val outline = BookOutline(book, buildList {
        add(BookEntry(book.copy(end = 109), book, ReadingUnitKind.SECTION, ""))
        add(BookEntry(chapterOne.copy(end = 199), chapterOne, ReadingUnitKind.CHAPTER, book.displayTitle))
        firstVerses.forEach { add(BookEntry(it, it, ReadingUnitKind.VERSE, chapterOne.displayTitle)) }
        add(BookEntry(chapterTwo.copy(end = 1499), chapterTwo, ReadingUnitKind.CHAPTER, book.displayTitle))
        otherVerses.forEach { add(BookEntry(it, it, ReadingUnitKind.VERSE, chapterTwo.displayTitle)) }
    })
    private val chapterTwoIndex = firstVerses.size + 2
    private val resourceEntries = listOf(0..0, 1 until chapterTwoIndex, chapterTwoIndex..outline.entries.lastIndex)
    private val entryParagraphs = outline.entries.associate { it.node.record to paragraphs(it) }
    private val verseDocuments = verses.mapIndexed { index, node ->
        val entry = outline.entries.single { it.node == node }
        val paragraphs = entryParagraphs.getValue(node.record)
        node.record to ReaderDocument(
            ReadingSection(node, ReadingUnitKind.VERSE, entry.parentTitle, paragraphs,
                verses.getOrNull(index - 1), verses.getOrNull(index + 1)),
            FolioText.document(paragraphs, library.languageTag, ReaderPreferences(), compactVerse = true, bookMode = false),
        )
    }.toMap()
    private val bookControlTags = listOf("reader-book-contents", "reader-book-progress", "reader-book-seek",
        "reader-book-previous", "reader-book-next")
    private val chromeTags = listOf("reader-back", "reader-title", "reader-context", "reader-mode-toggle") + bookControlTags

    @Test
    fun headerToggleMapsTheLiveVisibleVerseWithoutReusingPixelsFromAnotherLayout() {
        val fixture = showReader()
        val verseView = assertVerseReader(fixture, selected)
        assertVerseText(unicode)
        assertVerseText(entryParagraphs.getValue(selected.record).last().text)
        val offset = resetLongVerseScroll(fixture, verseView)
        val host = compose.runOnIdle { requireNotNull(readerHost()) }
        assertActions(R.string.reader_previous_verse, R.string.reader_next_verse)

        compose.onNodeWithTag("reader-mode-toggle").performClick()

        val bookView = assertBookReader(fixture, selected.record)
        compose.runOnIdle {
            assertSame(host, readerHost())
            assertNotSame(verseView, bookView)
            assertTrue(verseView.isReleased)
            assertTrue(offset > 0)
            assertEquals(0, bookView.scrollY)
            assertEquals(0, fixture.controller.state?.scrollY)
            assertEquals(chapterOne.record, fixture.controller.state?.location?.start)
        }

        // The resource was opened at selected, but its live anchor now belongs to another verse.
        val nextPurport = measureBook(bookView).record(nextVerse.record + 2)
        assertTrue("The next verse must have a real continuation column", nextPurport.pages.size >= 2)
        turnToPage(fixture, bookView, nextPurport.pages[1])
        val live = assertBookPage(fixture, bookView)
        assertEquals(nextVerse, entryAt(live.anchorRecord).source)
        compose.runOnIdle {
            val document = requireNotNull(fixture.controller.state?.document)
            assertEquals(selected.record, document.bookResource?.anchorRecord)
            assertEquals(BookPosition(outline, entryIndex(selected.record)), document.bookResource?.position)
            assertEquals(nextVerse.displayTitle, fixture.controller.state?.location?.title)
            assertEquals(live.anchorRecord, fixture.controller.state?.location?.position)
            assertEquals(2, fixture.requests.size)
        }
        compose.onNodeWithTag("reader-mode-toggle").performClick()

        val restored = assertVerseReader(fixture, nextVerse)
        assertVerseScroll(fixture, restored, 0)
        assertActions(R.string.reader_previous_verse, R.string.reader_next_verse)
        compose.runOnIdle {
            assertSame(host, readerHost())
            assertNotSame(bookView, restored)
            assertTrue(bookView.isReleased)
            assertEquals(listOf(ReadingMode.BOOK, ReadingMode.VERSE), fixture.modeChanges)
        }
        assertVerseText(unicode)
        assertVerseText(entryParagraphs.getValue(nextVerse.record).last().text)
        assertVerseText(entryParagraphs.getValue(selected.record).last().text, expectedMatches = 0)

        // VERSE still swaps whole units, even when the previous BOOK column was mid-purport.
        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertVerseScroll(fixture, assertVerseReader(fixture, firstVerses[10]), 0)
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertVerseScroll(fixture, assertVerseReader(fixture, nextVerse), 0)
        compose.runOnIdle {
            assertEquals(listOf(request(ReadingMode.VERSE), request(ReadingMode.BOOK),
                request(ReadingMode.VERSE, nextVerse), request(ReadingMode.VERSE, firstVerses[10]),
                request(ReadingMode.VERSE, nextVerse)), fixture.requests)
        }
    }

    @Test
    fun nativeBookSwipesTurnHorizontalColumnsAndRequestANeighbourOnlyAfterTheLastPage() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val view = assertBookReader(fixture, selected.record)
        val before = assertBookPage(fixture, view)
        assertTrue("This must exercise several real columns", before.count > before.page + 3)

        compose.onNodeWithTag("reader-document").performTouchInput { swipeUp(durationMillis = 400) }
        compose.onNodeWithTag("reader-document").performTouchInput { swipeDown(durationMillis = 400) }
        awaitNativeVisualState(view)
        assertEquals("Vertical gestures cannot scroll, turn or hide BOOK chrome", before, assertBookPage(fixture, view))
        assertLiveBookChrome(fixture, view)
        compose.runOnIdle {
            view.scrollTo(view.scrollX, 9_999)
            assertEquals(0, view.scrollY)
            assertEquals(listOf(request(ReadingMode.BOOK)), fixture.requests)
        }

        turnPage(fixture, view, forward = true)
        turnPage(fixture, view, forward = false)
        assertEquals(before, assertBookPage(fixture, view))
        turnToPage(fixture, view, before.count - 1)
        compose.onNodeWithTag("reader-book-next").assertIsEnabled().performTouchInput { click() }

        val next = assertBookReader(fixture, chapterTwo.record)
        compose.runOnIdle {
            assertNotSame(view, next)
            assertTrue(view.isReleased)
            assertEquals(2, fixture.requests.size)
        }
        assertEquals(0, assertBookPage(fixture, next).page)
        turnToPage(fixture, next, assertBookPage(fixture, next).count - 1)
        val last = assertBookPage(fixture, next)
        compose.onNodeWithTag("reader-book-next").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        awaitNativeVisualState(next)
        assertEquals(last, assertBookPage(fixture, next))
        compose.runOnIdle { assertEquals(2, fixture.requests.size) }

        turnToPage(fixture, next, 0)
        compose.onNodeWithTag("reader-book-previous").assertIsEnabled().performTouchInput { click() }
        val returned = assertBookReader(fixture, chapterOne.end, atEnd = true)
        val finalPage = assertBookPage(fixture, returned)
        assertEquals(finalPage.count - 1, finalPage.page)
        compose.runOnIdle {
            assertTrue(next.isReleased)
            assertFalse(requireNotNull(fixture.controller.state).enterAtEnd)
            assertTrue(fixture.scrollReports.isEmpty())
            assertEquals(listOf(request(ReadingMode.BOOK), request(ReadingMode.BOOK, chapterTwo),
                Request(ReadingMode.BOOK, chapterOne.location(library.id).copy(position = chapterOne.end), library.languageTag)),
                fixture.requests)
        }
    }

    @Test
    fun aVerseEndingAndTheNextVerseHeadingCanShareTheSamePaintedColumn() {
        val fixture = showReader(initialMode = ReadingMode.BOOK, node = firstVerses.first())
        val view = assertBookReader(fixture, firstVerses.first().record)
        val layout = measureBook(view)
        // Derive the column from laid-out fragments, not a device-specific font/viewport guess.
        val pair = firstVerses.take(8).zipWithNext().firstOrNull { (left, right) ->
            layout.record(left.end).pages.last() == layout.record(right.record).pages.first()
        } ?: throw AssertionError("Short adjacent verses must flow together without per-verse page breaks")
        val page = layout.record(pair.first.end).pages.last()
        turnToPage(fixture, view, page)
        val painted = measureBook(view)
        val ending = painted.record(pair.first.end)
        val heading = painted.record(pair.second.record)
        assertTrue("The preceding verse's actual final text must be visible", ending.visible)
        assertTrue("The following title must be visible on that very same page", heading.visible)
        assertEquals(entryParagraphs.getValue(pair.first.record).last().text, ending.text)
        assertEquals(pair.second.displayTitle, heading.text)
        assertEquals(page, ending.pages.last())
        assertEquals(page, heading.pages.first())
        compose.runOnIdle {
            assertEquals(1, fixture.requests.size)
            assertEquals(chapterOne, fixture.controller.state?.document?.section?.node)
        }
    }

    @Test
    fun accessiblePageActionsAndFooterButtonsUseColumnsThenRestoreWholeVerseActions() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val view = assertBookReader(fixture, selected.record)
        turnToPage(fixture, view, interiorPurportPage(view))
        val before = assertBookPage(fixture, view)
        assertActions(R.string.reader_hide_controls, R.string.reader_previous_page, R.string.reader_next_page)
        turnPage(fixture, view, forward = true) { invokeAction(R.string.reader_next_page) }
        turnPage(fixture, view, forward = false) { invokeAction(R.string.reader_previous_page) }
        assertEquals(before, assertBookPage(fixture, view))
        turnPage(fixture, view, forward = true) {
            compose.onNodeWithTag("reader-book-next").assertIsEnabled().performTouchInput { click() }
        }
        turnPage(fixture, view, forward = false) {
            compose.onNodeWithTag("reader-book-previous").assertIsEnabled().performTouchInput { click() }
        }
        assertEquals(before, assertBookPage(fixture, view))

        compose.onNodeWithTag("reader-mode-toggle").performClick()
        assertVerseScroll(fixture, assertVerseReader(fixture, selected), 0)
        assertActions(R.string.reader_previous_verse, R.string.reader_next_verse)
        invokeAction(R.string.reader_next_verse)
        assertVerseScroll(fixture, assertVerseReader(fixture, nextVerse), 0)
        compose.runOnIdle {
            assertEquals(listOf(request(ReadingMode.BOOK), request(ReadingMode.VERSE),
                request(ReadingMode.VERSE, nextVerse)), fixture.requests)
        }
    }

    @Test
    fun bookContentsSelectsRealChapterAndVerseEntriesAndDismissDoesNotNavigate() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val original = assertBookReader(fixture, selected.record)
        turnToPage(fixture, original, interiorPurportPage(original))
        val before = assertBookPage(fixture, original)
        openBookContents()
        compose.onNodeWithTag("book-entry-${selected.record}").assertIsDisplayed()
            .assertTextEquals(selected.displayTitle)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText(label(R.string.reader_close_contents)).assertIsEnabled().performClick()
        compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
        assertEquals(before, assertBookPage(fixture, original))
        assertLiveBookChrome(fixture, original)
        compose.runOnIdle { assertSame(original, readerHost()?.settledView); assertEquals(1, fixture.requests.size) }

        val chapterEntry = outline.entries.single { it.source == chapterTwo }.node
        selectBookEntry(chapterEntry)
        val chapterView = assertBookReader(fixture, chapterEntry.record)
        compose.onNodeWithTag("reader-context").assertTextEquals(book.displayTitle)
        compose.runOnIdle {
            val loaded = requireNotNull(fixture.controller.state?.document)
            assertEquals(ReadingUnitKind.CHAPTER, loaded.section.kind)
            assertEquals(chapterTwo, loaded.bookResource?.source)
            assertTrue(original.isReleased)
        }
        assertFalse(measureBook(chapterView).records.any { it.record in selected.record..selected.end })
        val previousDocument = compose.runOnIdle { requireNotNull(fixture.controller.state?.document) }

        val target = otherVerses.last()
        selectBookEntry(target)
        val jumped = assertBookReader(fixture, target.record)
        compose.runOnIdle {
            val loaded = requireNotNull(fixture.controller.state?.document)
            assertEquals(previousDocument.section, loaded.section)
            assertEquals(previousDocument.html, loaded.html)
            assertNotSame(previousDocument, loaded)
            assertNotSame(chapterView, jumped)
            assertTrue(chapterView.isReleased)
            assertEquals(target, loaded.bookResource?.source)
            assertEquals(listOf(chapterEntry.record, target.record), fixture.entrySelections)
            assertEquals(listOf(request(ReadingMode.BOOK), request(ReadingMode.BOOK, chapterEntry),
                request(ReadingMode.BOOK, target)), fixture.requests)
        }
    }

    @Test
    fun bookContentsJumpAndBothBackActionsRestoreTheSameOuterChapterAndSavedListPosition() {
        val fixture = showReader(initiallyReading = false)
        openOuterChapter(fixture)
        val list = "browse-list-${chapterOne.record}"
        val marker = "verse-preview-${selected.record}"
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(marker))
        compose.onNodeWithTag(marker).assertIsDisplayed()
        val before = scrollPosition(list)
        val top = compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot.top
        assertTrue("The outer chapter must really scroll before entering the reader", before > 0f)
        val outer = compose.runOnIdle { fixture.navigator.state }
        val browseCalls = compose.runOnIdle { fixture.browseCalls.toList() }

        for ((index, systemBack) in listOf(false, true).withIndex()) {
            // The second opening uses the mode selected before close, not a fresh default.
            compose.onNodeWithTag(marker).assertIsDisplayed().performClick()
            if (index == 0) {
                assertVerseReader(fixture, selected)
                compose.onNodeWithTag("reader-mode-toggle").performClick()
            }
            assertBookReader(fixture, selected.record)
            selectBookEntry(otherVerses.last())
            val native = assertBookReader(fixture, otherVerses.last().record)
            compose.runOnIdle {
                assertSame(outer, fixture.navigator.state)
                assertEquals(browseCalls, fixture.browseCalls)
            }
            if (systemBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("reader-back").performClick()

            waitFor(hasTestTag(list))
            // Do not scroll after Back: that would conceal lost saved list state.
            compose.onNodeWithTag(marker).assertIsDisplayed()
            compose.onNodeWithTag("browse-title").assertTextEquals(chapterOne.displayTitle)
            assertEquals(before, scrollPosition(list), .001f)
            assertEquals(top, compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot.top, .5f)
            compose.onNodeWithTag("reader-document").assertDoesNotExist()
            compose.onNodeWithTag("reader-book-progress").assertDoesNotExist()
            compose.onNodeWithTag("reader-book-seek").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(outer, fixture.navigator.state)
                assertEquals(browseCalls, fixture.browseCalls)
                assertEquals(listOf(book, chapterOne), fixture.navigator.state.path)
                assertNull(fixture.controller.state)
                assertNull(readerHost())
                assertTrue(native.isReleased)
                assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
                assertEquals(index + 1, fixture.backCalls)
                assertFalse(compose.activity.isFinishing)
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(ReadingMode.BOOK), fixture.modeChanges)
            assertEquals(listOf(request(ReadingMode.VERSE), request(ReadingMode.BOOK),
                request(ReadingMode.BOOK, otherVerses.last()), request(ReadingMode.BOOK),
                request(ReadingMode.BOOK, otherVerses.last())), fixture.requests)
        }
    }

    @Test
    fun sourceLocationStaysStableAcrossPurportPagesInsteadOfReportingAPercentOrPageNumber() {
        val fixture = showReader()
        assertVerseReader(fixture, selected)
        compose.onNodeWithTag("reader-mode-toggle").performClick()
        val view = assertBookReader(fixture, selected.record)
        turnToPage(fixture, view, interiorPurportPage(view))
        val before = assertBookPage(fixture, view)
        val index = entryIndex(selected.record)
        assertEquals(index, assertSourceLocation(view))
        assertTrue("Source-entry count must not accidentally equal a record percentage",
            index + 1 != (selected.record - book.record) * 100 / (book.end - book.record + 1))

        repeat(2) {
            turnPage(fixture, view, forward = true)
            assertEquals(selected, entryAt(assertBookPage(fixture, view).anchorRecord).source)
            assertEquals(index, assertSourceLocation(view))
        }
        val after = assertBookPage(fixture, view)
        assertEquals(before.page + 2, after.page)
        assertEquals(before.count, after.count)
        compose.runOnIdle {
            assertEquals(listOf(request(ReadingMode.VERSE), request(ReadingMode.BOOK)), fixture.requests)
        }
        compose.onNodeWithTag("reader-mode-toggle").performClick()
        assertVerseReader(fixture, selected)
    }

    @Test
    fun sourceSliderPreviewsWithoutLoadingThenJumpsOnReleaseAndSupportsAccessibleSeeking() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val original = assertBookReader(fixture, selected.record)
        val before = assertBookPage(fixture, original)
        val seek = compose.onNodeWithTag("reader-book-seek").assertIsDisplayed().assertIsEnabled()
        assertFullyVisibleAboveNavigationBar(seek)
        val lastIndex = outline.entries.lastIndex
        seek.performTouchInput {
            down(pointerId = 0, position = center)
            moveTo(pointerId = 0, position = Offset(width - 1f, centerY), delayMillis = 160)
        }
        try {
            waitFor(hasTestTag("reader-book-progress") and
                hasText(label(R.string.reader_book_progress, lastIndex + 1, outline.entries.size)))
            assertLocationControls(lastIndex)
            assertEquals("Dragging previews a source location without moving the document", before, assertBookPage(fixture, original))
            compose.runOnIdle {
                assertEquals(listOf(request(ReadingMode.BOOK)), fixture.requests)
                assertTrue(fixture.entrySelections.isEmpty())
            }
        } finally {
            seek.performTouchInput { up(pointerId = 0) }
        }

        val target = otherVerses.last()
        val jumped = assertBookReader(fixture, target.record)
        compose.runOnIdle {
            assertTrue(original.isReleased)
            assertEquals(listOf(target.record), fixture.entrySelections)
            assertEquals(listOf(request(ReadingMode.BOOK), request(ReadingMode.BOOK, target)), fixture.requests)
        }
        val accessibleTarget = firstVerses.first()
        compose.onNodeWithTag("reader-book-seek").performSemanticsAction(SemanticsActions.SetProgress) {
            assertTrue(it(entryIndex(accessibleTarget.record).toFloat()))
        }
        assertBookReader(fixture, accessibleTarget.record)
        compose.runOnIdle {
            assertTrue(jumped.isReleased)
            assertEquals(listOf(target.record, accessibleTarget.record), fixture.entrySelections)
            assertEquals(listOf(request(ReadingMode.BOOK), request(ReadingMode.BOOK, target),
                request(ReadingMode.BOOK, accessibleTarget)), fixture.requests)
        }
    }

    @Test
    fun edgeToEdgeFooterStaysFullyVisibleAndTappableOverLongPurportAfterTextReflow() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val view = assertBookReader(fixture, selected.record)
        turnToPage(fixture, view, interiorPurportPage(view))
        val document = compose.runOnIdle { requireNotNull(fixture.controller.state?.document) }
        val initial = assertBookPage(fixture, view)
        val initialZoom = compose.runOnIdle { view.settings.textZoom }
        var previousTextHeight = 0f

        for (textScale in listOf(1f, 1.3f)) {
            // Reflow this composition only; retain the device's density/font scale and settings.
            compose.runOnIdle { fixture.textScale = textScale }
            if (textScale > 1f) waitForNativeCondition(view, "Font zoom must actually reflow the existing columns") {
                view.settings.textZoom > initialZoom && (view.bookPageInfo?.count ?: 0) > initial.count
            }
            val page = assertBookPage(fixture, view)
            assertTrue("Reflow must retain the previously visible paragraph", measureBook(view).record(initial.anchorRecord).visible)
            assertLiveBookChrome(fixture, view)
            val progressText = compose.onNodeWithTag("reader-book-progress", useUnmergedTree = true)
            val progressBounds = assertFullyVisibleAboveNavigationBar(progressText)
            assertTrue("The fixture must really enlarge/reflow the footer text", progressBounds.height > previousTextHeight)
            previousTextHeight = progressBounds.height
            val layouts = mutableListOf<TextLayoutResult>()
            progressText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            // Fractional intrinsic widths can set hasVisualOverflow despite fitting. Inspect
            // actual line bounds with one-pixel rounding tolerance, plus height and ellipsis.
            assertFalse("Footer must not overflow vertically", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("Footer must not ellipsize", layout.isLineEllipsized(line))
                assertTrue("Footer line must fit its measured width", layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= layout.size.width + 1f)
            }
            val previous = compose.onNodeWithTag("reader-book-previous").assertIsEnabled()
            val seek = compose.onNodeWithTag("reader-book-seek").assertIsEnabled()
            val next = compose.onNodeWithTag("reader-book-next").assertIsEnabled()
            val previousBounds = assertFullyVisibleAboveNavigationBar(previous)
            val seekBounds = assertFullyVisibleAboveNavigationBar(seek)
            val nextBounds = assertFullyVisibleAboveNavigationBar(next)
            assertTrue(previousBounds.right <= seekBounds.left + .5f && seekBounds.right <= nextBounds.left + .5f)
            assertTrue(progressBounds.bottom <= minOf(previousBounds.top, seekBounds.top, nextBounds.top) + .5f)
            for (button in listOf(previous, next)) {
                val bounds = button.getUnclippedBoundsInRoot()
                assertTrue("Footer page buttons must retain 48dp touch targets",
                    (bounds.right - bounds.left).value >= 48f && (bounds.bottom - bounds.top).value >= 48f)
            }
            val bodyBounds = assertFullyVisibleAboveNavigationBar(compose.onNodeWithTag("reader-document"))
            assertTrue("The footer must not cover the column", bodyBounds.bottom <= progressBounds.top + .5f)
            val contents = compose.onNodeWithTag("reader-book-contents").assertIsEnabled()
            assertTrue("Contents belongs in the header, not a duplicate footer",
                assertFullyVisibleAboveNavigationBar(contents).bottom <= bodyBounds.top + .5f)
            compose.runOnIdle {
                val visible = android.graphics.Rect()
                assertTrue("The native source body must actually be visible", view.isShown && view.getGlobalVisibleRect(visible))
                assertEquals(view.width, visible.width())
                assertEquals(view.height, visible.height())
                assertEquals(0, view.scrollY)
                assertSame(document, fixture.controller.state?.document)
            }

            // Semantics clicks bypass hit testing: exercise both real, inset-safe footer buttons.
            turnPage(fixture, view, forward = true) { next.performTouchInput { click() } }
            turnPage(fixture, view, forward = false) { previous.performTouchInput { click() } }
            assertEquals(page, assertBookPage(fixture, view))
            contents.performTouchInput { click() }
            waitFor(hasTestTag("reader-book-contents-list"))
            compose.onNodeWithTag("reader-book-contents-list").assertIsDisplayed()
            compose.onNodeWithTag("book-entry-${selected.record}").assertIsDisplayed().assertTextEquals(selected.displayTitle)
            compose.onNodeWithText(label(R.string.reader_close_contents)).assertIsEnabled().performClick()
            compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
            assertEquals(page, assertBookPage(fixture, view))
            compose.runOnIdle {
                assertSame(view, readerHost()?.settledView)
                assertEquals(listOf(request(ReadingMode.BOOK)), fixture.requests)
                assertTrue(fixture.entrySelections.isEmpty())
            }
        }
    }

    @Test
    fun tappingActualPurportTextHidesAndRestoresChromeWithoutResizingOrRepaginating() {
        val fixture = showReader(initialMode = ReadingMode.BOOK)
        val view = assertBookReader(fixture, selected.record)
        turnToPage(fixture, view, interiorPurportPage(view))
        val page = assertBookPage(fixture, view)
        val host = compose.runOnIdle { requireNotNull(readerHost()) }
        val hostBounds = compose.runOnIdle { nativeBounds(host) }
        val viewBounds = compose.runOnIdle { nativeBounds(view) }
        val bodyBounds = compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot()
        val state = compose.runOnIdle { requireNotNull(fixture.controller.state) }
        val point = purportTextPoint(view)
        assertTrue(point.record in selected.record..selected.end)
        assertTrue(entryParagraphs.getValue(selected.record).single { it.record == point.record }.text.contains(point.text))

        // A measured glyph in a visible purport, not an edge/gutter, link, disclosure, or callback.
        compose.onNodeWithTag("reader-document").performTouchInput { click(point.offset) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("reader-mode-toggle")).fetchSemanticsNodes().isEmpty()
        }
        for (tag in chromeTags) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        assertActions(R.string.reader_show_controls, R.string.reader_previous_page, R.string.reader_next_page)
        assertEquals(page, assertBookPage(fixture, view))
        assertEquals(bodyBounds, compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertSame(host, readerHost())
            assertEquals(hostBounds, nativeBounds(host))
            assertEquals(viewBounds, nativeBounds(view))
            assertEquals(state.location, fixture.controller.state?.location)
            assertSame(state.document, fixture.controller.state?.document)
            assertEquals(listOf(request(ReadingMode.BOOK)), fixture.requests)
        }

        val secondPoint = purportTextPoint(view)
        assertEquals(point.record, secondPoint.record)
        compose.onNodeWithTag("reader-document").performTouchInput { click(secondPoint.offset) }
        waitFor(hasTestTag("reader-mode-toggle"))
        assertLiveBookChrome(fixture, view)
        assertActions(R.string.reader_hide_controls, R.string.reader_previous_page, R.string.reader_next_page)
        assertEquals(page, assertBookPage(fixture, view))
        assertEquals(bodyBounds, compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertSame(host, readerHost())
            assertEquals(hostBounds, nativeBounds(host))
            assertEquals(viewBounds, nativeBounds(view))
            assertEquals(state.location, fixture.controller.state?.location)
            assertSame(state.document, fixture.controller.state?.document)
            assertEquals(listOf(request(ReadingMode.BOOK)), fixture.requests)
            assertTrue(fixture.modeChanges.isEmpty())
            assertTrue(fixture.entrySelections.isEmpty())
            assertTrue(fixture.scrollReports.isEmpty())
        }
    }

    @Test
    fun failedBookLoadKeepsHeaderRecoveryUsableAndReturnsToTheSameVerseAndScroll() {
        val response = CompletableDeferred<ReaderDocument>()
        val fixture = showReader(bookLoader = { _, _ -> response.await() })
        try {
            val original = assertVerseReader(fixture, selected)
            val offset = resetLongVerseScroll(fixture, original)
            compose.onNodeWithTag("reader-mode-toggle").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.runOnIdle { fixture.controller.state?.let { it.loading && it.mode == ReadingMode.BOOK } == true }
            }
            assertHeader(ReadingMode.BOOK, enabled = false)
            assertUnavailableBookControls()
            assertActions()
            compose.runOnIdle {
                val host = requireNotNull(readerHost())
                assertTrue(host.isBusy)
                assertSame(original, host.currentView)
                assertNull(host.settledView)
                assertFalse(original.interactionEnabled)
                assertEquals(2, fixture.requests.size)
                response.completeExceptionally(IllegalStateException("Synthetic BOOK load failure"))
            }
            waitFor(hasText(label(R.string.reader_error)))
            assertHeader(ReadingMode.BOOK)
            assertUnavailableBookControls()
            compose.onNodeWithText(label(R.string.try_again)).assertIsDisplayed().assertIsEnabled()
            assertActions()
            compose.runOnIdle {
                assertTrue(requireNotNull(fixture.controller.state).failed)
                assertNull(fixture.controller.state?.document)
                val host = requireNotNull(readerHost())
                assertEquals(0, host.childCount)
                assertNull(host.currentView)
                assertTrue(original.isReleased)
            }

            // No BOOK layout ever painted: returning to the unchanged VERSE may restore its
            // old pixel offset. This is distinct from successful cross-layout source mapping.
            compose.onNodeWithTag("reader-mode-toggle").performClick()
            val recovered = assertVerseReader(fixture, selected)
            assertVerseScroll(fixture, recovered, offset)
            compose.onNodeWithText(label(R.string.reader_error)).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.try_again)).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf(request(ReadingMode.VERSE), request(ReadingMode.BOOK), request(ReadingMode.VERSE)),
                    fixture.requests)
                assertEquals(listOf(ReadingMode.BOOK, ReadingMode.VERSE), fixture.modeChanges)
            }
            assertVerseText(unicode)
            assertVerseText(entryParagraphs.getValue(selected.record).last().text)
        } finally {
            response.cancel()
        }
    }

    private fun showReader(
        initiallyReading: Boolean = true,
        initialMode: ReadingMode = ReadingMode.VERSE,
        node: ContentNode = selected,
        bookLoader: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ ->
            bookDocument(location.position)
        },
    ): Fixture {
        val fixture = Fixture()
        val tree = mapOf(book.record to listOf(chapterOne, chapterTwo),
            chapterOne.record to firstVerses, chapterTwo.record to otherVerses)
        // Match MainActivity's window geometry before attaching any Compose/native content.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            var reader by remember { mutableStateOf<ReaderState?>(null) }
            var browser by remember { mutableStateOf(BrowseState()) }
            val scope = rememberCoroutineScope()
            val controller = remember(scope) {
                ReaderController(scope,
                    loadDocument = { location, language ->
                        check(location.libraryId == library.id && language == library.languageTag)
                        fixture.requests += Request(ReadingMode.VERSE, location, language)
                        verseDocuments.getValue(location.start)
                    }, onChange = { reader = it },
                    loadBookDocument = { location, language ->
                        check(location.libraryId == library.id && language == library.languageTag)
                        fixture.requests += Request(ReadingMode.BOOK, location, language)
                        bookLoader(location, language)
                    }, initialMode = initialMode, onModeChange = { fixture.modeChanges += it })
            }
            val navigator = remember(scope) {
                BrowseNavigator(scope,
                    loadChildren = { id, parent ->
                        check(id == library.id)
                        fixture.browseCalls += id to parent
                        tree.getValue(parent)
                    }, onChange = { browser = it },
                    loadOverview = { id, parent, children ->
                        check(id == library.id)
                        if (parent == chapterOne.record) { check(children == firstVerses); overview } else null
                    })
            }
            val screens = rememberSaveableStateHolder()
            DisposableEffect(controller, navigator) {
                fixture.controller = controller
                fixture.navigator = navigator
                navigator.setLibrary(library.id, listOf(book))
                if (initiallyReading) controller.open(library.id, library.languageTag, node)
                onDispose { navigator.close(); controller.close() }
            }
            VedaPreviewTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fixture.textScale)) {
                    val reading = reader
                    if (reading != null) screens.SaveableStateProvider("reader") {
                        ReaderScreen(state = reading,
                            onBack = { fixture.backCalls++; controller.close() },
                            onNext = controller::next, onPrevious = controller::previous,
                            onRetry = controller::retry,
                            onScroll = { offset ->
                                fixture.scrollReports += requireNotNull(controller.state).mode to offset
                                controller.saveScroll(offset)
                            },
                            onToggleMode = controller::toggleMode,
                            onBookEntry = { record -> fixture.entrySelections += record; controller.jumpToBookEntry(record) },
                            onBookAnchor = { record -> fixture.anchorReports += record; controller.saveBookAnchor(record) })
                    } else screens.SaveableStateProvider("library") {
                        LibraryScreen(state = LibraryUiState(loading = false, libraries = listOf(library),
                            active = library, collections = listOf(book), browser = browser),
                            // Mirror the wired preview-entry route without constructing a store/VM.
                            onOpenSection = { record ->
                                val target = navigator.readingTarget(record)
                                if (target != null) controller.open(library.id, library.languageTag, target)
                                else navigator.open(record)
                            }, onBack = navigator::back, onAncestor = navigator::goToDepth,
                            onRetrySection = navigator::retry)
                    }
                }
            }
        }
        return fixture
    }

    private fun awaitReader(fixture: Fixture, mode: ReadingMode, expected: ReaderDocument): OfflineReaderView {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnIdle {
                fixture.controller.state?.let {
                    !it.loading && !it.failed && it.mode == mode && it.document == expected
                } == true && readerHost()?.settledView?.isAttachedToWindow == true
            }
        }
        val view = awaitNativeDocument()
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        compose.runOnIdle {
            val state = requireNotNull(fixture.controller.state)
            assertEquals(expected, state.document)
            assertEquals(mode, fixture.controller.selectedMode)
            assertFalse(state.enterAtEnd)
            val host = requireNotNull(readerHost())
            assertSame(view, host.settledView)
            assertEquals(1, host.childCount)
            assertSame(view, host.getChildAt(0))
            assertFalse(host.isBusy)
            assertTrue(view.interactionEnabled)
            assertEquals(mode == ReadingMode.BOOK, view.paginatedBook)
            assertEquals(mode == ReadingMode.BOOK, view.settings.javaScriptEnabled)
            assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
            assertFalse(view.settings.domStorageEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertTrue(view.settings.blockNetworkLoads)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
            assertFalse(view.settings.supportMultipleWindows())
            assertFalse(view.settings.supportZoom())
        }
        return view
    }

    private fun assertVerseReader(fixture: Fixture, node: ContentNode): OfflineReaderView {
        val document = verseDocuments.getValue(node.record)
        val view = awaitReader(fixture, ReadingMode.VERSE, document)
        assertHeader(ReadingMode.VERSE)
        compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(node.displayTitle)
        compose.onNodeWithTag("reader-context").assertIsDisplayed().assertTextEquals(document.section.parentTitle)
        for (tag in bookControlTags) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(node.location(library.id), fixture.controller.state?.location)
            assertNull(document.bookPosition)
            assertNull(document.bookResource)
            assertNull(view.bookPageInfo)
            assertEquals(0, view.scrollX)
        }
        return view
    }

    /** A load lands at the requested record's column, whose FIRST visible record may be earlier. */
    private fun assertBookReader(fixture: Fixture, anchor: Int, atEnd: Boolean = false): OfflineReaderView {
        val expected = bookDocument(anchor)
        val view = awaitReader(fixture, ReadingMode.BOOK, expected)
        val info = assertBookPage(fixture, view)
        val layout = measureBook(view)
        val target = layout.record(anchor)
        assertTrue("The exact selected source record must be visible", target.visible)
        assertEquals(if (atEnd) info.count - 1 else target.pages.first(), info.page)
        assertEquals(expected.section.paragraphs.map { it.record }, layout.records.map { it.record })
        assertEquals("Complete source text, not a blank/error WebView or a test paginator",
            expected.section.paragraphs.map { it.text }, layout.records.map { it.text })
        assertTrue("Every declared fixture paragraph must have laid-out column fragments",
            layout.records.all { it.pages.isNotEmpty() })
        compose.runOnIdle {
            assertEquals(anchor, view.initialBookAnchor)
            assertNull(fixture.controller.state?.document?.bookPosition) // Exercise the new resource path only.
            val response = requireNotNull(view.webViewClient.shouldInterceptRequest(view,
                DocumentRequest(requireNotNull(view.url))))
            try {
                assertEquals(200, response.statusCode)
                val policy = requireNotNull(response.responseHeaders?.get("Content-Security-Policy"))
                for (directive in listOf("default-src 'none'", "script-src 'none'", "connect-src 'none'", "frame-src 'none'")) {
                    assertTrue("BOOK response must retain $directive", policy.contains(directive))
                }
            } finally { response.data?.close() }
        }
        assertLiveBookChrome(fixture, view)
        return view
    }

    private fun assertBookPage(fixture: Fixture, view: OfflineReaderView, page: Int? = null): BookPageInfo {
        waitForNativeCondition(view, "A painted horizontal column and its controller anchor must agree") {
            val info = view.bookPageInfo
            val state = fixture.controller.state
            info != null && state != null && !state.loading && !state.failed && !state.enterAtEnd &&
                state.mode == ReadingMode.BOOK && (page == null || info.page == page) &&
                info.viewportWidth > 0 && view.scrollX == info.nativeOffset && view.scrollY == 0 &&
                state.location.position == info.anchorRecord && fixture.anchorReports.lastOrNull() == info.anchorRecord
        }
        awaitNativeVisualState(view)
        val info = compose.runOnIdle {
            val value = requireNotNull(view.bookPageInfo)
            val state = requireNotNull(fixture.controller.state)
            val document = requireNotNull(state.document)
            val resource = requireNotNull(document.bookResource)
            assertTrue(value.count > 0 && value.page in 0 until value.count)
            if (page != null) assertEquals(page, value.page)
            assertEquals(value.nativeOffset, view.scrollX)
            assertEquals(0, view.scrollY)
            assertEquals(0, view.scrollRange)
            assertEquals(0, state.scrollY)
            assertFalse(state.enterAtEnd)
            assertEquals(ReadingMode.BOOK, state.mode)
            assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
            assertEquals(resource.section, document.section)
            assertEquals(library.id, state.location.libraryId)
            assertEquals(resource.section.node.record, state.location.start)
            assertEquals(resource.section.node.end, state.location.end)
            assertEquals(value.anchorRecord, state.location.position)
            assertEquals(entryAt(value.anchorRecord).source.displayTitle, state.location.title)
            assertTrue("A live anchor is an actual declared paragraph", document.section.paragraphs.any { it.record == value.anchorRecord })
            assertTrue(fixture.scrollReports.none { it.first == ReadingMode.BOOK })
            val host = requireNotNull(readerHost())
            assertFalse(host.isBusy)
            assertSame(view, host.settledView)
            assertEquals(1, host.childCount)
            assertTrue(view.interactionEnabled)
            assertTrue(view.paginatedBook)
            assertTrue(view.settings.javaScriptEnabled)
            value
        }
        val layout = measureBook(view)
        assertEquals((layout.pitch * layout.scale).roundToInt(), info.viewportWidth)
        assertEquals((info.page * layout.pitch * layout.scale).roundToInt(), info.nativeOffset)
        compose.runOnIdle {
            assertEquals(view.scrollX.toDouble(), layout.x * layout.scale, 2.0)
            assertEquals("Native and visual widths agree within one CSS pixel", view.width / layout.scale, layout.width, 1.0)
        }
        assertEquals(0.0, layout.y, .01)
        assertEquals(info.page * layout.pitch, layout.x, 1.0)
        assertEquals("Real DOM overflow, not source-entry count, determines columns",
            ceil((layout.extent - 1.0).coerceAtLeast(layout.pitch) / layout.pitch).toInt(), info.count)
        assertTrue("The native anchor must actually intersect the painted column", layout.record(info.anchorRecord).visible)
        assertEquals("The bookmark must identify the first visible source record, not merely any visible verse",
            layout.records.first { it.visible }.record, info.anchorRecord)
        return info
    }

    private fun assertLiveBookChrome(fixture: Fixture, view: OfflineReaderView) {
        assertHeader(ReadingMode.BOOK)
        val info = compose.runOnIdle { requireNotNull(view.bookPageInfo) }
        val entry = entryAt(info.anchorRecord)
        compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(entry.source.displayTitle)
        if (entry.parentTitle.isBlank()) compose.onNodeWithTag("reader-context").assertDoesNotExist()
        else compose.onNodeWithTag("reader-context").assertIsDisplayed().assertTextEquals(entry.parentTitle)
        compose.onAllNodes(hasTestTag("reader-book-contents")).assertCountEquals(1)
        val contents = compose.onNodeWithTag("reader-book-contents").assertIsDisplayed().assertIsEnabled()
            .assertHasClickAction().assertContentDescriptionEquals(label(R.string.reader_book_contents))
        assertTrue("Contents is a header control", contents.fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("reader-document").fetchSemanticsNode().boundsInRoot.top + .5f)
        assertSourceLocation(view)
        val section = compose.runOnIdle { requireNotNull(fixture.controller.state?.document).section }
        val previousEnabled = info.page > 0 || section.previous != null
        val nextEnabled = info.page < info.count - 1 || section.next != null
        for ((tag, description, enabled) in listOf(
            Triple("reader-book-previous", R.string.reader_previous_page, previousEnabled),
            Triple("reader-book-next", R.string.reader_next_page, nextEnabled),
        )) {
            val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                .assertContentDescriptionEquals(label(description))
            if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
        }
        compose.runOnIdle {
            assertEquals(previousEnabled, view.canPreviousUnit)
            assertEquals(nextEnabled, view.canNextUnit)
        }
        assertActions(*buildList {
            add(R.string.reader_hide_controls)
            if (previousEnabled) add(R.string.reader_previous_page)
            if (nextEnabled) add(R.string.reader_next_page)
        }.toIntArray())
    }

    /** Every in-resource turn must retain the same native view/document and issue ZERO loads. */
    private fun turnPage(fixture: Fixture, view: OfflineReaderView, forward: Boolean, action: (() -> Unit)? = null) {
        val before = assertBookPage(fixture, view)
        val target = before.page + if (forward) 1 else -1
        assertTrue("Use a real adjacent column, not a neighbouring resource", target in 0 until before.count)
        val document = compose.runOnIdle { requireNotNull(fixture.controller.state?.document) }
        val requests = compose.runOnIdle { fixture.requests.toList() }
        if (action != null) action() else compose.onNodeWithTag("reader-document").performTouchInput {
            if (forward) swipeLeft() else swipeRight()
        }
        val after = assertBookPage(fixture, view, target)
        assertEquals(before.count, after.count)
        compose.runOnIdle {
            assertSame(document, fixture.controller.state?.document)
            assertEquals(requests, fixture.requests)
            assertEquals("A turn reaches the adjacent absolute rounded offset",
                after.nativeOffset - before.nativeOffset, view.scrollX - before.nativeOffset)
        }
        assertLiveBookChrome(fixture, view)
    }

    private fun turnToPage(fixture: Fixture, view: OfflineReaderView, target: Int) {
        val before = assertBookPage(fixture, view)
        assertTrue(target in 0 until before.count)
        repeat(kotlin.math.abs(target - before.page)) { turnPage(fixture, view, forward = target > before.page) }
        assertEquals(target, assertBookPage(fixture, view).page)
    }

    private fun interiorPurportPage(view: OfflineReaderView): Int {
        val pages = measureBook(view).record(longPurportRecord).pages
        assertTrue("A single declared purport paragraph must span at least four columns", pages.size >= 4)
        return pages[1]
    }

    private fun assertUnavailableBookControls() {
        // The footer and header slots remain measured during loading/error instead of resizing BOOK.
        for (tag in bookControlTags.filterNot { it == "reader-book-progress" }) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled()
        }
        compose.onNodeWithTag("reader-book-progress").assertIsDisplayed()
            .assertTextEquals(label(R.string.reader_book_location_unavailable))
            .assertContentDescriptionEquals(label(R.string.reader_book_location_unavailable))
    }

    private fun assertHeader(mode: ReadingMode, enabled: Boolean = true) {
        val modeLabel = label(if (mode == ReadingMode.BOOK) R.string.reader_mode_book else R.string.reader_mode_verse)
        val actionLabel = label(if (mode == ReadingMode.BOOK) R.string.reader_switch_to_verse else R.string.reader_switch_to_book)
        val toggle = compose.onNodeWithTag("reader-mode-toggle").assertIsDisplayed().assertHasClickAction()
            .assertTextEquals(modeLabel).assertContentDescriptionEquals(actionLabel)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, modeLabel))
        if (enabled) toggle.assertIsEnabled() else toggle.assertIsNotEnabled()
        compose.onNodeWithTag("reader-back").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertContentDescriptionEquals(label(R.string.reader_back))
        compose.onNodeWithText(label(R.string.back), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-previous", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-next", useUnmergedTree = true).assertDoesNotExist()
        for (tag in listOf("reader-page", "reader-footer", "reader-end")) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher("no fabricated Page N or end-of-section label") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                Regex("(?i)\\bpage\\s+\\d+\\b|\\bend of (?:this |the )?section\\b").containsMatchIn(it.text)
            }
        }, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(label(R.string.reader_swipe_hint)).assertDoesNotExist()
    }

    private fun assertActions(@StringRes vararg labels: Int) {
        val actions = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(labels.map { label(it) }, actions.map { it.label })
    }

    private fun invokeAction(@StringRes id: Int) {
        val action = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label(id) }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    private fun openBookContents() {
        compose.onNodeWithTag("reader-book-contents").assertIsEnabled().performClick()
        waitFor(hasTestTag("reader-book-contents-list"))
        compose.onNodeWithTag("reader-book-contents-list").assertIsDisplayed()
        // A chapter header behind the dialog can have the same book title as its context.
        compose.onNode(hasText(book.displayTitle) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    private fun selectBookEntry(node: ContentNode) {
        openBookContents()
        val tag = "book-entry-${node.record}"
        compose.onNodeWithTag("reader-book-contents-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().assertTextEquals(node.displayTitle).performClick()
        compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
    }

    private fun openOuterChapter(fixture: Fixture) {
        waitFor(hasTestTag("library-list"))
        compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("collection-${book.record}"))
        compose.onNodeWithTag("collection-${book.record}").performClick()
        waitFor(hasTestTag("browse-list-${book.record}"))
        compose.onNodeWithTag("browse-list-${book.record}").performScrollToNode(hasTestTag("section-${chapterOne.record}"))
        compose.onNodeWithTag("section-${chapterOne.record}").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                fixture.navigator.state.let { !it.loading && !it.failed && it.chapter == overview }
            }
        }
        waitFor(hasTestTag("browse-list-${chapterOne.record}"))
        compose.runOnIdle {
            assertEquals(overview, fixture.navigator.state.chapter)
            assertEquals(listOf(book, chapterOne), fixture.navigator.state.path)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    /** Check measured (unclipped) geometry, not merely the surviving semantics intersection. */
    private fun assertFullyVisibleAboveNavigationBar(node: SemanticsNodeInteraction): Rect {
        node.assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode()
        val rootBounds = root.boundsInRoot
        val rootWindowBounds = root.boundsInWindow
        val pixelsPerDp = compose.density.density
        val measured = node.getUnclippedBoundsInRoot()
        val bounds = Rect(measured.left.value * pixelsPerDp, measured.top.value * pixelsPerDp,
            measured.right.value * pixelsPerDp, measured.bottom.value * pixelsPerDp)
        val safeBounds = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationInWindow(it) }
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)) { "Window insets must be dispatched" }
                .getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            // Do not let a decor-fitted fixture accidentally pass this edge-to-edge regression.
            assertEquals("Compose must extend to the window bottom", (origin[1] + decor.height).toFloat(), rootWindowBounds.bottom, .5f)
            val rootX = rootWindowBounds.left - rootBounds.left
            val rootY = rootWindowBounds.top - rootBounds.top
            Rect(origin[0] + insets.left - rootX, origin[1] + insets.top - rootY,
                origin[0] + decor.width - insets.right - rootX, origin[1] + decor.height - insets.bottom - rootY)
        }
        val clipped = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Measured and visible bounds must be nonzero: measured=$bounds, visible=$clipped",
            bounds.width > 0f && bounds.height > 0f && clipped.width > 0f && clipped.height > 0f)
        assertEquals("No horizontal clipping", bounds.width, clipped.width, .5f)
        assertEquals("No vertical clipping", bounds.height, clipped.height, .5f)
        for (container in listOf(rootBounds, safeBounds)) {
            assertTrue("The entire node $bounds must fit within $container",
                bounds.left >= container.left - .5f && bounds.top >= container.top - .5f &&
                    bounds.right <= container.right + .5f && bounds.bottom <= container.bottom + .5f)
        }
        return bounds
    }

    private fun assertSourceLocation(view: OfflineReaderView): Int {
        val index = compose.runOnIdle { entryIndex(requireNotNull(view.bookPageInfo).anchorRecord) }
        waitFor(hasTestTag("reader-book-progress") and hasText(label(R.string.reader_book_progress, index + 1, outline.entries.size)))
        assertLocationControls(index)
        return index
    }

    private fun assertLocationControls(index: Int) {
        val description = label(R.string.reader_book_progress_description, index + 1, outline.entries.size)
        compose.onNodeWithTag("reader-book-progress").assertIsDisplayed()
            .assertTextEquals(label(R.string.reader_book_progress, index + 1, outline.entries.size))
            .assertContentDescriptionEquals(description)
        val seek = compose.onNodeWithTag("reader-book-seek").assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals(label(R.string.reader_book_seek))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        val progress = seek.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(index.toFloat(), progress.current, .0001f)
        assertEquals(0f..outline.entries.lastIndex.toFloat(), progress.range)
        assertEquals(outline.entries.size - 2, progress.steps)
        assertTrue(seek.fetchSemanticsNode().config.contains(SemanticsActions.SetProgress))
    }

    /** Clear/fence native find work before setting offsets: a search must never mimic a gesture. */
    private fun resetLongVerseScroll(fixture: Fixture, view: OfflineReaderView): Int {
        waitForNativeCondition(view, "Long purport must span several viewports") { view.scrollRange > view.viewportHeight * 3 }
        compose.runOnIdle { view.clearMatches() }
        awaitNativeVisualState(view)
        val offset = compose.runOnIdle { view.scrollRange / 2 }
        compose.runOnIdle { view.scrollTo(0, offset) }
        awaitNativeVisualState(view)
        assertVerseScroll(fixture, view, offset)
        return offset
    }

    private fun assertVerseScroll(fixture: Fixture, view: OfflineReaderView, offset: Int) {
        waitForNativeCondition(view, "Native and controller offsets must both equal $offset") {
            view.scrollY == offset && fixture.controller.state?.scrollY == offset
        }
        compose.runOnIdle {
            assertEquals(ReadingMode.VERSE, fixture.controller.state?.mode)
            assertFalse(view.paginatedBook)
            assertFalse(view.settings.javaScriptEnabled)
            assertEquals(offset, view.scrollY)
            assertEquals(offset, fixture.controller.state?.scrollY)
        }
    }

    /** VERSE search only. BOOK measurements never find/scroll, replace a client or inject source. */
    private fun assertVerseText(text: String, expectedMatches: Int = 1) {
        val view = awaitNativeDocument()
        val matches = AtomicInteger(-1)
        try {
            compose.runOnIdle {
                assertEquals(label(R.string.reader_document), view.contentDescription.toString())
                assertFalse(view.settings.javaScriptEnabled)
                view.setFindListener { _, count, finished -> if (finished) matches.set(count) }
                view.findAllAsync(text)
            }
            waitForNativeCondition(view, "Native text search must finish: $text") { matches.get() >= 0 }
            assertEquals("Rendered synthetic text: $text", expectedMatches, matches.get())
        } finally {
            compose.runOnIdle { view.setFindListener(null); view.clearMatches() }
        }
        awaitNativeVisualState(view)
    }

    /** Only the two fixed test-owned queries below are executable; source strings remain data. */
    private fun readBookQuery(view: OfflineReaderView, query: BookQuery): JSONObject {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertSame(view, readerHost()?.settledView)
            assertTrue(view.paginatedBook)
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript(query.script) { result.set(it) }
        }
        waitForNativeCondition(view, "Read-only BOOK measurement ${query.name} must complete") { result.get() != null }
        return JSONObject(requireNotNull(result.get())).also {
            assertFalse("Invalid BOOK measurement: $it", it.has("error"))
        }
    }

    private fun measureBook(view: OfflineReaderView): BookMeasurement {
        val json = readBookQuery(view, BookQuery.GEOMETRY)
        assertEquals("loaded", json.getString("fonts"))
        assertTrue(json.getBoolean("bookBody"))
        assertEquals("One real CSS column per viewport", "1", json.getString("columnCount"))
        assertEquals(0.0, json.getDouble("columnGap"), .01)
        assertTrue(json.getDouble("pitch") > 0)
        assertEquals(json.getDouble("width"), json.getDouble("pitch"), 1.0)
        assertEquals(json.getDouble("height") - 32.0, json.getDouble("flowHeight"), 1.0)
        assertTrue(json.getString("policy").contains("script-src 'none'"))
        assertEquals("No source script, handler, frame or embedded document", 0, json.getInt("activeSource"))
        val scale = compose.runOnIdle { view.nativeContentScale }
        assertTrue(scale.isFinite() && scale > 0)
        assertEquals("Native scale agrees with independent DOM scale",
            json.getDouble("dpr") * json.getDouble("visualScale"), scale, .00001)
        val records = json.getJSONArray("records")
        return BookMeasurement(json.getDouble("width"), json.getDouble("pitch"), scale,
            json.getDouble("x"), json.getDouble("y"), json.getDouble("extent"),
            List(records.length()) { index ->
                val record = records.getJSONObject(index)
                val pages = record.getJSONArray("pages")
                MeasuredRecord(record.getInt("record"), record.getString("text"),
                    List(pages.length()) { pages.getInt(it) }.distinct(), record.getBoolean("visible"))
            })
    }

    private fun purportTextPoint(view: OfflineReaderView): TextPoint {
        val json = readBookQuery(view, BookQuery.PURPORT_TEXT_POINT)
        assertFalse("The tap must not target a link or native disclosure", json.getBoolean("interactive"))
        assertFalse("The tap must not clear an existing selection", json.getBoolean("selection"))
        assertTrue("The tap must hit actual source text", json.getString("text").isNotBlank())
        val point = compose.runOnIdle {
            Offset((json.getDouble("x") * view.nativeContentScale).toFloat(),
                (json.getDouble("y") * view.nativeContentScale).toFloat())
        }
        compose.runOnIdle {
            val host = requireNotNull(readerHost())
            assertEquals("The AndroidView touch origin must match the WebView", nativeBounds(host), nativeBounds(view))
            assertTrue(point.x > 0 && point.x < view.width && point.y > 0 && point.y < view.height / 2f)
        }
        return TextPoint(json.getInt("record"), json.getString("text"), point)
    }

    /** Called on UI; unlike semantics clipping, these are the complete native window bounds. */
    private fun nativeBounds(view: View): Rect {
        val position = IntArray(2).also { view.getLocationInWindow(it) }
        return Rect(position[0].toFloat(), position[1].toFloat(),
            (position[0] + view.width).toFloat(), (position[1] + view.height).toFloat())
    }

    private fun awaitNativeDocument(): OfflineReaderView {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnIdle { readerHost()?.settledView?.isAttachedToWindow == true }
        }
        val view = compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
        waitForNativeCondition(view, "Secure native document must finish rendering, not an error page") {
            view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                view.title == "Reader" && view.url?.matches(Regex("https://reader\\.local/document/[1-9][0-9]*")) == true
        }
        awaitNativeVisualState(view)
        return view
    }

    private fun awaitNativeVisualState(view: OfflineReaderView) {
        val ready = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { view.post { ready.set(true) } }
            })
            view.invalidate()
        }
        waitForNativeCondition(view, "Native visual fence must finish") { ready.get() }
    }

    private fun waitForNativeCondition(view: OfflineReaderView, message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.runOnIdle { view.isAttachedToWindow && readerHost()?.settledView === view && condition() }
            }
        } catch (error: ComposeTimeoutException) {
            val details = compose.runOnIdle {
                "progress=${view.progress}, title=${view.title}, url=${view.url?.take(120)}, " +
                    "contentHeight=${view.contentHeight}, scrollX=${view.scrollX}, scrollY=${view.scrollY}, range=${view.scrollRange}, " +
                    "book=${view.bookPageInfo}, viewport=${view.width}x${view.height}, " +
                    "attached=${view.isAttachedToWindow}, busy=${readerHost()?.isBusy}"
            }
            throw AssertionError("$message: $details", error)
        }
    }

    // Access native state only on the UI thread; only the host knows which page is settled.
    private fun readerHost(view: View = compose.activity.window.decorView): ReaderPageHost? = when (view) {
        is ReaderPageHost -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { readerHost(view.getChildAt(it)) }
        else -> null
    }

    /** No sparse pretend chapter: all entries in an exact contiguous span supply their paragraphs. */
    private fun bookDocument(anchor: Int): ReaderDocument {
        val index = entryIndex(anchor)
        val resourceIndex = resourceEntries.indexOfFirst { index in it }
        require(resourceIndex >= 0)
        val span = resourceEntries[resourceIndex]
        val members = span.map { outline.entries[it] }
        check(members.zipWithNext().all { (left, right) -> left.node.end + 1 == right.node.record })
        val paragraphs = members.flatMap { entryParagraphs.getValue(it.node.record) }
        val node = resourceNode(span)
        check(paragraphs.all { it.record in node.record..node.end })
        check(paragraphs.zipWithNext().all { (left, right) -> left.record < right.record })
        check(paragraphs.any { it.record == anchor })
        val section = ReadingSection(node, members.first().kind, members.first().parentTitle, paragraphs,
            resourceEntries.getOrNull(resourceIndex - 1)?.let(::resourceNode),
            resourceEntries.getOrNull(resourceIndex + 1)?.let(::resourceNode))
        val resource = BookResource(section, BookPosition(outline, index), span.first, span.last, anchor)
        // Fresh document identity on every request, including a jump inside the current chapter.
        return ReaderDocument(section,
            FolioText.document(paragraphs, library.languageTag, ReaderPreferences(), bookMode = true),
            bookResource = resource)
    }

    private fun resourceNode(span: IntRange): ContentNode = outline.entries[span.first].source.copy(
        record = outline.entries[span.first].node.record, end = outline.entries[span.last].node.end)

    private fun entryIndex(record: Int): Int = outline.entries.indexOfFirst {
        record in it.node.record..it.node.end
    }.also { require(it >= 0) { "No synthetic source entry contains record $record" } }

    private fun entryAt(record: Int): BookEntry = outline.entries[entryIndex(record)]

    private fun paragraphs(entry: BookEntry): List<Paragraph> = buildList {
        val node = entry.node
        val kind = when (entry.kind) {
            ReadingUnitKind.VERSE -> "Verse_Number_Heading"
            ReadingUnitKind.CHAPTER -> "Chapter"
            else -> "Collection"
        }
        // Genuine semantic headings stay visible in BOOK, not hidden PA_Textnum labels.
        add(Paragraph(node.record, entry.source.displayTitle, "PA_$kind", "LE_$kind"))
        add(Paragraph(node.record + 1, "Synthetic opening ${node.record}: $unicode.", "PA_Translation", ""))
        if (node == selected) {
            add(Paragraph(longPurportRecord, "Synthetic continuous purport. " +
                "Original generated prose continues through real horizontal columns without selecting another source verse. ".repeat(48),
                "PA_Purport", ""))
            repeat(8) { index ->
                add(Paragraph(node.record + index + 3, "Synthetic long purport paragraph ${index + 1}. " +
                    "This original generated text remains inside the same complete source verse. ".repeat(2), "PA_Purport", ""))
            }
        } else if (node == nextVerse) {
            add(Paragraph(node.record + 2, "Synthetic neighbouring purport. " +
                "The visible source anchor must follow this verse rather than the original selection. ".repeat(20),
                "PA_Purport", ""))
        }
        add(Paragraph(node.end, "Synthetic final purport marker ${node.record}.", "PA_Purport", ""))
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
    }

    private fun scrollPosition(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun label(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun request(mode: ReadingMode, node: ContentNode = selected) = Request(mode, node.location(library.id), library.languageTag)

    private data class MeasuredRecord(val record: Int, val text: String, val pages: List<Int>, val visible: Boolean)
    private data class BookMeasurement(val width: Double, val pitch: Double, val scale: Double,
        val x: Double, val y: Double, val extent: Double,
        val records: List<MeasuredRecord>) {
        fun record(record: Int): MeasuredRecord = records.single { it.record == record }
    }
    private data class TextPoint(val record: Int, val text: String, val offset: Offset)

    /** No DOM mutation, scrolling, listeners, source interpolation, navigation, timers or bridge. */
    private enum class BookQuery(val script: String) {
        GEOMETRY("""
            (function(){
                var flow=document.querySelector('main.book-flow'), root=document.scrollingElement;
                if(!flow || !root) return {error:'Missing production book flow'};
                var v=window.visualViewport, w=v?v.width:window.innerWidth, h=v?v.height:window.innerHeight;
                var x=window.scrollX, style=getComputedStyle(flow);
                var offsetX=v?v.offsetLeft:0, offsetY=v?v.offsetTop:0, pitch=flow.getBoundingClientRect().width;
                var policy=document.querySelector('meta[http-equiv="Content-Security-Policy"]');
                return {width:w, height:h, pitch:pitch, x:v?v.pageLeft:x, y:v?v.pageTop:window.scrollY,
                    dpr:devicePixelRatio, visualScale:v?v.scale:1,
                    extent:Math.max(root.scrollWidth,document.documentElement.scrollWidth,document.body.scrollWidth,w),
                    fonts:document.fonts.status, bookBody:document.body.classList.contains('book-reading'),
                    columnCount:style.columnCount, columnGap:parseFloat(style.columnGap),
                    flowHeight:flow.getBoundingClientRect().height, policy:policy?policy.content:'',
                    activeSource:document.querySelectorAll('script,iframe,object,embed,[onload],[onerror]').length,
                    records:Array.from(flow.querySelectorAll('.record[data-record]')).map(function(e){
                        var boxes=Array.from(e.getClientRects()).filter(function(b){return b.width>0&&b.height>0;});
                        return {record:Number(e.getAttribute('data-record')), text:e.textContent,
                            pages:boxes.map(function(b){return Math.floor((b.left+x+.5)/pitch);}),
                            visible:boxes.some(function(b){return b.right-offsetX>.5&&b.left-offsetX<w-.5&&
                                b.bottom-offsetY>.5&&b.top-offsetY<h-.5;})};
                    })};
            })()
        """.trimIndent()),
        PURPORT_TEXT_POINT("""
            (function(){
                var flow=document.querySelector('main.book-flow');
                if(!flow) return {error:'Missing production book flow'};
                var selection=window.getSelection();
                if(selection&&!selection.isCollapsed) return {error:'Unexpected text selection'};
                var v=window.visualViewport, w=v?v.width:window.innerWidth, h=v?v.height:window.innerHeight;
                var offsetX=v?v.offsetLeft:0, offsetY=v?v.offsetTop:0;
                var excluded='a,img,summary,details,button,input,textarea,select,label,audio,video,[contenteditable]';
                var walker=document.createTreeWalker(flow,NodeFilter.SHOW_TEXT), text;
                while((text=walker.nextNode())){
                    var parent=text.parentElement, record=parent&&parent.closest('.record.purport[data-record]');
                    if(!record || parent.closest(excluded)) continue;
                    for(var i=0;i<text.length;i++){
                        if(/\s/.test(text.data.charAt(i))) continue;
                        var range=document.createRange();range.setStart(text,i);range.setEnd(text,i+1);
                        var boxes=range.getClientRects();
                        for(var j=0;j<boxes.length;j++){
                            var b=boxes[j];
                            var left=b.left-offsetX,right=b.right-offsetX,top=b.top-offsetY,bottom=b.bottom-offsetY;
                            if(b.width<=0||b.height<=0||left<4||right>w-4||top<4||bottom>h*.45) continue;
                            var x=(left+right)/2,y=(top+bottom)/2,hit=document.elementFromPoint(x+offsetX,y+offsetY);
                            if(!hit || hit.closest('.record[data-record]')!==record || hit.closest(excluded)) continue;
                            return {record:Number(record.getAttribute('data-record')),text:text.data.charAt(i),
                                x:x,y:y,interactive:false,selection:false};
                        }
                    }
                }
                return {error:'No visible non-interactive purport glyph in the upper half'};
            })()
        """.trimIndent()),
    }

    private class DocumentRequest(private val address: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private data class Request(val mode: ReadingMode, val location: ReadingLocation, val language: String)
    private class Fixture {
        lateinit var controller: ReaderController
        lateinit var navigator: BrowseNavigator
        var textScale by mutableStateOf(1f)
        val requests = mutableListOf<Request>()
        val modeChanges = mutableListOf<ReadingMode>()
        val entrySelections = mutableListOf<Int>()
        val anchorReports = mutableListOf<Int>()
        val scrollReports = mutableListOf<Pair<ReadingMode, Int>>()
        val browseCalls = mutableListOf<Pair<String, Int>>()
        var backCalls = 0
    }
}