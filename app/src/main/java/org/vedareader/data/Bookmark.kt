package org.vedareader.data

/** User-owned metadata. A bookmark identifies a source paragraph, not a screen-page number. */
data class Bookmark(
    val id: String,
    val location: ReadingLocation,
    val bookRecord: Int,
    val bookTitle: String,
    val excerpt: String,
    val createdAt: Long,
)

enum class BookmarkIssue { LOAD, SAVE, LIMIT }
class BookmarkException(val issue: BookmarkIssue, cause: Throwable? = null) : Exception(issue.name, cause)