package org.vedareader.data

/** Empty bookRecords means the entire active imported database, not other imports. */
data class SearchRequest(
    val query: String,
    val bookRecords: Set<Int> = emptySet(),
    val mode: SearchMode = SearchMode.ALL_WORDS,
    val target: SearchTarget = SearchTarget.ALL_TEXT,
)

enum class SearchMode { ALL_WORDS, EXACT_PHRASE }
enum class SearchTarget { ALL_TEXT, VERSE_WORDS, VERSE_REFERENCE }

/** Original source book/collection metadata, including independently selectable nested books. */
data class SearchBook(val node: ContentNode, val parentTitle: String = "")

data class SearchHighlight(val start: Int, val endExclusive: Int)

data class ScopedSearchHit(
    val location: ReadingLocation,
    val bookTitle: String,
    val snippet: String,
    val highlights: List<SearchHighlight> = emptyList(),
)

/** A bounded source-ordered page. nextAfter is a scanned-record cursor, not an offset. */
data class SearchPage(
    val hits: List<ScopedSearchHit> = emptyList(),
    val nextAfter: Int? = null,
)

data class SearchSuggestion(val query: String, val label: String = query, val reference: Boolean = false)

enum class SearchIssue { INVALID_QUERY, TIMEOUT, UNAVAILABLE }