package org.vedareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.vedareader.R
import org.vedareader.data.BookPosition
import org.vedareader.data.ContentObject
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderPageStatus
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode

@Composable
fun ReaderScreen(
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
) {
    // A verse change must not recreate the native host. Closing the reader ends this session.
    key(state.location.libraryId, state.languageTag) {
        ReaderSessionScreen(state, onBack, onNext, onPrevious, onRetry, onScroll, image,
            onToggleMode, onBookEntry, onBookAnchor)
    }
}

@Composable
private fun ReaderSessionScreen(
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
) {
    BackHandler { onBack() }
    var host by remember { mutableStateOf<ReaderPageHost?>(null) }
    var pageStatus by remember { mutableStateOf(ReaderPageStatus()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.reader_link_later)
    val title = state.location.title.ifBlank { stringResource(R.string.browse_untitled, state.location.start) }
    val section = state.document?.section
    val bookMode = state.mode == ReadingMode.BOOK
    val bookPosition = state.document?.let { it.bookResource?.position ?: it.bookPosition }
    // A chapter resource retains its original selection. Follow the painted source anchor,
    // falling back to the controller's saved anchor until a BOOK page is ready.
    val anchorRecord = pageStatus.anchorRecord.takeIf {
        bookMode && pageStatus.hasPage && it > 0 && it in state.location.start..state.location.end
    } ?: state.location.position
    val liveBookPosition = remember(bookPosition, anchorRecord) {
        bookPosition?.let { position ->
            val index = position.outline.entries.binarySearch {
                when {
                    it.node.end < anchorRecord -> -1
                    it.node.record > anchorRecord -> 1
                    else -> 0
                }
            }
            if (index >= 0) position.copy(index = index) else position
        }
    }
    var lastContext by remember { mutableStateOf("") }
    val sourceContext = if (bookMode && liveBookPosition != null) {
        liveBookPosition.outline.entries[liveBookPosition.index].parentTitle
    } else section?.parentTitle
    val contextTitle = sourceContext ?: if (state.loading) lastContext else ""
    SideEffect { if (sourceContext != null) lastContext = sourceContext }
    val empty = !state.loading && !state.failed && section?.paragraphs.isNullOrEmpty()
    val description = stringResource(R.string.reader_document)
    val zoom = (LocalDensity.current.fontScale * 100).toInt().coerceIn(80, 300)
    val canNavigate = !state.loading && !state.failed && !pageStatus.renderFailed &&
        !pageStatus.busy && pageStatus.hasPage && !empty
    var contentsOpen by rememberSaveable(bookMode) { mutableStateOf(false) }
    var chromeVisible by rememberSaveable(bookMode) { mutableStateOf(true) }
    val needsChrome = state.loading || state.failed || pageStatus.renderFailed || empty || !pageStatus.hasPage
    val controlsVisible = !bookMode || needsChrome || chromeVisible
    LaunchedEffect(bookMode, state.loading, state.failed, pageStatus.renderFailed, empty) {
        if (!bookMode || state.loading || state.failed || pageStatus.renderFailed || empty) {
            chromeVisible = true
            contentsOpen = false
        }
    }
    var controlsHintShown by rememberSaveable { mutableStateOf(false) }
    val controlsHint = stringResource(R.string.reader_book_controls_hint)
    LaunchedEffect(bookMode, canNavigate, controlsVisible) {
        if (bookMode && canNavigate && controlsVisible && !controlsHintShown) {
            controlsHintShown = true
            snackbar.showSnackbar(controlsHint)
        }
    }
    val toggleChrome: () -> Unit = {
        if (bookMode && canNavigate) {
            chromeVisible = !chromeVisible
            snackbar.currentSnackbarData?.dismiss()
        }
    }
    val chromeActionLabel = stringResource(
        if (controlsVisible) R.string.reader_hide_controls else R.string.reader_show_controls)
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
        containerColor = ReadingColors.Paper, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val headerInk = if (bookMode) ReadingColors.Ink else ReadingColors.OnHeader
            Surface(modifier = Modifier.readerChromeVisibility(controlsVisible),
                color = if (bookMode) ReadingColors.Paper else ReadingColors.Header,
                contentColor = headerInk, shadowElevation = if (bookMode) 0.dp else 2.dp) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, enabled = controlsVisible, modifier = Modifier.testTag("reader-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back))
                        }
                        if (bookMode) IconButton(onClick = { contentsOpen = true },
                            enabled = controlsVisible && canNavigate && liveBookPosition != null,
                            modifier = Modifier.testTag("reader-book-contents")) {
                            Icon(Icons.Filled.Menu, stringResource(R.string.reader_book_contents))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, Modifier.testTag("reader-title"), fontFamily = AppFontFamily,
                                fontWeight = if (bookMode) FontWeight.Medium else FontWeight.SemiBold,
                                fontSize = if (bookMode) 16.sp else 19.sp, lineHeight = if (bookMode) 20.sp else 24.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Reserve the subtitle line in BOOK even at a heading with no parent.
                            // Anchor updates must not change the native column's viewport height.
                            if (bookMode || contextTitle.isNotBlank()) Text(contextTitle,
                                if (contextTitle.isNotBlank()) Modifier.testTag("reader-context") else Modifier.clearAndSetSemantics {},
                                fontSize = if (bookMode) 12.sp else 13.sp, lineHeight = if (bookMode) 16.sp else 18.sp,
                                color = if (bookMode) ReadingColors.Muted else ReadingColors.OnHeader.copy(alpha = .88f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { chromeVisible = true; contentsOpen = false; onToggleMode() },
                            enabled = controlsVisible && !state.loading && (!pageStatus.busy || state.failed || pageStatus.renderFailed || empty),
                            modifier = Modifier.testTag("reader-mode-toggle").semantics {
                                contentDescription = toggleLabel
                                stateDescription = modeLabel
                            }, colors = ButtonDefaults.textButtonColors(contentColor = headerInk,
                                disabledContentColor = headerInk.copy(alpha = .5f))) {
                            Text(modeLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Keep this slot while loading as well as while hidden: neither may resize the page.
            if (bookMode) BookReaderFooter(liveBookPosition, controlsVisible, canNavigate, pageStatus,
                onPrevious = { host?.previous() }, onNext = { host?.next() }, onBookEntry = onBookEntry)
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("reader-document").semantics {
                    // These actions remain reachable when BOOK chrome is hidden.
                    customActions = buildList {
                        if (bookMode && canNavigate) add(CustomAccessibilityAction(chromeActionLabel) {
                            toggleChrome(); true
                        })
                        if (canNavigate && pageStatus.canPrevious) add(CustomAccessibilityAction(previousLabel) {
                            host?.previous(); true
                        })
                        if (canNavigate && pageStatus.canNext) add(CustomAccessibilityAction(nextLabel) {
                            host?.next(); true
                        })
                    }
                },
                factory = { context -> ReaderPageHost(context).also { host = it } },
                update = { view ->
                    view.documentDescription = description
                    view.onStatusChanged = { pageStatus = it }
                    view.onLinkUnavailable = {
                        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(unavailable) }
                    }
                    view.onScrollPosition = onScroll
                    view.onNextUnit = onNext
                    view.onPreviousUnit = onPrevious
                    view.onBookAnchor = onBookAnchor
                    view.onReaderTap = toggleChrome
                    view.submit(state, zoom, image)
                },
                onRelease = { view -> view.release() },
            )
            when {
                state.failed || pageStatus.renderFailed -> Surface(Modifier.fillMaxSize(), color = ReadingColors.Paper) {
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
                (state.loading || pageStatus.busy) && !pageStatus.hasPage -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(30.dp))
                    Text(stringResource(R.string.reader_loading), Modifier.padding(top = 16.dp))
                }
            }
        }
    }
    if (contentsOpen && bookMode && liveBookPosition != null) BookContentsDialog(liveBookPosition,
        onDismiss = { contentsOpen = false }, onSelect = { record -> contentsOpen = false; onBookEntry(record) })
}

