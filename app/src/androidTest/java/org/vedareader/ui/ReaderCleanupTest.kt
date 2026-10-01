package org.vedareader.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Small in-memory resources through the real reviewed screen/controller/native host.
 * No corpus, preference writes, injected layout CSS, fake page status or callback replacement.
 */
@RunWith(AndroidJUnit4::class)
class ReaderCleanupTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = ContentNode(100, 0, "Synthetic cleanup work", 399, 3, 1)
    private val verses = (0..2).map { index ->
        ContentNode(100 + index * 100, book.record, "Bg 2.${7 + index}", 199 + index * 100, 0, 2)
    }
    private val outline = BookOutline(book, verses.map { BookEntry(it, it, ReadingUnitKind.VERSE, book.title) })
    // Begin with a short BG 2.7 tail, then a fully visible BG 2.8 heading on either axis.
    // One long paragraph gives genuine column stepping without hundreds of fixture records.
    private val firstParagraphs = listOf(
        Paragraph(101, "Synthetic BG 2.7 tail.<CR>Original continuation.", "PA_Purport", ""),
        Paragraph(200, "Bg 2.8", "PA_Verse_Number_Heading", "LE_Verse_Number"),
        Paragraph(201, (1..120).joinToString("<CR>") { "Synthetic purport line $it." }, "PA_Purport", ""),
        Paragraph(299, "Synthetic end of first resource.", "PA_Purport", ""),
    )
    private val secondParagraphs = listOf(
        Paragraph(300, "Bg 2.9", "PA_Verse_Number_Heading", "LE_Verse_Number"),
        Paragraph(301, "Synthetic next resource.", "PA_Translation", ""),
        Paragraph(399, "Synthetic final marker.", "PA_Purport", ""),
    )

    @Test
    fun visibleBg28HeaderKeepsBg27TailAsBookmarkInBothActualReadingModes() {
        val fixture = showReader(ReadingMode.BOOK)
        for ((index, mode) in listOf(ReadingMode.BOOK, ReadingMode.VERSE).withIndex()) {
            if (index > 0) compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled().performClick()
            val view = awaitReady(fixture, mode)
            val geometry = recordGeometry(view, 200)
            assertTrue("BG 2.7 must really be painted", recordGeometry(view, 101).getBoolean("visible"))
            assertTrue("BG 2.8 must be fully painted, not merely present in the DOM", geometry.getBoolean("full"))
            assertEquals("TEXT 8 ( BG 2.8 )", geometry.getString("label"))
            assertEquals(0, geometry.getInt("hiddenRects"))
            compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals("BG 2.8")
            compose.runOnIdle {
                val state = requireNotNull(fixture.state)
                assertEquals(mode == ReadingMode.BOOK, view.paginatedBook)
                assertEquals(mode == ReadingMode.VERSE, view.scrollingBook)
                assertEquals(200, view.readingReference)
                assertEquals(101, view.bookPageInfo?.anchorRecord ?: view.readingAnchor)
                assertEquals("Display reference must never replace the saved paragraph", 101, state.location.position)
                assertEquals("BG 2.7", state.location.title)
                assertEquals(verses.first(), outline.entries.single {
                    state.location.position in it.node.record..it.node.end
                }.source)
                assertEquals(101, fixture.anchors.last())
                assertTrue(fixture.anchors.all { it == 101 })
                assertEquals(index + 1, fixture.requests.size)
                assertTrue("Mode reload uses the bookmark, not the displayed heading",
                    fixture.requests.all { it.second.position == 101 })
                assertEquals(0, fixture.nextCalls + fixture.previousCalls)
                assertEquals(1, requireNotNull(readerHost()).childCount)
            }
        }
    }

    @Test
    fun cornerArrowsAreFull48dpStepRealBookPagesAndOnlyLoadSourcesAtBoundaries() {
        val fixture = showReader(ReadingMode.BOOK)
        val view = awaitReady(fixture, ReadingMode.BOOK)
        val document = compose.runOnIdle { requireNotNull(fixture.state?.document) }
        val initialPage = compose.runOnIdle { requireNotNull(view.bookPageInfo) }
        assertEquals(0, initialPage.page)
        assertTrue("Exercise actual in-resource pages", initialPage.count > 2)
        assertFooterGeometry()
        compose.onNodeWithTag(PREVIOUS).assertIsNotEnabled()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_previous_page))
        compose.onNodeWithTag(NEXT).assertIsEnabled()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_next_page))
        val disabledPrevious = controlPixels(PREVIOUS)
        compose.onNodeWithTag(PREVIOUS).performTouchInput { click() }
        assertRetained(fixture, view, document, page = 0)

        step(fixture, view, document, NEXT, page = 1)
        compose.onNodeWithTag(PREVIOUS).assertIsEnabled()
        assertArrayEquals("Enabling the previous arrow must not change its ink", disabledPrevious, controlPixels(PREVIOUS))
        step(fixture, view, document, PREVIOUS, page = 0)
        compose.onNodeWithTag(PREVIOUS).assertIsNotEnabled()
        for (page in 1 until initialPage.count) step(fixture, view, document, NEXT, page)

        // Only this next click is allowed to leave the current resource. Hold its real loader
        // to verify canNavigate gates both arrows without changing their size or colors.
        val arrows = listOf(PREVIOUS, NEXT)
        val bounds = arrows.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val pixels = arrows.associateWith { controlPixels(it) }
        val gate = CompletableDeferred<Unit>()
        try {
            compose.runOnIdle { fixture.gate = gate }
            compose.onNodeWithTag(NEXT).assertIsEnabled().performClick()
            awaitState { fixture.state?.loading == true && readerHost()?.isBusy == true && fixture.requests.size == 2 }
            // The boundary click starts a native Material ripple which can outlive Compose
            // idleness. Compare resting colors after that feedback, not its transient overlay.
            // Keep the resource loader held throughout; a disabled tint regression still fails.
            compose.waitUntil(5_000) { pixels.getValue(NEXT).contentEquals(controlPixels(NEXT)) }
            for (tag in arrows) {
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsNotEnabled()
                assertEquals(bounds.getValue(tag), compose.onNodeWithTag(tag).getUnclippedBoundsInRoot())
                assertArrayEquals("Disabled $tag keeps its painted color", pixels.getValue(tag), controlPixels(tag))
                compose.onNodeWithTag(tag).performTouchInput { click() }
            }
            compose.runOnIdle {
                assertSame(view, readerHost()?.currentView)
                assertEquals(2, fixture.requests.size)
                assertEquals(1, fixture.nextCalls)
                assertEquals(0, fixture.previousCalls)
                fixture.gate = null
                gate.complete(Unit)
            }
            val nextResource = awaitReady(fixture, ReadingMode.BOOK)
            compose.onNodeWithTag("reader-title").assertTextEquals("BG 2.9")
            assertTrue(recordGeometry(nextResource, 300).getBoolean("full"))
            assertTrue(recordGeometry(nextResource, 399).getBoolean("visible"))
            compose.onNodeWithTag(NEXT).assertIsNotEnabled().performTouchInput { click() }
            compose.onNodeWithTag(PREVIOUS).assertIsEnabled().performClick()
            val returned = awaitReady(fixture, ReadingMode.BOOK)
            assertTrue(recordGeometry(returned, 299).getBoolean("visible"))
            assertFooterGeometry()
            compose.runOnIdle {
                assertEquals(initialPage.count - 1, requireNotNull(returned.bookPageInfo).page)
                assertEquals(100, fixture.state?.location?.start)
                assertEquals(299, fixture.state?.location?.end)
                assertEquals(listOf(101, 300, 299), fixture.requests.map { it.second.position })
                assertEquals(1, fixture.nextCalls)
                assertEquals(1, fixture.previousCalls)
                assertTrue(view.isReleased)
                assertTrue(nextResource.isReleased)
            }
        } finally {
            gate.cancel()
        }
    }

    @Test
    fun slowModeChangesShowLoadingWithoutMovingOrReplacingTheRetainedPageEarly() {
        val fixture = showReader(ReadingMode.VERSE)
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE)) {
            val before = awaitReady(fixture, if (mode == ReadingMode.BOOK) ReadingMode.VERSE else ReadingMode.BOOK)
            val bounds = compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot()
            val gate = CompletableDeferred<Unit>()
            try {
                compose.runOnIdle { fixture.gate = gate }
                compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("reader-page-loading").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("reader-page-loading").assertIsDisplayed()
                compose.onNodeWithText(compose.activity.getString(R.string.book_loading_greeting),
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(compose.activity.getString(R.string.book_loading_body),
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(compose.activity.getString(R.string.reader_preparing_pages)).assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(R.string.reader_loading)).assertDoesNotExist()
                compose.onNodeWithTag("reader-book-contents").assertIsNotEnabled()
                compose.onNodeWithTag("reader-mode-toggle").assertIsNotEnabled()
                compose.onNodeWithTag("reader-text-size").assertIsNotEnabled()
                assertEquals(bounds, compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot())
                compose.runOnIdle {
                    assertSame(before, readerHost()?.currentView)
                    assertFalse(before.isReleased)
                    assertEquals(101, fixture.state?.location?.position)
                    fixture.gate = null
                    gate.complete(Unit)
                }
                awaitReady(fixture, mode)
                compose.onNodeWithTag("reader-page-loading").assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(R.string.book_loading_greeting),
                    useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText(compose.activity.getString(R.string.book_loading_body),
                    useUnmergedTree = true).assertDoesNotExist()
                assertFooterGeometry()
            } finally { gate.cancel() }
        }
    }

    @Test
    fun settingsActionShowsTheTwoLineHareKrishnaPreviewWithoutReloadingSource() {
        val fixture = showReader(ReadingMode.VERSE)
        val view = awaitReady(fixture, ReadingMode.VERSE)
        val document = compose.runOnIdle { requireNotNull(fixture.state?.document) }
        val preview = compose.activity.getString(R.string.reader_text_size_preview)
        assertEquals("Hare Krishna\nहरे कृष्ण", preview)
        compose.onNodeWithTag("reader-text-size").assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_settings)).performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-text-size-dialog").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.reader_settings)).assertIsDisplayed()
        compose.onNodeWithTag("reader-settings-back")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_settings_back)).assertIsEnabled()
        compose.onNodeWithTag("reader-text-size-preview").assertIsDisplayed().assertTextEquals(preview)
        compose.onNodeWithTag("reader-text-size-done").performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(view, readerHost()?.settledView)
            assertSame(document, fixture.state?.document)
            assertEquals(101, fixture.state?.location?.position)
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.nextCalls + fixture.previousCalls)
        }
    }

    private fun showReader(mode: ReadingMode): Fixture {
        val fixture = Fixture()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember(scope) {
                suspend fun load(mode: ReadingMode, location: ReadingLocation, language: String): ReaderDocument {
                    fixture.requests += mode to location
                    fixture.gate?.await()
                    val second = location.position >= 300
                    val node = if (second) verses[2] else book.copy(end = 299, children = 2)
                    val paragraphs = if (second) secondParagraphs else firstParagraphs
                    val section = ReadingSection(node, ReadingUnitKind.CHAPTER, book.title, paragraphs,
                        previous = if (second) book.copy(end = 299, children = 2) else null,
                        next = if (second) null else verses[2])
                    val position = BookPosition(outline, outline.entries.indexOfFirst {
                        location.position in it.node.record..it.node.end
                    })
                    val resource = BookResource(section, position, if (second) 2 else 0, if (second) 2 else 1, location.position)
                    return ReaderDocument(section, FolioText.document(paragraphs, language, ReaderPreferences(),
                        bookMode = true, scrollingBook = mode == ReadingMode.VERSE), position, resource)
                }
                ReaderController(scope, initialMode = mode,
                    loadDocument = { location, language -> load(ReadingMode.VERSE, location, language) },
                    loadBookDocument = { location, language -> load(ReadingMode.BOOK, location, language) },
                    onChange = { fixture.state = it })
            }
            DisposableEffect(controller) {
                controller.open("synthetic-cleanup", "en", verses.first().copy(record = 101))
                onDispose { controller.close() }
            }
            VedaPreviewTheme {
                fixture.state?.let { state -> ReviewedReaderScreen(state,
                    onBack = controller::close, onRetry = controller::retry, onScroll = controller::saveScroll,
                    onNext = { fixture.nextCalls++; controller.next() },
                    onPrevious = { fixture.previousCalls++; controller.previous() },
                    onToggleMode = controller::toggleMode, onBookEntry = controller::jumpToBookEntry,
                    onBookAnchor = { fixture.anchors += it; controller.saveBookAnchor(it) }) }
            }
        }
        return fixture
    }

    private fun awaitReady(fixture: Fixture, mode: ReadingMode): OfflineReaderView {
        awaitState {
            val state = fixture.state
            assertFalse("Synthetic resource must pass validation", state?.failed == true)
            val view = readerHost()?.settledView
            state?.mode == mode && !state.loading && view != null && view.isAttachedToWindow &&
                view.progress == 100 && view.title == "Reader" && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                state.location.position == (view.bookPageInfo?.anchorRecord ?: view.readingAnchor) &&
                fixture.anchors.lastOrNull() == state.location.position &&
                (if (mode == ReadingMode.BOOK) view.paginatedBook && view.bookPageInfo != null else view.scrollingBook)
        }
        val view = compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
        val painted = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.set(true) }
            })
            view.invalidate()
        }
        compose.waitUntil(10_000) { painted.get() }
        return view
    }

    private fun step(fixture: Fixture, view: OfflineReaderView, document: ReaderDocument, tag: String, page: Int) {
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
        awaitState { readerHost()?.settledView === view && view.bookPageInfo?.page == page }
        awaitReady(fixture, ReadingMode.BOOK)
        assertRetained(fixture, view, document, page)
    }

    private fun assertRetained(fixture: Fixture, view: OfflineReaderView, document: ReaderDocument, page: Int) = compose.runOnIdle {
        assertSame(view, readerHost()?.settledView)
        assertSame(document, fixture.state?.document)
        assertEquals(page, requireNotNull(view.bookPageInfo).page)
        assertEquals(requireNotNull(view.bookPageInfo).nativeOffset, view.scrollX)
        assertEquals(0, view.scrollY)
        assertEquals(100, fixture.state?.location?.start)
        assertEquals(299, fixture.state?.location?.end)
        assertEquals(1, fixture.requests.size)
        assertEquals("Page buttons must go through the host, not skip to another source", 0,
            fixture.nextCalls + fixture.previousCalls)
    }

    private fun assertFooterGeometry() {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val previous = compose.onNodeWithTag(PREVIOUS).assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
        val next = compose.onNodeWithTag(NEXT).assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
        for (bounds in listOf(previous, next)) {
            assertEquals(48f, (bounds.right - bounds.left).value, .5f)
            assertEquals(48f, (bounds.bottom - bounds.top).value, .5f)
            assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
        }
        assertEquals(4f, (previous.left - root.left).value, .5f)
        assertEquals(4f, (root.right - next.right).value, .5f)
        assertEquals(previous.top, next.top)
        assertEquals(previous.bottom, next.bottom)
        val progress = compose.onNodeWithTag("reader-book-progress").assertIsDisplayed().getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("reader-document").getUnclippedBoundsInRoot()
        assertTrue(body.bottom <= progress.top)
        assertTrue(progress.top >= previous.top && progress.bottom <= previous.bottom)
        assertEquals((previous.top.value + previous.bottom.value) / 2,
            (progress.top.value + progress.bottom.value) / 2, .5f)
        val contents = compose.onNodeWithTag("reader-book-contents")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_book_contents))
            .getUnclippedBoundsInRoot()
        assertEquals(48f, (contents.right - contents.left).value, .5f)
        assertEquals(48f, (contents.bottom - contents.top).value, .5f)
        compose.onNodeWithText(compose.activity.getString(R.string.reader_book_contents)).assertDoesNotExist()
        val size = compose.onNodeWithTag("reader-text-size")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_settings)).getUnclippedBoundsInRoot()
        assertTrue(progress.left >= contents.right && progress.right <= size.left)
        for (tag in listOf("reader-book-contents", "reader-text-size", "reader-fullscreen")) {
            val action = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(action.left >= previous.right && action.right <= next.left)
            assertTrue(action.top >= previous.top && action.bottom <= previous.bottom)
        }
    }

    /** Inspect actual native text/record fragments independently of the reference calculation. */
    private fun recordGeometry(view: OfflineReaderView, record: Int): JSONObject {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertFalse(view.isReleased)
            view.evaluateJavascript("""
                (function(){var v=visualViewport,w=v?v.width:innerWidth,h=v?v.height:innerHeight,
                    ox=v?v.offsetLeft:0,oy=v?v.offsetTop:0,e=document.getElementById('record-$record');
                    function full(r){return r.width>0&&r.height>0&&r.left-ox>=0&&r.right-ox<=w&&r.top-oy>=0&&r.bottom-oy<=h;}
                    var boxes=Array.from(e.getClientRects()),label=Array.from(e.children).find(function(c){
                        return c.classList.contains('folio-block')&&getComputedStyle(c).display!=='none';});
                    var range=document.createRange();range.selectNodeContents(label);
                    var lines=Array.from(range.getClientRects()).filter(function(r){return r.width>0&&r.height>0;}),
                        hidden=e.querySelector('.source-book-heading');
                    return {label:label.textContent,hiddenRects:hidden?hidden.getClientRects().length:0,
                        full:boxes.length>0&&boxes.every(full)&&lines.length>0&&lines.every(full),
                        visible:boxes.some(function(r){return r.width>0&&r.height>0&&r.right-ox>.5&&
                            r.left-ox<w-.5&&r.bottom-oy>.5&&r.top-oy<h-.5;})};})()
            """.trimIndent()) { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        return JSONObject(requireNotNull(result.get()))
    }

    private fun controlPixels(tag: String): IntArray {
        var pixels = IntArray(0)
        // Settle BOTH baseline and disabled captures. Repeated page turns can leave a native
        // ripple in the enabled baseline too, especially on a fractional-density emulator.
        compose.waitUntil(5_000) {
            val image = compose.onNodeWithTag(tag).captureToImage()
            val width = image.width / 2
            val height = image.height / 2
            pixels = IntArray(width * height).also { image.readPixels(it,
                startX = image.width / 4, startY = image.height / 4, width = width, height = height) }
            listOf(0, width - 1, pixels.size - width, pixels.lastIndex)
                .all { pixels[it] == ReaderPaperColors.Paper.toArgb() }
        }
        return pixels
    }

    private fun awaitState(condition: () -> Boolean) = compose.waitUntil(20_000) { compose.runOnIdle(condition) }

    private fun readerHost(view: View = compose.activity.window.decorView): ReaderPageHost? = when (view) {
        is ReaderPageHost -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { readerHost(view.getChildAt(it)) }
        else -> null
    }

    private class Fixture {
        var state by mutableStateOf<ReaderState?>(null)
        var gate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<Pair<ReadingMode, ReadingLocation>>()
        val anchors = mutableListOf<Int>()
        var nextCalls = 0
        var previousCalls = 0
    }

    private companion object {
        const val PREVIOUS = "reader-page-previous"
        const val NEXT = "reader-page-next"
    }
}