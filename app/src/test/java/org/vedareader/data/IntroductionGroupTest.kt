package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroductionGroupTest {
    // Deliberately non-numeric order and non-semantic labels: only the verified ID set matters.
    private val entries = listOf(
        node(90, "Unrelated opening"), node(70, "नमूना"), node(30, "Unrelated middle"),
        node(50, "Arbitrary verified title"), node(10, "Chapter"),
    )

    @Test
    fun groupsAtFirstVerifiedPositionAndKeepsSourceOrderAndOtherRowsUntouched() {
        val result = groupIntroduction(entries, linkedSetOf(50, 70))
        assertEquals(4, result.size)
        assertEquals(ContentsItem.Section(entries[0], 0), result[0])
        val group = result[1] as ContentsItem.Introduction
        assertEquals(listOf(ContentsItem.Section(entries[1], 1), ContentsItem.Section(entries[3], 3)), group.sections)
        assertEquals(ContentsItem.Section(entries[2], 2), result[2])
        assertEquals(ContentsItem.Section(entries[4], 4), result[3])
        assertSame(entries[1], group.sections[0].node)
        assertSame(entries[3], group.sections[1].node)
        assertEquals(listOf(90, 70, 30, 50, 10), entries.map { it.record })
    }

    @Test
    fun noMetadataMeansNoGuessingFromTitlesLevelsOrRecordNumbers() {
        val familiarTitles = listOf(node(2, "Setting the Scene"), node(3, "Dedication"),
            node(4, "Preface"), node(5, "Introduction"), node(6, "Foreword"))
        assertUnchanged(familiarTitles, emptySet())
    }

    @Test
    fun fewerThanTwoEligibleRecordsDoNotCreateAGroup() {
        assertUnchanged(entries, emptySet())
        assertUnchanged(entries, setOf(70))
        assertUnchanged(emptyList(), emptySet())
    }

    @Test
    fun anyUnknownIdRejectsTheWholeGroupingRatherThanInventingOrDroppingEntries() {
        assertUnchanged(entries, setOf(70, 999))
        assertUnchanged(entries, setOf(70, 50, 999))
        assertUnchanged(emptyList(), setOf(70, 50))
    }

    @Test
    fun duplicateSourceIdsAreNotGrouped() {
        assertUnchanged(entries + entries[1].copy(title = "Duplicate record"), setOf(70, 50))
    }

    @Test
    fun allEligibleEntriesMakeExactlyOneGroupWithoutInventingASourceNode() {
        val result = groupIntroduction(entries, entries.map { it.record }.toSet())
        val group = result.single() as ContentsItem.Introduction
        assertEquals(entries, group.sections.map { it.node })
        assertEquals(entries.indices.toList(), group.sections.map { it.sourceIndex })
        group.sections.forEachIndexed { index, section -> assertSame(entries[index], section.node) }
    }

    private fun assertUnchanged(source: List<ContentNode>, ids: Set<Int>) {
        val result = groupIntroduction(source, ids)
        assertTrue(result.all { it is ContentsItem.Section })
        assertEquals(source.mapIndexed { index, node -> ContentsItem.Section(node, index) }, result)
    }

    private fun node(record: Int, title: String) = ContentNode(record, 1, title, record + 1, 0, 7)
}