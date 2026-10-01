package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** UUID-isolated synthetic user state only. Application context must never escape the fixture. */
@RunWith(AndroidJUnit4::class)
class AnnotationStoreTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: HighlightStore
    private lateinit var file: File
    private lateinit var sentinel: File
    private val sentinelBytes = "Synthetic source and bookmark sentinel कृष्ण".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "annotation-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "no_backup").also { it.mkdirs() }
            override fun getFilesDir(): File = File(root, "files").also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("Annotation storage must not access preferences: $name")
        }
        assertSame(context, context.applicationContext)
        sentinel = File(context.noBackupFilesDir, "synthetic-source-and-bookmarks.bin").also { it.writeBytes(sentinelBytes) }
        file = File(context.noBackupFilesDir, "user-state/highlights.json")
        store = HighlightStore(context)
    }

    @After
    fun tearDown() {
        try {
            if (::sentinel.isInitialized) assertArrayEquals(sentinelBytes, sentinel.readBytes())
        } finally {
            if (::root.isInitialized) {
                UUID.fromString(root.name.removePrefix("annotation-"))
                check(root.deleteRecursively())
            }
        }
    }

    @Test
    fun legacyJsonKeepsEveryIdColorAndNoteWithFillDefaultTrueWithoutRewritingOnRead() = runBlocking {
        val plain = sample("legacy-plain")
        val noted = sample("legacy-note").copy(start = 8, end = 15, note = "Legacy कृष्ण reflection", color = HighlightColor.PINK)
        writeLegacy(plain, noted)
        val before = file.readBytes()
        val loaded = store.load()
        assertEquals(setOf(plain, noted), loaded.toSet())
        assertTrue(loaded.all { it.highlighted && it.span.highlighted })
        assertTrue(loaded.single { it.id == noted.id }.span.noted)
        assertArrayEquals(before, file.readBytes())
        store.setNote(plain.id, LIBRARY, "New note")
        assertEquals(noted, HighlightStore(context).load().single { it.id == noted.id })
        val entries = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("highlights")
        repeat(entries.length()) { assertTrue(entries.getJSONObject(it).getBoolean("highlighted")) }
    }

    @Test
    fun legacyEntryWithoutNoteStillMigratesAsAnExplicitHighlight() = runBlocking {
        val original = sample()
        val entry = legacyEntry(original).also { it.remove("note") }
        writeEntries(JSONArray().put(entry))
        assertEquals(original, store.load().single())
    }

    @Test
    fun cancelledOrBlankStandaloneDraftDoesNotWriteAnAnnotation() = runBlocking {
        assertTrue(store.load().isEmpty())
        val selection = HighlightSelection(10, 0, 10, 7, "कृष्ण ā")
        assertFalse(selection.isEmpty) // Selection and an unsaved editor draft require no store call.
        assertFalse(file.exists())
        assertTrue(store.add(sample().copy(highlighted = false, note = " \n\t")).isEmpty())
        assertFalse(file.exists())
        assertTrue(HighlightStore(context).load().isEmpty())
    }

    @Test
    fun noteOnlyPersistsAcrossRestartAndClearingItsNoteDeletesTheAnnotation() = runBlocking {
        val note = sample().copy(highlighted = false, note = "कृष्ण a\u0304 🎵\nA reflection")
        assertEquals(listOf(note), store.add(note))
        val restored = HighlightStore(context).load().single()
        assertEquals(note, restored)
        assertFalse(restored.span.highlighted)
        assertTrue(restored.span.noted)
        assertTrue(store.setNote(note.id, LIBRARY, " \n ").isEmpty())
        assertTrue(HighlightStore(context).load().isEmpty())
    }

    @Test
    fun clearingNoteOnExplicitHighlightKeepsOriginalFillAndIdentity() = runBlocking {
        val mark = sample().copy(note = "A note", color = HighlightColor.BLUE)
        store.add(mark)
        assertEquals(listOf(mark.copy(note = "")), store.setNote(mark.id, LIBRARY, ""))
        assertEquals(mark.copy(note = ""), HighlightStore(context).load().single())
    }

    @Test
    fun removeHighlightOnlyPreservesNoteAndRequiresTheLibraryIdentity() = runBlocking {
        val mark = sample().copy(note = "Keep this independent note")
        store.add(mark)
        assertEquals(listOf(mark), store.removeHighlightOnly(mark.id, "other-library"))
        assertEquals(listOf(mark.copy(highlighted = false)), store.removeHighlightOnly(mark.id, LIBRARY))
        assertEquals(mark.copy(highlighted = false), HighlightStore(context).load().single())
        assertEquals(listOf(mark.copy(highlighted = false)), store.removeHighlightOnly(mark.id, LIBRARY))
    }

    @Test
    fun removeHighlightOnlyDeletesAPlainHighlightWithoutTouchingOtherLibraries() = runBlocking {
        val mark = sample()
        val other = mark.copy(location = mark.location.copy(libraryId = "other-library"))
        store.add(mark)
        store.add(other)
        assertEquals(listOf(other), store.removeHighlightOnly(mark.id, LIBRARY))
    }

    @Test
    fun addingHighlightToSameNoteRangeKeepsIdAndNoteAndUsesTheNewDefaultFill() = runBlocking {
        val note = sample("old-note").copy(highlighted = false, note = "Keep me", color = HighlightColor.YELLOW)
        store.add(note)
        val request = note.copy(id = "new-mark", highlighted = true, note = "", color = HighlightColor.GREEN, createdAt = 999)
        val expected = note.copy(highlighted = true, color = HighlightColor.GREEN)
        assertEquals(listOf(expected), store.add(request))
        assertEquals(listOf(expected), HighlightStore(context).load())
    }

    @Test
    fun addingNoteToSameHighlightedRangeKeepsOldFillAndExistingNotesAreNotOverwritten() = runBlocking {
        val mark = sample().copy(color = HighlightColor.BLUE)
        store.add(mark)
        val incoming = mark.copy(id = "new-note", highlighted = false, color = HighlightColor.PINK, note = "First note")
        val expected = mark.copy(note = "First note")
        assertEquals(listOf(expected), store.add(incoming))
        assertEquals(listOf(expected), store.add(incoming.copy(id = "third", note = "Do not overwrite")))
        assertEquals(listOf(expected.copy(note = "Intentional edit")), store.setNote(mark.id, LIBRARY, "Intentional edit"))
    }

    @Test
    fun exactRangeMergeIsLibraryScopedAndDoesNotMergeOverlappingRanges() = runBlocking {
        val first = sample()
        val overlap = first.copy(id = "overlap", start = 2, end = 9)
        val other = first.copy(id = "other", location = first.location.copy(libraryId = "other-library"))
        store.add(first)
        store.add(overlap)
        assertEquals(setOf(first, overlap, other), store.add(other).toSet())
    }

    @Test
    fun oversizedNotesRejectBeforeTrimmingWithoutTruncatingOrOverwritingDisk() = runBlocking {
        val mark = sample().copy(note = "Original")
        store.add(mark)
        val bytes = file.readBytes()
        for (invalid in listOf("x".repeat(HighlightStore.MAX_NOTE + 1), " ".repeat(HighlightStore.MAX_NOTE) + "x")) {
            rejects { store.setNote(mark.id, LIBRARY, invalid) }
            rejects { store.add(mark.copy(id = "new", note = invalid)) }
            assertArrayEquals(bytes, file.readBytes())
        }
        val boundary = "क".repeat(HighlightStore.MAX_NOTE)
        assertEquals(boundary, store.setNote(mark.id, LIBRARY, boundary).single().note)
        assertEquals(boundary, HighlightStore(context).load().single().note)
    }

    @Test
    fun concurrentExactRangeRequestsMergeUnderTheStoreLock() = runBlocking {
        coroutineScope {
            listOf(sample("mark"), sample("note").copy(highlighted = false, note = "Independent note"))
                .map { async { HighlightStore(context).add(it) } }.awaitAll()
        }
        val merged = store.load().single()
        assertTrue(merged.highlighted)
        assertEquals("Independent note", merged.note)
    }

    @Test
    fun invalidStoredFlagFailsClosedRatherThanMakingLegacyNotesDisappear() = runBlocking {
        writeEntries(JSONArray().put(legacyEntry(sample()).put("highlighted", "false")))
        val bytes = file.readBytes()
        rejects { store.load() }
        rejects { store.add(sample("new")) }
        assertArrayEquals(bytes, file.readBytes())
    }

    private fun sample(id: String = "synthetic-mark") = Highlight(id,
        ReadingLocation(LIBRARY, "Synthetic passage", 10, 20, 10), "Synthetic book", 10, 0, 10, 7,
        "कृष्ण ā", HighlightColor.YELLOW, 100L)

    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid annotation to be rejected") } catch (_: IllegalArgumentException) { }
    }

    private fun legacyEntry(mark: Highlight): JSONObject = JSONObject()
        .put("id", mark.id).put("location", JSONObject().put("libraryId", mark.location.libraryId)
            .put("title", mark.location.title).put("start", 10).put("end", 20).put("position", 10))
        .put("bookTitle", mark.bookTitle).put("record", mark.record).put("start", mark.start)
        .put("endRecord", mark.endRecord).put("end", mark.end).put("text", mark.text)
        .put("color", mark.color.name).put("createdAt", mark.createdAt).put("note", mark.note)

    private fun writeLegacy(vararg marks: Highlight) = writeEntries(JSONArray().also { entries -> marks.forEach { entries.put(legacyEntry(it)) } })
    private fun writeEntries(entries: JSONArray) {
        file.parentFile!!.mkdirs()
        file.writeText(JSONObject().put("version", 1).put("highlights", entries).toString(), Charsets.UTF_8)
    }

    companion object { private const val LIBRARY = "synthetic-annotation-library" }
}