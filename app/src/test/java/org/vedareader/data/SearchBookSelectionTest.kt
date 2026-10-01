package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.SearchBookSelection.Check

class SearchBookSelectionTest {
    private val root = book(100, 0, 999, "Collection", 1)
    private val branch = book(110, 100, 399, "First work", 4)
    // Parent 115 is an omitted chapter/canto, NOT another selectable catalog entry.
    private val leaf = book(120, 115, 199, "Kṛṣṇa", 8)
    private val sibling = book(220, 110, 299, "Other nested work", 8)
    private val otherBranch = book(500, 100, 899, "Second work", 4)
    private val separate = book(1100, 0, 1199, "Separate", 1)
    private val books = listOf(root, branch, leaf, sibling, otherBranch, separate)
    private val tree = SearchBookSelection(books)

    @Test
    fun deduplicatedCatalogUsesNearestContainingParentAndActualTreeDepth() {
        val index = SearchBookSelection(books.reversed() + leaf)
        assertEquals(listOf(100, 110, 120, 220, 500, 1100), index.entries.map { it.record })
        assertEquals(listOf(1, 2, 3, 3, 2, 1), index.entries.map { it.depth })
        assertEquals(listOf(100, 1100), index.roots)
        assertEquals(listOf(110, 500), index.children(100))
        assertEquals(listOf(120, 220), index.children(110))
        assertEquals(listOf(110, 100), index.ancestors(120))
        assertEquals(110, index.entry(120)?.parentRecord)
        assertFalse(index.isKnown(115))
    }

    @Test
    fun narrowerRangeWinsEvenWhenStoredParentSkipsASelectableContainer() {
        val index = SearchBookSelection(books.map {
            if (it.node.record == 120) it.copy(node = it.node.copy(parent = 100)) else it
        })
        assertEquals(listOf(110, 100), index.ancestors(120))
    }

    @Test
    fun unrelatedStoredParentIsIgnoredAndInclusiveEndStillContainsItsLastChild() {
        val index = SearchBookSelection(listOf(root, book(900, 1100, 999), separate))
        assertEquals(listOf(100), index.ancestors(900))
        assertEquals(listOf(900), index.children(100))
        assertTrue(index.children(1100).isEmpty())
    }

    @Test
    fun identicalStartsSelfParentsAndInvalidRangesCannotCreateCycles() {
        val index = SearchBookSelection(listOf(root.copy(node = root.node.copy(parent = 100)),
            root.copy(node = root.node.copy(title = "Duplicate")), book(110, 110, 109), separate))
        assertEquals(3, index.entries.size)
        assertEquals(listOf(100, 110, 1100), index.roots)
        assertTrue(index.entries.all { it.depth == 1 })
    }

    @Test
    fun equalEndsAndPointRangesNestWhileInvalidRangesLeaveTheActiveParentIntact() {
        val index = SearchBookSelection(listOf(
            book(10, 0, 100), book(20, 10, 80), book(30, 10, 29),
            book(40, 10, 80), book(80, 10, 80), book(81, 20, 100),
            book(100, 0, 100), book(101, 100, 101),
        ).reversed())
        assertEquals(listOf(10, 30, 101), index.roots)
        assertEquals(listOf(20, 81), index.children(10))
        assertEquals(listOf(40, 20, 10), index.ancestors(80))
        assertEquals(listOf(81, 10), index.ancestors(100))
        assertEquals(listOf(10, 20, 40, 80, 81, 100, 30, 101), index.entries.map { it.record })
        assertTrue(index.ancestors(30).isEmpty())
        assertTrue(index.ancestors(101).isEmpty())
    }

    @Test
    fun duplicateRecordsKeepTheFirstRangeAndDoNotInventCrossings() {
        val first = book(20, 10, 40, "First")
        val index = SearchBookSelection(listOf(book(10, 0, 100), first,
            book(20, 0, 200, "Ignored duplicate"), book(30, 0, 40)))
        assertEquals(first, index.entry(20)?.book)
        assertEquals(listOf(20, 10), index.ancestors(30))
        assertEquals(listOf(10), index.roots)
    }

