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

/** Chapter navigation contracts using synthetic nodes only; no Android runtime or database. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBrowseTest {
    @Test
    fun omittedOverviewLoaderPreservesGenericNavigationEvenForVerseLikeTitles() = runTest(StandardTestDispatcher()) {
        val calls = mutableListOf<Pair<String, Int>>()
        // Deliberately omit loadOverview, rather than supply a loader returning null.
        val navigator = BrowseNavigator(
            scope = this,
            loadChildren = { library, parent ->
                calls += library to parent
                tree.getValue(parent)
            },
            onChange = {},
        )
        navigator.setLibrary(LIBRARY_A, listOf(collection))
        for (node in listOf(collection, chapter)) {
            navigator.open(node.record)
            runCurrent()
        }
        assertEquals(BrowseState(LIBRARY_A, listOf(collection, chapter), entries), navigator.state)
        assertNull(navigator.readingTarget(first.record))

        navigator.open(first.record)
        runCurrent()
        assertEquals(
            BrowseState(LIBRARY_A, listOf(collection, chapter, first), listOf(subsection)),
            navigator.state,
        )
        assertEquals(listOf(collection, chapter, first).map { LIBRARY_A to it.record }, calls)
    }

    @Test
    fun overviewAndChildrenPublishAtomicallyAndLoadingRejectsOpenReadAndRetry() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<ChapterOverview>()
        val fixture = Fixture(this, overviewLoader = { library, parent, children ->
            if (parent == chapter.record) {
                assertEquals(LIBRARY_A, library)
                assertSame(entries, children)
                response.await()
            } else null
        })
        try {
            openChapter(fixture)
            val loading = BrowseState(LIBRARY_A, listOf(collection, chapter), loading = true)
            fixture.assertState(loading)
            val changes = fixture.changes.toList()
            val calls = fixture.childCalls.toList()
            repeat(2) {
                assertNull(fixture.navigator.readingTarget(first.record))
                fixture.navigator.open(first.record)
                fixture.navigator.open(notes.record)
                fixture.navigator.retry()
                runCurrent()
            }
            assertEquals(changes, fixture.changes)
            assertEquals(calls, fixture.childCalls)
            assertEquals(listOf(collection, chapter).map { LIBRARY_A to it.record }, fixture.overviewCalls)
            assertTrue(fixture.changes.filter { it.current == chapter }.all {
                it.loading && it.entries.isEmpty() && it.chapter == null
            })

            response.complete(overview)
            runCurrent()

            val ready = BrowseState(LIBRARY_A, listOf(collection, chapter), entries, chapter = overview)
            fixture.assertState(ready)
            assertEquals(changes + ready, fixture.changes)
            assertEquals(listOf(loading, ready), fixture.changes.filter { it.current == chapter })
        } finally {
            response.complete(overview)
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun readingTargetReturnsOnlyVerifiedDirectPreviewNodesWithoutPushingOrReloading() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        assertNull(fixture.navigator.readingTarget(first.record))
        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
        assertNull(fixture.navigator.readingTarget(first.record))
        fixture.navigator.open(collection.record)
        runCurrent()
        assertNull(fixture.navigator.readingTarget(chapter.record))
        fixture.navigator.open(chapter.record)
        runCurrent()
        val ready = fixture.navigator.state
        val changes = fixture.changes.toList()
        val calls = fixture.childCalls.toList()
        val overviewCalls = fixture.overviewCalls.toList()

        for (preview in overview.verses) {
            assertSame(preview.node, fixture.navigator.readingTarget(preview.node.record))
        }
        // A direct generic child is not a reading target just because its title resembles a verse.
        for (record in listOf(0, Int.MAX_VALUE, collection.record, chapter.record, notes.record, subsection.record)) {
            assertNull("Not a verified chapter preview: $record", fixture.navigator.readingTarget(record))
        }
        runCurrent()
        assertSame(ready, fixture.navigator.state)
        assertEquals(listOf(collection, chapter), fixture.navigator.state.path)
        assertEquals(changes, fixture.changes)
        assertEquals(calls, fixture.childCalls)
        assertEquals(overviewCalls, fixture.overviewCalls)

        // A once-valid row callback must become inert as soon as the chapter is left,
        // both while its parent is loading and after the parent's children arrive.
        fixture.navigator.back()
        repeat(2) {
            val parentState = fixture.navigator.state
            val parentChanges = fixture.changes.toList()
            assertNull(fixture.navigator.readingTarget(first.record))
            fixture.navigator.open(first.record)
            assertSame(parentState, fixture.navigator.state)
            assertEquals(parentChanges, fixture.changes)
            runCurrent()
        }
        fixture.assertState(BrowseState(LIBRARY_A, listOf(collection), listOf(chapter)))
        assertEquals(listOf(collection, chapter, collection).map { LIBRARY_A to it.record }, fixture.childCalls)
    }

    @Test
    fun combinedDuplicateAndBlankLabelsRemainDistinctRecordBasedReadingTargets() = runTest(StandardTestDispatcher()) {
        val fixture = Fixture(this)
        openChapter(fixture)
        val loaded = requireNotNull(fixture.navigator.state.chapter)

        assertEquals(previews, loaded.verses)
        assertEquals(1, loaded.verses.count { it.node.record == combined.record })
        assertEquals(combined, fixture.navigator.readingTarget(combined.record))
        assertEquals(duplicateA.title, duplicateB.title)
        assertEquals(duplicateA, fixture.navigator.readingTarget(duplicateA.record))
        assertEquals(duplicateB, fixture.navigator.readingTarget(duplicateB.record))
        assertTrue(blank.title.isBlank())
        assertEquals(blank, fixture.navigator.readingTarget(blank.record))
        assertNull(loaded.verses.single { it.node == blank }.translation)
        assertEquals(listOf(collection, chapter), fixture.navigator.state.path)
        assertEquals(listOf(collection, chapter).map { LIBRARY_A to it.record }, fixture.childCalls)
    }

    @Test
    fun foreignDuplicateEmptyAndWrongChapterOverviewsFailWithoutPartialPublication() = runTest(StandardTestDispatcher()) {
        val invalid = listOf(
            "foreign node" to overview.copy(verses = listOf(previews.first(), VersePreview(subsection, "Synthetic foreign text"))),
            "same ID but altered node" to overview.copy(verses = listOf(VersePreview(first.copy(title = "Altered"), "Synthetic text"))),
            "duplicate record" to overview.copy(verses = listOf(previews.first(), previews.first().copy(translation = "Other synthetic text"))),
            "wrong chapter" to overview.copy(chapter = collection),
            "same chapter ID but altered node" to overview.copy(chapter = chapter.copy(title = "Other chapter")),
            "empty overview" to overview.copy(verses = emptyList()),
        )
        for ((reason, malformed) in invalid) {
            val fixture = Fixture(this, overviewLoader = { _, parent, _ ->
                if (parent == chapter.record) malformed else null
            })
            openChapter(fixture)
            fixture.assertState(BrowseState(LIBRARY_A, listOf(collection, chapter), failed = true))
            assertTrue(reason, fixture.changes.filter { it.current == chapter }.all {
                it.entries.isEmpty() && it.chapter == null && (it.loading || it.failed)
            })
            val changes = fixture.changes.toList()
            assertNull(reason, fixture.navigator.readingTarget(first.record))
            fixture.navigator.open(first.record)
            runCurrent()
            assertEquals(reason, changes, fixture.changes)
            fixture.navigator.back()
            runCurrent()
            fixture.assertState(BrowseState(LIBRARY_A, listOf(collection), listOf(chapter)))
            fixture.navigator.close()
        }
    }

    @Test
    fun backDuringNonCancellableOverviewIgnoresTheLateResult() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<ChapterOverview>()
        var returned = false
        var cancelled = false
        val fixture = Fixture(this, overviewLoader = { _, parent, _ ->
            if (parent == chapter.record) {
                val request = currentCoroutineContext().job
                val result = withContext(NonCancellable) { response.await() }
                returned = true
                cancelled = request.isCancelled
                result
            } else null
        })
        try {
            openChapter(fixture)
            assertTrue(fixture.navigator.state.loading)
            fixture.navigator.back()
            runCurrent()
            val expected = BrowseState(LIBRARY_A, listOf(collection), listOf(chapter))
            fixture.assertState(expected)
            val changes = fixture.changes.toList()

            response.complete(overview)
            runCurrent()

            assertTrue("The stale loader must actually return", returned)
            assertTrue(cancelled)
            fixture.assertState(expected)
            assertEquals(changes, fixture.changes)
            assertNull(fixture.navigator.readingTarget(first.record))
            assertEquals(listOf(collection, chapter, collection).map { LIBRARY_A to it.record }, fixture.childCalls)
        } finally {
            response.complete(overview)
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun librarySwitchWithReusedRecordIdsIgnoresTheOldAsyncOverview() = runTest(StandardTestDispatcher()) {
        val newCollection = collection.copy(title = "Other synthetic collection")
        val newChapter = chapter.copy(title = "Other synthetic chapter")
        val newEntries = entries.map { it.copy(title = "Other source ${it.record}") }
        val newOverview = ChapterOverview(newChapter, newEntries.take(previews.size).map {
            VersePreview(it, "Other synthetic translation ${it.record}")
        })
        val response = CompletableDeferred<ChapterOverview>()
        var oldReturned = false
        val fixture = Fixture(
            this,
            childLoader = { library, parent ->
                if (library == LIBRARY_A) tree.getValue(parent) else when (parent) {
                    newCollection.record -> listOf(newChapter)
                    newChapter.record -> newEntries
                    else -> error("Unexpected synthetic parent $parent")
                }
            },
            overviewLoader = { library, parent, _ ->
                when {
                    parent != chapter.record -> null
                    library == LIBRARY_A -> {
                        val result = withContext(NonCancellable) { response.await() }
                        oldReturned = true
                        result
                    }
                    else -> newOverview
                }
            },
        )
        try {
            openChapter(fixture)
            assertTrue(fixture.navigator.state.loading)
            fixture.navigator.setLibrary(LIBRARY_B, listOf(newCollection))
            fixture.assertState(BrowseState(libraryId = LIBRARY_B))
            assertNull(fixture.navigator.readingTarget(first.record))
            for (node in listOf(newCollection, newChapter)) {
                fixture.navigator.open(node.record)
                runCurrent()
            }
            val expected = BrowseState(LIBRARY_B, listOf(newCollection, newChapter), newEntries, chapter = newOverview)
            fixture.assertState(expected)
            val changes = fixture.changes.toList()

            response.complete(overview)
            runCurrent()

            assertTrue(oldReturned)
            fixture.assertState(expected)
            assertEquals(changes, fixture.changes)
            assertEquals(newEntries.first(), fixture.navigator.readingTarget(first.record))
            assertFalse(fixture.navigator.readingTarget(first.record) == first)
            assertEquals(
                listOf(LIBRARY_A to collection.record, LIBRARY_A to chapter.record,
                    LIBRARY_B to collection.record, LIBRARY_B to chapter.record),
                fixture.childCalls,
            )
        } finally {
            response.complete(overview)
            fixture.navigator.close()
            runCurrent()
        }
    }

    @Test
    fun overviewFailureGuardsActionsAndRetryReloadsTheSameChapterExactlyOnce() = runTest(StandardTestDispatcher()) {
        var attempts = 0
        val fixture = Fixture(this, overviewLoader = { _, parent, _ ->
            if (parent == chapter.record) {
                if (++attempts == 1) error("Synthetic overview failure after children loaded")
                overview
            } else null
        })
        openChapter(fixture)
        fixture.assertState(BrowseState(LIBRARY_A, listOf(collection, chapter), failed = true))
        val changes = fixture.changes.toList()
        for (record in entries.map { it.record }) {
            assertNull(fixture.navigator.readingTarget(record))
            fixture.navigator.open(record)
        }
        runCurrent()
        assertEquals(changes, fixture.changes)
        assertEquals(1, attempts)

        fixture.navigator.retry()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(collection, chapter), loading = true))
        fixture.navigator.retry()
        fixture.navigator.open(first.record)
        assertNull(fixture.navigator.readingTarget(first.record))
        runCurrent()
        fixture.assertState(BrowseState(LIBRARY_A, listOf(collection, chapter), entries, chapter = overview))
        assertEquals(2, attempts)
        assertEquals(first, fixture.navigator.readingTarget(first.record))
        val readyChanges = fixture.changes.toList()
        fixture.navigator.retry()
        runCurrent()
        assertEquals(readyChanges, fixture.changes)
        assertEquals(listOf(collection, chapter, chapter).map { LIBRARY_A to it.record }, fixture.childCalls)
        assertEquals(fixture.childCalls, fixture.overviewCalls)
    }

    private fun TestScope.openChapter(fixture: Fixture) {
        fixture.navigator.setLibrary(LIBRARY_A, listOf(collection))
        for (node in listOf(collection, chapter)) {
            fixture.navigator.open(node.record)
            runCurrent()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        childLoader: suspend (String, Int) -> List<ContentNode> = { _, parent -> tree.getValue(parent) },
        overviewLoader: suspend (String, Int, List<ContentNode>) -> ChapterOverview? = { _, parent, _ ->
            if (parent == chapter.record) overview else null
        },
    ) {
        val childCalls = mutableListOf<Pair<String, Int>>()
        val overviewCalls = mutableListOf<Pair<String, Int>>()
        val changes = mutableListOf<BrowseState>()
        val navigator = BrowseNavigator(
            scope = scope,
            loadChildren = { library, parent ->
                childCalls += library to parent
                childLoader(library, parent)
            },
            onChange = { changes += it },
            loadOverview = { library, parent, children ->
                overviewCalls += library to parent
                overviewLoader(library, parent, children)
            },
        )

        fun assertState(expected: BrowseState) {
            assertEquals(expected, navigator.state)
            assertEquals(expected, changes.last())
        }
    }

    private companion object {
        const val LIBRARY_A = "synthetic-chapter-a"
        const val LIBRARY_B = "synthetic-chapter-b"
        val collection = ContentNode(100, 0, "Synthetic collection", 1999, 1, 1)
        val chapter = ContentNode(200, 100, "Synthetic chapter", 999, 6, 2)
        val first = ContentNode(210, 200, "Verse 1.1", 219, 4, 3)
        val combined = ContentNode(230, 200, "Verses 1.2–3", 249, 4, 3)
        val duplicateA = ContentNode(260, 200, "Shared verse", 269, 4, 3)
        val duplicateB = ContentNode(280, 200, "Shared verse", 289, 4, 3)
        val blank = ContentNode(300, 200, "   ", 309, 4, 3)
        val notes = ContentNode(800, 200, "Verse 1.9 (generic notes)", 809, 0, 3)
        val subsection = ContentNode(211, 210, "Synthetic subsection", 211, 0, 4)
        val previews = listOf(first, combined, duplicateA, duplicateB, blank).map {
            VersePreview(it, if (it == blank) null else "Synthetic translation ${it.record}: नमूना বাংলা ā a\u0304.")
        }
        val entries = previews.map { it.node } + notes
        val overview = ChapterOverview(chapter, previews)
        val tree = mapOf(
            collection.record to listOf(chapter),
            chapter.record to entries,
            first.record to listOf(subsection),
        )
    }
}