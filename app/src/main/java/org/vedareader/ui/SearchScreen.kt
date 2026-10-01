package org.vedareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.vedareader.R
import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchBook
import org.vedareader.data.SearchBookSelection
import org.vedareader.data.SearchIssue
import org.vedareader.data.SearchMode
import androidx.compose.foundation.background
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget

private const val LEVEL_INDENT = 20

/** Presentation only: typing never starts a search or reads the database here. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onBooks: (Set<Int>) -> Unit,
    onMode: (SearchMode) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (SearchSuggestion) -> Unit,
    onOpenHit: (ScopedSearchHit) -> Unit,
    onLoadMore: () -> Unit,
    onRetryCatalog: () -> Unit,
    onCancel: () -> Unit,
    onClearHistory: () -> Unit,
    onTarget: (SearchTarget) -> Unit = {},
    onHome: (() -> Unit)? = null,
    onBookmarks: ((Int) -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    var showScope by rememberSaveable(state.libraryId) { mutableStateOf(false) }
    var confirmClearHistory by rememberSaveable(state.libraryId) { mutableStateOf(false) }
    val catalog = remember(state.books) { SearchBookSelection(state.books) }
    val selectedRoots = remember(catalog, state.selectedBooks) { catalog.canonical(state.selectedBooks) }
    // Own the editor value outside lazy items/keys. Equal model echoes must not reset selection
    // or accept (and thereby prematurely finish) an IME composition while suggestions arrive.
    var queryValue by rememberSaveable(state.libraryId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length)))
    }
    LaunchedEffect(state.libraryId, state.query) {
        if (queryValue.text != state.query) queryValue = TextFieldValue(state.query, TextRange(state.query.length))
    }
    val busy = state.searching || state.loadingMore
    val canSearch = state.libraryId != null && state.query.isNotBlank() && !busy && !state.resolvingContext
    val currentSubmission = state.submittedQuery != null && state.query.trim() == state.submittedQuery.trim()
    val editing = state.query.isNotBlank() && !currentSubmission && !busy && !state.resolvingContext
    val scopeVisible = showScope && state.libraryId != null && !state.catalogLoading && !state.catalogError
    val visibleIssue = state.error.takeIf { state.submittedQuery == null || currentSubmission }
    // Only library identity resets the saveable list. Typing must not recreate the lazy subtree
    // (and its focused text field). The caller's holder also restores position after reader Back.
    val list = key(state.libraryId) {
        rememberLazyListState()
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        if (canSearch) {
            focusManager.clearFocus()
            keyboard?.hide()
            scope.launch { list.scrollToItem(0) }
            onSearch()
        }
    }
    val refine: () -> Unit = {
        scope.launch {
            list.scrollToItem(0)
            focus.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(enabled = !scopeVisible && !confirmClearHistory, onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("search-screen"),
        containerColor = ReadingColors.Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = androidx.compose.ui.graphics.Color.Transparent, contentColor = ReadingColors.OnHeader,
                modifier = Modifier.background(ReadingColors.HeaderBrush)) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("search-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_ui_back))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.search_ui_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (state.libraryId == null) stringResource(R.string.search_ui_no_database)
                            else stringResource(R.string.search_ui_database, state.libraryTitle.ifBlank {
                                stringResource(R.string.search_ui_unnamed_database)
                            }),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("search-database"),
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
            if (busy && state.libraryId != null) Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(if (state.loadingMore) "search-loading-more" else "search-loading")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(if (state.loadingMore) R.string.search_ui_loading_more else R.string.search_ui_searching),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("search-cancel")) {
                        Text(stringResource(R.string.search_ui_cancel_search))
                    }
                }
            }
            if (onHome != null && (onMore != null || onBookmarks != null)) {
                AppNavigationBar(AppDestination.SEARCH, onLibrary = { focusManager.clearFocus(); keyboard?.hide(); onHome() },
                    onSearch = {}, onBookmarks = {
                        focusManager.clearFocus(); keyboard?.hide()
                        if (onMore != null) onMore() else onBookmarks?.invoke(0)
                    }, tagPrefix = "search")
            }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.libraryId == null) {
                    item(key = "import") {
                        SearchNotice(stringResource(R.string.search_ui_import_title), stringResource(R.string.search_ui_import_body), "search-import") {
                            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.search_ui_back_to_library))
                            }
                        }
                    }
                } else {
                    item(key = "query") {
                        OutlinedTextField(
                            value = queryValue,
                            onValueChange = { value ->
                                val textChanged = queryValue.text != value.text
                                queryValue = value // Synchronous, before publishing to the model.
                                if (textChanged) onQuery(value.text)
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search-query"),
                            label = { Text(stringResource(R.string.search_ui_query_label)) },
                            placeholder = { Text(stringResource(R.string.search_ui_query_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) IconButton(
                                    onClick = { queryValue = TextFieldValue(""); onQuery("") },
                                    modifier = Modifier.size(48.dp).testTag("search-clear-query"),
                                ) { Icon(Icons.Default.Close, stringResource(R.string.search_ui_clear_query)) }
                            },
                            singleLine = true, shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { submit() }),
                        )
                    }
                    // Keep suggestions next to the query, above the scope/mode controls and IME.
                    if (editing) {
                        if (state.suggesting) item(key = "suggestion-loading") {
                            SearchProgress(stringResource(R.string.search_ui_suggesting), "search-suggestions-loading")
                        }
                        if (state.suggestionsUnavailable) item(key = "suggestion-unavailable") {
                            Text(stringResource(R.string.search_ui_suggestions_unavailable),
                                style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted,
                                modifier = Modifier.testTag("search-suggestions-unavailable"))
                        }
                        if (state.suggestions.isNotEmpty()) item(key = "suggestion-heading") {
                            SearchHeading(stringResource(R.string.search_ui_suggestions))
                        }
                        itemsIndexed(state.suggestions, key = { index, _ -> "suggestion-$index" }) { index, suggestion ->
                            SearchQueryRow(suggestion.label,
                                if (suggestion.reference) stringResource(R.string.search_ui_reference) else null,
                                "search-suggestion-$index") {
                                focusManager.clearFocus(); keyboard?.hide()
                                scope.launch { list.scrollToItem(0) }
                                onSuggestion(suggestion)
                            }
                        }
                    }
                    item(key = "controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SearchScopeSummary(state, selectedRoots) { focusManager.clearFocus(); keyboard?.hide(); showScope = true }
                            SearchTargets(state.target, onTarget)
                            SearchModes(state.mode, onMode)
                            Button(onClick = submit, enabled = canSearch,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-submit"),
                                shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.search_ui_submit))
                            }
                        }
                    }
                    if (state.catalogLoading) item(key = "catalog-loading") {
                        SearchProgress(stringResource(if (state.resolvingContext) R.string.search_ui_context_loading
                            else R.string.search_ui_catalog_loading), "search-catalog-loading")
                    } else if (state.catalogError) item(key = "catalog-error") {
                        SearchNotice(stringResource(R.string.search_ui_catalog_error_title),
                            stringResource(R.string.search_ui_catalog_error_body), "search-catalog-error") {
                            TextButton(onClick = onRetryCatalog, modifier = Modifier.heightIn(min = 48.dp).testTag("search-retry-catalog")) {
                                Text(stringResource(R.string.search_ui_retry_catalog))
                            }
                        }
                    }
                    if (visibleIssue != null) item(key = "error") {
                        SearchProblem(visibleIssue, refine, {
                            focusManager.clearFocus(); keyboard?.hide()
                            if (currentSubmission && state.nextAfter != null) onLoadMore() else onSearch()
                        }, canSearch)
                    }
                    if (currentSubmission) {
                        item(key = "results-heading") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                SearchHeading(stringResource(R.string.search_ui_passages_shown, state.results.size))
                                Text(stringResource(R.string.search_ui_results_query, state.submittedQuery.orEmpty()),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.search_ui_source_order), style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted)
                            }
                        }
                        items(state.results, key = { "result-${it.location.libraryId}-${it.location.position}" }) { hit ->
                            SearchPassage(hit) { onOpenHit(hit) }
                        }
                        if (state.results.isEmpty() && !busy && visibleIssue == null && state.nextAfter == null) item(key = "empty") {
                            SearchNotice(stringResource(R.string.search_ui_empty_title),
                                stringResource(R.string.search_ui_empty_body), "search-empty") {
                                TextButton(onClick = refine, modifier = Modifier.heightIn(min = 48.dp).testTag("search-refine")) {
                                    Text(stringResource(R.string.search_ui_refine))
                                }
                                if (state.selectedBooks.isNotEmpty()) TextButton(onClick = { onBooks(emptySet()) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-reset-scope")) {
                                    Text(stringResource(R.string.search_ui_reset_scope))
                                }
                            }
                        }
                        if (state.nextAfter != null) item(key = "more") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.results.isEmpty()) Text(stringResource(R.string.search_ui_continue),
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("search-continue"))
                                OutlinedButton(onClick = onLoadMore, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-more")) {
                                    Text(stringResource(R.string.search_ui_show_more))
                                }
                            }
                        }
                    }
                    if (state.query.isBlank() && !busy && !state.resolvingContext && state.recent.isNotEmpty()) {
                        item(key = "recent-heading") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.search_ui_recent), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    focusManager.clearFocus(); keyboard?.hide(); confirmClearHistory = true
                                },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-clear-history")) {
                                    Text(stringResource(R.string.search_ui_clear_history))
                                }
                            }
                        }
                        itemsIndexed(state.recent.distinct(), key = { index, _ -> "recent-$index" }) { index, recent ->
                            SearchQueryRow(recent, null, "search-recent-$index") {
                                focusManager.clearFocus(); keyboard?.hide()
                                scope.launch { list.scrollToItem(0) }
                                // One atomic intent, not onQuery followed by a potentially stale onSearch.
                                onSuggestion(SearchSuggestion(recent))
                            }
                        }
                    }
                }
            }
            }
        }
    }
    if (scopeVisible) {
        key(state.libraryId) {
            SearchBookPicker(catalog, selectedRoots, onDismiss = { showScope = false }) {
                showScope = false
                onBooks(it)
            }
        }
    }
    if (confirmClearHistory && state.libraryId != null) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            modifier = Modifier.testTag("search-clear-history-dialog"),
            title = { Text(stringResource(R.string.search_ui_clear_history_title)) },
            text = { Text(stringResource(R.string.search_ui_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClearHistory = false; onClearHistory() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-clear-history-confirm")) {
                    Text(stringResource(R.string.search_ui_clear_history))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-clear-history-cancel")) {
                    Text(stringResource(R.string.search_ui_cancel))
                }
            },
        )
    }
}

@Composable
private fun SearchScopeSummary(state: SearchUiState, selectedRoots: Set<Int>, onClick: () -> Unit) {
    val summary = when (selectedRoots.size) {
        0 -> stringResource(R.string.search_ui_all_database)
        1 -> state.books.firstOrNull { it.node.record in selectedRoots }?.let { searchBookTitle(it) }
            ?: stringResource(R.string.search_ui_selected_count, 1)
        else -> stringResource(R.string.search_ui_selected_count, selectedRoots.size)
    }
    val action = stringResource(R.string.search_ui_choose_scope)
    OutlinedCard(onClick = onClick, enabled = !state.catalogLoading && !state.catalogError && !state.resolvingContext,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-scope")
            .semantics { contentDescription = action; stateDescription = summary },
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, ReadingColors.Rule)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.search_ui_scope_label), style = MaterialTheme.typography.labelMedium, color = ReadingColors.Muted)
                Text(summary, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTargets(target: SearchTarget, onTarget: (SearchTarget) -> Unit) {
    // References such as "Bg 2.8" are recognised inside All text; no separate chip is offered.
    val options = listOf(SearchTarget.ALL_TEXT, SearchTarget.VERSE_WORDS)
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val label = if (option == SearchTarget.ALL_TEXT) R.string.search_ui_target_all_text
                    else R.string.search_ui_target_verse_words
                FilterChip(selected = target == option, onClick = { onTarget(option) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-target-${option.name}"),
                    label = { Text(stringResource(label)) },
                    leadingIcon = if (target == option) {
                        {
                            Icon(Icons.Default.Check, contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("search-target-${option.name}-check"))
                        }
                    } else null,
                    colors = searchFilterChipColors(selected = target == option),
                    border = BorderStroke(1.dp, if (target == option) ReadingColors.Header else ReadingColors.Rule),
                    shape = RoundedCornerShape(12.dp))
            }
        }
        Text(stringResource(if (target == SearchTarget.VERSE_WORDS) R.string.search_ui_target_verse_words_help
            else R.string.search_ui_target_all_text_help), style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted,
            modifier = Modifier.testTag("search-target-help"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchModes(mode: SearchMode, onMode: (SearchMode) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchMode.entries.forEach { option ->
                FilterChip(selected = mode == option, onClick = { onMode(option) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("search-mode-${option.name}"),
                    label = { Text(stringResource(if (option == SearchMode.ALL_WORDS) R.string.search_ui_all_words else R.string.search_ui_exact_phrase)) },
                    leadingIcon = if (mode == option) {
                        {
                            Icon(Icons.Default.Check, contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("search-mode-${option.name}-check"))
                        }
                    } else null,
                    colors = searchFilterChipColors(selected = mode == option),
                    border = BorderStroke(1.dp, if (mode == option) ReadingColors.Header else ReadingColors.Rule),
                    shape = RoundedCornerShape(12.dp))
            }
        }
        Text(stringResource(if (mode == SearchMode.ALL_WORDS) R.string.search_ui_all_words_help else R.string.search_ui_exact_phrase_help),
            style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted, modifier = Modifier.testTag("search-mode-help"))
    }
}

/** Keep both chip groups on the app palette, including disabled/selected combinations. */
@Composable
internal fun searchFilterChipColors(selected: Boolean): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = ReadingColors.Paper,
    labelColor = ReadingColors.Ink,
    iconColor = ReadingColors.Ink,
    selectedContainerColor = ReadingColors.Header,
    selectedLabelColor = ReadingColors.OnHeader,
    selectedLeadingIconColor = ReadingColors.OnHeader,
    selectedTrailingIconColor = ReadingColors.OnHeader,
    disabledContainerColor = ReadingColors.Paper,
    disabledSelectedContainerColor = ReadingColors.Header,
    disabledLabelColor = if (selected) ReadingColors.OnHeader else ReadingColors.Muted,
    disabledLeadingIconColor = if (selected) ReadingColors.OnHeader else ReadingColors.Muted,
    disabledTrailingIconColor = if (selected) ReadingColors.OnHeader else ReadingColors.Muted,
)

