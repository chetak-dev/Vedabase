package org.vedareader.ui

import androidx.annotation.StringRes
import org.vedareader.R
import org.vedareader.data.ContentNode

/** Which root collections the home page features, hides, and how the rest are grouped and ordered. */
internal object HomeLayout {
    private val featuredCodes = listOf("bgaiiwa", "sbbp", "scc")
    /** Source housekeeping roots ("What's New?", front matter, book reviews); "The End" has no children. */
    private val hiddenCodes = setOf("wn", "bvfm", "br")
    private val numbered = Regex("^\\s*(\\d{2})\\.")

    data class Group(@StringRes val title: Int, val prefixes: List<String>, val tint: Int)

    /** Order requested for the shelves after the three scriptures; anything unlisted lands in the archive. */
    val groups = listOf(
        Group(R.string.home_group_books, listOf("05", "16", "27", "28", "24"), 0),
        Group(R.string.home_group_lectures, listOf("06", "22", "23"), 1),
        Group(R.string.home_group_songs, listOf("25", "31"), 2),
    )

    data class Shelf(@StringRes val title: Int, val tint: Int, val collections: List<ContentNode>)
    data class Arrangement(val featured: List<ContentNode>, val shelves: List<Shelf>)

    fun isFeatured(node: ContentNode) = node.code in featuredCodes

    fun arrange(collections: List<ContentNode>): Arrangement {
        val visible = collections.filterNot { it.code in hiddenCodes || (it.children == 0 && it.title.trim() == "The End") }
        val featured = visible.filter { isFeatured(it) }.sortedBy { featuredCodes.indexOf(it.code) }
        val rest = visible.filterNot { isFeatured(it) }
        val placed = mutableSetOf<Int>()
        val shelves = groups.mapNotNull { group ->
            val members = group.prefixes.mapNotNull { prefix -> rest.firstOrNull { prefixOf(it) == prefix } }
            placed += members.map { it.record }
            if (members.isEmpty()) null else Shelf(group.title, group.tint, members)
        }
        val archive = rest.filter { it.record !in placed }
        return Arrangement(featured, if (archive.isEmpty()) shelves else shelves + Shelf(R.string.home_group_archive, 3, archive))
    }

    private fun prefixOf(node: ContentNode) = numbered.find(node.title)?.groupValues?.get(1)
}
