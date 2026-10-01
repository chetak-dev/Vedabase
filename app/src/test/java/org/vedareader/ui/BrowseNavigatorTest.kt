package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.ContentNode

/** Synthetic navigation data only; no database, Android runtime, or reader content. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseNavigatorTest {
    @Test
    fun followsRecordAndParentLinksToLeafWithoutInferringHierarchyFromMetadata() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        assertEquals(BrowseState(), fixture.navigator.state)
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        fixture.assertState(BrowseState(libraryId = LIBRARY_A))
        assertTrue(fixture.calls.isEmpty())

        var path = emptyList<ContentNode>()
        val expectedChanges = mutableListOf(BrowseState(libraryId = LIBRARY_A))
        val steps = listOf(
            root to chapters,
            chapter to verses,
            verse to listOf(leaf),
            leaf to emptyList<ContentNode>(),
        )
        for ((node, children) in steps) {
            path = path + node
            fixture.navigator.open(node.record)
            val loading = BrowseState(LIBRARY_A, path, loading = true)
            fixture.assertState(loading)
            runCurrent()
            val loaded = BrowseState(LIBRARY_A, path, children)
            fixture.assertState(loaded)
            expectedChanges += loading
            expectedChanges += loaded
        }

        assertEquals(
            listOf(root, chapter, verse, leaf).map { LIBRARY_A to it.record },
            fixture.calls,
        )
        assertEquals(expectedChanges, fixture.changes)
    }

    @Test
    fun backBreadcrumbAndHomeUsePathDepthAndReloadOnlyTheRequestedParent() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        openAndRun(fixture, root, chapter, verse, leaf)
        fixture.calls.clear()

        fixture.navigator.back()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter, verse), loading = true))
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter, verse), listOf(leaf)))

        fixture.navigator.goToDepth(1)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), loading = true))
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), chapters))

        fixture.navigator.goToDepth(0)
        runCurrent()
        fixture.assertState(BrowseState(libraryId = LIBRARY_A))
        assertEquals(listOf(LIBRARY_A to verse.record, LIBRARY_A to root.record), fixture.calls)

        openAndRun(fixture, otherRoot)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(otherRoot)))
        fixture.navigator.back()
        runCurrent()
        fixture.assertState(BrowseState(libraryId = LIBRARY_A))
        assertEquals(
            listOf(verse, root, otherRoot).map { LIBRARY_A to it.record },
            fixture.calls,
        )
    }

    @Test
    fun noLibraryUnknownRecordsAndCurrentOrInvalidBreadcrumbsAreNoOps() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.navigator.setLibrary(null, roots)
        fixture.navigator.open(root.record)
        fixture.navigator.back()
        fixture.navigator.goToDepth(0)
        fixture.navigator.retry()
        runCurrent()
        assertEquals(BrowseState(), fixture.navigator.state)
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.changes.isEmpty())

        fixture.navigator.setLibrary(LIBRARY_A, roots)
        val homeChanges = fixture.changes.toList()
        fixture.navigator.open(chapter.record)
        fixture.navigator.open(Int.MAX_VALUE)
        fixture.navigator.back()
        fixture.navigator.goToDepth(0)
        fixture.navigator.retry()
        runCurrent()
        assertEquals(homeChanges, fixture.changes)
        assertTrue(fixture.calls.isEmpty())

        openAndRun(fixture, root)
        val changes = fixture.changes.toList()
        for (record in listOf(0, Int.MAX_VALUE, root.record, otherRoot.record, leaf.record)) {
            fixture.navigator.open(record)
        }
        for (depth in listOf(-1, fixture.navigator.state.path.size, Int.MAX_VALUE)) {
            fixture.navigator.goToDepth(depth)
        }
        fixture.navigator.retry()
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), chapters))
        assertEquals(changes, fixture.changes)
        assertEquals(listOf(LIBRARY_A to root.record), fixture.calls)
    }

    @Test
    fun extraOpensWhileLoadingDoNotStartOrReplaceRequests() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<List<ContentNode>>()
        val fixture = Fixture(this) { _, parent ->
            check(parent == root.record)
            response.await()
        }
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        fixture.navigator.open(root.record)
        val changes = fixture.changes.toList()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), loading = true))

        // Try both before the queued loader starts and while it is suspended.
        repeat(2) {
            fixture.navigator.open(root.record)
            fixture.navigator.open(otherRoot.record)
            fixture.navigator.open(chapter.record)
            fixture.navigator.goToDepth(1)
            fixture.navigator.retry()
            runCurrent()
            assertEquals(changes, fixture.changes)
            assertEquals(listOf(LIBRARY_A to root.record), fixture.calls)
        }

        response.complete(chapters)
        runCurrent()
        val loaded = BrowseState(LIBRARY_A, listOf(root), chapters)
        fixture.assertState(loaded)
        assertEquals(changes + loaded, fixture.changes)
        assertEquals(listOf(LIBRARY_A to root.record), fixture.calls)
    }

    @Test
    fun errorAndRetryKeepThePathAndReloadTheSameParent() = runTest(StandardTestDispatcher()) {
        var chapterAttempts = 0
        val fixture = Fixture(this) { _, parent ->
            if (parent == chapter.record && ++chapterAttempts == 1) {
                throw IllegalStateException("Synthetic load failure")
            }
            tree.getValue(parent)
        }
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        openAndRun(fixture, root, chapter)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), failed = true))

        val failedChanges = fixture.changes.toList()
        fixture.navigator.open(verse.record)
        fixture.navigator.open(otherRoot.record)
        runCurrent()
        assertEquals(failedChanges, fixture.changes)

        fixture.navigator.retry()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), loading = true))
        fixture.navigator.retry()
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), verses))
        val loadedChanges = fixture.changes.toList()
        fixture.navigator.retry()
        runCurrent()
        assertEquals(loadedChanges, fixture.changes)
        assertEquals(2, chapterAttempts)
        assertEquals(
            listOf(root, chapter, chapter).map { LIBRARY_A to it.record },
            fixture.calls,
        )
    }

    @Test
    fun rapidBackCancelsADelayedLoadAndCannotResurrectTheBranch() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<List<ContentNode>>()
        val cancelled = CompletableDeferred<Unit>()
        var returned = false
        val fixture = Fixture(this) { _, parent ->
            if (parent == chapter.record) {
                try {
                    response.await().also { returned = true }
                } catch (error: CancellationException) {
                    cancelled.complete(Unit)
                    throw error
                }
            } else tree.getValue(parent)
        }
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        openAndRun(fixture, root, chapter)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), loading = true))
        assertEquals(listOf(root, chapter).map { LIBRARY_A to it.record }, fixture.calls)

        fixture.navigator.back()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), loading = true))
        fixture.navigator.back()
        fixture.assertState(BrowseState(libraryId = LIBRARY_A))
        val changes = fixture.changes.toList()
        runCurrent()
        assertTrue(cancelled.isCompleted)

        response.complete(verses)
        runCurrent()
        assertFalse(returned)
        fixture.assertState(BrowseState(libraryId = LIBRARY_A))
        assertEquals(changes, fixture.changes)
        // The intermediate back request was cancelled before its loader could start.
        assertEquals(listOf(root, chapter).map { LIBRARY_A to it.record }, fixture.calls)
    }

    @Test
    fun lateNonCancellableResultCannotReplaceANewerBranch() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<List<ContentNode>>()
        var returned = false
        var requestWasCancelled = false
        val fixture = Fixture(this) { _, parent ->
            if (parent == chapter.record) {
                val requestJob = currentCoroutineContext().job
                val children = withContext(NonCancellable) { response.await() }
                returned = true
                requestWasCancelled = requestJob.isCancelled
                children
            } else tree.getValue(parent)
        }
        try {
            fixture.navigator.setLibrary(LIBRARY_A, roots)
            openAndRun(fixture, root, chapter)
            fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), loading = true))

            fixture.navigator.goToDepth(0)
            openAndRun(fixture, otherRoot)
            val expected = BrowseState(LIBRARY_A, listOf(otherRoot))
            fixture.assertState(expected)
            val changes = fixture.changes.toList()

            response.complete(verses)
            runCurrent()
            assertTrue(returned)
            assertTrue(requestWasCancelled)
            fixture.assertState(expected)
            assertEquals(changes, fixture.changes)
            assertEquals(listOf(root, chapter, otherRoot).map { LIBRARY_A to it.record }, fixture.calls)
        } finally {
            // Release the non-cooperative loader even if an assertion fails.
            response.complete(verses)
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun switchingLibrariesClearsThePathAndRejectsOldResultsWithTheSameRecordIds() = runTest(StandardTestDispatcher()) {
        val newRoot = root.copy(title = "Other collection")
        val newChapter = chapter.copy(title = "Other chapter")
        val newVerse = verse.copy(title = "Other verse")
        val response = CompletableDeferred<List<ContentNode>>()
        var oldResultReturned = false
        val fixture = Fixture(this) { library, parent ->
            when (library) {
                LIBRARY_A -> if (parent == chapter.record) {
                    val children = withContext(NonCancellable) { response.await() }
                    oldResultReturned = true
                    children
                } else tree.getValue(parent)
                LIBRARY_B -> when (parent) {
                    newRoot.record -> listOf(newChapter)
                    newChapter.record -> listOf(newVerse)
                    else -> error("Unexpected synthetic parent: $parent")
                }
                else -> error("Unexpected synthetic library: $library")
            }
        }
        try {
            fixture.navigator.setLibrary(LIBRARY_A, roots)
            openAndRun(fixture, root, chapter)
            fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), loading = true))

            fixture.navigator.setLibrary(LIBRARY_B, listOf(newRoot))
            fixture.assertState(BrowseState(libraryId = LIBRARY_B))
            val homeChanges = fixture.changes.toList()
            fixture.navigator.open(chapter.record)
            runCurrent()
            assertEquals(homeChanges, fixture.changes)

            openAndRun(fixture, newRoot, newChapter)
            val expected = BrowseState(LIBRARY_B, listOf(newRoot, newChapter), listOf(newVerse))
            fixture.assertState(expected)
            val changes = fixture.changes.toList()
            response.complete(verses)
            runCurrent()
            assertTrue(oldResultReturned)
            fixture.assertState(expected)
            assertEquals(changes, fixture.changes)
            assertEquals(
                listOf(
                    LIBRARY_A to root.record,
                    LIBRARY_A to chapter.record,
                    LIBRARY_B to newRoot.record,
                    LIBRARY_B to newChapter.record,
                ),
                fixture.calls,
            )
        } finally {
            response.complete(verses)
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun duplicateRecordChildrenFailEvenWhenTheirTitlesDiffer() = runTest(StandardTestDispatcher()) {
        assertChildrenRejected(listOf(verse, verse.copy(title = "Different heading")))
    }

    @Test
    fun ancestorSelfAndZeroRecordChildrenFailEvenWithTheExpectedParent() = runTest(StandardTestDispatcher()) {
        for (invalid in listOf(
            root.copy(parent = chapter.record),
            chapter.copy(parent = chapter.record),
            verse.copy(record = 0),
        )) {
            assertChildrenRejected(listOf(otherVerse, invalid))
        }
    }

    @Test
    fun wrongParentChildrenRejectTheEntireBatchWithoutPublishingPartialEntries() = runTest(StandardTestDispatcher()) {
        for (wrongParent in listOf(0, root.record, otherRoot.record)) {
            assertChildrenRejected(listOf(verse, otherVerse.copy(parent = wrongParent)))
        }
    }

    @Test
    fun closeCancelsItsRequestAndIgnoresLateResultsWithoutCancellingTheOwnerScope() = runTest(StandardTestDispatcher()) {
        for (nonCancellable in listOf(false, true)) {
            val response = CompletableDeferred<List<ContentNode>>()
            val requestJob = CompletableDeferred<Job>()
            var returned = false
            var finished = false
            val fixture = Fixture(this) { _, parent ->
                check(parent == root.record)
                requestJob.complete(currentCoroutineContext().job)
                try {
                    val children = if (nonCancellable) {
                        withContext(NonCancellable) { response.await() }
                    } else response.await()
                    returned = true
                    children
                } finally {
                    finished = true
                }
            }
            try {
                fixture.navigator.setLibrary(LIBRARY_A, roots)
                openAndRun(fixture, root)
                assertTrue(requestJob.isCompleted)
                val beforeClose = fixture.navigator.state
                val changes = fixture.changes.toList()

                fixture.navigator.close()
                fixture.navigator.close()
                runCurrent()
                assertTrue(requestJob.await().isCancelled)
                assertTrue(coroutineContext.job.isActive)
                assertEquals(!nonCancellable, finished)

                response.complete(chapters)
                runCurrent()
                assertEquals(nonCancellable, returned)
                assertTrue(finished)
                fixture.assertState(beforeClose)
                assertEquals(changes, fixture.changes)
                assertEquals(listOf(LIBRARY_A to root.record), fixture.calls)
            } finally {
                response.complete(chapters)
                fixture.navigator.close()
                runCurrent()
            }
        }
    }

    private fun TestScope.openAndRun(fixture: Fixture, vararg nodes: ContentNode) {
        for (node in nodes) {
            fixture.navigator.open(node.record)
            runCurrent()
        }
    }

    private fun TestScope.assertChildrenRejected(children: List<ContentNode>) {
        val fixture = Fixture(this) { _, parent ->
            if (parent == chapter.record) children else tree.getValue(parent)
        }
        fixture.navigator.setLibrary(LIBRARY_A, roots)
        openAndRun(fixture, root, chapter)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root, chapter), failed = true))
        assertEquals(listOf(root, chapter).map { LIBRARY_A to it.record }, fixture.calls)
        assertTrue(
            "Malformed children must never be published, even as a partial batch",
            fixture.changes.none { it.path.size == 2 && it.entries.isNotEmpty() },
        )

        val changes = fixture.changes.toList()
        children.forEach { fixture.navigator.open(it.record) }
        runCurrent()
        assertEquals(changes, fixture.changes)

        fixture.navigator.back()
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(root), chapters))
        assertEquals(listOf(root, chapter, root).map { LIBRARY_A to it.record }, fixture.calls)
    }

    private class Fixture(
        scope: CoroutineScope,
        loader: suspend (String, Int) -> List<ContentNode> = { library, parent ->
            check(library == LIBRARY_A)
            tree.getValue(parent)
        },
    ) {
        val calls = mutableListOf<Pair<String, Int>>()
        val changes = mutableListOf<BrowseState>()
        val navigator = BrowseNavigator(
            scope = scope,
            loadChildren = { library, parent ->
                calls += library to parent
                loader(library, parent)
            },
            onChange = { changes += it },
        )

        fun assertState(expected: BrowseState) {
            assertEquals(expected, navigator.state)
            assertEquals(expected, changes.last())
            assertEquals(expected.path.lastOrNull(), navigator.state.current)
        }
    }

    private companion object {
        const val LIBRARY_A = "synthetic-library-a"
        const val LIBRARY_B = "synthetic-library-b"

        // Only parent links define this tree. Record/level order and node_children are misleading.
        val root = node(700, 0, "Collection", children = 0, level = 2)
        val otherRoot = node(90, 0, "Collection", children = 17, level = 23)
        val sameTitleChapter = node(800, root.record, "Repeated heading", children = 73, level = 19)
        val chapter = node(240, root.record, "Repeated heading", children = 0, level = 7)
        val verse = node(910, chapter.record, "", children = 0, level = 31)
        val otherVerse = node(43, chapter.record, "Verse", children = 0, level = 17)
        val leaf = node(110, verse.record, "   ", children = 42, level = 80)
        val roots = listOf(otherRoot, root)
        val chapters = listOf(sameTitleChapter, chapter)
        val verses = listOf(verse, otherVerse)
        val tree = mapOf(
            root.record to chapters,
            otherRoot.record to emptyList(),
            sameTitleChapter.record to emptyList(),
            chapter.record to verses,
            verse.record to listOf(leaf),
            otherVerse.record to emptyList(),
            leaf.record to emptyList(),
        )

        fun node(record: Int, parent: Int, title: String, children: Int, level: Int) = ContentNode(
            record = record,
            parent = parent,
            title = title,
            end = record + 1,
            children = children,
            level = level,
        )
    }
}