/** Measure exactly the same controls/insets, but do not place, draw or hit-test hidden content. */
private fun Modifier.readerChromeVisibility(visible: Boolean): Modifier =
    then(if (visible) Modifier else Modifier.clearAndSetSemantics {}).layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible) placeable.placeRelative(0, 0)
        }
    }

@Composable
private fun BookReaderFooter(
    position: BookPosition?,
    controlsVisible: Boolean,
    canNavigate: Boolean,
    pageStatus: ReaderPageStatus,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBookEntry: (Int) -> Unit,
) {
    val entries = position?.outline?.entries.orEmpty()
    val currentIndex = position?.index ?: 0
    // Dragging previews a real outline location locally; only release requests a document.
    var seekPreview by remember(position?.outline, currentIndex, canNavigate, controlsVisible) {
        mutableStateOf<Float?>(null)
    }
    val lastIndex = entries.lastIndex.coerceAtLeast(0)
    val value = (seekPreview ?: currentIndex.toFloat()).coerceIn(0f, lastIndex.toFloat())
    val previewIndex = value.roundToInt()
    val locationLabel = if (entries.isEmpty()) stringResource(R.string.reader_book_location_unavailable)
        else stringResource(R.string.reader_book_progress, previewIndex + 1, entries.size)
    val progressDescription = if (entries.isEmpty()) locationLabel
        else stringResource(R.string.reader_book_progress_description, previewIndex + 1, entries.size)
    val seekLabel = stringResource(R.string.reader_book_seek)
    val seekEnabled = controlsVisible && canNavigate && entries.size > 1
    Surface(modifier = Modifier.readerChromeVisibility(controlsVisible),
        color = ReadingColors.Paper, contentColor = ReadingColors.Ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(locationLabel, Modifier.testTag("reader-book-progress").semantics {
                contentDescription = progressDescription
            }, fontSize = 12.sp, lineHeight = 18.sp, color = ReadingColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = controlsVisible && canNavigate && pageStatus.canPrevious,
                    modifier = Modifier.testTag("reader-book-previous")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.reader_previous_page))
                }
                Slider(value = value, onValueChange = { seekPreview = it },
                    onValueChangeFinished = {
                        val target = seekPreview?.roundToInt()?.coerceIn(0, lastIndex)
                        seekPreview = null
                        if (seekEnabled && target != null) onBookEntry(entries[target].node.record)
                    }, enabled = seekEnabled, valueRange = 0f..lastIndex.coerceAtLeast(1).toFloat(),
                    steps = (entries.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f).testTag("reader-book-seek").semantics {
                        contentDescription = seekLabel
                        stateDescription = progressDescription
                    }, colors = SliderDefaults.colors(thumbColor = ReadingColors.Accent,
                        activeTrackColor = ReadingColors.Accent, inactiveTrackColor = ReadingColors.Rule,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                        disabledThumbColor = ReadingColors.Muted.copy(alpha = .5f),
                        disabledActiveTrackColor = ReadingColors.Accent.copy(alpha = .38f),
                        disabledInactiveTrackColor = ReadingColors.Rule.copy(alpha = .5f),
                        disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent))
                IconButton(onClick = onNext, enabled = controlsVisible && canNavigate && pageStatus.canNext,
                    modifier = Modifier.testTag("reader-book-next")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.reader_next_page))
                }
            }
        }
    }
}

