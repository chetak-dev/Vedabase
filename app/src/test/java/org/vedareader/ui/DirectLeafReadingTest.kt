package org.vedareader.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.ChapterOverview
import org.vedareader.data.ContentNode
import org.vedareader.data.VersePreview

/** Synthetic nodes and a virtual scheduler only; no Android, database, store, or reader WebView. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectLeafReadingTest {
    @Test
    fun onlyActuallyLoadedEmptyChildrenOpenTheReaderRegardlessOfNodeChildrenHints() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        fixture.navigator.open(leaf.record)
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        assertTrue(fixture.reads.isEmpty())

        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
        // Both containers claim zero children. Neither title nor node_children is a leaf test.
        for (node in listOf(collection, parent)) {
            assertEquals(0, node.children)
            fixture.navigator.open(node.record)
            assertTrue(fixture.navigator.state.loading)
            assertTrue(fixture.reads.isEmpty())
            runCurrent()
            assertFalse(fixture.navigator.state.loading)
            assertTrue(fixture.navigator.state.entries.isNotEmpty())
            assertTrue(fixture.reads.isEmpty())
        }
        val ready = fixture.navigator.state
        assertTrue(leaf.children > 0) // Conversely, the real leaf claims it has many children.
        fixture.navigator.open(leaf.record)
        assertTrue(fixture.navigator.state.loading)
        assertTrue(fixture.reads.isEmpty())
        runCurrent()

        assertEquals(listOf(LIBRARY_A to leaf), fixture.reads)
        assertSame(ready, fixture.navigator.state)
        assertSame(ready, fixture.readParents.single())
        assertEquals(listOf(collection, parent, leaf).map { LIBRARY_A to it.record }, fixture.calls)
        assertEquals(listOf(collection, parent).map { LIBRARY_A to it.record }, fixture.overviewCalls)
        assertTrue("The leaf must never publish a ready empty contents page", fixture.changes.none {
            it.current == leaf && !it.loading
        })
    }

    @Test
    fun directReadingRestoresTheExactParentPathAndBackAnchorIncludingLibraryRoot() = runTest(StandardTestDispatcher()) {
        val rootLeaf = ContentNode(3000, 0, "Synthetic root text", 3009, 8, 1)
        val fixture = Fixture(this, childLoader = { _, record ->
            if (record == rootLeaf.record) emptyList() else tree.getValue(record)
        })
        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection, rootLeaf))
        openAndRun(fixture.navigator, collection, parent)
        val ready = fixture.navigator.state
        val anchor = ready.libraryId to ready.current?.record
        val changes = fixture.changes.toList()
        val calls = fixture.calls.toList()

        openAndRun(fixture.navigator, leaf)

        assertSame(ready, fixture.navigator.state)
        assertSame(ready, fixture.readParents.single()) // Parent published BEFORE the reader callback.
        assertSame(ready.entries, fixture.navigator.state.entries)
        assertEquals(listOf(collection, parent), fixture.navigator.state.path)
        assertEquals(anchor, fixture.navigator.state.libraryId to fixture.navigator.state.current?.record)
        assertEquals(changes + listOf(BrowseState(LIBRARY_A, ready.path + leaf, loading = true), ready), fixture.changes)
        assertEquals(calls + (LIBRARY_A to leaf.record), fixture.calls) // No parent reload to reset its anchor.

        // After closing the reader, Browse Back must leave the parent, not revisit the leaf.
        fixture.navigator.back()
        runCurrent()
        assertEquals(BrowseState(LIBRARY_A, listOf(collection), listOf(parent)), fixture.navigator.state)
        assertEquals(calls + listOf(LIBRARY_A to leaf.record, LIBRARY_A to collection.record), fixture.calls)

        fixture.navigator.goToDepth(0)
        val home = fixture.navigator.state
        openAndRun(fixture.navigator, rootLeaf)
        assertSame(home, fixture.navigator.state)
        assertSame(home, fixture.readParents.last())
        assertEquals(BrowseState(libraryId = LIBRARY_A), home)
        assertEquals(listOf(LIBRARY_A to leaf, LIBRARY_A to rootLeaf), fixture.reads)
        val homeChanges = fixture.changes.toList()
        fixture.navigator.back()
        runCurrent()
        assertEquals(homeChanges, fixture.changes)
    }

    @Test
    fun staleNonCancellableLeafResultsCannotOpenReaderAfterBackOrLibrarySwitch() = runTest(StandardTestDispatcher()) {
        for (switchLibrary in listOf(false, true)) {
            val response = CompletableDeferred<List<ContentNode>>()
            var returned = false
            var cancelled = false
            val otherCollection = collection.copy(title = "Other synthetic collection")
            val otherParent = parent.copy(title = "Other synthetic parent")
            val otherLeaf = leaf.copy(title = "Other synthetic leaf")
            val fixture = Fixture(this, childLoader = { library, record ->
                when {
                    library == LIBRARY_A && record == leaf.record -> {
                        val request = currentCoroutineContext().job
                        val result = withContext(NonCancellable) { response.await() }
                        returned = true
                        cancelled = request.isCancelled
                        result
                    }
                    library == LIBRARY_A -> tree.getValue(record)
                    record == otherCollection.record -> listOf(otherParent)
                    record == otherParent.record -> listOf(otherLeaf)
                    else -> error("Unexpected synthetic request: $library/$record")
                }
            })
            try {
                fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
                openAndRun(fixture.navigator, collection, parent, leaf)
                assertTrue(fixture.navigator.state.loading)
                assertFalse(returned)
                if (switchLibrary) {
                    // Reused record IDs must not allow a result from the old library to win.
                    fixture.navigator.setLibrary(LIBRARY_B, listOf(otherCollection))
                    openAndRun(fixture.navigator, otherCollection, otherParent)
                    assertEquals(BrowseState(LIBRARY_B, listOf(otherCollection, otherParent), listOf(otherLeaf)), fixture.navigator.state)
                } else {
                    fixture.navigator.back()
                    runCurrent()
                    assertEquals(BrowseState(LIBRARY_A, listOf(collection, parent), entries), fixture.navigator.state)
                }
                val ready = fixture.navigator.state
                val changes = fixture.changes.toList()
                val calls = fixture.calls.toList()
                response.complete(emptyList())
                runCurrent()

                assertTrue("The stale loader must actually return its empty result", returned)
                assertTrue("The obsolete request must have been cancelled", cancelled)
                assertSame(ready, fixture.navigator.state)
                assertEquals(changes, fixture.changes)
                assertEquals(calls, fixture.calls)
                assertTrue(fixture.reads.isEmpty())
                assertTrue(fixture.readParents.isEmpty())
            } finally {
                response.complete(emptyList())
                fixture.navigator.close()
                runCurrent()
            }
        }
    }

    @Test
    fun duplicateTapsWhileLeafChildrenLoadProduceOneRequestAndOneReaderCallback() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<List<ContentNode>>()
        val fixture = Fixture(this, childLoader = { _, record ->
            if (record == leaf.record) response.await() else tree.getValue(record)
        })
        try {
            fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
            openAndRun(fixture.navigator, collection, parent)
            val ready = fixture.navigator.state
            val calls = fixture.calls.toList()
            fixture.navigator.open(leaf.record)
            val loading = fixture.navigator.state
            val changes = fixture.changes.toList()
            assertEquals(BrowseState(LIBRARY_A, ready.path + leaf, loading = true), loading)

            // First before the queued loader runs, then while its deferred result is pending.
            repeat(2) {
                fixture.navigator.open(leaf.record)
                fixture.navigator.open(sibling.record)
                fixture.navigator.retry()
                assertNull(fixture.navigator.readingTarget(leaf.record))
                runCurrent()
                assertSame(loading, fixture.navigator.state)
                assertEquals(changes, fixture.changes)
                assertEquals(calls + (LIBRARY_A to leaf.record), fixture.calls)
                assertTrue(fixture.reads.isEmpty())
            }
            response.complete(emptyList())
            runCurrent()
            assertSame(ready, fixture.navigator.state)
            assertEquals(changes + ready, fixture.changes)
            assertEquals(listOf(LIBRARY_A to leaf), fixture.reads)
            assertSame(ready, fixture.readParents.single())
            assertEquals(calls + (LIBRARY_A to leaf.record), fixture.calls)
            runCurrent()
            assertEquals(1, fixture.reads.size)
        } finally {
            response.complete(emptyList())
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun omittedLeafCallbackPreservesGenericEmptyContentsAndBackNavigation() = runTest(StandardTestDispatcher()) {
        val calls = mutableListOf<Pair<String, Int>>()
        val changes = mutableListOf<BrowseState>()
        // Omit onReadLeaf entirely to verify the public default, rather than pass a no-op callback.
        val navigator = BrowseNavigator(
            scope = this,
            loadChildren = { library, record ->
                calls += library to record
                tree.getValue(record)
            },
            onChange = { changes += it },
        )
        navigator.setLibrary(LIBRARY_A, listOf(collection))
        openAndRun(navigator, collection, parent, leaf)

        val ready = BrowseState(LIBRARY_A, listOf(collection, parent, leaf))
        assertEquals(ready, navigator.state)
        assertEquals(ready, changes.last())
        assertFalse(navigator.state.loading)
        assertFalse(navigator.state.failed)
        assertNull(navigator.readingTarget(leaf.record))
        assertEquals(listOf(collection, parent, leaf).map { LIBRARY_A to it.record }, calls)
        navigator.back()
        runCurrent()
        assertEquals(BrowseState(LIBRARY_A, listOf(collection, parent), entries), navigator.state)
        assertEquals(listOf(collection, parent, leaf, parent).map { LIBRARY_A to it.record }, calls)
    }

    @Test
    fun containerWithOnlyTextSubsectionsOpensAsOneReadingUnit() = runTest(StandardTestDispatcher()) {
        val textSection = ContentNode(211, parent.record, "SYNONYMS", 211, 0, 4)
        val text = ContentNode(212, parent.record, "TEXT", 212, 0, 5)
        val fixture = Fixture(
            this,
            childLoader = { _, record -> if (record == parent.record) listOf(textSection, text) else tree.getValue(record) },
            levelNamesLoader = { mapOf(2 to "LE_Chapter", 4 to "LE_Verse_Section", 5 to "LE_Verse_Text") },
        )
        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
        openAndRun(fixture.navigator, collection)
        val ready = fixture.navigator.state

        fixture.navigator.open(parent.record)
        runCurrent()

        assertSame(ready, fixture.navigator.state)
        assertEquals(listOf(LIBRARY_A to parent), fixture.reads)
        assertSame(ready, fixture.readParents.single())
        assertTrue(fixture.changes.none { it.current == parent && !it.loading })
    }

    @Test
    fun chapterPreviewReadingTargetsStayDirectAndUnchangedWhenLeafReadingIsEnabled() = runTest(StandardTestDispatcher()) {
        val notes = ContentNode(600, parent.record, "Synthetic notes", 609, 0, 3)
        val chapterEntries = entries + notes
        val overview = ChapterOverview(parent, entries.map { VersePreview(it, "Synthetic translation ${it.record}") })
        val fixture = Fixture(
            scope = this,
            childLoader = { _, record ->
                when (record) {
                    parent.record -> chapterEntries
                    notes.record -> emptyList()
                    else -> tree.getValue(record)
                }
            },
            overviewLoader = { _, record, children ->
                if (record == parent.record) {
                    assertEquals(chapterEntries, children)
                    overview
                } else null
            },
        )
        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
        openAndRun(fixture.navigator, collection, parent)
        val ready = fixture.navigator.state
        val changes = fixture.changes.toList()
        val calls = fixture.calls.toList()
        val overviewCalls = fixture.overviewCalls.toList()

        for (preview in overview.verses) {
            assertSame(preview.node, fixture.navigator.readingTarget(preview.node.record))
        }
        for (record in listOf(notes.record, collection.record, parent.record, subsection.record, Int.MAX_VALUE)) {
            assertNull(fixture.navigator.readingTarget(record))
        }
        runCurrent()
        assertSame(ready, fixture.navigator.state)
        assertEquals(changes, fixture.changes)
        assertEquals(calls, fixture.calls)
        assertEquals(overviewCalls, fixture.overviewCalls)
        assertTrue(fixture.reads.isEmpty()) // Preview taps use readingTarget, never the generic leaf callback.

        openAndRun(fixture.navigator, notes)
        assertEquals(listOf(LIBRARY_A to notes), fixture.reads)
        assertSame(ready, fixture.navigator.state)
        assertSame(ready, fixture.readParents.single())
        assertSame(overview, fixture.navigator.state.chapter)
        assertEquals(listOf(collection, parent), fixture.navigator.state.path)
        assertSame(leaf, fixture.navigator.readingTarget(leaf.record))
        assertEquals(calls + (LIBRARY_A to notes.record), fixture.calls)
        assertEquals(overviewCalls, fixture.overviewCalls)
    }

    private fun TestScope.openAndRun(navigator: BrowseNavigator, vararg nodes: ContentNode) {
        for (node in nodes) {
            navigator.open(node.record)
            runCurrent()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        childLoader: suspend (String, Int) -> List<ContentNode> = { _, record -> tree.getValue(record) },
        overviewLoader: suspend (String, Int, List<ContentNode>) -> ChapterOverview? = { _, _, _ -> null },
        levelNamesLoader: suspend (String) -> Map<Int, String> = { emptyMap() },
    ) {
        val calls = mutableListOf<Pair<String, Int>>()
        val overviewCalls = mutableListOf<Pair<String, Int>>()
        val changes = mutableListOf<BrowseState>()
        val reads = mutableListOf<Pair<String, ContentNode>>()
        val readParents = mutableListOf<BrowseState>()
        val navigator = BrowseNavigator(
            scope = scope,
            loadChildren = { library, record ->
                calls += library to record
                childLoader(library, record)
            },
            onChange = { changes += it },
            loadOverview = { library, record, children ->
                overviewCalls += library to record
                overviewLoader(library, record, children)
            },
            onReadLeaf = { library, node ->
                reads += library to node
                readParents += changes.last()
            },
            loadLevelNames = levelNamesLoader,
        )
    }

    private companion object {
        const val LIBRARY_A = "synthetic-leaf-a"
        const val LIBRARY_B = "synthetic-leaf-b"
        val collection = ContentNode(100, 0, "Synthetic collection", 1999, 0, 1)
        val parent = ContentNode(200, 100, "Synthetic parent", 999, 0, 2)
        val leaf = ContentNode(210, 200, "Synthetic verse-like leaf", 219, 99, 3)
        val sibling = ContentNode(230, 200, "Synthetic sibling", 239, 0, 3)
        val subsection = ContentNode(231, 230, "Synthetic subsection", 231, 0, 4)
        val entries = listOf(leaf, sibling)
        val tree = mapOf(
            collection.record to listOf(parent),
            parent.record to entries,
            leaf.record to emptyList(),
            sibling.record to listOf(subsection),
            subsection.record to emptyList(),
        )
    }
}