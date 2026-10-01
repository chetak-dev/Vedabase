package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Generated SQLite only; no importer, corpus, production preferences, or shared library files. */
@RunWith(AndroidJUnit4::class)
class IvdPagingTest {
    private lateinit var root: File
    private lateinit var context: IsolatedContext
    private lateinit var store: LibraryStore
    private lateinit var repository: IvdRepository
    private lateinit var library: LibraryInfo
    private lateinit var databaseFile: File
    private lateinit var originalHash: ByteArray
    private var originalSize = 0L
    private lateinit var rasters: List<ObjectFixture>

    private val location: ReadingLocation
        get() = section.location(library.id)

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        context = IsolatedContext(target, root)
        store = LibraryStore(context)
        assertTrue(store.libraries().isEmpty())
        assertNull(store.activeId)

        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "libraries").also { check(it.mkdirs()) }
        databaseFile = File(directory, "$id.ivd")
        rasters = rasterFixtures()
        createFixture(databaseFile)
        originalSize = databaseFile.length()
        originalHash = sha256(databaseFile)
        library = LibraryInfo(
            id, "Generated paging fixture", "sa-Latn", databaseFile.name, originalSize,
            records.size.toLong() + siblingRecords.size, "Synthetic test data only.",
        )
        store.addLibrary(library)
        repository = IvdRepository(context, store)
        assertEquals(listOf(library), store.libraries())
    }

    @After
    fun tearDown() {
        try {
            if (::repository.isInitialized) repository.close()
            if (::originalHash.isInitialized) assertSourceUnchanged()
        } finally {
            try {
                if (::context.isInitialized) context.deleteOwnedPreferences()
            } finally {
                // Delete only the UUID directory this test created, never the real app library.
                if (::root.isInitialized) {
                    check(UUID.fromString(root.name).toString() == root.name)
                    check(root.deleteRecursively())
                }
            }
        }
    }

    @Test
    fun forwardPagesContainAllIrregularIdsExactlyOnceWithAnInclusiveEnd() {
        val sourceNode = requireNotNull(repository.node(library.id, section.record))
        assertEquals(section, sourceNode)
        assertEquals(location, sourceNode.location(library.id))
        val pages = forwardPages()
        assertEquals(listOf(60, 60, 7), pages.map { it.paragraphs.size })
        assertEquals(records.chunked(60), pages.map { page -> page.paragraphs.map { it.record } })
        assertEquals(listOf(null, records[0], records[60]), pages.map { it.previous })
        assertEquals(listOf(records[60], records[120], null), pages.map { it.next })

        val actual = pages.flatMap { page -> page.paragraphs.map { it.record } }
        assertEquals(records, actual)
        assertEquals(actual.size, actual.toSet().size)
        assertEquals(section.end, actual.last())
        assertTrue(actual.all { it in section.record..section.end })
        assertTrue(actual.zipWithNext().map { (a, b) -> b - a }.distinct().size > 1)
        assertTrue(actual.none { it in siblingRecords })
    }

    @Test
    fun backwardTokensRecreatePreviousPagesAcrossIrregularRecordGaps() {
        val pages = forwardPages()
        var current = pages.last()
        for (index in pages.lastIndex downTo 1) {
            val previous = requireNotNull(current.previous)
            assertEquals(records[(index - 1) * 60], previous)
            current = repository.page(location.copy(position = previous))
            assertEquals(pages[index - 1], current)
            val forward = repository.page(location.copy(position = requireNotNull(current.next)))
            assertEquals(pages[index], forward)
        }
        assertNull(current.previous)
        assertEquals(pages.first(), current)
    }

    @Test
    fun cursorsInsideGapsUseRealRowsAndRespectTheLowerSectionBound() {
        for (precedingIndex in listOf(0, 59, 60, 95, 119, 125)) {
            val cursor = records[precedingIndex] + 1
            assertFalse(cursor in records)
            val page = repository.page(location.copy(position = cursor))
            val following = records.drop(precedingIndex + 1)
            assertEquals("cursor=$cursor", following.take(60), page.paragraphs.map { it.record })
            assertEquals(following.getOrNull(60), page.next)
            assertEquals(records.take(precedingIndex + 1).takeLast(60).firstOrNull(), page.previous)
        }

        // Both the section start and current cursor fall in gaps, not on rows.
        val bounded = location.copy(start = records[25] + 1, position = records[60] + 1, end = records[100])
        val page = repository.page(bounded)
        assertEquals(records.subList(61, 101), page.paragraphs.map { it.record })
        assertEquals(records[26], page.previous)
        assertNull(page.next)
        val previous = repository.page(bounded.copy(position = requireNotNull(page.previous)))
        assertNull(previous.previous)
        assertEquals(records[26], previous.paragraphs.first().record)
    }

    @Test
    fun nullStringsBecomeEmptyButOnlyStylesAreTrimmedAndUnicodeStaysOriginal() {
        val paragraphs = forwardPages().flatMap { it.paragraphs }.associateBy { it.record }
        assertEquals(Paragraph(records[7], "", "", ""), paragraphs.getValue(records[7]))
        assertEquals(Paragraph(records[8], " \t ", "", " \t "), paragraphs.getValue(records[8]))

        val original = paragraphs.getValue(records[63])
        assertEquals(UNICODE_TEXT, original.text)
        assertArrayEquals(UNICODE_TEXT.toByteArray(Charsets.UTF_8), original.text.toByteArray(Charsets.UTF_8))
        assertEquals("PA_Fixture ā a\u0304", original.style)
        assertEquals(UNICODE_LEVEL, original.level)
        assertTrue("Folio markup is source data here, not rendered or stripped", original.text.contains("<PS:\"Fixture\">"))
    }

    @Test
    fun emptySectionAndInclusiveSingleRecordDoNotLeakIntoNeighbours() {
        assertEquals(emptySection, repository.node(library.id, emptySection.record))
        assertEquals(ReadingPage(emptyList(), null, null), repository.page(emptySection.location(library.id)))

        val atEnd = repository.page(location.copy(position = section.end))
        assertEquals(listOf(section.end), atEnd.paragraphs.map { it.record })
        assertEquals(records[66], atEnd.previous)
        assertNull(atEnd.next)

        val singleton = repository.page(location.copy(start = section.end, position = section.end))
        assertEquals(atEnd.paragraphs, singleton.paragraphs)
        assertNull(singleton.previous)
        assertNull(singleton.next)
    }

    @Test
    fun invalidBoundsThrowRatherThanQueryingOutsideTheSection() {
        for (invalid in listOf(
            location.copy(start = 0),
            location.copy(start = -1),
            location.copy(end = location.start - 1),
            location.copy(position = location.start - 1),
            location.copy(position = location.end + 1),
            location.copy(position = Int.MIN_VALUE),
            location.copy(position = Int.MAX_VALUE),
        )) {
            assertThrows(IllegalArgumentException::class.java) { repository.page(invalid) }
        }
        assertEquals(records.take(60), repository.page(location).paragraphs.map { it.record })
    }

    @Test
    fun readsAndReopensLeaveDatabaseBytesAndIsolatedRegistrationUnchanged() {
        val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE).all.toMap()
        val expectedPages = forwardPages()
        repeat(2) {
            assertEquals(expectedPages, forwardPages())
            assertEquals(section, repository.node(library.id, section.record))
            assertTrue(repository.children(library.id, 0).contains(section))
            rasters.forEach(::assertImage)
            repository.close()
            assertSourceUnchanged()
            assertEquals(setOf(databaseFile.name), requireNotNull(databaseFile.parentFile!!.list()).toSet())

            val reopenedStore = LibraryStore(context)
            assertEquals(listOf(library), reopenedStore.libraries())
            assertEquals(library.id, reopenedStore.activeId)
            repository = IvdRepository(context, reopenedStore)
        }
        assertEquals(expectedPages, forwardPages())
        assertEquals(preferences, context.getSharedPreferences("reader", Context.MODE_PRIVATE).all)
    }

    @Test
    fun readerImageAcceptsEveryAllowlistedRasterTypeAndMapsLegacyObToBmp() {
        assertEquals(setOf("OB", "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"), rasters.map { it.type }.toSet())
        for (fixture in rasters) {
            assertImage(fixture)
            // Fixtures really encode a generated pixel, not arbitrary bytes labelled as images.
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(fixture.bytes, 0, fixture.bytes.size))
            try {
                assertEquals(1, bitmap.width)
                assertEquals(1, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
        assertNull(repository.readerImage(library.id, "not-present.png"))
    }

    @Test
    fun readerImageRejectsNonRasterTypesAndOversizeButAllowsExactlyOneMiB() {
        for (fixture in rejectedObjects) {
            assertNull(fixture.type, repository.readerImage(library.id, fixture.name))
        }
        assertEquals(IMAGE_LIMIT, atLimit.bytes.size)
        assertImage(atLimit)
        assertEquals(IMAGE_LIMIT + 1, oversized.bytes.size)
        assertNull(repository.readerImage(library.id, oversized.name))
        assertNull(repository.readerImage(library.id, "missing-data.png"))
        assertNull(repository.readerImage(library.id, "missing-type.png"))
    }

    @Test
    fun imageNamesAreBoundParametersSoSqlLookingFilenamesAreHarmless() {
        for (name in listOf(
            "' OR 1=1 --",
            "missing' UNION SELECT 'image/png',X'010203' --",
            "missing'; DROP TABLE texts; --",
            "../../reader.xml",
        )) {
            assertNull(name, repository.readerImage(library.id, name))
        }
        // A real row with SQL-looking punctuation must still be addressable by its exact name.
        assertImage(quotedName)
        assertImage(rasters.first())
        assertEquals(records.take(60), repository.page(location).paragraphs.map { it.record })
        assertSourceUnchanged()
    }

    private fun forwardPages(): List<ReadingPage> {
        val pages = mutableListOf<ReadingPage>()
        var cursor = location.start
        // Bound the traversal even if a regression produces a cyclic or endless next token.
        repeat(4) {
            val page = repository.page(location.copy(position = cursor))
            pages += page
            val next = page.next ?: return pages
            assertTrue("Page token must move forward inside the source range", next > cursor && next <= location.end)
            cursor = next
        }
        throw AssertionError("127 synthetic records must not require a fifth page")
    }

    private fun assertImage(fixture: ObjectFixture) {
        val image = requireNotNull(repository.readerImage(library.id, fixture.name)) { fixture.name }
        assertEquals(fixture.name, if (fixture.type == "OB") "image/bmp" else fixture.type, image.mimeType)
        assertArrayEquals(fixture.name, fixture.bytes, image.bytes)
    }

    private fun assertSourceUnchanged() {
        assertTrue("The isolated source still exists", databaseFile.isFile)
        assertEquals(originalSize, databaseFile.length())
        assertArrayEquals("Every read must leave the SQLite bytes unchanged", originalHash, sha256(databaseFile))
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun createFixture(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE contents (
                        record INTEGER PRIMARY KEY, parent INTEGER, title TEXT, next_sibling INTEGER,
                        node_children INTEGER, level INTEGER, simpletitle TEXT, node_code TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE texts (recid INTEGER PRIMARY KEY, plain TEXT, stylename TEXT, levelname TEXT)")
                db.execSQL("CREATE TABLE objects (objectName TEXT PRIMARY KEY, objectType TEXT, objectData BLOB)")
                for (node in listOf(
                    emptySection, section,
                    ContentNode(section.record - 10, 0, "Generated preceding sibling", section.record - 1, 0, 0),
                    ContentNode(section.end + 1, 0, "Generated following sibling", emptySection.record - 1, 0, 0),
                    ContentNode(emptySection.end + 1, 0, "Generated final sibling", emptySection.end + 5, 0, 0),
                )) {
                    db.execSQL(
                        "INSERT INTO contents (record,parent,title,next_sibling,node_children,level,simpletitle,node_code) VALUES (?,?,?,?,?,?,?,?)",
                        arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.title, node.code),
                    )
                }
                // Insert out of order, and place sentinel text just outside both section ranges.
                for (record in records.reversed() + siblingRecords) {
                    val values: Array<Any?> = when (record) {
                        records[7] -> arrayOf(record, null, null, null)
                        records[8] -> arrayOf(record, " \t ", " \r\n\t ", " \t ")
                        records[63] -> arrayOf(record, UNICODE_TEXT, " \tPA_Fixture ā a\u0304\r\n ", UNICODE_LEVEL)
                        else -> arrayOf(record, "Generated test record $record only.", "Body", "Text")
                    }
                    db.execSQL("INSERT INTO texts (recid,plain,stylename,levelname) VALUES (?,?,?,?)", values)
                }
                for (fixture in rasters + rejectedObjects + listOf(atLimit, oversized, quotedName)) {
                    db.execSQL("INSERT INTO objects (objectName,objectType,objectData) VALUES (?,?,?)",
                        arrayOf(fixture.name, fixture.type, fixture.bytes))
                }
                db.execSQL("INSERT INTO objects (objectName,objectType,objectData) VALUES (?,?,?)",
                    arrayOf<Any?>("missing-data.png", "image/png", null))
                db.execSQL("INSERT INTO objects (objectName,objectType,objectData) VALUES (?,?,?)",
                    arrayOf<Any?>("missing-type.png", null, rasters.first().bytes))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } // Close the writable fixture connection before registration, hashing, or reader access.
    }

    @Suppress("DEPRECATION") // WEBP is supported throughout the app's minSdk range.
    private fun rasterFixtures(): List<ObjectFixture> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff112233.toInt())
            fun encode(format: Bitmap.CompressFormat): ByteArray = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, 100, output))
                output.toByteArray()
            }
            return listOf(
                ObjectFixture("pixel.png", "image/png", encode(Bitmap.CompressFormat.PNG)),
                ObjectFixture("pixel.jpg", "image/jpeg", encode(Bitmap.CompressFormat.JPEG)),
                ObjectFixture("pixel.webp", "image/webp", encode(Bitmap.CompressFormat.WEBP)),
                ObjectFixture("pixel.gif", "image/gif", byteArrayOf(
                    0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80.toByte(), 0, 0,
                    0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0,
                    2, 2, 0x44, 1, 0, 0x3b,
                )),
                ObjectFixture("pixel.bmp", "image/bmp", bitmapBytes()),
                ObjectFixture("legacy.bmp", "OB", bitmapBytes()),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private data class ObjectFixture(val name: String, val type: String, val bytes: ByteArray)

    private class IsolatedContext(target: Context, private val root: File) : ContextWrapper(target) {
        private val preferencePrefix = "ivd-paging-test-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")

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
        private const val IMAGE_LIMIT = 1024 * 1024
        private const val UNICODE_TEXT = " \tGenerated नमूना हिन्दी বাংলা ā a\u0304 ī i\u0304 ṛ r\u0323 ś ṣ ñ\r\n<PS:\"Fixture\">text & data.  "
        private const val UNICODE_LEVEL = " \tLE_Fixture नमूना a\u0304 \r\n"
        private val records = buildList {
            val gaps = listOf(2, 7, 3, 11, 5)
            var record = 100
            repeat(127) { index ->
                add(record)
                record += gaps[index % gaps.size]
            }
        }
        private val section = ContentNode(records.first(), 0, "01. Generated section", records.last(), 0, 0, "TEXT")
        private val emptySection = ContentNode(section.end + 50, 0, "02. Generated empty section", section.end + 59, 0, 0, "TEXT")
        private val siblingRecords = listOf(section.record - 1, section.end + 1, emptySection.record - 1, emptySection.end + 1)
        private val atLimit = ObjectFixture("limit.bmp", "image/bmp", bitmapBytes(IMAGE_LIMIT))
        private val oversized = ObjectFixture("oversized.bmp", "image/bmp", bitmapBytes(IMAGE_LIMIT + 1))
        private val quotedName = ObjectFixture("pixel' OR 1=1 --.bmp", "image/bmp", bitmapBytes())
        private val rejectedObjects = listOf(
            ObjectFixture("html-disguised-as.png", "text/html", "<p>Generated HTML fixture.</p>".toByteArray(Charsets.UTF_8)),
            ObjectFixture("vector.png", "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(Charsets.UTF_8)),
            ObjectFixture("sound.png", "audio/mpeg", "ID3 generated fixture".toByteArray(Charsets.UTF_8)),
            ObjectFixture("sound.ogg", "audio/ogg", "OggS generated fixture".toByteArray(Charsets.UTF_8)),
            ObjectFixture("video.mp4", "video/mp4", byteArrayOf(0, 1, 2, 3)),
            ObjectFixture("unknown.bmp", "application/octet-stream", bitmapBytes()),
            ObjectFixture("parameters.bmp", "image/bmp; charset=utf-8", bitmapBytes()),
        )

        /** A generated 1x1, 24-bit BMP; optional trailing padding exercises the exact byte limit. */
        private fun bitmapBytes(size: Int = 58): ByteArray {
            require(size >= 58)
            return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('B'.code.toByte())
                put('M'.code.toByte())
                putInt(size)
                putInt(0)
                putInt(54)
                putInt(40)
                putInt(1)
                putInt(1)
                putShort(1.toShort())
                putShort(24.toShort())
                putInt(0)
                putInt(4)
                repeat(4) { putInt(0) }
                put(byteArrayOf(0x33, 0x22, 0x11, 0))
            }.array()
        }
    }
}