@Composable
private fun SearchHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SearchNotice(title: String, body: String, tag: String, actions: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, ReadingColors.Rule)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchHeading(title)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

@Composable
private fun SearchProgress(label: String, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted)
    }
}

@Composable
private fun SearchQueryRow(label: String, detail: String?, tag: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, ReadingColors.Rule)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = ReadingColors.Muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchProblem(issue: SearchIssue, onRefine: () -> Unit, onRetry: () -> Unit, retryEnabled: Boolean) {
    val title = when (issue) {
        SearchIssue.INVALID_QUERY -> R.string.search_ui_invalid_title
        SearchIssue.TIMEOUT -> R.string.search_ui_timeout_title
        SearchIssue.UNAVAILABLE -> R.string.search_ui_unavailable_title
    }
    val body = when (issue) {
        SearchIssue.INVALID_QUERY -> R.string.search_ui_invalid_body
        SearchIssue.TIMEOUT -> R.string.search_ui_timeout_body
        SearchIssue.UNAVAILABLE -> R.string.search_ui_unavailable_body
    }
    SearchNotice(stringResource(title), stringResource(body), "search-error-${issue.name}") {
        if (issue != SearchIssue.INVALID_QUERY) TextButton(onClick = onRetry, enabled = retryEnabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("search-retry")) {
            Text(stringResource(R.string.search_ui_retry))
        }
        TextButton(onClick = onRefine, modifier = Modifier.heightIn(min = 48.dp).testTag("search-refine")) {
            Text(stringResource(R.string.search_ui_refine))
        }
    }
}

