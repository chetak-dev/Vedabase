package org.vedareader.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReadingException
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind

/** Metadata and text are entirely synthetic; all asynchronous cases use explicit deferred results. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBookControllerTest {
    @Test
    fun bookOpenUsesTheExactSelectedVerseAndPublishesUsableLoadingAndReadySnapshots() = runTest {
        val fixture = Fixture(this)
        val selected = fixture.book.verseB
        fixture.controller.open(LIBRARY, LANGUAGE, selected)
        val loading = ReaderState(selected.location(LIBRARY), LANGUAGE, mode = ReadingMode.BOOK)
        fixture.assertState(loading)
        assertTrue(fixture.calls.isEmpty())
        runCurrent()
        val ready = loading.copy(document = fixture.book.document(5), loading = false)
        fixture.assertState(ready)
        assertEquals(listOf(loading, ready), fixture.changes)
        assertEquals(listOf(Call(ReadingMode.BOOK, selected.location(LIBRARY), LANGUAGE)), fixture.calls)
        assertEquals(fixture.book.root, ready.document!!.bookPosition!!.outline.book)
        assertEquals(5, ready.document.bookPosition!!.index)
        assertTrue(fixture.modes.isEmpty())
    }

    @Test
    fun openingNestedSourceAndSplitFrontMatterValidatesTheAnchorNotTheWholeOriginalRange() = runTest {
        val book = SyntheticBook()
        val purport = ContentNode(168, book.verseA.record, "PURPORT", 179, 0, 4, "TEXT")
        for ((selected, index) in listOf(book.root to 0, book.intro to 1, book.chapter to 3, purport to 4)) {
            val fixture = Fixture(this, book = book)
            fixture.controller.open(LIBRARY, LANGUAGE, selected)
            runCurrent()
            fixture.assertReady(index)
            assertEquals(selected.location(LIBRARY), fixture.calls.single().location)
            assertEquals(book.entries[index].node.location(LIBRARY), fixture.controller.state!!.location)
        }
    }

    @Test
    fun sameRangeTogglesReloadTheSelectedSourceAndPreserveScrollInBothDirections() = runTest {
        val fixture = Fixture(this, initialMode = ReadingMode.VERSE)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.saveScroll(420)
        fixture.controller.toggleMode()
        assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
        assertTrue(fixture.controller.state!!.loading)
        assertNull(fixture.controller.state!!.document)
        runCurrent()
        fixture.assertReady(4)
        assertEquals(420, fixture.controller.state!!.scrollY)
        fixture.controller.saveScroll(615)
        fixture.controller.toggleMode()
        runCurrent()
        assertEquals(ReadingMode.VERSE, fixture.controller.state!!.mode)
        assertEquals(615, fixture.controller.state!!.scrollY)
        assertFalse(fixture.controller.state!!.enterAtEnd)
        assertEquals(listOf(ReadingMode.BOOK, ReadingMode.VERSE), fixture.modes)
        assertEquals(listOf(ReadingMode.VERSE, ReadingMode.BOOK, ReadingMode.VERSE), fixture.calls.map { it.mode })
        assertTrue(fixture.calls.all { it.location == fixture.book.verseA.location(LIBRARY) })
    }

    @Test
    fun differentRangeTogglesResetScrollAndMapSyntheticChunkBackToActualSourceAndTitle() = runTest {
        val fixture = Fixture(this, initialMode = ReadingMode.VERSE)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.intro)
        runCurrent()
        fixture.controller.saveScroll(500)
        fixture.controller.toggleMode()
        runCurrent()
        fixture.assertReady(1)
        assertEquals(0, fixture.controller.state!!.scrollY)
        fixture.controller.next()
        runCurrent()
        fixture.assertReady(2)
        fixture.controller.saveScroll(750)
        fixture.controller.toggleMode()
        assertEquals(fixture.book.intro.location(LIBRARY), fixture.controller.state!!.location)
        runCurrent()
        val ready = fixture.controller.state!!
        assertFalse(ready.failed)
        assertEquals(ReadingMode.VERSE, ready.mode)
        assertEquals(fixture.book.intro, ready.document!!.section.node)
        assertEquals(fixture.book.intro.displayTitle, fixture.calls.last().location.title)
        assertEquals(fixture.book.intro.record, fixture.calls.last().location.position)
        assertEquals(0, ready.scrollY)
    }

    @Test
    fun bookNavigationUsesOutlineNeighboursAndPreviousEntersAtEndUntilActualScrollIsPublished() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.saveScroll(640)
        fixture.controller.next()
        assertFalse(fixture.controller.state!!.enterAtEnd)
        assertEquals(0, fixture.controller.state!!.scrollY)
        runCurrent()
        fixture.assertReady(5)
        fixture.controller.previous()
        assertTrue(fixture.controller.state!!.loading)
        assertTrue(fixture.controller.state!!.enterAtEnd)
        assertEquals(0, fixture.controller.state!!.scrollY)
        runCurrent()
        fixture.assertReady(4)
        assertTrue(fixture.controller.state!!.enterAtEnd)
        fixture.controller.saveScroll(912)
        assertFalse(fixture.controller.state!!.enterAtEnd)
        assertEquals(912, fixture.controller.state!!.scrollY)
        val changes = fixture.changes.toList()
        fixture.controller.saveScroll(912)
        assertEquals(changes, fixture.changes)

        fixture.controller.previous()
        runCurrent()
        fixture.assertReady(3) // Chapter preamble, not the legacy verse neighbour.
        assertTrue(fixture.controller.state!!.enterAtEnd)
        fixture.controller.saveScroll(0) // Short sections still consume the one-shot end request.
        assertFalse(fixture.controller.state!!.enterAtEnd)
        assertEquals(0, fixture.controller.state!!.scrollY)
        assertTrue(fixture.calls.all { it.location.scrollY == 0 && it.mode == ReadingMode.BOOK })
    }

    @Test
    fun bookBoundsDoNothingAndJumpAcceptsOnlyExactCurrentOutlineEntries() = runTest {
        val fixture = Fixture(this)
        fixture.controller.jumpToBookEntry(100)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.root)
        runCurrent()
        val firstChanges = fixture.changes.toList()
        fixture.controller.previous()
        for (invalid in listOf(Int.MIN_VALUE, 0, 99, 101, 169, 220, Int.MAX_VALUE)) {
            fixture.controller.jumpToBookEntry(invalid)
        }
        runCurrent()
        assertEquals(firstChanges, fixture.changes)
        assertEquals(1, fixture.calls.size)
        fixture.controller.saveScroll(240)
        fixture.controller.jumpToBookEntry(fixture.book.entries.last().node.record)
        runCurrent()
        fixture.assertReady(6)
        assertEquals(0, fixture.controller.state!!.scrollY)
        assertFalse(fixture.controller.state!!.enterAtEnd)
        val lastChanges = fixture.changes.toList()
        fixture.controller.next()
        runCurrent()
        assertEquals(lastChanges, fixture.changes)

        fixture.controller.jumpToBookEntry(fixture.book.entries[2].node.record)
        runCurrent()
        fixture.assertReady(2)
        assertEquals(130, fixture.calls.last().location.position) // Synthetic entry start is valid.
        fixture.controller.saveScroll(320)
        fixture.controller.jumpToBookEntry(130)
        runCurrent()
        assertEquals(0, fixture.controller.state!!.scrollY)
        assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
        assertTrue(fixture.modes.isEmpty())
        fixture.controller.toggleMode()
        runCurrent()
        assertEquals(110, fixture.calls.last().location.position) // Jump kept the source bookmark.
        val verseChanges = fixture.changes.toList()
        fixture.controller.jumpToBookEntry(160)
        assertEquals(verseChanges, fixture.changes)
    }

    @Test
    fun loadingIgnoresTogglesNavigationJumpRetryAndScrollWithoutModeCallbacks() = runTest {
        val response = CompletableDeferred<ReaderDocument>()
        val fixture = Fixture(this)
        fixture.loadBook = { _, _ -> response.await() }
        fixture.controller.toggleMode()
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        try {
            val loading = fixture.controller.state
            val changes = fixture.changes.toList()
            repeat(2) {
                fixture.controller.toggleMode()
                fixture.controller.next()
                fixture.controller.previous()
                fixture.controller.jumpToBookEntry(180)
                fixture.controller.retry()
                fixture.controller.saveScroll(100)
                runCurrent()
                fixture.assertState(loading)
                assertEquals(changes, fixture.changes)
                assertTrue(fixture.modes.isEmpty())
                assertEquals(1, fixture.calls.size)
            }
            response.complete(fixture.book.document(4))
            runCurrent()
            fixture.assertReady(4)
        } finally {
            response.complete(fixture.book.document(4))
            fixture.controller.close()
        }
    }

    @Test
    fun typedBookFailuresStayInBookModeAndRetryTheExactRequestWithoutVerseFallback() = runTest {
        for (reason in ReadingFailure.entries) {
            val fixture = Fixture(this)
            var attempts = 0
            fixture.loadBook = { _, _ ->
                if (++attempts == 1) throw ReadingException(reason)
                fixture.book.document(4)
            }
            fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
            runCurrent()
            fixture.assertFailed(reason)
            assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
            val failedChanges = fixture.changes.toList()
            fixture.controller.next()
            fixture.controller.previous()
            fixture.controller.jumpToBookEntry(180)
            fixture.controller.saveScroll(240)
            assertEquals(failedChanges, fixture.changes)
            fixture.controller.retry()
            fixture.controller.retry()
            runCurrent()
            fixture.assertReady(4)
            assertEquals(2, fixture.calls.size)
            assertEquals(fixture.calls.first(), fixture.calls.last())
            assertTrue(fixture.calls.all { it.mode == ReadingMode.BOOK })
            assertTrue(fixture.modes.isEmpty())
        }
    }

    @Test
    fun failedInitialToggleCanReturnToVerseWithSameRangeScrollAndNoSilentFallback() = runTest {
        val fixture = Fixture(this, initialMode = ReadingMode.VERSE)
        fixture.loadBook = { _, _ -> throw ReadingException(ReadingFailure.CHOOSE_SECTION) }
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.saveScroll(487)
        fixture.controller.toggleMode()
        runCurrent()
        fixture.assertFailed(ReadingFailure.CHOOSE_SECTION)
        assertEquals(ReadingMode.BOOK, fixture.controller.state!!.mode)
        assertEquals(2, fixture.calls.size)
        fixture.controller.toggleMode()
        runCurrent()
        assertFalse(fixture.controller.state!!.failed)
        assertEquals(ReadingMode.VERSE, fixture.controller.state!!.mode)
        assertEquals(487, fixture.controller.state!!.scrollY)
        assertEquals(listOf(ReadingMode.BOOK, ReadingMode.VERSE), fixture.modes)
    }

    @Test
    fun unsupportedContainerToggleFailsExplicitlyAndToggleBackRestoresTheChunkAnchor() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.previous()
        runCurrent()
        fixture.assertReady(3)
        fixture.controller.saveScroll(368)
        fixture.controller.toggleMode()
        runCurrent()
        fixture.assertFailed(ReadingFailure.CHOOSE_SECTION)
        assertEquals(ReadingMode.VERSE, fixture.controller.state!!.mode)
        assertEquals(fixture.book.chapter.location(LIBRARY), fixture.calls.last().location)
        fixture.controller.retry()
        runCurrent()
        fixture.assertFailed(ReadingFailure.CHOOSE_SECTION)
        fixture.controller.toggleMode()
        runCurrent()
        fixture.assertReady(3)
        assertEquals(fixture.book.entries[3].node.location(LIBRARY), fixture.calls.last().location)
        assertEquals(368, fixture.controller.state!!.scrollY)
        assertFalse(fixture.controller.state!!.enterAtEnd)
    }

    @Test
    fun failedNavigationAndJumpRememberTheTargetSourceRatherThanThePreviousDisplayedVerse() = runTest {
        for (jump in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
            runCurrent()
            fixture.loadBook = { _, _ -> throw ReadingException(ReadingFailure.LOAD) }
            if (jump) fixture.controller.jumpToBookEntry(130) else fixture.controller.previous()
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            fixture.controller.toggleMode()
            runCurrent()
            val source = if (jump) fixture.book.intro else fixture.book.chapter
            assertEquals(source.location(LIBRARY), fixture.calls.last().location)
            assertEquals(ReadingMode.VERSE, fixture.calls.last().mode)
            if (!jump) fixture.assertFailed(ReadingFailure.CHOOSE_SECTION)
        }
    }

    @Test
    fun previousFailureRetriesWithEnterAtEndAndDoesNotInventAScrollOffset() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseB)
        runCurrent()
        fixture.loadBook = { _, _ -> throw ReadingException(ReadingFailure.LOAD) }
        fixture.controller.previous()
        runCurrent()
        fixture.assertFailed(ReadingFailure.LOAD)
        assertTrue(fixture.controller.state!!.enterAtEnd)
        fixture.loadBook = { _, _ -> fixture.book.document(4) }
        fixture.controller.retry()
        runCurrent()
        fixture.assertReady(4)
        assertTrue(fixture.controller.state!!.enterAtEnd)
        assertEquals(0, fixture.controller.state!!.scrollY)
        assertEquals(fixture.calls[1], fixture.calls[2])
    }

    @Test
    fun selectedModeSurvivesCloseReopenAndChangedLibraryWithoutRetainingScrollOrBookIdentity() = runTest {
        val fixture = Fixture(this, initialMode = ReadingMode.VERSE)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.toggleMode()
        runCurrent()
        fixture.controller.saveScroll(888)
        fixture.controller.close()
        fixture.controller.toggleMode()
        fixture.assertState(null)
        assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
        val replacement = SyntheticBook(rootTitle = "Another library's book")
        fixture.loadBook = { location, _ -> replacement.at(location.position) }
        fixture.controller.open(OTHER_LIBRARY, "hi-Deva", replacement.verseB)
        runCurrent()
        val ready = fixture.controller.state!!
        assertFalse(ready.failed)
        assertEquals(OTHER_LIBRARY, ready.location.libraryId)
        assertEquals("hi-Deva", ready.languageTag)
        assertEquals(replacement.root, ready.document!!.bookPosition!!.outline.book)
        assertEquals(0, ready.scrollY)
        assertEquals(ReadingMode.BOOK, ready.mode)
        assertEquals(listOf(ReadingMode.BOOK), fixture.modes)
    }

    @Test
    fun defaultBookLoaderDelegatesToOriginalLoaderButStillRequiresValidBookMetadata() = runTest {
        val book = SyntheticBook()
        var calls = 0
        val controller = ReaderController(this, { _, _ -> calls++; book.document(4) }, {},
            initialMode = ReadingMode.BOOK)
        controller.open(LIBRARY, LANGUAGE, book.verseA)
        runCurrent()
        assertFalse(controller.state!!.failed)
        assertEquals(ReadingMode.BOOK, controller.state!!.mode)
        assertEquals(1, calls)

        val missing = ReaderController(this, { _, _ -> book.legacy(book.verseA.record) }, {},
            initialMode = ReadingMode.BOOK)
        missing.open(LIBRARY, LANGUAGE, book.verseA)
        runCurrent()
        assertTrue(missing.state!!.failed)
        assertNull(missing.state!!.document)
        assertEquals(ReadingMode.BOOK, missing.state!!.mode)
        missing.toggleMode()
        runCurrent()
        assertFalse(missing.state!!.failed)
        assertEquals(ReadingMode.VERSE, missing.state!!.mode)
    }

    @Test
    fun wrongRootResponsesAreRejectedForNextPreviousJumpAndTheirRetries() = runTest {
        val book = SyntheticBook()
        val wrongRoots = listOf(
            SyntheticBook(rootTitle = "Wrong work with overlapping records").outline,
            BookOutline(book.root.copy(record = 99), listOf(
                book.entries.first().copy(node = book.entries.first().node.copy(record = 99),
                    source = book.root.copy(record = 99)),
            ) + book.entries.drop(1)),
        )
        for (wrongRoot in wrongRoots) {
            for (navigation in listOf("next", "previous", "jump")) {
                val fixture = Fixture(this, book = book)
                fixture.controller.open(LIBRARY, LANGUAGE, book.verseA)
                runCurrent()
                fixture.loadBook = { location, _ -> book.at(location.position, wrongRoot) }
                when (navigation) {
                    "next" -> fixture.controller.next()
                    "previous" -> fixture.controller.previous()
                    else -> fixture.controller.jumpToBookEntry(130)
                }
                runCurrent()
                fixture.assertFailed(ReadingFailure.LOAD)
                fixture.controller.retry()
                runCurrent()
                fixture.assertFailed(ReadingFailure.LOAD)
                fixture.loadBook = { location, _ -> book.at(location.position) }
                fixture.controller.retry()
                runCurrent()
                assertFalse(navigation, fixture.controller.state!!.failed)
                assertEquals(book.root, fixture.controller.state!!.document!!.bookPosition!!.outline.book)
            }
        }
    }

    @Test
    fun bookIdentitySurvivesModeTogglesButExplicitOpenCanSelectANewWork() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.toggleMode()
        runCurrent()
        fixture.controller.next()
        runCurrent()
        val wrong = SyntheticBook(rootTitle = "Different root")
        fixture.loadBook = { location, _ -> wrong.at(location.position) }
        fixture.controller.toggleMode()
        runCurrent()
        fixture.assertFailed(ReadingFailure.LOAD)
        fixture.controller.open(LIBRARY, LANGUAGE, wrong.verseB)
        runCurrent()
        assertFalse(fixture.controller.state!!.failed)
        assertEquals(wrong.root, fixture.controller.state!!.document!!.bookPosition!!.outline.book)
    }

    @Test
    fun verseResultsCannotEscapeAKnownBookThroughUnitsOrNeighbours() = runTest {
        val book = SyntheticBook()
        val valid = book.legacy(book.verseA.record)
        val outside = ContentNode(220, 1, "Other work", 229, 0, 1, "BOOK")
        val before = ContentNode(90, 1, "Previous work", 99, 0, 1, "BOOK")
        for (section in listOf(
            valid.section.copy(next = outside),
            valid.section.copy(previous = before),
            valid.section.copy(node = book.root.copy(end = 229), previous = null, next = null),
        )) {
            val fixture = Fixture(this, book = book)
            fixture.controller.open(LIBRARY, LANGUAGE, book.verseA)
            runCurrent()
            fixture.loadVerse = { _, _ -> valid.copy(section = section) }
            fixture.controller.toggleMode()
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            fixture.controller.toggleMode()
            runCurrent()
            fixture.assertReady(4)
        }
    }

    @Test
    fun malformedBookMetadataAndParagraphsNeverPublishAPartialDocument() = runTest {
        val book = SyntheticBook()
        val valid = book.document(4)
        val section = valid.section
        val paragraph = section.paragraphs.first()
        val badSource = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 4) entry.copy(source = entry.source.copy(title = "Mismatched source title")) else entry
        })
        val outsideSource = BookOutline(book.root, book.entries.mapIndexed { index, entry ->
            if (index == 4) entry.copy(source = entry.source.copy(end = book.root.end + 1)) else entry
        })
        val invalid = listOf(
            valid.copy(bookPosition = null),
            valid.copy(bookPosition = BookPosition(book.outline, 5)),
            valid.copy(bookPosition = BookPosition(badSource, 4)),
            valid.copy(bookPosition = BookPosition(outsideSource, 4)),
            valid.copy(section = section.copy(node = book.verseA.copy(title = "Other node"))),
            valid.copy(section = section.copy(kind = ReadingUnitKind.CHAPTER)),
            valid.copy(section = section.copy(parentTitle = "Wrong parent")),
            valid.copy(section = section.copy(previous = null)),
            valid.copy(section = section.copy(next = null)),
            valid.copy(section = section.copy(previous = book.entries[2].node)),
            valid.copy(section = section.copy(next = book.entries[6].node)),
            valid.copy(section = section.copy(paragraphs = listOf(paragraph.copy(record = 159)))),
            valid.copy(section = section.copy(paragraphs = listOf(paragraph.copy(record = 180)))),
            valid.copy(section = section.copy(paragraphs = listOf(paragraph, paragraph))),
            valid.copy(section = section.copy(paragraphs = section.paragraphs.reversed())),
            book.document(5), // Well-formed, but does not contain the requested anchor.
        )
        for ((index, document) in invalid.withIndex()) {
            val fixture = Fixture(this, book = book)
            fixture.loadBook = { _, _ -> document }
            fixture.controller.open(LIBRARY, LANGUAGE, book.verseA)
            runCurrent()
            fixture.assertFailed(ReadingFailure.LOAD)
            assertTrue("Invalid document $index was exposed", fixture.changes.none { it?.document != null })
        }
    }

    @Test
    fun emptyBookSectionStillHasValidOutlineNavigation() = runTest {
        val fixture = Fixture(this)
        fixture.loadBook = { location, _ ->
            val document = fixture.book.at(location.position)
            document.copy(section = document.section.copy(paragraphs = emptyList()))
        }
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.assertReady(4)
        assertTrue(fixture.controller.state!!.document!!.section.paragraphs.isEmpty())
        fixture.controller.next()
        runCurrent()
        fixture.assertReady(5)
    }

    @Test
    fun closeCancelsBookLoadWithoutCancellingOwnerAndPreservesTheSelectedMode() = runTest {
        val fixture = Fixture(this)
        val response = CompletableDeferred<ReaderDocument>()
        val requestJob = CompletableDeferred<Job>()
        var finished = false
        fixture.loadBook = { _, _ ->
            requestJob.complete(currentCoroutineContext().job)
            try { response.await() } finally { finished = true }
        }
        fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
        runCurrent()
        fixture.controller.close()
        runCurrent()
        assertTrue(requestJob.await().isCancelled)
        assertTrue(finished)
        assertTrue(coroutineContext.job.isActive)
        fixture.assertState(null)
        assertEquals(ReadingMode.BOOK, fixture.controller.selectedMode)
        val changes = fixture.changes.toList()
        response.complete(fixture.book.document(4))
        runCurrent()
        assertEquals(changes, fixture.changes)
        assertTrue(fixture.modes.isEmpty())
    }

    @Test
    fun cancelledOwnerCannotPublishANonCooperativeBookSuccessOrFailure() = runTest {
        for (fail in listOf(false, true)) {
            val owner = Job(coroutineContext.job)
            val fixture = Fixture(CoroutineScope(coroutineContext + owner))
            val response = CompletableDeferred<ReaderDocument>()
            fixture.loadBook = { _, _ -> withContext(NonCancellable) { response.await() } }
            fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
            runCurrent()
            try {
                val changes = fixture.changes.toList()
                owner.cancel()
                if (fail) response.completeExceptionally(ReadingException(ReadingFailure.LOAD))
                else response.complete(fixture.book.document(4))
                runCurrent()
                assertEquals(changes, fixture.changes)
                assertTrue(owner.isCancelled)
                assertTrue(coroutineContext.job.isActive)
                assertTrue(fixture.controller.state!!.loading)
            } finally {
                response.complete(fixture.book.document(4))
                owner.cancel()
                fixture.controller.close()
                runCurrent()
            }
        }
    }

    @Test
    fun staleNonCancellableBookSuccessOrFailureCannotReplaceNewWorkLibraryOrClosedState() = runTest {
        for (replacement in listOf("work", "library", "close")) {
            for (fail in listOf(false, true)) {
                val fixture = Fixture(this, initialMode = ReadingMode.VERSE)
                fixture.controller.open(LIBRARY, LANGUAGE, fixture.book.verseA)
                runCurrent()
                val response = CompletableDeferred<ReaderDocument>()
                val requestJob = CompletableDeferred<Job>()
                var returned = false
                fixture.loadBook = { _, _ ->
                    requestJob.complete(currentCoroutineContext().job)
                    try { withContext(NonCancellable) { response.await() } } finally { returned = true }
                }
                fixture.controller.toggleMode()
                runCurrent()
                try {
                    if (replacement == "close") fixture.controller.close() else {
                        val other = if (replacement == "work") SyntheticBook(offset = 1000)
                            else SyntheticBook(rootTitle = "Same IDs in another library")
                        fixture.loadBook = { location, _ -> other.at(location.position) }
                        fixture.controller.open(if (replacement == "library") OTHER_LIBRARY else LIBRARY,
                            "bn-Beng", other.verseB)
                        runCurrent()
                        assertFalse(fixture.controller.state!!.failed)
                        assertEquals(other.root, fixture.controller.state!!.document!!.bookPosition!!.outline.book)
                    }
                    val expected = fixture.controller.state
                    val changes = fixture.changes.toList()
                    val modes = fixture.modes.toList()
                    if (fail) response.completeExceptionally(ReadingException(ReadingFailure.SECTION_TOO_LARGE))
                    else response.complete(fixture.book.document(4))
                    runCurrent()
                    assertTrue(returned)
                    assertTrue(requestJob.await().isCancelled)
                    assertTrue(coroutineContext.job.isActive)
                    fixture.assertState(expected)
                    assertEquals(changes, fixture.changes)
                    assertEquals(modes, fixture.modes)
                } finally {
                    response.complete(fixture.book.document(4))
                    fixture.controller.close()
                    runCurrent()
                }
            }
        }
    }

    @Test
    fun observersCanCloseDuringLoadingOrModeChangeWithoutStartingAnObsoleteLoader() = runTest {
        val book = SyntheticBook()
        var calls = 0
        var closeOnLoading = false
        lateinit var controller: ReaderController
        controller = ReaderController(this, { _, _ -> calls++; book.legacy(160) }, { state ->
            if (closeOnLoading && state?.loading == true) controller.close()
        }, { _, _ -> calls++; book.document(4) }, onModeChange = { controller.close() })
        controller.open(LIBRARY, LANGUAGE, book.verseA)
        runCurrent()
        controller.toggleMode()
        runCurrent()
        assertNull(controller.state)
        assertEquals(ReadingMode.BOOK, controller.selectedMode)
        assertEquals(1, calls)
        closeOnLoading = true
        controller.open(LIBRARY, LANGUAGE, book.verseB)
        runCurrent()
        assertNull(controller.state)
        assertEquals(1, calls)
    }

    private data class Call(val mode: ReadingMode, val location: ReadingLocation, val language: String)

    private class Fixture(
        scope: CoroutineScope,
        initialMode: ReadingMode = ReadingMode.BOOK,
        val book: SyntheticBook = SyntheticBook(),
    ) {
        val calls = mutableListOf<Call>()
        val changes = mutableListOf<ReaderState?>()
        val modes = mutableListOf<ReadingMode>()
        var loadVerse: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ -> book.legacy(location.position) }
        var loadBook: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ -> book.at(location.position) }
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

        fun assertReady(index: Int) {
            val state = controller.state!!
            assertFalse(state.loading)
            assertFalse(state.failed)
            assertEquals(ReadingMode.BOOK, state.mode)
            assertEquals(index, state.document!!.bookPosition!!.index)
            assertEquals(book.entries[index].node, state.document.section.node)
            assertState(state)
        }

        fun assertFailed(reason: ReadingFailure) {
            val state = controller.state!!
            assertFalse(state.loading)
            assertTrue(state.failed)
            assertEquals(reason, state.failure)
            assertNull(state.document)
            assertState(state)
        }
    }

    private class SyntheticBook(offset: Int = 0, rootTitle: String = "Generated book ā") {
        val root = ContentNode(100 + offset, 1, rootTitle, 219 + offset, 3, 1, "BOOK")
        val intro = ContentNode(110 + offset, root.record, "01. Introduction ā", 149 + offset, 0, 2, "TEXT")
        val chapter = ContentNode(150 + offset, root.record, "02. Chapter", 219 + offset, 3, 2, "CHAPTER")
        val verseA = ContentNode(160 + offset, chapter.record, "01. Verse 1", 179 + offset, 2, 3, "TEXT")
        val verseB = ContentNode(180 + offset, chapter.record, "02. Verse 2–3", 199 + offset, 2, 3, "TEXT")
        val ending = ContentNode(200 + offset, chapter.record, "03. Closing text", 219 + offset, 0, 3, "TEXT")
        val entries = listOf(
            BookEntry(root.copy(end = 109 + offset), root, ReadingUnitKind.SECTION, "Generated library"),
            BookEntry(intro.copy(end = 129 + offset), intro, ReadingUnitKind.SECTION, root.displayTitle),
            BookEntry(intro.copy(record = 130 + offset), intro, ReadingUnitKind.SECTION, root.displayTitle),
            BookEntry(chapter.copy(end = 159 + offset), chapter, ReadingUnitKind.CHAPTER, root.displayTitle),
            BookEntry(verseA, verseA, ReadingUnitKind.VERSE, chapter.displayTitle),
            BookEntry(verseB, verseB, ReadingUnitKind.VERSE, chapter.displayTitle),
            BookEntry(ending, ending, ReadingUnitKind.SECTION, chapter.displayTitle),
        )
        val outline = BookOutline(root, entries)

        fun document(index: Int, selectedOutline: BookOutline = outline): ReaderDocument {
            val entry = selectedOutline.entries[index]
            return ReaderDocument(ReadingSection(entry.node, entry.kind, entry.parentTitle, paragraphs(entry.node),
                selectedOutline.entries.getOrNull(index - 1)?.node, selectedOutline.entries.getOrNull(index + 1)?.node),
                "<p>Generated book range ${entry.node.record}–${entry.node.end} only.</p>", BookPosition(selectedOutline, index))
        }

        fun at(record: Int, selectedOutline: BookOutline = outline): ReaderDocument =
            document(selectedOutline.entries.indexOfFirst { record in it.node.record..it.node.end }, selectedOutline)

        fun legacy(record: Int): ReaderDocument {
            val node = listOf(intro, verseA, verseB, ending).firstOrNull { it.record == record }
                ?: throw ReadingException(ReadingFailure.CHOOSE_SECTION)
            return ReaderDocument(ReadingSection(node,
                if (node == verseA || node == verseB) ReadingUnitKind.VERSE else ReadingUnitKind.SECTION,
                chapter.displayTitle, paragraphs(node),
                previous = if (node == verseB) verseA else null,
                next = if (node == verseA) verseB else null), "<p>Generated legacy source ${node.record}.</p>")
        }

        private fun paragraphs(node: ContentNode) = listOf(node.record, node.end).distinct().map {
            Paragraph(it, "Generated record $it only.", "Body", "Text")
        }
    }

    companion object {
        private const val LIBRARY = "synthetic-book-library"
        private const val OTHER_LIBRARY = "synthetic-other-library"
        private const val LANGUAGE = "sa-Latn-IN"
    }
}