package org.vedareader.ui

import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.BookPageInfo
import org.vedareader.reader.BookPagination
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real edge-to-edge window, reviewed Compose screen, controller and native WebViews.
 * Synthetic in-memory source only: no MainActivity, repository, database or preference writes.
 * Uses the small ReadingModeToggleTest font/anchor fixture shape and production Folio layouts.
 */
@RunWith(AndroidJUnit4::class)
class ReaderFullscreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = ContentNode(100, 0, "Synthetic fullscreen resource", 160, 1, 1)
    private val verse = ContentNode(101, 100, "Synthetic verse", 160, 0, 2)
    private val selection = verse.copy(record = 128) // Interior source, not a vacuous first-page check.
    private val paragraphs = listOf(
        Paragraph(100, "Synthetic chapter", "", "LE_Chapter"),
        Paragraph(101, "Bg 1.1", "PA_Verse_Number_Heading", "LE_Verse_Number"),
        Paragraph(102, "नमूना कृष्ण", "PA_DevaNagariQ", ""),
        Paragraph(103, "Synthetic śloka ā a\u0304", "PA_Verse", "LE_Verse_Text"),
        Paragraph(104, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
        Paragraph(105, "<CS:\"Italic\">śabda</CS> — synthetic word.", "PA_Synonyms", ""),
        Paragraph(106, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
        Paragraph(107, "A complete synthetic translation.", "PA_Translation", ""),
        Paragraph(108, "PURPORT", "PA_Titles", "LE_Verse_Section"),
    ) + (109..160).map { record -> Paragraph(record,
        "Synthetic purport $record. " + "Complete original prose stays available in both reading modes. ".repeat(3),
        "PA_Purport", "") }
    private val section = ReadingSection(book, ReadingUnitKind.CHAPTER, book.title, paragraphs)
    private val outline = BookOutline(book, listOf(
        BookEntry(book.copy(end = 100, children = 0), book, ReadingUnitKind.SECTION, book.title),
        BookEntry(verse, verse, ReadingUnitKind.VERSE, book.title),
    ))
    private val chrome = listOf("reader-title", "reader-context", "reader-back", "reader-mode-toggle",
        "reader-fullscreen", "reader-book-progress", "reader-book-contents", "reader-text-size",
        "reader-page-previous", "reader-page-next")

    @Test
    fun scrollingFullscreenGrowsViewportAndBackRestoresClampedScrollBeforeExit() {
        assertBackRoundTrip(ReadingMode.VERSE)
    }

    @Test
    fun pagedFullscreenGrowsViewportAndBackKeepsVisibleSourceBeforeExit() {
        assertBackRoundTrip(ReadingMode.BOOK)
    }

    @Test
    fun externalUnmountAndEmptyResourceRestoreBarsAndReentryDefaultsToNormal() {
        val fixture = showReader()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            selectMode(fixture, mode)
            val view = awaitReady(fixture, mode)
            val before = capture(fixture, view)
            enterFullscreen(fixture, before)

            // Parent-controlled unmount, not Back and not a replacement Compose root. The
            // controller/resource deliberately survive, exercising AndroidView.onRelease.
            compose.runOnIdle { fixture.mounted = false }
            awaitBars(visible = true)
            compose.onNodeWithTag("outside-reader").assertIsDisplayed()
            compose.onNodeWithTag("reader-document").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(view.isReleased)
                assertNull(readerHost())
                assertSame(before.document, fixture.controller.state?.document)
                assertEquals(before.requests, fixture.requests.size)
                assertEquals(0, fixture.backCalls)
                assertEquals(before.window, windowPolicy())
                fixture.mounted = true
            }
            val reopened = awaitReady(fixture, mode)
            assertNormalChrome()
            compose.runOnIdle {
                assertNotSame(view, reopened)
                assertEquals(before.requests, fixture.requests.size)
                assertSame(before.document, fixture.controller.state?.document)
            }

            val normal = capture(fixture, reopened)
            enterFullscreen(fixture, normal)
            // A valid empty resource comes through the real loader/controller, not fake status.
            compose.runOnIdle { fixture.emptyNext = true; fixture.controller.open(LIBRARY, "en", selection) }
            awaitState { fixture.controller.state?.let { !it.loading && !it.failed &&
                it.document?.section?.paragraphs?.isEmpty() == true } == true }
            awaitBars(visible = true)
            compose.onNodeWithText(label(R.string.empty_page)).assertIsDisplayed()
            assertNormalChrome(ready = false)
            compose.runOnIdle {
                assertTrue(reopened.isReleased)
                assertNull(readerHost()?.currentView)
                assertEquals(normal.requests + 1, fixture.requests.size)
                assertEquals(normal.window, windowPolicy())
                fixture.controller.open(LIBRARY, "en", selection)
            }
            awaitReady(fixture, mode)
            assertNormalChrome()
            compose.runOnIdle { assertEquals(0, fixture.backCalls) }
        }
    }

    @Test
    fun lifecycleResumeKeepsFullscreenAndAccessibilityExitRestoresWindow() {
        val fixture = showReader()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            selectMode(fixture, mode)
            val view = awaitReady(fixture, mode)
            val before = capture(fixture, view)
            enterFullscreen(fixture, before)
            val full = capture(fixture, view)

            // STARTED pauses (does not recreate) the real Activity/LocalLifecycleOwner.
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitBars(visible = false)
            assertFullscreenChrome()
            awaitPosition(fixture, view, full.reflowAnchor.takeIf { mode == ReadingMode.BOOK },
                full.offset.takeIf { mode == ReadingMode.VERSE })
            assertRetained(fixture, before)

            exitAccessibly()
            awaitBars(visible = true)
            assertNormalChrome()
            assertRetained(fixture, before)
            // A removed resume/focus observer must not re-hide bars after fullscreen exits.
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            fence(view)
            awaitBars(visible = true)
            compose.runOnIdle {
                assertEquals(before.window, windowPolicy())
                assertEquals(0, fixture.backCalls)
                assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            }
        }
    }

    @Test
    fun pendingLoadsDisableFullscreenInBothModes() {
        val bookGate = CompletableDeferred<Unit>()
        val textGate = CompletableDeferred<Unit>()
        val fixture = showReader(ReadingMode.BOOK) { mode ->
            if (mode == ReadingMode.BOOK) bookGate.await() else textGate.await()
        }
        try {
            for ((index, mode) in listOf(ReadingMode.BOOK, ReadingMode.VERSE).withIndex()) {
                awaitState { fixture.controller.state?.let { it.mode == mode && it.loading } == true &&
                    fixture.requests.size == index + 1 }
                awaitBars(visible = true)
                val normal = compose.runOnIdle { windowPolicy() }
                assertLoadingCopy(if (index == 0) "reader-loading" else "reader-page-loading")
                compose.onNodeWithTag("reader-fullscreen").assertIsDisplayed().assertIsNotEnabled()
                    .assertContentDescriptionEquals(label(R.string.reader_enter_fullscreen))
                    .performTouchInput { click() } // Disabled semantics alone would not test a real tap.
                compose.onNodeWithTag("reader-title").assertIsDisplayed()
                compose.onNodeWithTag("reader-text-size").assertIsDisplayed().assertIsNotEnabled()
                    .assertContentDescriptionEquals(label(R.string.reader_settings)).performTouchInput { click() }
                compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
                assertExitAction(present = false)
                compose.runOnIdle {
                    assertEquals(normal, windowPolicy())
                    assertEquals(index + 1, fixture.requests.size)
                    assertEquals(index, fixture.toggleCalls)
                    assertEquals(0, fixture.backCalls)
                    if (index == 0) assertNull(readerHost()?.currentView)
                    (if (mode == ReadingMode.BOOK) bookGate else textGate).complete(Unit)
                }
                awaitReady(fixture, mode)
                assertNormalChrome()
                if (index == 0) compose.onNodeWithTag("reader-mode-toggle").performClick()
            }
            compose.runOnIdle {
                assertEquals(listOf(ReadingMode.BOOK, ReadingMode.VERSE), fixture.requests.map { it.first })
            }
        } finally {
            bookGate.cancel()
            textGate.cancel()
        }
    }

    @Test
    fun loadAndNativeRenderFailuresExitFullscreenAndRetryInBothModes() {
        val fixture = showReader()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            selectMode(fixture, mode)
            val before = capture(fixture, awaitReady(fixture, mode))
            enterFullscreen(fixture, before)
            compose.runOnIdle { fixture.failNext = true; fixture.controller.open(LIBRARY, "en", selection) }
            awaitState { fixture.controller.state?.let { it.failed && !it.loading } == true }
            assertErrorRestored(before.window)
            compose.runOnIdle {
                assertTrue(before.view.isReleased)
                assertEquals(before.requests + 1, fixture.requests.size)
            }
            val retries = compose.runOnIdle { fixture.retryCalls }
            compose.onNodeWithText(label(R.string.try_again)).assertIsEnabled().performClick()
            val recovered = awaitReady(fixture, mode)
            assertNormalChrome()
            compose.runOnIdle {
                assertEquals(retries + 1, fixture.retryCalls)
                assertEquals(before.requests + 2, fixture.requests.size)
            }

            val rendered = capture(fixture, recovered)
            enterFullscreen(fixture, rendered)
            // Drive the installed WebViewClient's real main-frame failure path. No renderer
            // process crash, reflection, mocked host or network request is needed.
            compose.runOnIdle {
                recovered.webViewClient.onReceivedHttpError(recovered, MainFrame(requireNotNull(recovered.url)),
                    WebResourceResponse("text/html", "UTF-8", 503, "Synthetic failure", emptyMap(),
                        ByteArrayInputStream(ByteArray(0))))
            }
            assertErrorRestored(rendered.window)
            compose.runOnIdle {
                assertFalse(requireNotNull(fixture.controller.state).failed)
                assertTrue(recovered.isReleased)
                assertNull(readerHost()?.currentView)
                assertSame(rendered.document, fixture.controller.state?.document)
            }
            compose.onNodeWithText(label(R.string.try_again)).assertIsEnabled().performClick()
            val rerendered = awaitReady(fixture, mode)
            assertNormalChrome()
            compose.runOnIdle {
                assertNotSame(recovered, rerendered)
                assertSame(rendered.host, readerHost())
                assertSame(rendered.document, fixture.controller.state?.document)
                assertEquals("Render retry must not reload the source", rendered.requests, fixture.requests.size)
                assertEquals("Render retry belongs to the host", retries + 1, fixture.retryCalls)
            }
            enterFullscreen(fixture, capture(fixture, rerendered))
            exitAccessibly()
            awaitBars(visible = true)
            assertNormalChrome()
            compose.runOnIdle { assertEquals(0, fixture.backCalls) }
        }
    }

    @Test
    fun nativeProseTapTogglesBottomExitWithoutReflowAndExitRestoresNormalChromeInBothModes() {
        val fixture = showReader()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            selectMode(fixture, mode)
            val view = awaitReady(fixture, mode)
            assertNormalFullscreenButtonBelowBody()
            val normalBounds = (chrome + "reader-document").associateWith {
                compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            }
            val before = capture(fixture, view)
            enterFullscreen(fixture, before)
            val full = viewport(view)
            val layout = measure(view, 0).toString()
            val policy = compose.runOnIdle { windowPolicy() }

            // A real native hit on rendered prose, not a direct onReaderTap callback or
            // an accessibility action that could hide a broken WebView touch bridge.
            repeat(3) { index ->
                tapVisibleProse(view)
                val visible = index != 1
                if (visible) compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(label(R.string.reader_exit_fullscreen)).fetchSemanticsNodes().isNotEmpty()
                } else compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(label(R.string.reader_exit_fullscreen)).fetchSemanticsNodes().isEmpty()
                }
                fence(view)
                assertFullscreenChrome(controlsVisible = visible)
                awaitBars(visible = false)
                assertEquals("An overlay must not resize, scroll or repaginate $mode", full, viewport(view))
                assertEquals(layout, measure(view, 0).toString())
                assertRetained(fixture, before)
                compose.runOnIdle { assertEquals(policy, windowPolicy()); assertEquals(0, fixture.backCalls) }
                if (visible) {
                    val body = compose.onNodeWithTag("reader-document").fetchSemanticsNode().boundsInRoot
                    val exit = compose.onNodeWithTag("reader-fullscreen-exit").fetchSemanticsNode().boundsInRoot
                    assertTrue("Exit overlays the bottom half of the reader", exit.top > body.center.y)
                    assertTrue("Exit stays inside the unchanged reader viewport", exit.bottom <= body.bottom + 1f)
                }
            }

            compose.onNodeWithTag("reader-fullscreen-exit").performClick()
            awaitBars(visible = true)
            assertNormalChrome()
            assertNormalFullscreenButtonBelowBody()
            awaitState { view.height == before.height && view.viewportHeight == before.extent }
            for ((tag, original) in normalBounds) assertEquals("$tag restores its actual pre-fullscreen bounds",
                original, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
            awaitPosition(fixture, view, before.reflowAnchor.takeIf { mode == ReadingMode.BOOK },
                full.scrollY.takeIf { mode == ReadingMode.VERSE })
            assertRetained(fixture, before)
            compose.runOnIdle { assertEquals(before.window, windowPolicy()); assertEquals(0, fixture.backCalls) }
        }
    }

    @Test
    fun fullscreenScrollAndPageSwipesMoveContentWithoutRevealingExitInEitherMode() {
        val fixture = showReader()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            selectMode(fixture, mode)
            val view = awaitReady(fixture, mode)
            val before = capture(fixture, view)
            enterFullscreen(fixture, before)
            val full = viewport(view)
            if (mode == ReadingMode.BOOK) {
                val page = requireNotNull(full.page)
                assertTrue("The fixture has another real column", page.page < page.count - 1)
                compose.onNodeWithTag("reader-document").performTouchInput { swipeLeft(durationMillis = 400) }
                awaitState { readerHost()?.settledView === view && view.bookPageInfo?.page == page.page + 1 }
            } else {
                compose.onNodeWithTag("reader-document").performTouchInput { swipeUp(durationMillis = 400) }
                awaitState { view.scrollY > full.scrollY }
            }
            awaitPosition(fixture, view)
            // Drain native DOWN/UP hit-query work as well as the scroll/page compositor fence.
            repeat(3) { evaluate(view, "0"); fence(view) }
            assertFullscreenChrome()
            awaitBars(visible = false)
            assertEquals(full.bounds, viewport(view).bounds)
            assertRetained(fixture, before)

            exitAccessibly()
            awaitBars(visible = true)
            awaitReady(fixture, mode)
            assertNormalChrome()
        }
    }

    @Test
    fun pendingResourceKeepsFooterBoundsTextAndRenderedControlColorsWhileBlockingCallbacks() {
        var gate: CompletableDeferred<Unit>? = null
        val fixture = showReader { gate?.await() }
        val controls = listOf("reader-mode-toggle", "reader-book-contents", "reader-text-size", "reader-fullscreen")
        val geometry = controls + listOf("reader-document", "reader-book-progress", "reader-title", "reader-context",
            "reader-page-previous", "reader-page-next")
        val labels = listOf("reader-title", "reader-context", "reader-book-progress")
        try {
            for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
                selectMode(fixture, mode)
                val view = awaitReady(fixture, mode)
                if (mode == ReadingMode.BOOK && compose.runOnIdle { view.bookPageInfo?.page == 0 }) {
                    // A preceding reload may land on the opening headings; put real prose
                    // in view before probing the busy host's input gate.
                    compose.runOnIdle { requireNotNull(readerHost()).next() }
                    awaitReady(fixture, mode)
                }
                assertNormalFullscreenButtonBelowBody()
                val before = capture(fixture, view)
                val target = outline.entries.single { before.anchor in it.node.record..it.node.end }.node.record
                val oldViewport = viewport(view)
                val oldContent = evaluate(view, "document.querySelector('main.book-flow').textContent")
                val oldLayout = measure(view, 0).toString()
                val oldBounds = geometry.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                val oldLabels = labels.associateWith {
                    compose.onNodeWithTag(it).fetchSemanticsNode().config[SemanticsProperties.Text]
                }
                val pixels = controls.associateWith { tag ->
                    compose.onNodeWithTag(tag).assertIsEnabled()
                    controlPixels(tag).also { painted ->
                        val ink = (if (tag == "reader-mode-toggle") ReaderPaperColors.OnHeader
                            else ReaderPaperColors.Accent).toArgb()
                        val paper = (if (tag == "reader-mode-toggle") ReaderPaperColors.Header
                            else ReaderPaperColors.Paper).toArgb()
                        assertTrue("$tag must paint reader ink", painted.any { closeColor(it, ink) })
                        assertTrue("$tag must paint reader paper", painted.any { closeColor(it, paper) })
                    }
                }
                val toggles = compose.runOnIdle { fixture.toggleCalls }
                val selections = compose.runOnIdle { fixture.entrySelections.toList() }
                val pending = CompletableDeferred<Unit>()
                compose.runOnIdle {
                    gate = pending
                    // Same-mode reload through the real controller discards document metadata,
                    // while the host must keep its already painted resource on screen.
                    fixture.controller.jumpToBookEntry(target)
                }
                awaitState { fixture.controller.state?.loading == true && readerHost()?.isBusy == true &&
                    fixture.requests.size == before.requests + 1 }
                assertLoadingCopy("reader-page-loading")
                compose.runOnIdle {
                    assertNull(fixture.controller.state?.document)
                    assertSame(before.host, readerHost())
                    assertSame(view, readerHost()?.currentView)
                    assertEquals(before.url, view.url)
                    assertNull(readerHost()?.settledView)
                    assertFalse(view.interactionEnabled)
                    assertFalse(view.isReleased)
                    assertTrue(view.isShown)
                    assertEquals(1f, view.alpha, 0f)
                }
                for (tag in controls) {
                    compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled()
                    // Compare rendered centre pixels, not a theme constant/semantics value.
                    // Capturing before disabled taps avoids attributing ripples to a tint change.
                    assertArrayEquals("Disabled $tag must not blink/fade in $mode", pixels.getValue(tag), controlPixels(tag))
                    compose.onNodeWithTag(tag).performTouchInput { click() }
                }
                tapVisibleProse(view)
                compose.onNodeWithTag("reader-document").performTouchInput {
                    if (mode == ReadingMode.BOOK) swipeLeft(durationMillis = 400) else swipeUp(durationMillis = 400)
                }
                repeat(3) { evaluate(view, "0"); fence(view) }
                for (tag in geometry) assertEquals("$tag must stay placed while metadata is pending",
                    oldBounds.getValue(tag), compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
                for (tag in labels) assertEquals("$tag must retain its displayed content",
                    oldLabels.getValue(tag), compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text])
                assertEquals(oldViewport, viewport(view))
                assertEquals(oldLayout, measure(view, 0).toString())
                assertEquals(oldContent, evaluate(view, "document.querySelector('main.book-flow').textContent"))
                assertNormalChrome(ready = false)
                assertNormalFullscreenButtonBelowBody()
                compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
                compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
                awaitBars(visible = true)
                compose.runOnIdle {
                    assertEquals(before.requests + 1, fixture.requests.size)
                    assertEquals(toggles, fixture.toggleCalls)
                    assertEquals(selections, fixture.entrySelections)
                    assertEquals(0, fixture.navigationCalls)
                    assertEquals(0, fixture.backCalls)
                    assertEquals(before.window, windowPolicy())
                    gate = null
                    pending.complete(Unit)
                }
                val replacement = awaitReady(fixture, mode)
                assertNormalChrome()
                controls.forEach { compose.onNodeWithTag(it).assertIsEnabled() }
                compose.runOnIdle {
                    assertNotSame(view, replacement)
                    assertTrue(view.isReleased)
                    assertSame(before.host, readerHost())
                    assertEquals(before.requests + 1, fixture.requests.size)
                }
            }
        } finally {
            gate?.cancel()
        }
    }

    private fun assertBackRoundTrip(mode: ReadingMode) {
        val fixture = showReader(mode)
        val view = awaitReady(fixture, mode)
        if (mode == ReadingMode.VERSE) {
            compose.runOnIdle { view.scrollTo(0, view.scrollRange / 2) }
            awaitPosition(fixture, view)
        } else if (compose.runOnIdle { requireNotNull(view.bookPageInfo).page == 0 }) {
            // A wide tablet can fit the selected paragraph on its first page. Move using
            // the real host instead of assuming a phone's line count or native dimensions.
            compose.runOnIdle { requireNotNull(readerHost()).next() }
            awaitReady(fixture, mode)
        }
        val before = capture(fixture, view)
        assertTrue("Exercise an interior source paragraph", before.anchor > verse.record)
        if (mode == ReadingMode.VERSE) assertTrue(before.offset > 0)
        else compose.runOnIdle { assertTrue(requireNotNull(view.bookPageInfo).page > 0) }
        enterFullscreen(fixture, before)
        val full = capture(fixture, view)

        pressBack()
        awaitBars(visible = true)
        assertNormalChrome()
        awaitState { view.height == before.height && view.viewportHeight == before.extent }
        awaitPosition(fixture, view, full.reflowAnchor.takeIf { mode == ReadingMode.BOOK },
            full.offset.takeIf { mode == ReadingMode.VERSE })
        assertRetained(fixture, before)
        compose.runOnIdle {
            assertEquals("First Back only exits fullscreen", 0, fixture.backCalls)
            assertEquals(before.window, windowPolicy())
        }

        pressBack()
        compose.onNodeWithTag("outside-reader").assertIsDisplayed()
        compose.onNodeWithTag("reader-document").assertDoesNotExist()
        awaitBars(visible = true)
        compose.runOnIdle {
            assertEquals("Second Back exits the reader once", 1, fixture.backCalls)
            assertNull(fixture.controller.state)
            assertNull(readerHost())
            assertTrue(view.isReleased)
            assertEquals(before.requests, fixture.requests.size)
            assertFalse(compose.activity.isFinishing)
            assertEquals(before.window, windowPolicy())
        }
    }

    private fun showReader(initialMode: ReadingMode = ReadingMode.VERSE,
        beforeLoad: suspend (ReadingMode) -> Unit = {}): Fixture {
        val fixture = Fixture()
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            // Establish a normal baseline only here; never help production restore its bars.
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember(scope) {
                suspend fun load(mode: ReadingMode, location: ReadingLocation, language: String): ReaderDocument {
                    fixture.requests += mode to location
                    beforeLoad(mode)
                    if (fixture.failNext) { fixture.failNext = false; error("Synthetic load failure") }
                    val content = if (fixture.emptyNext) section.copy(paragraphs = emptyList()) else section
                    fixture.emptyNext = false
                    val position = BookPosition(outline,
                        outline.entries.indexOfFirst { location.position in it.node.record..it.node.end })
                    val resource = BookResource(content, position, 0, 1, location.position)
                    return ReaderDocument(content, FolioText.document(content.paragraphs, language, ReaderPreferences(),
                        bookMode = true, scrollingBook = mode == ReadingMode.VERSE), position, resource)
                }
                ReaderController(scope, initialMode = initialMode,
                    loadDocument = { location, language -> load(ReadingMode.VERSE, location, language) },
                    loadBookDocument = { location, language -> load(ReadingMode.BOOK, location, language) },
                    onChange = { fixture.state = it; fixture.changes += it })
            }
            DisposableEffect(controller) {
                fixture.controller = controller
                controller.open(LIBRARY, "en", selection)
                onDispose { controller.close() }
            }
            VedaPreviewTheme {
                val state = fixture.state
                if (fixture.mounted && state != null) ReviewedReaderScreen(state,
                    onBack = { fixture.backCalls++; controller.close() },
                    onNext = { fixture.navigationCalls++; controller.next() },
                    onPrevious = { fixture.navigationCalls++; controller.previous() },
                    onRetry = { fixture.retryCalls++; controller.retry() },
                    onScroll = controller::saveScroll,
                    onBookAnchor = { fixture.anchors += it; controller.saveBookAnchor(it) },
                    onBookEntry = { fixture.entrySelections += it; controller.jumpToBookEntry(it) },
                    onToggleMode = { fixture.toggleCalls++; controller.toggleMode() })
                else Text("Outside reader", Modifier.testTag("outside-reader"))
            }
        }
        awaitBars(visible = true)
        return fixture
    }

    private fun selectMode(fixture: Fixture, mode: ReadingMode) {
        if (compose.runOnIdle { fixture.controller.state?.mode != mode }) {
            compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled().performClick()
        }
    }

    private fun awaitReady(fixture: Fixture, mode: ReadingMode): OfflineReaderView {
        awaitState {
            val state = fixture.controller.state
            assertFalse("Synthetic resource must pass controller validation", state?.failed == true)
            val view = readerHost()?.settledView
            state?.mode == mode && !state.loading && view != null && view.isAttachedToWindow &&
                view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                view.title == "Reader" && view.url?.matches(Regex("https://reader\\.local/document/[1-9][0-9]*")) == true &&
                if (mode == ReadingMode.BOOK) view.paginatedBook && (view.bookPageInfo?.count ?: 0) > 1
                else view.scrollingBook && view.scrollRange > 0
        }
        val view = compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
        awaitPosition(fixture, view)
        return view
    }

    private fun enterFullscreen(fixture: Fixture, before: Snapshot) {
        assertNormalChrome()
        compose.onNodeWithTag("reader-fullscreen").performClick()
        awaitBars(visible = false)
        assertFullscreenChrome()
        awaitState { before.view.height > before.height && before.view.viewportHeight > before.extent }
        val paged = compose.runOnIdle { before.view.paginatedBook }
        // Preserve logical reading intent, not an earlier tail that happened to share its
        // previous column. Actual first-visible DOM/native/controller agreement is still checked.
        awaitPosition(fixture, before.view, before.reflowAnchor.takeIf { paged }, before.offset.takeUnless { paged })
        assertCutoutSafe(before.view)
        assertRetained(fixture, before)
    }

    private fun assertNormalChrome(ready: Boolean = true) {
        // Insets visibility is reported before the system-bar resize animation has placed
        // the restored bottom row. Wait for actual displayed geometry, not just bar flags.
        compose.waitUntil(timeoutMillis = 10_000) {
            chrome.all { tag ->
                try { compose.onNodeWithTag(tag).assertIsDisplayed(); true }
                catch (_: AssertionError) { false }
            }
        }
        chrome.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("reader-fullscreen-exit").assertDoesNotExist()
        val button = compose.onNodeWithTag("reader-fullscreen")
            .assertContentDescriptionEquals(label(R.string.reader_enter_fullscreen))
        if (ready) button.assertIsEnabled() else button.assertIsNotEnabled()
        val settings = compose.onNodeWithTag("reader-text-size")
            .assertContentDescriptionEquals(label(R.string.reader_settings))
        if (ready) settings.assertIsEnabled() else settings.assertIsNotEnabled()
        if (ready) {
            compose.onNodeWithTag("reader-loading").assertDoesNotExist()
            compose.onNodeWithTag("reader-page-loading").assertDoesNotExist()
        }
        assertExitAction(present = false)
    }

    private fun assertFullscreenChrome(controlsVisible: Boolean = false) {
        chrome.forEach { compose.onNodeWithTag(it, useUnmergedTree = true).assertDoesNotExist() }
        compose.onNodeWithTag("reader-book-contents-list").assertDoesNotExist()
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        val exit = compose.onNodeWithTag("reader-fullscreen-exit")
        if (controlsVisible) exit.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertTextEquals(label(R.string.reader_exit_fullscreen))
        else exit.assertDoesNotExist()
        assertExitAction(present = true)
    }

    private fun assertLoadingCopy(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals("Hare Krishna!", label(R.string.book_loading_greeting))
        assertEquals("Your eBook is loading", label(R.string.book_loading_body))
        compose.onNodeWithText(label(R.string.book_loading_greeting), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.book_loading_body), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.reader_loading)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_preparing_pages)).assertDoesNotExist()
    }

    private fun assertNormalFullscreenButtonBelowBody() {
        val body = compose.onNodeWithTag("reader-document").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("reader-fullscreen").fetchSemanticsNode().boundsInRoot
        val mode = compose.onNodeWithTag("reader-mode-toggle").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("reader-book-progress").fetchSemanticsNode().boundsInRoot
        assertTrue("Normal fullscreen button belongs below the body, not in the header", button.top >= body.bottom - 1f)
        assertTrue("The mode button remains above the body", mode.bottom <= body.top + 1f)
        assertTrue(button.top > mode.bottom)
        assertTrue("Progress is below the native body", progress.top >= body.bottom - 1f)
        for (tag in listOf("reader-page-previous", "reader-book-contents", "reader-text-size",
            "reader-fullscreen", "reader-page-next")) {
            val action = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals("Progress shares the $tag action row", progress.center.y, action.center.y, 1f)
            assertEquals("$tag belongs to the fullscreen action row", button.center.y, action.center.y, 1f)
        }
    }

    private fun assertExitAction(present: Boolean) {
        val actions = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(if (present) 1 else 0, actions.count { it.label == label(R.string.reader_exit_fullscreen) })
    }

    private fun exitAccessibly() {
        val action = compose.onNodeWithTag("reader-document").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label(R.string.reader_exit_fullscreen) }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    private fun assertErrorRestored(policy: WindowPolicy) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label(R.string.reader_error)).fetchSemanticsNodes().isNotEmpty()
        }
        awaitBars(visible = true)
        compose.onNodeWithText(label(R.string.reader_error)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.try_again)).assertIsEnabled()
        // A load error has no BookPosition, but still has the footer's fullscreen button.
        listOf("reader-title", "reader-back", "reader-mode-toggle", "reader-fullscreen").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("reader-fullscreen").assertIsNotEnabled()
        compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled()
        assertExitAction(present = false)
        compose.runOnIdle { assertEquals(policy, windowPolicy()) }
    }

    private fun awaitBars(visible: Boolean) {
        awaitState {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == visible &&
                (visible || (insets.getInsets(WindowInsetsCompat.Type.statusBars()) == Insets.NONE &&
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()) == Insets.NONE))
        }
    }

    private fun assertCutoutSafe(view: OfflineReaderView) = compose.runOnIdle {
        val decor = compose.activity.window.decorView
        val cutout = requireNotNull(ViewCompat.getRootWindowInsets(decor)).displayCutout
        val window = bounds(decor)
        val actual = bounds(view)
        val breathingRoom = (16 * compose.density.density).roundToInt()
        assertTrue("Native reader $actual must stay inside safe display-cutout bounds of $window",
            actual.left >= window.left + (cutout?.safeInsetLeft ?: 0) - 1 &&
                actual.right <= window.right - (cutout?.safeInsetRight ?: 0) + 1 &&
                actual.top >= window.top && actual.bottom <= window.bottom)
        // Real native bounds in screen coordinates, not CSS margins or system-bar flags.
        assertEquals("Exactly 16dp above the WebView beyond the top cutout", breathingRoom.toFloat(),
            (actual.top - window.top - (cutout?.safeInsetTop ?: 0)).toFloat(), 1f)
        assertEquals("Exactly 16dp below the WebView beyond the bottom cutout", breathingRoom.toFloat(),
            (window.bottom - (cutout?.safeInsetBottom ?: 0) - actual.bottom).toFloat(), 1f)
        assertEquals("Host and native document use the same padded viewport", bounds(requireNotNull(readerHost())), actual)
    }

    /** Host.isBusy does NOT describe automatic native resize reflow. Wait for painted DOM,
     * native offsets and the controller's acknowledged source anchor to agree instead.
     * getClientRects + visualViewport offsets handle split paragraphs/Android viewport panning.
     * Never force refreshBookLayout here: that would conceal a missing automatic reflow.
     */
    private fun awaitPosition(fixture: Fixture, view: OfflineReaderView, visibleRecord: Int? = null, offset: Int? = null) {
        fence(view)
        var previous: String? = null
        var diagnostic = "No sample"
        try { compose.waitUntil(20_000) {
            val layout = measure(view, visibleRecord ?: 0)
            val agrees = compose.runOnIdle {
                val state = fixture.controller.state
                val anchor = view.bookPageInfo?.anchorRecord ?: view.readingAnchor
                val scale = view.nativeContentScale
                val info = view.bookPageInfo
                diagnostic = "native=${view.width}x${view.height} scale=$scale scroll=${view.scrollX},${view.scrollY} " +
                    "anchor=$anchor vm=${state?.location?.position} vmScroll=${state?.scrollY} settled=${readerHost()?.settledView === view} " +
                    "wantedRecord=$visibleRecord wantedOffset=$offset DOM=$layout"
                readerHost()?.settledView === view && layout.getString("fonts") == "loaded" &&
                    // CSS viewport floats can land a few millionths above an exact pixel;
                    // compare in native integer pixels, retaining the two-pixel allowance.
                    abs(view.height - (layout.getDouble("height") * scale).roundToInt()) <= 2 &&
                    abs(view.scrollX - layout.getDouble("x") * scale) <= 2 &&
                    abs(view.scrollY - layout.getDouble("y") * scale) <= 2 &&
                    layout.getInt("first") == anchor && state?.location?.position == anchor &&
                    paragraphs.any { it.record == anchor } && layout.getBoolean("visible") &&
                    (offset == null || abs(view.scrollY - offset.coerceIn(0, view.scrollRange)) <= 2) &&
                    if (view.paginatedBook) info != null && info.viewportWidth > 0 &&
                        abs(view.scrollX - info.nativeOffset) <= 1 && view.scrollY == 0
                    else view.scrollX == 0 && state?.scrollY == view.scrollY
            }
            // Require successive observations, not a transient match during an inset/reflow.
            val now = layout.toString()
            val stable = agrees && previous == now
            previous = now.takeIf { agrees }
            stable
        } } catch (error: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError(diagnostic, error) }
    }

    private fun measure(view: OfflineReaderView, record: Int): JSONObject {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertFalse(view.isReleased)
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript("""
                (function(){var v=window.visualViewport,w=v?v.width:innerWidth,h=v?v.height:innerHeight,
                    ox=v?v.offsetLeft:0,oy=v?v.offsetTop:0;
                    function visible(e){return Array.from(e.getClientRects()).some(function(r){return r.width>0&&r.height>0&&
                        r.right-ox>.5&&r.left-ox<w-.5&&r.bottom-oy>.5&&r.top-oy<h-.5;});}
                    var records=Array.from(document.querySelectorAll('main.book-flow .record[data-record]'));
                    var first=records.find(visible),target=document.getElementById('record-$record');
                    return {fonts:document.fonts.status,width:w,height:h,x:v?v.pageLeft:scrollX,y:v?v.pageTop:scrollY,
                        first:first?Number(first.dataset.record):0,visible:$record===0||!!(target&&visible(target))};})()
            """.trimIndent()) { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        return JSONObject(requireNotNull(result.get()))
    }

    /** Locate an actual text fragment safely above the bottom exit overlay in either layout.
     * Read-only DOM inspection handles split columns/visualViewport panning; no injected handlers.
     */
    private fun tapVisibleProse(view: OfflineReaderView) {
        val point = JSONObject(evaluate(view, """
            (function(){var v=visualViewport,w=v?v.width:innerWidth,h=v?v.height:innerHeight,
                ox=v?v.offsetLeft:0,oy=v?v.offsetTop:0;
                var blocks=document.querySelectorAll('.record[data-record] .folio-block');
                for(var b=0;b<blocks.length;b++){
                    var e=blocks[b];if(e.textContent.indexOf('Synthetic purport ')!==0)continue;
                    var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),t;
                    while(t=walker.nextNode())for(var i=0;i+8<=t.length;i+=8){
                        var range=document.createRange();range.setStart(t,i);range.setEnd(t,i+8);
                        var boxes=range.getClientRects();
                        for(var j=0;j<boxes.length;j++){var r=boxes[j];
                            if(r.width>0&&r.height>0&&r.left-ox>12&&r.right-ox<w-12&&
                                r.top-oy>32&&r.bottom-oy<h*.65)
                                return {x:(r.left+r.width/2-ox)/w,y:(r.top+r.height/2-oy)/h,
                                    record:Number(e.closest('.record').dataset.record)};
                        }
                    }
                }return {error:'No visible synthetic prose fragment'};})()
        """.trimIndent()))
        assertFalse(point.toString(), point.has("error"))
        assertTrue(point.getInt("record") in 109..160)
        val x = point.getDouble("x").toFloat()
        val y = point.getDouble("y").toFloat()
        assertTrue(x in 0f..1f && y in 0f..1f)
        val query = compose.runOnIdle {
            val host = requireNotNull(readerHost())
            assertEquals(host.width, view.width)
            assertEquals(host.height, view.height)
            BookPagination.hitTest((x * view.width).roundToInt(), (y * view.height).roundToInt(), view.width, view.height)
        }
        assertEquals("Hit must be non-interactive prose, not a link, selection or summary", "0", evaluate(view, query))
        compose.onNodeWithTag("reader-document").performTouchInput { click(Offset(width * x, height * y)) }
    }

    private fun evaluate(view: OfflineReaderView, script: String): String {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertFalse(view.isReleased)
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript(script) { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        return requireNotNull(result.get())
    }

    /** Exclude the touch/ripple perimeter, but include real antialiased text/icon pixels. */
    private fun controlPixels(tag: String): IntArray {
        val image = compose.onNodeWithTag(tag).captureToImage()
        val width = image.width / 2
        val height = image.height / 2
        return IntArray(width * height).also { image.readPixels(it,
            startX = image.width / 4, startY = image.height / 4, width = width, height = height) }
    }

    private fun closeColor(actual: Int, expected: Int): Boolean = listOf(24, 16, 8, 0).all { shift ->
        abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 12
    }

    private fun viewport(view: OfflineReaderView) = compose.runOnIdle {
        Viewport(bounds(view), view.viewportHeight, view.contentHeight, view.scrollX, view.scrollY, view.bookPageInfo)
    }

    private fun fence(view: OfflineReaderView) {
        val painted = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.set(true) }
            })
            view.invalidate()
        }
        compose.waitUntil(10_000) { painted.get() }
    }

    private fun capture(fixture: Fixture, view: OfflineReaderView) = compose.runOnIdle {
        Snapshot(requireNotNull(readerHost()), view, requireNotNull(fixture.controller.state?.document),
            requireNotNull(view.url), view.height, view.viewportHeight, view.scrollY,
            view.bookPageInfo?.anchorRecord ?: view.readingAnchor, fixture.requests.size, windowPolicy(), view.reflowReadingRecord)
    }

    private fun assertRetained(fixture: Fixture, before: Snapshot) = compose.runOnIdle {
        val state = requireNotNull(fixture.controller.state)
        assertSame(before.host, readerHost())
        assertSame(before.view, before.host.settledView)
        assertSame(before.document, state.document)
        assertSame(before.document.bookResource, state.document?.bookResource)
        assertEquals("No native document reload on resize", before.url, before.view.url)
        assertEquals(before.requests, fixture.requests.size)
        assertEquals(1, before.host.childCount)
        assertFalse(before.view.isReleased)
        assertEquals(book.record, state.location.start)
        assertEquals(book.end, state.location.end)
        val source = outline.entries.single { state.location.position in it.node.record..it.node.end }.source
        assertEquals(source.displayTitle, state.location.title)
        assertTrue(fixture.anchors.isNotEmpty())
        assertTrue(fixture.anchors.all { id -> paragraphs.any { it.record == id } })
        assertTrue(fixture.requests.all { it.second.libraryId == LIBRARY })
        assertTrue(fixture.changes.filterNotNull().all { it.location.libraryId == LIBRARY })
        assertEquals(0, fixture.navigationCalls)
    }

    @Suppress("DEPRECATION") // Also detect leaked legacy immersive flags on API 26–29.
    private fun windowPolicy(): WindowPolicy {
        val window = compose.activity.window
        return WindowPolicy(WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior,
            window.attributes.flags and (WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN),
            window.decorView.systemUiVisibility and (View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY))
    }

    private fun bounds(view: View): Rect {
        val xy = IntArray(2)
        view.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }

    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(20_000) { compose.runOnIdle(condition) }
    }

    private fun pressBack() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun label(id: Int) = compose.activity.getString(id)

    private fun readerHost(view: View = compose.activity.window.decorView): ReaderPageHost? = when (view) {
        is ReaderPageHost -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { readerHost(view.getChildAt(it)) }
        else -> null
    }

    private data class WindowPolicy(val behavior: Int, val windowFlags: Int, val immersiveFlags: Int)
    private data class Viewport(val bounds: Rect, val extent: Int, val contentHeight: Int,
        val scrollX: Int, val scrollY: Int, val page: BookPageInfo?)
    private data class Snapshot(val host: ReaderPageHost, val view: OfflineReaderView, val document: ReaderDocument,
        val url: String, val height: Int, val extent: Int, val offset: Int, val anchor: Int,
        val requests: Int, val window: WindowPolicy, val reflowAnchor: Int)

    private class Fixture {
        lateinit var controller: ReaderController
        var mounted by mutableStateOf(true)
        var state by mutableStateOf<ReaderState?>(null)
        val requests = mutableListOf<Pair<ReadingMode, ReadingLocation>>()
        val changes = mutableListOf<ReaderState?>()
        val anchors = mutableListOf<Int>()
        val entrySelections = mutableListOf<Int>()
        var failNext = false
        var emptyNext = false
        var backCalls = 0
        var toggleCalls = 0
        var retryCalls = 0
        var navigationCalls = 0
    }

    private class MainFrame(private val address: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private companion object { const val LIBRARY = "synthetic-fullscreen" }
}