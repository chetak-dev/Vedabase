package org.vedareader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LibraryRoute(model: LibraryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val screens = rememberSaveableStateHolder()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDatabase(uri)
    }
    val view = LocalView.current
    DisposableEffect(view, state.progress != null) {
        val previous = view.keepScreenOn
        if (state.progress != null) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    if (state.searchVisible) {
        screens.SaveableStateProvider("search:${state.active?.id.orEmpty()}") {
            SearchScreen(state.search, model::closeSearch, model::setSearchQuery, model::setSearchBooks,
                model::setSearchMode, model::submitSearch, model::selectSearchSuggestion,
                model::openSearchHit, model::loadMoreSearch, model::retrySearchCatalog,
                model::cancelSearch, model::clearSearchHistory, model::setSearchTarget,
                onHome = model::openLibraryHome, onBookmarks = model::openBookmarks,
                onMore = model::openMore)
        }
        return
    }
    if (state.bookmarksVisible) {
        screens.SaveableStateProvider("bookmarks:${state.active?.id.orEmpty()}") {
            BookmarksScreen(state.bookmarks, state.active, model::closeBookmarks,
                { model.openSearch() }, model::openBookmark, model::removeBookmark,
                model::retryBookmarks, model::acknowledgeBookmarkFeedback,
                highlights = state.highlights, onOpenHighlight = model::openHighlight,
                onRemoveHighlight = model::removeHighlight, onRetryHighlights = model::reloadHighlights,
                tab = state.bookmarksTab, onTabChange = model::setBookmarksTab,
                onMore = model::openMore, onHome = model::openLibraryHome,
                onEditNote = model::setHighlightNote, onRemoveNote = model::removeNote)
        }
        return
    }
    if (state.moreVisible) {
        screens.SaveableStateProvider("more:${state.active?.id.orEmpty()}") {
            MoreScreen(state.active, model::closeMore, model::openLibraryHome,
                { model.openSearch() }, model::openBookmarks)
        }
        return
    }
    val reading = state.reader
    if (reading != null) {
        screens.SaveableStateProvider("reader") {
            val bookmarkedRecords = remember(state.bookmarks.items, reading.location.libraryId) {
                state.bookmarks.items.filter { it.location.libraryId == reading.location.libraryId }
                    .mapTo(hashSetOf()) { it.location.position }
            }
            val documentHighlights = remember(state.highlights.items, reading.location.libraryId, reading.location.start, reading.location.end) {
                state.highlights.items.filter {
                    it.location.libraryId == reading.location.libraryId &&
                        it.record <= reading.location.end && it.endRecord >= reading.location.start
                }
            }
            ReviewedReaderScreen(reading, model::closeReader, model::nextReaderUnit, model::previousReaderUnit,
                model::retryReader, model::saveReaderScroll, image = { model.readerImage(reading.location.libraryId, it) },
                onToggleMode = model::toggleReadingMode, onBookEntry = model::jumpToBookEntry,
                onBookAnchor = model::saveBookAnchor, fontSize = state.readerFontSize,
                onFontSizeChange = model::setReaderFontSize, onSearch = { model.openSearch(inContext = true) },
                bookmarkedRecords = bookmarkedRecords, onBookmarkChange = model::setReaderBookmark,
                bookmarkReady = !state.bookmarks.loading && !state.bookmarks.busy && state.bookmarks.error == null,
                bookmarkFeedback = state.bookmarks.feedback, onBookmarkFeedbackShown = model::acknowledgeBookmarkFeedback,
                paper = state.readerPaper, onPaperChange = model::setReaderPaper,
                lineSpacing = state.readerLineSpacing, onLineSpacingChange = model::setReaderLineSpacing,
                onLink = model::openReaderLink, linkState = state.readerLink,
                onLinkNoticeShown = model::acknowledgeLinkNotice, onClosePopup = model::closeReaderPopup,
                onPopupLink = model::openPopupLink,
                highlights = documentHighlights, onHighlightCreate = model::addHighlight,
                onHighlightRecolor = model::recolorHighlight, onHighlightRemove = model::removeHighlight,
                onHighlightNote = model::setHighlightNote, highlightCreated = state.highlights.created,
                defaultHighlightColor = state.highlights.defaultColor,
                onDefaultHighlightColorChange = model::setDefaultHighlightColor,
                onNoteCreate = model::addNote, onUndoHighlight = model::removeHighlight,
                onHighlightCreatedShown = model::acknowledgeHighlightCreated,
                highlightNotice = state.highlights.notice, onHighlightNoticeShown = model::acknowledgeHighlightNotice)
        }
        return
    }
    // SAF supplies a grant for this one document. No broad storage permission is requested.
    screens.SaveableStateProvider("library") {
    LibraryScreen(
        state = state,
        onChooseDocument = { picker.launch(arrayOf("*/*")) },
        onSelectLibrary = model::selectLibrary,
        onCancelImport = model::cancelImport,
        onDismissError = model::dismissError,
        onRetry = { model.reload(cleanup = true) },
        onOpenSection = model::openSection,
        onBack = model::backToParent,
        onAncestor = model::goToAncestor,
        onRetrySection = model::retrySection,
        onSearch = { model.openSearch(inContext = state.browser.current != null) },
        onBookmarks = model::openBookmarks,
        onMore = model::openMore,
    )
    }
}