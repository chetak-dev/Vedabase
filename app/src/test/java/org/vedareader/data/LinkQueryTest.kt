package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure Folio query-link parsing; the database lookup is covered by the instrumentation engine test. */
class LinkQueryTest {
    @Test
    fun headingsQueriesYieldFoldedLevelAndTitleWithOrWithoutClosingBracket() {
        assertEquals(LinkQuery.Heading("verse number", "bg 2.26"), LinkQuery.heading("[Headings Verse Number,bg 2.26"))
        assertEquals(LinkQuery.Heading("verse number", "bg 2.26"), LinkQuery.heading("[Headings Verse Number,bg 2.26]"))
        assertEquals(LinkQuery.Heading("verse number", "madhya 17.133"), LinkQuery.heading("s [Headings Verse Number,Madhya 17.133]"))
        assertEquals(LinkQuery.Heading("chapter", "bg 1 observing the armies on the battlefield of kuruksetra"),
            LinkQuery.heading("[Headings Chapter,bg 1 observing   the armies on the battlefield of kuruksetra]"))
        assertEquals(LinkQuery.Heading("book", "fm 1 dedication"), LinkQuery.heading("[Headings Book,fm 1 dedication]"))
        assertEquals(LinkQuery.Heading("verse number", "bg 2.13"), LinkQuery.heading("[Level Verse Number:(BG 2.13)]"))
    }

    @Test
    fun nonHeadingQueriesBecomeBoundedSearchTextOrNothing() {
        assertNull(LinkQuery.heading("3.14"))
        assertEquals("3.14", LinkQuery.searchText("3.14"))
        assertEquals("720227ta.may", LinkQuery.searchText("720227ta.may"))
        assertEquals("Sipping Water for Purification camana",
            LinkQuery.searchText("\"\"Sipping Water for Purification (<CH:228>camana)\"\""))
        assertNull(LinkQuery.searchText("   "))
        assertNull(LinkQuery.searchText("[]()\"\""))
        assertNull("Nine words exceed the search limit", LinkQuery.searchText("a b c d e f g h i"))
        assertNull(LinkQuery.heading("[Headings Verse Number,${"x".repeat(600)}]"))
        assertNull(LinkQuery.searchText("x".repeat(600)))
    }
}
