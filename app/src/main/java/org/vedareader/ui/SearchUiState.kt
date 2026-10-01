package org.vedareader.ui

import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchBook
import org.vedareader.data.SearchIssue
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget

data class SearchUiState(
    val libraryId: String? = null,
    val libraryTitle: String = "",
    val query: String = "",
    val books: List<SearchBook> = emptyList(),
    val selectedBooks: Set<Int> = emptySet(),
    val mode: SearchMode = SearchMode.ALL_WORDS,
    val catalogLoading: Boolean = false,
    val catalogError: Boolean = false,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val suggesting: Boolean = false,
    val suggestionsUnavailable: Boolean = false,
    val results: List<ScopedSearchHit> = emptyList(),
    val submittedQuery: String? = null,
    val nextAfter: Int? = null,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: SearchIssue? = null,
    val recent: List<String> = emptyList(),
    val resolvingContext: Boolean = false,
    val target: SearchTarget = SearchTarget.ALL_TEXT,
)