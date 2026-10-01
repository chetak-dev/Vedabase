package org.vedareader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkIssue
import org.vedareader.data.BookmarkStore
import org.vedareader.data.ContentNode
import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightStore
import org.vedareader.data.LibraryInfo
import org.vedareader.data.LibraryStore
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReaderLinkKind
import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchMode
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual LibraryViewModel -> repository/SQLite/controllers, optionally mounted in LibraryRoute.
 * Never launches MainActivity, opens an imported corpus, or reads the target's "reader" prefs.
 * The Application redirects applicationContext too: init's prepareStorage must remain isolated.
 * State-only tests deliberately leave the route unmounted so viewport callbacks cannot conceal
 * an incorrect initial paragraph by later updating ReaderState.location.
 */
@RunWith(AndroidJUnit4::class)
class SearchNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: File
    private lateinit var application: IsolatedApplication
    private lateinit var store: LibraryStore
    private lateinit var preferences: SharedPreferences
    private lateinit var firstLibrary: LibraryInfo
    private lateinit var secondLibrary: LibraryInfo
    private lateinit var model: LibraryViewModel
    private lateinit var modelJob: Job
    private var models = ViewModelStore()
    private val mounted = mutableStateOf(false)
    private var contentInstalled = false
    private val sources = mutableListOf<SourceSnapshot>()
    private lateinit var unchangedPreferences: Map<String, *>

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        application = IsolatedApplication(target, root)
        assertSame(application, application.applicationContext)
        assertTrue(application.noBackupFilesDir.canonicalPath.startsWith(root.canonicalPath + File.separator))
        preferences = application.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        check(preferences.edit().putString("fixture-owner", root.name).commit())
        store = LibraryStore(application)
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)
        firstLibrary = registerFixture("First synthetic library")
        // Deliberately reuse record IDs: a stale hit must not resolve into the other library.
        secondLibrary = registerFixture("Second synthetic library")
        store.activeId = firstLibrary.id
        store.readingMode = ReadingMode.VERSE
        unchangedPreferences = unrelatedPreferences()
        compose.runOnIdle {
            model = LibraryViewModel(application)
            models.put("isolated-search-navigation", model)
            modelJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
        }
        awaitLibrary(firstLibrary)
    }

    @After
    fun tearDown() {
        try {
            // Release WebViews before clearing the controller and its repository.
            if (contentInstalled) {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            }
        } finally {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
                // Cancellation alone is not completion: let all VM I/O children leave the fixture.
                if (::modelJob.isInitialized) runBlocking { withTimeout(10_000) { modelJob.join() } }
                for (source in sources) {
                    assertEquals(source.size, source.file.length())
                    assertArrayEquals("Search/reading changed the synthetic source", source.hash, digest(source.file))
                }
                if (::unchangedPreferences.isInitialized) assertEquals(unchangedPreferences, unrelatedPreferences())
            } finally {
                try {
                    if (::application.isInitialized) application.deleteOwnedPreferences()
                } finally {
                    if (::root.isInitialized) {
                        check(UUID.fromString(root.name).toString() == root.name)
                        check(root.deleteRecursively())
                    }
                }
            }
        }
    }

    @Test
    fun globalOpenDoesNotInferTheBrowseScopeButContextChoosesTheNarrowestIndependentWork() {
        browseToChapter()
        val browser = model.state.value.browser
        compose.runOnIdle { model.openSearch(inContext = false) }
        awaitCatalog()
        assertTrue(model.state.value.searchVisible)
        assertEquals(emptySet<Int>(), model.state.value.search.selectedBooks)
        assertEquals(listOf(10, 20, 200), model.state.value.search.books.map { it.node.record })
        assertEquals(browser, model.state.value.browser)

        compose.runOnIdle { model.closeSearch(); model.openSearch(inContext = true) }
        awaitCatalog()
        assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
        assertNotEquals(setOf(collection.record), model.state.value.search.selectedBooks)
        assertEquals(browser, model.state.value.browser)
        assertNull(model.state.value.reader)
    }

    @Test
    fun immediateContextSubmissionWaitsForTheBookCatalogInsteadOfUsingAllDatabase() {
        browseToChapter()
        compose.runOnIdle {
            model.openSearch(inContext = true)
            model.setSearchQuery("krsna amber")
            // Same event turn: the off-main catalog cannot have published into the VM yet.
            assertTrue(model.state.value.search.resolvingContext)
            model.submitSearch()
            assertFalse(model.state.value.search.searching)
            assertNull(model.state.value.search.submittedQuery)
        }
        awaitCatalog()
        assertFalse(model.state.value.search.resolvingContext)
        assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
        compose.runOnIdle { model.submitSearch() }
        awaitSearch("krsna amber")
        assertHits(model.state.value.search.results, listOf(46))
    }

    @Test
    fun homeAndChapterSearchButtonsUseTheRealRouteAndDifferentDefaultScopes() {
        mountRoute()
        compose.onNodeWithTag("library-search").performClick()
        awaitCatalog()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        assertEquals(emptySet<Int>(), model.state.value.search.selectedBooks)
        compose.onNodeWithTag("search-back").performClick()
        clickToChapter()
        val browser = model.state.value.browser
        compose.onNodeWithTag("chapter-search").performClick()
        awaitCatalog()
        assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
        pressSystemBack()
        compose.onNodeWithTag("browse-list-${chapter.record}").assertIsDisplayed()
        assertEquals(browser, model.state.value.browser)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun singleAndMultipleBookQueriesIntersectWithinTheExactOriginalParagraph() {
        openGlobalSearch()
        compose.runOnIdle { model.setSearchBooks(setOf(work.record)) }
        assertHits(search("krsna"), listOf(46, 86))
        assertHits(search("krsna amber"), listOf(46))
        // Both words exist in verse 40..69, but not in the same paragraph.
        assertHits(search("krsna violet"), emptyList())

        compose.runOnIdle { model.setSearchBooks(setOf(work.record, otherWork.record)) }
        assertHits(search("krsna amber"), listOf(46, 226))
        compose.runOnIdle { model.setSearchMode(SearchMode.EXACT_PHRASE) }
        assertHits(search("amber light"), listOf(46, 226))
        // Overlapping collection/work scope must not duplicate the same source paragraph.
        compose.runOnIdle { model.setSearchBooks(setOf(collection.record, work.record, otherWork.record)) }
        assertHits(search("amber light"), listOf(46, 226))
    }

    @Test
    fun searchHitLoadsItsParagraphNotItsVerseOrChapterStartInBothReaderModes() {
        browseToChapter()
        val browser = model.state.value.browser
        compose.runOnIdle { model.openSearch(inContext = true) }
        awaitCatalog()
        val hit = search("krsna amber").single()
        assertEquals(40, hit.location.start)
        assertEquals(69, hit.location.end)
        assertEquals(46, hit.location.position)
        val retained = model.state.value.search

        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            compose.runOnIdle { model.openSearchHit(hit) }
            var reading = awaitReader()
            if (reading.mode != mode) {
                compose.runOnIdle { model.toggleReadingMode() }
                reading = awaitReader()
            }
            assertExactResource(reading, hit, mode)
            assertFalse(model.state.value.searchVisible)
            assertEquals(browser, model.state.value.browser)
            compose.runOnIdle { model.closeReader() }
            assertTrue(model.state.value.searchVisible)
            assertNull(model.state.value.reader)
            assertRetainedSearch(retained)
        }
        // BOOK now persists in the isolated store; a fresh explicit open must also retain 46.
        compose.runOnIdle { model.openSearchHit(hit) }
        assertExactResource(awaitReader(), hit, ReadingMode.BOOK)
        compose.runOnIdle { model.closeReader(); model.closeSearch() }
        assertEquals(browser, model.state.value.browser)
        assertFalse(model.state.value.searchVisible)
    }

    @Test
    fun searchFromAnExistingReaderWithoutSelectingRetainsItsExactDocumentAnchorAndScroll() {
        browseToChapter()
        compose.runOnIdle { model.openSection(firstVerse.record) }
        awaitReader()
        compose.runOnIdle { model.saveBookAnchor(46); model.saveReaderScroll(137) }
        val original = requireNotNull(model.state.value.reader)
        assertEquals(46, original.location.position)
        assertEquals(137, original.scrollY)
        val browser = model.state.value.browser
        compose.runOnIdle { model.openSearch(inContext = true) }
        awaitCatalog()
        assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
        assertHits(search("krsna"), listOf(46, 86))
        compose.runOnIdle { model.closeSearch() }
        assertFalse(model.state.value.searchVisible)
        assertSame(original, model.state.value.reader)
        assertSame(original.document, model.state.value.reader?.document)
        assertEquals(browser, model.state.value.browser)
        compose.runOnIdle { model.closeReader() }
        assertFalse("Ordinary reader Back must not resurrect search", model.state.value.searchVisible)
    }

    @Test
    fun resultTapAndBothBackPathsRetainSearchResultsListPositionAndTheBrowseStack() {
        mountRoute()
        clickToChapter()
        val browser = model.state.value.browser
        for (systemBack in listOf(false, true)) {
            compose.onNodeWithTag("chapter-search").performClick()
            awaitCatalog()
            compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-query"))
            compose.onNodeWithTag("search-query").performTextReplacement("navigation")
            compose.onNodeWithTag("search-query").performImeAction()
            awaitSearch("navigation")
            assertHits(model.state.value.search.results, navigationRecords)
            val retained = model.state.value.search
            compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-result-86"))
            val listPosition = searchListPosition()
            val resultTop = compose.onNodeWithTag("search-result-86").fetchSemanticsNode().boundsInRoot.top
            val listTopBefore = compose.onNodeWithTag("search-list").fetchSemanticsNode().boundsInRoot.top
            assertTrue("The round trip must exercise a scrolled result list", listPosition > 0f)
            compose.onNodeWithTag("search-result-86").assertTextContains(FolioText.plain(originalText.getValue(86)))
                .performClick()
            val view = awaitPaintedAnchor(86)
            assertEquals(86, model.state.value.reader?.document?.bookResource?.anchorRecord)
            assertEquals(originalText.getValue(86), model.state.value.reader?.document?.section?.paragraphs
                ?.single { it.record == 86 }?.text)
            assertRenderedText(view, "Kṛṣṇa carries quiet light")

            if (systemBack) pressSystemBack() else compose.onNodeWithTag("reader-back").performClick()
            compose.onNodeWithTag("search-result-86").assertIsDisplayed()
            assertTrue(model.state.value.searchVisible)
            assertNull(model.state.value.reader)
            assertRetainedSearch(retained)
            // Do not scroll after returning: that would mask lost SaveableStateHolder state.
            assertEquals(listPosition, searchListPosition(), 0.001f)
            // A lookup-only visit must not move the result list.
            compose.onNodeWithTag("continue-reading").assertDoesNotExist()
            val cardShift = compose.onNodeWithTag("search-list").fetchSemanticsNode().boundsInRoot.top - listTopBefore
            assertEquals(0f, cardShift, 1f)
            assertEquals(resultTop + cardShift, compose.onNodeWithTag("search-result-86").fetchSemanticsNode().boundsInRoot.top, 1f)
            if (systemBack) compose.onNodeWithTag("search-back").performClick() else pressSystemBack()
            compose.onNodeWithTag("browse-list-${chapter.record}").assertIsDisplayed()
            assertEquals(browser, model.state.value.browser)
        }
        // Back must still visit the original ancestors, not an injected result/verse path.
        for (expected in listOf(work, collection)) {
            compose.onNodeWithTag("browse-back").performClick()
            awaitBrowse(expected.record)
            compose.onNodeWithTag("browse-list-${expected.record}").assertIsDisplayed()
        }
        pressSystemBack()
        await { it.browser.path.isEmpty() }
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun readerSearchToolbarAndBackWithoutSelectingRestoreThePaintedSourceAnchor() {
        mountRoute()
        clickToChapter()
        compose.onNodeWithTag("verse-preview-${secondVerse.record}").performClick()
        awaitReader()
        awaitPaintedReader()
        for (systemBack in listOf(false, true)) {
            val before = requireNotNull(model.state.value.reader)
            assertTrue(before.location.position in secondVerse.record..secondVerse.end)
            val document = before.document
            compose.onNodeWithTag("reader-search").performClick()
            awaitCatalog()
            assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
            assertEquals(before.location.position, model.state.value.reader?.location?.position)
            compose.onNodeWithTag("search-query").performTextReplacement("unsubmitted fixture query")
            if (systemBack) pressSystemBack() else compose.onNodeWithTag("search-back").performClick()
            awaitPaintedAnchor(before.location.position)
            assertSame(document, model.state.value.reader?.document)
            assertEquals(before.location.position, model.state.value.reader?.location?.position)
            assertFalse(model.state.value.searchVisible)
        }
    }

    @Test
    fun forgedForeignStaleAndHiddenSearchHitsCannotOpenAReader() {
        openGlobalSearch()
        val original = search("krsna amber").first()
        val invalid = listOf(
            original.copy(location = original.location.copy(libraryId = secondLibrary.id)),
            original.copy(location = original.location.copy(position = 47)),
            original.copy(location = original.location.copy(start = 69, end = 40)),
            original.copy(snippet = "Forged result not emitted by the current search"),
        )
        for (hit in invalid) {
            compose.runOnIdle { model.openSearchHit(hit) }
            assertNull(model.state.value.reader)
            assertTrue(model.state.value.searchVisible)
        }
        // Even a formerly genuine hit is stale as soon as the query changes.
        compose.runOnIdle { model.setSearchQuery("violet"); model.openSearchHit(original) }
        assertNull(model.state.value.reader)
        assertTrue(model.state.value.search.results.isEmpty())
        val current = search("krsna amber").first()
        compose.runOnIdle { model.closeSearch(); model.openSearchHit(current) }
        assertNull(model.state.value.reader)
        assertFalse(model.state.value.searchVisible)
    }

    @Test
    fun libraryChangeResetsQueryResultsScopeModeAndBrowseAndRejectsPreviousLibraryHits() {
        store.saveRecentSearches(secondLibrary.id, listOf("other library history"))
        browseToChapter()
        compose.runOnIdle { model.openSearch(inContext = true) }
        awaitCatalog()
        compose.runOnIdle { model.setSearchMode(SearchMode.EXACT_PHRASE) }
        val oldHit = search("amber light").single()
        compose.runOnIdle { model.selectLibrary(secondLibrary.id) }
        awaitLibrary(secondLibrary)
        awaitCatalog()
        val current = model.state.value
        assertTrue(current.searchVisible)
        assertEquals("", current.search.query)
        assertNull(current.search.submittedQuery)
        assertNull(current.search.nextAfter)
        assertTrue(current.search.results.isEmpty())
        assertTrue(current.search.suggestions.isEmpty())
        assertTrue(current.search.selectedBooks.isEmpty())
        assertEquals(SearchMode.ALL_WORDS, current.search.mode)
        assertEquals(listOf("other library history"), current.search.recent)
        assertEquals(BrowseState(libraryId = secondLibrary.id), current.browser)
        assertNull(current.reader)
        compose.runOnIdle { model.openSearchHit(oldHit) }
        assertNull(model.state.value.reader)
        assertHits(search("amber light"), listOf(46, 226), secondLibrary.id)
        assertTrue(store.recentSearches(firstLibrary.id).contains("amber light"))
    }

    @Test
    fun switchingLibraryWhileAResultReaderIsOpenClearsItsReturnToSearchFlag() {
        openGlobalSearch()
        val oldHit = search("krsna amber").first()
        compose.runOnIdle { model.openSearchHit(oldHit) }
        awaitReader()
        compose.runOnIdle { model.selectLibrary(secondLibrary.id) }
        awaitLibrary(secondLibrary)
        assertNull(model.state.value.reader)
        assertFalse(model.state.value.searchVisible)
        assertTrue(model.state.value.search.results.isEmpty())
        assertEquals("", model.state.value.search.query)
        compose.runOnIdle { model.closeReader() }
        assertFalse("A previous-library result reader must not reopen search", model.state.value.searchVisible)
        compose.runOnIdle { model.openSearch() }
        awaitCatalog()
        compose.runOnIdle { model.openSearchHit(oldHit) }
        assertNull(model.state.value.reader)
    }

    @Test
    fun readerBookmarkTapSavesThePaintedSearchParagraphWithoutReplacingTheViewOrDocument() {
        awaitBookmarks(0)
        mountRoute()
        clickToChapter()
        val browser = model.state.value.browser
        compose.onNodeWithTag("chapter-search").performClick()
        awaitCatalog()
        compose.onNodeWithTag("search-query").performTextReplacement("krsna amber")
        compose.onNodeWithTag("search-query").performImeAction()
        awaitSearch("krsna amber")
        val retainedSearch = model.state.value.search
        compose.onNodeWithTag("search-result-46").performClick()
        val view = awaitPaintedAnchor(46)
        assertVisibleRecord(view, 46, firstVisible = true)
        assertRenderedText(view, "Kṛṣṇa offers amber light")
        val before = requireNotNull(model.state.value.reader)
        val document = requireNotNull(before.document)
        val host = compose.runOnIdle { requireNotNull(readerHost()) }
        val url = compose.runOnIdle { view.url }
        assertEquals(46, before.location.position)
        assertEquals(40, document.bookResource?.source?.record)
        compose.onNodeWithTag("reader-title").assertTextEquals(firstVerse.displayTitle)
        compose.onNodeWithTag("reader-bookmark").assertIsDisplayed().assertIsEnabled().performClick()

        val saved = awaitBookmarks(1).single()
        assertBookmarkSource(saved, 46)
        assertEquals(listOf(saved), loadBookmarksFromFreshStore())
        compose.onNodeWithTag("reader-bookmark").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bookmarked"))
        assertSame(document, model.state.value.reader?.document)
        compose.runOnIdle {
            assertSame(host, readerHost())
            assertSame(view, host.settledView)
            assertEquals(url, view.url)
            assertEquals(46, view.readingAnchor)
        }
        // Identity/URL are observable no-replacement guards, not a fabricated DB-query count.
        compose.onNodeWithTag("reader-back").performClick()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        assertRetainedSearch(retainedSearch)
        pressSystemBack()
        compose.onNodeWithTag("browse-list-${chapter.record}").assertIsDisplayed()
        assertEquals(browser, model.state.value.browser)
        assertEquals(listOf(saved), model.state.value.bookmarks.items)
    }

    @Test
    fun selectingNestedSearchResultStillReturnsToTheBookmarkListAfterBothBackActions() {
        awaitBookmarks(0)
        openBookmarkSource()
        val saved = saveSourceBookmark(46)
        compose.runOnIdle { model.closeReader(); model.closeSearch(); model.openBookmarks(); model.openBookmark(saved) }
        val fromBookmark = awaitReader()
        assertFalse("Bookmark-origin reading carries no search marks", requireNotNull(fromBookmark.document).html.contains("<mark"))
        assertTrue(fromBookmark.document!!.html.contains("Kṛṣṇa offers amber light"))
        compose.runOnIdle { model.openSearch(inContext = true) }
        awaitCatalog()
        val result = search("krsna").last()
        compose.runOnIdle { model.openSearchHit(result) }
        assertEquals(result.location.position, awaitReader().location.position)
        assertFalse(model.state.value.bookmarksVisible)
        compose.runOnIdle { model.closeReader() }
        assertTrue(model.state.value.searchVisible)
        compose.runOnIdle { model.closeSearch() }
        assertTrue(model.state.value.bookmarksVisible)
        assertFalse(model.state.value.searchVisible)
        assertNull(model.state.value.reader)
        assertEquals(listOf(saved), model.state.value.bookmarks.items)
    }

    @Test
    fun duplicateBookmarkRequestsAndModeChangesKeepTheOriginalParagraphAndDurableSnapshot() {
        awaitBookmarks(0)
        val hit = openBookmarkSource()
        val document = requireNotNull(model.state.value.reader?.document)
        compose.runOnIdle {
            model.setReaderBookmark(46, save = true)
            assertTrue(model.state.value.bookmarks.busy)
            assertTrue("Do not optimistically publish before the store commits", model.state.value.bookmarks.items.isEmpty())
            model.setReaderBookmark(46, save = true)
        }
        val saved = awaitBookmarks(1).single()
        assertBookmarkSource(saved, 46)
        assertSame(document, model.state.value.reader?.document)
        val feedback = requireNotNull(model.state.value.bookmarks.feedback)
        assertEquals(BookmarkMessage.SAVED, feedback.message)
        compose.runOnIdle { model.acknowledgeBookmarkFeedback(feedback.id + 1) }
        assertEquals(feedback, model.state.value.bookmarks.feedback)
        acknowledgeBookmarkFeedback()

        // No native host here: a paginated page may start at an earlier paragraph than 46.
        // This separately proves that the mode request preserves the exact source selection.
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE)) {
            compose.runOnIdle { model.toggleReadingMode() }
            assertExactResource(awaitReader(), hit, mode)
            compose.runOnIdle {
                model.setReaderBookmark(46, save = true)
                model.setReaderBookmark(46, save = true)
            }
            assertEquals(listOf(saved), awaitBookmarks(1))
            assertNull("Idempotent saves must not emit another saved event", model.state.value.bookmarks.feedback)
        }
        assertEquals(listOf(saved), loadBookmarksFromFreshStore())
        compose.runOnIdle { model.retryBookmarks() }
        assertEquals(listOf(saved), awaitBookmarks(1))
        assertEquals(listOf(saved), loadBookmarksFromFreshStore())
    }

    @Test
    fun libraryBookmarksRoundTripRestoresTheFilteredScrolledListAndOriginalBrowseStack() {
        awaitBookmarks(0)
        browseToChapter()
        openBookmarkSource()
        val saved = navigationRecords.map { saveSourceBookmark(it) }
        val filteredOut = saveSourceBookmark(47)
        val target = saved.first { it.location.position == 46 }
        acknowledgeBookmarkFeedback()
        compose.runOnIdle { model.closeReader(); model.closeSearch(); model.backToParent() }
        awaitBrowse(work.record)
        val browser = model.state.value.browser
        val allBookmarks = model.state.value.bookmarks.items
        mountRoute()
        compose.onNodeWithTag("library-more").assertIsDisplayed().performClick(); compose.onNodeWithTag("more-bookmarks").performClick()
        compose.onNodeWithTag("bookmarks-title").assertTextEquals(compose.activity.getString(R.string.bookmark_title))
        compose.onNodeWithTag("bookmarks-filter").performTextReplacement("navigation")
        compose.onNodeWithTag("bookmarks-filter").performImeAction()
        compose.onNodeWithTag("bookmark-open-${filteredOut.id}").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-open-${target.id}"))
        val listPosition = bookmarkListPosition()
        val rowTop = compose.onNodeWithTag("bookmark-open-${target.id}").fetchSemanticsNode().boundsInRoot.top
        val listTopBefore = compose.onNodeWithTag("bookmarks-list").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Exercise a genuinely scrolled list of VM-created bookmarks", listPosition > 0f)

        for (systemBack in listOf(false, true)) {
            compose.onNodeWithTag("bookmark-open-${target.id}").assertIsDisplayed()
                .assertTextContains(target.location.title).performClick()
            val view = awaitPaintedAnchor(46)
            assertEquals(46, model.state.value.reader?.document?.bookResource?.anchorRecord)
            assertRenderedText(view, "Kṛṣṇa offers amber light")
            assertFalse(model.state.value.bookmarksVisible)
            if (systemBack) pressSystemBack() else compose.onNodeWithTag("reader-back").performClick()
            compose.onNodeWithTag("bookmarks-screen").assertIsDisplayed()
            compose.onNodeWithTag("bookmarks-filter").assertTextContains("navigation")
            compose.onNodeWithTag("bookmark-open-${filteredOut.id}").assertDoesNotExist()
            compose.onNodeWithTag("bookmark-open-${target.id}").assertIsDisplayed()
            // Never scroll after Back: doing so would hide a broken SaveableStateProvider key.
            assertEquals(listPosition, bookmarkListPosition(), 0.001f)
            val listShift = compose.onNodeWithTag("bookmarks-list").fetchSemanticsNode().boundsInRoot.top - listTopBefore
            assertEquals(rowTop + listShift, compose.onNodeWithTag("bookmark-open-${target.id}").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(browser, model.state.value.browser)
            assertEquals(allBookmarks, model.state.value.bookmarks.items)
            assertFalse("Bookmark reader Back must not resurrect its seeding search", model.state.value.searchVisible)
            assertNull(model.state.value.reader)
        }
        compose.onNodeWithTag("bookmarks-back").performClick()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-back").performClick()
        compose.onNodeWithTag("browse-list-${work.record}").assertIsDisplayed()
        assertEquals(browser, model.state.value.browser)
        compose.onNodeWithTag("browse-back").performClick()
        awaitBrowse(collection.record)
        pressSystemBack()
        await { it.browser.path.isEmpty() }
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun bookmarksOpenInBothActualReaderModesAndNestedSearchBackKeepsTheBookmarkOrigin() {
        awaitBookmarks(0)
        browseToChapter()
        openBookmarkSource()
        val saved = saveSourceBookmark(46)
        acknowledgeBookmarkFeedback()
        compose.runOnIdle { model.closeReader(); model.closeSearch(); model.openBookmarks() }
        val browser = model.state.value.browser
        mountRoute()
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            compose.onNodeWithTag("bookmark-open-${saved.id}").performClick()
            val reading = awaitReader()
            assertEquals(mode, reading.mode)
            assertEquals(46, reading.document?.bookResource?.anchorRecord)
            val view = awaitPaintedReader()
            assertVisibleRecord(view, 46)
            assertRenderedText(view, "Kṛṣṇa offers amber light")
            val beforeSearch = requireNotNull(model.state.value.reader)
            compose.onNodeWithTag("reader-search").performClick()
            awaitCatalog()
            assertEquals(setOf(work.record), model.state.value.search.selectedBooks)
            compose.onNodeWithTag("search-query").performTextReplacement("not submitted from bookmark")
            if (mode == ReadingMode.VERSE) pressSystemBack() else compose.onNodeWithTag("search-back").performClick()
            val restored = awaitPaintedReader()
            // BOOK restores the selected paragraph's column, which may also show an earlier
            // paragraph. Assert the request and visibility, not an invented page-start identity.
            compose.runOnIdle { assertEquals(beforeSearch.location.position, restored.initialBookAnchor) }
            assertVisibleRecord(restored, beforeSearch.location.position)
            assertSame(beforeSearch.document, model.state.value.reader?.document)
            assertFalse(model.state.value.searchVisible)
            assertFalse(model.state.value.bookmarksVisible)
            assertEquals(listOf(saved), model.state.value.bookmarks.items)
            if (mode == ReadingMode.VERSE) {
                // Persist BOOK through the real toolbar, so the next bookmark enters that mode.
                compose.onNodeWithTag("reader-mode-toggle").performClick()
                assertEquals(ReadingMode.BOOK, awaitReader().mode)
                awaitPaintedReader()
                assertEquals(listOf(saved), loadBookmarksFromFreshStore())
                compose.onNodeWithTag("reader-back").performClick()
            } else pressSystemBack()
            compose.onNodeWithTag("bookmark-open-${saved.id}").assertIsDisplayed()
            assertTrue(model.state.value.bookmarksVisible)
            assertFalse(model.state.value.searchVisible)
            assertNull(model.state.value.reader)
            assertEquals(browser, model.state.value.browser)
        }
        pressSystemBack()
        compose.onNodeWithTag("browse-list-${chapter.record}").assertIsDisplayed()
        assertEquals(browser, model.state.value.browser)
    }

    @Test
    fun bookmarkRemovalCancelPreservesTheStoreAndConfirmRemovesOnlyTheChosenParagraph() {
        awaitBookmarks(0)
        openBookmarkSource()
        val removed = saveSourceBookmark(46)
        val retained = saveSourceBookmark(86)
        acknowledgeBookmarkFeedback()
        compose.runOnIdle { model.closeReader(); model.closeSearch() }
        mountRoute()
        compose.onNodeWithTag("library-more").performClick(); compose.onNodeWithTag("more-bookmarks").performClick()
        val original = model.state.value.bookmarks.items
        compose.onNodeWithTag("bookmarks-list").performScrollToNode(hasTestTag("bookmark-remove-${removed.id}"))
        compose.onNodeWithTag("bookmark-remove-${removed.id}").performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertIsDisplayed()
        assertEquals(original, loadBookmarksFromFreshStore())
        compose.onNodeWithTag("bookmarks-remove-cancel").performClick()
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        assertEquals(original, model.state.value.bookmarks.items)
        assertEquals(original, loadBookmarksFromFreshStore())

        compose.onNodeWithTag("bookmark-remove-${removed.id}").performClick()
        compose.onNodeWithTag("bookmarks-remove-confirm").assertIsEnabled().performClick()
        assertEquals(listOf(retained), awaitBookmarks(1))
        compose.onNodeWithTag("bookmarks-remove-dialog").assertDoesNotExist()
        compose.onNodeWithTag("bookmark-open-${removed.id}").assertDoesNotExist()
        assertEquals(listOf(retained), loadBookmarksFromFreshStore())
        compose.runOnIdle { model.openBookmark(removed); model.removeBookmark(removed) }
        assertNull(model.state.value.reader)
        assertEquals(listOf(retained), awaitBookmarks(1))
        // Reopening the same source proves reader icon state also reflects the durable removal.
        compose.onNodeWithTag("bookmarks-back").performClick()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-back").performClick()
        openBookmarkSource()
        awaitPaintedAnchor(46)
        compose.onNodeWithTag("reader-bookmark").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                compose.activity.getString(R.string.bookmark_unmarked_state)))
    }

    @Test
    fun librarySwitchHidesButRetainsForeignBookmarksAndRejectsForgedCurrentListSnapshots() {
        awaitBookmarks(0)
        openBookmarkSource()
        val foreign = saveSourceBookmark(46)
        acknowledgeBookmarkFeedback()
        compose.runOnIdle { model.closeReader(); model.closeSearch(); model.openBookmarks() }
        mountRoute()
        compose.runOnIdle { model.selectLibrary(secondLibrary.id) }
        awaitLibrary(secondLibrary)
        compose.onNodeWithTag("bookmarks-library").assertTextEquals(secondLibrary.title)
        compose.onNodeWithTag("bookmarks-empty").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertDoesNotExist()
        compose.runOnIdle {
            model.openBookmark(foreign)
            model.removeBookmark(foreign)
            model.openBookmark(foreign.copy(location = foreign.location.copy(libraryId = secondLibrary.id)))
        }
        assertNull(model.state.value.reader)
        assertEquals(listOf(foreign), awaitBookmarks(1))
        assertEquals(listOf(foreign), loadBookmarksFromFreshStore())

        compose.runOnIdle { model.closeBookmarks() }
        openBookmarkSource()
        awaitPaintedAnchor(46)
        compose.onNodeWithTag("reader-bookmark").assertIsEnabled().performClick()
        val current = awaitBookmarks(2).single { it.location.libraryId == secondLibrary.id }
        assertBookmarkSource(current, 46, secondLibrary.id)
        assertNotEquals(foreign.id, current.id)
        compose.runOnIdle { model.closeReader(); model.closeSearch(); model.openBookmarks() }
        compose.onNodeWithTag("bookmark-open-${current.id}").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertDoesNotExist()
        val allBookmarks = model.state.value.bookmarks.items
        for (forged in listOf(
            current.copy(id = UUID.randomUUID().toString()),
            current.copy(excerpt = "Forged snapshot, not emitted by the bookmark store"),
            current.copy(location = current.location.copy(position = 47)),
            current.copy(location = current.location.copy(start = 69, end = 40)),
        )) {
            compose.runOnIdle { model.openBookmark(forged); model.removeBookmark(forged) }
            assertNull(model.state.value.reader)
            assertTrue(model.state.value.bookmarksVisible)
            assertEquals(allBookmarks, awaitBookmarks(2))
        }
        compose.onNodeWithTag("bookmarks-back").performClick()
        compose.runOnIdle { model.openBookmark(current); model.removeBookmark(current) }
        assertNull("A genuine but hidden list item must not open", model.state.value.reader)
        assertEquals(allBookmarks, loadBookmarksFromFreshStore())
        compose.runOnIdle { model.selectLibrary(firstLibrary.id) }
        awaitLibrary(firstLibrary)
        compose.onNodeWithTag("library-more").performClick(); compose.onNodeWithTag("more-bookmarks").performClick()
        compose.onNodeWithTag("bookmark-open-${foreign.id}").assertIsDisplayed()
        compose.onNodeWithTag("bookmark-open-${current.id}").assertDoesNotExist()
        assertEquals(allBookmarks, model.state.value.bookmarks.items)
    }

    @Test
    fun loadingHiddenMissingAndNonCurrentParagraphsCannotBeSavedOrRemoveAValidBookmark() {
        awaitBookmarks(0)
        openGlobalSearch()
        val hit = search("krsna amber").first()
        compose.runOnIdle {
            model.openSearchHit(hit)
            assertTrue(requireNotNull(model.state.value.reader).loading)
            model.setReaderBookmark(46, save = true)
        }
        awaitReader()
        assertEquals(emptyList<Bookmark>(), awaitBookmarks(0))
        compose.runOnIdle {
            // 40 and 47 exist in the loaded resource, but neither is the current paragraph 46.
            for (record in listOf(0, 40, 47, Int.MAX_VALUE)) model.setReaderBookmark(record, save = true)
            model.retryBookmarks()
            assertTrue(model.state.value.bookmarks.loading)
            model.setReaderBookmark(46, save = true)
        }
        assertEquals(emptyList<Bookmark>(), awaitBookmarks(0))
        compose.runOnIdle { model.openSearch(); model.setReaderBookmark(46, save = true) }
        assertEquals(emptyList<Bookmark>(), awaitBookmarks(0))
        compose.runOnIdle { model.closeSearch(); model.openBookmarks(); model.setReaderBookmark(46, save = true) }
        assertEquals(emptyList<Bookmark>(), awaitBookmarks(0))
        assertTrue(loadBookmarksFromFreshStore().isEmpty())
        compose.runOnIdle { model.closeBookmarks() }
        val saved = saveSourceBookmark(46)
        compose.runOnIdle {
            model.setReaderBookmark(47, save = false)
            model.setReaderBookmark(Int.MAX_VALUE, save = true)
            model.closeReader()
            model.setReaderBookmark(46, save = false)
            model.setReaderBookmark(86, save = true)
        }
        assertEquals(listOf(saved), awaitBookmarks(1))
        assertEquals(listOf(saved), loadBookmarksFromFreshStore())
    }

    @Test
    fun bookmarkLoadFailureAndRouteRetryUseOnlyTheIsolatedUserStateDirectory() {
        awaitBookmarks(0)
        val blockedFile = File(application.noBackupFilesDir, "user-state/bookmarks.json")
        assertTrue(blockedFile.canonicalPath.startsWith(root.canonicalPath + File.separator))
        // An empty directory cannot be decoded as a bookmark file; no real prefs/history touched.
        check(blockedFile.mkdirs())
        compose.runOnIdle { model.retryBookmarks() }
        await { !it.bookmarks.loading && !it.bookmarks.busy && it.bookmarks.error != null }
        assertEquals(BookmarkIssue.LOAD, model.state.value.bookmarks.error)
        val feedback = requireNotNull(model.state.value.bookmarks.feedback)
        assertEquals(BookmarkMessage.LOAD_FAILED, feedback.message)
        acknowledgeBookmarkFeedback()
        assertEquals(BookmarkIssue.LOAD, model.state.value.bookmarks.error)
        val browser = model.state.value.browser
        mountRoute()
        compose.onNodeWithTag("library-more").performClick(); compose.onNodeWithTag("more-bookmarks").performClick()
        compose.onNodeWithTag("bookmarks-error").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-empty").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-retry").assertIsEnabled()
        check(blockedFile.delete())
        compose.onNodeWithTag("bookmarks-retry").performClick()
        awaitBookmarks(0)
        compose.onNodeWithTag("bookmarks-error").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-empty").assertIsDisplayed()
        assertTrue(model.state.value.bookmarksVisible)
        assertEquals(browser, model.state.value.browser)
        assertTrue(loadBookmarksFromFreshStore().isEmpty())
        compose.onNodeWithTag("bookmarks-back").performClick()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-back").performClick()
        compose.onNodeWithTag("library-list").assertIsDisplayed()
    }

    @Test
    fun routeHighlightSelectionPersistsYellowAndSettingsGreenOnlyAffectsFutureHighlights() {
        awaitHighlights { it.isEmpty() }
        openBookmarkSource()
        mountRoute()
        awaitPaintedAnchor(46)
        assertEquals(HighlightColor.YELLOW, model.state.value.highlights.defaultColor)
        val firstSelection = annotationSelection("Kṛṣṇa")

        // Freeze only Compose time while checking the short-lived snackbar. VM/store I/O and
        // native WebView callbacks still run; no production notice duration is changed.
        val autoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        val yellow = try {
            selectAnnotation(firstSelection, withNote = false)
            val saved = awaitHighlights { it.size == 1 }.single()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText(compose.activity.getString(R.string.annotation_undo)).assertIsDisplayed()
            assertEquals(saved.id, model.state.value.highlights.notice?.undoId)
            compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
            compose.onNodeWithTag("note-editor").assertDoesNotExist()
            saved
        } finally {
            compose.mainClock.autoAdvance = autoAdvance
        }
        assertAnnotationSource(yellow, firstSelection)
        assertEquals(HighlightColor.YELLOW, yellow.color)
        assertTrue(yellow.highlighted)
        assertFalse(yellow.hasNote)
        assertEquals(listOf(yellow), loadHighlightsFromFreshStore())

        compose.onNodeWithTag("reader-text-size").performClick()
        compose.onNodeWithTag("highlight-color-GREEN").performScrollTo().performClick().assertIsSelected()
        assertEquals(HighlightColor.GREEN, model.state.value.highlights.defaultColor)
        assertTrue(preferences.edit().commit())
        assertEquals(HighlightColor.GREEN, LibraryStore(application).highlightColor)
        // Changing the default must not recolor any existing annotation, even on disk.
        assertEquals(listOf(yellow), model.state.value.highlights.items)
        assertEquals(listOf(yellow), loadHighlightsFromFreshStore())
        compose.onNodeWithTag("reader-text-size-done").performClick()

        val nextSelection = annotationSelection("amber light")
        selectAnnotation(nextSelection, withNote = false)
        val saved = awaitHighlights { it.size == 2 }
        val green = saved.single { it.id != yellow.id }
        assertAnnotationSource(green, nextSelection)
        assertEquals(HighlightColor.GREEN, green.color)
        assertTrue(green.highlighted)
        assertFalse(green.hasNote)
        assertEquals(yellow, saved.single { it.id == yellow.id })
        assertEquals(saved, loadHighlightsFromFreshStore())
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
    }

    @Test
    fun routeNoteDraftSaveMergeAndMoreNotesEditDeletePreserveIndependentFill() {
        awaitHighlights { it.isEmpty() }
        openBookmarkSource()
        mountRoute()
        awaitPaintedAnchor(46)
        val selection = annotationSelection("amber light")

        selectAnnotation(selection, withNote = true)
        compose.onNodeWithTag("note-editor").assertIsDisplayed()
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.onNodeWithTag("note-editor-field").performTextReplacement("Uncommitted draft कृष्ण")
        assertTrue(awaitHighlights { it.isEmpty() }.isEmpty())
        assertTrue(loadHighlightsFromFreshStore().isEmpty())
        compose.onNodeWithTag("note-editor-cancel").performClick()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        assertTrue(awaitHighlights { it.isEmpty() }.isEmpty())
        assertTrue(loadHighlightsFromFreshStore().isEmpty())

        val note = "Independent reflection कृष्ण"
        selectAnnotation(selection, withNote = true)
        compose.onNodeWithTag("note-editor-field").performTextReplacement("  $note  ")
        compose.onNodeWithTag("note-editor-save").assertIsEnabled().performClick()
        val noteOnly = awaitHighlights { it.singleOrNull()?.note == note }.single()
        assertAnnotationSource(noteOnly, selection)
        assertFalse(noteOnly.highlighted)
        assertFalse(noteOnly.span.highlighted)
        assertTrue(noteOnly.span.noted)
        assertEquals(listOf(noteOnly), loadHighlightsFromFreshStore())
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.runOnIdle { model.reloadHighlights() }
        assertEquals(listOf(noteOnly), awaitHighlights { it == listOf(noteOnly) })

        compose.runOnIdle { model.setDefaultHighlightColor(HighlightColor.GREEN) }
        selectAnnotation(selection, withNote = false)
        val filled = noteOnly.copy(highlighted = true, color = HighlightColor.GREEN)
        // Equality includes id, note, source coordinates and timestamp: an exact-range merge,
        // not a replacement or second annotation, must use the current default for the new fill.
        assertEquals(listOf(filled), awaitHighlights { it == listOf(filled) })
        assertEquals(listOf(filled), loadHighlightsFromFreshStore())
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("highlight-palette").assertDoesNotExist()
        compose.runOnIdle { model.removeHighlight(filled.id) }
        val unfilled = filled.copy(highlighted = false)
        assertEquals(listOf(unfilled), awaitHighlights { it == listOf(unfilled) })
        assertEquals(listOf(unfilled), loadHighlightsFromFreshStore())

        selectAnnotation(selection, withNote = false)
        assertEquals(listOf(filled), awaitHighlights { it == listOf(filled) })
        compose.runOnIdle { model.closeReader(); model.closeSearch() }
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("more-notes").performScrollTo().performClick()
        compose.onNodeWithTag("bookmarks-tab-notes").assertIsSelected()
        compose.onNodeWithTag("highlights-list").performScrollToNode(hasTestTag("note-edit-${filled.id}"))
        compose.onNodeWithTag("note-edit-${filled.id}").performClick()
        compose.onNodeWithTag("note-editor-field").assertTextContains(note)
        val edited = filled.copy(note = "Edited from More Notes")
        compose.onNodeWithTag("note-editor-field").performTextReplacement(edited.note)
        compose.onNodeWithTag("note-editor-save").assertIsEnabled().performClick()
        assertEquals(listOf(edited), awaitHighlights { it == listOf(edited) })
        assertEquals(listOf(edited), loadHighlightsFromFreshStore())
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithTag("highlights-list").performScrollToNode(hasTestTag("highlight-remove-${filled.id}"))
        compose.onNodeWithTag("highlight-remove-${filled.id}").performClick()
        compose.onNodeWithTag("highlights-remove-confirm").assertIsEnabled().performClick()
        val fillOnly = edited.copy(note = "")
        assertEquals(listOf(fillOnly), awaitHighlights { it == listOf(fillOnly) })
        assertEquals(listOf(fillOnly), loadHighlightsFromFreshStore())
        compose.onNodeWithTag("notes-empty").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-tab-highlights").performClick()
        compose.onNodeWithTag("highlights-list").performScrollToNode(hasTestTag("highlight-open-${fillOnly.id}"))
        compose.onNodeWithTag("highlight-open-${fillOnly.id}").assertIsDisplayed()
        compose.onNodeWithTag("highlight-note-${fillOnly.id}").assertDoesNotExist()
    }

    @Test
    fun moreRoutesPreserveBrowseAndSelectBookmarksHighlightsAndNotes() {
        browseToChapter()
        compose.runOnIdle { model.openSection(firstVerse.record) }
        awaitReader()
        compose.runOnIdle { model.closeReader(); model.backToParent() }
        awaitBrowse(work.record)
        val browser = model.state.value.browser
        mountRoute()
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-library").assertTextEquals(firstLibrary.title)
        for ((tab, name) in listOf("bookmarks", "highlights", "notes").withIndex()) {
            compose.onNodeWithTag("more-$name").performScrollTo().performClick()
            compose.onNodeWithTag("bookmarks-screen").assertIsDisplayed()
            compose.onNodeWithTag("bookmarks-tab-$name").assertIsSelected()
            assertEquals(tab, model.state.value.bookmarksTab)
            assertTrue(model.state.value.bookmarksVisible)
            assertEquals(browser, model.state.value.browser)
            if (tab == 1) pressSystemBack() else compose.onNodeWithTag("bookmarks-back").performClick()
            compose.onNodeWithTag("more-screen").assertIsDisplayed()
            assertTrue(model.state.value.moreVisible)
            assertFalse(model.state.value.bookmarksVisible)
        }
        compose.onNodeWithTag("more-nav-search").performClick()
        awaitCatalog()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        pressSystemBack()
        compose.onNodeWithTag("more-screen").assertIsDisplayed()
        compose.onNodeWithTag("more-back").performClick()
        compose.onNodeWithTag("browse-list-${work.record}").assertIsDisplayed()
        assertFalse(model.state.value.moreVisible)
        assertEquals(browser, model.state.value.browser)
        compose.onNodeWithTag("library-more").performClick()
        compose.onNodeWithTag("more-nav-library").performClick()
        await { it.browser.path.isEmpty() }
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        assertFalse(model.state.value.moreVisible)
    }

    @Test
    fun readerLinksOpenDestinationsShowPopupsAndBackRetracesJumpsBeforeLeavingTheReader() {
        browseToChapter()
        compose.runOnIdle { model.openSection(firstVerse.record) }
        val start = awaitReader()
        assertEquals(firstVerse.record, start.location.position)
        assertEquals(0, model.state.value.readerLink.returnDepth)

        // Popup: shown over the reader, dismissed without moving; a link inside it navigates.
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.POPUP, "InlinePopupText_9")) }
        await { it.readerLink.popup != null }
        assertEquals("InlinePopupText_9", model.state.value.readerLink.popup?.title)
        assertEquals(start.location, model.state.value.reader?.location)
        compose.runOnIdle { model.closeReaderPopup() }
        assertNull(model.state.value.readerLink.popup)
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.POPUP, "InlinePopupText_9")) }
        await { it.readerLink.popup != null }
        compose.runOnIdle { model.openPopupLink(ReaderLink(ReaderLinkKind.QUERY, "[Headings Verse Number,sb 1.1.2]")) }
        assertNull(model.state.value.readerLink.popup)
        await { it.reader?.loading == false && it.reader?.location?.position == lastVerse.record }
        val jumped = awaitReader()
        assertEquals(lastVerse.record, jumped.location.position)
        assertEquals(otherChapter.record, jumped.location.start)
        assertEquals(1, model.state.value.readerLink.returnDepth)

        // Jump link, then an unresolvable one only notifies.
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.JUMP, "OtherWorkStart")) }
        await { it.reader?.loading == false && it.reader?.location?.position == 226 }
        assertEquals(otherChapter.record, model.state.value.reader?.location?.start)
        assertEquals(2, model.state.value.readerLink.returnDepth)
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.JUMP, "nowhere")) }
        await { it.readerLink.notice != null }
        val notice = requireNotNull(model.state.value.readerLink.notice)
        assertEquals(ReaderLinkMessage.UNAVAILABLE, notice.message)
        assertEquals(226, model.state.value.reader?.location?.position)
        compose.runOnIdle { model.acknowledgeLinkNotice(notice.id) }
        assertNull(model.state.value.readerLink.notice)
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.AUDIO, "Bg-01-01")) }
        assertEquals(ReaderLinkMessage.AUDIO_UNSUPPORTED, model.state.value.readerLink.notice?.message)

        // Back retraces the two jumps in order, then leaves the reader normally.
        compose.runOnIdle { model.closeReader() }
        await { it.reader?.loading == false && it.reader?.location?.position == lastVerse.record }
        assertEquals(1, model.state.value.readerLink.returnDepth)
        assertNull("Retracing clears link activity", model.state.value.readerLink.notice)
        compose.runOnIdle { model.closeReader() }
        await { it.reader?.loading == false && it.reader?.location?.position == firstVerse.record }
        assertEquals(start.location, model.state.value.reader?.location)
        assertEquals(0, model.state.value.readerLink.returnDepth)
        compose.runOnIdle { model.closeReader() }
        assertNull(model.state.value.reader)
        assertFalse(model.state.value.searchVisible)
        assertEquals(listOf(collection, work, chapter), model.state.value.browser.path)

        // Free-text query links become an ordinary search over the same library.
        compose.runOnIdle { model.openSection(firstVerse.record) }
        awaitReader()
        compose.runOnIdle { model.openReaderLink(ReaderLink(ReaderLinkKind.QUERY, "amber light")) }
        await { it.searchVisible && !it.search.searching && it.search.submittedQuery == "amber light" }
        assertEquals(listOf(46, 226), model.state.value.search.results.map { it.location.position })
        assertEquals(0, model.state.value.readerLink.returnDepth)
    }

    /** New provider/VM/store owner over persisted UUID fixture data; not an OS-process-death test. */
    private fun restartModel() {
        check(!contentInstalled)
        val active = requireNotNull(model.state.value.active)
        compose.runOnIdle { models.clear() }
        runBlocking { withTimeout(10_000) { modelJob.join() } }
        assertTrue(preferences.edit().commit())
        store = LibraryStore(application)
        models = ViewModelStore()
        compose.runOnIdle {
            model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(application))[LibraryViewModel::class.java]
            modelJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
        }
        awaitLibrary(active)
    }

    /** Offsets are in the original record's plain text, never in its Folio tags/search marks. */
    private fun annotationSelection(quote: String): HighlightSelection {
        val text = FolioText.plain(originalText.getValue(46))
        val start = text.indexOf(quote)
        check(quote.isNotEmpty() && start >= 0)
        return HighlightSelection(46, start, 46, start + quote.length, quote)
    }

    private fun selectAnnotation(selection: HighlightSelection, withNote: Boolean) {
        val view = awaitPaintedReader()
        compose.runOnIdle {
            val host = requireNotNull(readerHost())
            assertSame(view, host.settledView)
            // Exercise LibraryRoute's real callbacks installed on the native reader host.
            host.onHighlightSelected(selection, withNote)
        }
    }

    private fun awaitHighlights(expected: (List<Highlight>) -> Boolean): List<Highlight> {
        await { !it.highlights.loading && !it.highlights.busy && (it.highlights.failed ||
            it.highlights.notice?.message == HighlightMessage.FAILED || expected(it.highlights.items)) }
        return model.state.value.highlights.let {
            assertFalse("Annotation I/O must succeed", it.failed)
            assertNotEquals(HighlightMessage.FAILED, it.notice?.message)
            assertTrue("Expected committed annotation snapshot", expected(it.items))
            it.items
        }
    }

    private fun assertAnnotationSource(annotation: Highlight, selection: HighlightSelection) {
        assertEquals(firstLibrary.id, annotation.location.libraryId)
        assertEquals(selection.record, annotation.location.position)
        assertEquals(firstVerse.displayTitle, annotation.location.title)
        assertEquals(work.displayTitle, annotation.bookTitle)
        assertEquals(selection, HighlightSelection(annotation.record, annotation.start,
            annotation.endRecord, annotation.end, annotation.text))
        assertTrue(annotation.id.isNotBlank())
        assertTrue(annotation.createdAt > 0)
    }

    private fun loadHighlightsFromFreshStore(): List<Highlight> = runBlocking {
        withTimeout(10_000) { HighlightStore(application).load() }
    }

    private fun awaitBookmarks(count: Int): List<Bookmark> {
        await { !it.bookmarks.loading && !it.bookmarks.busy }
        return model.state.value.bookmarks.let {
            assertNull("Bookmark I/O must succeed, not masquerade as an empty list", it.error)
            assertEquals(count, it.items.size)
            it.items
        }
    }

    private fun openBookmarkSource(): ScopedSearchHit {
        openGlobalSearch()
        compose.runOnIdle { model.setSearchBooks(setOf(work.record)) }
        val hit = search("krsna amber").single()
        assertEquals(46, hit.location.position)
        compose.runOnIdle { model.openSearchHit(hit) }
        assertExactResource(awaitReader(), hit, ReadingMode.VERSE)
        return hit
    }

    /** Seed through the actual VM/paragraph validation before mounting a viewport callback owner. */
    private fun saveSourceBookmark(record: Int): Bookmark {
        val count = model.state.value.bookmarks.items.size
        compose.runOnIdle {
            model.saveBookAnchor(record)
            assertEquals(record, model.state.value.reader?.location?.position)
            model.setReaderBookmark(record, save = true)
        }
        return awaitBookmarks(count + 1).single {
            it.location.libraryId == model.state.value.active?.id && it.location.position == record
        }.also { assertBookmarkSource(it, record, requireNotNull(model.state.value.active).id) }
    }

    private fun assertBookmarkSource(bookmark: Bookmark, record: Int, libraryId: String = firstLibrary.id) {
        val verse = listOf(firstVerse, secondVerse).single { record in it.record..it.end }
        assertEquals(libraryId, bookmark.location.libraryId)
        assertEquals(record, bookmark.location.position)
        assertNotEquals("Save the paragraph, not the reference heading", verse.record, bookmark.location.position)
        assertEquals(verse.record, bookmark.location.start)
        assertEquals(verse.end, bookmark.location.end)
        assertEquals(verse.displayTitle, bookmark.location.title)
        assertEquals(0, bookmark.location.scrollY)
        assertEquals(work.record, bookmark.bookRecord)
        assertEquals(work.displayTitle, bookmark.bookTitle)
        assertEquals(FolioText.plain(originalText.getValue(record)), bookmark.excerpt)
        assertTrue(bookmark.id.isNotBlank())
        assertTrue(bookmark.createdAt > 0)
    }

    private fun acknowledgeBookmarkFeedback() {
        compose.runOnIdle {
            model.state.value.bookmarks.feedback?.let { model.acknowledgeBookmarkFeedback(it.id) }
            assertNull(model.state.value.bookmarks.feedback)
        }
    }

    /** Fresh storage instance, not process-restart coverage or a second VM sharing fixture jobs. */
    private fun loadBookmarksFromFreshStore(): List<Bookmark> = runBlocking {
        withTimeout(10_000) { withContext(Dispatchers.IO) { BookmarkStore(application).load() } }
    }

    private fun bookmarkListPosition() = compose.onNodeWithTag("bookmarks-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertVisibleRecord(view: OfflineReaderView, record: Int, firstVisible: Boolean = false) {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            // Read-only probe of the actual rendered paragraph. Paginated first-visible anchors
            // may precede the selected record; visibility must account for split client rects.
            view.evaluateJavascript("""(function(){var e=document.getElementById('record-$record'),v=visualViewport,
                w=v?v.width:innerWidth,h=v?v.height:innerHeight,x=v?v.offsetLeft:0,y=v?v.offsetTop:0;
                function visible(n){return Array.from(n.getClientRects()).some(function(r){return r.width>0&&r.height>0&&
                    r.right-x>.5&&r.left-x<w-.5&&r.bottom-y>.5&&r.top-y<h-.5;});}
                var first=Array.from(document.querySelectorAll('main.book-flow .record[data-record]')).find(visible);
                return {visible:!!e&&visible(e),first:first?Number(first.dataset.record):0};})()""") { result.set(it) }
        }
        compose.waitUntil(10_000) { result.get() != null }
        val layout = JSONObject(requireNotNull(result.get()))
        assertTrue("The bookmarked source record $record must intersect the actual WebView viewport", layout.getBoolean("visible"))
        if (firstVisible) assertEquals("The saved anchor must be the first painted source paragraph, not its heading",
            record, layout.getInt("first"))
    }

    private fun mountRoute() {
        mounted.value = true
        compose.setContent { VedaPreviewTheme { if (mounted.value) LibraryRoute(model) } }
        contentInstalled = true
    }

    private fun clickToChapter() {
        compose.onNodeWithTag("collection-${collection.record}").performClick()
        awaitBrowse(collection.record)
        for (node in listOf(work, chapter)) {
            val parent = requireNotNull(model.state.value.browser.current).record
            compose.onNodeWithTag("browse-list-$parent").performScrollToNode(hasTestTag("section-${node.record}"))
            compose.onNodeWithTag("section-${node.record}").performClick()
            awaitBrowse(node.record)
        }
        assertEquals(listOf(collection, work, chapter), model.state.value.browser.path)
    }

    private fun browseToChapter() {
        for (node in listOf(collection, work, chapter)) {
            compose.runOnIdle { model.openSection(node.record) }
            awaitBrowse(node.record)
        }
        assertEquals(listOf(collection, work, chapter), model.state.value.browser.path)
        assertEquals(listOf(firstVerse, secondVerse), model.state.value.browser.chapter?.verses?.map { it.node })
    }

    private fun openGlobalSearch() {
        compose.runOnIdle { model.openSearch() }
        awaitCatalog()
    }

    private fun search(query: String): List<ScopedSearchHit> {
        compose.runOnIdle { model.setSearchQuery(query); model.submitSearch() }
        awaitSearch(query)
        return model.state.value.search.results
    }

    private fun await(predicate: (LibraryUiState) -> Boolean) {
        compose.waitUntil(10_000) { predicate(model.state.value) }
    }

    private fun awaitLibrary(library: LibraryInfo) {
        await { !it.loading && (it.error != null || (it.active?.id == library.id && it.browser.libraryId == library.id)) }
        assertNull(model.state.value.error)
        assertEquals(listOf(firstLibrary, secondLibrary), model.state.value.libraries)
        assertEquals(library.id, model.state.value.search.libraryId)
    }

    private fun awaitBrowse(record: Int) {
        await { !it.browser.loading && it.browser.current?.record == record }
        assertFalse("Synthetic browse fixture must load", model.state.value.browser.failed)
    }

    private fun awaitCatalog() {
        await { !it.search.catalogLoading && (it.search.catalogError ||
            (it.search.books.isNotEmpty() && !it.search.resolvingContext)) }
        assertFalse("Synthetic search catalog must load", model.state.value.search.catalogError)
    }

    private fun awaitSearch(query: String) {
        await { !it.search.searching && (it.search.error != null || it.search.submittedQuery == query) }
        assertNull("Search must succeed, not present an error as zero hits", model.state.value.search.error)
        assertEquals(query, model.state.value.search.submittedQuery)
    }

    private fun awaitReader(): ReaderState {
        await { it.reader?.loading == false }
        return requireNotNull(model.state.value.reader).also {
            assertFalse("Synthetic resource must pass IvdBookReader and ReaderController validation", it.failed)
            assertTrue(it.document != null)
        }
    }

    private fun assertHits(hits: List<ScopedSearchHit>, records: List<Int>, libraryId: String = firstLibrary.id) {
        assertEquals(records, hits.map { it.location.position })
        for (hit in hits) {
            val record = hit.location.position
            assertEquals(libraryId, hit.location.libraryId)
            assertEquals(FolioText.plain(originalText.getValue(record)), hit.snippet)
            assertEquals(if (record < otherWork.record) work.displayTitle else otherWork.displayTitle, hit.bookTitle)
            val verse = listOf(firstVerse, secondVerse, otherVerse).single { record in it.record..it.end }
            assertEquals(verse.record, hit.location.start)
            assertEquals(verse.end, hit.location.end)
            assertEquals(verse.displayTitle, hit.location.title)
            assertNotEquals("Do not relocate the match to its containing heading", verse.record, record)
        }
    }

    private fun assertExactResource(reading: ReaderState, hit: ScopedSearchHit, mode: ReadingMode) {
        assertEquals(mode, reading.mode)
        assertEquals(hit.location.libraryId, reading.location.libraryId)
        assertEquals(hit.location.position, reading.location.position)
        val document = requireNotNull(reading.document)
        val resource = requireNotNull(document.bookResource)
        assertEquals(chapter.record, document.section.node.record)
        assertEquals(firstVerse, resource.source)
        assertEquals(hit.location.position, resource.anchorRecord)
        assertNotEquals(document.section.node.record, reading.location.position)
        assertNotEquals(resource.source.record, reading.location.position)
        assertEquals(originalText.getValue(46), document.section.paragraphs.single { it.record == 46 }.text)
        // Search-origin documents wrap the submitted words in app-owned marks without altering text.
        assertTrue(withoutMarks(document.html).contains("Kṛṣṇa offers amber light"))
        assertTrue(document.html.contains("<mark class=\"search-hit\">amber</mark>"))
        assertTrue(document.html.contains("<mark class=\"search-hit\">Kṛṣṇa</mark>"))
    }

    private fun withoutMarks(html: String) = html.replace(Regex("</?mark[^>]*>"), "")

    private fun assertRetainedSearch(expected: SearchUiState) {
        val actual = model.state.value.search
        assertEquals(expected.libraryId, actual.libraryId)
        assertEquals(expected.query, actual.query)
        assertEquals(expected.submittedQuery, actual.submittedQuery)
        assertEquals(expected.selectedBooks, actual.selectedBooks)
        assertEquals(expected.mode, actual.mode)
        assertEquals(expected.results, actual.results)
        assertEquals(expected.nextAfter, actual.nextAfter)
        assertEquals(expected.recent, actual.recent)
        assertFalse(actual.searching)
        assertFalse(actual.loadingMore)
    }

    private fun pressSystemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun searchListPosition() = compose.onNodeWithTag("search-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun awaitPaintedReader(): OfflineReaderView {
        awaitReader()
        var lastNative = "No native child observed"
        val lastDom = java.util.concurrent.atomic.AtomicReference("No DOM callback")
        val started = System.nanoTime()
        var sampled = false
        try { compose.waitUntil(20_000) { compose.runOnIdle {
            val host = readerHost()
            if (host != null && host.childCount > 0) {
                val native = host.getChildAt(host.childCount - 1) as? OfflineReaderView
                if (native != null) {
                    lastNative = "busy=${host.isBusy} children=${host.childCount} size=${native.width}x${native.height} " +
                        "progress=${native.progress} title=${native.title} content=${native.contentHeight} " +
                        "anchor=${native.bookPageInfo?.anchorRecord ?: native.readingAnchor} vm=${model.state.value.reader?.location?.position} " +
                        "scroll=${native.scrollX},${native.scrollY} range=${native.scrollRange} attached=${native.isAttachedToWindow}"
                    if (!sampled && System.nanoTime() - started > 5_000_000_000L) {
                        sampled = true
                        native.evaluateJavascript("JSON.stringify({width:innerWidth,height:innerHeight,visual:visualViewport?{w:visualViewport.width,h:visualViewport.height,top:visualViewport.pageTop,left:visualViewport.pageLeft}:null,fonts:document.fonts.status,records:document.querySelectorAll('[data-record]').length})") { lastDom.set(it) }
                    }
                }
            }
            val view = readerHost()?.settledView
            val anchor = view?.bookPageInfo?.anchorRecord ?: view?.readingAnchor ?: 0
            view != null && !view.isReleased && view.isAttachedToWindow && view.width > 0 && view.height > 0 &&
                view.title == "Reader" && view.contentHeight > 0 && view.progress == 100 && anchor > 0 &&
                model.state.value.reader?.location?.position == anchor
        } } } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Native settle timed out: $lastNative DOM=${lastDom.get()}", error)
        }
        return compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
    }

    private fun awaitPaintedAnchor(record: Int): OfflineReaderView {
        val view = awaitPaintedReader()
        compose.waitUntil(10_000) { compose.runOnIdle {
            (view.bookPageInfo?.anchorRecord ?: view.readingAnchor) == record &&
                model.state.value.reader?.location?.position == record
        } }
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        return view
    }

    private fun assertRenderedText(view: OfflineReaderView, text: String) {
        val done = AtomicBoolean(false)
        val matches = AtomicInteger(0)
        compose.runOnIdle {
            view.setFindListener { _, count, finished -> if (finished) { matches.set(count); done.set(true) } }
            view.findAllAsync(text)
        }
        try {
            compose.waitUntil(10_000) { done.get() }
            assertTrue("Native WebView must render the synthetic Unicode paragraph, not just a loading/error page", matches.get() > 0)
        } finally {
            compose.runOnIdle { view.setFindListener(null); view.clearMatches() }
        }
    }

    private fun readerHost(): ReaderPageHost? = findHost(compose.activity.window.decorView)
    private fun findHost(view: View): ReaderPageHost? {
        if (view is ReaderPageHost) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun registerFixture(title: String): LibraryInfo {
        val id = UUID.randomUUID().toString()
        val directory = File(application.noBackupFilesDir, "libraries").also { check(it.isDirectory || it.mkdirs()) }
        val file = File(directory, "$id.ivd")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE contents(record INTEGER PRIMARY KEY,parent INTEGER,title TEXT,next_sibling INTEGER," +
                    "node_children INTEGER,level INTEGER,node_code TEXT,simpletitle TEXT)")
                db.execSQL("CREATE TABLE texts(recid INTEGER PRIMARY KEY,plain TEXT,stylename TEXT,levelname TEXT)")
                db.execSQL("CREATE TABLE levels(id INTEGER PRIMARY KEY,level TEXT,original TEXT)")
                db.execSQL("CREATE TABLE words(word TEXT,indexbase INTEGER,data BLOB,idx TEXT)")
                db.execSQL("CREATE TABLE docinfo(name TEXT,valuex TEXT)")
                db.execSQL("CREATE TABLE objects(objectName TEXT,objectType TEXT,objectData BLOB)")
                db.execSQL("CREATE TABLE jumplinks(title TEXT,recid INTEGER)")
                db.execSQL("CREATE TABLE popup(title TEXT,class TEXT,plain TEXT)")
                db.execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf("OtherWorkStart", 226))
                db.execSQL("INSERT INTO popup VALUES (?,?,?)", arrayOf("InlinePopupText_9", "(null)",
                    "<BD+>Synthetic note<BD> see <QL:Query,\"[Headings Verse Number,sb 1.1.2]\">SB 1.1.2<EL>"))
                db.execSQL("CREATE INDEX contents_parent ON contents(parent,record)")
                db.execSQL("CREATE INDEX contents_level ON contents(level)")
                db.execSQL("CREATE INDEX contents_simple ON contents(simpletitle)")
                db.execSQL("CREATE INDEX words_word ON words(word)")
                val levels = mapOf(10 to "LE_Collection", 20 to "LE_Book", 30 to "LE_Chapter",
                    40 to "LE_Verse_Number", 50 to "LE_Verse_Section")
                levels.forEach { (number, name) -> db.execSQL("INSERT INTO levels VALUES (?,?,?)",
                    arrayOf(number, name, name.removePrefix("LE_").replace('_', ' '))) }
                for (node in headings) db.execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.code, node.title.lowercase()))
                for (record in sourceRecords) {
                    val heading = headings.firstOrNull { it.record == record }
                    val body = originalText[record] ?: heading?.title ?: when (record) {
                        41, 48, 81, 88, 221, 228 -> "Generated layout spacer $record. ".repeat(100)
                        else -> "Generated source row $record."
                    }
                    val style = when {
                        record in setOf(42, 82, 222, 252) -> "PA_Translation"
                        heading?.level == 40 -> "PA_Verse_Number_Heading"
                        heading?.level == 50 -> "PA_Titles"
                        else -> "PA_ParaText"
                    }
                    db.execSQL("INSERT INTO texts VALUES (?,?,?,?)", arrayOf(record, body, style,
                        heading?.let { levels.getValue(it.level) }.orEmpty()))
                }
                for ((term, records) in mapOf("krsna" to listOf(46, 86, 226), "light" to listOf(46, 86, 226),
                    "amber" to listOf(46, 226, 256), "violet" to listOf(47), "navigation" to navigationRecords)) {
                    val postings = ByteArray(records.size * 6)
                    records.forEachIndexed { index, record ->
                        for (byte in 0..3) postings[index * 6 + byte] = (record ushr (8 * byte)).toByte()
                        postings[index * 6 + 4] = 1 // Synthetic little-endian word position.
                    }
                    db.execSQL("INSERT INTO words VALUES (?,?,?,?)", arrayOf(term, 0, postings, ""))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(file.setReadOnly())
        sources += SourceSnapshot(file, file.length(), digest(file))
        return LibraryInfo(id, title, "en", file.name, file.length(), sourceRecords.size.toLong(), "Synthetic only")
            .also(store::addLibrary)
    }

    private fun unrelatedPreferences() = preferences.all.filterKeys {
        it !in setOf("active", "readingMode", "lastReading", "highlightColor") &&
            !it.startsWith("searchHistory:") && !it.startsWith("study:")
    }.toMap()

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private data class SourceSnapshot(val file: File, val size: Long, val hash: ByteArray)

    private class IsolatedApplication(private val target: Context, private val root: File) : Application() {
        private val prefix = "search-navigation-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        init { attachBaseContext(target) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        @Synchronized
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix$name"
            ownedPreferences += isolated
            return target.getSharedPreferences(isolated, mode)
        }
        @Synchronized
        fun deleteOwnedPreferences() {
            for (name in ownedPreferences) { check(name.startsWith(prefix)); check(target.deleteSharedPreferences(name)) }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    companion object {
        private val collection = ContentNode(10, 0, "Synthetic collection", 159, 1, 10)
        private val work = ContentNode(20, 10, "Inner synthetic work", 149, 1, 20)
        private val chapter = ContentNode(30, 20, "Synthetic chapter", 139, 2, 30)
        private val firstVerse = ContentNode(40, 30, "BG 1.1", 69, 1, 40)
        private val secondVerse = ContentNode(80, 30, "BG 1.2", 109, 1, 40)
        private val otherWork = ContentNode(200, 0, "Other synthetic work", 279, 1, 20)
        private val otherChapter = ContentNode(210, 200, "Other chapter", 279, 2, 30)
        private val otherVerse = ContentNode(220, 210, "SB 1.1.1", 239, 1, 40)
        private val lastVerse = ContentNode(250, 210, "SB 1.1.2", 269, 1, 40)
        private val headings = listOf(collection, work, chapter, firstVerse,
            ContentNode(45, 40, "PURPORT", 69, 0, 50), secondVerse, ContentNode(85, 80, "PURPORT", 109, 0, 50),
            otherWork, otherChapter, otherVerse, ContentNode(225, 220, "PURPORT", 239, 0, 50),
            lastVerse, ContentNode(255, 250, "PURPORT", 269, 0, 50))
        private val sourceRecords = (10..159).toList() + (200..279).toList()
        // Enough cards to require real scrolling, rather than relying on a particular phone height.
        private val navigationRecords = listOf(46) + (50..64).toList() + 86
        private val originalText = mapOf(
            46 to "<PS:\"PA_ParaText\">Kṛṣṇa offers amber light — कृष्ण a\u0304.\nUnmodified synthetic navigation passage.",
            47 to "Violet exists only in this neighboring paragraph.",
            86 to "<PS:\"PA_ParaText\">Kṛṣṇa carries quiet light — नमूना বাংলা. A different original navigation paragraph.",
            226 to "<PS:\"PA_ParaText\">Kṛṣṇa offers amber light in the other work — ā ś ṛ.",
            256 to "Amber without the other indexed word.",
        ) + (50..64).associateWith { "Synthetic navigation result $it — नमूना. Original paragraph, not a generated heading." }
    }
}