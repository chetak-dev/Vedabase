package org.vedareader.data

/** Presentation items only: an introduction has no invented ContentNode, record or parent. */
internal sealed interface ContentsItem {
    data class Section(val node: ContentNode, val sourceIndex: Int) : ContentsItem
    data class Introduction(val sections: List<Section>) : ContentsItem
}

/**
 * Consume IDs already classified by the loader; never infer front matter from titles or levels.
 * Unknown IDs or ambiguous source IDs fail closed. Otherwise group two or more at their first
 * source position, preserving the original nodes, ordering and numbering of all other entries.
 */
internal fun groupIntroduction(entries: List<ContentNode>, verifiedRecords: Set<Int>): List<ContentsItem> {
    val sections = entries.mapIndexed { index, node -> ContentsItem.Section(node, index) }
    if (verifiedRecords.size < 2) return sections
    val available = entries.map { it.record }.toSet()
    if (available.size != entries.size || !available.containsAll(verifiedRecords)) return sections
    val grouped = sections.filter { it.node.record in verifiedRecords }
    return buildList {
        for (section in sections) {
            if (section.node.record !in verifiedRecords) add(section)
            else if (section.sourceIndex == grouped.first().sourceIndex) {
                add(ContentsItem.Introduction(grouped))
            }
        }
    }
}