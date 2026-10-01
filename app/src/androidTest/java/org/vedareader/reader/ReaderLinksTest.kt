package org.vedareader.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReaderLinkKind

/** The anchors Folio emits must round-trip; anything off-host, off-path or malformed is rejected. */
@RunWith(AndroidJUnit4::class)
class ReaderLinksTest {
    @Test
    fun emittedAnchorsRoundTripToTypedLinks() {
        val html = FolioText.fragment("<QL:Query,\"[Headings Verse Number,bg 2.26\">2.26<EL> " +
            "<JL:Jump,\"Lifes Final Exam\">x<EL> <PX:\"Popup\",\"InlinePopupText_117\">Note<EL> <DL:Data,\"Bg-01-01\">Audio<EL>")
        val targets = Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        assertEquals(4, targets.size)
        assertEquals(listOf(
            ReaderLink(ReaderLinkKind.QUERY, "[Headings Verse Number,bg 2.26"),
            ReaderLink(ReaderLinkKind.JUMP, "Lifes Final Exam"),
            ReaderLink(ReaderLinkKind.POPUP, "InlinePopupText_117"),
            ReaderLink(ReaderLinkKind.AUDIO, "Bg-01-01"),
        ), targets.map { ReaderLinks.parse(Uri.parse(it)) })
    }

    @Test
    fun foreignOrMalformedUrlsAreNotLinks() {
        for (url in listOf("https://example.com/links/jump?target=x", "http://reader.local/links/jump?target=x",
            "https://reader.local/document/3", "https://reader.local/links/web?target=x",
            "https://reader.local/links/jump", "https://reader.local/links/jump?target=",
            "https://reader.local/links/jump/extra?target=x", "https://reader.local/links/jump?target=%01bad",
            "https://reader.local/links/jump?target=" + "x".repeat(600), "javascript:alert(1)", "")) {
            assertNull(url, ReaderLinks.parse(Uri.parse(url)))
        }
        assertNull(ReaderLinks.parse(null))
    }
}