@Composable
private fun SearchPassage(hit: ScopedSearchHit, onClick: () -> Unit) {
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer,
        color = ReadingColors.Ink, fontWeight = FontWeight.SemiBold)
    val snippet = remember(hit.snippet, hit.highlights, mark) { searchSnippet(hit, mark) }
    val action = stringResource(R.string.search_ui_open_passage, hit.location.title)
    OutlinedCard(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-result-${hit.location.position}")
            .semantics { onClick(label = action, action = null) },
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, ReadingColors.Rule)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(hit.bookTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(hit.location.title, style = MaterialTheme.typography.titleMedium)
            // The repository supplies plain Unicode, never HTML; no maxLines/ellipsis on passages.
            Text(snippet, style = MaterialTheme.typography.bodyLarge, color = ReadingColors.Ink,
                modifier = Modifier.testTag("search-snippet-${hit.location.position}"))
        }
    }
}

private fun searchSnippet(hit: ScopedSearchHit, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(hit.snippet)
    fun splitsSurrogate(offset: Int): Boolean = offset > 0 && offset < hit.snippet.length &&
        Character.isHighSurrogate(hit.snippet[offset - 1]) && Character.isLowSurrogate(hit.snippet[offset])
    hit.highlights.forEach { range ->
        // Ignore invalid offsets rather than clamping and highlighting unrelated text.
        if (range.start >= 0 && range.endExclusive <= hit.snippet.length && range.start < range.endExclusive &&
            !splitsSurrogate(range.start) && !splitsSurrogate(range.endExclusive)) {
            addStyle(style, range.start, range.endExclusive)
        }
    }
}

