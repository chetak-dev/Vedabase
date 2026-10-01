package org.vedareader.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
import org.vedareader.data.ReadingException
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind

/** Synthetic, sparse source records only; deferred results control pending loads, never clocks. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookResourceControllerTest {
    @Test
    fun appendedResourceIsOptionalForBothOriginalAndSingleEntryDocumentArguments() {
        val book = SyntheticBook()
        val legacy = book.entryDocument(book.middle.record)
        val original = ReaderDocument(legacy.section, legacy.html)
        val positional = ReaderDocument(legacy.section, legacy.html, legacy.bookPosition)
        assertNull(original.bookPosition)
        assertNull(original.bookResource)
        assertEquals(legacy, positional)
        assertNull(positional.bookResource)
    }

    @Test
    fun groupedMiddleVerseKeepsItsSelectionAndTitleWithOrWithoutOptionalBookPosition() = runTest {
        for (mirrorPosition in listOf(false, true)) {
            val fixture = Fixture(this, mirrorPosition = mirrorPosition)
            val selected = fixture.book.middle
            fixture.controller.open(LIBRARY, LANGUAGE, selected)
            val loading = ReaderState(selected.location(LIBRARY), LANGUAGE, mode = ReadingMode.BOOK)
            fixture.assertState(loading)
            assertTrue(fixture.calls.isEmpty())
            runCurrent()

            val ready = fixture.assertReady(selected.record)
            val document = ready.document!!
            val resource = document.bookResource!!
            assertEquals(fixture.book.chapter, document.section.node)
            assertEquals(3, resource.firstEntry)
            assertEquals(6, resource.lastEntry)
            assertEquals(5, resource.position.index)
            assertEquals(selected, resource.source)
            assertEquals(selected.displayTitle, ready.location.title)
            assertTrue(ready.location.title != document.section.node.displayTitle)
            assertEquals(loading.copy(location = ready.location, document = document, loading = false), ready)
            assertEquals(listOf(loading, ready), fixture.changes)
            assertEquals(listOf(Call(ReadingMode.BOOK, selected.location(LIBRARY), LANGUAGE)), fixture.calls)
            if (mirrorPosition) assertSame(resource.position, document.bookPosition) else assertNull(document.bookPosition)
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun bodySparseAndSplitProseSelectionsKeepExactRequestedPositionsNotResourceStarts() = runTest {
        val book = SyntheticBook()
        val selections = listOf(
            ContentNode(182, book.middle.record, "PURPORT", book.middle.end, 0, 4, "TEXT"),
            ContentNode(183, book.middle.record, "Sparse selection", book.middle.end, 0, 4, "TEXT"),
            book.entries[2].node,
            book.root,
        )
        for (selected in selections) {
            val fixture = Fixture(this, book = book)
            fixture.controller.open(LIBRARY, LANGUAGE, selected)
            runCurrent()
            val ready = fixture.assertReady(selected.record)
            val entry = book.entries.single { selected.record in it.node.record..it.node.end }
            assertEquals(entry.source.displayTitle, ready.location.title)
            assertEquals(selected.location(LIBRARY), fixture.calls.single().location)
        }
    }

    @Test
    fun visibleAnchorsCrossVersesAndHeaderWithoutQueriesOrDocumentHtmlRangeOrScrollChanges() = runTest {
        val fixture = Fixture(this, mirrorPosition = true)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.controller.saveScroll(480)
        val original = fixture.controller.state!!
        val document = original.document!!
        val resource = document.bookResource!!
        val calls = fixture.calls.toList()
        val records = listOf(fixture.book.last.end, fixture.book.first.record, fixture.book.chapter.record, 182)
        for (record in records) {
            val source = fixture.book.entries.single { record in it.node.record..it.node.end }.source
            val changeCount = fixture.changes.size
            fixture.controller.saveBookAnchor(record)
            runCurrent()
            val saved = fixture.controller.state!!
            fixture.assertState(original.copy(location = original.location.copy(position = record, title = source.displayTitle)))
            assertEquals(changeCount + 1, fixture.changes.size)
            assertSame(document, saved.document)
            assertSame(document.html, saved.document!!.html)
            assertSame(document.section.paragraphs, saved.document.section.paragraphs)
            assertSame(resource, saved.document.bookResource)
            assertSame(resource.position, saved.document.bookPosition)
            assertEquals(fixture.book.middle.record, resource.anchorRecord)
            assertEquals(5, resource.position.index) // Loaded metadata is not the live viewport bookmark.
            assertEquals(calls, fixture.calls)
        }
        val changes = fixture.changes.toList()
        fixture.controller.saveBookAnchor(records.last())
        assertEquals(changes, fixture.changes)
        assertTrue(fixture.modes.isEmpty())
    }

    @Test
    fun invalidAndMissingParagraphAnchorsAreIgnoredRatherThanClampedOrLoaded() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        val ready = fixture.assertReady(fixture.book.middle.record)
        val changes = fixture.changes.toList()
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 149, 181, 183, 250, Int.MAX_VALUE)) {
            fixture.controller.saveBookAnchor(invalid)
            runCurrent()
            assertSame(ready, fixture.controller.state)
            assertEquals(changes, fixture.changes)
        }
        assertEquals(1, fixture.calls.size)
        assertTrue(fixture.modes.isEmpty())
    }

    @Test
    fun anchorReportsAreIgnoredWhenClosedOrUsingNonResourceDocumentsInEitherMode() = runTest {
        for (mode in ReadingMode.entries) {
            val fixture = Fixture(this, initialMode = mode)
            fixture.loadVerse = { location, _ -> fixture.book.verseDocument(location.position) }
            fixture.loadBook = { location, _ -> fixture.book.entryDocument(location.position) }
            fixture.controller.saveBookAnchor(fixture.book.middle.record)
            assertTrue(fixture.changes.isEmpty())
            fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
            runCurrent()
            val ready = fixture.controller.state!!
            assertFalse(ready.failed)
            assertNull(ready.document!!.bookResource)
            val changes = fixture.changes.toList()
            fixture.controller.saveBookAnchor(182)
            assertSame(ready, fixture.controller.state)
            assertEquals(changes, fixture.changes)
            assertEquals(1, fixture.calls.size)
            fixture.controller.close()
            val closed = fixture.changes.toList()
            fixture.controller.saveBookAnchor(182)
            assertNull(fixture.controller.state)
            assertEquals(closed, fixture.changes)
        }
    }

    @Test
    fun loadingFailedRetryAndReplacementStatesIgnoreStaleAnchorReports() = runTest {
        val fixture = Fixture(this)
        val response = CompletableDeferred<ReaderDocument>()
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.controller.saveBookAnchor(fixture.book.last.end)
        fixture.loadBook = { _, _ -> response.await() }
        fixture.controller.next()
        try {
            val loading = fixture.controller.state
            val loadingChanges = fixture.changes.toList()
            repeat(2) {
                fixture.controller.saveBookAnchor(fixture.book.middle.record)
                fixture.controller.saveBookAnchor(fixture.book.nextMiddle.record)
                runCurrent() // Cover the queued request and the suspended loader.
                assertSame(loading, fixture.controller.state)
                assertEquals(loadingChanges, fixture.changes)
                assertEquals(2, fixture.calls.size)
            }
            response.completeExceptionally(ReadingException(ReadingFailure.SECTION_TOO_LARGE))
            runCurrent()
            val failed = fixture.assertFailed(ReadingFailure.SECTION_TOO_LARGE)
            val failedChanges = fixture.changes.toList()
            fixture.controller.saveBookAnchor(fixture.book.middle.record)
            fixture.controller.saveBookAnchor(fixture.book.nextMiddle.record)
            assertSame(failed, fixture.controller.state)
            assertEquals(failedChanges, fixture.changes)

            fixture.loadBook = { location, _ -> fixture.book.resourceDocument(location.position) }
            fixture.controller.retry()
            val retrying = fixture.controller.state
            fixture.controller.saveBookAnchor(fixture.book.nextMiddle.record)
            assertSame(retrying, fixture.controller.state)
            runCurrent()
            val replacement = fixture.assertReady(fixture.book.nextChapter.record)
            val replacementChanges = fixture.changes.toList()
            fixture.controller.saveBookAnchor(fixture.book.last.end)
            assertSame(replacement, fixture.controller.state)
            assertEquals(replacementChanges, fixture.changes)
            assertEquals(3, fixture.calls.size)
            assertEquals(fixture.calls[1], fixture.calls[2])
            assertTrue(fixture.calls.all { it.mode == ReadingMode.BOOK })
        } finally {
            response.complete(fixture.book.resourceDocument(fixture.book.nextChapter.record))
            fixture.controller.close()
        }
    }

    @Test
    fun toggleToVerseUsesTheVisibleEntryNotTheInitialSelectionOrResourceHeader() = runTest {
        for (mirrorPosition in listOf(false, true)) {
            for (visible in listOf(162, 249)) {
                val fixture = Fixture(this, mirrorPosition = mirrorPosition)
                fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
                runCurrent()
                val original = fixture.assertReady(fixture.book.middle.record).document!!
                fixture.controller.saveScroll(400)
                fixture.controller.saveBookAnchor(visible)
                val source = fixture.book.entries.single { visible in it.node.record..it.node.end }.source
                val target = source.location(LIBRARY).copy(position = visible)
                fixture.controller.toggleMode()
                assertEquals(target, fixture.controller.state!!.location)
                assertNull(fixture.controller.state!!.document)
                assertEquals(0, fixture.controller.state!!.scrollY)
                runCurrent()
                val verse = fixture.assertReady(visible, mode = ReadingMode.VERSE)
                assertEquals(source, verse.document!!.bookResource!!.source)
                assertEquals(original.section, verse.document.section)
                assertEquals(original.html, verse.document.html)
                assertEquals(fixture.book.chapter.record, verse.location.start)
                assertEquals(fixture.book.chapter.end, verse.location.end)
                assertEquals(0, verse.scrollY)
                assertFalse(verse.enterAtEnd)
                assertEquals(listOf(
                    Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
                    Call(ReadingMode.VERSE, target, LANGUAGE),
                ), fixture.calls)
                assertEquals(listOf(ReadingMode.VERSE), fixture.modes)
            }
        }
    }

    @Test
    fun failedVisibleSourceToggleReturnsToTheLiveResourceAnchorOnlyOnExplicitToggle() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.controller.saveBookAnchor(212)
        fixture.controller.saveScroll(378)
        val saved = fixture.controller.state!!
        val verseRequest = Call(ReadingMode.VERSE, fixture.book.last.location(LIBRARY).copy(position = 212), LANGUAGE)
        fixture.loadVerse = { _, _ -> throw ReadingException(ReadingFailure.CHOOSE_SECTION) }
        fixture.controller.toggleMode()
        runCurrent()
        val failed = fixture.assertFailed(ReadingFailure.CHOOSE_SECTION)
        assertEquals(verseRequest.location, failed.location)
        assertEquals(0, failed.scrollY)
        assertEquals(ReadingMode.VERSE, fixture.controller.selectedMode)
        assertEquals(2, fixture.calls.size)
        assertEquals(verseRequest, fixture.calls.last())
        fixture.controller.retry()
        runCurrent()
        fixture.assertState(failed)
        assertEquals(listOf(
            Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
            verseRequest,
            verseRequest,
        ), fixture.calls) // Neither the failure nor its retry silently falls back to BOOK.
        fixture.controller.toggleMode()
        assertEquals(saved.location, fixture.controller.state!!.location)
        assertEquals(0, fixture.controller.state!!.scrollY)
        runCurrent()
        val restored = fixture.assertReady(212)
        assertEquals(listOf(
            Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
            verseRequest,
            verseRequest,
            Call(ReadingMode.BOOK, saved.location, LANGUAGE),
        ), fixture.calls)
        assertEquals(saved.location, restored.location)
        assertEquals(0, restored.scrollY) // A live source anchor survives; an offset from the other axis does not.
        assertFalse(restored.enterAtEnd)
        assertNotSame(saved.document, restored.document)
        assertEquals(listOf(ReadingMode.VERSE, ReadingMode.BOOK), fixture.modes)
    }

    @Test
    fun nextLoadsTheAdjacentResourceRegardlessOfWhichMemberWasInitiallySelected() = runTest {
        val book = SyntheticBook()
        for (selected in listOf(book.chapter, book.first, book.middle, book.last)) {
            val fixture = Fixture(this, book = book, mirrorPosition = true)
            fixture.controller.open(LIBRARY, LANGUAGE, selected)
            runCurrent()
            fixture.controller.saveScroll(700)
            fixture.controller.next()
            assertEquals(book.nextChapter.location(LIBRARY), fixture.controller.state!!.location)
            assertFalse(fixture.controller.state!!.enterAtEnd)
            assertEquals(0, fixture.controller.state!!.scrollY)
            runCurrent()
            val ready = fixture.assertReady(book.nextChapter.record)
            assertEquals(book.nextChapter, ready.document!!.section.node)
            assertEquals(7, ready.document.bookResource!!.firstEntry)
            assertEquals(10, ready.document.bookResource!!.lastEntry)
            assertEquals(7, ready.document.bookResource!!.position.index)
            assertEquals(listOf(selected.record, book.nextChapter.record), fixture.calls.map { it.location.position })
            val changes = fixture.changes.toList()
            fixture.controller.next()
            assertEquals(changes, fixture.changes)
            assertEquals(2, fixture.calls.size)
        }
    }

    @Test
    fun previousLoadsTheNeighbourEndAndAcknowledgesItsPaintedAnchor() = runTest {
        val fixture = Fixture(this, mirrorPosition = true)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.nextMiddle)
        runCurrent()
        fixture.controller.previous()
        assertTrue(fixture.controller.state!!.loading)
        assertTrue(fixture.controller.state!!.enterAtEnd)
        runCurrent()
        val ready = fixture.assertReady(fixture.book.chapter.end)
        assertEquals(fixture.book.chapter.location(LIBRARY).copy(position = fixture.book.chapter.end), fixture.calls.last().location)
        assertEquals(fixture.book.last.displayTitle, ready.location.title)
        assertEquals(6, ready.document!!.bookResource!!.position.index)
        assertTrue(ready.enterAtEnd)
        assertEquals(0, ready.scrollY)
        fixture.controller.saveBookAnchor(212)
        assertFalse(fixture.controller.state!!.enterAtEnd)
        assertSame(ready.document, fixture.controller.state!!.document)
        fixture.controller.saveScroll(0)
        assertFalse(fixture.controller.state!!.enterAtEnd)

        fixture.controller.previous()
        runCurrent()
        val first = fixture.assertReady(fixture.book.intro.end)
        assertEquals(100, first.location.start)
        assertEquals(fixture.book.intro.displayTitle, first.location.title)
        assertEquals(2, first.document!!.bookResource!!.position.index)
        assertTrue(first.enterAtEnd)
        val changes = fixture.changes.toList()
        fixture.controller.previous()
        assertEquals(changes, fixture.changes)
        assertEquals(3, fixture.calls.size)
    }

    @Test
    fun nearestOutlineEntryNeighboursAreAcceptedAsWellAsWholeResourceNeighbours() = runTest {
        val fixture = Fixture(this)
        fixture.loadBook = { location, _ -> fixture.book.resourceDocument(location.position, entryNeighbours = true) }
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.assertReady(fixture.book.middle.record)
        fixture.controller.next()
        runCurrent()
        val next = fixture.assertReady(fixture.book.nextChapter.record)
        assertEquals(fixture.book.entries[7].node.location(LIBRARY), fixture.calls.last().location)
        assertEquals(fixture.book.nextChapter.end, next.location.end)
        fixture.controller.previous()
        runCurrent()
        val previous = fixture.assertReady(fixture.book.last.end)
        assertEquals(fixture.book.last.location(LIBRARY).copy(position = fixture.book.last.end), fixture.calls.last().location)
        assertEquals(fixture.book.chapter.record, previous.location.start)
        assertTrue(previous.enterAtEnd)
    }

    @Test
    fun sameResourceContentsJumpsReloadAtTheExactEntryAndResetScrollEvenForRepeatedTargets() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.controller.saveBookAnchor(212)
        val initialDocument = fixture.controller.state!!.document!!
        repeat(2) {
            val previousDocument = fixture.controller.state!!.document
            fixture.controller.saveScroll(640)
            fixture.controller.jumpToBookEntry(fixture.book.first.record)
            assertTrue(fixture.controller.state!!.loading)
            assertNull(fixture.controller.state!!.document)
            assertEquals(fixture.book.first.location(LIBRARY), fixture.controller.state!!.location)
            runCurrent()
            val ready = fixture.assertReady(fixture.book.first.record)
            assertEquals(initialDocument.section, ready.document!!.section)
            assertEquals(initialDocument.html, ready.document.html)
            assertNotSame(previousDocument, ready.document)
            assertEquals(4, ready.document.bookResource!!.position.index)
            assertEquals(0, ready.scrollY)
            assertFalse(ready.enterAtEnd)
        }
        assertEquals(listOf(180, 160, 160), fixture.calls.map { it.location.position })
        val changes = fixture.changes.toList()
        for (invalid in listOf(Int.MIN_VALUE, 0, 162, 181, 400, Int.MAX_VALUE)) {
            fixture.controller.jumpToBookEntry(invalid)
        }
        assertEquals(changes, fixture.changes)
        assertEquals(3, fixture.calls.size)
        assertTrue(fixture.modes.isEmpty())

        fixture.controller.jumpToBookEntry(fixture.book.entries[2].node.record)
        runCurrent()
        val prose = fixture.assertReady(130)
        assertEquals(100, prose.location.start)
        assertEquals(fixture.book.intro.displayTitle, prose.location.title)
        fixture.controller.saveScroll(640)
        val target = fixture.book.intro.location(LIBRARY).copy(position = 130)
        fixture.controller.toggleMode()
        assertEquals(target, fixture.controller.state!!.location)
        runCurrent()
        val scrolling = fixture.assertReady(130, mode = ReadingMode.VERSE)
        assertEquals(prose.location, scrolling.location)
        assertEquals(prose.document!!.section, scrolling.document!!.section)
        assertEquals(prose.document.html, scrolling.document.html)
        assertEquals(fixture.book.intro, scrolling.document.bookResource!!.source)
        assertEquals(2, scrolling.document.bookResource!!.position.index)
        assertEquals(0, scrolling.scrollY)
        assertFalse(scrolling.enterAtEnd)
        assertEquals(listOf(
            Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.BOOK, fixture.book.first.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.BOOK, fixture.book.first.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.BOOK, fixture.book.entries[2].node.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.VERSE, target, LANGUAGE),
        ), fixture.calls)
        assertEquals(listOf(ReadingMode.VERSE), fixture.modes)
    }

    @Test
    fun typedResourceFailuresRetryTheExactSelectionWithoutAnyVerseFallback() = runTest {
        for (reason in ReadingFailure.entries) {
            val fixture = Fixture(this)
            var attempts = 0
            fixture.loadBook = { location, _ ->
                if (++attempts == 1) throw ReadingException(reason)
                fixture.book.resourceDocument(location.position)
            }
            fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
            runCurrent()
            fixture.assertFailed(reason)
            assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
            assertEquals(1, fixture.calls.size)
            fixture.controller.retry()
            fixture.controller.retry()
            runCurrent()
            fixture.assertReady(fixture.book.middle.record)
            assertEquals(2, fixture.calls.size)
            assertEquals(fixture.calls.first(), fixture.calls.last())
            assertTrue(fixture.calls.all { it.mode == ReadingMode.BOOK })
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun failedResourceMovesRememberTheTargetSourceRatherThanAnInitialSelectionNeighbour() = runTest {
        for (forward in listOf(false, true)) {
            val fixture = Fixture(this, mirrorPosition = true)
            val initial = if (forward) fixture.book.middle else fixture.book.nextMiddle
            val resourceTarget = if (forward) fixture.book.nextChapter else fixture.book.chapter
            val anchor = if (forward) resourceTarget.record else resourceTarget.end
            val failedRequest = Call(ReadingMode.BOOK, resourceTarget.location(LIBRARY).copy(position = anchor), LANGUAGE)
            fixture.controller.open(LIBRARY, LANGUAGE, initial)
            runCurrent()
            fixture.loadBook = { _, _ -> throw ReadingException(ReadingFailure.LOAD) }
            if (forward) fixture.controller.next() else fixture.controller.previous()
            runCurrent()
            val failed = fixture.assertFailed(ReadingFailure.LOAD)
            assertEquals(failedRequest.location, failed.location)
            assertEquals(!forward, failed.enterAtEnd)
            assertEquals(listOf(Call(ReadingMode.BOOK, initial.location(LIBRARY), LANGUAGE), failedRequest), fixture.calls)
            fixture.controller.retry()
            runCurrent()
            fixture.assertState(failed)
            assertEquals(listOf(
                Call(ReadingMode.BOOK, initial.location(LIBRARY), LANGUAGE),
                failedRequest,
                failedRequest,
            ), fixture.calls)
            val source = if (forward) fixture.book.nextChapter else fixture.book.last
            val target = source.location(LIBRARY).copy(position = anchor)
            fixture.controller.toggleMode()
            assertEquals(target, fixture.controller.state!!.location)
            runCurrent()
            val ready = fixture.assertReady(anchor, mode = ReadingMode.VERSE)
            assertEquals(source, ready.document!!.bookResource!!.source)
            assertEquals(resourceTarget, ready.document.section.node)
            assertEquals(0, ready.scrollY)
            assertFalse(ready.enterAtEnd)
            assertEquals(listOf(
                Call(ReadingMode.BOOK, initial.location(LIBRARY), LANGUAGE),
                failedRequest,
                failedRequest,
                Call(ReadingMode.VERSE, target, LANGUAGE),
            ), fixture.calls)
            assertEquals(listOf(ReadingMode.VERSE), fixture.modes)
        }
    }

    @Test
    fun differentWorkResponsesAreRejectedForResourceMovesContentsJumpsAndTheirRetries() = runTest {
        for (navigation in listOf("next", "previous", "jump")) {
            val fixture = Fixture(this)
            fixture.controller.open(LIBRARY, LANGUAGE,
                if (navigation == "previous") fixture.book.nextMiddle else fixture.book.middle)
            runCurrent()
            val wrong = SyntheticBook(rootTitle = "Another work with overlapping record IDs")
            fixture.loadBook = { location, _ -> wrong.resourceDocument(location.position) }
            when (navigation) {
                "next" -> fixture.controller.next()
                "previous" -> fixture.controller.previous()
                else -> fixture.controller.jumpToBookEntry(fixture.book.first.record)
            }
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            val requested = fixture.calls.last()
            fixture.controller.retry()
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            assertEquals(requested, fixture.calls.last())
            fixture.loadBook = { location, _ -> fixture.book.resourceDocument(location.position) }
            fixture.controller.retry()
            runCurrent()
            val ready = fixture.assertReady(requested.location.position)
            assertEquals(fixture.book.root, ready.document!!.bookResource!!.position.outline.book)
            assertEquals(navigation == "previous", ready.enterAtEnd)
            assertEquals(requested, fixture.calls.last())
            assertTrue(fixture.calls.all { it.mode == ReadingMode.BOOK })
            assertTrue(fixture.changes.none { it?.document?.bookResource?.position?.outline?.book == wrong.root })
        }
    }

    @Test
    fun resourceWorkIdentitySurvivesModeTogglesButAnExplicitOpenCanChooseAnotherWork() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        fixture.controller.saveBookAnchor(fixture.book.last.end)
        fixture.controller.saveScroll(480)
        val target = fixture.book.last.location(LIBRARY).copy(position = fixture.book.last.end)
        fixture.controller.toggleMode()
        runCurrent()
        val scrolling = fixture.assertReady(fixture.book.last.end, mode = ReadingMode.VERSE)
        assertEquals(fixture.book.root, scrolling.document!!.bookResource!!.position.outline.book)
        assertEquals(fixture.book.last, scrolling.document.bookResource!!.source)
        assertEquals(0, scrolling.scrollY)
        assertEquals(listOf(
            Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.VERSE, target, LANGUAGE),
        ), fixture.calls)
        val wrong = SyntheticBook(rootTitle = "Explicitly selected other work")
        fixture.loadBook = { location, _ -> wrong.resourceDocument(location.position) }
        fixture.controller.toggleMode()
        runCurrent()
        val failed = fixture.assertFailed(ReadingFailure.LOAD)
        assertEquals(target, failed.location)
        fixture.controller.retry()
        runCurrent()
        fixture.assertState(failed)
        assertEquals(listOf(
            Call(ReadingMode.BOOK, fixture.book.middle.location(LIBRARY), LANGUAGE),
            Call(ReadingMode.VERSE, target, LANGUAGE),
            Call(ReadingMode.BOOK, target, LANGUAGE),
            Call(ReadingMode.BOOK, target, LANGUAGE),
        ), fixture.calls)
        assertTrue(fixture.changes.none { it?.document?.bookResource?.position?.outline?.book == wrong.root })
        fixture.controller.open(LIBRARY, LANGUAGE, wrong.middle)
        runCurrent()
        val reopened = fixture.assertReady(wrong.middle.record)
        assertEquals(wrong.root, reopened.document!!.bookResource!!.position.outline.book)
        assertEquals(5, fixture.calls.size)
        assertEquals(Call(ReadingMode.BOOK, wrong.middle.location(LIBRARY), LANGUAGE), fixture.calls.last())
        assertEquals(listOf(ReadingMode.VERSE, ReadingMode.BOOK), fixture.modes)
    }

    @Test
    fun verseResponsesCannotEscapeTheWorkEstablishedByAResource() = runTest {
        val book = SyntheticBook()
        val valid = book.verseDocument(book.middle.record)
        val outside = ContentNode(400, 1, "Other work", 419, 0, 1, "BOOK")
        val before = ContentNode(90, 1, "Previous work", 99, 0, 1, "BOOK")
        val sections = listOf(
            valid.section.copy(next = outside),
            valid.section.copy(previous = before),
            valid.section.copy(node = book.root.copy(end = outside.end), previous = null, next = null),
        )
        for (section in sections) {
            val fixture = Fixture(this, book = book)
            fixture.controller.open(LIBRARY, LANGUAGE, book.middle)
            runCurrent()
            fixture.loadVerse = { _, _ -> valid.copy(section = section) }
            fixture.controller.toggleMode()
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            assertEquals(ReadingMode.VERSE, fixture.controller.state!!.mode)
            fixture.controller.toggleMode()
            runCurrent()
            fixture.assertReady(book.middle.record)
        }
    }

    @Test
    fun mismatchedResourceSectionsSelectionsSourcesParagraphsAndNeighboursNeverPublishDocuments() = runTest {
        val book = SyntheticBook()
        val valid = book.resourceDocument(book.middle.record)
        val section = valid.section
        val paragraph = section.paragraphs.first()
        val badTitle = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 5) entry.copy(source = entry.source.copy(title = "Mismatched source")) else entry
        })
        val outsideSource = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 5) entry.copy(source = entry.source.copy(end = book.root.end + 1)) else entry
        })
        val invalid = listOf(
            valid.copy(bookResource = null),
            valid.copy(bookResource = null, bookPosition = valid.bookResource!!.position),
            valid.copy(section = section.copy(node = section.node.copy(record = section.node.record + 1))),
            valid.copy(section = section.copy(node = section.node.copy(end = section.node.end - 1))),
            valid.copy(section = section.copy(node = section.node.copy(title = "Other resource"))),
            valid.copy(section = section.copy(kind = ReadingUnitKind.VERSE)),
            valid.copy(section = section.copy(parentTitle = "Wrong parent")),
            valid.copy(section = section.copy(previous = null)),
            valid.copy(section = section.copy(next = null)),
            valid.copy(section = section.copy(previous = book.entries[1].node)),
            valid.copy(section = section.copy(next = book.nextMiddle)),
            valid.copy(section = section.copy(paragraphs = emptyList())),
            book.resourceDocument(182), // Right selected entry, wrong exact anchor.
            book.resourceDocument(book.last.record), // Right resource, wrong selected entry/anchor.
            book.resourceDocument(book.nextChapter.record), // Unrelated resource.
            book.resourceDocument(book.middle.record, selectedOutline = badTitle),
            book.resourceDocument(book.middle.record, selectedOutline = outsideSource),
            valid.withResourceSection(section.copy(paragraphs = listOf(paragraph.copy(record = 149)))),
            valid.withResourceSection(section.copy(paragraphs = listOf(paragraph.copy(record = 250)))),
            valid.withResourceSection(section.copy(paragraphs = listOf(paragraph, paragraph))),
            valid.withResourceSection(section.copy(paragraphs = section.paragraphs.reversed())),
            valid.withResourceSection(section.copy(previous = section.previous!!.copy(record = 0))),
            valid.withResourceSection(section.copy(previous = section.previous!!.copy(record = 99))),
            valid.withResourceSection(section.copy(previous = section.previous!!.copy(record = 150))),
            valid.withResourceSection(section.copy(next = section.next!!.copy(end = 249))),
            valid.withResourceSection(section.copy(next = section.next!!.copy(end = 400))),
        )
        for ((index, document) in invalid.withIndex()) {
            val fixture = Fixture(this, book = book)
            fixture.loadBook = { _, _ -> document }
            fixture.controller.open(LIBRARY, LANGUAGE, book.middle)
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            assertTrue("Malformed resource $index escaped", fixture.changes.none { it?.document != null })
            assertEquals(1, fixture.calls.size)
            assertEquals(ReadingMode.BOOK, fixture.calls.single().mode)
            assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun emptyResourcesAllowSelectionsAndNavigationButCannotAcceptVisibleParagraphAnchors() = runTest {
        val fixture = Fixture(this)
        fixture.loadBook = { location, _ ->
            val document = fixture.book.resourceDocument(location.position)
            document.withResourceSection(document.section.copy(paragraphs = emptyList()))
        }
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.middle)
        runCurrent()
        val ready = fixture.assertReady(fixture.book.middle.record)
        assertTrue(ready.document!!.section.paragraphs.isEmpty())
        val changes = fixture.changes.toList()
        fixture.controller.saveBookAnchor(fixture.book.last.record)
        assertSame(ready, fixture.controller.state)
        assertEquals(changes, fixture.changes)
        fixture.controller.next()
        runCurrent()
        fixture.assertReady(fixture.book.nextChapter.record)
    }

    private fun ReaderDocument.withResourceSection(section: ReadingSection): ReaderDocument =
        copy(section = section, bookResource = bookResource!!.copy(section = section))

    private data class Call(val mode: ReadingMode, val location: ReadingLocation, val language: String)

    private class Fixture(
        scope: CoroutineScope,
        initialMode: ReadingMode = ReadingMode.BOOK,
        val book: SyntheticBook = SyntheticBook(),
        mirrorPosition: Boolean = false,
    ) {
        val calls = mutableListOf<Call>()
        val changes = mutableListOf<ReaderState?>()
        val modes = mutableListOf<ReadingMode>()
        // Both modes load the same bounded content; legacy-only tests opt into non-resource documents.
        var loadVerse: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ ->
            book.resourceDocument(location.position, mirrorPosition = mirrorPosition)
        }
        var loadBook: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ ->
            book.resourceDocument(location.position, mirrorPosition = mirrorPosition)
        }
        val controller = ReaderController(scope, { location, language ->
            calls += Call(ReadingMode.VERSE, location, language)
            loadVerse(location, language)
        }, { changes += it }, { location, language ->
            calls += Call(ReadingMode.BOOK, location, language)
            loadBook(location, language)
        }, initialMode, { modes += it })

        fun assertState(expected: ReaderState?) {
            assertEquals(expected, controller.state)
            assertEquals(expected, changes.lastOrNull())
        }

        fun assertReady(anchor: Int, mode: ReadingMode = ReadingMode.BOOK): ReaderState {
            val state = controller.state!!
            assertFalse(state.loading)
            assertFalse(state.failed)
            assertEquals(mode, state.mode)
            assertEquals(mode, controller.selectedMode)
            val resource = state.document!!.bookResource!!
            assertEquals(resource.section, state.document.section)
            assertEquals(resource.section.node.record, state.location.start)
            assertEquals(resource.section.node.end, state.location.end)
            assertEquals(anchor, state.location.position)
            assertEquals(anchor, resource.anchorRecord)
            assertEquals(resource.source.displayTitle, state.location.title)
            assertState(state)
            return state
        }

        fun assertFailed(reason: ReadingFailure): ReaderState {
            val state = controller.state!!
            assertFalse(state.loading)
            assertTrue(state.failed)
            assertEquals(reason, state.failure)
            assertNull(state.document)
            assertState(state)
            return state
        }
    }

    private class SyntheticBook(rootTitle: String = "Generated work ā") {
        val root = ContentNode(100, 1, rootTitle, 399, 3, 1, "BOOK")
        val intro = ContentNode(110, root.record, "01. Introduction ā", 149, 0, 2, "TEXT")
        val chapter = ContentNode(150, root.record, "02. First chapter", 249, 3, 2, "CHAPTER")
        val first = ContentNode(160, chapter.record, "01. Verse 1", 179, 0, 3, "TEXT")
        val middle = ContentNode(180, chapter.record, "02. Verse 2–3 ā", 209, 0, 3, "TEXT")
        val last = ContentNode(210, chapter.record, "03. Verse 4", 249, 0, 3, "TEXT")
        val nextChapter = ContentNode(250, root.record, "03. Second chapter", 399, 3, 2, "CHAPTER")
        val nextFirst = ContentNode(260, nextChapter.record, "01. Next verse 1", 289, 0, 3, "TEXT")
        val nextMiddle = ContentNode(290, nextChapter.record, "02. Next verse 2", 339, 0, 3, "TEXT")
        val nextLast = ContentNode(340, nextChapter.record, "03. Next verse 3", 399, 0, 3, "TEXT")
        val entries = listOf(
            BookEntry(root.copy(end = 109), root, ReadingUnitKind.SECTION, "Generated library"),
            BookEntry(intro.copy(end = 129), intro, ReadingUnitKind.SECTION, root.displayTitle),
            BookEntry(intro.copy(record = 130), intro, ReadingUnitKind.SECTION, root.displayTitle),
            BookEntry(chapter.copy(end = 159), chapter, ReadingUnitKind.CHAPTER, root.displayTitle),
            BookEntry(first, first, ReadingUnitKind.VERSE, chapter.displayTitle),
            BookEntry(middle, middle, ReadingUnitKind.VERSE, chapter.displayTitle),
            BookEntry(last, last, ReadingUnitKind.VERSE, chapter.displayTitle),
            BookEntry(nextChapter.copy(end = 259), nextChapter, ReadingUnitKind.CHAPTER, root.displayTitle),
            BookEntry(nextFirst, nextFirst, ReadingUnitKind.VERSE, nextChapter.displayTitle),
            BookEntry(nextMiddle, nextMiddle, ReadingUnitKind.VERSE, nextChapter.displayTitle),
            BookEntry(nextLast, nextLast, ReadingUnitKind.VERSE, nextChapter.displayTitle),
        )
        val outline = BookOutline(root, entries)
        private val resourceEntries = listOf(0..2, 3..6, 7..10)
        private val resourceNodes = listOf(root.copy(end = intro.end), chapter, nextChapter)

        fun resourceDocument(
            record: Int,
            mirrorPosition: Boolean = false,
            entryNeighbours: Boolean = false,
            selectedOutline: BookOutline = outline,
        ): ReaderDocument {
            val members = selectedOutline.entries
            val index = members.indexOfFirst { record in it.node.record..it.node.end }
            val resourceIndex = resourceEntries.indexOfFirst { index in it }
            val range = resourceEntries[resourceIndex]
            val node = resourceNodes[resourceIndex]
            val position = BookPosition(selectedOutline, index)
            val previous = if (entryNeighbours) members.getOrNull(range.first - 1)?.node
                else resourceNodes.getOrNull(resourceIndex - 1)
            val next = if (entryNeighbours) members.getOrNull(range.last + 1)?.node
                else resourceNodes.getOrNull(resourceIndex + 1)
            val section = ReadingSection(node,
                if (resourceIndex == 0) ReadingUnitKind.SECTION else ReadingUnitKind.CHAPTER,
                if (resourceIndex == 0) "Generated library" else root.displayTitle,
                paragraphs(node), previous, next)
            val resource = BookResource(section, position, range.first, range.last, record)
            return ReaderDocument(section, "<p>Generated resource ${node.record}–${node.end}.</p>",
                if (mirrorPosition) position else null, resource)
        }

        fun entryDocument(record: Int): ReaderDocument {
            val index = entries.indexOfFirst { record in it.node.record..it.node.end }
            val entry = entries[index]
            val section = ReadingSection(entry.node, entry.kind, entry.parentTitle, paragraphs(entry.node),
                entries.getOrNull(index - 1)?.node, entries.getOrNull(index + 1)?.node)
            return ReaderDocument(section, "<p>Generated single entry.</p>", BookPosition(outline, index))
        }

        fun verseDocument(record: Int): ReaderDocument {
            val entry = entries.firstOrNull { it.source.record == record }
                ?: throw ReadingException(ReadingFailure.CHOOSE_SECTION)
            val node = entry.source
            val verses = entries.filter { it.kind == ReadingUnitKind.VERSE }.map { it.source }
            val index = verses.indexOf(node)
            val section = ReadingSection(node, entry.kind, entry.parentTitle, paragraphs(node),
                if (index >= 0) verses.getOrNull(index - 1) else null,
                if (index >= 0) verses.getOrNull(index + 1) else null)
            return ReaderDocument(section, "<p>Generated legacy source ${node.record}.</p>")
        }

        private fun paragraphs(node: ContentNode): List<Paragraph> = entries
            .flatMap { listOf(it.node.record, it.node.record + 2, it.node.end) }
            .filter { it in node.record..node.end }
            .map { Paragraph(it, "Generated record $it only.", "Body", "Text") }
    }

    companion object {
        private const val LIBRARY = "synthetic-resource-library"
        private const val LANGUAGE = "sa-Latn-IN"
    }
}