    @Test
    fun crossingCandidatesUseSpanThenDirectParentThenLatestRecord() {
        // Equal spans, neither container contains the other. A direct parent only wins a tie.
        val index = SearchBookSelection(listOf(
            book(10, 0, 110), book(20, 0, 120),
            book(30, 10, 35), book(40, 20, 45), book(50, 0, 55),
            book(60, 0, 90), book(70, 10, 75),
        ).reversed())
        assertEquals(listOf(10, 20), index.roots)
        assertEquals(10, index.entry(30)?.parentRecord)
        assertEquals(20, index.entry(40)?.parentRecord)
        assertEquals(20, index.entry(50)?.parentRecord)
        assertEquals(60, index.entry(70)?.parentRecord)
        assertEquals(listOf(60, 20), index.ancestors(70))
        assertEquals(listOf(10, 30, 20, 40, 50, 60, 70), index.entries.map { it.record })
    }

    @Test
    fun crossingFallbackKeepsEarlierCandidatesAfterAStackWouldHavePoppedThem() {
        val index = SearchBookSelection(listOf(
            book(10, 0, 500), book(20, 10, 100), book(30, 20, 40),
            book(50, 10, 200), book(60, 50, 70), book(100, 0, 100), book(101, 0, 110),
        ))
        assertEquals(10, index.entry(50)?.parentRecord)
        // 20 is narrower than the later crossing range 50, even with a direct link to 50.
        assertEquals(20, index.entry(60)?.parentRecord)
        assertEquals(20, index.entry(100)?.parentRecord)
        assertEquals(50, index.entry(101)?.parentRecord)
    }

    @Test
    fun inclusiveTouchingRangesAreCrossingsAndSpanComparisonDoesNotOverflow() {
        val touching = SearchBookSelection(listOf(book(10, 0, 20), book(20, 10, 30), book(21, 10, 21)))
        assertEquals(listOf(10, 20), touching.roots)
        assertEquals(20, touching.entry(21)?.parentRecord)

        val wide = SearchBookSelection(listOf(
            book(Int.MIN_VALUE, 0, 10), book(-10, 0, 100), book(0, Int.MIN_VALUE, 1),
        ))
        // The first span exceeds Int.MAX_VALUE; wrapping it would wrongly beat the narrower parent.
        assertEquals(-10, wide.entry(0)?.parentRecord)
    }

    @Test
    fun fourThousandNestedRangesHaveEveryExpectedParentAndDepthWithoutRecursiveTraversal() {
        val count = 4096
        // Short labels keep this about hierarchy, not the size of cached ancestor filter text.
        val index = SearchBookSelection((1..count).map { book(it, 0, count, "") }.reversed())
        assertEquals(listOf(1), index.roots)
        assertEquals((1..count).toList(), index.entries.map { it.record })
        index.entries.forEach { entry ->
            assertEquals(entry.record, entry.depth)
            assertEquals(if (entry.record == 1) null else entry.record - 1, entry.parentRecord)
            assertEquals(if (entry.record == count) emptyList() else listOf(entry.record + 1),
                index.children(entry.record))
        }
        assertEquals((count - 1 downTo 1).toList(), index.ancestors(count))
        assertEquals(listOf(1), index.visible("", emptySet()).map { it.record })
        assertEquals(setOf(1), index.canonical(setOf(1, count)))
        val states = index.checks(setOf(count))
        assertEquals(Check.PARTIAL, states[1])
        assertEquals(Check.ON, states[count])
    }

    @Test
    fun thousandsOfDisjointBranchesPopCompletedRangesAndKeepSourceOrder() {
        val branchCount = 2047
        val catalog = listOf(book(1, 0, 100_000)) + (0 until branchCount).flatMap { offset ->
            val record = 10 + offset * 4
            listOf(book(record, 0, record + 2), book(record + 1, 1, record + 2))
        } + book(100_001, 1, 100_001)
        assertEquals(4096, catalog.size)
        val index = SearchBookSelection(catalog.reversed())
        assertEquals(catalog.map { it.node.record }, index.entries.map { it.record })
        assertEquals(listOf(1, 100_001), index.roots)
        assertEquals((0 until branchCount).map { 10 + it * 4 }, index.children(1))
        (0 until branchCount).forEach { offset ->
            val record = 10 + offset * 4
            assertEquals(1, index.entry(record)?.parentRecord)
            assertEquals(record, index.entry(record + 1)?.parentRecord)
            assertEquals(listOf(record, 1), index.ancestors(record + 1))
            assertEquals(3, index.entry(record + 1)?.depth)
        }
    }

