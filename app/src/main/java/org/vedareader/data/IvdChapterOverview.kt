package org.vedareader.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.vedareader.reader.FolioText

/** Translation-only previews from source style metadata, never from guessed English headings. */
internal class IvdChapterOverview(private val database: SQLiteDatabase) {
    suspend fun load(chapter: ContentNode, children: List<ContentNode>): ChapterOverview? {
        currentCoroutineContext().ensureActive()
        val level = database.rawQuery("SELECT levelname FROM texts WHERE recid=? LIMIT 1", arrayOf(chapter.record.toString()))
            .use { if (it.moveToFirst()) it.getString(0).orEmpty().trim() else "" }
        if (level != "LE_Chapter" || children.isEmpty()) return null
        val verseIds = database.rawQuery(
            "SELECT c.record FROM contents c JOIN texts h ON h.recid=c.record " +
                "WHERE c.parent=? AND trim(h.levelname,char(9)||char(10)||char(13)||' ')='LE_Verse_Number' ORDER BY c.record",
            arrayOf(chapter.record.toString()),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getInt(0)) } }
        if (verseIds.isEmpty()) return null
        val verses = children.filter { it.record in verseIds }
        check(verses.size == verseIds.size && verses.size <= 2_000)
        check(verses.all { it.parent == chapter.record && it.record > chapter.record && it.end in it.record..chapter.end })
        check(verses.zipWithNext().all { (a, b) -> a.end < b.record })

        val stylePlaceholders = TRANSLATION_STYLES.joinToString(",") { "?" }
        // One indexed chapter-range scan. Android's SQLite planner made the previous three-table
        // range join take 4–6 seconds on the real corpus, despite fast plans on desktop SQLite.
        val arguments = (listOf(chapter.record.toString(), chapter.end.toString()) + TRANSLATION_STYLES).toTypedArray()
        val translations = mutableMapOf<Int, MutableList<String>>()
        var verseIndex = 0
        var rows = 0L
        var characters = 0L
        database.rawQuery(
            "SELECT recid,length(plain),CASE WHEN length(plain)<=200000 THEN plain ELSE NULL END FROM texts " +
                "WHERE recid BETWEEN ? AND ? AND trim(stylename,char(9)||char(10)||char(13)||' ') " +
                "IN ($stylePlaceholders) ORDER BY recid", arguments,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val record = cursor.getInt(0)
                while (verseIndex < verses.size && verses[verseIndex].end < record) verseIndex++
                if (verseIndex == verses.size) break
                val verse = verses[verseIndex]
                if (record < verse.record) continue // Never include chapter prose or nested, unrelated works.
                val length = cursor.getLong(1)
                rows++
                characters += length
                check(rows <= 12_000 && characters <= 2_000_000 && length <= 200_000) {
                    "Chapter translations exceed the overview limit"
                }
                val plain = FolioText.plain(cursor.getString(2).orEmpty())
                if (plain.isNotBlank()) translations.getOrPut(verse.record) { mutableListOf() }.add(plain)
            }
        }
        return ChapterOverview(chapter, verses.map { VersePreview(it, translations[it.record]?.joinToString("\n\n")) })
    }

    companion object {
        // Verified identifiers in the supplied IVD; source titles/language are not classification rules.
        // PA_Glossary entries are single-paragraph "verses": the entry itself is the preview.
        private val TRANSLATION_STYLES = listOf("PA_Translation", "PA_Spec_Translation",
            "PA_Deity_95Book_95Translation", "PA_Prema_45Dhvani_Translation", "PA_TKQ_47TLK_Translation",
            "PA_Sloka_Translation", "PA_Purple_95BG_95Translations", "PA_TKQTranslation", "PA_Glossary")
    }
}