package org.vedareader.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.LibraryInfo
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Whole-unit reader: synthetic text, a real controller, no stores, preferences writes, or database. */
@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo(
        "synthetic-reader", "Synthetic reader library", "en", "synthetic-only.ivd",
        32_768L, 3L, "Synthetic fixture only",
    )
    private val collection = ContentNode(600, 0, "01. Synthetic collection", 1699, 3, 1, "bgaiiwa")
    private val firstParent = "Synthetic chapter one"
    private val secondParent = "Synthetic chapter two"
    private val section = ContentNode(710, 700, "01. Synthetic नमूना verse 1", 799, 4, 3)
    private val secondSection = ContentNode(900, 700, "02. Synthetic combined verse 2–3", 999, 4, 3)
    private val thirdSection = ContentNode(1100, 1000, "01. Synthetic next-chapter verse", 1199, 4, 3)
    // Include Indic text, precomposed Latin letters, and an intentionally decomposed combining mark.
    private val originalUnicode = "नमूना বাংলা — ā ś ṛ — a\u0304"
    private val firstParagraphs = listOf(
        Paragraph(710, "Synthetic opening verse: $originalUnicode.", "Devanagari", "Text"),
        Paragraph(715, "Synthetic transliteration marker.", "Verse", "Text"),
        Paragraph(720, "Synthetic synonyms marker.", "Synonyms", "Text"),
        Paragraph(740, "Synthetic translation marker.", "Translation", "Text"),
        Paragraph(799, "Synthetic first verse final purport marker.", "Purport", "Text"),
    )
    private val documents = mapOf(
        section.record to document(section, firstParagraphs, next = secondSection),
        secondSection.record to document(secondSection, listOf(
            Paragraph(900, "Synthetic combined verse opening marker.", "Verse", "Text"),
            Paragraph(999, "Synthetic combined verse final purport marker.", "Purport", "Text"),
        ), previous = section, next = thirdSection),
        thirdSection.record to document(thirdSection, listOf(
            Paragraph(1100, "Synthetic second chapter opening marker.", "Verse", "Text"),
            Paragraph(1199, "Synthetic second chapter final purport marker.", "Purport", "Text"),
        ), previous = secondSection, parentTitle = secondParent),
    )
    private val firstDocument get() = documents.getValue(section.record)

    // A separate range fits 140 strictly increasing records; the three standard units stay disjoint.
    private val longSection = ContentNode(1300, 1200, "01. Synthetic long verse", 1499, 4, 3)
    private val afterLongSection = ContentNode(1600, 1200, "02. Synthetic verse after long purport", 1699, 0, 3)
    private val longDocument = document(longSection, List(140) { index ->
        val record = if (index == 139) longSection.end else longSection.record + index
        Paragraph(record, "Synthetic long-unit paragraph ${index + 1}. " +
            "This generated purport stays inside the same complete verse while reading vertically. ".repeat(3).trimEnd(),
            "Purport", "Text")
    }, next = afterLongSection, parentTitle = "Synthetic long chapter")
    private val afterLongDocument = document(afterLongSection, listOf(
        Paragraph(1600, "Synthetic short verse after the long unit.", "Verse", "Text"),
        Paragraph(1699, "Synthetic short verse ending.", "Purport", "Text"),
    ), previous = longSection, parentTitle = "Synthetic long chapter")

    @Test
    fun directReaderOpenShowsTheWholeSectionWithOriginalUnicodeText() {
        val fixture = showReader(initiallyReading = false)

        openFromContents(fixture)

        compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(section.displayTitle)
        assertCompactChrome()
        assertUnit(fixture, firstDocument)
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        compose.onNodeWithTag(browseList(section)).assertDoesNotExist()
        assertDocumentText(originalUnicode)
        // Synonyms may be folded by the HTML renderer; native details expansion is tested separately.
        firstParagraphs.drop(1).filterNot { it.style == "Synonyms" }.forEach { assertDocumentText(it.text) }
        compose.runOnIdle {
            assertEquals(1, fixture.readCalls)
            assertEquals(listOf(section.location(library.id) to library.languageTag), fixture.requests)
            val loaded = requireNotNull(fixture.controller.state?.document)
            assertEquals(firstDocument, loaded)
            assertTrue(loaded.html.contains("Synthetic synonyms marker."))
            assertTrue("The HTML must retain the original Unicode code units", loaded.html.contains(originalUnicode))
            assertTrue(loaded.html.contains("<meta charset=\"UTF-8\">"))
        }
    }

    @Test
    fun emptySectionUsesLocalizedUntitledHeadingWithoutCreatingADocumentView() {
        val untitled = section.copy(title = "   ")
        val empty = document(untitled, emptyList(), parentTitle = "")
        val fixture = showReader(node = untitled, loader = { _, _ -> empty })

        waitFor(hasText(label(R.string.empty_page)))

        compose.onNodeWithTag("reader-title").assertTextEquals(label(R.string.browse_untitled, untitled.record))
        assertCompactChrome()
        compose.onNodeWithTag("reader-context").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.empty_page)).assertIsDisplayed()
        assertNoNativeDocument()
        compose.onNodeWithText(label(R.string.book_loading_greeting)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_error)).assertDoesNotExist()
        assertNativePaging(previous = false, next = false)
        compose.runOnIdle {
            val state = requireNotNull(fixture.controller.state)
            assertEquals("", state.location.title)
            assertEquals(empty, state.document)
            assertFalse(state.loading)
            assertFalse(state.failed)
        }
    }

    @Test
    fun loadingKeepsTitleAndDisablesPagingUntilTheSyntheticResponseArrives() {
        val response = CompletableDeferred<ReaderDocument>()
        val fixture = showReader(loader = { _, _ -> response.await() })
        try {
            waitFor(hasText(label(R.string.book_loading_greeting)))

            compose.onNodeWithTag("reader-title").assertTextEquals(section.displayTitle)
            compose.onNodeWithText(label(R.string.book_loading_greeting)).assertIsDisplayed()
            assertNoNativeDocument()
            compose.onNodeWithText(label(R.string.empty_page)).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.try_again)).assertDoesNotExist()
            assertCompactChrome()
            assertNativePaging(previous = false, next = false)
            compose.runOnIdle {
                assertTrue(requireNotNull(fixture.controller.state).loading)
                assertEquals(1, fixture.requests.size)
                response.complete(firstDocument)
            }

            assertUnit(fixture, firstDocument)
            compose.onNodeWithTag("reader-document").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.book_loading_greeting)).assertDoesNotExist()
            assertNativePaging(previous = false, next = true)
            assertUnit(fixture, firstDocument)
            compose.runOnIdle { assertEquals(firstDocument, fixture.controller.state?.document) }
        } finally {
            response.cancel()
        }
    }

    @Test
    fun failedSectionRetryLoadsTheSameLocationAndReplacesTheErrorWithText() {
        var attempts = 0
        val fixture = showReader(loader = { _, _ ->
            if (++attempts == 1) error("Synthetic reader failure")
            firstDocument
        })
        waitFor(hasText(label(R.string.reader_error)))

        compose.onNodeWithTag("reader-title").assertTextEquals(section.displayTitle)
        compose.onNodeWithText(label(R.string.reader_error)).assertIsDisplayed()
        assertNoNativeDocument()
        compose.onNodeWithText(label(R.string.empty_page)).assertDoesNotExist()
        assertNativePaging(previous = false, next = false)
        compose.onNodeWithText(label(R.string.try_again)).assertIsEnabled().performClick()

        assertUnit(fixture, firstDocument)
        compose.onNodeWithText(label(R.string.reader_error)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.try_again)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(List(2) { section.location(library.id) to library.languageTag }, fixture.requests)
        }
    }

    @Test
    fun nativeSwipesReplaceWholeRangesTitlesAndChapterContextWithoutHeaderArrowsOrPageFooter() {
        val fixture = showReader()
        assertUnit(fixture, firstDocument)

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, documents.getValue(secondSection.record))
        assertDocumentText(documents.getValue(secondSection.record).section.paragraphs.last().text)
        assertDocumentText(firstParagraphs.first().text, expectedMatches = 0)

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, documents.getValue(thirdSection.record))
        assertDocumentText(documents.getValue(secondSection.record).section.paragraphs.first().text, expectedMatches = 0)

        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, documents.getValue(secondSection.record))
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, firstDocument)
        assertDocumentText(documents.getValue(thirdSection.record).section.paragraphs.first().text, expectedMatches = 0)

        compose.runOnIdle {
            assertEquals(
                listOf(section, secondSection, thirdSection, secondSection, section)
                    .map { it.location(library.id) to library.languageTag },
                fixture.requests,
            )
        }
    }

    @Test
    fun toolbarBackInvokesReturnAndRestoresTheSameContents() {
        val fixture = showReader(initiallyReading = false)
        openFromContents(fixture)

        compose.onNodeWithTag("reader-back").assertIsEnabled().performClick()

        assertReturnedToContents(fixture)
    }

    @Test
    fun systemBackInvokesReturnWithoutFinishingTheHostActivity() {
        val fixture = showReader(initiallyReading = false)
        openFromContents(fixture)

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertReturnedToContents(fixture)
    }

    @Test
    fun saveableContentsPositionSurvivesAReaderRoundTrip() {
        val entries = List(60) { index ->
            val record = 720 + index
            ContentNode(record, section.record, "Synthetic subsection ${index + 1}", record, 0, 3)
        }
        val parent = section.copy(children = entries.size)
        val fixture = showReader(node = parent, entries = entries, initiallyReading = false)
        val list = browseList(parent)
        val marker = "section-${entries[45].record}"
        waitFor(hasTestTag(list))
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(marker))
        compose.onNodeWithTag(marker).assertIsDisplayed()
        val before = scrollPosition(list)
        assertTrue("The contents fixture must actually scroll", before > 0f)

        // Reader tests open the controller directly; navigation entry points have their own tests.
        compose.runOnIdle { fixture.controller.open(library.id, library.languageTag, parent) }
        waitFor(hasTestTag("reader-document"))
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        compose.onNodeWithTag(list).assertDoesNotExist()
        compose.onNodeWithTag("reader-back").performClick()

        waitFor(hasTestTag(list))
        // Deliberately do not scroll after returning: doing so could conceal a lost saved position.
        compose.onNodeWithTag(marker).assertIsDisplayed()
        assertEquals(before, scrollPosition(list), 0.001f)
        compose.onNodeWithTag("reader-document").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.backCalls)
            assertNull(fixture.controller.state)
            assertEquals(listOf(parent.location(library.id) to library.languageTag), fixture.requests)
        }
    }

    @Test
    fun nativeHorizontalSwipesNavigateWholeVersesAndBlockFirstPreviousAndLastNext() {
        val fixture = showReader()
        assertUnit(fixture, firstDocument)

        // These gestures go through the AndroidView, never directly through controller callbacks.
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, firstDocument)
        compose.runOnIdle { assertEquals(1, fixture.requests.size) }

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, documents.getValue(secondSection.record))
        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, documents.getValue(thirdSection.record))

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, documents.getValue(thirdSection.record))
        compose.runOnIdle { assertEquals(3, fixture.requests.size) }

        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, documents.getValue(secondSection.record))
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, firstDocument)
        compose.runOnIdle {
            assertEquals(
                listOf(section, secondSection, thirdSection, secondSection, section)
                    .map { it.location(library.id) to library.languageTag },
                fixture.requests,
            )
        }
    }

    @Test
    fun verticalSwipeScrollsLongPurportWithoutChangingUnitTitleOrLoadCount() {
        val fixture = showLongReader()
        assertUnit(fixture, longDocument)
        val view = scrollNativeWebView()
        val before = compose.runOnIdle { view.scrollY }

        compose.onNodeWithTag("reader-document").performTouchInput { swipeUp(durationMillis = 400) }

        waitForNativeCondition(view, "Vertical swipe must scroll and report its offset") {
            view.scrollY > before && requireNotNull(fixture.controller.state).scrollY > before
        }
        // Native find can scroll to a match, so do not search again after the gesture under test.
        assertUnit(fixture, longDocument, inspectText = false)
        compose.runOnIdle {
            assertSame(view, currentReaderView())
            assertTrue(view.scrollY > before)
            assertFalse(view.settings.javaScriptEnabled)
            assertEquals(listOf(longSection.location(library.id) to library.languageTag), fixture.requests)
        }
    }

    @Test
    fun screenReaderActionsRetainNavigationWithoutVisibleArrowButtons() {
        val fixture = showReader()
        assertUnit(fixture, firstDocument)
        val firstActions = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf(label(R.string.reader_next_verse)), firstActions.map { it.label })
        compose.runOnIdle { assertTrue(firstActions.single().action()) }
        assertUnit(fixture, documents.getValue(secondSection.record))
        val secondActions = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf(label(R.string.reader_previous_verse), label(R.string.reader_next_verse)),
            secondActions.map { it.label })
        compose.runOnIdle { assertTrue(secondActions.first().action()) }
        assertUnit(fixture, firstDocument)
        compose.runOnIdle { assertEquals(3, fixture.requests.size) }
    }

    @Test
    fun horizontalSwipeFromMidPurportLoadsNextWholeUnitAtZeroOffset() {
        val fixture = showLongReader()
        assertUnit(fixture, longDocument)
        val previousView = scrollNativeWebView()
        compose.runOnIdle {
            previousView.scrollTo(0, scrollableHeight(previousView) / 2)
        }
        waitForNativeCondition(previousView, "Mid-purport offset must reach the real controller") {
            previousView.scrollY > 0 && fixture.controller.state?.scrollY == previousView.scrollY
        }
        compose.runOnIdle {
            assertTrue(previousView.canScrollVertically(-1))
            assertTrue(previousView.canScrollVertically(1))
            assertEquals(longSection.location(library.id), fixture.controller.state?.location)
            assertEquals(1, fixture.requests.size)
        }

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }

        // Inspect the initial offset before any native find operation could move it.
        assertUnit(fixture, afterLongDocument, inspectText = false)
        val nextView = awaitNativeDocument()
        compose.runOnIdle {
            assertTrue("Changing units must replace the native document", previousView !== nextView)
            assertEquals(0, nextView.scrollY)
            assertEquals(0, fixture.controller.state?.scrollY)
            assertEquals(
                listOf(longSection, afterLongSection).map { it.location(library.id) to library.languageTag },
                fixture.requests,
            )
        }
        assertDocumentText(afterLongDocument.section.paragraphs.first().text)
        assertDocumentText(longDocument.section.paragraphs.first().text, expectedMatches = 0)
    }

    @Test
    fun rapidSwipesMakeExactlyOneRequestPerCompletedGestureWithoutSkippingAUnit() {
        val fixture = showReader()
        assertUnit(fixture, firstDocument)

        for ((index, node) in listOf(secondSection, thirdSection).withIndex()) {
            compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft(durationMillis = 80) }
            assertUnit(fixture, documents.getValue(node.record), inspectText = false)
            awaitNativeDocument()
            compose.runOnIdle { assertEquals(index + 2, fixture.requests.size) }
        }
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight(durationMillis = 80) }
        assertUnit(fixture, documents.getValue(secondSection.record))
        compose.runOnIdle {
            assertEquals(
                listOf(section, secondSection, thirdSection, secondSection)
                    .map { it.location(library.id) to library.languageTag },
                fixture.requests,
            )
        }
    }

    @Test
    fun pendingNavigationKeepsTheReadyNativePageAndLocksRapidGesturesUntilTheSwapSettles() {
        val response = CompletableDeferred<ReaderDocument>()
        val fixture = showReader(loader = { location, _ ->
            if (location.start == section.record) firstDocument else response.await()
        })
        try {
            assertUnit(fixture, firstDocument)
            val oldView = awaitNativeDocument()
            val host = compose.runOnIdle { requireNotNull(readerHost()) }

            compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft(durationMillis = 80) }
            compose.waitUntil(timeoutMillis = 5_000) { compose.runOnIdle { fixture.controller.state?.loading == true } }
            assertNativePaging(previous = false, next = false)
            compose.onNodeWithText(label(R.string.book_loading_greeting)).assertDoesNotExist()
            compose.runOnIdle {
                assertSame(host, readerHost())
                assertSame(oldView, host.currentView)
                assertTrue(host.isBusy)
                assertEquals(listOf(oldView), readerViews(host))
                assertTrue(oldView.isAttachedToWindow)
                assertEquals(1f, oldView.alpha, 0f)
                assertEquals(0f, oldView.translationX, 0f)
                assertFalse(oldView.interactionEnabled)
                assertFalse(oldView.canNextUnit)
                assertFalse(oldView.canPreviousUnit)
            }
            repeat(3) {
                compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft(durationMillis = 80) }
                compose.onNodeWithTag("reader-document").performTouchInput { swipeRight(durationMillis = 80) }
            }
            compose.runOnIdle {
                host.next()
                host.previous()
                oldView.onScrollPosition(777) // A queued callback from an outgoing page is not a new-page offset.
                assertEquals(2, fixture.requests.size)
                assertEquals(0, fixture.controller.state?.scrollY)
                response.complete(documents.getValue(secondSection.record))
            }

            assertUnit(fixture, documents.getValue(secondSection.record), inspectText = false)
            val newView = awaitNativeDocument()
            compose.runOnIdle {
                assertSame(host, readerHost())
                assertTrue(oldView !== newView)
                assertTrue(oldView.isReleased)
                assertFalse(oldView.isAttachedToWindow)
                assertEquals(listOf(newView), readerViews(host))
                assertEquals(0f, newView.translationX, 0f)
                assertEquals(1f, newView.alpha, 0f)
                assertEquals(0, fixture.controller.state?.scrollY)
                assertEquals(2, fixture.requests.size)
            }
        } finally {
            response.cancel()
        }
    }

    @Test
    fun navigationFailureAndEmptyRetryRemoveOldPageInteractionAndLeaveBackUsable() {
        val response = CompletableDeferred<ReaderDocument>()
        val empty = document(secondSection, emptyList(), previous = section, next = thirdSection)
        var attempts = 0
        val fixture = showReader(loader = { location, _ ->
            if (location.start == section.record) firstDocument
            else if (++attempts == 1) response.await() else empty
        })
        try {
            assertUnit(fixture, firstDocument)
            val oldView = awaitNativeDocument()
            compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
            compose.runOnIdle { response.completeExceptionally(IllegalStateException("Synthetic next-unit failure")) }
            waitFor(hasText(label(R.string.reader_error)))
            assertNoNativeDocument()
            assertNativePaging(previous = false, next = false)
            compose.onNodeWithTag("reader-back").assertIsEnabled()
            compose.runOnIdle {
                assertTrue(oldView.isReleased)
                oldView.onNextUnit()
                oldView.onScrollPosition(999)
                assertEquals(2, fixture.requests.size)
                assertEquals(0, fixture.controller.state?.scrollY)
            }

            compose.onNodeWithText(label(R.string.try_again)).performClick()
            waitFor(hasText(label(R.string.empty_page)))
            assertNoNativeDocument()
            assertNativePaging(previous = false, next = false)
            compose.onNodeWithText(label(R.string.reader_error)).assertDoesNotExist()
            compose.runOnIdle { assertEquals(3, fixture.requests.size) }
            compose.onNodeWithTag("reader-back").performClick()
            waitFor(hasTestTag(browseList(section)))
            compose.runOnIdle {
                assertNull(fixture.controller.state)
                assertEquals(1, fixture.backCalls)
            }
        } finally {
            response.cancel()
        }
    }

    @Test
    fun transparentIncomingPagesAreBoundedAndStaleVisualCallbacksCannotReplaceANewerRequest() {
        // Drive the host synchronously so assertions inspect the pre-render interval, not a timed race.
        compose.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("reader-document"),
                factory = { context -> ReaderPageHost(context).apply {
                    submit(ReaderState(section.location(library.id), "en", firstDocument, loading = false), 100) { null }
                } },
                onRelease = { it.release() },
            )
        }
        val oldView = awaitNativeDocument()
        compose.runOnIdle {
            val host = requireNotNull(readerHost())
            var navigationCalls = 0
            var scrollCalls = 0
            host.onNextUnit = { navigationCalls++ }
            host.onScrollPosition = { scrollCalls++ }
            host.submit(ReaderState(secondSection.location(library.id), "en",
                documents.getValue(secondSection.record), loading = false), 100) { null }
            val superseded = readerViews(host).single { it !== oldView }
            val staleReady = superseded.onRendered
            assertEquals(2, host.childCount)
            assertEquals(0f, superseded.alpha, 0f)
            assertEquals(View.VISIBLE, superseded.visibility)
            assertEquals(1f, oldView.alpha, 0f)
            assertSame(oldView, host.currentView)
            assertNull(host.settledView)
            assertFalse(oldView.interactionEnabled)
            assertFalse(superseded.interactionEnabled)
            assertFalse(superseded.settings.javaScriptEnabled)

            host.submit(ReaderState(thirdSection.location(library.id), "en",
                documents.getValue(thirdSection.record), loading = false), 100) { null }
            val newest = readerViews(host).single { it !== oldView }
            val newestReady = newest.onRendered
            staleReady()
            assertTrue(superseded.isReleased)
            assertEquals(2, host.childCount)
            assertSame(oldView, host.currentView)
            assertEquals(0f, newest.alpha, 0f)
            assertTrue(host.isBusy)
            oldView.onScrollPosition(777)
            assertEquals(0, scrollCalls)

            // The captured fence callback permits inspecting the transition in this same UI turn.
            newestReady()
            if (ValueAnimator.areAnimatorsEnabled()) {
                assertTrue(host.isBusy)
                assertFalse(oldView.interactionEnabled)
                assertFalse(newest.interactionEnabled)
            }
            host.next()
            assertEquals(0, navigationCalls)

            host.submit(ReaderState(thirdSection.location(library.id), "en",
                document(thirdSection, emptyList()), loading = false), 100) { null }
            newestReady()
            staleReady()
            assertEquals(0, host.childCount)
            assertNull(host.currentView)
            assertNull(host.settledView)
            assertTrue(oldView.isReleased)
            assertTrue(newest.isReleased)
        }
    }

    @Test
    fun nativeSwipesNavigateWholeChaptersInBothDirections() {
        val chapterOne = section.copy(title = "01. Synthetic whole chapter one")
        val chapterTwo = secondSection.copy(title = "02. Synthetic whole chapter two")
        val chapterDocuments = mapOf(
            chapterOne.record to document(chapterOne, firstParagraphs, next = chapterTwo,
                kind = ReadingUnitKind.CHAPTER, parentTitle = collection.displayTitle),
            chapterTwo.record to document(chapterTwo, documents.getValue(secondSection.record).section.paragraphs,
                previous = chapterOne, kind = ReadingUnitKind.CHAPTER, parentTitle = collection.displayTitle),
        )
        val fixture = showReader(node = chapterOne, loader = { location, _ -> chapterDocuments.getValue(location.start) })
        assertUnit(fixture, chapterDocuments.getValue(chapterOne.record))
        assertDocumentText(firstParagraphs.last().text)

        compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft() }
        assertUnit(fixture, chapterDocuments.getValue(chapterTwo.record))
        assertDocumentText(chapterDocuments.getValue(chapterTwo.record).section.paragraphs.last().text)
        compose.onNodeWithTag("reader-document").performTouchInput { swipeRight() }
        assertUnit(fixture, chapterDocuments.getValue(chapterOne.record))
        compose.runOnIdle {
            assertEquals(
                listOf(chapterOne, chapterTwo, chapterOne).map { it.location(library.id) to library.languageTag },
                fixture.requests,
            )
        }
    }

    private fun showReader(
        node: ContentNode = section,
        entries: List<ContentNode> = emptyList(),
        initiallyReading: Boolean = true,
        loader: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ -> documents.getValue(location.start) },
    ): Fixture {
        val fixture = Fixture()
        val libraryState = LibraryUiState(
            loading = false, libraries = listOf(library), active = library, collections = listOf(collection),
            browser = BrowseState(libraryId = library.id, path = listOf(collection, node), entries = entries),
        )
        compose.setContent {
            var reader by remember { mutableStateOf<ReaderState?>(null) }
            val scope = rememberCoroutineScope()
            val controller = remember(scope) {
                ReaderController(
                    scope = scope,
                    loadDocument = { location, language ->
                        fixture.requests += location to language
                        loader(location, language)
                    },
                    onChange = { reader = it },
                )
            }
            val screens = rememberSaveableStateHolder()
            DisposableEffect(controller) {
                fixture.controller = controller
                if (initiallyReading) {
                    fixture.readCalls++
                    controller.open(library.id, library.languageTag, node)
                }
                onDispose { controller.close() }
            }
            VedaPreviewTheme {
                val reading = reader
                if (reading == null) screens.SaveableStateProvider("library") {
                    LibraryScreen(state = libraryState)
                } else screens.SaveableStateProvider("reader") {
                    ReviewedReaderScreen(
                        state = reading,
                        onBack = { fixture.backCalls++; controller.close() },
                        onNext = controller::next,
                        onPrevious = controller::previous,
                        onRetry = controller::retry,
                        onScroll = controller::saveScroll,
                    )
                }
            }
        }
        return fixture
    }

    private fun showLongReader(): Fixture {
        val units = mapOf(longSection.record to longDocument, afterLongSection.record to afterLongDocument)
        return showReader(node = longSection, loader = { location, _ -> units.getValue(location.start) })
    }

    private fun openFromContents(fixture: Fixture) {
        waitFor(hasTestTag(browseList(section)))
        compose.runOnIdle {
            fixture.readCalls++
            fixture.controller.open(library.id, library.languageTag, section)
        }
        awaitNativeDocument()
    }

    private fun assertReturnedToContents(fixture: Fixture) {
        waitFor(hasTestTag(browseList(section)))
        compose.onNodeWithTag(browseList(section)).assertIsDisplayed()
        compose.onNodeWithTag("reader-document").assertDoesNotExist()
        compose.onNodeWithTag("reader-back").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.readCalls)
            assertEquals(1, fixture.backCalls)
            assertNull(fixture.controller.state)
            assertFalse(compose.activity.isFinishing)
        }
    }

    private fun assertUnit(fixture: Fixture, expected: ReaderDocument, inspectText: Boolean = true) {
        val unit = expected.section
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                fixture.controller.state?.let { !it.loading && !it.failed && it.document == expected } == true
            }
        }
        waitFor(hasTestTag("reader-document"))
        val view = awaitNativeDocument()
        compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(unit.node.displayTitle)
        if (unit.parentTitle.isBlank()) compose.onNodeWithTag("reader-context").assertDoesNotExist()
        else compose.onNodeWithTag("reader-context").assertIsDisplayed().assertTextEquals(unit.parentTitle)
        assertCompactChrome()
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        assertNativePaging(previous = unit.previous != null, next = unit.next != null)
        compose.runOnIdle {
            val state = requireNotNull(fixture.controller.state)
            // Full equality checks start/end/title/position, not just a within-verse record offset.
            assertEquals(unit.node.location(library.id), state.location)
            assertEquals(expected, state.document)
            assertFalse(state.loading)
            assertFalse(state.failed)
            assertSame(view, currentReaderView())
            assertEquals("Only the settled document may remain mounted", listOf(view), readerViews(requireNotNull(readerHost())))
            assertEquals(unit.previous != null, view.canPreviousUnit)
            assertEquals(unit.next != null, view.canNextUnit)
            assertFalse(view.settings.javaScriptEnabled)
            assertEquals(ReadingMode.VERSE, state.mode)
            assertFalse(view.paginatedBook)
            assertNull(view.bookPageInfo)
            assertNull(state.document?.bookResource)
            assertEquals(0, view.scrollX)
        }
        if (inspectText) assertDocumentText(unit.paragraphs.first().text)
    }

    private fun assertCompactChrome() {
        compose.onNodeWithTag("reader-title").assertIsDisplayed()
        compose.onNodeWithTag("reader-back").assertIsDisplayed().assertIsEnabled()
            .assertHasClickAction().assertContentDescriptionEquals(label(R.string.reader_back))
        compose.onNodeWithText(label(R.string.back), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-mode-toggle").assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(label(R.string.reader_switch_to_book))
        compose.onNodeWithText(label(R.string.reader_mode_verse)).assertDoesNotExist()
        compose.onNodeWithTag("reader-previous", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-next", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-book-contents").assertDoesNotExist()
        compose.onNodeWithTag("reader-book-progress").assertDoesNotExist()
        compose.onNodeWithTag("reader-book-seek").assertDoesNotExist()
        compose.onNodeWithTag("reader-book-previous").assertDoesNotExist()
        compose.onNodeWithTag("reader-book-next").assertDoesNotExist()
        // Historical strings intentionally stay literal: their removed resource IDs must not return.
        compose.onNodeWithTag("reader-page").assertDoesNotExist()
        compose.onNodeWithTag("reader-footer").assertDoesNotExist()
        compose.onNodeWithTag("reader-end").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher("no Page N or end-of-section footer") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
                Regex("(?i)\\bpage\\s+\\d+\\b|\\bend of (?:this |the )?section\\b").containsMatchIn(text.text)
            }
        }).fetchSemanticsNodes().let { assertTrue("Artificial page/footer text must be absent", it.isEmpty()) }
        compose.onNodeWithText(label(R.string.reader_swipe_hint)).assertDoesNotExist()
    }

    private fun assertNativePaging(previous: Boolean, next: Boolean) {
        compose.onNodeWithTag("reader-previous", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reader-next", useUnmergedTree = true).assertDoesNotExist()
        val actions = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals("Screen-reader actions follow the native navigation gate",
            (if (previous) 1 else 0) + (if (next) 1 else 0), actions.size)
        compose.runOnIdle {
            val host = requireNotNull(readerHost())
            // settledView is null while busy: inspect the retained current page so stale
            // navigation capabilities cannot be mistaken for a correctly locked host.
            val view = host.currentView
            assertEquals("Native previous-unit capability", previous, view?.canPreviousUnit == true)
            assertEquals("Native next-unit capability", next, view?.canNextUnit == true)
            if (host.isBusy) {
                assertFalse("Busy hosts must block previous navigation", previous)
                assertFalse("Busy hosts must block next navigation", next)
                assertNull(host.settledView)
                // Initial loading/error/empty may have no page; retained/incoming pages must be inert.
                readerViews(host).forEach { page ->
                    assertFalse(page.interactionEnabled)
                    assertFalse(page.canPreviousUnit)
                    assertFalse(page.canNextUnit)
                }
            } else {
                val settled = requireNotNull(view)
                assertSame(settled, host.settledView)
                assertTrue(settled.isAttachedToWindow)
                assertTrue(settled.interactionEnabled)
            }
        }
    }

    /** Native text inspection is only a test assertion: no JavaScript, URL navigation, or search UI. */
    private fun assertDocumentText(text: String, expectedMatches: Int = 1) {
        val view = awaitNativeDocument()
        val matches = AtomicInteger(-1)
        try {
            compose.runOnIdle {
                assertEquals(label(R.string.reader_document), view.contentDescription.toString())
                assertFalse(view.settings.javaScriptEnabled)
                view.setFindListener { _, count, finished -> if (finished) matches.set(count) }
                view.findAllAsync(text)
            }
            waitForNativeCondition(view, "Native text search did not finish: $text", timeoutMillis = 5_000) {
                matches.get() >= 0
            }
            assertEquals("Rendered document matches for: $text", expectedMatches, matches.get())
        } finally {
            compose.runOnIdle {
                view.setFindListener(null)
                view.clearMatches()
            }
        }
    }

    private fun awaitNativeDocument(): OfflineReaderView {
        waitFor(hasTestTag("reader-document"))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnIdle { currentReaderView()?.isAttachedToWindow == true }
        }
        val view = compose.runOnIdle { requireNotNull(currentReaderView()) }
        waitForNativeCondition(view, "Reader did not finish rendering") {
            view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                view.title == "Reader" && view.url?.matches(Regex("https://reader\\.local/document/[1-9][0-9]*")) == true
        }
        // Progress alone can describe an error page and does not guarantee a laid-out document.
        // Keep the production WebViewClient (including its initial-scroll callback) intact.
        awaitNativeVisualState(view)
        return view
    }

    private fun awaitNativeVisualState(view: OfflineReaderView) {
        val ready = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.set(true) }
            })
            view.invalidate()
        }
        waitForNativeCondition(view, "Reader visual state did not settle") { ready.get() }
    }

    private fun scrollNativeWebView(): OfflineReaderView {
        val view = awaitNativeDocument()
        waitForNativeCondition(view, "Long purport must have more than a viewport of scrollable text") {
            scrollableHeight(view) > view.height
        }
        // findAllAsync can move the viewport even after its match callback. Drain the native
        // visual work after clearing matches, then reset so it cannot masquerade as a swipe.
        compose.runOnIdle { view.clearMatches() }
        awaitNativeVisualState(view)
        compose.runOnIdle { view.scrollTo(0, 0) }
        awaitNativeVisualState(view)
        waitForNativeCondition(view, "Long purport must start at the top with room to swipe vertically") {
            view.scrollY == 0 && !view.canScrollVertically(-1) && view.canScrollVertically(1)
        }
        return view
    }

    /** Called on the main thread: contentHeight is in CSS pixels, scrollY/height in view pixels. */
    @Suppress("DEPRECATION") // Native scale avoids enabling JavaScript just to measure scroll range.
    private fun scrollableHeight(view: WebView): Int =
        ((view.contentHeight * view.scale).toInt() - view.height + view.paddingTop + view.paddingBottom)
            .coerceAtLeast(0)

    /** Poll only the captured, attached view; every native property and controller read is on UI. */
    private fun waitForNativeCondition(
        view: OfflineReaderView,
        message: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                compose.runOnIdle {
                    view.isAttachedToWindow && currentReaderView() === view &&
                        condition()
                }
            }
        } catch (error: ComposeTimeoutException) {
            val detail = compose.runOnIdle {
                "progress=${view.progress}, contentHeight=${view.contentHeight}, title=${view.title}, url=${view.url?.take(120)}, " +
                    "width=${view.width}, height=${view.height}, scrollY=${view.scrollY}, scrollableHeight=${scrollableHeight(view)}, " +
                    "attached=${view.isAttachedToWindow}, current=${currentReaderView() === view}"
            }
            throw AssertionError("$message: $detail", error)
        }
    }

    private fun assertNoNativeDocument() {
        compose.onNodeWithTag("reader-document").assertExists() // The session host itself stays mounted.
        compose.runOnIdle {
            assertTrue(readerViews(compose.activity.window.decorView).isEmpty())
            assertNull(currentReaderView())
        }
    }

    // Called only on the main thread. During a transition there may legitimately be two WebViews.
    private fun currentReaderView() = readerHost()?.settledView

    private fun readerHost(view: View = compose.activity.window.decorView): ReaderPageHost? = when (view) {
        is ReaderPageHost -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { readerHost(view.getChildAt(it)) }
        else -> null
    }

    // Compose semantics alone cannot inspect WebView text.
    private fun readerViews(view: View): List<OfflineReaderView> = when (view) {
        is OfflineReaderView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { readerViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun document(
        node: ContentNode,
        paragraphs: List<Paragraph>,
        previous: ContentNode? = null,
        next: ContentNode? = null,
        kind: ReadingUnitKind = ReadingUnitKind.VERSE,
        parentTitle: String = firstParent,
    ) = ReaderDocument(
        ReadingSection(node = node, kind = kind, parentTitle = parentTitle, paragraphs = paragraphs,
            previous = previous, next = next),
        FolioText.document(paragraphs, library.languageTag, ReaderPreferences()),
    )

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
    }

    private fun scrollPosition(list: String): Float =
        compose.onNodeWithTag(list).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun browseList(node: ContentNode) = "browse-list-${node.record}"

    private fun label(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private class Fixture {
        lateinit var controller: ReaderController
        val requests = mutableListOf<Pair<ReadingLocation, String>>()
        var readCalls = 0
        var backCalls = 0
    }
}