    @Test
    fun generatedCatalogsAgreeWithOriginalParentSelectionIncludingMalformedRanges() {
        val random = kotlin.random.Random(73)
        repeat(64) {
            val catalog = (1..32).map { record ->
                book(record, random.nextInt(0, 34), random.nextInt(0, 40))
            }.shuffled(random)
            assertOriginalParents(catalog + catalog.take(4).map { it.copy(node = it.node.copy(end = 100)) })
        }
        // Systematically exercise the fast path too, including duplicate, equal-end and invalid ranges.
        repeat(64) { iteration ->
            val catalog = (1..32).map { record ->
                val blockEnd = ((record - 1) / 8 + 1) * 8
                val end = if ((record + iteration) % 7 == 0) record - 1 else blockEnd
                book(record, random.nextInt(0, 34), end)
            }.shuffled(random)
            assertOriginalParents(catalog + catalog.take(4))
        }
    }

    @Test
    fun selectingParentChecksEveryHiddenDescendantAndStoresOnlyOneRoot() {
        val selected = tree.toggle(setOf(120, 220), 100).roots
        assertEquals(setOf(100), selected)
        val states = tree.checks(selected)
        listOf(100, 110, 120, 220, 500).forEach { assertEquals(Check.ON, states[it]) }
        assertEquals(Check.OFF, states[1100])
        assertEquals(listOf(100, 1100), tree.visible("", emptySet()).map { it.record })
        assertEquals(setOf(100), tree.canonical(setOf(100, 110, 120, 220, 500)))
    }

    @Test
    fun selectedDescendantsMakeEveryAncestorPartialWithoutSelectingItsOwnProse() {
        assertEquals(Check.PARTIAL, tree.checks(setOf(120))[110])
        assertEquals(Check.PARTIAL, tree.checks(setOf(120))[100])
        assertEquals(Check.OFF, tree.checks(setOf(120))[220])
        // Every catalog child is selected, but the parent itself is deliberately NOT added.
        assertEquals(setOf(120, 220), tree.canonical(setOf(120, 220)))
        assertEquals(Check.PARTIAL, tree.checks(setOf(120, 220))[110])
        assertEquals(setOf(110, 500), tree.canonical(setOf(110, 500)))
        assertEquals(Check.PARTIAL, tree.checks(setOf(110, 500))[100])
    }

    @Test
    fun selectingPartialParentReplacesDescendantRootsButRetainsUnrelatedSelection() {
        assertEquals(setOf(110, 1100), tree.toggle(setOf(120, 220, 1100), 110).roots)
    }

    @Test
    fun excludingNestedChildReplacesAllEnclosingSelectionsWithSiblingBranches() {
        val changed = tree.toggle(setOf(100, 120, 1100), 120)
        assertEquals(setOf(220, 500, 1100), changed.roots)
        assertEquals(setOf(100, 110), changed.excludedParentRecords)
        val states = tree.checks(changed.roots)
        assertEquals(Check.OFF, states[120])
        assertEquals(Check.PARTIAL, states[110])
        assertEquals(Check.PARTIAL, states[100])
        assertEquals(Check.ON, states[220])
        // Verify the actual ranges the engine will receive, not just checkbox appearances.
        assertTrue(changed.roots.none { id -> tree.entry(id)!!.book.node.let { it.record <= 199 && it.end >= 120 } })
    }

    @Test
    fun excludingWholeChildBranchDropsItsDescendantsAndParentOwnProse() {
        val changed = tree.toggle(setOf(100), 110)
        assertEquals(setOf(500), changed.roots)
        assertEquals(setOf(100), changed.excludedParentRecords)
        listOf(110, 120, 220).forEach { assertEquals(Check.OFF, tree.checks(changed.roots)[it]) }
    }

