package org.vedareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.vedareader.R
import org.vedareader.data.BookPosition
import org.vedareader.data.ContentObject
import org.vedareader.data.Highlight
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightSelection
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderPageStatus
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReaderStyle
import org.vedareader.reader.ReadingMode
import kotlin.math.roundToInt

/** Shared paged/scrolling reader with explicitly entered, reversible fullscreen. */
@Composable
fun ReviewedReaderScreen(
    state: ReaderState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRetry: () -> Unit,
    onScroll: (Int) -> Unit,
    image: (String) -> ContentObject? = { null },
    onToggleMode: () -> Unit = {},
    onBookEntry: (Int) -> Unit = {},
    onBookAnchor: (Int) -> Unit = {},
    fontSize: Int = 20,
    onFontSizeChange: (Int) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    bookmarkedRecords: Set<Int> = emptySet(),
    onBookmarkChange: ((Int, Boolean) -> Unit)? = null,
    bookmarkReady: Boolean = true,
    bookmarkFeedback: BookmarkFeedback? = null,
    onBookmarkFeedbackShown: (Long) -> Unit = {},
    paper: ReaderPaper = ReaderPaper.LIGHT,
    onPaperChange: (ReaderPaper) -> Unit = {},
    lineSpacing: Int = 100,
    onLineSpacingChange: (Int) -> Unit = {},
    onLink: ((ReaderLink) -> Unit)? = null,
    linkState: ReaderLinkUiState = ReaderLinkUiState(),
    onLinkNoticeShown: (Long) -> Unit = {},
    onClosePopup: () -> Unit = {},
    onPopupLink: (ReaderLink) -> Unit = {},
    highlights: List<Highlight> = emptyList(),
    onHighlightCreate: ((HighlightSelection, Boolean) -> Unit)? = null,
    onHighlightRecolor: (String, HighlightColor) -> Unit = { _, _ -> },
    onHighlightRemove: (String) -> Unit = {},
    onHighlightNote: (String, String) -> Unit = { _, _ -> },
    highlightCreated: HighlightCreation? = null,
    onHighlightCreatedShown: (Long) -> Unit = {},
    highlightNotice: HighlightNotice? = null,
    onHighlightNoticeShown: (Long) -> Unit = {},
    defaultHighlightColor: HighlightColor = HighlightColor.YELLOW,
    onDefaultHighlightColorChange: (HighlightColor) -> Unit = {},
    onNoteCreate: (HighlightSelection, String) -> Unit = { _, _ -> },
    onUndoHighlight: (String) -> Unit = {},
) {
    // Keep the native double buffer alive across unit and mode changes in this session.
    key(state.location.libraryId, state.languageTag) {
        ReaderPaperTheme(paper) {
            ReviewedReaderSessionScreen(state, onBack, onNext, onPrevious, onRetry, onScroll,
                image, onToggleMode, onBookEntry, onBookAnchor, fontSize, onFontSizeChange, onSearch,
                bookmarkedRecords, onBookmarkChange, bookmarkReady, bookmarkFeedback, onBookmarkFeedbackShown,
                paper, onPaperChange, lineSpacing, onLineSpacingChange, onLink, linkState, onLinkNoticeShown,
                onClosePopup, onPopupLink, highlights, onHighlightCreate, onHighlightRecolor, onHighlightRemove,
                onHighlightNote, highlightCreated, onHighlightCreatedShown, highlightNotice, onHighlightNoticeShown,
                defaultHighlightColor, onDefaultHighlightColorChange, onNoteCreate, onUndoHighlight,
            )
        }
    }
}

