package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.ContentNode
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ScopedSearchHit
import org.vedareader.data.SearchBook
import org.vedareader.data.SearchIssue
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchPage
import org.vedareader.data.SearchRequest
import org.vedareader.data.SearchSuggestion
import org.vedareader.data.SearchTarget
import org.vedareader.data.SearchText

/** Synthetic data and virtual time only; no database, Android runtime, or reader content. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    @Test
    fun startsEmptyAndDoesNotSearchWithoutALibrary() = runTest {
        withFixture {
            assertEquals(SearchUiState(), controller.state)
            assertTrue(changes.isEmpty())
            controller.submit()
            controller.selectSuggestion(SearchSuggestion("example"))
            controller.loadMore()
            controller.retryCatalog()
            controller.clearHistory()
            controller.cancel()
            runCurrent()
            assertEquals(SearchUiState(), controller.state)
            assertTrue(changes.isEmpty())
            assertTrue(searchCalls.isEmpty())
            assertTrue(catalogCalls.isEmpty())
        }
    }

    @Test
    fun sameLibraryCachesCatalogStateAndPendingWorkButUpdatesTitle() = runTest {
        withFixture {
            val gate = pending(SearchPage(listOf(hit(10)), 20))
            searchLoader = { _, _, _ -> gate.await() }
            ready()
            controller.setBooks(setOf(BOOK_A))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setQuery("example words")
            controller.submit()
            runCurrent()
            val before = controller.state
            controller.setLibrary(LIBRARY_A, "Renamed")
            assertEquals(before.copy(libraryTitle = "Renamed"), controller.state)
            assertFalse(gate.job!!.isCancelled)
            gate.complete()
            runCurrent()
            val loaded = controller.state
            controller.setLibrary(LIBRARY_A, "Renamed")
            controller.retryCatalog()
            runCurrent()
            assertEquals(loaded, controller.state)
            assertEquals(listOf(LIBRARY_A), catalogCalls)
            assertEquals(listOf(LIBRARY_A), historyLoads)
        }
    }

    @Test
    fun suggestionsDebounceAfterTwoCharactersAndTypingNeverSubmits() = runTest {
        withFixture {
            suggestionLoader = { _, request -> listOf(SearchSuggestion(request.query + "ample")) }
            ready()
            controller.setQuery("e")
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(suggestionCalls.isEmpty())
            controller.setQuery("ex")
            runCurrent()
            advanceTimeBy(249)
            runCurrent()
            assertTrue(suggestionCalls.isEmpty())
            controller.setQuery("exa")
            runCurrent()
            advanceTimeBy(249)
            runCurrent()
            assertTrue(suggestionCalls.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(LIBRARY_A to SearchRequest("exa")), suggestionCalls)
            assertEquals(listOf(SearchSuggestion("exaample")), controller.state.suggestions)
            assertFalse(controller.state.suggesting)
            assertNull(controller.state.submittedQuery)
            assertTrue(searchCalls.isEmpty())
            controller.setQuery("e")
            assertTrue(controller.state.suggestions.isEmpty())
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, suggestionCalls.size)
        }
    }

    @Test
    fun invalidInputUsesExactSearchTextRulesAndDoesNotReachEitherLoader() = runTest {
        withFixture {
            ready()
            for (query in listOf("", "   ", "---", "x".repeat(161), "x ".repeat(9),
                " ".repeat(160) + "x")) {
                controller.setQuery(query)
                advanceTimeBy(250)
                runCurrent()
                controller.submit()
                assertEquals(SearchIssue.INVALID_QUERY, controller.state.error)
                assertNull(controller.state.submittedQuery)
                assertFalse(controller.state.searching)
            }
            assertTrue(searchCalls.isEmpty())
            assertTrue(suggestionCalls.isEmpty())
            for (query in listOf("x".repeat(SearchText.MAX_QUERY_CHARACTERS), "x ".repeat(7) + "x")) {
                controller.setQuery(query)
                assertNull(controller.state.error)
                controller.submit()
                runCurrent()
                assertNull(controller.state.error)
                assertEquals(query, searchCalls.last().request.query)
                assertEquals(query, controller.state.submittedQuery)
            }
        }
    }

    @Test
    fun scopeSelectionRejectsUnknownIdsAndSnapshotsExactRequests() = runTest {
        withFixture {
            val catalogGate = pending(books)
            catalogLoader = { catalogGate.await() }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setBooks(setOf(BOOK_A))
            assertTrue(controller.state.selectedBooks.isEmpty())
            runCurrent()
            catalogGate.complete()
            runCurrent()
            val selection = linkedSetOf(BOOK_A, BOOK_B)
            controller.setBooks(selection)
            selection.clear()
            selection.add(999)
            assertEquals(setOf(BOOK_A, BOOK_B), controller.state.selectedBooks)
            val before = controller.state
            controller.setBooks(setOf(999))
            controller.setBooks(setOf(BOOK_A, 999))
            assertEquals(before, controller.state)
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setQuery("  Example words ")
            advanceTimeBy(250)
            runCurrent()
            val expected = SearchRequest("  Example words ", setOf(BOOK_A, BOOK_B), SearchMode.EXACT_PHRASE)
            assertEquals(LIBRARY_A to expected, suggestionCalls.single())
            controller.submit()
            runCurrent()
            assertEquals(Call(LIBRARY_A, expected, 0), searchCalls.single())
            controller.setBooks(emptySet())
            assertTrue(controller.state.selectedBooks.isEmpty())
            assertNull(controller.state.submittedQuery)
            controller.submit()
            runCurrent()
            assertEquals(expected.copy(bookRecords = emptySet()), searchCalls.last().request)
        }
    }

    @Test
    fun selectingSuggestionAtomicallySubmitsAndRejectsLateSuggestions() = runTest {
        withFixture {
            val suggestions = pending(listOf(SearchSuggestion("obsolete")))
            suggestionLoader = { _, _ -> suggestions.await() }
            ready()
            controller.setBooks(setOf(BOOK_B))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setQuery("ex")
            advanceTimeBy(250)
            runCurrent()
            assertTrue(controller.state.suggesting)
            val count = changes.size
            controller.selectSuggestion(SearchSuggestion("example words", "Visible label", true))
            assertEquals(1, changes.size - count)
            assertEquals("example words", controller.state.query)
            assertEquals("example words", controller.state.submittedQuery)
            assertTrue(controller.state.searching)
            assertFalse(controller.state.suggesting)
            assertTrue(suggestions.job!!.isCancelled)
            runCurrent()
            val completed = controller.state
            suggestions.complete()
            advanceTimeBy(500)
            runCurrent()
            assertTrue(suggestions.returned)
            assertEquals(completed, controller.state)
            assertEquals(1, suggestionCalls.size)
            assertEquals(Call(LIBRARY_A,
                SearchRequest("example words", setOf(BOOK_B), SearchMode.EXACT_PHRASE), 0), searchCalls.single())
        }
    }

    @Test
    fun textScopeModeAndTargetEditsImmediatelyClearCompletedAndPendingResults() = runTest {
        for (kind in 0..3) withFixture {
            ready()
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val gate = pending(SearchPage(listOf(hit(30)), 40))
            searchLoader = { _, _, _ -> gate.await() }
            controller.loadMore()
            runCurrent()
            when (kind) {
                0 -> controller.setQuery("changed")
                1 -> controller.setBooks(setOf(BOOK_A))
                2 -> controller.setMode(SearchMode.EXACT_PHRASE)
                else -> controller.setTarget(SearchTarget.VERSE_WORDS)
            }
            assertTrue(gate.job!!.isCancelled)
            assertTrue(controller.state.results.isEmpty())
            assertNull(controller.state.submittedQuery)
            assertNull(controller.state.nextAfter)
            assertNull(controller.state.error)
            assertFalse(controller.state.loadingMore)
            val edited = controller.state
            controller.loadMore()
            gate.complete()
            runCurrent()
            assertTrue(gate.returned)
            assertEquals(edited, controller.state)
            assertEquals(2, searchCalls.size)
        }
    }

    @Test
    fun staleInitialSearchSuccessOrFailureCannotPublishAfterTextModeScopeOrTargetChanges() = runTest {
        for (kind in 0..3) for (fails in listOf(false, true)) withFixture {
            ready()
            val gate = pending(SearchPage(listOf(hit(10))))
            searchLoader = { _, _, _ ->
                gate.await().also { if (fails) error("Synthetic late failure") }
            }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            when (kind) {
                0 -> controller.setQuery("changed")
                1 -> controller.setBooks(setOf(BOOK_B))
                2 -> controller.setMode(SearchMode.EXACT_PHRASE)
                else -> controller.setTarget(SearchTarget.VERSE_WORDS)
            }
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(20))) }
            controller.submit()
            runCurrent()
            val current = controller.state
            val publications = changes.toList()
            gate.complete()
            runCurrent()
            assertTrue(gate.returned)
            assertEquals(current, controller.state)
            assertEquals(publications, changes)
            assertEquals(listOf(20), controller.state.results.map { it.location.position })
            assertEquals(1, historySaves.size)
        }
    }

    @Test
    fun textModeScopeAndTargetChangesCancelAndFenceLateSuggestionSuccessAndFailure() = runTest {
        for (kind in 0..3) for (fails in listOf(false, true)) withFixture {
            ready()
            val gate = pending(listOf(SearchSuggestion("obsolete")))
            suggestionLoader = { _, _ ->
                gate.await().also { if (fails) error("Synthetic late failure") }
            }
            controller.setQuery("ex")
            advanceTimeBy(250)
            runCurrent()
            when (kind) {
                0 -> controller.setQuery("changed")
                1 -> controller.setBooks(setOf(BOOK_A))
                2 -> controller.setMode(SearchMode.EXACT_PHRASE)
                else -> controller.setTarget(SearchTarget.VERSE_WORDS)
            }
            assertTrue(gate.job!!.isCancelled)
            assertFalse(controller.state.suggesting)
            val edited = controller.state
            gate.complete()
            runCurrent()
            assertTrue(gate.returned)
            assertEquals(edited, controller.state)
            suggestionLoader = { _, _ -> listOf(SearchSuggestion("current")) }
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf(SearchSuggestion("current")), controller.state.suggestions)
        }
    }

    @Test
    fun changingLibrariesRejectsOldSearchEvenWithIdenticalQueryAndRecordIds() = runTest {
        withFixture {
            ready()
            val gate = pending(SearchPage(listOf(hit(10)), 20))
            searchLoader = { _, _, _ -> gate.await() }
            controller.setBooks(setOf(BOOK_A))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            storedHistory[LIBRARY_B] = listOf("other")
            controller.setLibrary(LIBRARY_B, "B")
            assertEquals(SearchUiState(libraryId = LIBRARY_B, libraryTitle = "B",
                catalogLoading = true, recent = listOf("other")), controller.state)
            runCurrent()
            searchLoader = { library, _, _ -> SearchPage(listOf(hit(10, library))) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val newer = controller.state
            val publications = changes.toList()
            gate.complete()
            runCurrent()
            assertTrue(gate.returned)
            assertEquals(newer, controller.state)
            assertEquals(publications, changes)
            assertEquals(listOf(LIBRARY_B), historySaves.map { it.first })
            controller.setLibrary(null, "")
            assertEquals(SearchUiState(), controller.state)
        }
    }

    @Test
    fun targetSurvivesSuggestionSubmissionRefreshCancelAndPaginationWithoutDefaultingToAllText() = runTest {
        for (target in SearchTarget.entries) withFixture {
            ready()
            controller.setBooks(setOf(BOOK_B))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setTarget(target)
            val query = if (target == SearchTarget.VERSE_REFERENCE) "CCMadhya1.1" else "example"
            val prefix = if (target == SearchTarget.VERSE_REFERENCE) "CCMadhya1." else "ex"
            controller.setQuery(prefix)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(SearchRequest(prefix, setOf(BOOK_B), SearchMode.EXACT_PHRASE, target), suggestionCalls.single().second)
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.selectSuggestion(SearchSuggestion(query, "Original source label", target == SearchTarget.VERSE_REFERENCE))
            runCurrent()
            val request = SearchRequest(query, setOf(BOOK_B), SearchMode.EXACT_PHRASE, target)
            assertEquals(request, searchCalls.single().request)
            val saved = controller.state
            val gate = pending(SearchPage(listOf(hit(30))))
            searchLoader = { _, _, _ -> gate.await() }
            controller.submit()
            runCurrent()
            controller.cancel()
            gate.complete()
            runCurrent()
            assertEquals(saved, controller.state)
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(30))) }
            controller.loadMore()
            runCurrent()
            assertEquals(20, searchCalls.last().after)
            assertTrue(searchCalls.all { it.request == request })
            assertEquals(listOf(10, 30), controller.state.results.map { it.location.position })
        }
    }

    @Test
    fun referencePrefixesSuggestButSubmitIsInvalidUntilCompleteAndQuotedOrPlainWordsNeverReachLoader() = runTest {
        withFixture {
            ready()
            controller.setTarget(SearchTarget.VERSE_REFERENCE)
            for (prefix in listOf("BG2.", "SB1.2.", "CCMadhya1.", "Adi1.", "Antya1.")) {
                controller.setQuery(prefix)
                advanceTimeBy(250)
                runCurrent()
                assertEquals(SearchRequest(prefix, target = SearchTarget.VERSE_REFERENCE), suggestionCalls.last().second)
                controller.submit()
                assertEquals(SearchIssue.INVALID_QUERY, controller.state.error)
            }
            val count = suggestionCalls.size
            for (invalid in listOf("Krishna", "\"BG 2.8\"", "prose CC Madhya 1.1")) {
                controller.setQuery(invalid)
                advanceTimeBy(250)
                runCurrent()
                controller.submit()
                assertEquals(SearchIssue.INVALID_QUERY, controller.state.error)
            }
            assertEquals(count, suggestionCalls.size)
            assertTrue(searchCalls.isEmpty())
            for (complete in listOf("BG2.8", "SB1.2.3", "CCMadhya1.1", "Madhya1.1", "Adi1.1", "Antya1.1")) {
                controller.setQuery(complete)
                controller.submit()
                runCurrent()
                assertNull(controller.state.error)
                assertEquals(SearchTarget.VERSE_REFERENCE, searchCalls.last().request.target)
            }
        }
    }

    @Test
    fun editingTargetRestartsDebounceAndSameTargetDoesNotCancelCurrentWork() = runTest {
        withFixture {
            ready()
            controller.setQuery("BG 2.")
            advanceTimeBy(249)
            runCurrent()
            controller.setTarget(SearchTarget.VERSE_REFERENCE)
            advanceTimeBy(249)
            runCurrent()
            assertTrue(suggestionCalls.isEmpty())
            controller.setTarget(SearchTarget.VERSE_REFERENCE)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(LIBRARY_A to SearchRequest("BG 2.", target = SearchTarget.VERSE_REFERENCE)), suggestionCalls)
            val gate = pending(SearchPage(listOf(hit(10))))
            searchLoader = { _, _, _ -> gate.await() }
            controller.selectSuggestion(SearchSuggestion("BG 2.8"))
            runCurrent()
            controller.setTarget(SearchTarget.VERSE_REFERENCE)
            assertFalse(gate.job!!.isCancelled)
            gate.complete()
            runCurrent()
            assertEquals(listOf(hit(10)), controller.state.results)
        }
    }

    @Test
    fun changingLibrariesFencesCatalogAndSuggestionCompletionsSeparately() = runTest {
        withFixture {
            val catalog = pending(books)
            val suggestions = pending(listOf(SearchSuggestion("obsolete")))
            catalogLoader = { library -> if (library == LIBRARY_A) catalog.await() else emptyList() }
            suggestionLoader = { _, _ -> suggestions.await() }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("ex")
            advanceTimeBy(250)
            runCurrent()
            controller.setLibrary(LIBRARY_B, "B")
            runCurrent()
            val newer = controller.state
            assertTrue(catalog.job!!.isCancelled)
            assertTrue(suggestions.job!!.isCancelled)
            catalog.complete()
            suggestions.complete()
            runCurrent()
            assertTrue(catalog.returned)
            assertTrue(suggestions.returned)
            assertEquals(newer, controller.state)
            assertTrue(controller.state.books.isEmpty())
            assertFalse(controller.state.catalogError)
        }
    }

    @Test
    fun catalogFailureRetriesOnlyOnRequestAndCachesEmptySuccess() = runTest {
        withFixture {
            catalogLoader = { error("Synthetic catalog failure") }
            controller.setLibrary(LIBRARY_A, "A")
            runCurrent()
            assertTrue(controller.state.catalogError)
            assertFalse(controller.state.catalogLoading)
            controller.setBooks(setOf(BOOK_A))
            assertTrue(controller.state.selectedBooks.isEmpty())
            controller.setLibrary(LIBRARY_A, "A")
            assertEquals(1, catalogCalls.size)
            val gate = pending(emptyList<SearchBook>())
            catalogLoader = { gate.await() }
            controller.retryCatalog()
            assertTrue(controller.state.catalogLoading)
            assertFalse(controller.state.catalogError)
            controller.retryCatalog()
            runCurrent()
            assertEquals(2, catalogCalls.size)
            gate.complete()
            runCurrent()
            controller.retryCatalog()
            controller.pause()
            controller.resume()
            runCurrent()
            assertEquals(2, catalogCalls.size)
            assertFalse(controller.state.catalogError)
            assertFalse(controller.state.catalogLoading)
        }
    }

    @Test
    fun malformedCatalogIsNeverPublishedAsSelectableBooks() = runTest {
        for (invalid in listOf(listOf(books[0], books[0]),
            listOf(books[0].copy(node = books[0].node.copy(record = 0))))) withFixture {
            catalogLoader = { invalid }
            controller.setLibrary(LIBRARY_A, "A")
            runCurrent()
            assertTrue(controller.state.catalogError)
            assertTrue(controller.state.books.isEmpty())
            catalogLoader = { books }
            controller.retryCatalog()
            runCurrent()
            assertEquals(books, controller.state.books)
        }
    }

    @Test
    fun searchTimeoutOtherFailureAndCancellationHaveDistinctStates() = runTest {
        withFixture {
            ready()
            controller.setQuery("example")
            searchLoader = { _, _, _ -> withTimeout(10) { delay(20); SearchPage() } }
            controller.submit()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(SearchIssue.TIMEOUT, controller.state.error)
            assertEquals("example", controller.state.submittedQuery)
            assertFalse(controller.state.searching)
            searchLoader = { _, _, _ -> throw IllegalArgumentException("Synthetic loader failure") }
            controller.submit()
            runCurrent()
            assertEquals(SearchIssue.UNAVAILABLE, controller.state.error)
            assertEquals("example", controller.state.submittedQuery)
            searchLoader = { _, _, _ -> throw CancellationException("Synthetic cancellation") }
            controller.submit()
            runCurrent()
            assertNull(controller.state.error)
            assertNull(controller.state.submittedQuery)
            assertFalse(controller.state.searching)
            assertTrue(historySaves.isEmpty())
            assertTrue(coroutineContext.job.isActive)
        }
    }

    @Test
    fun suggestionFailuresAreSeparateFromSearchErrorsAndCancellationIsNotFailure() = runTest {
        withFixture {
            ready()
            suggestionLoader = { _, _ -> withTimeout(10) { delay(20); emptyList() } }
            controller.setQuery("ex")
            advanceTimeBy(260)
            runCurrent()
            assertTrue(controller.state.suggestionsUnavailable)
            assertFalse(controller.state.suggesting)
            assertNull(controller.state.error)
            suggestionLoader = { _, _ -> error("Synthetic suggestion failure") }
            controller.setMode(SearchMode.EXACT_PHRASE)
            assertFalse(controller.state.suggestionsUnavailable)
            advanceTimeBy(250)
            runCurrent()
            assertTrue(controller.state.suggestionsUnavailable)
            suggestionLoader = { _, _ -> throw CancellationException("Synthetic cancellation") }
            controller.setQuery("example")
            advanceTimeBy(250)
            runCurrent()
            assertFalse(controller.state.suggestionsUnavailable)
            assertFalse(controller.state.suggesting)
            assertNull(controller.state.error)
        }
    }

    @Test
    fun cancelInitialSearchNeverClaimsNoMatchesOrSavesLateSuccess() = runTest {
        withFixture {
            ready()
            val gate = pending(SearchPage())
            searchLoader = { _, _, _ -> gate.await() }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            controller.cancel()
            assertTrue(gate.job!!.isCancelled)
            assertNull(controller.state.submittedQuery)
            assertNull(controller.state.error)
            assertFalse(controller.state.searching)
            assertEquals("example", controller.state.query)
            val stopped = controller.state
            val publications = changes.toList()
            gate.complete()
            runCurrent()
            assertTrue(gate.returned)
            assertEquals(stopped, controller.state)
            assertEquals(publications, changes)
            assertTrue(historySaves.isEmpty())
        }
    }

    @Test
    fun cancelPreservesHeldCatalogAndSelectableBooksButNeverPublishesLateQuery() = runTest {
        for (fails in listOf(false, true)) withFixture {
            val catalog = pending(books)
            val query = pending(SearchPage(listOf(hit(10)), 20))
            catalogLoader = { catalog.await() }
            searchLoader = { _, _, _ ->
                query.await().also { if (fails) error("Synthetic late failure") }
            }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            assertTrue(controller.state.catalogLoading)
            assertTrue(controller.state.searching)
            controller.cancel()
            assertTrue(catalog.job!!.isActive)
            assertTrue(query.job!!.isCancelled)
            assertTrue(coroutineContext.job.isActive)
            assertTrue(controller.state.catalogLoading)
            assertFalse(controller.state.catalogError)
            assertFalse(controller.state.searching)
            assertNull(controller.state.submittedQuery)
            assertNull(controller.state.error)
            assertEquals("example", controller.state.query)
            val stopped = controller.state
            catalog.complete()
            runCurrent()
            assertTrue(catalog.returned)
            assertEquals(stopped.copy(books = books, catalogLoading = false), controller.state)
            assertEquals(controller.state, changes.last())
            val loaded = controller.state
            val publications = changes.toList()
            query.complete()
            runCurrent()
            assertTrue(query.returned)
            assertEquals(loaded, controller.state)
            assertEquals(publications, changes)
            assertTrue(historySaves.isEmpty())
            controller.setBooks(setOf(BOOK_A))
            assertEquals(setOf(BOOK_A), controller.state.selectedBooks)
            controller.retryCatalog()
            runCurrent()
            assertEquals(listOf(LIBRARY_A), catalogCalls)
        }
    }

    @Test
    fun cancelStopsSuggestionsWithoutStoppingHeldCatalog() = runTest {
        withFixture {
            val catalog = pending(books)
            val suggestions = pending(listOf(SearchSuggestion("obsolete")))
            catalogLoader = { catalog.await() }
            suggestionLoader = { _, _ -> suggestions.await() }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("ex")
            advanceTimeBy(250)
            runCurrent()
            assertTrue(controller.state.suggesting)
            controller.cancel()
            assertTrue(catalog.job!!.isActive)
            assertTrue(suggestions.job!!.isCancelled)
            assertTrue(controller.state.catalogLoading)
            assertFalse(controller.state.suggesting)
            assertFalse(controller.state.suggestionsUnavailable)
            assertTrue(controller.state.suggestions.isEmpty())
            val stopped = controller.state
            val publications = changes.toList()
            suggestions.complete()
            advanceTimeBy(500)
            runCurrent()
            assertTrue(suggestions.returned)
            assertEquals(stopped, controller.state)
            assertEquals(publications, changes)
            assertEquals(1, suggestionCalls.size)
            catalog.complete()
            runCurrent()
            assertEquals(stopped.copy(books = books, catalogLoading = false), controller.state)
            assertTrue(searchCalls.isEmpty())
        }
    }

    @Test
    fun cancelRetainsCompletedResultsAndCursorDuringRefreshOrLoadMore() = runTest {
        for (more in listOf(false, true)) withFixture {
            ready()
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val completed = controller.state
            val gate = pending(SearchPage(listOf(hit(30)), 40))
            searchLoader = { _, _, _ -> gate.await() }
            if (more) controller.loadMore() else controller.submit()
            runCurrent()
            controller.cancel()
            assertEquals(completed, controller.state)
            gate.complete()
            runCurrent()
            assertEquals(completed, controller.state)
            assertEquals(1, historySaves.size)
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(30))) }
            controller.loadMore()
            runCurrent()
            assertEquals(20, searchCalls.last().after)
            assertEquals(listOf(10, 30), controller.state.results.map { it.location.position })
        }
    }

    @Test
    fun paginationHandlesEmptyPartialPagesRetriesAndDeduplicatesByMatchedRecordNotHeading() = runTest {
        withFixture {
            ready()
            val first = hit(10)
            val sameHeading = hit(11).copy(location = first.location.copy(position = 11))
            val pages = mutableListOf(
                SearchPage(listOf(first, first.copy(snippet = "duplicate"), sameHeading), 20),
                SearchPage(nextAfter = 30),
                SearchPage(listOf(first.copy(snippet = "later duplicate"), hit(40), hit(40)), 50),
                SearchPage(),
            )
            var fail = false
            searchLoader = { _, _, _ ->
                if (fail) error("Synthetic page failure") else pages.removeAt(0)
            }
            controller.setQuery("example")
            controller.submit()
            controller.loadMore() // Queued initial submission is already busy.
            runCurrent()
            assertEquals(listOf(first, sameHeading), controller.state.results)
            assertEquals(1, searchCalls.size)
            controller.loadMore()
            controller.loadMore() // Queued pagination is already busy too.
            runCurrent()
            assertEquals(30, controller.state.nextAfter)
            assertEquals(listOf(first, sameHeading), controller.state.results)
            assertEquals(2, searchCalls.size) // Empty partial page does not auto-loop.
            fail = true
            controller.loadMore()
            runCurrent()
            assertEquals(SearchIssue.UNAVAILABLE, controller.state.error)
            assertEquals(30, controller.state.nextAfter)
            assertEquals(listOf(first, sameHeading), controller.state.results)
            fail = false
            controller.loadMore()
            assertNull(controller.state.error)
            runCurrent()
            assertEquals(listOf(first, sameHeading, hit(40)), controller.state.results)
            assertEquals(50, controller.state.nextAfter)
            controller.loadMore()
            runCurrent()
            assertNull(controller.state.nextAfter)
            assertEquals(3, controller.state.results.size)
            controller.loadMore()
            runCurrent()
            assertEquals(listOf(0, 20, 30, 30, 50), searchCalls.map { it.after })
            assertTrue(searchCalls.all { it.request == SearchRequest("example") && it.library == LIBRARY_A })
            assertEquals(1, historySaves.size)
        }
    }

    @Test
    fun initiallyEmptyPartialPageIsSuccessfulAndStillAllowsPagination() = runTest {
        withFixture {
            ready()
            searchLoader = { _, _, after -> if (after == 0) SearchPage(nextAfter = 20) else SearchPage(listOf(hit(30))) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            assertTrue(controller.state.results.isEmpty())
            assertEquals("example", controller.state.submittedQuery)
            assertEquals(20, controller.state.nextAfter)
            assertEquals(listOf("example"), controller.state.recent)
            controller.cancel()
            assertEquals("example", controller.state.submittedQuery)
            controller.loadMore()
            runCurrent()
            assertEquals(listOf(hit(30)), controller.state.results)
        }
    }

    @Test
    fun invalidCursorsAndCrossLibraryPagesFailAtomicallyAndKeepRetryCursor() = runTest {
        for (bad in listOf(
            SearchPage(nextAfter = -1), SearchPage(nextAfter = 0), SearchPage(nextAfter = 19),
            SearchPage(listOf(hit(30)), 20), SearchPage(listOf(hit(40)), 30),
            SearchPage(listOf(hit(30, LIBRARY_B)), 40),
        )) withFixture {
            ready()
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            searchLoader = { _, _, _ -> bad }
            controller.loadMore()
            runCurrent()
            assertEquals(SearchIssue.UNAVAILABLE, controller.state.error)
            assertEquals(listOf(hit(10)), controller.state.results)
            assertEquals(20, controller.state.nextAfter)
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(30))) }
            controller.loadMore()
            runCurrent()
            assertEquals(listOf(0, 20, 20), searchCalls.map { it.after })
            assertEquals(listOf(hit(10), hit(30)), controller.state.results)
        }
    }

    @Test
    fun paginationTimeoutAndCancellationPreserveAlreadyCompletedPages() = runTest {
        withFixture {
            ready()
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val completed = controller.state
            searchLoader = { _, _, _ -> withTimeout(10) { delay(20); SearchPage() } }
            controller.loadMore()
            advanceTimeBy(10)
            runCurrent()
            assertEquals(completed.copy(error = SearchIssue.TIMEOUT), controller.state)
            searchLoader = { _, _, _ -> throw CancellationException("Synthetic cancellation") }
            controller.loadMore()
            runCurrent()
            assertEquals(completed, controller.state)
        }
    }

    @Test
    fun userRequestedPagesHaveNoArbitraryVisibleResultCap() = runTest {
        withFixture {
            ready()
            searchLoader = { _, _, after ->
                val end = after + 100
                SearchPage((after + 1..end).map { hit(it) }, if (end < 700) end else null)
            }
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            repeat(6) { controller.loadMore(); runCurrent() }
            assertEquals(700, controller.state.results.size)
            assertNull(controller.state.nextAfter)
            assertNull(controller.state.error)
            assertEquals(7, searchCalls.size)
        }
    }

    @Test
    fun pauseRetainsCompletedStateAndResumeDoesNotRepeatSearchOrCatalog() = runTest {
        withFixture {
            ready()
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10)), 20) }
            controller.setBooks(setOf(BOOK_B))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val completed = controller.state
            val gate = pending(SearchPage(listOf(hit(30))))
            searchLoader = { _, _, _ -> gate.await() }
            controller.loadMore()
            runCurrent()
            controller.pause()
            controller.pause()
            assertEquals(completed, controller.state)
            controller.submit()
            controller.loadMore()
            controller.selectSuggestion(SearchSuggestion("other"))
            controller.retryCatalog()
            controller.resume()
            controller.resume()
            gate.complete()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(completed, controller.state)
            assertEquals(2, searchCalls.size)
            assertEquals(1, catalogCalls.size)
            assertTrue(suggestionCalls.isEmpty())
        }
    }

    @Test
    fun pauseCancelsCatalogAndSuggestionsThenResumeRetriesOnlyNeededWork() = runTest {
        withFixture {
            val catalog = pending(books)
            val suggestions = pending(listOf(SearchSuggestion("obsolete")))
            catalogLoader = { catalog.await() }
            suggestionLoader = { _, _ -> suggestions.await() }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("ex")
            advanceTimeBy(250)
            runCurrent()
            controller.pause()
            assertFalse(controller.state.catalogLoading)
            assertFalse(controller.state.suggesting)
            assertFalse(controller.state.catalogError)
            assertTrue(catalog.job!!.isCancelled)
            assertTrue(suggestions.job!!.isCancelled)
            val stopped = controller.state
            val stoppedPublications = changes.toList()
            controller.retryCatalog()
            runCurrent()
            assertEquals(stopped, controller.state)
            assertEquals(stoppedPublications, changes)
            controller.setQuery("changed")
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, catalogCalls.size)
            assertEquals(1, suggestionCalls.size)
            val resumedCatalog = pending(books)
            catalogLoader = { resumedCatalog.await() }
            suggestionLoader = { _, _ -> listOf(SearchSuggestion("current")) }
            controller.resume()
            runCurrent()
            assertTrue(controller.state.catalogLoading)
            assertFalse(controller.state.catalogError)
            assertTrue(resumedCatalog.job!!.isActive)
            val resuming = controller.state
            val resumingPublications = changes.toList()
            catalog.complete()
            suggestions.complete()
            runCurrent()
            assertTrue(catalog.returned)
            assertTrue(suggestions.returned)
            assertEquals(resuming, controller.state)
            assertEquals(resumingPublications, changes)
            resumedCatalog.complete()
            runCurrent()
            assertEquals(books, controller.state.books)
            assertFalse(controller.state.catalogLoading)
            advanceTimeBy(249)
            runCurrent()
            assertEquals(1, suggestionCalls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, catalogCalls.size)
            assertEquals(SearchRequest("changed"), suggestionCalls.last().second)
            assertEquals(listOf(SearchSuggestion("current")), controller.state.suggestions)
            assertTrue(searchCalls.isEmpty())
        }
    }

    @Test
    fun cancelBeforeQueuedJobsStartPreservesCatalogButDoesNotInvokeQueryLoaders() = runTest {
        withFixture {
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("example")
            controller.submit()
            controller.cancel()
            assertTrue(controller.state.catalogLoading)
            runCurrent()
            assertEquals(listOf(LIBRARY_A), catalogCalls)
            assertTrue(searchCalls.isEmpty())
            assertTrue(suggestionCalls.isEmpty())
            assertNull(controller.state.submittedQuery)
            assertEquals(books, controller.state.books)
            assertFalse(controller.state.catalogLoading)
            controller.retryCatalog()
            runCurrent()
            assertEquals(listOf(LIBRARY_A), catalogCalls)
        }
    }

    @Test
    fun pauseBeforeQueuedJobsStartCancelsCatalogAndQueryUntilResume() = runTest {
        withFixture {
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("example")
            controller.submit()
            controller.pause()
            runCurrent()
            assertTrue(catalogCalls.isEmpty())
            assertTrue(searchCalls.isEmpty())
            assertTrue(suggestionCalls.isEmpty())
            assertFalse(controller.state.catalogLoading)
            assertNull(controller.state.submittedQuery)
            controller.resume()
            runCurrent()
            assertEquals(listOf(LIBRARY_A), catalogCalls)
            assertEquals(books, controller.state.books)
            assertFalse(controller.state.catalogLoading)
            assertTrue(searchCalls.isEmpty())
        }
    }

    @Test
    fun historyIsTrimmedDeduplicatedBoundedAndPersistedOnlyAfterSuccessfulSubmissions() = runTest {
        withFixture {
            storedHistory[LIBRARY_A] = listOf(" first ", "first", "", "---", "x".repeat(161)) +
                (1..10).map { "term$it" }
            ready()
            assertEquals(listOf("first") + (1..7).map { "term$it" }, controller.state.recent)
            assertTrue(historySaves.isEmpty())
            controller.setQuery("  term3  ")
            advanceTimeBy(250)
            runCurrent()
            assertTrue(historySaves.isEmpty())
            controller.submit() // An empty successful search belongs in history too.
            runCurrent()
            val expected = listOf("term3", "first", "term1", "term2", "term4", "term5", "term6", "term7")
            assertEquals(expected, controller.state.recent)
            assertEquals(LIBRARY_A to expected, historySaves.single())
            controller.setQuery("failed")
            searchLoader = { _, _, _ -> error("Synthetic failure") }
            controller.submit()
            runCurrent()
            assertEquals(expected, controller.state.recent)
            controller.setQuery("!!!")
            controller.submit()
            assertEquals(1, historySaves.size)
            controller.setLibrary(LIBRARY_B, "B")
            runCurrent()
            assertTrue(controller.state.recent.isEmpty())
            controller.setLibrary(LIBRARY_A, "A")
            runCurrent()
            assertEquals(expected, controller.state.recent)
            controller.clearHistory()
            assertTrue(controller.state.recent.isEmpty())
            assertEquals(LIBRARY_A to emptyList<String>(), historySaves.last())
            assertEquals(emptyList<String>(), storedHistory[LIBRARY_A])
        }
    }

    @Test
    fun malformedHistoryAndThrowingStorageDoNotFailAnOtherwiseSuccessfulSearch() = runTest {
        withFixture {
            @Suppress("UNCHECKED_CAST")
            val malformed = listOf(null, 7, " kept ", "kept", "!!!") as List<String>
            recentLoader = { malformed }
            ready()
            assertEquals(listOf("kept"), controller.state.recent)
            recentSaver = { _, _ -> error("Synthetic storage failure") }
            searchLoader = { _, _, _ -> SearchPage(listOf(hit(10))) }
            controller.setQuery(" Example ")
            controller.submit()
            runCurrent()
            assertEquals(listOf("Example", "kept"), controller.state.recent)
            assertEquals(listOf(hit(10)), controller.state.results)
            assertNull(controller.state.error)
            assertFalse(controller.state.searching)
            controller.clearHistory()
            assertTrue(controller.state.recent.isEmpty())
            assertEquals(listOf(hit(10)), controller.state.results)
            recentLoader = { error("Synthetic storage failure") }
            controller.setLibrary(LIBRARY_B, "B")
            runCurrent()
            assertTrue(controller.state.recent.isEmpty())
            assertFalse(controller.state.catalogError)
            assertNull(controller.state.error)
        }
    }

    @Test
    fun closeIsSilentTerminalAndDoesNotCancelTheOwnerScope() = runTest {
        withFixture {
            val catalog = pending(books)
            val query = pending(SearchPage(listOf(hit(10))))
            catalogLoader = { catalog.await() }
            searchLoader = { _, _, _ -> query.await() }
            controller.setLibrary(LIBRARY_A, "A")
            controller.setQuery("example")
            controller.submit()
            runCurrent()
            val before = controller.state
            val publications = changes.toList()
            controller.close()
            controller.close()
            assertTrue(catalog.job!!.isCancelled)
            assertTrue(query.job!!.isCancelled)
            assertTrue(coroutineContext.job.isActive)
            controller.setLibrary(LIBRARY_B, "B")
            controller.setQuery("other")
            controller.setBooks(setOf(BOOK_A))
            controller.setMode(SearchMode.EXACT_PHRASE)
            controller.setTarget(SearchTarget.VERSE_REFERENCE)
            controller.submit()
            controller.selectSuggestion(SearchSuggestion("other"))
            controller.loadMore()
            controller.retryCatalog()
            controller.cancel()
            controller.pause()
            controller.resume()
            controller.clearHistory()
            catalog.complete()
            query.complete()
            runCurrent()
            assertTrue(catalog.returned)
            assertTrue(query.returned)
            assertEquals(before, controller.state)
            assertEquals(publications, changes)
            assertTrue(historySaves.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture(this)
        try {
            fixture.block()
        } finally {
            fixture.controller.close()
            fixture.releaseAll()
            runCurrent()
        }
    }

    private class Fixture(private val testScope: TestScope) {
        val changes = mutableListOf<SearchUiState>()
        val catalogCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<Call>()
        val suggestionCalls = mutableListOf<Pair<String, SearchRequest>>()
        val historyLoads = mutableListOf<String>()
        val historySaves = mutableListOf<Pair<String, List<String>>>()
        val storedHistory = mutableMapOf<String, List<String>>()
        private val releases = mutableListOf<() -> Unit>()
        var catalogLoader: suspend (String) -> List<SearchBook> = { books }
        var searchLoader: suspend (String, SearchRequest, Int) -> SearchPage = { _, _, _ -> SearchPage() }
        var suggestionLoader: suspend (String, SearchRequest) -> List<SearchSuggestion> = { _, _ -> emptyList() }
        var recentLoader: (String) -> List<String> = { storedHistory[it].orEmpty() }
        var recentSaver: (String, List<String>) -> Unit = { library, recent -> storedHistory[library] = recent }

        val controller = SearchController(
            scope = testScope,
            loadBooks = { library -> catalogCalls += library; catalogLoader(library) },
            search = { library, request, after ->
                searchCalls += Call(library, request, after)
                searchLoader(library, request, after)
            },
            suggest = { library, request ->
                suggestionCalls += library to request
                suggestionLoader(library, request)
            },
            onChange = { changes += it },
            loadRecent = { library -> historyLoads += library; recentLoader(library) },
            saveRecent = { library, recent ->
                historySaves += library to recent
                recentSaver(library, recent)
            },
        )

        fun ready() {
            controller.setLibrary(LIBRARY_A, "A")
            testScope.runCurrent()
            assertFalse(controller.state.catalogLoading)
            assertFalse(controller.state.catalogError)
            assertEquals(books, controller.state.books)
        }

        fun <T> pending(value: T): Pending<T> = Pending(value).also { pending ->
            releases += { pending.complete() }
        }

        fun releaseAll() { releases.forEach { it() } }
    }

    /** Deliberately returns after cancellation; every fixture releases these even on failure. */
    private class Pending<T>(private val value: T) {
        private val response = CompletableDeferred<T>()
        var job: Job? = null
        var returned = false

        suspend fun await(): T {
            job = currentCoroutineContext().job
            val result = withContext(NonCancellable) { response.await() }
            returned = true
            return result
        }

        fun complete() { response.complete(value) }
    }

    private data class Call(val library: String, val request: SearchRequest, val after: Int)

    private companion object {
        const val LIBRARY_A = "synthetic-library-a"
        const val LIBRARY_B = "synthetic-library-b"
        const val BOOK_A = 1
        const val BOOK_B = 100
        val books = listOf(BOOK_A, BOOK_B).map { record ->
            SearchBook(ContentNode(record, 0, "Book $record", record + 99, 0, 1))
        }

        fun hit(record: Int, library: String = LIBRARY_A) = ScopedSearchHit(
            ReadingLocation(library, "Location", record, record + 1, record),
            "Synthetic book", "Synthetic snippet $record",
        )
    }
}