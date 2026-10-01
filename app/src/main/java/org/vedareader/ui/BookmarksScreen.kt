package org.vedareader.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.vedareader.R
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkIssue
import org.vedareader.data.Highlight
import org.vedareader.data.LibraryInfo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.Color
import org.vedareader.data.SearchText
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Presentation only. The owner retains the full store and the library's browse/search state. */
@Composable
fun BookmarksScreen(
    state: BookmarkUiState,
    library: LibraryInfo?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpen: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
    onRetry: () -> Unit,
    onFeedbackShown: (Long) -> Unit,
    highlights: HighlightUiState = HighlightUiState(),
    onOpenHighlight: (Highlight) -> Unit = {},
    onRemoveHighlight: (Highlight) -> Unit = {},
    onRetryHighlights: () -> Unit = {},
    tab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    onMore: () -> Unit = {},
    onHome: (() -> Unit)? = null,
    onEditNote: (String, String) -> Unit = { _, _ -> },
    onRemoveNote: (Highlight) -> Unit = {},
) {
    val libraryId = library?.id
    // Neither the editor nor its subtree is keyed by the query/results. Preserve IME composition
    // and selection across filtering, loading, feedback, and equal store refreshes.
    var query by rememberSaveable(libraryId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val indexed = remember(state.items, libraryId) {
        state.items.asSequence()
            .filter { libraryId != null && it.location.libraryId == libraryId }
            .sortedWith(compareByDescending<Bookmark> { it.createdAt }.thenBy { it.id })
            .map { bookmark ->
                IndexedBookmark(bookmark, SearchText.normalize(
                    "${bookmark.bookTitle}\n${bookmark.location.title}\n${bookmark.excerpt}",
                ))
            }.toList()
    }
    val filter = remember(query.text) { SearchText.normalize(query.text).trim() }
    val visible = remember(indexed, filter) {
        if (filter.isEmpty()) indexed else indexed.filter { filter in it.normalized }
    }
    // One heading per book, books ordered by their newest bookmark; rows inside stay newest first.
    // Titles are display text, never identity: two source books may have the same title.
    val grouped = remember(visible) {
        visible.groupBy { it.bookmark.location.libraryId to it.bookmark.bookRecord }
    }
    val list = key(libraryId) { rememberLazyListState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val leaveEditor: () -> Unit = { focus.clearFocus(); keyboard?.hide() }
    val back: () -> Unit = { leaveEditor(); onBack() }
    val search: () -> Unit = { leaveEditor(); onSearch() }
    val home: () -> Unit = { leaveEditor(); (onHome ?: onBack)() }
    val more: () -> Unit = { leaveEditor(); onMore() }
    val actionable = library != null && !state.loading && !state.busy && state.error == null

    // Keep the original object, not a reconstructed location or an index into a filtered list.
    // Confirmation is deliberately not restored after recreation or a library switch.
    var pending by remember(libraryId) { mutableStateOf<Bookmark?>(null) }
    val removal = pending?.takeIf { candidate ->
        candidate.location.libraryId == libraryId && indexed.any {
            it.bookmark.id == candidate.id && it.bookmark == candidate
        }
    }
    LaunchedEffect(pending, removal) {
        if (pending != null && removal == null) pending = null
    }
    // AlertDialog owns native Back while visible; never let it also navigate the root screen.
    BackHandler(enabled = removal == null, onBack = back)

    val snackbar = remember { SnackbarHostState() }
    BookmarkFeedbackEffect(state.feedback, snackbar, onFeedbackShown)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("bookmarks-screen"),
        containerColor = ReadingColors.Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { BookmarksHeader(library, tab, back) },
        bottomBar = { AppNavigationBar(AppDestination.BOOKMARKS, home, search, more, tagPrefix = "bookmarks") },
        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("bookmarks-snackbar")) },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                if (library != null) {
                    TabRow(selectedTabIndex = tab.coerceIn(0, 2), containerColor = ReadingColors.Paper, contentColor = ReadingColors.Header,
                        modifier = Modifier.testTag("bookmarks-tabs")) {
                        Tab(selected = tab == 0, onClick = { leaveEditor(); onTabChange(0) }, modifier = Modifier.testTag("bookmarks-tab-bookmarks"),
                            text = { Text(stringResource(R.string.bookmarks_tab)) })
                        Tab(selected = tab == 1, onClick = { leaveEditor(); onTabChange(1) }, modifier = Modifier.testTag("bookmarks-tab-highlights"),
                            text = { Text(stringResource(R.string.highlights_tab)) })
                        Tab(selected = tab == 2, onClick = { leaveEditor(); onTabChange(2) }, modifier = Modifier.testTag("bookmarks-tab-notes"),
                            text = { Text(stringResource(R.string.notes_tab)) })
                    }
                }
                if (library != null && tab in 1..2) {
                    HighlightsList(highlights, library.id, dateFormat,
                        onOpen = { leaveEditor(); onOpenHighlight(it) },
                        onRemove = if (tab == 2) onRemoveNote else onRemoveHighlight,
                        onRetry = onRetryHighlights, notesOnly = tab == 2, onEditNote = onEditNote,
                        modifier = Modifier.weight(1f))
                } else {
                if (library != null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("bookmarks-filter"),
                        label = { Text(stringResource(R.string.bookmark_filter)) },
                        placeholder = { Text(stringResource(R.string.bookmark_filter_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.text.isNotEmpty()) IconButton(
                                onClick = { query = TextFieldValue("") },
                                modifier = Modifier.size(48.dp).testTag("bookmarks-filter-clear"),
                            ) { Icon(Icons.Default.Close, stringResource(R.string.bookmark_filter_clear)) }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { leaveEditor() }),
                    )
                }
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("bookmarks-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (library == null) {
                        item(key = "import") {
                            BookmarkNotice(R.string.bookmark_import_title, R.string.bookmark_import_body, "bookmarks-import") {
                                TextButton(onClick = home, modifier = Modifier.heightIn(min = 48.dp).testTag("bookmarks-import-library")) {
                                    Text(stringResource(R.string.bookmark_back_to_library))
                                }
                            }
                        }
                    } else {
                        if (state.loading || state.busy) item(key = "progress") {
                            BookmarkProgress(
                                if (state.loading) R.string.bookmark_loading else R.string.bookmark_updating,
                                if (state.loading) "bookmarks-loading" else "bookmarks-busy",
                            )
                        }
                        if (state.error != null) item(key = "error") {
                            BookmarkNotice(R.string.bookmark_error_title, bookmarkIssueResource(state.error), "bookmarks-error") {
                                TextButton(
                                    onClick = onRetry,
                                    enabled = !state.loading && !state.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("bookmarks-retry"),
                                ) { Text(stringResource(R.string.bookmark_retry)) }
                            }
                        }
                        if (!state.loading) {
                            if (visible.isNotEmpty()) item(key = "order") {
                                Text(stringResource(R.string.bookmark_newest_first),
                                    style = MaterialTheme.typography.labelMedium, color = ReadingColors.Muted,
                                    modifier = Modifier.testTag("bookmarks-order"))
                            }
                            grouped.forEach { (book, entries) ->
                                val bookKey = "${book.first}:${book.second}"
                                item(key = "book:$bookKey") {
                                    Box(Modifier.testTag("bookmarks-book-$bookKey")) {
                                        BookHeading(entries.first().bookmark.bookTitle, entries.size, bookKey)
                                    }
                                }
                                items(entries, key = { "bookmark:${it.bookmark.id}" }) { entry ->
                                    val bookmark = entry.bookmark
                                    val date = remember(bookmark.createdAt, dateFormat) { dateFormat.format(Date(bookmark.createdAt)) }
                                    BookmarkRow(bookmark, date, actionable,
                                        onOpen = { if (actionable) { leaveEditor(); onOpen(bookmark) } },
                                        onRemove = { if (actionable) { leaveEditor(); pending = bookmark } },
                                    )
                                }
                            }
                            if (!state.busy && state.error == null && visible.isEmpty()) item(key = "empty") {
                                if (indexed.isEmpty()) {
                                    BookmarkNotice(R.string.bookmark_empty_title, R.string.bookmark_empty_body, "bookmarks-empty")
                                } else {
                                    BookmarkNotice(R.string.bookmark_filter_empty_title, R.string.bookmark_filter_empty_body,
                                        "bookmarks-filter-empty") {
                                        TextButton(onClick = { query = TextFieldValue("") },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("bookmarks-reset-filter")) {
                                            Text(stringResource(R.string.bookmark_filter_clear))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (removal != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            modifier = Modifier.testTag("bookmarks-remove-dialog"),
            icon = { Icon(painterResource(R.drawable.ic_bookmark_outline), contentDescription = null) },
            title = { Text(stringResource(R.string.bookmark_remove_title)) },
            text = { Text(stringResource(R.string.bookmark_remove_body, removal.location.title, removal.bookTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Revalidate the id, library AND snapshot; a stale dialog cannot remove
                        // a replacement entry, a disappeared entry, or an entry in another library.
                        if (pending === removal && actionable && libraryId == removal.location.libraryId && state.items.any {
                                it.id == removal.id && it.location.libraryId == libraryId && it == removal
                            }) {
                            pending = null
                            onRemove(removal)
                        }
                    },
                    enabled = actionable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("bookmarks-remove-confirm"),
                ) { Text(stringResource(R.string.bookmark_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("bookmarks-remove-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private data class IndexedBookmark(val bookmark: Bookmark, val normalized: String)

/** Parent heading for one book's bookmarks: saffron bar, serif title, count badge. */
@Composable
private fun BookHeading(title: String, count: Int, bookKey: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("bookmarks-book-heading"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(ReadingColors.Header))
        Spacer(Modifier.width(10.dp))
        Text(title.ifBlank { stringResource(R.string.bookmark_unnamed_library) }, Modifier.weight(1f), fontFamily = BookFontFamily,
            style = MaterialTheme.typography.titleMedium, color = ReadingColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Surface(color = ReadingColors.Chapter, contentColor = ReadingColors.Accent, shape = RoundedCornerShape(10.dp)) {
            Text(count.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp).testTag("bookmarks-book-count-$bookKey"),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Filled highlights and notes are independent views over the owner's annotation snapshots. */
@Composable
private fun HighlightsList(
    state: HighlightUiState,
    libraryId: String,
    dateFormat: DateFormat,
    onOpen: (Highlight) -> Unit,
    onRemove: (Highlight) -> Unit,
    onRetry: () -> Unit,
    notesOnly: Boolean = false,
    onEditNote: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val items = remember(state.items, libraryId, notesOnly) {
        state.items.filter { it.location.libraryId == libraryId && (if (notesOnly) it.hasNote else it.highlighted) }
            .sortedWith(compareByDescending<Highlight> { it.createdAt }.thenBy { it.id })
    }
    val list = key(libraryId, notesOnly) { rememberLazyListState() }
    var pending by remember(libraryId, notesOnly) { mutableStateOf<Highlight?>(null) }
    val removal = pending?.takeIf { candidate -> items.any { it == candidate } }
    LaunchedEffect(pending, removal) { if (pending != null && removal == null) pending = null }
    val actionable = !state.loading && !state.busy && !state.failed

    // Persist only editor identity/draft metadata, never a stale annotation object to write back.
    var editingId by rememberSaveable(libraryId, notesOnly) { mutableStateOf<String?>(null) }
    var initialNote by rememberSaveable(libraryId, notesOnly) { mutableStateOf("") }
    var excerpt by rememberSaveable(libraryId, notesOnly) { mutableStateOf("") }
    var submittedNote by rememberSaveable(libraryId, notesOnly) { mutableStateOf<String?>(null) }
    var submittedNoticeId by rememberSaveable(libraryId, notesOnly) { mutableStateOf<Long?>(null) }
    var wasFailed by rememberSaveable(libraryId, notesOnly) { mutableStateOf(false) }
    var sawBusy by rememberSaveable(libraryId, notesOnly) { mutableStateOf(false) }
    var saveFailed by rememberSaveable(libraryId, notesOnly) { mutableStateOf(false) }
    val closeEditor: () -> Unit = { editingId = null; submittedNote = null; saveFailed = false }
    LaunchedEffect(submittedNote, state.items, state.loading, state.busy, state.failed, state.notice?.id) {
        val submitted = submittedNote ?: return@LaunchedEffect
        if (state.busy) sawBusy = true
        val committed = state.items.firstOrNull { it.id == editingId && it.location.libraryId == libraryId }
        if (!state.loading && !state.busy) {
            when {
                committed?.note == submitted || (submitted.isEmpty() && committed == null) -> closeEditor()
                (state.notice?.id != submittedNoticeId && state.notice?.message == HighlightMessage.FAILED) ||
                    (state.failed && (!wasFailed || sawBusy)) -> {
                    submittedNote = null
                    saveFailed = true
                }
            }
        }
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().testTag("highlights-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading || state.busy) item(key = "progress") {
            BookmarkProgress(if (state.loading) R.string.bookmark_loading else R.string.bookmark_updating,
                if (state.loading) "highlights-loading" else "highlights-busy")
        }
        if (state.failed) item(key = "error") {
            BookmarkNotice(R.string.bookmark_error_title, R.string.bookmark_load_error, "highlights-error") {
                TextButton(onClick = onRetry, enabled = !state.loading && !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("highlights-retry")) { Text(stringResource(R.string.bookmark_retry)) }
            }
        }
        if (!state.loading) {
            items(items, key = { "highlight:${it.id}" }) { highlight ->
                val date = remember(highlight.createdAt, dateFormat) { dateFormat.format(Date(highlight.createdAt)) }
                HighlightRow(highlight, date, actionable, notesOnly,
                    onOpen = { if (actionable) onOpen(highlight) },
                    onRemove = { if (actionable) pending = highlight },
                    onEdit = {
                        if (actionable) {
                            initialNote = highlight.note; excerpt = highlight.text; editingId = highlight.id
                            submittedNote = null; saveFailed = false
                        }
                    })
            }
            if (!state.busy && !state.failed && items.isEmpty()) item(key = "empty") {
                if (notesOnly) BookmarkNotice(R.string.notes_empty_title, R.string.notes_empty_body, "notes-empty")
                else BookmarkNotice(R.string.highlights_empty_title, R.string.highlights_empty_body, "highlights-empty")
            }
        }
    }
    if (removal != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            modifier = Modifier.testTag("highlights-remove-dialog"),
            title = { Text(stringResource(if (notesOnly) R.string.annotation_remove_note_title else R.string.highlight_remove_title)) },
            text = { Text(stringResource(if (notesOnly) R.string.annotation_remove_note_body else R.string.annotation_remove_fill_body,
                removal.location.title)) },
            confirmButton = {
                TextButton(onClick = {
                    if (pending === removal && actionable && state.items.any { it == removal && it.location.libraryId == libraryId }) {
                        pending = null
                        onRemove(removal)
                    }
                }, enabled = actionable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("highlights-remove-confirm")) {
                    Text(stringResource(if (notesOnly) R.string.annotation_remove_note else R.string.annotation_remove_highlight))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("highlights-remove-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    editingId?.let { id ->
        NoteEditorDialog(excerpt, initialNote, editorId = "$libraryId:$id", allowEmpty = true,
            saving = submittedNote != null || state.loading || state.busy,
            error = if (saveFailed) stringResource(R.string.annotation_note_failed) else null,
            onSave = { note ->
                val current = state.items.firstOrNull { it.id == id && it.location.libraryId == libraryId }
                if (submittedNote == null && !state.loading && !state.busy) {
                    when {
                        current == null -> saveFailed = true
                        current.note == note -> closeEditor()
                        else -> {
                            submittedNote = note; submittedNoticeId = state.notice?.id
                            wasFailed = state.failed; sawBusy = false; saveFailed = false
                            try {
                                onEditNote(id, note)
                            } catch (_: Exception) {
                                submittedNote = null; saveFailed = true
                            }
                        }
                    }
                }
            }, onDismiss = closeEditor)
    }
}

@Composable
private fun HighlightRow(highlight: Highlight, date: String, actionable: Boolean, notesOnly: Boolean,
    onOpen: () -> Unit, onRemove: () -> Unit, onEdit: () -> Unit) {
    val marker = highlightColor(highlight.color, dark = false)
    val open = stringResource(R.string.highlight_open)
    Surface(color = ReadingColors.Paper, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ReadingColors.Rule),
        modifier = Modifier.fillMaxWidth().testTag("highlight-${highlight.id}")) {
        // Opening the passage, editing a note and removing are sibling targets, never nested.
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(enabled = actionable, role = Role.Button, onClickLabel = open, onClick = onOpen)
                .testTag("highlight-open-${highlight.id}").padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (highlight.highlighted) Box(Modifier.size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(3.dp)).background(marker))
                Text(highlight.location.title.ifBlank { highlight.bookTitle }, style = MaterialTheme.typography.titleSmall,
                    color = ReadingColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(highlight.text, style = MaterialTheme.typography.bodyMedium, color = ReadingColors.Ink,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("highlight-text-${highlight.id}"))
                if (highlight.hasNote) Surface(color = Color(0xFFF4EEF8), contentColor = Color(0xFF4A3462), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("highlight-note-${highlight.id}")) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(highlight.note, style = MaterialTheme.typography.bodyMedium, fontFamily = BookFontFamily,
                            maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(listOf(highlight.bookTitle, date).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.labelSmall, color = ReadingColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(top = 4.dp)) {
                if (notesOnly) IconButton(onClick = onEdit, enabled = actionable,
                    modifier = Modifier.size(48.dp).testTag("note-edit-${highlight.id}")) {
                    Icon(Icons.Default.Edit, stringResource(R.string.note_edit), tint = ReadingColors.Header)
                }
                IconButton(onClick = onRemove, enabled = actionable,
                    modifier = Modifier.size(48.dp).testTag("highlight-remove-${highlight.id}")) {
                    Icon(Icons.Default.Delete, stringResource(if (notesOnly) R.string.annotation_remove_note
                        else R.string.annotation_remove_highlight), tint = ReadingColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun BookmarksHeader(library: LibraryInfo?, tab: Int, onBack: () -> Unit) {
    Surface(color = androidx.compose.ui.graphics.Color.Transparent, contentColor = ReadingColors.OnHeader,
        modifier = Modifier.background(ReadingColors.HeaderBrush)) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("bookmarks-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(when (tab) {
                    1 -> R.string.highlights_tab
                    2 -> R.string.notes_tab
                    else -> R.string.bookmark_title
                }), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("bookmarks-title").semantics { heading() })
                Text(
                    if (library == null) stringResource(R.string.bookmark_no_library)
                    else library.title.ifBlank { stringResource(R.string.bookmark_unnamed_library) },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("bookmarks-library"),
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, date: String, enabled: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val openLabel = stringResource(R.string.bookmark_open_location, bookmark.location.title, bookmark.bookTitle)
    // The passage text is not repeated here; the reference, book and saved time identify the entry.
    val showBook = bookmark.bookTitle.isNotBlank() && !bookmark.bookTitle.equals(bookmark.location.title, ignoreCase = true)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("bookmark-row-${bookmark.id}"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        contentColor = ReadingColors.Ink, border = BorderStroke(1.dp, ReadingColors.Rule),
    ) {
        // Siblings, not nested click targets. The full text/padding area opens; only the 48dp
        // trailing button asks to remove. No swipe, long-press, or bulk destructive action.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                    .testTag("bookmark-open-${bookmark.id}").padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(bookmark.location.title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("bookmark-location-${bookmark.id}"))
                if (showBook) Text(bookmark.bookTitle, style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("bookmark-book-${bookmark.id}"))
                Text(stringResource(R.string.bookmark_saved_date, date), style = MaterialTheme.typography.bodySmall,
                    color = ReadingColors.Muted, modifier = Modifier.testTag("bookmark-date-${bookmark.id}"))
            }
            IconButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.padding(end = 8.dp).size(48.dp).testTag("bookmark-remove-${bookmark.id}")) {
                Icon(Icons.Default.Delete,
                    stringResource(R.string.bookmark_remove_location, bookmark.location.title, bookmark.bookTitle))
            }
        }
    }
}

@Composable
private fun BookmarkNotice(@StringRes title: Int, @StringRes body: Int, tag: String,
    actions: @Composable () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, ReadingColors.Rule)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_bookmark_outline), contentDescription = null,
                tint = ReadingColors.Accent, modifier = Modifier.size(32.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            actions()
        }
    }
}

@Composable
private fun BookmarkProgress(@StringRes label: Int, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(tag)
        .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@StringRes
private fun bookmarkIssueResource(issue: BookmarkIssue): Int = when (issue) {
    BookmarkIssue.LOAD -> R.string.bookmark_load_error
    BookmarkIssue.SAVE -> R.string.bookmark_save_error
    BookmarkIssue.LIMIT -> R.string.bookmark_limit_error
}

/** Shared with reader chrome; this is an ordinary function, not a composable. */
@StringRes
fun bookmarkMessageResource(message: BookmarkMessage): Int = when (message) {
    BookmarkMessage.SAVED -> R.string.bookmark_saved
    BookmarkMessage.REMOVED -> R.string.bookmark_removed
    BookmarkMessage.LOAD_FAILED -> R.string.bookmark_load_error
    BookmarkMessage.SAVE_FAILED -> R.string.bookmark_save_error
    BookmarkMessage.LIMIT_REACHED -> R.string.bookmark_limit_error
}

/** Only completed snackbar presentation is acknowledged; disposal/replacement keeps it pending. */
@Composable
internal fun BookmarkFeedbackEffect(
    feedback: BookmarkFeedback?,
    snackbarHostState: SnackbarHostState,
    onFeedbackShown: (Long) -> Unit,
) {
    val acknowledge by rememberUpdatedState(onFeedbackShown)
    val message = feedback?.let { stringResource(bookmarkMessageResource(it.message)) }
    LaunchedEffect(feedback?.id, snackbarHostState) {
        if (feedback != null && message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            // Not a finally block: cancellation when leaving the screen must not consume feedback.
            acknowledge(feedback.id)
        }
    }
}