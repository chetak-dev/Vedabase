package org.vedareader.data

/** One continuous, complete source range for a horizontally paginated book document.
 * [firstEntry] and [lastEntry] are inclusive outline indices, not page or verse numbers.
 * [position] still identifies the entry containing the selection, not the resource's first entry.
 * [anchorRecord] preserves that selection (including an exact original verse opening).
 * Neighbours identify adjacent resources, or their nearest outline entries; opening previous
 * with end intent is the caller's responsibility, not a change to source order or the anchor.
 */
data class BookResource(
    val section: ReadingSection,
    val position: BookPosition,
    val firstEntry: Int,
    val lastEntry: Int,
    val anchorRecord: Int,
) {
    /** Actual contents heading for the selected entry, never a synthetic resource/chunk node. */
    val source: ContentNode get() = position.outline.entries[position.index].source

    init {
        val entries = position.outline.entries
        require(firstEntry in entries.indices && lastEntry in firstEntry..entries.lastIndex)
        require(position.index in firstEntry..lastEntry)
        require(section.node.record == entries[firstEntry].node.record && section.node.end == entries[lastEntry].node.end)
        val selected = entries[position.index].node
        require(anchorRecord in selected.record..selected.end)
        require(section.previous?.end == entries.getOrNull(firstEntry - 1)?.node?.end)
        require(section.next?.record == entries.getOrNull(lastEntry + 1)?.node?.record)
    }
}