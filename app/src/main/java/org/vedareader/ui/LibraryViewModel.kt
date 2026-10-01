package org.vedareader.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkStore
import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightStore
import org.vedareader.data.SearchText
import org.vedareader.data.ImportFailure
import org.vedareader.data.ImportProgress
import org.vedareader.data.ImportStage
import org.vedareader.data.IvdRepository
import org.vedareader.data.LibraryImportException
import org.vedareader.data.LibraryInfo
import org.vedareader.data.LibraryStore
import org.vedareader.data.LinkResolution
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReaderLinkKind
import org.vedareader.data.ReaderPopup
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingUnitKind
import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget
import org.vedareader.reader.FolioText
import org.vedareader.reader.ReaderController
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderState
import java.util.Locale
import java.util.UUID

data class LibraryUiState(
    val loading: Boolean = true,
    val libraries: List<LibraryInfo> = emptyList(),
    val active: LibraryInfo? = null,
    val collections: List<ContentNode> = emptyList(),
    val progress: ImportProgress? = null,
    val cancelling: Boolean = false,
    @StringRes val error: Int? = null,
    val browser: BrowseState = BrowseState(),
    val reader: ReaderState? = null,
    val readerFontSize: Int = ReaderPreferences.DEFAULT_TEXT_SIZE,
    val searchVisible: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    val bookmarksVisible: Boolean = false,
    val moreVisible: Boolean = false,
    /** 0 = bookmarks, 1 = highlights, 2 = notes; chosen from the More sheet or kept from the last visit. */
    val bookmarksTab: Int = 0,
    val bookmarks: BookmarkUiState = BookmarkUiState(),
    val highlights: HighlightUiState = HighlightUiState(),
    val readerPaper: ReaderPaper = ReaderPaper.LIGHT,
    val readerLineSpacing: Int = 100,
    val readerLink: ReaderLinkUiState = ReaderLinkUiState(),
)

enum class ReaderLinkMessage { UNAVAILABLE, AUDIO_UNSUPPORTED }
data class ReaderLinkNotice(val id: Long, val message: ReaderLinkMessage)

/** In-document link activity: at most one resolution in flight, one popup, one unacknowledged notice. */
data class ReaderLinkUiState(
    val resolving: Boolean = false,
    val popup: ReaderPopup? = null,
    val notice: ReaderLinkNotice? = null,
    /** Link jumps Back will retrace before leaving the reader. */
    val returnDepth: Int = 0,
)

