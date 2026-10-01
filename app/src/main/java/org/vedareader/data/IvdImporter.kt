package org.vedareader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.UUID

/** Copies only the selected document. The original is never opened for writing. */
class IvdImporter(private val context: Context, private val store: LibraryStore) {
    private val directory = File(context.noBackupFilesDir, "libraries")
    private val lock = Mutex()

    /** An interrupted import is retried from the picker, never silently resumed without consent. */
    suspend fun prepareStorage() = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureDirectory()
            val registered = store.libraries().map { it.fileName }.toSet()
            directory.listFiles()?.forEach { file ->
                if (STAGING_NAME.matches(file.name) && file.name !in registered) {
                    check(file.delete()) { "Could not clean up an interrupted import" }
                }
            }
        }
    }

    suspend fun import(uri: Uri, language: String, progress: (ImportProgress) -> Unit): LibraryInfo = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureDirectory()
            val languageTag = normalizeLanguage(language)
            val id = UUID.randomUUID().toString()
            val temporary = File(directory, "$id.part")
            val destination = File(directory, "$id.ivd")
            var registered = false
            try {
                val expected = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    val column = it.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && it.moveToFirst() && !it.isNull(column)) it.getLong(column) else -1L
                } ?: -1L
                if (expected >= 0 && expected > availableBytes() - RESERVE) fail(ImportFailure.SPACE)
                progress(ImportProgress(ImportStage.COPYING, if (expected > 0) 0 else null))
                val input = context.contentResolver.openInputStream(uri) ?: fail(ImportFailure.OPEN)
                input.use {
                    val header = ByteArray(16)
                    var headerSize = 0
                    while (headerSize < header.size) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(header, headerSize, header.size - headerSize)
                        if (count < 0) fail(ImportFailure.INVALID_DATABASE)
                        headerSize += count
                    }
                    if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) fail(ImportFailure.INVALID_DATABASE)
                    temporary.outputStream().use { output ->
                        output.write(header)
                        val buffer = ByteArray(1024 * 1024)
                        var copied = header.size.toLong()
                        var previousPercent = -1
                        var lastStorageCheck = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (copied - lastStorageCheck >= 16L * 1024 * 1024 || lastStorageCheck == 0L) {
                                if (availableBytes() < RESERVE + count) fail(ImportFailure.SPACE)
                                lastStorageCheck = copied
                            }
                            output.write(buffer, 0, count)
                            copied += count
                            val percent = if (expected > 0) (copied.toDouble() / expected * 100).toInt().coerceIn(0, 100) else null
                            if (percent == null || percent != previousPercent) {
                                progress(ImportProgress(ImportStage.COPYING, percent, copied))
                                previousPercent = percent ?: -1
                            }
                        }
                        if (expected >= 0 && expected != copied) fail(ImportFailure.INCOMPLETE)
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                progress(ImportProgress(ImportStage.CHECKING))
                val info = try {
                    SQLiteDatabase.openDatabase(temporary.absolutePath, null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                        validate(db)
                        LibraryInfo(id, metadata(db, "TT").ifBlank { "Vedabase" }, languageTag,
                            destination.name, temporary.length(), scalar(db, "SELECT count(*) FROM texts"), metadata(db, "RM"))
                    }
                } catch (exception: SQLiteException) {
                    throw LibraryImportException(ImportFailure.INVALID_DATABASE).apply { initCause(exception) }
                }
                currentCoroutineContext().ensureActive()
                progress(ImportProgress(ImportStage.FINISHING))
                currentCoroutineContext().ensureActive()
                // Once publication starts, cancellation must not leave a registry pointing at a deleted file.
                withContext(NonCancellable) {
                    if (!temporary.renameTo(destination)) fail(ImportFailure.STORAGE)
                    store.addLibrary(info)
                    registered = true
                }
                info
            } finally {
                temporary.delete()
                if (!registered) destination.delete()
            }
        }
    }

    private suspend fun validate(db: SQLiteDatabase) {
        val schema = mapOf(
            "contents" to setOf("title", "record", "parent", "next_sibling", "node_children", "level", "simpletitle", "node_code"),
            "texts" to setOf("plain", "recid", "stylename", "levelname"),
            "words" to setOf("word", "indexbase", "data", "idx"),
            "docinfo" to setOf("name", "valuex"),
            "objects" to setOf("objectName", "objectType", "objectData"),
        )
        for ((table, required) in schema) {
            currentCoroutineContext().ensureActive()
            val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            if (!columns.containsAll(required)) fail(ImportFailure.INVALID_DATABASE)
        }
        // The argument limits reported errors, not the amount of the database checked.
        db.rawQuery("PRAGMA quick_check(1)", null).use {
            if (!it.moveToFirst() || it.getString(0) != "ok") fail(ImportFailure.INVALID_DATABASE)
        }
        currentCoroutineContext().ensureActive()
        if (scalar(db, "SELECT count(*) FROM contents WHERE parent=0") == 0L ||
            scalar(db, "SELECT count(*) FROM texts") == 0L) fail(ImportFailure.INVALID_DATABASE)
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) fail(ImportFailure.STORAGE)
    }
    private fun availableBytes() = StatFs(directory.absolutePath).availableBytes
    private fun metadata(db: SQLiteDatabase, key: String): String = db.rawQuery("SELECT valuex FROM docinfo WHERE name=? LIMIT 1", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
    private fun fail(reason: ImportFailure): Nothing = throw LibraryImportException(reason)

    companion object {
        private const val RESERVE = 64L * 1024 * 1024
        private val STAGING_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(part|ivd)")

        fun normalizeLanguage(language: String): String {
            val locale = try { Locale.Builder().setLanguageTag(language.trim()).build() }
            catch (_: IllformedLocaleException) { throw LibraryImportException(ImportFailure.LANGUAGE) }
            if (locale.language.isBlank() || locale.language == "und") throw LibraryImportException(ImportFailure.LANGUAGE)
            return locale.toLanguageTag()
        }
    }
}