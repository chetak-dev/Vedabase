package org.vedareader.ui

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ChapterOverview
import org.vedareader.data.ContentNode
import org.vedareader.data.LibraryInfo
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import org.vedareader.data.VersePreview
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real navigation/controllers with synthetic text only; no MainActivity, ViewModel, store, or DB. */
@RunWith(AndroidJUnit4::class)
class ChapterOverviewScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo(
        "synthetic-chapter-screen", "Synthetic chapter library", "en", "synthetic-only.ivd",
        32_768L, 40L, "Synthetic fixture only",
    )
    private val unicode = "नमूना বাংলা — ā ś ṛ — a\u0304"
    private val collection = ContentNode(100, 0, "01. Synthetic collection", 9999, 1, 1, "bgaiiwa")
    private val chapter = ContentNode(200, 100, "Chapter Ω — $unicode", 8999, 7, 2)
    private val first = ContentNode(300, 200, "Synthetic verse 1.1", 309, 4, 3)
    private val second = ContentNode(320, 200, "Synthetic verse 1.2", 329, 4, 3)
    private val combined = ContentNode(340, 200, "Bg 1.16, Bg 1.17, Bg 1.18, Bg 1.16-18", 359, 4, 3)
    private val duplicateA = ContentNode(370, 200, "Shared source title", 379, 4, 3)
    private val duplicateB = ContentNode(390, 200, "Shared source title", 399, 4, 3)
    private val blank = ContentNode(410, 200, "   ", 419, 4, 3)
    private val missing = ContentNode(430, 200, "Synthetic verse without preview", 439, 4, 3)
    private val notes = ContentNode(8000, 200, "Synthetic chapter notes", 8099, 1, 3)
    private val noteLeaf = ContentNode(8001, 8000, "Synthetic note entry", 8009, 0, 4)
    private val previews = listOf(first, second, combined, duplicateA, duplicateB, blank, missing).map { node ->
        VersePreview(node, when (node) {
            missing -> null
            first -> "Synthetic full translation begins: $unicode.\n\n" +
                "This original test paragraph has enough words to wrap across several lines. " +
                "Its ending must remain visible rather than become a shortened excerpt.\n\n" +
                "Synthetic final translation marker: $unicode."
            else -> "Synthetic translation ${node.record}: $unicode."
        })
    }

    @Test
    fun overviewShowsSourceTitleAndFullUnicodeTranslationsWithoutReadButtonOrSubsectionCounts() {
        val fixture = showChapter()
        assertChapterChrome(fixture)

        for (expected in previews.filter { it.translation != null }) {
            // Exact merged text also rejects a per-verse subsection count or another action label.
            preview(fixture, expected.node).assertIsDisplayed()
                .assertTextEquals(reference(expected.node), requireNotNull(expected.translation))
                .assertContentDescriptionEquals(label(R.string.chapter_read_verse, displayTitle(expected.node)))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("verse-translation-${expected.node.record}", useUnmergedTree = true)
                .assertTextEquals(requireNotNull(expected.translation))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    assertTrue(action(layouts))
                }
            val layout = layouts.single()
            assertEquals(expected.translation, layout.layoutInput.text.text)
            assertFalse("The full translation must not be clipped or ellipsized", layout.hasVisualOverflow)
            if (expected.node == first) {
                assertEquals(3, layout.layoutInput.text.text.split("\n\n").size)
                assertTrue("The fixture must exceed a two-line excerpt", layout.lineCount > 2)
            }
            compose.onNodeWithTag("section-${expected.node.record}").assertDoesNotExist()
            compose.onNodeWithTag("read-section").assertDoesNotExist()
        }
        assertChapterChrome(fixture)
        assertChapterPath(fixture)
        compose.runOnIdle {
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun openingShortChapterShowsTheFixedHeaderAndWholeFirstTranslationWithoutScrolling() {
        val translation = "Synthetic short translation: $unicode."
        val fixture = showChapter(versePreviews = listOf(VersePreview(first, translation)))

        // Do not use preview()/scrollTo(): initial visibility is the behavior under test.
        assertChapterChrome(fixture)
        assertEquals(0f, scrollPosition(browseList(fixture.chapter)), 0f)
        val row = compose.onNodeWithTag("verse-preview-${first.record}")
            .assertIsDisplayed().assertIsEnabled().assertTextEquals(reference(first), translation)
        val text = compose.onNodeWithTag("verse-translation-${first.record}", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals(translation)
        val viewport = compose.onNodeWithTag(browseList(fixture.chapter)).getUnclippedBoundsInRoot()
        // Compare measured bounds, not device-specific pixels; clipped semantics bounds could
        // make a partly visible row look as though it fits inside the viewport.
        for (node in listOf(row, text)) {
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("The complete first row and translation must fit in the initial viewport",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                    bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layouts))
        }
        assertEquals(translation, layouts.single().layoutInput.text.text)
        assertFalse("The short translation must not be clipped or ellipsized", layouts.single().hasVisualOverflow)
        assertChapterPath(fixture)
        compose.runOnIdle {
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun compactVerseRowsAreAdjacentWithTheReferenceAboveAFullWidthTranslationAndAFullRowTapTarget() {
        val verses = listOf(VersePreview(first, "First original translation."),
            VersePreview(second, "Second original translation."))
        val fixture = showChapter(versePreviews = verses)
        assertChapterChrome(fixture)
        val firstCard = compose.onNodeWithTag("verse-preview-${first.record}")
            .assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(label(R.string.chapter_read_verse, first.displayTitle))
        val secondCard = compose.onNodeWithTag("verse-preview-${second.record}").assertIsDisplayed()
        val firstBounds = firstCard.getUnclippedBoundsInRoot()
        val secondBounds = secondCard.getUnclippedBoundsInRoot()
        val translation = compose.onNodeWithTag("verse-translation-${first.record}", useUnmergedTree = true)
            .assertTextEquals(requireNotNull(verses.first().translation)).getUnclippedBoundsInRoot()
        val reference = compose.onNode(hasText(first.displayTitle) and hasAnyAncestor(hasTestTag("verse-preview-${first.record}")),
            useUnmergedTree = true).getUnclippedBoundsInRoot()
        // Rows are separated only by a hairline divider, so a page shows many verses.
        assertEquals(1f, (secondBounds.top - firstBounds.bottom).value, 1f)
        assertEquals(firstBounds.left, secondBounds.left)
        assertEquals(firstBounds.right, secondBounds.right)
        assertTrue("Reference sits above the translation", reference.bottom <= translation.top)
        assertEquals("Translation uses the full row width", 4f, (translation.left - firstBounds.left).value, .5f)
        assertEquals(4f, (firstBounds.right - translation.right).value, .5f)
        assertEquals(10f, (firstBounds.bottom - translation.bottom).value, .5f)
        // Tap the empty left padding rather than text: the complete row is actionable.
        firstCard.performTouchInput { click(Offset(4f, height / 2f)) }
        assertReader(fixture, first)
        compose.runOnIdle { assertEquals(listOf(first.location(library.id) to library.languageTag), fixture.requests) }
    }

    @Test
    fun chapterOverviewNeverGroupsVerseRowsEvenIfIntroductionMetadataIsSupplied() {
        val verses = listOf(VersePreview(first, "First synthetic translation"), VersePreview(second, "Second synthetic translation"))
        compose.setContent {
            VedaPreviewTheme {
                LibraryScreen(LibraryUiState(
                    loading = false, libraries = listOf(library), active = library,
                    browser = BrowseState(
                        libraryId = library.id, path = listOf(collection, chapter), entries = verses.map { it.node },
                        chapter = ChapterOverview(chapter, verses), introductionRecords = setOf(first.record, second.record),
                    ),
                ))
            }
        }
        compose.onNodeWithTag("introduction-group").assertDoesNotExist()
        for (verse in verses) {
            scrollTo(browseList(chapter), hasTestTag("verse-preview-${verse.node.record}"))
                .assertIsDisplayed().assertTextEquals(reference(verse.node), requireNotNull(verse.translation))
        }
    }

    @Test
    fun readingPaletteKeepsTheSaffronHeaderCreamPaperAndWarmChapterBackground() {
        assertEquals(0xFFB34E15.toInt(), ReadingColors.Header.toArgb())
        assertEquals(0xFFFFF9EF.toInt(), ReadingColors.Paper.toArgb())
        assertEquals(0xFFFBEEDC.toInt(), ReadingColors.Chapter.toArgb())
    }

    @Test
    fun previewIdImmediatelyOpensTheExactFullReaderWithoutPushingAVerseOrItsSubsections() {
        val response = CompletableDeferred<Unit>()
        val fixture = showChapter(readerGate = response)
        try {
            // Not the first row: a title/index-based or hard-coded selection must fail this test.
            preview(fixture, second).assertIsEnabled().performClick()

            waitFor(hasTestTag("reader-title") and hasText(second.displayTitle))
            compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(second.displayTitle)
            compose.onNodeWithText(label(R.string.reader_loading)).assertIsDisplayed()
            compose.onNodeWithTag(browseList(fixture.chapter)).assertDoesNotExist()
            compose.onNodeWithTag(browseList(second)).assertDoesNotExist()
            compose.onNodeWithTag("read-section").assertDoesNotExist()
            compose.onNodeWithTag("reader-document").assertIsDisplayed()
            assertChapterPath(fixture)
            compose.runOnIdle {
                val host = requireNotNull(readerHost())
                assertTrue(host.isBusy)
                assertNull(host.currentView)
                assertNull(host.settledView)
                assertEquals(second.location(library.id), fixture.controller.state?.location)
                assertEquals(listOf(second.location(library.id) to library.languageTag), fixture.requests)
                response.complete(Unit)
            }

            assertReader(fixture, second)
            // Native find verifies real rendered body, including the end of the complete unit,
            // not merely a WebView/progress=100 or the chapter's translation preview.
            val document = fixture.documents.getValue(second.record)
            assertDocumentText(document.section.paragraphs.first().text)
            assertDocumentText(document.section.paragraphs.last().text)
        } finally {
            response.complete(Unit)
        }
    }

    @Test
    fun toolbarAndSystemBackRestoreTheSameChapterAndScrolledVerseWithoutReloading() {
        val longPreviews = List(60) { index ->
            val record = 1000 + index * 20
            val node = ContentNode(record, chapter.record, "Synthetic verse ${index + 1}", record + 9, 4, 3)
            VersePreview(node, "Synthetic translation ${index + 1}: $unicode.")
        }
        val fixture = showChapter(versePreviews = longPreviews)
        val selected = longPreviews[45].node
        val list = browseList(fixture.chapter)
        val tag = "verse-preview-${selected.record}"
        assertChapterChrome(fixture)
        val headerBounds = compose.onNodeWithTag("browse-title").getUnclippedBoundsInRoot()
        preview(fixture, selected).assertIsDisplayed()
        assertChapterChrome(fixture)
        assertEquals("The chapter title must stay fixed while translations scroll",
            headerBounds, compose.onNodeWithTag("browse-title").getUnclippedBoundsInRoot())
        val position = scrollPosition(list)
        val top = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("The fixture must scroll well beyond the chapter heading", position > 0f)

        for ((index, systemBack) in listOf(false, true).withIndex()) {
            // Both round trips start with an actual row click, not a controller callback.
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            assertReader(fixture, selected)
            if (systemBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("reader-back").performClick()

            waitForBrowse(fixture, fixture.chapter)
            // Never scroll after returning: doing so would conceal lost saved state.
            assertChapterChrome(fixture)
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertEquals(position, scrollPosition(list), 0.001f)
            assertEquals(top, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 0.5f)
            compose.onNodeWithTag("reader-title").assertDoesNotExist()
            compose.onNodeWithTag("reader-document").assertDoesNotExist()
            assertChapterPath(fixture)
            compose.runOnIdle {
                assertNull(fixture.controller.state)
                assertEquals(index + 1, fixture.readerBackCalls)
                assertEquals(List(index + 1) { selected.location(library.id) to library.languageTag }, fixture.requests)
                assertFalse(compose.activity.isFinishing)
            }
        }
    }

    @Test
    fun missingTranslationUsesTheHintButTheSameRowStillOpensItsOriginalUnit() {
        val fixture = showChapter()
        preview(fixture, missing).assertIsDisplayed()
            .assertTextEquals(reference(missing), label(R.string.chapter_translation_missing))
        compose.onNodeWithTag("verse-translation-${missing.record}", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("section-${missing.record}").assertDoesNotExist()

        compose.onNodeWithTag("verse-preview-${missing.record}").performClick()

        assertReader(fixture, missing)
        compose.runOnIdle {
            assertNull(requireNotNull(fixture.navigator.state.chapter).verses.single { it.node == missing }.translation)
            assertEquals(listOf(missing.location(library.id) to library.languageTag), fixture.requests)
            assertTrue(requireNotNull(fixture.controller.state?.document).section.paragraphs.isNotEmpty())
        }
    }

    @Test
    fun combinedDuplicateAndBlankTitlesKeepDistinctRecordIdsAndCompleteSourceRanges() {
        val fixture = showChapter()
        val selected = listOf(combined, duplicateA, duplicateB, blank)
        assertEquals("BG 1.16-18", combined.displayTitle)
        assertEquals(duplicateA.title, duplicateB.title)
        for (node in selected) {
            val expected = previews.single { it.node == node }
            preview(fixture, node).assertTextEquals(reference(node), requireNotNull(expected.translation))
                .assertContentDescriptionEquals(label(R.string.chapter_read_verse, displayTitle(node)))
            compose.onAllNodesWithTag("verse-preview-${node.record}").assertCountEquals(1)
            compose.onNodeWithTag("verse-preview-${node.record}").performClick()

            assertReader(fixture, node)
            compose.runOnIdle {
                assertEquals(node.title, fixture.navigator.state.entries.single { it.record == node.record }.title)
                assertEquals(1, requireNotNull(fixture.navigator.state.chapter).verses.count { it.node == node })
                val section = requireNotNull(fixture.controller.state?.document).section
                assertEquals(node, section.node)
                assertEquals(node.record, section.paragraphs.first().record)
                assertEquals(node.end, section.paragraphs.last().record)
            }
            compose.onNodeWithTag("reader-back").performClick()
            waitForBrowse(fixture, fixture.chapter)
        }
        compose.runOnIdle {
            assertEquals(selected.map { it.location(library.id) to library.languageTag }, fixture.requests)
            assertEquals(selected.size, fixture.readerBackCalls)
            assertNull(fixture.controller.state)
        }
        assertChapterPath(fixture)
    }

    @Test
    fun nonVerseEntriesStillUseGenericNavigationAndCanReturnToTheChapter() {
        val fixture = showChapter(includeGeneric = true)
        scrollTo(browseList(fixture.chapter), hasTestTag("section-${notes.record}"))
            .assertIsDisplayed().performClick()

        waitForBrowse(fixture, notes)
        scrollTo(browseList(notes), hasTestTag("browse-title")).assertTextEquals(notes.displayTitle)
        compose.onNodeWithTag("browse-path").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.preview_search_tab)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.more_title)).assertIsDisplayed()
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.read_section)).assertDoesNotExist()
        compose.onNodeWithTag("verse-preview-${notes.record}").assertDoesNotExist()
        compose.onNodeWithTag("reader-title").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(fixture.navigator.state.chapter)
            assertEquals(listOf(collection, fixture.chapter, notes), fixture.navigator.state.path)
        }
        scrollTo(browseList(notes), hasTestTag("section-${noteLeaf.record}")).performClick()
        waitForBrowse(fixture, noteLeaf)
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.read_section)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(collection, fixture.chapter, notes, noteLeaf), fixture.navigator.state.path)
        }

        compose.onNodeWithTag("browse-back").performClick()
        waitForBrowse(fixture, notes)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForBrowse(fixture, fixture.chapter)
        assertChapterChrome(fixture)
        preview(fixture, first).assertIsDisplayed()
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(collection, fixture.chapter), fixture.navigator.state.path)
            assertEquals(fixture.overview, fixture.navigator.state.chapter)
            assertEquals(listOf(collection, fixture.chapter, notes, noteLeaf, notes, fixture.chapter)
                .map { library.id to it.record }, fixture.browseCalls)
            assertTrue(fixture.requests.isEmpty())
            assertNull(fixture.controller.state)
            assertFalse(compose.activity.isFinishing)
        }
    }

    private fun showChapter(
        versePreviews: List<VersePreview> = previews,
        includeGeneric: Boolean = false,
        readerGate: CompletableDeferred<Unit>? = null,
    ): Fixture {
        val entries = versePreviews.map { it.node } + if (includeGeneric) listOf(notes) else emptyList()
        val sourceChapter = chapter.copy(children = entries.size)
        val overview = ChapterOverview(sourceChapter, versePreviews)
        val documents = versePreviews.associate { it.node.record to document(it.node, sourceChapter.displayTitle) }
        val fixture = Fixture(sourceChapter, overview, documents)
        val tree = buildMap {
            put(collection.record, listOf(sourceChapter))
            put(sourceChapter.record, entries)
            // A mistaken navigator.open(verse) would expose a real synthetic subsection screen.
            versePreviews.forEach { preview ->
                val node = preview.node
                put(node.record, listOf(ContentNode(node.record + 1, node.record,
                    "Synthetic subsection ${node.record}", node.record + 1, 0, 4)))
            }
            put(notes.record, listOf(noteLeaf))
            put(noteLeaf.record, emptyList())
        }
        compose.setContent {
            var browser by remember { mutableStateOf(BrowseState()) }
            var reader by remember { mutableStateOf<ReaderState?>(null) }
            val scope = rememberCoroutineScope()
            val navigator = remember(scope) {
                BrowseNavigator(
                    scope = scope,
                    loadChildren = { id, parent ->
                        check(id == library.id)
                        fixture.browseCalls += id to parent
                        tree.getValue(parent)
                    },
                    onChange = { browser = it; fixture.browseChanges += it },
                    loadOverview = { id, parent, children ->
                        check(id == library.id)
                        if (parent == sourceChapter.record) {
                            check(children == entries)
                            overview
                        } else null
                    },
                    // No onReadLeaf: this fixture intentionally exercises generic opt-out navigation.
                )
            }
            val controller = remember(scope) {
                ReaderController(
                    scope = scope,
                    loadDocument = { location, language ->
                        fixture.requests += location to language
                        check(location.libraryId == library.id && language == library.languageTag)
                        readerGate?.await()
                        documents.getValue(location.start)
                    },
                    onChange = { reader = it },
                )
            }
            // Like LibraryRoute, this holder must outlive BOTH screen branches. LibraryScreen
            // also owns its per-path holder; omitting this root holder would lose its saved list.
            val rootSaveableStateHolder = rememberSaveableStateHolder()
            DisposableEffect(navigator, controller) {
                fixture.navigator = navigator
                fixture.controller = controller
                navigator.setLibrary(library.id, listOf(collection))
                onDispose { navigator.close(); controller.close() }
            }
            VedaPreviewTheme {
                val state = LibraryUiState(
                    loading = false, libraries = listOf(library), active = library,
                    collections = listOf(collection), browser = browser, reader = reader,
                )
                val reading = state.reader
                if (reading != null) rootSaveableStateHolder.SaveableStateProvider("reader") {
                    ReaderScreen(
                        state = reading,
                        onBack = { fixture.readerBackCalls++; controller.close() },
                        onNext = controller::next,
                        onPrevious = controller::previous,
                        onRetry = controller::retry,
                        onScroll = controller::saveScroll,
                    )
                } else rootSaveableStateHolder.SaveableStateProvider("library") {
                    LibraryScreen(
                        state = state,
                        // Mirror LibraryViewModel.openSection without constructing its store or preferences.
                        onOpenSection = { record ->
                            if (!state.loading && state.progress == null && state.reader == null) {
                                val target = navigator.readingTarget(record)
                                val active = requireNotNull(state.active)
                                if (target != null && state.browser.libraryId == active.id) {
                                    controller.open(active.id, active.languageTag, target)
                                } else navigator.open(record)
                            }
                        },
                        onBack = navigator::back,
                        onAncestor = navigator::goToDepth,
                        onRetrySection = navigator::retry,
                    )
                }
            }
        }
        waitFor(hasTestTag("library-list"))
        scrollTo("library-list", hasTestTag("collection-${collection.record}")).performClick()
        waitForBrowse(fixture, collection)
        scrollTo(browseList(collection), hasTestTag("section-${sourceChapter.record}")).performClick()
        waitForBrowse(fixture, sourceChapter)
        return fixture
    }

    private fun assertChapterChrome(fixture: Fixture) {
        compose.onNodeWithTag("browse-title").assertIsDisplayed().assertTextEquals(fixture.chapter.displayTitle)
        compose.onNode(hasTestTag("browse-title") and hasAnyAncestor(hasTestTag(browseList(fixture.chapter))))
            .assertDoesNotExist()
        compose.onNodeWithTag("browse-back").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("chapter-library").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("browse-path").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.browse_path)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.chapter_overview)).assertDoesNotExist()
        val count = fixture.overview.verses.size
        compose.onNodeWithText(compose.activity.resources.getQuantityString(
            R.plurals.chapter_verse_count, count, count), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.preview_search_tab)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.more_title)).assertDoesNotExist()
        compose.onNode(hasText(label(R.string.library)) and isSelectable()).assertDoesNotExist()
    }

    private fun assertChapterPath(fixture: Fixture) {
        compose.runOnIdle {
            assertEquals(listOf(collection, fixture.chapter), fixture.navigator.state.path)
            assertEquals(fixture.overview, fixture.navigator.state.chapter)
            assertFalse(fixture.navigator.state.loading)
            assertFalse(fixture.navigator.state.failed)
            assertTrue("A preview tap must never push any verse onto the path", fixture.browseChanges.all {
                it.path == listOf(collection, fixture.chapter).take(it.path.size)
            })
            assertEquals(listOf(collection, fixture.chapter).map { library.id to it.record }, fixture.browseCalls)
        }
    }

    private fun assertReader(fixture: Fixture, node: ContentNode) {
        val expected = fixture.documents.getValue(node.record)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                fixture.controller.state?.let { !it.loading && !it.failed && it.document == expected } == true
            }
        }
        waitFor(hasTestTag("reader-document"))
        compose.onNodeWithTag("reader-title").assertIsDisplayed().assertTextEquals(displayTitle(node))
        // The loaded parent is source text exposed by ReaderScreen, not an invented string resource.
        compose.onNodeWithTag("reader-context").assertIsDisplayed().assertTextEquals(fixture.chapter.displayTitle)
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        settledReader()
        compose.onNodeWithTag(browseList(fixture.chapter)).assertDoesNotExist()
        compose.onNodeWithTag(browseList(node)).assertDoesNotExist()
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(node.location(library.id), fixture.controller.state?.location)
            assertEquals(expected, fixture.controller.state?.document)
            assertEquals(ReadingUnitKind.VERSE, expected.section.kind)
        }
        assertChapterPath(fixture)
    }

    private fun document(node: ContentNode, parentTitle: String): ReaderDocument {
        val paragraphs = listOf(
            Paragraph(node.record, "Synthetic opening ${node.record}: $unicode.", "Devanagari", "Text"),
            Paragraph(node.record + 1, "Synthetic transliteration ${node.record}.", "Verse", "Text"),
            Paragraph(node.record + 2, "Synthetic synonyms ${node.record}.", "Synonyms", "Text"),
            Paragraph(node.record + 3, "Synthetic reader translation ${node.record}.", "Translation", "Text"),
            Paragraph(node.end, "Synthetic final purport marker ${node.record}.", "Purport", "Text"),
        )
        return ReaderDocument(
            ReadingSection(node, ReadingUnitKind.VERSE, parentTitle, paragraphs),
            FolioText.document(paragraphs, library.languageTag, ReaderPreferences()),
        )
    }

    /** Same native-find approach as ReaderScreenTest: never enable JS or replace WebViewClient. */
    private fun assertDocumentText(text: String) {
        val view = settledReader()
        waitForNativeCondition(view, "Reader did not finish rendering") {
            view.progress == 100 && view.contentHeight > 0 && view.width > 0 && view.height > 0 &&
                view.title == "Reader" && view.url?.matches(Regex("https://reader\\.local/document/[1-9][0-9]*")) == true
        }
        val ready = AtomicBoolean(false)
        compose.runOnIdle {
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { ready.set(true) }
            })
            view.invalidate()
        }
        waitForNativeCondition(view, "Reader visual state did not settle") { ready.get() }
        val matches = AtomicInteger(-1)
        try {
            compose.runOnIdle {
                assertEquals(label(R.string.reader_document), view.contentDescription.toString())
                assertFalse(view.settings.javaScriptEnabled)
                view.setFindListener { _, count, finished -> if (finished) matches.set(count) }
                view.findAllAsync(text)
            }
            waitForNativeCondition(view, "Native text search did not finish: $text") { matches.get() >= 0 }
            assertEquals("Rendered document matches for: $text", 1, matches.get())
        } finally {
            compose.runOnIdle { view.setFindListener(null); view.clearMatches() }
        }
    }

    private fun waitForNativeCondition(view: OfflineReaderView, message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.runOnIdle {
                    view.isAttachedToWindow && readerHost()?.settledView === view && condition()
                }
            }
        } catch (error: ComposeTimeoutException) {
            val detail = compose.runOnIdle {
                "progress=${view.progress}, contentHeight=${view.contentHeight}, title=${view.title}, " +
                    "url=${view.url?.take(120)}, attached=${view.isAttachedToWindow}"
            }
            throw AssertionError("$message: $detail", error)
        }
    }

    /** A transition may retain two WebViews; only the host knows which one is ready for native find. */
    private fun settledReader(): OfflineReaderView {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnIdle { readerHost()?.settledView?.isAttachedToWindow == true }
        }
        return compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
    }

    // Called only on the UI thread; semantics alone cannot inspect native WebView body text.
    private fun readerHost(): ReaderPageHost? = readerHosts(compose.activity.window.decorView).singleOrNull()

    private fun readerHosts(view: View): List<ReaderPageHost> = when (view) {
        is ReaderPageHost -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { readerHosts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun waitForBrowse(fixture: Fixture, node: ContentNode) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle {
                fixture.navigator.state.let { it.current == node && !it.loading && !it.failed } && fixture.controller.state == null
            }
        }
        waitFor(hasTestTag(browseList(node)))
    }

    private fun preview(fixture: Fixture, node: ContentNode) =
        scrollTo(browseList(fixture.chapter), hasTestTag("verse-preview-${node.record}"))

    private fun scrollTo(list: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        waitFor(matcher)
        return compose.onNode(matcher)
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
    }

    private fun scrollPosition(list: String): Float =
        compose.onNodeWithTag(list).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun browseList(node: ContentNode) = "browse-list-${node.record}"

    private fun displayTitle(node: ContentNode) = node.displayTitle.ifBlank { label(R.string.browse_untitled, node.record) }

    /** Row text shows the complete display reference above the translation. */
    private fun reference(node: ContentNode) = displayTitle(node)

    private fun label(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private class Fixture(
        val chapter: ContentNode,
        val overview: ChapterOverview,
        val documents: Map<Int, ReaderDocument>,
    ) {
        lateinit var navigator: BrowseNavigator
        lateinit var controller: ReaderController
        val browseCalls = mutableListOf<Pair<String, Int>>()
        val browseChanges = mutableListOf<BrowseState>()
        val requests = mutableListOf<Pair<ReadingLocation, String>>()
        var readerBackCalls = 0
    }
}