package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkException
import org.vedareader.data.BookmarkIssue
import org.vedareader.data.ReadingLocation
import kotlin.coroutines.ContinuationInterceptor

/** Synthetic snapshots and deferred gates only: no Android, files, or library/source access. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkControllerTest {
    @Test
    fun constructionDoesNotLoadAndWritesCannotStartBeforeExplicitSuccessfulLoad() = runTest {
        withFixture {
            assertEquals(BookmarkUiState(), controller.state)
            controller.add { error("Must not create before loading") }
            controller.remove(bookmark())
            controller.acknowledge(1)
            runCurrent()
            assertEquals(0, loadCalls)
            assertTrue(addCalls.isEmpty())
            assertTrue(removeCalls.isEmpty())
            assertTrue(changes.isEmpty())
            assertEquals(BookmarkUiState(), controller.state)

            controller.reload()
            runCurrent()
            assertEquals(1, loadCalls)
            assertEquals(BookmarkUiState(loading = false), controller.state)
            controller.add { bookmark() }
            runCurrent()
            assertEquals(listOf(bookmark()), controller.state.items)
            assertEquals(BookmarkMessage.SAVED, controller.state.feedback?.message)
        }
    }

    @Test
    fun initialLoadingFlagDoesNotBlockReloadButQueuedAndDeferredLoadsGateEveryOperation() = runTest {
        withFixture {
            val gate = gate()
            loadAction = { gate.await(); listOf(bookmark()) }
            controller.reload()
            repeat(3) {
                controller.reload()
                controller.add { error("Must not create while loading") }
                controller.remove(bookmark())
            }
            assertTrue(controller.state.loading)
            assertFalse(controller.state.busy)
            assertNull(controller.state.feedback)
            assertEquals(0, loadCalls)
            runCurrent()
            assertEquals(1, loadCalls)
            repeat(3) { controller.reload() }
            runCurrent()
            assertEquals(1, loadCalls)
            assertTrue(controller.state.items.isEmpty())
            assertNull(controller.state.feedback)
            gate.complete(Unit)
            runCurrent()
            assertEquals(BookmarkUiState(items = listOf(bookmark()), loading = false), controller.state)
            assertTrue(addCalls.isEmpty())
            assertTrue(removeCalls.isEmpty())
        }
    }

    @Test
    fun refreshRetainsOldItemsUntilSuccessAndCopiesReturnedStoreLists() = runTest {
        withFixture {
            val original = mutableListOf(bookmark(), bookmark("second", position = 30))
            loadAction = { original }
            controller.reload()
            runCurrent()
            val before = controller.state
            assertNotSame(original, before.items)
            original.clear()
            assertEquals(2, before.items.size)

            val gate = gate()
            val replacement = mutableListOf(bookmark("third"), bookmark("fourth", position = 40))
            loadAction = { gate.await(); replacement }
            controller.reload()
            runCurrent()
            assertEquals(before.copy(loading = true), controller.state)
            gate.complete(Unit)
            runCurrent()
            assertEquals(replacement, controller.state.items)
            assertNotSame(replacement, controller.state.items)
            replacement.clear()
            assertEquals(2, controller.state.items.size)
            assertEquals(2, before.items.size)
            assertNull(controller.state.feedback)
        }
    }

    @Test
    fun everyReadFailureIsLoadFailureAndOnlyExplicitReloadRetries() = runTest {
        for (failure in listOf(IllegalStateException("read")) +
            BookmarkIssue.entries.map { BookmarkException(it) }) withFixture {
            loadAction = { throw failure }
            controller.reload()
            runCurrent()
            assertEquals(BookmarkIssue.LOAD, controller.state.error)
            assertFalse(controller.state.loading)
            assertFalse(controller.state.busy)
            val first = controller.state.feedback!!
            assertEquals(BookmarkMessage.LOAD_FAILED, first.message)
            repeat(3) {
                controller.add { error("No implicit load retry or snapshot") }
                controller.remove(bookmark())
                runCurrent()
            }
            assertEquals(1, loadCalls)
            assertTrue(addCalls.isEmpty())
            assertTrue(removeCalls.isEmpty())
            assertEquals(first, controller.state.feedback)
            controller.acknowledge(first.id)
            assertEquals(BookmarkIssue.LOAD, controller.state.error)
            controller.add { error("Acknowledgement does not make the store ready") }

            controller.reload()
            runCurrent()
            assertEquals(2, loadCalls)
            assertTrue(controller.state.feedback!!.id > first.id)
            loadAction = { stored }
            controller.reload()
            runCurrent()
            assertEquals(3, loadCalls)
            assertEquals(BookmarkUiState(loading = false), controller.state)
            controller.add { bookmark() }
            runCurrent()
            assertEquals(listOf(bookmark()), controller.state.items)
        }
    }

    @Test
    fun failedRefreshPreservesPreviousSnapshotAndBlocksWritesUntilReloadSucceeds() = runTest {
        withFixture {
            ready(bookmark())
            val before = controller.state.items
            loadAction = { throw BookmarkException(BookmarkIssue.LOAD) }
            controller.reload()
            runCurrent()
            assertSame(before, controller.state.items)
            assertEquals(BookmarkIssue.LOAD, controller.state.error)
            val failed = controller.state
            controller.remove(bookmark())
            controller.add { error("Failed refresh must block creation") }
            runCurrent()
            assertEquals(failed, controller.state)
            assertEquals(2, loadCalls)
            assertTrue(removeCalls.isEmpty())
            loadAction = { stored }
            controller.reload()
            runCurrent()
            controller.remove(bookmark())
            runCurrent()
            assertTrue(controller.state.items.isEmpty())
            assertEquals(BookmarkMessage.REMOVED, controller.state.feedback?.message)
        }
    }

    @Test
    fun rapidAddCreatesOnceOnProvidedWorkerAndPublishesOnlyReturnedCommittedSnapshot() = runTest {
        withFixture {
            val original = bookmark()
            ready(original)
            val oldState = controller.state
            val createGate = gate()
            val commitGate = gate()
            val worker = StandardTestDispatcher(testScheduler, name = "vm-bookmark-worker")
            var current = bookmark("new", position = 30)
            val captured = current
            var createCalls = 0
            val returned = mutableListOf(bookmark("external", LIBRARY_B), captured, original)
            addAction = { value ->
                assertEquals(captured, value)
                assertSame(oldState.items, controller.state.items)
                assertNull(controller.state.feedback)
                commitGate.await()
                stored = returned.toList()
                returned
            }
            controller.add {
                withContext(worker) {
                    assertSame(worker, currentCoroutineContext()[ContinuationInterceptor])
                    createCalls++
                    val snapshot = current
                    createGate.await()
                    snapshot
                }
            }
            repeat(3) {
                controller.add { error("Queued double tap") }
                controller.remove(original)
                controller.reload()
            }
            assertEquals(oldState.copy(busy = true), controller.state)
            assertEquals(0, createCalls)
            runCurrent()
            assertEquals(1, createCalls)
            assertTrue(addCalls.isEmpty())
            current = bookmark("reader-moved", position = 40)
            controller.add { error("Double tap during creation") }
            createGate.complete(Unit)
            runCurrent()
            assertEquals(listOf(captured), addCalls)
            assertEquals(listOf(original), stored)
            assertEquals(oldState.copy(busy = true), controller.state)
            repeat(3) {
                controller.add { error("Double tap during commit") }
                controller.reload()
                controller.remove(original)
            }
            commitGate.complete(Unit)
            runCurrent()
            assertEquals(returned, controller.state.items)
            assertEquals(1, createCalls)
            assertEquals(1, loadCalls)
            assertTrue(removeCalls.isEmpty())
            assertEquals(BookmarkMessage.SAVED, controller.state.feedback?.message)
            assertFalse(controller.state.busy)
            assertFalse(controller.state.loading)
            assertNull(controller.state.error)
            returned.clear()
            assertEquals(3, controller.state.items.size)
            assertEquals(listOf(original), oldState.items)
        }
    }

    @Test
    fun duplicateAddUsesUnchangedStoreResultAndReportsSavedWithoutInventingAnEntry() = runTest {
        withFixture {
            val original = bookmark()
            ready(original)
            val duplicate = original.copy(id = "new-id", excerpt = "New snapshot", createdAt = 200)
            controller.add { duplicate }
            runCurrent()
            assertEquals(listOf(duplicate), addCalls)
            assertEquals(listOf(original), stored)
            assertEquals(listOf(original), controller.state.items)
            assertEquals(BookmarkMessage.SAVED, controller.state.feedback?.message)
            assertNull(controller.state.error)
        }
    }

    @Test
    fun deferredRemoveRetainsItemsAndDoesNotPublishFakeSuccessOrQueueMoreWork() = runTest {
        withFixture {
            val first = bookmark()
            val second = bookmark("second", position = 30)
            ready(first, second)
            val before = controller.state
            val gate = gate()
            val commit = removeAction
            removeAction = { id, library -> gate.await(); commit(id, library) }
            controller.remove(first)
            repeat(3) { controller.remove(first); controller.remove(second); controller.reload() }
            assertEquals(before.copy(busy = true), controller.state)
            runCurrent()
            assertEquals(listOf(first.id to LIBRARY_A), removeCalls)
            assertEquals(before.items, stored)
            assertSame(before.items, controller.state.items)
            assertNull(controller.state.feedback)
            controller.add { error("Remove owns the single slot") }
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(second), controller.state.items)
            assertEquals(BookmarkMessage.REMOVED, controller.state.feedback?.message)
            assertEquals(1, loadCalls)
            assertTrue(addCalls.isEmpty())
            assertEquals(listOf(first, second), before.items)
        }
    }

    @Test
    fun writeFailuresKeepItemsMapTypedIssuesAndRequireReloadBeforeAddOrRemoveRetry() = runTest {
        val failures = listOf(
            BookmarkException(BookmarkIssue.LOAD) to BookmarkMessage.LOAD_FAILED,
            BookmarkException(BookmarkIssue.SAVE) to BookmarkMessage.SAVE_FAILED,
            BookmarkException(BookmarkIssue.LIMIT) to BookmarkMessage.LIMIT_REACHED,
            IllegalStateException("write") to BookmarkMessage.SAVE_FAILED,
        )
        for (removing in listOf(false, true)) for ((failure, message) in failures) withFixture {
            val original = bookmark()
            val added = bookmark("added", position = 30)
            ready(original)
            val before = controller.state.items
            val save = addAction
            val delete = removeAction
            addAction = { throw failure }
            removeAction = { _, _ -> throw failure }
            if (removing) controller.remove(original) else controller.add { added }
            runCurrent()
            assertSame(before, controller.state.items)
            assertEquals(before, stored)
            assertFalse(controller.state.loading)
            assertFalse(controller.state.busy)
            assertEquals((failure as? BookmarkException)?.issue ?: BookmarkIssue.SAVE, controller.state.error)
            assertEquals(message, controller.state.feedback?.message)
            val failed = controller.state
            val writes = addCalls.size + removeCalls.size
            repeat(3) {
                controller.add { error("No creation or automatic retry through an error") }
                controller.remove(original)
                runCurrent()
            }
            assertEquals(failed, controller.state)
            assertEquals(1, loadCalls)
            assertEquals(writes, addCalls.size + removeCalls.size)
            controller.acknowledge(failed.feedback!!.id)
            controller.remove(original)
            assertEquals(failed.error, controller.state.error)
            assertEquals(writes, addCalls.size + removeCalls.size)

            addAction = save
            removeAction = delete
            controller.reload()
            runCurrent()
            assertNull(controller.state.error)
            assertNull(controller.state.feedback)
            if (removing) controller.remove(original) else controller.add { added }
            runCurrent()
            assertEquals(if (removing) emptyList<Bookmark>() else listOf(added, original), controller.state.items)
            assertEquals(if (removing) BookmarkMessage.REMOVED else BookmarkMessage.SAVED,
                controller.state.feedback?.message)
            assertTrue(controller.state.feedback!!.id > failed.feedback.id)
            assertEquals(2, loadCalls)
            assertEquals(writes + 1, addCalls.size + removeCalls.size)
        }
    }

    @Test
    fun snapshotCreationFailuresNeverReachStoreAndKeepTheirTypedFailure() = runTest {
        for (issue in BookmarkIssue.entries) withFixture {
            ready(bookmark())
            val before = controller.state.items
            controller.add { throw BookmarkException(issue) }
            runCurrent()
            assertTrue(addCalls.isEmpty())
            assertSame(before, controller.state.items)
            assertEquals(issue, controller.state.error)
            assertEquals(when (issue) {
                BookmarkIssue.LOAD -> BookmarkMessage.LOAD_FAILED
                BookmarkIssue.SAVE -> BookmarkMessage.SAVE_FAILED
                BookmarkIssue.LIMIT -> BookmarkMessage.LIMIT_REACHED
            }, controller.state.feedback?.message)
            assertFalse(controller.state.busy)
            controller.add { error("Snapshot failure requires explicit reload too") }
            runCurrent()
            assertTrue(addCalls.isEmpty())
            assertEquals(1, loadCalls)
        }
    }

    @Test
    fun removeRequiresEntireCurrentSnapshotAndUsesLibraryAlongsideSharedBookmarkId() = runTest {
        withFixture {
            val first = bookmark()
            val otherLibrary = first.copy(location = first.location.copy(libraryId = LIBRARY_B))
            val otherId = first.copy(id = "other-id", location = first.location.copy(position = 30))
            ready(first, otherLibrary, otherId)
            val before = controller.state
            val forged = listOf(
                first.copy(id = "missing"),
                first.copy(location = first.location.copy(libraryId = "foreign")),
                first.copy(location = first.location.copy(title = "Changed title")),
                first.copy(location = first.location.copy(position = 40)),
                first.copy(location = first.location.copy(start = 11)),
                first.copy(location = first.location.copy(end = 99)),
                first.copy(location = first.location.copy(scrollY = 1)),
                first.copy(bookRecord = 3), first.copy(bookTitle = "Changed book"),
                first.copy(excerpt = "Changed excerpt"), first.copy(createdAt = 200),
            )
            forged.forEach(controller::remove)
            runCurrent()
            assertTrue(removeCalls.isEmpty())
            assertEquals(before, controller.state)
            val publications = changes.size
            val replacement = first.copy(excerpt = "Replacement with same key", createdAt = 300)
            stored = listOf(replacement, otherLibrary, otherId)
            controller.reload()
            runCurrent()
            val refreshed = controller.state
            controller.remove(first) // A confirmation opened before the refresh is stale.
            runCurrent()
            assertEquals(refreshed, controller.state)
            assertTrue(removeCalls.isEmpty())
            assertTrue(changes.size > publications)
            controller.remove(replacement.copy()) // Structural equality, not object identity.
            runCurrent()
            assertEquals(listOf(replacement.id to LIBRARY_A), removeCalls)
            assertEquals(listOf(otherLibrary, otherId), controller.state.items)
            val removed = controller.state
            controller.remove(replacement)
            runCurrent()
            assertEquals(removed, controller.state)
            assertEquals(1, removeCalls.size)
            controller.remove(otherLibrary)
            runCurrent()
            assertEquals(listOf(replacement.id to LIBRARY_A, otherLibrary.id to LIBRARY_B), removeCalls)
            assertEquals(listOf(otherId), controller.state.items)
        }
    }

    @Test
    fun feedbackTokensIncreaseAndOldAcknowledgementCannotClearNewFeedback() = runTest {
        withFixture {
            ready()
            controller.add { bookmark() }
            runCurrent()
            val first = controller.state.feedback!!
            controller.remove(bookmark())
            assertNull(controller.state.feedback)
            controller.acknowledge(first.id)
            runCurrent()
            val second = controller.state.feedback!!
            assertTrue(first.id > 0)
            assertTrue(second.id > first.id)
            assertEquals(BookmarkMessage.REMOVED, second.message)
            val before = controller.state
            val publications = changes.size
            controller.acknowledge(first.id)
            controller.acknowledge(second.id + 1)
            assertEquals(before, controller.state)
            assertEquals(publications, changes.size)
            controller.acknowledge(second.id)
            assertNull(controller.state.feedback)
            controller.acknowledge(second.id)
            assertEquals(publications + 1, changes.size)
            controller.add { bookmark() }
            runCurrent()
            assertTrue(controller.state.feedback!!.id > second.id)
        }
    }

    @Test
    fun cancellationPropagatesFromLoadCreateAddAndRemoveWithoutFailureFeedback() = runTest {
        for (stage in Stage.entries) withFixture {
            val original = bookmark()
            ready(original)
            val before = controller.state
            var cancelledJob: Job? = null
            suspend fun cancelOperation(): Nothing {
                cancelledJob = currentCoroutineContext().job
                throw CancellationException("Synthetic cancellation")
            }
            when (stage) {
                Stage.LOAD -> { loadAction = { cancelOperation() }; controller.reload() }
                Stage.CREATE -> controller.add { cancelOperation() }
                Stage.ADD -> { addAction = { cancelOperation() }; controller.add { bookmark("new") } }
                Stage.REMOVE -> { removeAction = { _, _ -> cancelOperation() }; controller.remove(original) }
            }
            runCurrent()
            assertTrue(cancelledJob!!.isCancelled)
            assertTrue(scope.isActive)
            assertEquals(before, controller.state)
            assertEquals(before.items, stored)
            assertTrue(changes.none { it.feedback != null })
            if (stage == Stage.CREATE) assertTrue(addCalls.isEmpty())
            loadAction = { stored }
            controller.reload()
            runCurrent()
            assertEquals(before, controller.state)
        }
    }

    @Test
    fun cancellingAnActiveChildResetsBusyAndAllowsAnotherWriteWithoutCancellingOwner() = runTest {
        withFixture {
            ready(bookmark())
            val gate = gate()
            var pending: Job? = null
            val commit = addAction
            addAction = { value ->
                pending = currentCoroutineContext().job
                gate.await()
                commit(value)
            }
            controller.add { bookmark("new", position = 30) }
            runCurrent()
            assertTrue(controller.state.busy)
            pending!!.cancel()
            runCurrent()
            assertTrue(pending!!.isCancelled)
            assertFalse(controller.state.busy)
            assertNull(controller.state.feedback)
            assertNull(controller.state.error)
            assertEquals(listOf(bookmark()), controller.state.items)
            assertTrue(scope.isActive)
            addAction = commit
            controller.add { bookmark("retry", position = 40) }
            runCurrent()
            assertEquals(listOf(bookmark("retry", position = 40), bookmark()), controller.state.items)
            assertEquals(BookmarkMessage.SAVED, controller.state.feedback?.message)
        }
    }

    @Test
    fun cancellationBeforeFirstDispatchReleasesGateButDoesNotPretendInitialLoadSucceeded() = runTest {
        withFixture {
            val existingChildren = scope.coroutineContext.job.children.toSet()
            controller.reload()
            val pending = scope.coroutineContext.job.children.single { it !in existingChildren }
            pending.cancel()
            runCurrent()
            assertTrue(pending.isCancelled)
            assertFalse(controller.state.loading)
            assertFalse(controller.state.busy)
            assertEquals(0, loadCalls)
            assertNull(controller.state.feedback)
            controller.add { error("A cancelled load is not a successful empty load") }
            runCurrent()
            assertTrue(addCalls.isEmpty())
            controller.reload()
            runCurrent()
            assertEquals(1, loadCalls)
            controller.add { bookmark() }
            runCurrent()
            assertEquals(1, addCalls.size)
        }
    }

    @Test
    fun closeBeforeQueuedLoadOrWriteIsSilentTerminalAndDoesNotOwnScope() = runTest {
        for (writing in listOf(false, true)) withFixture {
            if (writing) ready(bookmark())
            if (writing) controller.add { error("Closed before snapshot") } else controller.reload()
            val before = controller.state
            val publications = changes.toList()
            controller.close()
            controller.close()
            controller.reload()
            controller.add { error("Closed controller") }
            controller.remove(bookmark())
            controller.acknowledge(1)
            runCurrent()
            assertEquals(if (writing) 1 else 0, loadCalls)
            assertTrue(addCalls.isEmpty())
            assertTrue(removeCalls.isEmpty())
            assertEquals(before, controller.state)
            assertEquals(publications, changes)
            var siblingRan = false
            scope.launch { siblingRan = true }
            runCurrent()
            assertTrue(siblingRan)
            assertTrue(scope.isActive)
        }
    }

    @Test
    fun closeDiscardsNonCooperativeLoadAndCreateResultsAndLateTypedFailures() = runTest {
        for (creating in listOf(false, true)) for (failing in listOf(false, true)) withFixture {
            ready(bookmark())
            val gate = gate()
            var pending: Job? = null
            var finished = false
            suspend fun lateBookmark(): Bookmark {
                pending = currentCoroutineContext().job
                return withContext(NonCancellable) {
                    gate.await()
                    finished = true
                    if (failing) throw BookmarkException(BookmarkIssue.LIMIT)
                    bookmark("late", position = 30)
                }
            }
            if (creating) controller.add { lateBookmark() } else {
                loadAction = { listOf(lateBookmark()) }
                controller.reload()
            }
            runCurrent()
            val before = controller.state
            val publications = changes.toList()
            controller.close()
            assertTrue(pending!!.isCancelled)
            gate.complete(Unit)
            runCurrent()
            assertTrue(finished)
            assertTrue(pending!!.isCompleted)
            assertEquals(before, controller.state)
            assertEquals(publications, changes)
            assertEquals(listOf(bookmark()), stored)
            assertTrue(addCalls.isEmpty())
            assertTrue(removeCalls.isEmpty())
            assertTrue(scope.isActive)
        }
    }

    @Test
    fun closeDuringNonCancellableCommitKeepsDurableAddOrRemovalButPublishesNothing() = runTest {
        for (removing in listOf(false, true)) withFixture {
            val original = bookmark()
            val added = bookmark("new", position = 30)
            ready(original)
            val gate = gate()
            var pending: Job? = null
            var committed = false
            var returned = false
            val expected = if (removing) emptyList() else listOf(added, original)
            suspend fun durableCommit(): List<Bookmark> {
                pending = currentCoroutineContext().job
                val result = withContext(NonCancellable) {
                    gate.await()
                    stored = expected
                    committed = true
                    stored
                }
                returned = true
                return result
            }
            if (removing) {
                removeAction = { _, _ -> durableCommit() }
                controller.remove(original)
            } else {
                addAction = { durableCommit() }
                controller.add { added }
            }
            runCurrent()
            assertFalse(committed)
            assertEquals(listOf(original), stored)
            val before = controller.state
            val publications = changes.toList()
            controller.close()
            assertTrue(pending!!.isCancelled)
            gate.complete(Unit)
            runCurrent()
            assertTrue(committed)
            assertTrue(returned)
            assertTrue(pending!!.isCompleted)
            assertEquals(expected, stored)
            assertEquals(before, controller.state)
            assertEquals(publications, changes)
            assertEquals(1, addCalls.size + removeCalls.size)
            assertTrue(scope.isActive)
            // A new owner sees the commit; close never performs a compensating store mutation.
            val reopened = BookmarkController(scope, { stored }, { error("Unexpected add") },
                { _, _ -> error("Unexpected remove") }, {})
            try {
                reopened.reload()
                runCurrent()
                assertEquals(BookmarkUiState(items = expected, loading = false), reopened.state)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun immediateDispatcherAndReentrantBusyCallbacksCannotStartDuplicateWork() = runTest {
        val immediate = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
        withFixture(immediate) {
            ready(bookmark())
            var creates = 0
            observe = { value ->
                if (value.busy) {
                    controller.add { error("Reentrant duplicate") }
                    controller.remove(bookmark())
                    controller.reload()
                }
            }
            repeat(2) {
                controller.add { creates++; bookmark("new", position = 30) }
                assertFalse(controller.state.busy)
            }
            assertEquals(2, creates)
            assertEquals(2, addCalls.size)
            assertEquals(1, loadCalls)
            assertTrue(removeCalls.isEmpty())
            assertEquals(BookmarkMessage.SAVED, controller.state.feedback?.message)
        }
    }

    @Test
    fun reentrantCloseDuringBusyPublicationPreventsCreationAndFurtherPublications() = runTest {
        withFixture {
            ready(bookmark())
            observe = { if (it.busy) controller.close() }
            controller.add { error("Closed by busy observer before launch") }
            val before = controller.state
            val publications = changes.toList()
            runCurrent()
            assertEquals(before, controller.state)
            assertEquals(publications, changes)
            assertTrue(addCalls.isEmpty())
            assertTrue(scope.isActive)
        }
    }

    private enum class Stage { LOAD, CREATE, ADD, REMOVE }

    private suspend fun TestScope.withFixture(
        owner: CoroutineScope = this,
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(this, owner)
        try {
            fixture.block()
        } finally {
            fixture.controller.close()
            fixture.gates.forEach { it.complete(Unit) }
            runCurrent()
        }
    }

    private class Fixture(private val testScope: TestScope, val scope: CoroutineScope) {
        var stored: List<Bookmark> = emptyList()
        var loadCalls = 0
        val addCalls = mutableListOf<Bookmark>()
        val removeCalls = mutableListOf<Pair<String, String>>()
        val changes = mutableListOf<BookmarkUiState>()
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var observe: (BookmarkUiState) -> Unit = {}
        var loadAction: suspend () -> List<Bookmark> = { stored }
        var addAction: suspend (Bookmark) -> List<Bookmark> = { value ->
            if (stored.none { it.location.libraryId == value.location.libraryId &&
                    it.location.position == value.location.position }) stored = listOf(value) + stored
            stored
        }
        var removeAction: suspend (String, String) -> List<Bookmark> = { id, library ->
            stored = stored.filterNot { it.id == id && it.location.libraryId == library }
            stored
        }
        val controller = BookmarkController(
            scope = scope,
            load = { loadCalls++; loadAction() },
            add = { addCalls += it; addAction(it) },
            remove = { id, library -> removeCalls += id to library; removeAction(id, library) },
            onChange = { changes += it; observe(it) },
        )

        fun gate() = CompletableDeferred<Unit>().also { gates += it }

        fun ready(vararg bookmarks: Bookmark) {
            stored = bookmarks.toList()
            controller.reload()
            testScope.runCurrent()
            assertEquals(BookmarkUiState(items = stored, loading = false), controller.state)
        }
    }

    companion object {
        private const val LIBRARY_A = "library-a"
        private const val LIBRARY_B = "library-b"

        private fun bookmark(
            id: String = "shared-id",
            library: String = LIBRARY_A,
            position: Int = 20,
        ) = Bookmark(
            id = id,
            location = ReadingLocation(library, "Synthetic section", 10, 100, position),
            bookRecord = 2,
            bookTitle = "Synthetic book",
            excerpt = "Synthetic paragraph",
            createdAt = 100,
        )
    }
}