package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vedareader.data.SearchBook
import org.vedareader.data.SearchIssue
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchPage
import org.vedareader.data.SearchRequest
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget
import org.vedareader.data.SearchText

/**
 * Caller-thread state, like BrowseNavigator. Call on the scope's thread (normally main);
 * loaders own their I/O dispatching and timeouts. This controller never owns the scope.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val loadBooks: suspend (String) -> List<SearchBook>,
    private val search: suspend (String, SearchRequest, Int) -> SearchPage,
    private val suggest: suspend (String, SearchRequest) -> List<SearchSuggestion>,
    private val onChange: (SearchUiState) -> Unit,
    private val loadRecent: (String) -> List<String> = { emptyList() },
    private val saveRecent: (String, List<String>) -> Unit = { _, _ -> },
) {
    var state: SearchUiState = SearchUiState()
        private set

    private var catalogJob: Job? = null
    private var queryJob: Job? = null
    private var suggestionJob: Job? = null
    private var catalogGeneration = 0L
    private var queryGeneration = 0L
    private var suggestionGeneration = 0L
    private var catalogReady = false
    private var completedRequest: SearchRequest? = null
    private var paused = false
    private var closed = false

    fun setLibrary(id: String?, title: String) {
        if (closed) return
        if (state.libraryId == id) {
            publish(state.copy(libraryTitle = title))
            return
        }
        invalidateAll()
        catalogReady = false
        completedRequest = null
        val recent = if (id == null) emptyList() else try {
            cleanRecent(loadRecent(id))
        } catch (_: Exception) {
            emptyList()
        }
        publish(SearchUiState(libraryId = id, libraryTitle = title, recent = recent))
        retryCatalog()
    }

    fun setQuery(query: String) {
        if (closed || query == state.query) return
        edit(state.copy(query = query))
    }

    /** Reject an unknown selection as a whole: filtering it to empty could widen to all books. */
    fun setBooks(records: Set<Int>) {
        if (closed || !catalogReady || state.catalogLoading) return
        val selected = records.toSet()
        val available = state.books.map { it.node.record }.toSet()
        if (!available.containsAll(selected) || selected == state.selectedBooks) return
        edit(state.copy(selectedBooks = selected))
    }

    fun setMode(mode: SearchMode) {
        if (closed || mode == state.mode) return
        edit(state.copy(mode = mode))
    }

    fun setTarget(target: SearchTarget) {
        if (closed || target == state.target) return
        edit(state.copy(target = target))
    }

    fun submit() { submitQuery(state.query) }

    /** One transition, with no intermediate edit/suggestion request for the selected text. */
    fun selectSuggestion(suggestion: SearchSuggestion) { submitQuery(suggestion.query) }

    fun loadMore() {
        if (!active() || state.searching || state.loadingMore) return
        val library = state.libraryId ?: return
        val request = completedRequest ?: return
        val after = state.nextAfter ?: return
        if (request != currentRequest() || state.submittedQuery != request.query) return
        invalidateQuery()
        invalidateSuggestions()
        startSearch(library, request, after, more = true)
    }

    fun retryCatalog() {
        if (!active() || catalogReady || state.catalogLoading) return
        val library = state.libraryId ?: return
        invalidateCatalog()
        val generation = catalogGeneration
        fun current() = active() && generation == catalogGeneration && state.libraryId == library
        publish(state.copy(catalogLoading = true, catalogError = false))
        if (!current()) return
        catalogJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!current()) return@launch
            try {
                val books = loadBooks(library).toList()
                check(books.all { it.node.record > 0 })
                check(books.map { it.node.record }.distinct().size == books.size)
                if (current()) {
                    catalogReady = true // An empty successful catalog is still cached.
                    publish(state.copy(books = books, catalogLoading = false, catalogError = false))
                }
            } catch (_: TimeoutCancellationException) {
                if (current()) publish(state.copy(catalogLoading = false, catalogError = true))
            } catch (cancelled: CancellationException) {
                if (current()) publish(state.copy(catalogLoading = false))
                throw cancelled
            } catch (_: Exception) {
                if (current()) publish(state.copy(catalogLoading = false, catalogError = true))
            }
        }
        catalogJob?.start()
    }

    /** Cancel query/suggestions, retaining the catalog and any completed page/cursor. */
    fun cancel() {
        if (closed) return
        invalidateQuery()
        invalidateSuggestions()
        publish(cancelledState().copy(suggesting = false,
            suggestions = emptyList(), suggestionsUnavailable = false))
    }

    fun clearHistory() {
        if (closed) return
        val library = state.libraryId ?: return
        persistRecent(library, emptyList())
        publish(state.copy(recent = emptyList()))
    }

    fun pause() {
        if (closed || paused) return
        paused = true
        invalidateAll()
        publish(cancelledState().copy(catalogLoading = false, suggesting = false,
            suggestions = emptyList(), suggestionsUnavailable = false))
    }

    fun resume() {
        if (closed || !paused) return
        paused = false
        retryCatalog()
        if (state.submittedQuery == null) scheduleSuggestions()
    }

    /** Terminal and silent; no cancellation or ownership of the supplied scope. */
    fun close() {
        if (closed) return
        closed = true
        invalidateAll()
    }

    private fun edit(value: SearchUiState) {
        invalidateQuery()
        invalidateSuggestions()
        completedRequest = null
        publish(value.copy(results = emptyList(), submittedQuery = null, nextAfter = null,
            searching = false, loadingMore = false, error = null, suggestions = emptyList(),
            suggesting = false, suggestionsUnavailable = false))
        scheduleSuggestions()
    }

    private fun submitQuery(query: String) {
        if (!active()) return
        val library = state.libraryId ?: return
        invalidateQuery()
        invalidateSuggestions()
        val request = SearchRequest(query, state.selectedBooks.toSet(), state.mode, state.target)
        if (request != completedRequest) completedRequest = null
        val base = state.copy(query = query, suggestions = emptyList(), suggesting = false,
            suggestionsUnavailable = false, searching = false, loadingMore = false, error = null)
        // Validation applies to the original input, not a trimmed/truncated replacement.
        if (!valid(query, request.target)) {
            completedRequest = null
            publish(base.copy(results = emptyList(), submittedQuery = null, nextAfter = null,
                error = SearchIssue.INVALID_QUERY))
            return
        }
        startSearch(library, request, 0, more = false, base = base)
    }

    private fun startSearch(
        library: String,
        request: SearchRequest,
        after: Int,
        more: Boolean,
        base: SearchUiState = state,
    ) {
        val generation = queryGeneration
        fun current() = active() && generation == queryGeneration &&
            state.libraryId == library && currentRequest() == request
        val retained = completedRequest == request
        publish(base.copy(results = if (retained) base.results else emptyList(),
            nextAfter = if (retained) base.nextAfter else null, submittedQuery = request.query,
            searching = !more, loadingMore = more, error = null, suggestions = emptyList(),
            suggesting = false, suggestionsUnavailable = false))
        if (!current()) return
        queryJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!current()) return@launch
            try {
                val page = search(library, request, after)
                if (!current()) return@launch
                // Empty/duplicate-only pages may advance the scanned-record cursor. A stalled
                // or backward cursor is a failed page, never silent exhaustion or a retry loop.
                check(page.nextAfter == null || page.nextAfter > after)
                check(page.hits.all { it.location.libraryId == library && it.location.position > 0 })
                check(page.nextAfter == null || page.hits.all { it.location.position <= page.nextAfter })
                val hits = ((if (more) state.results else emptyList()) + page.hits)
                    .distinctBy { it.location.position }
                val recent = if (more) state.recent else cleanRecent(listOf(request.query) + state.recent)
                if (!more) persistRecent(library, recent)
                if (!current()) return@launch
                completedRequest = request
                publish(state.copy(results = hits, nextAfter = page.nextAfter, recent = recent,
                    searching = false, loadingMore = false, error = null))
            } catch (_: TimeoutCancellationException) {
                if (current()) publish(state.copy(searching = false, loadingMore = false, error = SearchIssue.TIMEOUT))
            } catch (cancelled: CancellationException) {
                if (current()) publish(cancelledState())
                throw cancelled
            } catch (_: Exception) {
                if (current()) publish(state.copy(searching = false, loadingMore = false, error = SearchIssue.UNAVAILABLE))
            }
        }
        queryJob?.start()
    }

    private fun scheduleSuggestions() {
        invalidateSuggestions()
        if (!active() || state.searching || state.loadingMore || state.submittedQuery != null) return
        val library = state.libraryId ?: return
        val request = currentRequest()
        if (request.query.trim().length < 2 || !valid(request.query, request.target, allowPrefix = true)) return
        val generation = suggestionGeneration
        fun current() = active() && generation == suggestionGeneration &&
            state.libraryId == library && currentRequest() == request
        suggestionJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(SUGGESTION_DELAY_MS)
                if (!current()) return@launch
                publish(state.copy(suggesting = true, suggestionsUnavailable = false))
                if (!current()) return@launch
                val suggestions = suggest(library, request).toList()
                if (current()) publish(state.copy(suggestions = suggestions, suggesting = false))
            } catch (_: TimeoutCancellationException) {
                if (current()) publish(state.copy(suggesting = false, suggestionsUnavailable = true))
            } catch (cancelled: CancellationException) {
                if (current()) publish(state.copy(suggesting = false))
                throw cancelled
            } catch (_: Exception) {
                if (current()) publish(state.copy(suggesting = false, suggestionsUnavailable = true))
            }
        }
        suggestionJob?.start()
    }

    private fun currentRequest() = SearchRequest(state.query, state.selectedBooks.toSet(), state.mode, state.target)

    private fun cancelledState() = state.copy(searching = false, loadingMore = false, error = null,
        submittedQuery = if (completedRequest == null) null else state.submittedQuery,
        results = if (completedRequest == null) emptyList() else state.results,
        nextAfter = if (completedRequest == null) null else state.nextAfter)

    private fun active() = !closed && !paused && scope.isActive

    private fun valid(query: String, target: SearchTarget = SearchTarget.ALL_TEXT, allowPrefix: Boolean = false): Boolean = try {
        SearchText.validate(query, target, allowPrefix)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun cleanRecent(values: List<*>): List<String> = values.asSequence()
        .mapNotNull { (it as? String)?.trim() }
        .filter { valid(it) }
        .distinct()
        .take(RECENT_LIMIT)
        .toList()

    private fun persistRecent(library: String, recent: List<String>) {
        try {
            saveRecent(library, recent.toList())
        } catch (_: Exception) {
            // History is best effort; do not fail search or log a user's query.
        }
    }

    private fun invalidateCatalog() {
        catalogGeneration++
        catalogJob?.cancel()
        catalogJob = null
    }

    private fun invalidateQuery() {
        queryGeneration++
        queryJob?.cancel()
        queryJob = null
    }

    private fun invalidateSuggestions() {
        suggestionGeneration++
        suggestionJob?.cancel()
        suggestionJob = null
    }

    private fun invalidateAll() {
        invalidateCatalog()
        invalidateQuery()
        invalidateSuggestions()
    }

    private fun publish(value: SearchUiState) {
        if (closed || value == state) return
        state = value
        onChange(value)
    }

    private companion object {
        const val SUGGESTION_DELAY_MS = 250L
        const val RECENT_LIMIT = 8
    }
}