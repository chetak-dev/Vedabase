package org.vedareader.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete reading units, using source levels and parent IDs rather than guessed titles. */
internal class IvdSections(private val database: SQLiteDatabase) {
    private data class Heading(val node: ContentNode, val levelName: String)

    suspend fun read(record: Int): ReadingSection {
        require(record > 0)
        val requested = heading(record) ?: throw ReadingException(ReadingFailure.LOAD)
        val ancestry = ancestors(requested)
        // TEXT / TRANSLATION / PURPORT inside a verse always read with the whole verse.
        val selected = ancestry.firstOrNull { it.levelName == VERSE } ?: requested
        if (selected.levelName in CONTAINERS) throw ReadingException(ReadingFailure.CHOOSE_SECTION)
        val kind = when (selected.levelName) {
            VERSE -> ReadingUnitKind.VERSE
            CHAPTER -> ReadingUnitKind.CHAPTER
            else -> ReadingUnitKind.SECTION
        }
        val unit = selected.node
        check(unit.record > 0 && unit.end >= unit.record)
        val selectedAncestors = ancestry.dropWhile { it.node.record != unit.record }
        val parents = selectedAncestors.drop(1)
        val work = parents.firstOrNull { it.levelName == "LE_Book" || it.levelName == "LE_Collection" }
            ?: selectedAncestors.last()
        return ReadingSection(unit, kind, parents.firstOrNull()?.node?.displayTitle.orEmpty(), completeText(unit),
            neighbour(selected, kind, work.node, false), neighbour(selected, kind, work.node, true))
    }

    private suspend fun ancestors(start: Heading): List<Heading> {
        val result = mutableListOf<Heading>()
        var current = start
        while (true) {
            currentCoroutineContext().ensureActive()
            check(result.size < 64 && result.none { it.node.record == current.node.record }) { "Invalid source hierarchy" }
            result.add(current)
            if (current.node.parent == 0) break
            val parent = heading(current.node.parent) ?: throw ReadingException(ReadingFailure.LOAD)
            check(parent.node.record < current.node.record && parent.node.end >= current.node.end) { "Invalid parent range" }
            current = parent
        }
        return result
    }

    private suspend fun completeText(node: ContentNode): List<Paragraph> {
        currentCoroutineContext().ensureActive()
        val arguments = arrayOf(node.record.toString(), node.end.toString())
        // Never silently truncate a verse or materialize an entire anthology.
        database.rawQuery("SELECT count(*),coalesce(sum(length(plain)),0) FROM texts WHERE recid BETWEEN ? AND ?", arguments).use {
            check(it.moveToFirst())
            if (it.getLong(0) > MAX_RECORDS || it.getLong(1) > MAX_CHARACTERS) throw ReadingException(ReadingFailure.SECTION_TOO_LARGE)
        }
        return database.rawQuery("SELECT recid,plain,stylename,levelname FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid", arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    add(Paragraph(cursor.getInt(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty().trim(), cursor.getString(3).orEmpty()))
                }
            }
        }
    }

    private suspend fun neighbour(selected: Heading, kind: ReadingUnitKind, work: ContentNode, forward: Boolean): ContentNode? {
        currentCoroutineContext().ensureActive()
        val comparison = if (forward) ">" else "<"
        val direction = if (forward) "ASC" else "DESC"
        val arguments = mutableListOf(selected.node.record.toString())
        val boundary = if (kind == ReadingUnitKind.SECTION) {
            arguments.add(selected.node.parent.toString())
            arguments.add(selected.node.level.toString())
            "c.parent=? AND c.level=?"
        } else {
            arguments.add(work.record.toString()); arguments.add(work.end.toString())
            "c.record BETWEEN ? AND ?"
        }
        arguments.add(selected.levelName)
        val result = database.rawQuery(
            "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record " +
                "WHERE c.record$comparison? AND $boundary AND coalesce(t.levelname,'')=? " +
                "ORDER BY c.record $direction LIMIT 1", arguments.toTypedArray(),
        ).use { if (it.moveToFirst()) it.heading() else null } ?: return null
        check(result.node.record > 0 && result.node.end >= result.node.record)
        if (forward) check(result.node.record > selected.node.end) else check(result.node.end < selected.node.record)
        // A nested work must not become an accidental continuation of the enclosing anthology.
        if (kind != ReadingUnitKind.SECTION) {
            val candidateParents = ancestors(result).drop(1)
            val candidateWork = candidateParents.firstOrNull { it.levelName == "LE_Book" || it.levelName == "LE_Collection" }
                ?: candidateParents.lastOrNull()
            if (candidateWork?.node?.record != work.record) return null
        }
        return result.node
    }

    private fun heading(record: Int): Heading? = database.rawQuery(
        "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record=? LIMIT 1", arrayOf(record.toString()),
    ).use { if (it.moveToFirst()) it.heading() else null }

    private fun Cursor.heading() = Heading(
        ContentNode(getInt(0), getInt(1), getString(2).orEmpty(), getInt(3), getInt(4), getInt(5), getString(6).orEmpty()),
        getString(7).orEmpty().trim(),
    )

    companion object {
        private const val VERSE = "LE_Verse_Number"
        private const val CHAPTER = "LE_Chapter"
        private val CONTAINERS = setOf("LE_Collection", "LE_Writings", "LE_Book", "LE_Canto")
        private const val MAX_RECORDS = 12_000L
        private const val MAX_CHARACTERS = 4_000_000L
        private const val SELECT = "SELECT c.record,c.parent,c.title,c.next_sibling,c.node_children,c.level,c.node_code,t.levelname"
    }
}