package org.vedareader.data

/**
 * Pure, prepared catalog index. Build once per catalog, not per keystroke/row.
 * Only supplied catalog entries are selectable; omitted chapters/cantos are not invented.
 * Inclusive source ranges resolve missing intermediate parents. Depth is catalog depth (1-based),
 * not the source's semantic level number. Equal/invalid ranges cannot form parent cycles.
 */
class SearchBookSelection(books: List<SearchBook>) {
    data class Entry(val book: SearchBook, val parentRecord: Int?, val depth: Int) {
        val record: Int get() = book.node.record
    }

    enum class Check { OFF, PARTIAL, ON }

    /** Removed containers can have their own prose that no surviving catalog branch represents. */
    data class Change(val roots: Set<Int>, val excludedParentRecords: Set<Int> = emptySet())

    private val catalog = books.distinctBy { it.node.record }.sortedBy { it.node.record }
    private val byRecord = catalog.associateBy { it.node.record }
    private val parents = buildParents()
    private val childRecords = catalog.groupBy { parents[it.node.record] }
        .mapValues { (_, children) -> children.map { it.node.record } }
    private val ancestorRecords = catalog.associate { book ->
        book.node.record to generateSequence(parents[book.node.record]) { parents[it] }.toList()
    }
    private val searchable = catalog.associate { book ->
        book.node.record to SearchText.normalize(
            (listOf(book.node.title, book.parentTitle) + ancestorRecords.getValue(book.node.record)
                .map { byRecord.getValue(it).node.title }).joinToString("\n"),
        )
    }
    private val byEntry = catalog.associate { book ->
        book.node.record to Entry(book, parents[book.node.record], ancestorRecords.getValue(book.node.record).size + 1)
    }

    val roots: List<Int> = childRecords[null].orEmpty()
    val entries: List<Entry> = buildList {
        val pending = ArrayDeque<Int>()
        roots.asReversed().forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val record = pending.removeLast()
            add(byEntry.getValue(record))
            children(record).asReversed().forEach { pending.addLast(it) }
        }
    }

    fun children(record: Int): List<Int> = childRecords[record].orEmpty()
    fun ancestors(record: Int): List<Int> = ancestorRecords[record].orEmpty()
    fun entry(record: Int): Entry? = byEntry[record]
    fun isKnown(record: Int): Boolean = record in byRecord

    /**
     * Minimal explicit roots, never promote all selected children to their parent (extra prose).
     * Unknown IDs remain opaque so a stale selection cannot silently turn into All database.
     * Empty here means no selection; interpreting it as All is the UI's explicit separate choice.
     */
    fun canonical(selected: Set<Int>): Set<Int> = selected.filterTo(linkedSetOf()) { record ->
        ancestors(record).none { it in selected }
    }

    /** A parent remains PARTIAL even if every catalog child is selected: its own prose is not. */
    fun checks(selected: Set<Int>): Map<Int, Check> {
        val selectedRoots = canonical(selected)
        val partial = selectedRoots.flatMapTo(mutableSetOf()) { ancestors(it) }
        return entries.associate { entry ->
            entry.record to when {
                entry.record in selectedRoots || ancestors(entry.record).any { it in selectedRoots } -> Check.ON
                entry.record in partial -> Check.PARTIAL
                else -> Check.OFF
            }
        }
    }

    /**
     * Clicking OFF/PARTIAL selects the entire branch, including hidden descendants.
     * Clicking ON removes it. An enclosing selected root is replaced with unaffected sibling
     * branches along the exclusion path, not kept as a mask that would still search the child.
     * Unrepresentable container prose is dropped and reported. Even malformed overlapping
     * sibling ranges are reduced conservatively so the excluded range cannot remain selected.
     */
    fun toggle(selected: Set<Int>, record: Int): Change {
        require(isKnown(record)) { "Unknown catalog record: $record" }
        val current = canonical(selected)
        if (checks(current).getValue(record) != Check.ON) return Change(canonical(current + record))
        val excluded = byRecord.getValue(record).node
        val kept = linkedSetOf<Int>()
        val removedParents = linkedSetOf<Int>()
        fun retainOutside(id: Int) {
            val node = byRecord[id]?.node
            when {
                node == null || node.end < excluded.record || node.record > excluded.end -> kept.add(id)
                node.record >= excluded.record && node.end <= excluded.end -> Unit
                else -> {
                    removedParents.add(id)
                    children(id).forEach(::retainOutside)
                }
            }
        }
        current.forEach(::retainOutside)
        return Change(canonical(kept), removedParents)
    }

    /** Filtering reveals matching descendants plus their ancestor path without changing expansion. */
    fun visible(filter: String, expanded: Set<Int>): List<Entry> {
        val term = SearchText.normalize(filter.trim())
        if (term.isEmpty()) return entries.filter { entry -> ancestors(entry.record).all { it in expanded } }
        val visible = linkedSetOf<Int>()
        searchable.forEach { (record, label) ->
            if (label.contains(term)) {
                visible.add(record)
                visible.addAll(ancestors(record))
            }
        }
        return entries.filter { it.record in visible }
    }

    /**
     * The catalog is already source ordered and deduplicated. In a laminar family (ranges
     * are disjoint or nested), the active stack top has the smallest containing span.
     * Each valid range is pushed/popped at most once: O(n) parent selection after the sort.
     * Equal inclusive ends are nested, not crossings; invalid ranges cannot parent anything.
     */
    private fun buildParents(): Map<Int, Int?> {
        val result = linkedMapOf<Int, Int?>()
        val active = ArrayDeque<SearchBook>()
        for (book in catalog) {
            val node = book.node
            if (node.end < node.record) {
                result[node.record] = null
                continue
            }
            while (active.isNotEmpty() && active.last().node.end < node.record) {
                active.removeLast()
            }
            val enclosing = active.lastOrNull()
            if (enclosing != null && enclosing.node.end < node.end) {
                // Crossing ranges break stack nesting. Recompute the whole catalog so a
                // popped candidate is never lost; preserve the original span/direct/record ties.
                return scannedParents()
            }
            result[node.record] = enclosing?.node?.record
            active.addLast(book)
        }
        return result
    }

    /** Compatibility path only for malformed, crossing ranges; no per-node sorting. */
    private fun scannedParents(): Map<Int, Int?> = catalog.associate { book ->
        val node = book.node
        val direct = byRecord[node.parent]?.takeIf { contains(it, book) }
        val nearest = catalog.asSequence().filter { contains(it, book) }
            .minWithOrNull(compareBy<SearchBook> { it.node.end.toLong() - it.node.record }
                .thenBy { if (it == direct) 0 else 1 }.thenByDescending { it.node.record })
        node.record to nearest?.node?.record
    }

    private fun contains(parent: SearchBook, child: SearchBook): Boolean =
        parent.node.record < child.node.record && child.node.end >= child.node.record &&
            parent.node.end >= child.node.end
}