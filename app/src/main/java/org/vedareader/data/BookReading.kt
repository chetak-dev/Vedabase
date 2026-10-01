package org.vedareader.data

import java.util.Collections

/** [node] is the inclusive reading range; [source] is always an actual contents heading.
 * Pass source.record, not a synthetic gap/chunk's record, when returning to legacy mode.
 * Container sources can still require section selection in legacy mode.
 */
data class BookEntry(
    val node: ContentNode,
    val source: ContentNode,
    val kind: ReadingUnitKind,
    val parentTitle: String,
)

/** Metadata only: no paragraphs/HTML. A defensive, unmodifiable snapshot in source order. */
class BookOutline(val book: ContentNode, entries: List<BookEntry>) {
    val entries: List<BookEntry> = Collections.unmodifiableList(ArrayList(entries))

    init {
        require(this.entries.isNotEmpty())
        require(this.entries.first().node.record == book.record && this.entries.last().node.end == book.end)
        require(this.entries.all { it.node.record >= it.source.record && it.node.end <= it.source.end && it.node.end >= it.node.record })
        require(this.entries.zipWithNext().all { (left, right) -> left.node.end.toLong() + 1 == right.node.record.toLong() })
    }
}

data class BookPosition(val outline: BookOutline, val index: Int) {
    init { require(index in outline.entries.indices) }
}

/** One loaded entry only; neighbours are outline ranges, never legacy verse-only neighbours. */
data class BookReadingSection(val section: ReadingSection, val position: BookPosition) {
    init {
        val entries = position.outline.entries
        val entry = entries[position.index]
        require(section.node == entry.node && section.kind == entry.kind && section.parentTitle == entry.parentTitle)
        require(section.previous == entries.getOrNull(position.index - 1)?.node)
        require(section.next == entries.getOrNull(position.index + 1)?.node)
    }
}