package org.vedareader.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReadingException
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.io.IOException

/** Synthetic section data only. Deferred responses, not clocks, control every pending request. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderControllerTest {
    @Test
    fun originalPositionalDocumentStateAndControllerArgumentsKeepVerseDefaults() = runTest {
        val originalDocument = ReaderDocument(firstDocument.section, firstDocument.html)
        val originalState = ReaderState(section.location(LIBRARY_A), LANGUAGE, originalDocument,
            false, false, ReadingFailure.LOAD, 72)
        assertNull(originalDocument.bookPosition)
        assertEquals(ReadingMode.VERSE, originalState.mode)
        assertFalse(originalState.enterAtEnd)

        val changes = mutableListOf<ReaderState?>()
        val controller = ReaderController(this, { _, _ -> originalDocument }, { changes += it })
        assertEquals(ReadingMode.VERSE, controller.selectedMode)
        controller.open(LIBRARY_A, LANGUAGE, section)
        runCurrent()
        assertEquals(originalState.copy(scrollY = 0), controller.state)
        assertEquals(controller.state, changes.last())
    }

    @Test
    fun openingLoadsTheWholeVerseAndNestedPurportPromotesToTheSameFullUnit() = runTest {
        val fixture = Fixture(this)
        fixture.assertState(null)
        fixture.controller.next()
        fixture.controller.previous()
        fixture.controller.retry()
        fixture.controller.saveScroll(80)
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.changes.isEmpty())

        val location = section.location(LIBRARY_A)
        fixture.controller.open(LIBRARY_A, LANGUAGE, section)
        fixture.assertState(ReaderState(location, LANGUAGE))
        assertTrue("Opening publishes loading before the queued loader runs", fixture.calls.isEmpty())
        runCurrent()

        val loaded = ReaderState(location, LANGUAGE, firstDocument, loading = false)
        fixture.assertState(loaded)
        assertEquals(listOf(location to LANGUAGE), fixture.calls)
        assertEquals(listOf(ReaderState(location, LANGUAGE), loaded), fixture.changes)
        assertEquals(130, loaded.document!!.section.paragraphs.size)
        assertEquals(section.end, loaded.document.section.paragraphs.last().record)

        val purport = ContentNode(600, section.record, "PURPORT", section.end, 0, 83, "TEXT")
        val nested = Fixture(this) { _, _ -> firstDocument }
        nested.controller.open(LIBRARY_A, LANGUAGE, purport)
        nested.assertState(ReaderState(purport.location(LIBRARY_A), LANGUAGE))
        runCurrent()
        nested.assertState(loaded)
        assertEquals(listOf(purport.location(LIBRARY_A) to LANGUAGE), nested.calls)

        val chapter = section.copy(title = "Whole generated chapter")
        val chapterDocument = firstDocument.copy(section = firstDocument.section.copy(
            node = chapter, kind = ReadingUnitKind.CHAPTER, next = null))
        val chapterFixture = Fixture(this) { _, _ -> chapterDocument }
        chapterFixture.controller.open(LIBRARY_A, LANGUAGE, chapter)
        runCurrent()
        chapterFixture.assertState(ReaderState(chapter.location(LIBRARY_A), LANGUAGE, chapterDocument, loading = false))
        assertEquals(130, chapterFixture.controller.state!!.document!!.section.paragraphs.size)
    }

    @Test
    fun nextAndPreviousChangeWholeRangesAndTitlesIncludingCombinedVersesAndBoundaries() = runTest {
        val fixture = Fixture(this)
        openAndRun(fixture)
        fixture.assertState(ready())
        var changes = fixture.changes.toList()
        fixture.controller.previous()
        runCurrent()
        assertEquals(changes, fixture.changes)

        for (node in listOf(secondSection, thirdSection)) {
            fixture.controller.next()
            fixture.assertState(ReaderState(node.location(LIBRARY_A), LANGUAGE))
            runCurrent()
            fixture.assertState(ready(node.record))
        }
        assertEquals(section.parent, secondSection.parent)
        assertEquals("Verse 2–3 ā", secondSection.displayTitle)
        assertEquals(thirdSection.end, fixture.controller.state!!.document!!.section.paragraphs.last().record)
        changes = fixture.changes.toList()
        fixture.controller.next()
        runCurrent()
        assertEquals(changes, fixture.changes)

        for (node in listOf(secondSection, section)) {
            fixture.controller.previous()
            fixture.assertState(ReaderState(node.location(LIBRARY_A), LANGUAGE))
            runCurrent()
            fixture.assertState(ready(node.record))
        }
        changes = fixture.changes.toList()
        repeat(2) { fixture.controller.previous() }
        runCurrent()
        fixture.assertState(ready())
        assertEquals(changes, fixture.changes)
        assertEquals(
            listOf(section, secondSection, thirdSection, secondSection, section)
                .map { it.location(LIBRARY_A) to LANGUAGE },
            fixture.calls,
        )
    }

    @Test
    fun navigationAndScrollingAreIgnoredWhileLoadingOrFailed() = runTest {
        val response = CompletableDeferred<ReaderDocument>()
        val fixture = Fixture(this) { location, _ ->
            if (location.position == section.record) firstDocument else response.await()
        }
        openAndRun(fixture)
        fixture.controller.next()
        val loading = ReaderState(secondSection.location(LIBRARY_A), LANGUAGE)
        fixture.assertState(loading)
        val loadingChanges = fixture.changes.toList()

        // Exercise both the queued request and the same request suspended in the loader.
        repeat(2) {
            fixture.controller.next()
            fixture.controller.previous()
            fixture.controller.saveScroll(120)
            fixture.controller.retry()
            runCurrent()
            fixture.assertState(loading)
            assertEquals(loadingChanges, fixture.changes)
            assertEquals(2, fixture.calls.size)
        }

        response.completeExceptionally(IOException("Synthetic unit failure"))
        runCurrent()
        val failed = loading.copy(loading = false, failed = true)
        fixture.assertState(failed)
        val failedChanges = fixture.changes.toList()
        repeat(2) {
            fixture.controller.next()
            fixture.controller.previous()
            fixture.controller.saveScroll(240)
        }
        runCurrent()
        fixture.assertState(failed)
        assertEquals(failedChanges, fixture.changes)
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun scrollIsClampedDeduplicatedAndResetWhenChangingUnitsInEitherDirection() = runTest {
        val fixture = Fixture(this)
        openAndRun(fixture)
        var changes = fixture.changes.toList()
        fixture.controller.saveScroll(-1)
        fixture.controller.saveScroll(0)
        assertEquals(changes, fixture.changes)

        fixture.controller.saveScroll(480)
        fixture.assertState(ready().copy(scrollY = 480))
        changes = fixture.changes.toList()
        fixture.controller.saveScroll(480)
        assertEquals(changes, fixture.changes)
        fixture.controller.saveScroll(Int.MIN_VALUE)
        fixture.assertState(ready())
        fixture.controller.saveScroll(Int.MAX_VALUE)
        fixture.assertState(ready().copy(scrollY = Int.MAX_VALUE))
        assertEquals(1, fixture.calls.size)

        fixture.controller.next()
        fixture.assertState(ReaderState(secondSection.location(LIBRARY_A), LANGUAGE))
        runCurrent()
        fixture.assertState(ready(SECOND_START))
        fixture.controller.saveScroll(720)
        fixture.assertState(ready(SECOND_START).copy(scrollY = 720))
        fixture.controller.previous()
        fixture.assertState(ready().copy(document = null, loading = true))
        runCurrent()
        fixture.assertState(ready())
        assertTrue(fixture.calls.all { (location, _) -> location.scrollY == 0 })
    }

    @Test
    fun typedFailuresAndRetryPreserveTheExactUnitAndLanguage() = runTest {
        for (reason in ReadingFailure.entries) {
            var attempts = 0
            val fixture = Fixture(this) { _, _ ->
                if (++attempts == 1) throw ReadingException(reason)
                firstDocument
            }
            openAndRun(fixture)
            fixture.assertState(ReaderState(section.location(LIBRARY_A), LANGUAGE,
                loading = false, failed = true, failure = reason))
            fixture.controller.retry()
            assertTrue(fixture.controller.state!!.loading)
            assertFalse(fixture.controller.state!!.failed)
            assertNull(fixture.controller.state!!.document)
            val retryChanges = fixture.changes.toList()
            fixture.controller.retry()
            assertEquals(retryChanges, fixture.changes)
            runCurrent()
            val loaded = fixture.controller.state!!
            assertFalse(loaded.loading)
            assertFalse(loaded.failed)
            assertEquals(firstDocument, loaded.document)
            assertEquals(section.location(LIBRARY_A), loaded.location)
            assertEquals(LANGUAGE, loaded.languageTag)
            val loadedChanges = fixture.changes.toList()
            fixture.controller.retry()
            runCurrent()
            assertEquals(loadedChanges, fixture.changes)
            assertEquals(2, attempts)
            assertEquals(List(2) { section.location(LIBRARY_A) to LANGUAGE }, fixture.calls)
        }
    }

    @Test
    fun closeCancelsASuspendedRequestWithoutCancellingItsOwner() = runTest {
        val response = CompletableDeferred<ReaderDocument>()
        val requestJob = CompletableDeferred<Job>()
        var returned = false
        var finished = false
        val fixture = Fixture(this) { _, _ ->
            requestJob.complete(currentCoroutineContext().job)
            try {
                response.await().also { returned = true }
            } finally {
                finished = true
            }
        }
        openAndRun(fixture)
        fixture.assertState(ReaderState(section.location(LIBRARY_A), LANGUAGE))
        assertTrue(requestJob.isCompleted)

        fixture.controller.close()
        fixture.assertState(null)
        val changes = fixture.changes.toList()
        runCurrent()
        assertTrue(requestJob.await().isCancelled)
        assertTrue(finished)
        assertTrue(coroutineContext.job.isActive)

        response.complete(firstDocument)
        runCurrent()
        assertFalse(returned)
        fixture.assertState(null)
        assertEquals(changes, fixture.changes)
        assertEquals(listOf(section.location(LIBRARY_A) to LANGUAGE), fixture.calls)
    }

    @Test
    fun lateNonCancellableSuccessOrFailureCannotReopenOrReplaceTheReader() = runTest {
        for ((reopen, fail) in listOf(false to false, true to false, false to true, true to true)) {
            val response = CompletableDeferred<ReaderDocument>()
            val replacement = firstDocument.copy(html = "<p>Generated replacement library.</p>")
            var returned = false
            var requestWasCancelled = false
            val fixture = Fixture(this) { location, _ ->
                if (location.libraryId == LIBRARY_A) {
                    val requestJob = currentCoroutineContext().job
                    try {
                        withContext(NonCancellable) { response.await() }
                    } finally {
                        returned = true
                        requestWasCancelled = requestJob.isCancelled
                    }
                } else replacement
            }
            try {
                openAndRun(fixture)
                val expected = if (reopen) {
                    // Switching directly (without close) must invalidate equal IDs in another library.
                    fixture.controller.open(LIBRARY_B, "hi-Deva", section)
                    runCurrent()
                    ReaderState(section.location(LIBRARY_B), "hi-Deva", replacement, loading = false)
                } else {
                    fixture.controller.close()
                    null
                }
                fixture.assertState(expected)
                val changes = fixture.changes.toList()

                if (fail) response.completeExceptionally(ReadingException(ReadingFailure.SECTION_TOO_LARGE))
                else response.complete(firstDocument)
                runCurrent()
                assertTrue("The non-cooperative loader reached its completion path", returned)
                assertTrue(requestWasCancelled)
                assertTrue(coroutineContext.job.isActive)
                fixture.assertState(expected)
                assertEquals(changes, fixture.changes)
                assertEquals(if (reopen) 2 else 1, fixture.calls.size)
            } finally {
                // A failed assertion must not strand a non-cooperative child of runTest.
                response.complete(firstDocument)
                fixture.controller.close()
                runCurrent()
            }
        }
    }

    @Test
    fun openingAnotherLibraryOrSectionResetsDocumentRangeLanguageAndScrollScope() = runTest {
        val otherSection = section.copy(record = 1600, end = 1699, title = "02. Other generated section")
        for ((library, language, node) in listOf(
            Triple(LIBRARY_B, "bn-Beng", section.copy(title = "03. Other library section")),
            Triple(LIBRARY_A, LANGUAGE, otherSection),
        )) {
            val replacement = document(node, listOf(node.record, node.end))
            var replacing = false
            val fixture = Fixture(this) { location, _ ->
                if (replacing) replacement
                else documents.getValue(location.position)
            }
            openAndRun(fixture)
            fixture.controller.next()
            runCurrent()
            fixture.controller.saveScroll(890)
            fixture.assertState(ready(SECOND_START).copy(scrollY = 890))

            replacing = true
            fixture.controller.open(library, language, node)
            val loading = ReaderState(node.location(library), language)
            fixture.assertState(loading)
            runCurrent()
            fixture.assertState(loading.copy(document = replacement, loading = false))
            assertEquals(
                listOf(
                    section.location(LIBRARY_A) to LANGUAGE,
                    secondSection.location(LIBRARY_A) to LANGUAGE,
                    node.location(library) to language,
                ),
                fixture.calls,
            )
        }
    }

    @Test
    fun outOfRangeDuplicateNonascendingRecordsAndUnrelatedUnitsRejectTheWholeDocument() = runTest {
        for ((label, records) in listOf(
            "before unit" to listOf(SECOND_START - 1, SECOND_START),
            "after inclusive unit end" to listOf(SECOND_START, secondSection.end + 1),
            "duplicate" to listOf(SECOND_START, SECOND_START),
            "nonascending" to listOf(SECOND_START, SECOND_START + 9, SECOND_START + 4),
            "descending" to listOf(SECOND_START + 1, SECOND_START),
        )) {
            assertDocumentRejected(label, document(secondSection, records, previous = section))
        }
        for (node in listOf(
            secondSection.copy(record = 0),
            secondSection.copy(end = SECOND_START - 1),
            secondSection.copy(record = SECOND_START + 1),
            secondSection.copy(end = secondSection.end - 1),
            thirdSection,
        )) {
            assertDocumentRejected("invalid or unrelated unit $node", document(node, emptyList()))
        }
    }

    @Test
    fun invalidOverlappingAndReversedNeighbourRangesRejectTheWholeDocument() = runTest {
        for (next in listOf(
            thirdSection.copy(record = 0),
            thirdSection.copy(record = SECOND_START),
            thirdSection.copy(record = secondSection.end),
            thirdSection.copy(end = THIRD_START - 1),
            section,
        )) {
            assertDocumentRejected("next=$next", secondDocument.copy(section = secondDocument.section.copy(next = next)))
        }
        for (previous in listOf(
            section.copy(record = 0),
            section.copy(end = section.record - 1),
            section.copy(end = SECOND_START),
            secondSection,
            thirdSection,
        )) {
            assertDocumentRejected("previous=$previous", secondDocument.copy(section = secondDocument.section.copy(previous = previous)))
        }
    }

    @Test
    fun emptyUnitsAreSuccessfulAndCanStillNavigateToWholeNeighbours() = runTest {
        val empty = document(section, emptyList())
        val fixture = Fixture(this) { _, _ -> empty }
        openAndRun(fixture)
        fixture.assertState(ReaderState(section.location(LIBRARY_A), LANGUAGE, empty, loading = false))
        val changes = fixture.changes.toList()
        fixture.controller.next()
        fixture.controller.previous()
        fixture.controller.retry()
        runCurrent()
        assertEquals(changes, fixture.changes)
        assertEquals(1, fixture.calls.size)

        val emptyWithPrevious = document(secondSection, emptyList(), previous = section, next = thirdSection)
        val later = Fixture(this) { location, _ ->
            if (location.position == SECOND_START) emptyWithPrevious else documents.getValue(location.position)
        }
        openAndRun(later)
        later.controller.next()
        runCurrent()
        later.assertState(ready(SECOND_START).copy(document = emptyWithPrevious))
        assertEquals(thirdSection, later.controller.state!!.document!!.section.next)
        later.controller.next()
        runCurrent()
        later.assertState(ready(THIRD_START))
        later.controller.previous()
        runCurrent()
        later.assertState(ready(SECOND_START).copy(document = emptyWithPrevious))
        later.controller.previous()
        runCurrent()
        later.assertState(ready())
        assertEquals(listOf(section.record, SECOND_START, THIRD_START, SECOND_START, section.record), later.calls.map { it.first.position })
    }

    @Test
    fun invalidSectionBoundsThrowWithoutReplacingTheCurrentReader() = runTest {
        val fixture = Fixture(this)
        openAndRun(fixture)
        fixture.controller.saveScroll(160)
        val changes = fixture.changes.toList()
        val calls = fixture.calls.toList()
        for (node in listOf(
            section.copy(record = 0),
            section.copy(record = -1),
            section.copy(end = section.record - 1),
            section.copy(record = section.end + 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                fixture.controller.open(LIBRARY_B, "hi", node)
            }
        }
        for (library in listOf("", " \t ")) {
            assertThrows(IllegalArgumentException::class.java) {
                fixture.controller.open(library, LANGUAGE, section)
            }
        }
        runCurrent()
        fixture.assertState(ready().copy(scrollY = 160))
        assertEquals(changes, fixture.changes)
        assertEquals(calls, fixture.calls)
    }

    private fun TestScope.openAndRun(fixture: Fixture) {
        fixture.controller.open(LIBRARY_A, LANGUAGE, section)
        runCurrent()
    }

    private fun TestScope.assertDocumentRejected(label: String, invalid: ReaderDocument) {
        val fixture = Fixture(this) { location, _ ->
            if (location.position == section.record) firstDocument else invalid
        }
        openAndRun(fixture)
        fixture.controller.next()
        runCurrent()
        fixture.assertState(ready(SECOND_START).copy(document = null, failed = true))
        assertTrue("Malformed $label must never be published, even partially", fixture.changes.none {
            it != null && it.location.position == SECOND_START && it.document != null
        })
        assertNull(fixture.controller.state!!.document)
        val changes = fixture.changes.toList()
        fixture.controller.next()
        fixture.controller.previous()
        runCurrent()
        assertEquals(label, changes, fixture.changes)
        assertEquals(label, 2, fixture.calls.size)
    }

    private class Fixture(
        scope: CoroutineScope,
        load: suspend (ReadingLocation, String) -> ReaderDocument = { location, _ -> documents.getValue(location.position) },
    ) {
        val calls = mutableListOf<Pair<ReadingLocation, String>>()
        val changes = mutableListOf<ReaderState?>()
        val controller = ReaderController(scope, { location, language ->
            calls += location to language
            load(location, language)
        }, { changes += it })

        fun assertState(expected: ReaderState?) {
            assertEquals(expected, controller.state)
            assertEquals(expected, changes.lastOrNull())
        }
    }

    companion object {
        private const val LIBRARY_A = "generated-library-a"
        private const val LIBRARY_B = "generated-library-b"
        private const val LANGUAGE = "sa-Latn-IN"
        private const val SECOND_START = 1207
        private const val THIRD_START = 1331
        private val section = ContentNode(100, 10, " 01. Synthetic verse ā ", 999, 3, 42, "TEXT")
        private val secondSection = ContentNode(SECOND_START, 10, "02. Verse 2–3 ā", 1299, 0, 42, "TEXT")
        private val thirdSection = ContentNode(THIRD_START, 1300, "03. Next chapter verse", 1399, 0, 42, "TEXT")
        private val firstDocument = document(section,
            List(129) { 100 + it * 5 + it % 3 } + section.end, next = secondSection)
        private val secondDocument = document(secondSection, listOf(SECOND_START, 1245, 1299), previous = section, next = thirdSection)
        private val lastDocument = document(thirdSection, listOf(THIRD_START, 1367, 1399), previous = secondSection)
        private val documents = mapOf(100 to firstDocument, SECOND_START to secondDocument, THIRD_START to lastDocument)

        private fun document(node: ContentNode, records: List<Int>, previous: ContentNode? = null, next: ContentNode? = null) = ReaderDocument(
            ReadingSection(node, ReadingUnitKind.VERSE, "Generated parent",
                records.map { Paragraph(it, "Generated test record $it only.", "Body", "Text") }, previous, next),
            "<p>Generated records ${records.joinToString()}.</p>",
        )

        private fun ready(position: Int = section.record) = ReaderState(
            documents.getValue(position).section.node.location(LIBRARY_A), LANGUAGE,
            documents.getValue(position), loading = false,
        )
    }
}