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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Real reviewed screen/controller/WebViews; synthetic resources, no database or preference writes. */
@RunWith(AndroidJUnit4::class)
class ReadingModeToggleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = ContentNode(100, 0, "Synthetic reading resource", 160, 1, 1)
    private val verse = ContentNode(101, 100, "Synthetic verse", 160, 0, 2)
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

    @Test
    fun iconRoundTripChangesCheckedSemanticsAndDrawableWithoutChangingNativeFormatting() {
        val fixture = showReader()
        val scrolling = awaitReady(fixture, ReadingMode.VERSE)
        assertToggle(ReadingMode.VERSE, enabled = true)
        val textIcon = iconPixels()
        val original = format(scrolling)
        assertNativeFormat(original)

        compose.onNodeWithTag("reader-mode-toggle").performClick()
        val paged = awaitReady(fixture, ReadingMode.BOOK)
        assertToggle(ReadingMode.BOOK, enabled = true)
        assertEquals("Switch to scrolling text", compose.activity.getString(R.string.reader_switch_to_verse))
        assertFalse("Book and scrolling text must not draw the same icon", textIcon.contentEquals(iconPixels()))
        val bookFormat = format(paged)
        assertNativeFormat(bookFormat)
        assertEquals(original.getString("html"), bookFormat.getString("html"))
        assertEquals(original.getJSONArray("styles").toString(), bookFormat.getJSONArray("styles").toString())

        compose.onNodeWithTag("reader-mode-toggle").performClick()
        val returned = awaitReady(fixture, ReadingMode.VERSE)
        assertToggle(ReadingMode.VERSE, enabled = true)
        val returnedFormat = format(returned)
        assertNativeFormat(returnedFormat)
        assertEquals(original.getString("html"), returnedFormat.getString("html"))
        assertEquals(original.getJSONArray("styles").toString(), returnedFormat.getJSONArray("styles").toString())
        compose.runOnIdle {
            assertTrue(scrolling.isReleased)
            assertTrue(paged.isReleased)
            assertEquals(1, requireNotNull(readerHost()).childCount)
            assertEquals(2, fixture.toggleCalls)
            assertEquals(listOf(ReadingMode.VERSE, ReadingMode.BOOK, ReadingMode.VERSE), fixture.requests.map { it.first })
        }
    }

    @Test
    fun loadingDisablesTheIconInBothModesUntilTheRealResourceIsReady() {
        val pendingBook = CompletableDeferred<Unit>()
        val pendingText = CompletableDeferred<Unit>()
        val fixture = showReader(initialMode = ReadingMode.BOOK) { mode ->
            if (mode == ReadingMode.BOOK) pendingBook.await() else pendingText.await()
        }
        try {
            awaitState(fixture, ReadingMode.BOOK, loading = true)
            assertToggle(ReadingMode.BOOK, enabled = false)
            compose.onNodeWithTag("reader-mode-toggle").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(0, fixture.toggleCalls)
                assertEquals(1, fixture.requests.size)
                pendingBook.complete(Unit)
            }
            awaitReady(fixture, ReadingMode.BOOK)
            assertToggle(ReadingMode.BOOK, enabled = true)

            compose.onNodeWithTag("reader-mode-toggle").performClick()
            awaitState(fixture, ReadingMode.VERSE, loading = true)
            assertToggle(ReadingMode.VERSE, enabled = false)
            compose.onNodeWithTag("reader-mode-toggle").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(1, fixture.toggleCalls)
                assertEquals(2, fixture.requests.size)
                assertTrue(requireNotNull(readerHost()).isBusy)
                pendingText.complete(Unit)
            }
            awaitReady(fixture, ReadingMode.VERSE)
            assertToggle(ReadingMode.VERSE, enabled = true)
        } finally {
            pendingBook.cancel()
            pendingText.cancel()
        }
    }

    @Test
    fun loadErrorsLeaveBothModeIconsEnabledAndSwitchingCanRecover() {
        var failuresLeft = 2
        val fixture = showReader { if (failuresLeft-- > 0) error("Synthetic load failure") }
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            awaitState(fixture, mode, loading = false, failed = true)
            compose.onNodeWithText(compose.activity.getString(R.string.reader_error)).assertIsDisplayed()
            assertToggle(mode, enabled = true)
            compose.onNodeWithTag("reader-mode-toggle").performClick()
        }
        assertNativeFormat(format(awaitReady(fixture, ReadingMode.VERSE)))
        assertToggle(ReadingMode.VERSE, enabled = true)
        compose.onNodeWithText(compose.activity.getString(R.string.reader_error)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, fixture.toggleCalls)
            assertEquals(3, fixture.requests.size)
        }
    }

    private fun showReader(initialMode: ReadingMode = ReadingMode.VERSE,
        beforeLoad: suspend (ReadingMode) -> Unit = {}): Fixture {
        val fixture = Fixture()
        compose.setContent {
            var state by remember { mutableStateOf<ReaderState?>(null) }
            val scope = rememberCoroutineScope()
            val controller = remember(scope) {
                ReaderController(scope, initialMode = initialMode,
                    loadDocument = { location, language ->
                        fixture.requests += ReadingMode.VERSE to location
                        beforeLoad(ReadingMode.VERSE)
                        document(location, language, scrolling = true)
                    }, loadBookDocument = { location, language ->
                        fixture.requests += ReadingMode.BOOK to location
                        beforeLoad(ReadingMode.BOOK)
                        document(location, language, scrolling = false)
                    }, onChange = { state = it })
            }
            DisposableEffect(controller) {
                fixture.controller = controller
                controller.open("synthetic-toggle", "en", verse)
                onDispose { controller.close() }
            }
            VedaPreviewTheme {
                state?.let { reading -> ReviewedReaderScreen(reading,
                    onBack = controller::close, onNext = controller::next, onPrevious = controller::previous,
                    onRetry = controller::retry, onScroll = controller::saveScroll,
                    onToggleMode = { fixture.toggleCalls++; controller.toggleMode() },
                    onBookAnchor = controller::saveBookAnchor, onBookEntry = controller::jumpToBookEntry)
                }
            }
        }
        return fixture
    }

    private fun document(location: ReadingLocation, language: String, scrolling: Boolean): ReaderDocument {
        val position = BookPosition(outline, outline.entries.indexOfFirst { location.position in it.node.record..it.node.end })
        val resource = BookResource(section, position, 0, 1, location.position)
        return ReaderDocument(section, FolioText.document(paragraphs, language, ReaderPreferences(),
            bookMode = true, scrollingBook = scrolling), position, resource)
    }

    private fun assertToggle(mode: ReadingMode, enabled: Boolean) {
        val bookMode = mode == ReadingMode.BOOK
        val node = compose.onNodeWithTag("reader-mode-toggle")
        node.assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(compose.activity.getString(
                if (bookMode) R.string.reader_switch_to_verse else R.string.reader_switch_to_book))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,
                if (bookMode) ToggleableState.On else ToggleableState.Off))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, compose.activity.getString(
                if (bookMode) R.string.reader_mode_book else R.string.reader_mode_verse)))
        if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
        for (text in listOf("Book", "Verse", compose.activity.getString(R.string.reader_mode_verse))) {
            compose.onNodeWithText(text, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun awaitState(fixture: Fixture, mode: ReadingMode, loading: Boolean, failed: Boolean = false) {
        compose.waitUntil(10_000) { compose.runOnIdle {
            fixture.controller.state?.let { it.mode == mode && it.loading == loading && it.failed == failed } == true
        } }
    }

    private fun awaitReady(fixture: Fixture, mode: ReadingMode): OfflineReaderView {
        compose.waitUntil(20_000) {
            compose.onNodeWithText(compose.activity.getString(R.string.reader_error)).assertDoesNotExist()
            compose.runOnIdle {
                val state = fixture.controller.state
                assertFalse("Synthetic resource must pass controller validation", state?.failed == true)
                val view = readerHost()?.settledView
                state?.mode == mode && !state.loading && view != null && view.isAttachedToWindow &&
                    view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                    view.title == "Reader" && view.url?.matches(Regex("https://reader\\.local/document/[1-9][0-9]*")) == true &&
                    if (mode == ReadingMode.BOOK) view.paginatedBook && (view.bookPageInfo?.count ?: 0) > 1
                    else view.scrollingBook && view.scrollRange > 0
            }
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
        compose.runOnIdle {
            assertEquals(mode == ReadingMode.BOOK, view.paginatedBook)
            assertEquals(mode == ReadingMode.VERSE, view.scrollingBook)
            assertEquals(fixture.requests.last().second.position, requireNotNull(view.initialBookAnchor))
        }
        return view
    }

    /** Read-only test inspection; production enables closed native JS for both resource layouts. */
    private fun format(view: OfflineReaderView): JSONObject {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertTrue(view.settings.javaScriptEnabled)
            view.evaluateJavascript("""
                (function(){var m=document.querySelector('main.book-flow');return {
                    fonts:document.fonts.status,html:m.innerHTML,records:m.querySelectorAll('.record').length,
                    details:m.querySelectorAll('details,summary').length,
                    synonyms:getComputedStyle(document.getElementById('record-104')).display,
                    last:document.getElementById('record-160').textContent,
                    bodiesVisible:Array.from(m.querySelectorAll('.record.synonyms,.record.translation,.record.purport'))
                        .every(function(e){var s=getComputedStyle(e);return s.display!=='none'&&s.visibility==='visible'&&e.getClientRects().length>0;}),
                    styles:[102,103,105,106,107,108,109,160].map(function(id){
                        var e=document.querySelector('#record-'+id+' > .folio-block'),s=getComputedStyle(e);
                        return {id:id,text:e.textContent,family:s.fontFamily,size:s.fontSize,
                            weight:s.fontWeight,style:s.fontStyle,color:s.color,align:s.textAlign};})};})()
            """.trimIndent()) { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        return JSONObject(requireNotNull(result.get()))
    }

    private fun assertNativeFormat(snapshot: JSONObject) {
        assertEquals("loaded", snapshot.getString("fonts"))
        assertEquals(paragraphs.size, snapshot.getInt("records"))
        assertEquals(0, snapshot.getInt("details"))
        assertEquals("none", snapshot.getString("synonyms"))
        assertEquals(paragraphs.last().text, snapshot.getString("last"))
        assertTrue(snapshot.getBoolean("bodiesVisible"))
        val styles = snapshot.getJSONArray("styles")
        val byRecord = (0 until styles.length()).map { styles.getJSONObject(it) }.associateBy { it.getInt("id") }
        assertEquals("Synthetic śloka ā a\u0304", byRecord.getValue(103).getString("text"))
        assertEquals("italic", byRecord.getValue(103).getString("style"))
        assertEquals(byRecord.getValue(109).getString("color"), byRecord.getValue(103).getString("color"))
        assertEquals("700", byRecord.getValue(107).getString("weight"))
        assertEquals("700", byRecord.getValue(106).getString("weight"))
        assertEquals("400", byRecord.getValue(108).getString("weight"))
        assertEquals("normal", byRecord.getValue(108).getString("style"))
        assertTrue(byRecord.getValue(107).getString("family").contains("Fira Sans Condensed"))
    }

    /** Compare the icon centre only, not the toggle's changing touch/ripple boundary. No files saved. */
    private fun iconPixels(): IntArray {
        val image = compose.onNodeWithTag("reader-mode-toggle").captureToImage()
        val width = image.width / 2
        val height = image.height / 2
        return IntArray(width * height).also { image.readPixels(it,
            startX = image.width / 4, startY = image.height / 4, width = width, height = height) }
    }

    private fun readerHost(view: View = compose.activity.window.decorView): ReaderPageHost? = when (view) {
        is ReaderPageHost -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { readerHost(view.getChildAt(it)) }
        else -> null
    }

    private class Fixture {
        lateinit var controller: ReaderController
        val requests = mutableListOf<Pair<ReadingMode, ReadingLocation>>()
        var toggleCalls = 0
    }
}