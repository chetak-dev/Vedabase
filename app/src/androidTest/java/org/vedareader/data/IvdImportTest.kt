package org.vedareader.data

import android.annotation.TargetApi
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Only generated SQLite fixtures: no downloads, corpus, picker, or shared app library state. */
@RunWith(AndroidJUnit4::class)
class IvdImportTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var store: LibraryStore
    private lateinit var repository: IvdRepository
    private val repositories = mutableListOf<IvdRepository>()

    private val libraryDirectory: File
        get() = File(context.noBackupFilesDir, "libraries")

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
        // Ordinary fixture imports must retain Android's real file-URI handling.
        assertSame(target.contentResolver, context.contentResolver)
        store = LibraryStore(context)
        repository = newRepository()
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)
    }

    @After
    fun tearDown() {
        try {
            repositories.forEach { it.close() }
        } finally {
            try {
                if (::context.isInitialized) context.deleteOwnedPreferences()
            } finally {
                // This is exclusively the UUID directory created by this test, never app storage.
                if (::root.isInitialized) check(root.deleteRecursively())
            }
        }
    }

    @Test
    fun validImportPreservesMetadataUnicodeRootsAndReopensPersistedLibrary() = runBlocking<Unit> {
        val source = fixture()
        assertSourceUnchanged(source) {
            val progress = mutableListOf<ImportProgress>()
            val imported = repository.import(Uri.fromFile(source), "  sa-deva-in  ") { progress += it }

            assertEquals(FIXTURE_TITLE, imported.title)
            assertEquals("sa-Deva-IN", imported.languageTag)
            assertEquals(FIXTURE_COPYRIGHT, imported.copyright)
            assertEquals(3L, imported.records)
            assertEquals(source.length(), imported.size)
            assertEquals(UUID.fromString(imported.id).toString(), imported.id)
            assertEquals("${imported.id}.ivd", imported.fileName)
            assertEquals(setOf(imported.fileName), libraryFileNames())
            assertArrayEquals(sha256(source), sha256(File(libraryDirectory, imported.fileName)))
            assertEquals(listOf(imported), store.libraries())
            assertEquals(imported.id, store.activeId)
            assertRoots(repository, imported.id)

            assertEquals(
                listOf(ImportStage.COPYING, ImportStage.CHECKING, ImportStage.FINISHING),
                progress.map { it.stage }.distinct(),
            )
            val copying = progress.filter { it.stage == ImportStage.COPYING }
            assertEquals(0L, copying.first().copiedBytes)
            assertEquals(source.length(), copying.last().copiedBytes)
            assertTrue(copying.all { it.percent == null })

            repository.close()
            val reopenedStore = LibraryStore(context)
            val reopened = newRepository(libraryStore = reopenedStore)
            reopened.prepareStorage()
            assertEquals(listOf(imported), reopenedStore.libraries())
            assertEquals(imported.id, reopenedStore.activeId)
            assertEquals(setOf(imported.fileName), libraryFileNames())
            assertRoots(reopened, imported.id)
        }
    }

    @Test
    fun invalidHeaderLeavesNoRegistryEntryOrFiles() = runBlocking<Unit> {
        val source = newFixtureFile().apply { writeText("Not SQLite: generated invalid fixture only.") }
        val progress = assertRejected(source, ImportFailure.INVALID_DATABASE)
        assertTrue(progress.none { it.stage == ImportStage.CHECKING })
    }

    @Test
    fun validSqliteMissingAnyRequiredTableIsRejectedWithoutPublishing() = runBlocking<Unit> {
        for (table in listOf("contents", "texts", "words", "docinfo", "objects")) {
            val source = fixture { it.execSQL("DROP TABLE $table") }
            assertRejected(source, ImportFailure.INVALID_DATABASE)
        }
    }

    @Test
    fun validSqliteMissingARequiredColumnIsRejectedWithoutPublishing() = runBlocking<Unit> {
        val source = fixture {
            it.execSQL("DROP TABLE words")
            // Recreate instead of ALTER ... DROP COLUMN, which older Android SQLite lacks.
            it.execSQL("CREATE TABLE words (word TEXT, indexbase INTEGER, data BLOB)")
        }
        assertRejected(source, ImportFailure.INVALID_DATABASE)
    }

    @Test
    fun truncatedDatabaseWithValidHeaderIsRejectedAndCleanedUp() = runBlocking<Unit> {
        val source = fixture()
        // Preserve the entire SQLite header but remove every complete database page.
        RandomAccessFile(source, "rw").use { it.setLength(100L) }
        assertRejected(source, ImportFailure.INVALID_DATABASE)
    }

    @Test
    fun corruptDatabasePageWithValidHeaderIsRejectedAndCleanedUp() = runBlocking<Unit> {
        val source = fixture()
        RandomAccessFile(source, "rw").use {
            it.seek(100L) // First b-tree page type, after the unmodified SQLite header.
            it.writeByte(0x7f) // Not a valid SQLite b-tree page type.
        }
        assertRejected(source, ImportFailure.INVALID_DATABASE)
    }

    @Test
    fun completeSchemaWithoutRootsOrParagraphsIsRejected() = runBlocking<Unit> {
        for (delete in listOf("DELETE FROM contents WHERE parent=0", "DELETE FROM texts")) {
            val source = fixture { it.execSQL(delete) }
            assertRejected(source, ImportFailure.INVALID_DATABASE)
        }
    }

    @Test
    fun cancellationAfterCopyProgressRemovesTheStagedFile() = runBlocking<Unit> {
        assertCancelledAt(ImportStage.COPYING)
    }

    @Test
    fun cancellationDuringCheckingRemovesTheStagedFile() = runBlocking<Unit> {
        assertCancelledAt(ImportStage.CHECKING)
    }

    @Test
    fun prepareStorageRemovesOnlyOwnedUnregisteredFilesAndIsIdempotent() = runBlocking<Unit> {
        val source = fixture()
        assertSourceUnchanged(source) {
            val registered = repository.import(Uri.fromFile(source), "en") {}
            val registeredFile = File(libraryDirectory, registered.fileName)
            val preserved = listOf(
                registeredFile,
                File(libraryDirectory, "personal.part"),
                File(libraryDirectory, "personal.ivd"),
                File(libraryDirectory, "notes-नमूना.txt"),
                File(libraryDirectory, "${UUID.randomUUID()}.ivd.bak"),
                File(libraryDirectory, "${UUID.randomUUID()}.part-journal"),
            )
            preserved.drop(1).forEach { it.writeText("Generated sentinel: ${it.name}") }
            val checksums = preserved.associateWith(::sha256)
            val abandonedPart = File(libraryDirectory, "${UUID.randomUUID()}.part")
            val orphanedDatabase = File(libraryDirectory, "${UUID.randomUUID()}.ivd")
            abandonedPart.writeText("Interrupted synthetic copy")
            orphanedDatabase.writeBytes(source.readBytes())
            assertTrue(abandonedPart.isFile)
            assertTrue(orphanedDatabase.isFile)

            repository.close()
            val reopenedStore = LibraryStore(context)
            val reopened = newRepository(libraryStore = reopenedStore)
            repeat(2) {
                reopened.prepareStorage()
                assertEquals(preserved.map { it.name }.toSet(), libraryFileNames())
                assertFalse(abandonedPart.exists())
                assertFalse(orphanedDatabase.exists())
                checksums.forEach { (file, hash) -> assertArrayEquals(file.name, hash, sha256(file)) }
                assertEquals(listOf(registered), reopenedStore.libraries())
                assertEquals(registered.id, reopenedStore.activeId)
            }
            assertRoots(reopened, registered.id)
        }
    }

    @Test
    fun malformedAndUndefinedLanguageTagsAreRejectedBeforeCopying() = runBlocking<Unit> {
        val source = fixture()
        for (language in listOf("", "en_US", "en--US", "und", " UND ", "und-Latn", "x-fixture")) {
            val progress = assertRejected(source, ImportFailure.LANGUAGE, language = language)
            assertTrue("No copying for language '$language'", progress.isEmpty())
        }
    }

    @Test
    fun sequentialImportsHaveDistinctIdsAndPersistTheNewestActiveLibrary() = runBlocking<Unit> {
        val source = fixture()
        assertSourceUnchanged(source) {
            val first = repository.import(Uri.fromFile(source), "en") {}
            val second = repository.import(Uri.fromFile(source), "hi") {}
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.fileName, second.fileName)
            assertEquals("en", first.languageTag)
            assertEquals("hi", second.languageTag)
            assertEquals(listOf(first, second), store.libraries())
            assertEquals(second.id, store.activeId)

            repository.close()
            val reopenedStore = LibraryStore(context)
            val reopened = newRepository(libraryStore = reopenedStore)
            reopened.prepareStorage()
            assertEquals(listOf(first, second), reopenedStore.libraries())
            assertEquals(second.id, reopenedStore.activeId)
            assertEquals(setOf(first.fileName, second.fileName), libraryFileNames())
            for (imported in listOf(first, second)) {
                assertArrayEquals(sha256(source), sha256(File(libraryDirectory, imported.fileName)))
                assertRoots(reopened, imported.id)
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun contentUriWithAccurateSizeImportsUsingReadOnlyAccess() = runBlocking<Unit> {
        val source = fixture()
        val content = providerSource(source, source.length())
        assertSourceUnchanged(source) {
            val progress = mutableListOf<ImportProgress>()
            val imported = content.repository.import(content.provider.uri, "en") { progress += it }
            val copying = progress.filter { it.stage == ImportStage.COPYING }
            assertEquals(0, copying.first().percent)
            assertEquals(100, copying.last().percent)
            assertEquals(source.length(), copying.last().copiedBytes)
            assertEquals(listOf(imported), store.libraries())
            assertEquals(imported.id, store.activeId)
            assertEquals(setOf(imported.fileName), libraryFileNames())
            assertArrayEquals(sha256(source), sha256(File(libraryDirectory, imported.fileName)))
            assertRoots(content.repository, imported.id)
            content.provider.assertReadOnlyAccess()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun reportedSizeMismatchIncludingZeroIsRejectedBeforeValidation() = runBlocking<Unit> {
        val source = fixture()
        for (reported in listOf(source.length() - 1, source.length() + 1, 0L)) {
            val content = providerSource(source, reported)
            val progress = assertRejected(
                source, ImportFailure.INCOMPLETE,
                importingRepository = content.repository, uri = content.provider.uri,
            )
            assertEquals(source.length(), progress.last().copiedBytes)
            assertTrue(progress.all { it.stage == ImportStage.COPYING })
            content.provider.assertReadOnlyAccess()
        }
    }

    private fun newRepository(
        repositoryContext: Context = context,
        libraryStore: LibraryStore = store,
    ): IvdRepository = IvdRepository(repositoryContext, libraryStore).also { repositories += it }

    private fun assertRoots(reader: IvdRepository, libraryId: String) {
        val roots = reader.children(libraryId, 0)
        // Exact nodes also check order, parent filtering, ranges, and child counts; no reading/search.
        assertEquals(ROOTS, roots)
        assertEquals(listOf("नमूना संग्रह ā", "Coleção • cafe\u0301"), roots.map { it.displayTitle })
    }

    private fun libraryFileNames(): Set<String> {
        if (!libraryDirectory.exists()) return emptySet()
        return checkNotNull(libraryDirectory.list()) { "Cannot list the isolated library directory" }.toSet()
    }

    private fun assertNothingPublished() {
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)
        val reopenedStore = LibraryStore(context)
        assertTrue(reopenedStore.libraries().isEmpty())
        assertNull(reopenedStore.activeId)
        // Check every entry, not just .part files, so sidecars and orphan .ivd files also fail.
        assertEquals(emptySet<String>(), libraryFileNames())
    }

    private suspend fun assertRejected(
        source: File,
        reason: ImportFailure,
        language: String = "en",
        importingRepository: IvdRepository = repository,
        uri: Uri = Uri.fromFile(source),
    ): List<ImportProgress> {
        val progress = mutableListOf<ImportProgress>()
        assertSourceUnchanged(source) {
            val failure = try {
                importingRepository.import(uri, language) { progress += it }
                throw AssertionError("Expected import failure $reason")
            } catch (failure: LibraryImportException) {
                failure
            }
            assertEquals(reason, failure.reason)
            assertTrue(progress.none { it.stage == ImportStage.FINISHING })
            assertNothingPublished()
        }
        return progress
    }

    private suspend fun assertCancelledAt(stage: ImportStage) {
        val source = fixture()
        assertSourceUnchanged(source) {
            coroutineScope {
                val progress = mutableListOf<ImportProgress>()
                var cancelledWithStagedBytes = false
                val pending = async {
                    // Cancel only this child, never the runBlocking/JUnit coroutine.
                    val importJob = currentCoroutineContext().job
                    repository.import(Uri.fromFile(source), "en") { update ->
                        progress += update
                        if (update.stage == stage && (stage != ImportStage.COPYING || update.copiedBytes > 16L)) {
                            cancelledWithStagedBytes = libraryDirectory.listFiles().orEmpty().any {
                                it.extension == "part" && it.length() > 16L
                            }
                            importJob.cancel(CancellationException("Fixture cancellation at $stage"))
                        }
                    }
                }
                try {
                    pending.await()
                    throw AssertionError("Import should throw CancellationException at $stage")
                } catch (_: CancellationException) {
                    // The importer's ensureActive checks must propagate cancellation and run cleanup.
                }
                pending.join()
                currentCoroutineContext().ensureActive()
                assertTrue(pending.isCancelled)
                assertTrue("Cancellation must occur after a real staging file was written", cancelledWithStagedBytes)
                assertEquals(stage, progress.last().stage)
                assertTrue(progress.none { it.stage == ImportStage.FINISHING })
                assertNothingPublished()
            }
        }
    }

    private suspend fun assertSourceUnchanged(source: File, block: suspend () -> Unit) {
        val before = sha256(source)
        try {
            block()
        } finally {
            assertTrue("The selected source must still exist", source.isFile)
            assertArrayEquals("The selected source must remain byte-for-byte unchanged", before, sha256(source))
        }
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun newFixtureFile(): File {
        val directory = File(root, "fixtures")
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "${UUID.randomUUID()}.ivd")
    }

    private fun fixture(alter: (SQLiteDatabase) -> Unit = {}): File {
        val file = newFixtureFile()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Ensure the returned file is self-contained, not dependent on a live WAL connection.
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE contents (
                        title TEXT NOT NULL, record INTEGER NOT NULL, parent INTEGER NOT NULL,
                        next_sibling INTEGER NOT NULL, node_children INTEGER NOT NULL, level INTEGER NOT NULL,
                        simpletitle TEXT NOT NULL, node_code TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE texts (plain TEXT NOT NULL, recid INTEGER PRIMARY KEY, stylename TEXT, levelname TEXT)")
                db.execSQL("CREATE TABLE words (word TEXT, indexbase INTEGER, data BLOB, idx TEXT)")
                db.execSQL("CREATE TABLE docinfo (name TEXT PRIMARY KEY, valuex TEXT)")
                db.execSQL("CREATE TABLE objects (objectName TEXT, objectType TEXT, objectData BLOB)")
                db.execSQL("INSERT INTO docinfo (name,valuex) VALUES (?,?)", arrayOf("TT", FIXTURE_TITLE))
                db.execSQL("INSERT INTO docinfo (name,valuex) VALUES (?,?)", arrayOf("RM", FIXTURE_COPYRIGHT))

                val child = ContentNode(11, 10, "Nested synthetic entry, not a root", 19, 0, 1, "TEXT")
                // Deliberately insert roots out of order, and include a non-root between them.
                for (node in listOf(ROOTS[1], child, ROOTS[0])) {
                    db.execSQL(
                        "INSERT INTO contents (title,record,parent,next_sibling,node_children,level,simpletitle,node_code) VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf(node.title, node.record, node.parent, node.end, node.children, node.level, node.title, node.code),
                    )
                    db.execSQL(
                        "INSERT INTO texts (plain,recid,stylename,levelname) VALUES (?,?,?,?)",
                        arrayOf("Generated test paragraph ${node.record} only.", node.record, "Body", "Text"),
                    )
                }
                alter(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } // Close the SQLite database before any import, truncation, corruption, or hashing.
        return file
    }

    // Called exclusively from tests guarded by @SdkSuppress(minSdkVersion = 29).
    @TargetApi(29)
    private fun providerSource(source: File, reportedSize: Long): ProviderSource {
        val provider = FixtureProvider(source, reportedSize)
        provider.attachInfo(context, ProviderInfo().apply {
            authority = provider.uri.authority
            exported = false
        })
        // API 29+ can wrap an in-process provider without manifest registration or URI grants.
        val resolver = ContentResolver.wrap(provider)
        val providerContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = resolver
            override fun getApplicationContext(): Context = this
        }
        return ProviderSource(provider, newRepository(providerContext, LibraryStore(providerContext)))
    }

    private data class ProviderSource(val provider: FixtureProvider, val repository: IvdRepository)

    private class FixtureProvider(private val source: File, private val reportedSize: Long) : ContentProvider() {
        val uri: Uri = Uri.parse("content://org.vedareader.test.fixture/${source.name}")
        private var queries = 0
        private val openModes = mutableListOf<String>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            assertEquals(this.uri, uri)
            queries++
            return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(reportedSize)) }
        }

        override fun getType(uri: Uri): String = "application/vnd.sqlite3"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(this.uri, uri)
            openModes += mode
            assertEquals("The importer must never request writable source access", "r", mode)
            // The default openAssetFile uses UNKNOWN_LENGTH, so a false reported size cannot
            // itself truncate the stream and accidentally hide an overlong-document bug.
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw AssertionError("Source is read-only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw AssertionError("Source is read-only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw AssertionError("Source is read-only")

        fun assertReadOnlyAccess() {
            assertEquals(1, queries)
            assertEquals(listOf("r"), openModes)
        }
    }

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val preferencePrefix = "ivd-import-test-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        // Deliberately inherit the target ContentResolver; only provider-size tests override it.

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$preferencePrefix$name"
            ownedPreferences += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun deleteOwnedPreferences() {
            ownedPreferences.forEach { check(baseContext.deleteSharedPreferences(it)) }
        }

        private fun directory(name: String): File = File(root, name).also {
            check(it.isDirectory || it.mkdirs())
        }
    }

    companion object {
        private const val FIXTURE_TITLE = "Synthetic library — परीक्षण"
        private const val FIXTURE_COPYRIGHT = "© 2026 Test fixture contributors; generated data only."
        private val ROOTS = listOf(
            ContentNode(10, 0, "01. नमूना संग्रह ā", 19, 1, 0, "BOOK"),
            ContentNode(30, 0, "02. Coleção • cafe\u0301", 39, 0, 0, "BOOK"),
        )
    }
}