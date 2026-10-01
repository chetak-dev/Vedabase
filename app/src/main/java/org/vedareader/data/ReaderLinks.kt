package org.vedareader.data

/** Local Folio link kinds the reader can act on; external web/program links are never emitted. */
enum class ReaderLinkKind { JUMP, QUERY, POPUP, AUDIO }

data class ReaderLink(val kind: ReaderLinkKind, val target: String)

/** Popup source markup from the `popup` table; rendered through the same closed Folio pipeline. */
data class ReaderPopup(val title: String, val style: String, val source: String)

sealed interface LinkResolution {
    data class Open(val location: ReadingLocation) : LinkResolution
    data class Popup(val popup: ReaderPopup) : LinkResolution
    /** A free-text Folio query the app cannot map to one heading; offered as a normal search. */
    data class Search(val query: String) : LinkResolution
    data object Unavailable : LinkResolution
}

/** Pure parsing of Folio query-link syntax; no database access. */
object LinkQuery {
    const val MAX_TARGET_CHARACTERS = 512

    /** `[Headings <level>,<title>]` -> level display name and folded title; null when not that shape. */
    data class Heading(val level: String, val title: String)

    private val headings = Regex("\\[\\s*headings\\s+([^,\\]]{1,64}),([^\\]]{1,300})]?", RegexOption.IGNORE_CASE)
    private val levelReference = Regex("\\[\\s*level\\s+([^:\\]]{1,64}):\\(([^)\\]]{1,300})\\)\\s*]?", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")

    fun heading(target: String): Heading? {
        if (target.length > MAX_TARGET_CHARACTERS) return null
        val match = headings.find(target) ?: levelReference.find(target) ?: return null
        val level = fold(match.groupValues[1])
        val title = fold(match.groupValues[2])
        return if (level.isEmpty() || title.isEmpty()) null else Heading(level, title)
    }

    /** Text suitable for the search box when a query link is not a heading lookup. */
    fun searchText(target: String): String? {
        if (target.length > MAX_TARGET_CHARACTERS) return null
        val text = target.replace(Regex("<[^>]{0,64}>"), " ") // Stray inline Folio codes inside queries.
            .replace(Regex("[\\[\\]\"()#]"), " ")
            .replace(spaces, " ").trim()
        if (text.isEmpty()) return null
        val words = SearchText.tokens(text)
        if (words.isEmpty() || words.size > SearchText.MAX_QUERY_WORDS) return null
        return text.take(SearchText.MAX_QUERY_CHARACTERS)
    }

    /** Same folding as contents.simpletitle: lowercase, single spaces, trimmed. */
    fun fold(value: String): String = value.trim().lowercase(java.util.Locale.ROOT).replace(spaces, " ")
}
