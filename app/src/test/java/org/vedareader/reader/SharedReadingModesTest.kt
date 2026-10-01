package org.vedareader.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind

/** Both loaders serve identical synthetic resource content; only the navigation mode differs. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedReadingModesTest {
    @Test
    fun togglesKeepTheVisibleMiddleParagraphInBothDirectionsButNeverItsPixelOffset() = runTest {
        for (initialMode in ReadingMode.entries) {
            val fixture = Fixture(this, initialMode)
            val controller = fixture.controller
            // The requested whole-work range need not fit the first bounded resource.
            controller.open(LIBRARY, LANGUAGE, fixture.book.root)
            runCurrent()
            val original = fixture.assertReady(initialMode, fixture.book.root.record).document!!
            assertTrue(controller.state!!.location.end < fixture.book.root.end)
            val opposite = if (initialMode == ReadingMode.VERSE) ReadingMode.BOOK else ReadingMode.VERSE
            for (mode in listOf(opposite, initialMode)) {
                controller.saveBookAnchor(142)
                controller.saveScroll(720)
                controller.toggleMode()
                val loading = controller.state!!
                assertTrue(loading.loading)
                assertNull(loading.document)
                assertEquals(142, loading.location.position)
                assertEquals(0, loading.scrollY)
                runCurrent()
                val ready = fixture.assertReady(mode, 142)
                assertEquals(fixture.book.middle.displayTitle, ready.location.title)
                assertEquals(original.section, ready.document!!.section)
                assertEquals(original.html, ready.document.html)
                assertEquals(0, ready.scrollY)
                assertFalse(ready.enterAtEnd)
                assertEquals(mode to fixture.book.middle.location(LIBRARY).copy(position = 142), fixture.calls.last())
            }
            assertEquals(listOf(opposite, initialMode), fixture.modes)
            assertEquals(3, fixture.calls.size)
        }
    }

    @Test
    fun scrollingAnchorReportsUpdateSourceTitleOnlyForActualParagraphsWithoutReloading() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller
        controller.open(LIBRARY, LANGUAGE, fixture.book.first)
        runCurrent()
        controller.saveScroll(360)
        val original = controller.state!!
        val document = original.document!!
        for ((record, source) in listOf(142 to fixture.book.middle, 172 to fixture.book.last, 100 to fixture.book.root)) {
            controller.saveBookAnchor(record)
            val saved = controller.state!!
            assertEquals(original.copy(location = original.location.copy(position = record, title = source.displayTitle)), saved)
            assertSame(document, saved.document)
            assertSame(document.html, saved.document!!.html)
            assertSame(document.section.paragraphs, saved.document.section.paragraphs)
            assertSame(document.bookResource, saved.document.bookResource)
            assertEquals(fixture.book.first.record, saved.document.bookResource!!.anchorRecord)
        }
        val saved = controller.state
        val changes = fixture.changes.toList()
        for (record in listOf(100, -1, 0, 141, 143, 200, Int.MAX_VALUE)) {
            controller.saveBookAnchor(record) // Duplicate, out of bounds, or sparse/nonexistent row.
            assertSame(saved, controller.state)
        }
        runCurrent()
        assertEquals(changes, fixture.changes)
        assertEquals(1, fixture.calls.size)
        assertTrue(fixture.modes.isEmpty())
    }

    @Test
    fun previousScrollingResourceUsesNeighbourEndAnchorAndNextUsesNeighbourStart() = runTest {
        val fixture = Fixture(this)
        val controller = fixture.controller
        controller.open(LIBRARY, LANGUAGE, fixture.book.nextVerse)
        runCurrent()
        controller.saveScroll(650)
        controller.previous()
        assertTrue(controller.state!!.enterAtEnd)
        assertEquals(199, controller.state!!.location.position)
        assertEquals(0, controller.state!!.scrollY)
        runCurrent()
        val previous = fixture.assertReady(ReadingMode.VERSE, 199)
        assertEquals(fixture.book.last.displayTitle, previous.location.title)
        assertTrue(previous.enterAtEnd)
        assertEquals(3, previous.document!!.bookResource!!.position.index)
        controller.saveBookAnchor(172)
        assertFalse(controller.state!!.enterAtEnd)
        assertSame(previous.document, controller.state!!.document)
        controller.saveScroll(800)
        controller.next()
        runCurrent()
        val next = fixture.assertReady(ReadingMode.VERSE, 200)
        assertFalse(next.enterAtEnd)
        assertEquals(0, next.scrollY)
        assertEquals(fixture.book.nextChapter.displayTitle, next.location.title)
        val changes = fixture.changes.toList()
        controller.next()
        assertEquals(changes, fixture.changes)
        assertEquals(listOf(210, 199, 200), fixture.calls.map { it.second.position })
        assertTrue(fixture.calls.all { it.first == ReadingMode.VERSE })
    }

    @Test
    fun contentsJumpsAcceptExactOutlineStartsInEitherResourceMode() = runTest {
        for (mode in ReadingMode.entries) {
            val fixture = Fixture(this, mode)
            val controller = fixture.controller
            controller.open(LIBRARY, LANGUAGE, fixture.book.first)
            runCurrent()
            for (entry in listOf(fixture.book.middle, fixture.book.nextVerse, fixture.book.first)) {
                controller.saveScroll(600)
                controller.jumpToBookEntry(entry.record)
                assertTrue(controller.state!!.loading)
                assertNull(controller.state!!.document)
                assertEquals(entry.location(LIBRARY), controller.state!!.location)
                runCurrent()
                val ready = fixture.assertReady(mode, entry.record)
                assertEquals(entry.displayTitle, ready.location.title)
                assertEquals(0, ready.scrollY)
                assertFalse(ready.enterAtEnd)
            }
            val changes = fixture.changes.toList()
            for (invalid in listOf(0, 112, 142, 299, 300)) controller.jumpToBookEntry(invalid)
            assertEquals(changes, fixture.changes)
            assertEquals(4, fixture.calls.size)
            assertTrue(fixture.calls.all { it.first == mode })
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun wrongResourceRootIsRejectedAcrossBothModesNavigationTogglesAndRetries() = runTest {
        for (mode in ReadingMode.entries) {
            for (action in listOf("next", "previous", "jump", "toggle")) {
                val fixture = Fixture(this, mode)
                val controller = fixture.controller
                controller.open(LIBRARY, LANGUAGE, if (action == "previous") fixture.book.nextVerse else fixture.book.first)
                runCurrent()
                controller.saveBookAnchor(if (action == "previous") 212 else 142)
                controller.saveScroll(480)
                val wrong = SyntheticBook("Other work with overlapping records")
                fixture.load = { _, location -> wrong.resourceDocument(location.position) }
                when (action) {
                    "next" -> controller.next()
                    "previous" -> controller.previous()
                    "jump" -> controller.jumpToBookEntry(fixture.book.middle.record)
                    else -> controller.toggleMode()
                }
                runCurrent()
                val failed = controller.state!!
                assertTrue(failed.failed)
                assertEquals(ReadingFailure.LOAD, failed.failure)
                assertNull(failed.document)
                val request = fixture.calls.last()
                controller.retry()
                runCurrent()
                assertEquals(failed, controller.state)
                assertEquals(request, fixture.calls.last())
                fixture.load = { _, location -> fixture.book.resourceDocument(location.position) }
                controller.retry()
                runCurrent()
                val ready = fixture.assertReady(request.first, request.second.position)
                assertEquals(action == "previous", ready.enterAtEnd)
                assertEquals(0, ready.scrollY)
                assertTrue(fixture.changes.none { it?.document?.bookResource?.position?.outline?.book == wrong.root })
                controller.close()
                assertNull(controller.state)
                assertEquals(request.first, controller.selectedMode)
                fixture.load = { _, location -> wrong.resourceDocument(location.position) }
                controller.open(LIBRARY, LANGUAGE, wrong.first) // Explicit open resets the root guard.
                runCurrent()
                assertEquals(wrong.root, fixture.assertReady(request.first, wrong.first.record).document!!.bookResource!!.position.outline.book)
            }
        }
    }

    @Test
    fun resourceMetadataAndRequestedAnchorAreValidatedEquallyInBothModes() = runTest {
        val book = SyntheticBook()
        val valid = book.resourceDocument(book.first.record)
        val badSource = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 1) entry.copy(source = entry.source.copy(title = "Unrelated source title")) else entry
        })
        val outsideSource = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 1) entry.copy(source = entry.source.copy(end = book.root.end + 1)) else entry
        })
        val invalid = listOf(
            valid.copy(section = valid.section.copy(parentTitle = "Wrong section metadata")),
            valid.copy(section = valid.section.copy(paragraphs = emptyList())),
            book.resourceDocument(112), // Same entry and resource, but not the requested paragraph.
            book.resourceDocument(book.nextVerse.record),
            book.resourceDocument(book.first.record, badSource),
            book.resourceDocument(book.first.record, outsideSource),
        )
        for (mode in ReadingMode.entries) {
            for (document in invalid) {
                val fixture = Fixture(this, mode)
                fixture.load = { _, _ -> document }
                fixture.controller.open(LIBRARY, LANGUAGE, book.first)
                runCurrent()
                assertTrue(fixture.controller.state!!.failed)
                assertEquals(ReadingFailure.LOAD, fixture.controller.state!!.failure)
                assertTrue(fixture.changes.none { it?.document != null })
            }
        }
    }

    @Test
    fun originalVerseApiStillPromotesWholeUnitsAndIgnoresResourceOnlyOperations() = runTest {
        val book = SyntheticBook()
        val calls = mutableListOf<ReadingLocation>()
        val controller = ReaderController(this, { location, _ ->
            calls += location
            book.verseDocument(location.position)
        }, {})
        val purport = book.first.copy(record = 112, parent = book.first.record, title = "PURPORT")
        controller.open(LIBRARY, LANGUAGE, purport)
        runCurrent()
        val ready = controller.state!!
        assertFalse(ready.failed)
        assertEquals(ReadingMode.VERSE, controller.selectedMode)
        assertEquals(book.first.location(LIBRARY), ready.location)
        assertNull(ready.document!!.bookResource)
        controller.saveScroll(420)
        val scrolled = controller.state
        controller.saveBookAnchor(112)
        controller.jumpToBookEntry(book.middle.record)
        assertSame(scrolled, controller.state)
        controller.next()
        runCurrent()
        assertEquals(book.middle.location(LIBRARY), controller.state!!.location)
        assertEquals(0, controller.state!!.scrollY)
        controller.saveScroll(500)
        controller.previous()
        runCurrent()
        assertEquals(ready, controller.state)
        assertFalse(controller.state!!.enterAtEnd)
        assertEquals(listOf(purport, book.middle, book.first).map { it.location(LIBRARY) }, calls)
    }

    private class Fixture(scope: CoroutineScope, initialMode: ReadingMode = ReadingMode.VERSE) {
        val book = SyntheticBook()
        val calls = mutableListOf<Pair<ReadingMode, ReadingLocation>>()
        val changes = mutableListOf<ReaderState?>()
        val modes = mutableListOf<ReadingMode>()
        var load: suspend (ReadingMode, ReadingLocation) -> ReaderDocument = { _, location ->
            book.resourceDocument(location.position)
        }
        val controller = ReaderController(scope, { location, language ->
            check(language == LANGUAGE)
            calls += ReadingMode.VERSE to location
            load(ReadingMode.VERSE, location)
        }, { changes += it }, { location, language ->
            check(language == LANGUAGE)
            calls += ReadingMode.BOOK to location
            load(ReadingMode.BOOK, location)
        }, initialMode, { modes += it })

        fun assertReady(mode: ReadingMode, anchor: Int): ReaderState {
            val state = controller.state!!
            assertFalse(state.loading)
            assertFalse(state.failed)
            assertEquals(mode, state.mode)
            assertEquals(mode, controller.selectedMode)
            assertEquals(anchor, state.location.position)
            val resource = state.document!!.bookResource!!
            assertEquals(anchor, resource.anchorRecord)
            assertEquals(resource.source.displayTitle, state.location.title)
            assertEquals(resource.section.node.record, state.location.start)
            assertEquals(resource.section.node.end, state.location.end)
            assertEquals(resource.section, state.document.section)
            assertEquals(state, changes.last())
            return state
        }
    }

    private class SyntheticBook(title: String = "Generated work") {
        val root = ContentNode(100, 1, title, 299, 2, 1, "BOOK")
        val first = ContentNode(110, root.record, "First verse", 139, 0, 2, "TEXT")
        val middle = ContentNode(140, root.record, "Middle verse", 169, 0, 2, "TEXT")
        val last = ContentNode(170, root.record, "Last verse", 199, 0, 2, "TEXT")
        val nextChapter = ContentNode(200, root.record, "Next chapter", 299, 1, 2, "CHAPTER")
        val nextVerse = ContentNode(210, nextChapter.record, "Next verse", 299, 0, 3, "TEXT")
        val entries = listOf(
            BookEntry(root.copy(end = 109), root, ReadingUnitKind.SECTION, "Generated library"),
            BookEntry(first, first, ReadingUnitKind.VERSE, root.displayTitle),
            BookEntry(middle, middle, ReadingUnitKind.VERSE, root.displayTitle),
            BookEntry(last, last, ReadingUnitKind.VERSE, root.displayTitle),
            BookEntry(nextChapter.copy(end = 209), nextChapter, ReadingUnitKind.CHAPTER, root.displayTitle),
            BookEntry(nextVerse, nextVerse, ReadingUnitKind.VERSE, nextChapter.displayTitle),
        )
        val outline = BookOutline(root, entries)
        private val nodes = listOf(root.copy(end = 199), nextChapter)

        fun resourceDocument(record: Int, selectedOutline: BookOutline = outline): ReaderDocument {
            val index = selectedOutline.entries.indexOfFirst { record in it.node.record..it.node.end }
            val resourceIndex = if (index < 4) 0 else 1
            val range = if (resourceIndex == 0) 0..3 else 4..5
            val section = ReadingSection(nodes[resourceIndex], ReadingUnitKind.CHAPTER, root.displayTitle,
                paragraphs(nodes[resourceIndex]), nodes.getOrNull(resourceIndex - 1), nodes.getOrNull(resourceIndex + 1))
            val resource = BookResource(section, BookPosition(selectedOutline, index), range.first, range.last, record)
            val html = section.paragraphs.joinToString("") { "<p id=\"record-${it.record}\">${it.text}</p>" }
            return ReaderDocument(section, html, bookResource = resource)
        }

        fun verseDocument(record: Int): ReaderDocument {
            val verses = listOf(first, middle, last, nextVerse)
            val index = verses.indexOfFirst { record in it.record..it.end }
            val section = ReadingSection(verses[index], ReadingUnitKind.VERSE, root.displayTitle,
                paragraphs(verses[index]), verses.getOrNull(index - 1), verses.getOrNull(index + 1))
            return ReaderDocument(section, "<p>Generated legacy verse ${section.node.record}.</p>")
        }

        private fun paragraphs(node: ContentNode): List<Paragraph> = entries
            .flatMap { listOf(it.node.record, it.node.record + 2, it.node.end) }
            .filter { it in node.record..node.end }
            .map { Paragraph(it, "Generated paragraph $it.", "Body", "Text") }
    }

    companion object {
        private const val LIBRARY = "synthetic-shared-library"
        private const val LANGUAGE = "en"
    }
}