    @Test
    fun excludingOnlyChildLeavesExplicitlyEmptySelectionNeverAll() {
        val index = SearchBookSelection(listOf(root, branch, leaf))
        val changed = index.toggle(setOf(100), 120)
        assertTrue(changed.roots.isEmpty())
        assertEquals(setOf(100, 110), changed.excludedParentRecords)
        assertTrue(index.checks(changed.roots).values.all { it == Check.OFF })
        assertTrue(index.toggle(setOf(120), 120).excludedParentRecords.isEmpty())
    }

    @Test
    fun reselectingExcludedChildDoesNotPretendTheParentsOwnProseWasRecovered() {
        val excluded = tree.toggle(setOf(100), 120).roots
        val restoredChild = tree.toggle(excluded, 120).roots
        assertEquals(setOf(120, 220, 500), restoredChild)
        assertEquals(Check.PARTIAL, tree.checks(restoredChild)[100])
        assertEquals(setOf(100), tree.toggle(restoredChild, 100).roots)
    }

    @Test
    fun filterRevealsAncestorsAndMatchingChildrenWithoutMutatingExpansionOrSelection() {
        val collapsed = emptySet<Int>()
        assertEquals(listOf(100, 110, 120), tree.visible("KRSNA", collapsed).map { it.record })
        assertEquals(listOf(100, 110, 120, 220), tree.visible("First work", collapsed).map { it.record })
        assertEquals(listOf(100, 1100), tree.visible("", collapsed).map { it.record })
        assertTrue(tree.visible("no match", setOf(100, 110)).isEmpty())
        assertEquals(listOf(100, 110, 120, 220, 500), tree.visible("Collection", collapsed).map { it.record })
        assertEquals(setOf(100), tree.toggle(emptySet(), 100).roots)
    }

    @Test
    fun expandingOneBranchDoesNotExpandItsSiblingOrForgetOtherBranchChoices() {
        assertEquals(listOf(100, 110, 500, 1100), tree.visible("", setOf(100)).map { it.record })
        assertEquals(listOf(100, 110, 120, 220, 500, 1100), tree.visible("", setOf(100, 110)).map { it.record })
        assertEquals(listOf(100, 1100), tree.visible("", setOf(110)).map { it.record })
    }

    @Test
    fun unknownCommittedIdsArePreservedForValidationRatherThanBecomingAllDatabase() {
        assertEquals(setOf(9999), tree.canonical(setOf(9999)))
        assertFalse(tree.isKnown(9999))
        assertTrue(SearchBookSelection(emptyList()).entries.isEmpty())
    }

    @Test
    fun malformedOverlappingSiblingCannotMaskAnExcludedChild() {
        val crossing = book(180, 100, 450, "Crossing branch")
        val index = SearchBookSelection(books + crossing)
        val changed = index.toggle(setOf(100), 120)
        assertFalse(180 in changed.roots)
        assertEquals(setOf(220, 500), changed.roots)
        assertTrue(changed.roots.none { id -> index.entry(id)!!.book.node.let { it.record <= 199 && it.end >= 120 } })
    }

    private fun assertOriginalParents(books: List<SearchBook>) {
        val catalog = books.distinctBy { it.node.record }.sortedBy { it.node.record }
        val index = SearchBookSelection(books)
        catalog.forEach { child ->
            val candidates = catalog.filter { candidate ->
                candidate.node.record < child.node.record && child.node.end >= child.node.record &&
                    candidate.node.end >= child.node.end
            }
            val direct = candidates.firstOrNull { it.node.record == child.node.parent }
            val expected = candidates.minWithOrNull(compareBy<SearchBook> { it.node.end.toLong() - it.node.record }
                .thenBy { if (it == direct) 0 else 1 }.thenByDescending { it.node.record })
            assertEquals("Parent of ${child.node.record}", expected?.node?.record,
                index.entry(child.node.record)?.parentRecord)
        }
    }

    private fun book(record: Int, parent: Int, end: Int, title: String = "Source $record", level: Int = 1) =
        SearchBook(ContentNode(record, parent, title, end, 0, level))
}