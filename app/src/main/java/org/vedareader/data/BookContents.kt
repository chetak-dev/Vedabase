package org.vedareader.data

/** Original source headings, with only valid outline-entry starts as jump targets. */
data class BookContentsItem(val source: ContentNode, val jumpRecord: Int, val children: List<BookContentsItem>)

fun bookContents(outline: BookOutline): List<BookContentsItem> {
    val sources = outline.entries.distinctBy { it.source.record }
    val byRecord = sources.associateBy { it.source.record }
    val children = mutableMapOf<Int, MutableList<Int>>()
    val roots = mutableListOf<Int>()
    for (entry in sources) {
        val node = entry.source
        // The book's opening entry is a standalone jump, never a wrapper hiding all chapters.
        val parent = byRecord[node.parent]?.source?.takeIf {
            it.record != outline.book.record && it.record < node.record && it.end >= node.end
        } ?: sources.asSequence().filter { candidate ->
            candidate.kind != ReadingUnitKind.VERSE && candidate.source.record != outline.book.record &&
                candidate.source.record < node.record && candidate.source.end >= node.end
        }.maxByOrNull { it.source.record }?.source
        if (parent == null) roots += node.record else children.getOrPut(parent.record) { mutableListOf() }.add(node.record)
    }
    fun item(record: Int): BookContentsItem {
        val entry = byRecord.getValue(record)
        return BookContentsItem(entry.source, entry.node.record, children[record].orEmpty().map(::item))
    }
    return roots.map(::item)
}