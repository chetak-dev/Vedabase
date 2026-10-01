package org.vedareader.ui

import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkIssue

enum class BookmarkMessage { SAVED, REMOVED, LOAD_FAILED, SAVE_FAILED, LIMIT_REACHED }
data class BookmarkFeedback(val id: Long, val message: BookmarkMessage)

data class BookmarkUiState(
    val items: List<Bookmark> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: BookmarkIssue? = null,
    val feedback: BookmarkFeedback? = null,
)