@Composable
private fun searchBookTitle(book: SearchBook): String = book.node.title.ifBlank {
    stringResource(R.string.search_ui_untitled_source, book.node.record)
}

@Composable
private fun SearchBookPicker(catalog: SearchBookSelection, selected: Set<Int>, onDismiss: () -> Unit, onApply: (Set<Int>) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var allDatabase by rememberSaveable { mutableStateOf(selected.isEmpty()) }
    var draft by rememberSaveable { mutableStateOf(catalog.canonical(selected).toList()) }
    var expanded by rememberSaveable {
        mutableStateOf((catalog.roots + selected.flatMap { catalog.ancestors(it) }).distinct())
    }
    var excludedParents by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    val filtered = remember(catalog, filter, expanded) { catalog.visible(filter, expanded.toSet()) }
    val revealedParents = remember(catalog, filtered) { filtered.mapNotNullTo(mutableSetOf()) { it.parentRecord } }
    val dialogTitle = stringResource(R.string.search_ui_choose_scope)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val draftIds = remember(catalog, draft) { catalog.canonical(draft.toSet()) }
    val checks = remember(catalog, draftIds, allDatabase) { catalog.checks(if (allDatabase) emptySet() else draftIds) }
    val valid = allDatabase || (draftIds.isNotEmpty() && draftIds.all(catalog::isKnown))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight()
                .testTag("search-scope-dialog").semantics { paneTitle = dialogTitle },
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Text(dialogTitle, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() })
                    HorizontalDivider(color = ReadingColors.Rule)
                    LazyColumn(modifier = Modifier.weight(1f).testTag("search-book-list"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        item(key = "filter") {
                            OutlinedTextField(value = filter, onValueChange = { filter = it },
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp).testTag("search-book-filter"),
                                label = { Text(stringResource(R.string.search_ui_filter_sources)) },
                                singleLine = true, shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboard?.hide() }),
                                trailingIcon = {
                                    if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Close, stringResource(R.string.search_ui_clear_filter))
                                    }
                                })
                        }
                        item(key = "all") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = allDatabase, role = Role.RadioButton, onClick = {
                                    allDatabase = true; draft = emptyList(); excludedParents = emptyList()
                                })
                                .testTag("search-scope-all").padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = allDatabase, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(stringResource(R.string.search_ui_all_database), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.search_ui_all_database_help), style = MaterialTheme.typography.bodySmall,
                                        color = ReadingColors.Muted)
                                }
                            }
                        }
                        item(key = "selection") {
                            HorizontalDivider(color = ReadingColors.Rule)
                            val selectionMessage = when {
                                allDatabase -> stringResource(R.string.search_ui_pick_sources)
                                draftIds.isEmpty() -> stringResource(R.string.search_ui_select_required)
                                else -> stringResource(R.string.search_ui_selection_help, draftIds.size)
                            }
                            Text(selectionMessage,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).testTag("search-scope-selection")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted)
                            if (excludedParents.isNotEmpty()) Text(stringResource(R.string.search_ui_excluded_parent_prose),
                                modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 8.dp).testTag("search-scope-exclusion-note")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted)
                        }
                        if (filtered.isEmpty()) item(key = "no-sources") {
                            Text(stringResource(if (catalog.entries.isEmpty()) R.string.search_ui_catalog_empty else R.string.search_ui_filter_empty),
                                Modifier.padding(vertical = 16.dp).testTag("search-book-empty"), style = MaterialTheme.typography.bodyMedium)
                        }
                        items(filtered, key = { "book-${it.record}" }) { entry ->
                            val book = entry.book
                            val id = entry.record
                            val checked = when (checks.getValue(id)) {
                                SearchBookSelection.Check.ON -> ToggleableState.On
                                SearchBookSelection.Check.OFF -> ToggleableState.Off
                                SearchBookSelection.Check.PARTIAL -> ToggleableState.Indeterminate
                            }
                            val hasChildren = catalog.children(id).isNotEmpty()
                            val filtering = filter.isNotBlank()
                            val isExpanded = if (filtering) id in revealedParents else id in expanded
                            val title = searchBookTitle(book)
                            val parentTitle = entry.parentRecord?.let { catalog.entry(it)?.book }?.let { searchBookTitle(it) }
                            val expansionState = stringResource(when {
                                filtering -> R.string.search_ui_filter_reveals_children
                                isExpanded -> R.string.search_ui_expanded
                                else -> R.string.search_ui_collapsed
                            })
                            val levels = (entry.depth - 1).coerceIn(0, 6)
                            val guideColor = ReadingColors.Rule
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min).testTag("search-book-row-$id"),
                                verticalAlignment = Alignment.CenterVertically) {
                                // One thin guide per ancestor level makes depth legible without labels.
                                if (levels > 0) Spacer(Modifier.width((levels * LEVEL_INDENT).dp).fillMaxHeight().drawBehind {
                                    val step = LEVEL_INDENT.dp.toPx()
                                    for (level in 0 until levels) {
                                        val x = level * step + step / 2
                                        drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                                    }
                                })
                                if (hasChildren) IconButton(
                                    onClick = { expanded = if (isExpanded) expanded - id else expanded + id },
                                    enabled = !filtering,
                                    modifier = Modifier.size(48.dp).testTag("search-book-expand-$id")
                                        .semantics { stateDescription = expansionState },
                                ) {
                                    Icon(if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        stringResource(if (isExpanded) R.string.search_ui_collapse_book else R.string.search_ui_expand_book, title))
                                } else Spacer(Modifier.width(48.dp))
                                // Expansion and selection are separate 48dp targets, never nested click actions.
                                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                                    .triStateToggleable(state = checked, role = Role.Checkbox, onClick = {
                                        val change = catalog.toggle(if (allDatabase) emptySet() else draftIds, id)
                                        allDatabase = false
                                        draft = change.roots.toList()
                                        val updatedChecks = catalog.checks(change.roots)
                                        excludedParents = (excludedParents + change.excludedParentRecords).distinct()
                                            .filter { updatedChecks[it] != SearchBookSelection.Check.ON }
                                    }).testTag("search-book-$id").padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    TriStateCheckbox(state = checked, onClick = null, modifier = Modifier.size(44.dp))
                                    Column(Modifier.weight(1f).padding(start = 2.dp, end = 8.dp)) {
                                        Text(title, style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (entry.depth == 1) FontWeight.SemiBold else FontWeight.Normal)
                                        // A flat filtered list loses its indentation; name the parent there only.
                                        if (filtering && parentTitle != null) Text(parentTitle,
                                            style = MaterialTheme.typography.bodySmall, color = ReadingColors.Muted)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = ReadingColors.Rule)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("search-scope-cancel")) {
                            Text(stringResource(R.string.search_ui_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onApply(if (allDatabase) emptySet() else draftIds) }, enabled = valid,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("search-scope-apply")) {
                            Text(stringResource(R.string.search_ui_apply))
                        }
                    }
                }
            }
        }
    }
}