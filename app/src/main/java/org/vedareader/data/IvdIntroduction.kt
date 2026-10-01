package org.vedareader.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Read-only classification; the connection, source nodes and their ordering remain caller-owned. */
internal class IvdIntroduction(private val database: SQLiteDatabase) {
    /**
     * Returns original direct-child IDs, in source iteration order, only when at least two verified
      * front-matter Chapter/Section headings precede a non-front-matter direct LE_Chapter under a
      * book, collection or canto. Eligible headings have exact recognized names and no direct
      * semantic verse children. No non-front-matter chapter means no grouping. Unknown types stay
      * unchanged; numeric levels and advertised child counts are not semantic types.
     *
     * The supplied list must exactly match ALL database children, including ungrouped entries.
     * Invalid/ambiguous metadata fails closed with an empty set; SQLite failures become LOAD with
     * their original cause. Cancellation propagates. No cache, source writes or connection closure.
     */
    suspend fun records(parent: Int, children: List<ContentNode>): Set<Int> {
        currentCoroutineContext().ensureActive()
        if (parent <= 0 || children.size !in 2..MAX_CHILDREN || !database.isOpen || !database.isReadOnly) {
            return emptySet()
        }
        return try {
            verifiedRecords(parent, children.toList())
        } catch (failure: SQLiteException) {
            throw ReadingException(ReadingFailure.LOAD).also { it.initCause(failure) }
        }
    }

    private suspend fun verifiedRecords(parent: Int, children: List<ContentNode>): Set<Int> {
        // Equality lookups use contents.record and texts.recid indexes. LIMIT 2 detects ambiguity,
        // rather than choosing an arbitrary parent/text row from a malformed source.
        val work = database.rawQuery(
            "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record=? LIMIT 2",
            arrayOf(parent.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return emptySet()
            val heading = cursor.heading() ?: return emptySet()
            if (cursor.moveToNext()) return emptySet()
            heading
        }
        if (work.levelName !in PARENTS || work.node.record != parent) return emptySet()

        val result = linkedSetOf<Int>()
        var index = 0
        var previousEnd = parent
        var chapterSeen = false
        // One bounded parent-index scan with indexed text-header lookups, not a whole-book text
        // scan. Recognized prose chapters may be front matter; the first non-front-matter chapter
        // establishes the boundary. A left join keeps missing/duplicate headers visible so they
        // cannot conceal that boundary. Candidate verse checks also use direct-parent indexes.
        database.rawQuery(
            "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record " +
                "WHERE c.parent=? ORDER BY c.record LIMIT ${MAX_CHILDREN + 1}",
            arrayOf(parent.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val heading = cursor.heading() ?: return emptySet()
                val node = heading.node
                if (index >= children.size || node != children[index] || node.parent != parent ||
                    node.record <= previousEnd || node.end > work.node.end || heading.levelName.isEmpty()) {
                    return emptySet()
                }
                previousEnd = node.end
                index++
                if (!chapterSeen) {
                    // Even a chapter named Preface is a boundary when it contains direct verses.
                    // Neither a title nor an advertised zero child count can override those rows.
                    val frontMatter = heading.levelName in FRONT_MATTER_TYPES &&
                        isFrontMatter(node.title) && !hasDirectVerseChildren(node.record)
                    if (frontMatter) result += node.record
                    else if (heading.levelName == "LE_Chapter") chapterSeen = true
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return if (index == children.size && chapterSeen && result.size >= 2) result else emptySet()
    }

    private fun hasDirectVerseChildren(record: Int): Boolean = database.rawQuery(
        "SELECT 1 FROM contents c JOIN texts t ON t.recid=c.record " +
            "WHERE c.parent=? AND t.levelname='LE_Verse_Number' LIMIT 1",
        arrayOf(record.toString()),
    ).use { it.moveToFirst() }

    private data class Heading(val node: ContentNode, val levelName: String)

    private fun Cursor.heading(): Heading? {
        val record = sourceInt(0) ?: return null
        val parent = sourceInt(1) ?: return null
        val end = sourceInt(3) ?: return null
        val children = sourceInt(4) ?: return null
        val level = sourceInt(5) ?: return null
        if (record <= 0 || parent >= record || end < record) return null
        return Heading(ContentNode(record, parent, getString(2).orEmpty(), end, children, level,
            getString(6).orEmpty()), getString(7).orEmpty().trim())
    }

    /** Do not silently convert nulls, fractions, negative numbers or overflowing IDs to Int. */
    private fun Cursor.sourceInt(column: Int): Int? {
        if (getType(column) != Cursor.FIELD_TYPE_INTEGER) return null
        val value = getLong(column)
        return if (value in 0L..Int.MAX_VALUE.toLong()) value.toInt() else null
    }

    private fun isFrontMatter(title: String): Boolean {
        val normalized = buildString {
            for (character in title) {
                append(when {
                    character.isWhitespace() || Character.getType(character) == Character.DASH_PUNCTUATION.toInt() ||
                        character == '\u2212' -> ' '
                    character == '\u2019' || character == '\u2018' -> '\''
                    else -> character
                })
            }
        }.trim().lowercase(Locale.ROOT).replace(SPACES, " ")
        if (normalized in HEADINGS) return true
        // Explicit book abbreviations, never an arbitrary word/prose prefix or substring match.
        return normalized.substringBefore(' ') in BOOK_PREFIXES && normalized.substringAfter(' ', "") in HEADINGS
    }

    companion object {
        private const val MAX_CHILDREN = 12_000
        private const val SELECT = "SELECT c.record,c.parent,c.title,c.next_sibling,c.node_children,c.level,c.node_code,t.levelname"
        private val PARENTS = setOf("LE_Book", "LE_Collection", "LE_Canto")
        private val FRONT_MATTER_TYPES = setOf("LE_Section", "LE_Chapter")
        private val BOOK_PREFIXES = setOf("bg", "sb", "cc", "iso", "noi", "nod", "kb", "tlc")
        private val SPACES = Regex(" +")
        private val HEADINGS = setOf("setting the scene", "dedication", "preface", "introduction", "foreword",
            "acknowledgments", "acknowledgements", "author note", "author's note",
            "disciple succession", "disciplic succession", "the disciplic succession")
    }
}