@Composable
private fun BookContentsDialog(position: BookPosition, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    // First chunk per real source heading; no duplicate titles for chunked prose.
    val entries = remember(position.outline) { position.outline.entries.distinctBy { it.source.record } }
    val selectedSource = position.outline.entries[position.index].source.record
    val listState = rememberLazyListState(initialFirstVisibleItemIndex =
        entries.indexOfFirst { it.source.record == selectedSource }.coerceAtLeast(0))
    AlertDialog(onDismissRequest = onDismiss, containerColor = ReadingColors.Paper,
        titleContentColor = ReadingColors.Ink, textContentColor = ReadingColors.Ink,
        title = { Text(position.outline.book.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 460.dp).testTag("reader-book-contents-list")) {
                items(entries, key = { it.node.record }) { entry ->
                    TextButton(onClick = { onSelect(entry.node.record) }, modifier = Modifier.fillMaxWidth()
                        .testTag("book-entry-${entry.node.record}").semantics { selected = entry.source.record == selectedSource },
                        colors = ButtonDefaults.textButtonColors(contentColor = ReadingColors.Ink),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                        Text(entry.source.displayTitle.ifBlank { stringResource(R.string.browse_untitled, entry.source.record) },
                            Modifier.fillMaxWidth(), fontWeight = if (entry.source.record == selectedSource) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = ReadingColors.Accent)) {
                Text(stringResource(R.string.reader_close_contents))
            }
        })
}