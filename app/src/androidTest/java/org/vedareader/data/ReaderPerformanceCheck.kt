package org.vedareader.data

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.vedareader.reader.FolioText
import java.io.File

/** Explicitly opt-in, read-only diagnostics on the user's already imported corpus. No registry writes. */
class ReaderPerformanceCheck {
    @Test
    fun measureImportedReadingStages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureImportedLibrary") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = LibraryStore(context)
        val library = store.libraries().first { it.id == store.activeId }
        val repository = IvdRepository(context, store)
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        fun <T> timed(label: String, block: () -> T): T {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = block()
            report("PERF $label ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0}")
            return result
        }
        try {
            val root = repository.children(library.id, 0).firstOrNull { it.code == "bgaiiwa" }
            assumeTrue(root != null)
            val chapters = repository.children(library.id, requireNotNull(root).record)
            val chapter = chapters.first { it.children > 30 }
            repeat(3) { run ->
                val children = timed("children run=$run") { repository.children(library.id, chapter.record) }
                var started = SystemClock.elapsedRealtimeNanos()
                val overview = repository.chapterOverview(library.id, chapter.record, children)
                report("PERF chapter run=$run ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} rows=${overview?.verses?.size}")
                val verse = requireNotNull(overview).verses.first().node
                started = SystemClock.elapsedRealtimeNanos()
                val section = repository.readingSection(library.id, verse.record)
                report("PERF verse run=$run ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0} rows=${section.paragraphs.size}")
                timed("html run=$run") { FolioText.document(section.paragraphs, library.languageTag, ReaderPreferences()) }
            }
            SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "libraries/${library.fileName}").path,
                null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                val sql = "EXPLAIN QUERY PLAN SELECT count(*) FROM contents v JOIN texts h ON h.recid=v.record " +
                    "JOIN texts p ON p.recid BETWEEN v.record AND v.next_sibling WHERE v.parent=? " +
                    "AND trim(h.levelname)='LE_Verse_Number' AND trim(p.stylename)='PA_Translation'"
                db.rawQuery(sql, arrayOf(chapter.record.toString())).use { cursor ->
                    while (cursor.moveToNext()) report("PLAN ${cursor.getString(3)}")
                }
            }
        } finally { repository.close() }
    }
}