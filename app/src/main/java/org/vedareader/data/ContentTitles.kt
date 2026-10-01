package org.vedareader.data

/** Display-only normalization. Prose and incomplete references stay as supplied. */
internal object ContentTitles {
    private val collectionPrefix = Regex("^\\d{2}\\.\\s*")
    // Display-only trims of the source collection names: ", with Audio" and the Sanskrit alias in parentheses.
    private val collectionSuffixes = listOf(
        Regex(",?\\s+with Audio\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\s*\\(Bh\u0101gavata Pur\u0101\u1e47a\\)", RegexOption.IGNORE_CASE),
    )
    // "<book words> 1.2.3", "<book words> 1.2.3-5" (hyphen or en dash); the book part may be any non-digit text.
    private val reference = Regex("^(\\D*?)[ \\t]*([1-9][0-9]*(?:\\.[1-9][0-9]*)*)\\.([1-9][0-9]*)(?:[ \\t]*[-–][ \\t]*([1-9][0-9]*))?$")
    // A bare "17" or "17-18" continues the previous part's book and chapter.
    private val continuation = Regex("^([1-9][0-9]*)(?:[ \\t]*[-–][ \\t]*([1-9][0-9]*))?$")

    fun display(source: String): String {
        var title = source.trim().replace(collectionPrefix, "")
        for (suffix in collectionSuffixes) title = title.replace(suffix, "").trim()
        var book: String? = null
        var chapter: String? = null
        val ranges = mutableListOf<IntRange>()
        for (part in title.split(',')) {
            val trimmed = part.trim()
            val first: Int
            val last: Int
            val match = reference.matchEntire(trimmed)
            if (match != null) {
                val nextBook = match.groupValues[1].trim()
                val nextChapter = match.groupValues[2]
                if (nextBook.isEmpty() && book == null && ranges.isNotEmpty()) return title
                if (book != null && (nextBook != book || nextChapter != chapter)) return title
                book = nextBook
                chapter = nextChapter
                first = match.groupValues[3].toIntOrNull() ?: return title
                last = if (match.groupValues[4].isEmpty()) first else match.groupValues[4].toIntOrNull() ?: return title
            } else {
                val bare = continuation.matchEntire(trimmed) ?: return title
                if (book == null) return title
                first = bare.groupValues[1].toIntOrNull() ?: return title
                last = if (bare.groupValues[2].isEmpty()) first else bare.groupValues[2].toIntOrNull() ?: return title
            }
            if (last < first) return title
            ranges += first..last
        }
        if (book == null || book.isEmpty()) return title
        // Merge intervals without enumerating verses (even an enormous valid range stays cheap).
        val sorted = ranges.sortedBy { it.first }
        val first = sorted.first().first
        var last = sorted.first().last
        for (range in sorted.drop(1)) {
            if (range.first.toLong() > last.toLong() + 1L) return title
            last = maxOf(last, range.last)
        }
        val shownBook = if (book.equals("bg", ignoreCase = true)) "BG" else book
        return if (first == last) "$shownBook $chapter.$first" else "$shownBook $chapter.$first-$last"
    }

    /**
     * Fills a blank sibling title only when both neighbours are single references of the same book and
     * chapter exactly two verses apart (BG 1.6, _, BG 1.8 -> "Bg 1.7"); anything else stays as supplied.
     */
    fun interpolateBlank(siblings: List<ContentNode>): List<ContentNode> {
        if (siblings.none { it.title.isBlank() }) return siblings
        return siblings.mapIndexed { index, node ->
            if (node.title.isNotBlank() || index == 0 || index == siblings.lastIndex) return@mapIndexed node
            val previous = single(siblings[index - 1].title) ?: return@mapIndexed node
            val next = single(siblings[index + 1].title) ?: return@mapIndexed node
            if (previous.first != next.first || previous.second != next.second) return@mapIndexed node
            if (next.third.toLong() != previous.third.toLong() + 2L) return@mapIndexed node
            node.copy(title = "${previous.first} ${previous.second}.${previous.third + 1}")
        }
    }

    /** (book as written, chapter segments, verse) for one plain reference such as "Bg 1.6"; null otherwise. */
    private fun single(title: String): Triple<String, String, Int>? {
        val match = reference.matchEntire(title.trim()) ?: return null
        if (match.groupValues[4].isNotEmpty() || match.groupValues[1].isBlank()) return null
        return Triple(match.groupValues[1].trim(), match.groupValues[2], match.groupValues[3].toIntOrNull() ?: return null)
    }
}