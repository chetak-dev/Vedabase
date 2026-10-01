package org.vedareader.data

/** Four reader-safe marker colours; the reader palette maps each to light and dark paper shades. */
enum class HighlightColor { YELLOW, GREEN, BLUE, PINK }

/**
 * One user highlight. Offsets index each source record's plain text (the DOM textContent of
 * its `.record` element), so they survive theme, size and mode changes without re-anchoring.
 * `location.position` is the first record, letting the reader open the highlight like a bookmark.
 */
data class Highlight(
    val id: String,
    val location: ReadingLocation,
    val bookTitle: String,
    val record: Int,
    val start: Int,
    val endRecord: Int,
    val end: Int,
    val text: String,
    val color: HighlightColor,
    val createdAt: Long,
    /** The reader's own words about this passage. */
    val note: String = "",
    /** False for a standalone note. Defaults to true to preserve legacy filled highlights. */
    val highlighted: Boolean = true,
) {
    val hasNote: Boolean get() = note.isNotBlank()
    val span: HighlightSpan get() = HighlightSpan(id, record, start, endRecord, end, color, hasNote, highlighted)
}

/** Only what the document painter needs; no titles, timestamps or library identity. */
data class HighlightSpan(
    val id: String,
    val record: Int,
    val start: Int,
    val endRecord: Int,
    val end: Int,
    val color: HighlightColor,
    val noted: Boolean = false,
    val highlighted: Boolean = true,
)

/** A text selection reported by the reader, in the same record/offset coordinates as spans. */
data class HighlightSelection(
    val record: Int,
    val start: Int,
    val endRecord: Int,
    val end: Int,
    val text: String,
) {
    val isEmpty: Boolean get() = record == endRecord && end <= start
}