/** Offline import, source browsing, scoped search, bookmarks and reading. Audio remains separate. */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val repository = IvdRepository(application, store)
    private val initialPreferences = store.readerPreferences()
    private val mutableState = MutableStateFlow(LibraryUiState(readerFontSize = initialPreferences.fontSize,
        readerPaper = initialPreferences.paper, readerLineSpacing = initialPreferences.lineSpacing,
        highlights = HighlightUiState(defaultColor = store.highlightColor)))
    val state = mutableState.asStateFlow()
    private var importJob: Job? = null
    private enum class ReaderOrigin { LIBRARY, SEARCH, BOOKMARKS }
    private var readerOrigin = ReaderOrigin.LIBRARY
    private var searchFromBookmarks = false
    private var bookmarksFromMore = false
    private var searchFromMore = false
    /** Folded words of the search that opened the reader; empty outside search-originated reading. */
    private var readerHighlight: Set<String> = emptySet()
    private class LinkReturn(val location: ReadingLocation, val highlight: Set<String>)
    private val linkReturns = ArrayDeque<LinkReturn>()
    private var linkJob: Job? = null
    private var linkNoticeId = 0L
    private val bookmarkStore = BookmarkStore(application)
    private val bookmarks = BookmarkController(viewModelScope, bookmarkStore::load, bookmarkStore::add,
        bookmarkStore::remove, { value -> mutableState.update { it.copy(bookmarks = value) } })
    private val highlightStore = HighlightStore(application)
    private val annotationOperations = Mutex()
    private var highlightNoticeId = 0L
    private var requestedSearchRecord: Int? = null
    private val search = SearchController(viewModelScope, repository::searchBooks, repository::searchPage,
        repository::searchSuggestions, ::onSearchChanged, store::recentSearches, store::saveRecentSearches)
    private val navigator = BrowseNavigator(
        scope = viewModelScope,
        loadChildren = { library, parent -> withContext(Dispatchers.IO) { repository.children(library, parent) } },
        onChange = { browser -> mutableState.update { it.copy(browser = browser) } },
        loadOverview = repository::chapterOverview,
        onReadLeaf = ::openLeaf,
        loadIntroduction = repository::introductionRecords,
        loadLevelNames = { library -> withContext(Dispatchers.IO) { repository.levelNames(library) } },
    )
    private val reader = ReaderController(
        scope = viewModelScope,
        loadDocument = { location, language ->
            val reading = repository.bookResource(location.libraryId, location.position)
            val highlight = readerHighlight
            val preferences = ReaderPreferences()
            withContext(Dispatchers.Default) {
                ReaderDocument(reading.section, FolioText.document(reading.section.paragraphs, language, preferences,
                    bookMode = true, scrollingBook = true, highlight = highlight), reading.position, reading)
            }
        },
        onChange = { value -> mutableState.update { it.copy(reader = value) } },
        loadBookDocument = { location, language ->
            val reading = repository.bookResource(location.libraryId, location.position)
            val highlight = readerHighlight
            val preferences = ReaderPreferences()
            withContext(Dispatchers.Default) {
                ReaderDocument(reading.section, FolioText.document(reading.section.paragraphs, language, preferences,
                    bookMode = true, highlight = highlight), reading.position, reading)
            }
        },
        initialMode = store.readingMode,
        onModeChange = { store.readingMode = it },
    )

    init { bookmarks.reload(); reloadHighlights(); reload(cleanup = true) }

    fun reload(cleanup: Boolean = false) {
        if (mutableState.value.progress != null) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (cleanup) repository.prepareStorage()
                loadLibrary()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.update { it.copy(loading = false, error = R.string.library_load_error) }
            }
        }
    }

    fun selectLibrary(id: String) {
        if (mutableState.value.loading || mutableState.value.progress != null) return
        if (mutableState.value.libraries.none { it.id == id && Locale.forLanguageTag(it.languageTag).language == "en" }) return
        store.activeId = id
        reload()
    }

    fun importDatabase(uri: Uri) {
        if (mutableState.value.loading || mutableState.value.progress != null) return
        mutableState.update { it.copy(progress = ImportProgress(ImportStage.COPYING), cancelling = false, error = null) }
        importJob = viewModelScope.launch {
            try {
                repository.import(uri, "en") { progress -> mutableState.update { it.copy(progress = progress) } }
                loadLibrary()
            } catch (error: CancellationException) {
                // Publication may already have completed when cancellation reaches the caller.
                // Refresh from the registry rather than deleting a successfully committed library.
                withContext(NonCancellable) {
                    try { loadLibrary() }
                    catch (_: Exception) { mutableState.update { it.copy(loading = false, error = R.string.library_load_error) } }
                }
                throw error
            } catch (error: Exception) {
                val message = when ((error as? LibraryImportException)?.reason) {
                    ImportFailure.LANGUAGE -> R.string.invalid_language
                    ImportFailure.SPACE -> R.string.import_space_error
                    ImportFailure.INVALID_DATABASE -> R.string.import_invalid_error
                    ImportFailure.INCOMPLETE -> R.string.import_incomplete_error
                    ImportFailure.STORAGE -> R.string.import_storage_error
                    ImportFailure.OPEN, null -> R.string.import_open_error
                }
                mutableState.update { it.copy(error = message) }
            } finally {
                mutableState.update { it.copy(progress = null, cancelling = false, loading = false) }
            }
        }
    }

    fun cancelImport() {
        if (mutableState.value.progress?.stage == ImportStage.FINISHING) return
        mutableState.update { it.copy(cancelling = true) }
        importJob?.cancel()
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    fun openSection(record: Int) {
        val current = mutableState.value
        if (current.loading || current.progress != null || current.reader != null) return
        val target = navigator.readingTarget(record)
        val library = current.active
        if (target != null && library != null && current.browser.libraryId == library.id) {
            readerOrigin = ReaderOrigin.LIBRARY
            readerHighlight = emptySet()
            resetLinkActivity()
            reader.open(library.id, library.languageTag, target)
        } else navigator.open(record)
    }

    fun backToParent() { if (mutableState.value.progress == null) navigator.back() }
    fun goToAncestor(depth: Int) { if (mutableState.value.progress == null) navigator.goToDepth(depth) }
    fun retrySection() { navigator.retry() }

    private fun openLeaf(libraryId: String, node: ContentNode) {
        val current = mutableState.value
        if (current.loading || current.progress != null || current.reader != null) return
        if (current.active?.id == libraryId) {
            readerOrigin = ReaderOrigin.LIBRARY
            readerHighlight = emptySet()
            resetLinkActivity()
            reader.open(libraryId, "en", node)
        }
    }

    fun closeReader() {
        // Back first retraces link jumps made inside the reader, newest first.
        linkReturns.removeLastOrNull()?.let { previous ->
            val library = mutableState.value.active
            if (library != null && library.id == previous.location.libraryId) {
                resetLinkActivity(keepReturns = true)
                readerHighlight = previous.highlight
                reader.open(previous.location, library.languageTag)
                return
            }
            linkReturns.clear()
        }
        reader.close()
        readerHighlight = emptySet()
        resetLinkActivity()
        val origin = readerOrigin
        readerOrigin = ReaderOrigin.LIBRARY
        when (origin) {
            ReaderOrigin.SEARCH -> {
                mutableState.update { it.copy(searchVisible = true) }
                search.resume()
            }
            ReaderOrigin.BOOKMARKS -> mutableState.update { it.copy(bookmarksVisible = true) }
            ReaderOrigin.LIBRARY -> Unit
        }
    }

    fun openBookmarks(tab: Int = mutableState.value.bookmarksTab) {
        val current = mutableState.value
        if (current.loading || current.progress != null) return
        bookmarksFromMore = current.moreVisible
        requestedSearchRecord = null
        searchFromBookmarks = false
        search.pause()
        mutableState.update { it.copy(bookmarksVisible = true, searchVisible = false, bookmarksTab = tab.coerceIn(0, 2)) }
    }

    fun openMore() {
        val current = mutableState.value
        if (current.loading || current.progress != null) return
        requestedSearchRecord = null
        search.pause()
        mutableState.update { it.copy(moreVisible = true, bookmarksVisible = false, searchVisible = false) }
    }

    fun closeMore() { mutableState.update { it.copy(moreVisible = false) } }

    fun setBookmarksTab(tab: Int) { mutableState.update { it.copy(bookmarksTab = tab.coerceIn(0, 2)) } }

    /** Bottom-bar Library tab: leave search/bookmarks/reader and show the library's top level. */
    fun openLibraryHome() {
        val current = mutableState.value
        if (current.loading || current.progress != null) return
        requestedSearchRecord = null
        searchFromBookmarks = false
        search.pause()
        if (current.reader != null) { reader.close(); readerHighlight = emptySet(); resetLinkActivity() }
        readerOrigin = ReaderOrigin.LIBRARY
        mutableState.update { it.copy(bookmarksVisible = false, moreVisible = false, searchVisible = false,
            search = it.search.copy(resolvingContext = false)) }
        if (navigator.state.path.isNotEmpty()) navigator.goToDepth(0)
    }

    fun closeBookmarks() { mutableState.update { it.copy(bookmarksVisible = false, moreVisible = bookmarksFromMore) } }
    fun retryBookmarks() = bookmarks.reload()
    fun acknowledgeBookmarkFeedback(id: Long) = bookmarks.acknowledge(id)

    fun removeBookmark(bookmark: Bookmark) {
        val current = mutableState.value
        if (current.bookmarksVisible && current.active?.id == bookmark.location.libraryId) bookmarks.remove(bookmark)
    }

    // ---- Highlights: local JSON store, painted by the reader, listed beside bookmarks. ----

    fun reloadHighlights() {
        viewModelScope.launch {
            annotationOperations.withLock {
                mutableState.update { it.copy(highlights = it.highlights.copy(loading = true, failed = false)) }
                try {
                    val items = highlightStore.load()
                    mutableState.update { it.copy(highlights = it.highlights.copy(items = items, loading = false)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(highlights = it.highlights.copy(loading = false, failed = true)) }
                }
            }
        }
    }

    private fun mutateHighlights(success: HighlightMessage?, undo: ((List<Highlight>) -> String?)? = null,
        change: suspend () -> List<Highlight>) {
        val libraryId = mutableState.value.active?.id
        viewModelScope.launch {
            annotationOperations.withLock {
                mutableState.update { it.copy(highlights = it.highlights.copy(busy = true)) }
                try {
                    val items = change()
                    val notice = success?.let { HighlightNotice(++highlightNoticeId, it, undo?.invoke(items)) }
                    mutableState.update { it.copy(highlights = it.highlights.copy(items = items, busy = false, failed = false,
                        notice = if (it.active?.id == libraryId) notice ?: it.highlights.notice else it.highlights.notice)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    val notice = HighlightNotice(++highlightNoticeId, HighlightMessage.FAILED)
                    mutableState.update { it.copy(highlights = it.highlights.copy(busy = false,
                        notice = if (it.active?.id == libraryId) notice else it.highlights.notice)) }
                }
            }
        }
    }

    fun addHighlight(selection: HighlightSelection, withNote: Boolean = false) {
        if (withNote) return // Note drafts are saved only by addNote after the user confirms.
        val highlight = annotationFromSelection(selection) ?: return
        val existing = mutableState.value.highlights.items.firstOrNull { sameSpan(it, highlight) }
        mutateHighlights(HighlightMessage.SAVED, undo = { items ->
            items.firstOrNull { sameSpan(it, highlight) }?.id.takeIf { existing?.highlighted != true }
        }) { highlightStore.add(highlight) }
    }

    fun addNote(selection: HighlightSelection, note: String) {
        if (note.isBlank()) return
        val annotation = annotationFromSelection(selection)?.copy(note = note.trim(), highlighted = false) ?: return
        mutateHighlights(HighlightMessage.SAVED) { highlightStore.add(annotation) }
    }

    private fun sameSpan(a: Highlight, b: Highlight) = a.location.libraryId == b.location.libraryId &&
        a.record == b.record && a.start == b.start && a.endRecord == b.endRecord && a.end == b.end

    private fun annotationFromSelection(selection: HighlightSelection): Highlight? {
        val current = mutableState.value
        val reading = current.reader?.takeUnless { it.loading || it.failed } ?: return null
        if (current.searchVisible || current.bookmarksVisible || current.moreVisible || current.active?.id != reading.location.libraryId) return null
        val document = reading.document ?: return null
        val records = document.section.paragraphs.map { it.record }
        if (selection.record !in records || selection.endRecord !in records || selection.isEmpty ||
            selection.endRecord < selection.record || selection.start < 0 || selection.end < 0) return null
        val position = document.bookResource?.position ?: document.bookPosition
        val entry = position?.outline?.entries?.firstOrNull { selection.record in it.node.record..it.node.end }
        val location = (entry?.node?.location(reading.location.libraryId)?.copy(title = entry.source.displayTitle)
            ?: reading.location).copy(position = selection.record, scrollY = 0)
        val bookTitle = position?.outline?.book?.displayTitle ?: document.section.parentTitle.orEmpty()
        return Highlight(UUID.randomUUID().toString(), location, bookTitle, selection.record, selection.start,
            selection.endRecord, selection.end, selection.text.take(HighlightStore.MAX_TEXT), current.highlights.defaultColor,
            System.currentTimeMillis())
    }

    fun acknowledgeHighlightCreated(token: Long) {
        mutableState.update {
            if (it.highlights.created?.token == token) it.copy(highlights = it.highlights.copy(created = null)) else it
        }
    }

    fun setDefaultHighlightColor(color: HighlightColor) {
        store.highlightColor = color
        mutableState.update { it.copy(highlights = it.highlights.copy(defaultColor = color)) }
    }

    fun recolorHighlight(id: String, color: HighlightColor) {
        val libraryId = mutableState.value.active?.id ?: return
        if (mutableState.value.highlights.items.none { it.id == id && it.location.libraryId == libraryId }) return
        mutateHighlights(null) { highlightStore.recolor(id, libraryId, color) }
    }

    fun setHighlightNote(id: String, note: String) {
        val libraryId = mutableState.value.active?.id ?: return
        if (mutableState.value.highlights.items.none { it.id == id && it.location.libraryId == libraryId }) return
        mutateHighlights(HighlightMessage.SAVED) { highlightStore.setNote(id, libraryId, note) }
    }

    fun removeHighlight(id: String) {
        val libraryId = mutableState.value.active?.id ?: return
        if (mutableState.value.highlights.items.none { it.id == id && it.location.libraryId == libraryId }) return
        mutateHighlights(HighlightMessage.REMOVED) { highlightStore.removeHighlightOnly(id, libraryId) }
    }

    fun removeHighlight(highlight: Highlight) = removeHighlight(highlight.id)

    fun removeNote(highlight: Highlight) = setHighlightNote(highlight.id, "")

    fun acknowledgeHighlightNotice(id: Long) {
        mutableState.update {
            if (it.highlights.notice?.id == id) it.copy(highlights = it.highlights.copy(notice = null)) else it
        }
    }

    /** Same entry path as a bookmark: the highlight's first record becomes the reading position. */
    fun openHighlight(highlight: Highlight) {
        val current = mutableState.value
        val library = current.active ?: return
        if (!current.bookmarksVisible || current.loading || current.progress != null ||
            highlight.location.libraryId != library.id || highlight !in current.highlights.items) return
        readerOrigin = ReaderOrigin.BOOKMARKS
        readerHighlight = emptySet()
        resetLinkActivity()
        mutableState.update { it.copy(bookmarksVisible = false, moreVisible = false, searchVisible = false) }
        reader.open(highlight.location, library.languageTag)
    }

    fun openBookmark(bookmark: Bookmark) {
        val current = mutableState.value
        val library = current.active ?: return
        if (!current.bookmarksVisible || current.loading || current.progress != null ||
            bookmarks.state.loading || bookmarks.state.busy || bookmarks.state.error != null ||
            bookmark.location.libraryId != library.id || bookmark !in bookmarks.state.items) return
        readerOrigin = ReaderOrigin.BOOKMARKS
        readerHighlight = emptySet()
        resetLinkActivity()
        mutableState.update { it.copy(bookmarksVisible = false, moreVisible = false, searchVisible = false) }
        reader.open(bookmark.location, library.languageTag)
    }

    /** UI supplies the settled native anchor; display-only next-verse references are never saved. */
    fun setReaderBookmark(record: Int, save: Boolean) {
        val current = mutableState.value
        val reading = current.reader?.takeUnless { it.loading || it.failed } ?: return
        if (current.searchVisible || current.bookmarksVisible || current.progress != null || current.loading ||
            current.active?.id != reading.location.libraryId || reading.location.position != record) return
        val document = reading.document ?: return
        val paragraph = document.section.paragraphs.firstOrNull { it.record == record } ?: return
        val existing = bookmarks.state.items.firstOrNull {
            it.location.libraryId == reading.location.libraryId && it.location.position == record
        }
        if (!save) { if (existing != null) bookmarks.remove(existing); return }
        if (existing != null) return // Same-source requests are idempotent across modes/title updates.
        val position = document.bookResource?.position ?: document.bookPosition ?: return
        val entry = position.outline.entries.firstOrNull { record in it.node.record..it.node.end } ?: return
        val book = position.outline.book
        val location = entry.node.location(reading.location.libraryId).copy(
            title = entry.source.displayTitle, position = record, scrollY = 0)
        bookmarks.add {
            withContext(Dispatchers.Default) {
                Bookmark(UUID.randomUUID().toString(), location, book.record, book.displayTitle,
                    SearchText.snippet(FolioText.plain(paragraph.text), maxCharacters = 260).text,
                    System.currentTimeMillis())
            }
        }
    }

    fun openSearch(inContext: Boolean = false) {
        val current = mutableState.value
        if (current.loading || current.progress != null) return
        searchFromMore = current.moreVisible
        searchFromBookmarks = current.bookmarksVisible || readerOrigin == ReaderOrigin.BOOKMARKS ||
            (readerOrigin == ReaderOrigin.SEARCH && searchFromBookmarks)
        requestedSearchRecord = if (inContext) current.reader?.location?.position ?: current.browser.current?.record else null
        mutableState.update { it.copy(searchVisible = true,
            search = it.search.copy(resolvingContext = requestedSearchRecord != null)) }
        search.setLibrary(current.active?.id, current.active?.title.orEmpty())
        search.resume()
        applySearchContext(search.state)
    }

    fun closeSearch() {
        requestedSearchRecord = null
        search.pause()
        mutableState.update { it.copy(searchVisible = false,
            moreVisible = it.moreVisible || (searchFromMore && it.reader == null),
            bookmarksVisible = it.bookmarksVisible || (searchFromBookmarks && it.reader == null),
            search = it.search.copy(resolvingContext = false)) }
        searchFromBookmarks = false
        searchFromMore = false
    }

    private fun onSearchChanged(value: SearchUiState) {
        mutableState.update { it.copy(search = value.copy(resolvingContext = requestedSearchRecord != null)) }
        applySearchContext(value)
    }

    private fun applySearchContext(value: SearchUiState) {
        val record = requestedSearchRecord ?: return
        if (value.catalogLoading || value.catalogError || value.books.isEmpty()) return
        requestedSearchRecord = null
        val book = value.books.filter { record in it.node.record..it.node.end }
            .minByOrNull { it.node.end.toLong() - it.node.record }
        if (book != null) search.setBooks(setOf(book.node.record))
        mutableState.update { it.copy(search = it.search.copy(resolvingContext = false)) }
    }

    fun setSearchQuery(value: String) = search.setQuery(value)
    fun setSearchBooks(records: Set<Int>) {
        if (mutableState.value.search.resolvingContext) return
        search.setBooks(records)
    }
    fun setSearchMode(mode: SearchMode) = search.setMode(mode)
    fun setSearchTarget(target: SearchTarget) = search.setTarget(target)
    fun submitSearch() { if (!mutableState.value.search.resolvingContext) search.submit() }
    fun selectSearchSuggestion(value: SearchSuggestion) {
        if (!mutableState.value.search.resolvingContext) search.selectSuggestion(value)
    }
    fun loadMoreSearch() = search.loadMore()
    fun retrySearchCatalog() = search.retryCatalog()
    fun cancelSearch() = search.cancel()
    fun clearSearchHistory() = search.clearHistory()

    fun openSearchHit(hit: ScopedSearchHit) {
        val current = mutableState.value
        val library = current.active ?: return
        if (!current.searchVisible || hit.location.libraryId != library.id ||
            hit !in search.state.results || current.loading || current.progress != null) return
        search.pause()
        readerOrigin = ReaderOrigin.SEARCH
        readerHighlight = SearchText.highlightTerms(search.state.submittedQuery, search.state.target)
        resetLinkActivity()
        mutableState.update { it.copy(searchVisible = false, bookmarksVisible = false, moreVisible = false) }
        reader.open(hit.location, library.languageTag)
    }

    /** Resolve a tapped in-text link against the source; only the settled current page may ask. */
    fun openReaderLink(link: ReaderLink) {
        val current = mutableState.value
        val library = current.active ?: return
        val reading = current.reader?.takeUnless { it.loading || it.failed } ?: return
        if (current.searchVisible || current.bookmarksVisible || current.loading || current.progress != null ||
            current.readerLink.resolving || current.readerLink.popup != null ||
            reading.location.libraryId != library.id) return
        if (link.kind == ReaderLinkKind.AUDIO) { notifyLink(ReaderLinkMessage.AUDIO_UNSUPPORTED); return }
        val origin = reading.location
        val highlight = readerHighlight
        mutableState.update { it.copy(readerLink = it.readerLink.copy(resolving = true)) }
        linkJob = viewModelScope.launch {
            val resolution = try {
                repository.resolveLink(library.id, link, origin.position)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { LinkResolution.Unavailable }
            val latest = mutableState.value
            val latestLocation = latest.reader?.takeUnless { it.loading || it.failed }?.location
            // Stale if the reader moved to another resource, closed or left the library while resolving.
            if (latest.active?.id != library.id || latestLocation == null || latest.searchVisible ||
                latestLocation.libraryId != origin.libraryId || latestLocation.start != origin.start ||
                latestLocation.end != origin.end) {
                mutableState.update { it.copy(readerLink = it.readerLink.copy(resolving = false)) }
                return@launch
            }
            mutableState.update { it.copy(readerLink = it.readerLink.copy(resolving = false)) }
            when (resolution) {
                is LinkResolution.Open -> {
                    if (resolution.location.position == latestLocation.position) return@launch
                    linkReturns.addLast(LinkReturn(latestLocation, highlight))
                    while (linkReturns.size > MAX_LINK_RETURNS) linkReturns.removeFirst()
                    readerHighlight = emptySet()
                    mutableState.update { it.copy(readerLink = it.readerLink.copy(returnDepth = linkReturns.size)) }
                    reader.open(resolution.location, library.languageTag)
                }
                is LinkResolution.Popup -> mutableState.update { it.copy(readerLink = it.readerLink.copy(popup = resolution.popup)) }
                is LinkResolution.Search -> {
                    openSearch(inContext = false)
                    search.setQuery(resolution.query)
                    search.submit()
                }
                LinkResolution.Unavailable -> notifyLink(ReaderLinkMessage.UNAVAILABLE)
            }
        }
    }

    fun closeReaderPopup() { mutableState.update { it.copy(readerLink = it.readerLink.copy(popup = null)) } }

    /** Links inside a popup navigate the underlying reader after dismissing the popup. */
    fun openPopupLink(link: ReaderLink) {
        if (mutableState.value.readerLink.popup == null) return
        closeReaderPopup()
        openReaderLink(link)
    }

    fun acknowledgeLinkNotice(id: Long) {
        mutableState.update { if (it.readerLink.notice?.id == id) it.copy(readerLink = it.readerLink.copy(notice = null)) else it }
    }

    private fun notifyLink(message: ReaderLinkMessage) {
        val notice = ReaderLinkNotice(++linkNoticeId, message)
        mutableState.update { it.copy(readerLink = it.readerLink.copy(notice = notice)) }
    }

    private fun resetLinkActivity(keepReturns: Boolean = false) {
        linkJob?.cancel()
        linkJob = null
        if (!keepReturns) linkReturns.clear()
        mutableState.update { it.copy(readerLink = ReaderLinkUiState(returnDepth = linkReturns.size)) }
    }
    fun nextReaderUnit() = reader.next()
    fun previousReaderUnit() = reader.previous()
    fun retryReader() = reader.retry()
    fun saveReaderScroll(position: Int) = reader.saveScroll(position)
    fun toggleReadingMode() = reader.toggleMode()
    fun jumpToBookEntry(record: Int) = reader.jumpToBookEntry(record)
    fun saveBookAnchor(record: Int) = reader.saveBookAnchor(record)
    fun readerImage(libraryId: String, name: String) = repository.readerImage(libraryId, name)

    fun setReaderFontSize(fontSize: Int) {
        val size = fontSize.coerceIn(14, 30)
        if (size == mutableState.value.readerFontSize) return
        // User metadata only: preserve paper and the current document/controller caches.
        store.savePreferences(store.readerPreferences().copy(fontSize = size))
        mutableState.update { it.copy(readerFontSize = size) }
    }

    fun setReaderPaper(paper: ReaderPaper) {
        if (paper == mutableState.value.readerPaper) return
        store.savePreferences(store.readerPreferences().copy(paper = paper))
        mutableState.update { it.copy(readerPaper = paper) }
    }

    fun setReaderLineSpacing(spacing: Int) {
        val bounded = spacing.coerceIn(85, 150)
        if (bounded == mutableState.value.readerLineSpacing) return
        store.savePreferences(store.readerPreferences().copy(lineSpacing = bounded))
        mutableState.update { it.copy(readerLineSpacing = bounded) }
    }

    private suspend fun loadLibrary() {
        val next = withContext(Dispatchers.IO) {
            // Hide unsupported languages for now without deleting their imported files.
            val libraries = store.libraries().filter { Locale.forLanguageTag(it.languageTag).language == "en" }
            val active = libraries.firstOrNull { it.id == store.activeId } ?: libraries.firstOrNull()
            Triple(libraries, active, active?.let { repository.children(it.id, 0) }.orEmpty())
        }
        mutableState.update { it.copy(loading = false, libraries = next.first, active = next.second, collections = next.third) }
        if (reader.state?.location?.libraryId != next.second?.id) { reader.close(); readerOrigin = ReaderOrigin.LIBRARY; resetLinkActivity() }
        if (search.state.libraryId != next.second?.id) {
            requestedSearchRecord = null
            searchFromBookmarks = false
            search.pause()
            search.setLibrary(next.second?.id, next.second?.title.orEmpty())
            if (mutableState.value.searchVisible) search.resume()
        }
        navigator.setLibrary(next.second?.id, next.third)
    }

    override fun onCleared() {
        linkJob?.cancel()
        bookmarks.close()
        search.close()
        reader.close()
        navigator.close()
        repository.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_LINK_RETURNS = 16
    }
}