@Composable
private fun ReviewedReaderSessionScreen(
    state: ReaderState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRetry: () -> Unit,
    onScroll: (Int) -> Unit,
    image: (String) -> ContentObject?,
    onToggleMode: () -> Unit,
    onBookEntry: (Int) -> Unit,
    onBookAnchor: (Int) -> Unit,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onSearch: (() -> Unit)?,
    bookmarkedRecords: Set<Int>,
    onBookmarkChange: ((Int, Boolean) -> Unit)?,
    bookmarkReady: Boolean,
    bookmarkFeedback: BookmarkFeedback?,
    onBookmarkFeedbackShown: (Long) -> Unit,
    paper: ReaderPaper,
    onPaperChange: (ReaderPaper) -> Unit,
    lineSpacing: Int,
    onLineSpacingChange: (Int) -> Unit,
    onLink: ((ReaderLink) -> Unit)?,
    linkState: ReaderLinkUiState,
    onLinkNoticeShown: (Long) -> Unit,
    onClosePopup: () -> Unit,
    onPopupLink: (ReaderLink) -> Unit,
    highlights: List<Highlight>,
    onHighlightCreate: ((HighlightSelection, Boolean) -> Unit)?,
    onHighlightRecolor: (String, HighlightColor) -> Unit,
    onHighlightRemove: (String) -> Unit,
    onHighlightNote: (String, String) -> Unit,
    highlightCreated: HighlightCreation?,
    onHighlightCreatedShown: (Long) -> Unit,
    highlightNotice: HighlightNotice?,
    onHighlightNoticeShown: (Long) -> Unit,
    defaultHighlightColor: HighlightColor,
    onDefaultHighlightColorChange: (HighlightColor) -> Unit,
    onNoteCreate: (HighlightSelection, String) -> Unit,
    onUndoHighlight: (String) -> Unit,
) {
    val colors = readerPaperColors()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var fullscreenControls by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val libraryHighlights = remember(highlights, state.location.libraryId) {
        highlights.filter { it.location.libraryId == state.location.libraryId }
    }
    val spans = remember(libraryHighlights) { libraryHighlights.map { it.span } }
    // Only explicit taps open details. Primitive selection coordinates and a stable annotation ID
    // survive rotation; opening or cancelling a Note draft never invokes a persistence callback.
    var editingHighlight by rememberSaveable { mutableStateOf<String?>(null) }
    var noteSelectionData by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val noteSelection = noteSelectionData?.let {
        HighlightSelection(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4])
    }
    val noteEditorOpen = noteSelection != null
    var initialNote by rememberSaveable { mutableStateOf("") }
    var pendingNote by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedNoticeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var noteSaveFailed by rememberSaveable { mutableStateOf(false) }
    val editing = libraryHighlights.firstOrNull { it.id == editingHighlight }
    fun closeNote() {
        noteSelectionData = null; editingHighlight = null; pendingNote = null; noteSaveFailed = false
    }
    fun openNote(selection: HighlightSelection, annotation: Highlight?) {
        editingHighlight = annotation?.id
        initialNote = annotation?.note.orEmpty()
        noteSelectionData = listOf(selection.record.toString(), selection.start.toString(),
            selection.endRecord.toString(), selection.end.toString(), selection.text)
        pendingNote = null; noteSaveFailed = false
    }
    fun matchingAnnotation(selection: HighlightSelection): Highlight? = libraryHighlights.firstOrNull {
        it.record == selection.record &&
            it.start == selection.start && it.endRecord == selection.endRecord && it.end == selection.end
    }
    LaunchedEffect(highlightCreated?.token) {
        // Both plain Highlight and a saved Note are complete actions, never requests to open a tray.
        if (highlightCreated != null) onHighlightCreatedShown(highlightCreated.token)
    }
    LaunchedEffect(pendingNote, highlights, highlightNotice?.id) {
        val submitted = pendingNote
        val selection = noteSelection
        if (submitted != null && selection != null) {
            val committed = if (editingHighlight != null) libraryHighlights.firstOrNull { it.id == editingHighlight }
                else matchingAnnotation(selection)
            when {
                committed?.note == submitted || (submitted.isEmpty() && editingHighlight != null && committed == null) -> closeNote()
                highlightNotice?.id != submittedNoticeId && highlightNotice?.message == HighlightMessage.FAILED -> {
                    pendingNote = null; noteSaveFailed = true
                }
            }
        }
    }
    var host by remember { mutableStateOf<ReaderPageHost?>(null) }
    BackHandler {
        when {
            noteEditorOpen || editingHighlight != null -> closeNote()
            fullscreen -> fullscreen = false
            else -> onBack()
        }
    }
    ReaderFullscreen(fullscreen)
    var pageStatus by remember { mutableStateOf(ReaderPageStatus()) }
    val snackbar = remember { SnackbarHostState() }
    BookmarkFeedbackEffect(bookmarkFeedback, snackbar, onBookmarkFeedbackShown)
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.reader_link_later)
    val audioLater = stringResource(R.string.reader_link_audio_later)
    val highlightSaved = stringResource(R.string.highlight_saved)
    val highlightRemoved = stringResource(R.string.highlight_removed)
    val highlightFailed = stringResource(R.string.highlight_failed)
    val undoLabel = stringResource(R.string.annotation_undo)
    LaunchedEffect(highlightNotice?.id) {
        if (highlightNotice != null) {
            snackbar.currentSnackbarData?.dismiss()
            val undoId = highlightNotice.undoId.takeIf { highlightNotice.message == HighlightMessage.SAVED }
            val result = snackbar.showSnackbar(when (highlightNotice.message) {
                HighlightMessage.SAVED -> highlightSaved
                HighlightMessage.REMOVED -> highlightRemoved
                HighlightMessage.FAILED -> highlightFailed
            }, actionLabel = if (undoId != null) undoLabel else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed && undoId != null) onUndoHighlight(undoId)
            onHighlightNoticeShown(highlightNotice.id)
        }
    }
    val linkNotice = linkState.notice
    LaunchedEffect(linkNotice?.id) {
        if (linkNotice != null) {
            onLinkNoticeShown(linkNotice.id)
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(when (linkNotice.message) {
                ReaderLinkMessage.UNAVAILABLE -> unavailable
                ReaderLinkMessage.AUDIO_UNSUPPORTED -> audioLater
            })
        }
    }
    val visibleSource = if (!state.loading && !state.failed && pageStatus.hasPage) {
        state.document?.bookPosition?.outline?.entries?.firstOrNull {
            pageStatus.referenceRecord in it.node.record..it.node.end
        }?.source
    } else null
    val title = (visibleSource?.displayTitle ?: state.location.title)
        .ifBlank { stringResource(R.string.browse_untitled, state.location.start) }
    val section = state.document?.section
    val bookMode = state.mode == ReadingMode.BOOK
    var lastBookPosition by remember { mutableStateOf<BookPosition?>(null) }
    val loadedBookPosition = state.document?.bookPosition
    SideEffect { if (loadedBookPosition != null) lastBookPosition = loadedBookPosition }
    val initialBookPosition = loadedBookPosition ?: lastBookPosition.takeIf { state.loading }
    val bookPosition = initialBookPosition?.let { position ->
        val index = position.outline.entries.indexOfFirst { state.location.position in it.node.record..it.node.end }
        if (index >= 0) BookPosition(position.outline, index) else position
    }
    var lastContext by remember { mutableStateOf("") }
    val sourceContext = section?.parentTitle
    val contextTitle = sourceContext ?: if (state.loading) lastContext else ""
    SideEffect { if (sourceContext != null) lastContext = sourceContext }
    val empty = !state.loading && !state.failed && section?.paragraphs.isNullOrEmpty()
    val description = stringResource(R.string.reader_document)
    // HTML stays at its 20px baseline; only native reader text follows this preference.
    val zoom = (LocalDensity.current.fontScale * 100 * (fontSize.coerceIn(14, 30) / 20f)).roundToInt()
    val canNavigate = !state.loading && !state.failed && !pageStatus.renderFailed &&
        !pageStatus.busy && pageStatus.hasPage && !empty
    val bookmarkAnchor = pageStatus.anchorRecord
    val isBookmarked = bookmarkAnchor in bookmarkedRecords
    val canBookmark = canNavigate && bookmarkReady && bookmarkAnchor > 0 &&
        bookmarkAnchor == state.location.position && section?.paragraphs?.any { it.record == bookmarkAnchor } == true
    val bookmarkAction = stringResource(if (isBookmarked) R.string.bookmark_remove else R.string.bookmark_add)
    val bookmarkState = stringResource(if (isBookmarked) R.string.bookmark_marked_state else R.string.bookmark_unmarked_state)
    val toggleBookmark: () -> Unit = {
        val view = host?.settledView
        val anchor = view?.settledReadingRecord
        // Synchronous gate also rejects a rapid second click during a native turn/resize.
        if (canBookmark && anchor == bookmarkAnchor) onBookmarkChange?.invoke(bookmarkAnchor, !isBookmarked)
    }
    val waitingForPage = (state.loading || pageStatus.busy) &&
        !state.failed && !pageStatus.renderFailed && !empty
    var showPageLoading by remember { mutableStateOf(false) }
    LaunchedEffect(waitingForPage) {
        showPageLoading = false
        if (waitingForPage) {
            // Normal short turns should not flash a loader; slow loads still get feedback.
            delay(300)
            showPageLoading = true
        }
    }
    LaunchedEffect(state.failed, pageStatus.renderFailed, empty) {
        if (state.failed || pageStatus.renderFailed || empty) fullscreen = false
    }
    val exitFullscreen = stringResource(R.string.reader_exit_fullscreen)
    val showFullscreenControls = stringResource(R.string.reader_show_fullscreen_controls)
    var contentsOpen by rememberSaveable(bookMode) { mutableStateOf(false) }
    val modeLabel = stringResource(if (bookMode) R.string.reader_mode_book else R.string.reader_mode_verse)
    val toggleLabel = stringResource(if (bookMode) R.string.reader_switch_to_verse else R.string.reader_switch_to_book)
    val previousLabel = stringResource(if (bookMode) R.string.reader_previous_page else when (section?.kind) {
        ReadingUnitKind.VERSE -> R.string.reader_previous_verse
        ReadingUnitKind.CHAPTER -> R.string.reader_previous_chapter
        else -> R.string.reader_previous_section
    })
    val nextLabel = stringResource(if (bookMode) R.string.reader_next_page else when (section?.kind) {
        ReadingUnitKind.VERSE -> R.string.reader_next_verse
        ReadingUnitKind.CHAPTER -> R.string.reader_next_chapter
        else -> R.string.reader_next_section
    })
    Scaffold(
        containerColor = colors.Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!fullscreen) {
            Surface(color = if (colors.dark) colors.Header else Color.Transparent, contentColor = colors.OnHeader, shadowElevation = 2.dp,
                modifier = if (colors.dark) Modifier else Modifier.background(ReadingColors.HeaderBrush)) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("reader-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, Modifier.testTag("reader-title"), fontFamily = AppFontFamily,
                                fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (contextTitle.isNotBlank()) Text(contextTitle, Modifier.testTag("reader-context"),
                                fontSize = 13.sp, lineHeight = 18.sp,
                                color = colors.OnHeader.copy(alpha = .88f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onBookmarkChange != null) IconButton(onClick = toggleBookmark,
                            enabled = canBookmark, modifier = Modifier.size(48.dp).testTag("reader-bookmark")
                                .semantics { stateDescription = bookmarkState },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.OnHeader,
                                disabledContentColor = colors.OnHeader)) {
                            Icon(painterResource(if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline),
                                bookmarkAction, Modifier.size(23.dp))
                        }
                        if (onSearch != null) IconButton(onClick = onSearch,
                            modifier = Modifier.testTag("reader-search")) {
                            Icon(Icons.Default.Search, stringResource(R.string.search_ui_this_book))
                        }
                        val modeNotice = stringResource(if (bookMode) R.string.reader_mode_now_scroll else R.string.reader_mode_now_book)
                        IconToggleButton(checked = bookMode, onCheckedChange = {
                                contentsOpen = false
                                onToggleMode()
                                scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(modeNotice) }
                            },
                            enabled = !state.loading && (!pageStatus.busy || state.failed || pageStatus.renderFailed || empty),
                            modifier = Modifier.testTag("reader-mode-toggle").semantics {
                                contentDescription = toggleLabel
                                stateDescription = modeLabel
                            }, colors = IconButtonDefaults.iconToggleButtonColors(contentColor = colors.OnHeader,
                                checkedContentColor = colors.OnHeader,
                                disabledContentColor = colors.OnHeader)) {
                            Icon(painterResource(if (bookMode) R.drawable.ic_reading_book else R.drawable.ic_reading_text),
                                contentDescription = null, modifier = Modifier.size(23.dp))
                        }
                    }
                }
            }
            }
        },
        bottomBar = {
            Column {
            if (!fullscreen) ReviewedBookReaderFooter(bookPosition, 0f,
                enabled = canNavigate, onContents = { settingsOpen = false; contentsOpen = true },
                onSettings = { contentsOpen = false; settingsOpen = true }, onFullscreen = {
                    contentsOpen = false; settingsOpen = false; fullscreenControls = false; fullscreen = true
                }, canPrevious = canNavigate && pageStatus.canPrevious, canNext = canNavigate && pageStatus.canNext,
                previousLabel = previousLabel, nextLabel = nextLabel,
                onPrevious = { host?.previous() }, onNext = { host?.next() })
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)
            .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                .padding(vertical = 16.dp) else Modifier)) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("reader-document").semantics {
                    customActions = buildList {
                        if (onBookmarkChange != null && canBookmark) add(CustomAccessibilityAction(bookmarkAction) {
                            toggleBookmark(); true
                        })
                        if (fullscreen) add(CustomAccessibilityAction(exitFullscreen) { fullscreen = false; true })
                        if (fullscreen) add(CustomAccessibilityAction(showFullscreenControls) { fullscreenControls = true; true })
                        if (canNavigate && pageStatus.canPrevious) add(CustomAccessibilityAction(previousLabel) {
                            host?.previous(); true
                        })
                        if (canNavigate && pageStatus.canNext) add(CustomAccessibilityAction(nextLabel) {
                            host?.next(); true
                        })
                    }
                },
                factory = { context -> ReaderPageHost(context, paginateBooks = true).also { host = it } },
                update = { view ->
                    view.documentDescription = description
                    view.onStatusChanged = { pageStatus = it }
                    view.onLinkUnavailable = {
                        fullscreen = false // The existing notice must remain reachable with controls restored.
                        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(unavailable) }
                    }
                    view.onLinkActivated = { link ->
                        if (onLink == null) view.onLinkUnavailable()
                        else if (!linkState.resolving && linkState.popup == null) onLink(link)
                    }
                    view.onScrollPosition = onScroll
                    view.onBookAnchor = onBookAnchor
                    view.onNextUnit = onNext
                    view.onPreviousUnit = onPrevious
                    view.onReaderTap = { if (fullscreen) fullscreenControls = !fullscreenControls }
                    view.onHighlightSelected = { selection, withNote ->
                        if (withNote) openNote(selection, matchingAnnotation(selection))
                        else {
                            closeNote()
                            onHighlightCreate?.invoke(selection, false)
                        }
                    }
                    view.onHighlightTapped = { id ->
                        val annotation = highlights.firstOrNull { it.id == id && it.location.libraryId == state.location.libraryId }
                        if (annotation != null) {
                            closeNote()
                            if (annotation.hasNote) openNote(HighlightSelection(annotation.record, annotation.start,
                                annotation.endRecord, annotation.end, annotation.text), annotation)
                            else editingHighlight = id
                        }
                    }
                    view.setHighlights(spans)
                    // Style is independent of the document identity; the host keeps it during
                    // pending loads and reflows the existing document without a source reload.
                    view.setReaderStyle(ReaderStyle(paper, lineSpacing))
                    view.submit(state, zoom, image)
                },
                onRelease = { view -> view.release() },
            )
            if (editing != null && !noteEditorOpen) {
                val id = editing.id
                HighlightPalette(editing.text, current = editing.color, note = editing.note,
                    onPick = {}, // Retained compatibility parameter, never offered by the tray.
                    onNote = { openNote(HighlightSelection(editing.record, editing.start, editing.endRecord, editing.end, editing.text), editing) },
                    onRemove = { editingHighlight = null; onHighlightRemove(id) }, onDismiss = { editingHighlight = null },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp))
            }
            if (fullscreen && fullscreenControls) {
                Surface(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                    color = colors.Paper, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp) {
                    TextButton(onClick = { fullscreen = false; fullscreenControls = false },
                        modifier = Modifier.testTag("reader-fullscreen-exit").heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.Accent)) {
                        Icon(painterResource(R.drawable.ic_reading_fullscreen_exit), null, Modifier.size(23.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(exitFullscreen)
                    }
                }
            }
            when {
                state.failed || pageStatus.renderFailed -> Surface(Modifier.fillMaxSize(), color = colors.Paper) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(when {
                                pageStatus.renderFailed -> R.string.reader_error
                                state.failure == ReadingFailure.CHOOSE_SECTION -> R.string.reader_choose_section
                                state.failure == ReadingFailure.SECTION_TOO_LARGE -> R.string.reader_large_section
                                else -> R.string.reader_error
                            }))
                            Spacer(Modifier.height(16.dp))
                            if (pageStatus.renderFailed || state.failure == ReadingFailure.LOAD) Button(onClick = {
                                if (state.failed) onRetry() else host?.retryRender()
                            }) { Text(stringResource(R.string.try_again)) }
                            else Button(onClick = onBack) { Text(stringResource(R.string.reader_back)) }
                        }
                    }
                }
                empty -> Text(stringResource(R.string.empty_page), Modifier.align(Alignment.Center).padding(24.dp))
                (state.loading || pageStatus.busy) && !pageStatus.hasPage -> ReaderLoadingIndicator(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("reader-loading"))
                waitingForPage && showPageLoading -> Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        .testTag("reader-page-loading").semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.Paper, contentColor = colors.Ink,
                    shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp,
                ) {
                    ReaderLoadingIndicator(compact = true, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
    if (contentsOpen && bookPosition != null) HierarchicalBookContentsDialog(bookPosition,
        onDismiss = { contentsOpen = false }, onSelect = { record -> contentsOpen = false; onBookEntry(record) })
    if (noteSelection != null) {
        val id = editingHighlight
        NoteEditorDialog(noteSelection.text, initialNote,
            editorId = id ?: "${state.location.libraryId}:${noteSelection.record}:${noteSelection.start}:${noteSelection.endRecord}:${noteSelection.end}",
            allowEmpty = id != null, saving = pendingNote != null,
            error = if (noteSaveFailed) stringResource(R.string.annotation_note_failed) else null,
            onSave = { note ->
                if (id != null || note.isNotBlank()) {
                    pendingNote = note; submittedNoticeId = highlightNotice?.id; noteSaveFailed = false
                    try {
                        if (id != null) onHighlightNote(id, note) else onNoteCreate(noteSelection, note)
                    } catch (_: Exception) {
                        pendingNote = null; noteSaveFailed = true
                    }
                }
            }, onDismiss = { closeNote() })
    }
    // Once open, keep editing available even while the host reflows an earlier choice.
    if (settingsOpen && !fullscreen) ReaderSettingsDialog(fontSize,
        onFontSizeChange = onFontSizeChange, onDismiss = { settingsOpen = false },
        paper = paper, onPaperChange = onPaperChange,
        lineSpacing = lineSpacing, onLineSpacingChange = onLineSpacingChange,
        defaultHighlightColor = defaultHighlightColor, onDefaultHighlightColorChange = onDefaultHighlightColorChange)
    linkState.popup?.let { popup ->
        ReaderPopupDialog(popup, state.languageTag, ReaderStyle(paper, lineSpacing), fontSize, image,
            onDismiss = onClosePopup, onLink = onPopupLink)
    }
}

/** Shared loading copy for initial documents and the retained-page pill; no layout changes to the host. */
@Composable
internal fun ReaderLoadingIndicator(modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = readerPaperColors()
    val greeting = stringResource(R.string.book_loading_greeting)
    val body = stringResource(R.string.book_loading_body)
    val accessible = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    if (compact) {
        Row(accessible, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.Accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(greeting, color = colors.Ink, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(body, color = colors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    } else {
        Column(accessible, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(30.dp), color = colors.Accent)
            Spacer(Modifier.height(16.dp))
            Text(greeting, color = colors.Ink, fontSize = 18.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(body, Modifier.padding(top = 4.dp), color = colors.Muted,
                fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ReviewedBookReaderFooter(
    position: BookPosition?,
    pageProgress: Float,
    enabled: Boolean,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
    canPrevious: Boolean,
    canNext: Boolean,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = readerPaperColors()
    val book = position?.outline?.book
    val selectedEntry = position?.outline?.entries?.get(position.index)?.node
    // Approximate source-record progress, not a screen-page or printed-page count.
    val bookSpan = if (book != null) (book.end.toLong() - book.record + 1).coerceAtLeast(1L) else 1L
    val entrySpan = if (selectedEntry != null) (selectedEntry.end.toLong() - selectedEntry.record + 1).coerceAtLeast(1L) else 0L
    val completed = if (selectedEntry != null && book != null) selectedEntry.record.toLong() - book.record else 0L
    val progress = ((completed + entrySpan * pageProgress.coerceIn(0f, 1f)) / bookSpan).coerceIn(0f, 1f)
    val percent = (progress * 100).toInt()
    val progressDescription = stringResource(R.string.reader_font_preview_progress_description, percent)
    val settingsLabel = stringResource(R.string.reader_settings)
    Surface(color = colors.Paper, contentColor = colors.Ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (position != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = colors.Accent, trackColor = colors.Chapter)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = canPrevious,
                    modifier = Modifier.size(48.dp).testTag("reader-page-previous"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent,
                        disabledContentColor = colors.Accent)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, previousLabel)
                }
                if (position != null) {
                    IconButton(onClick = onContents, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("reader-book-contents"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent,
                            disabledContentColor = colors.Accent)) {
                        Icon(Icons.Default.Menu, stringResource(R.string.reader_book_contents))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reader_book_percent, percent),
                            Modifier.testTag("reader-book-progress").semantics { contentDescription = progressDescription },
                            color = colors.Muted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings, enabled = enabled,
                    modifier = Modifier.size(48.dp).testTag("reader-text-size"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent,
                        disabledContentColor = colors.Accent)) {
                    Icon(painterResource(R.drawable.ic_reading_settings), settingsLabel, Modifier.size(23.dp))
                }
                IconButton(onClick = onFullscreen, enabled = enabled, modifier = Modifier.testTag("reader-fullscreen"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent,
                        disabledContentColor = colors.Accent)) {
                    Icon(painterResource(R.drawable.ic_reading_fullscreen), stringResource(R.string.reader_enter_fullscreen),
                        modifier = Modifier.size(23.dp))
                }
                IconButton(onClick = onNext, enabled = canNext,
                    modifier = Modifier.size(48.dp).testTag("reader-page-next"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent,
                        disabledContentColor = colors.Accent)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, nextLabel)
                }
            }
        }
    }
}
