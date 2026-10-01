package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import java.util.Random

/** Synthetic grammar fixtures only: no corpus text, Android runtime, network or commands. */
class FolioLegacyTagsTest {
    @Test
    fun underlineHasBothResetFormsAndPreservesUnicodeInPlainText() {
        val unicode = "Kṛṣṇa कृष्ण हिन्दी কৃষ্ণ śrī a\u0301 𑀓 😀"
        val source = "<UN+>$unicode<UN> plain <UN+>again<UN-> end"
        val html = FolioText.fragment(source)
        assertEquals(block("<u>$unicode</u> plain <u>again</u> end"), html)
        assertEquals("$unicode plain again end", FolioText.plain(source))
        assertFalse(html.contains("&lt;UN"))
        assertBalanced(html)
    }

    @Test
    fun strikeoutHasBothResetFormsWithoutDroppingText() {
        val source = "<SO+>first<SO> normal <SO+>second<SO-> end"
        val html = FolioText.fragment(source)
        assertEquals(block("<s>first</s> normal <s>second</s> end"), html)
        assertEquals("first normal second end", FolioText.plain(source))
        assertFalse(html.contains("&lt;SO"))
        assertBalanced(html)
    }

    @Test
    fun namedStylesInheritDecorationsAndRestoreAfterNestedExplicitResets() {
        val source = "<UN+><CS:\"Bold_Italic\">a<CS:\"Underline_Strikeout\">b" +
            "<UN->c</CS>d</CS>e<UN>f"
        val html = FolioText.fragment(source)
        assertEquals(block("<strong><em><u>a<s>b</s></u><s>c</s><u>d</u></em></strong><u>e</u>f"), html)
        assertEquals("abcdef", FolioText.plain(source))
        assertBalanced(html)
        val nested = FolioText.fragment("<CS:\"Strikeout\"><CS:\"underline\">x</CS>y</CS>z")
        assertEquals(block("<u><s>x</s></u><s>y</s>z"), nested)
        assertBalanced(nested)
        assertEquals(block("<s>a</s>b<s>c</s>d"),
            FolioText.fragment("<SO+>a<CS:\"Underline\"><UN><SO->b</CS>c<SO>d"))
    }

    @Test
    fun crossedDecorationsStayBalancedAcrossLinksAndTableBoundaries() {
        val source = "<BD+><IT+><UN+><SO+><JL:Jump,\"local\">a" +
            "<TA:2,1,2; JU:CN><RO,HE><CE:VA:TO>b<UN>c<CE>d<BD>e<IT>f<SO>g</TA><EL>h"
        val html = FolioText.fragment(source)
        assertTrue(html.contains("<strong><em><u><s>a</s></u></em></strong></a></div>"))
        assertTrue(html.contains("<strong><em><u><s>b</s></u><s>c</s></em></strong>"))
        assertTrue(html.contains("<strong><em><s>d</s></em></strong><em><s>e</s></em><s>f</s>g"))
        assertTrue(html.endsWith("</table></div>${block("h")}"))
        assertEquals("a\nbc\tdefg\nh", FolioText.plain(source))
        assertBalanced(html)
    }

    @Test
    fun unclosedStylesLinksAndTablesCannotLeakIntoAnotherRecordOrCall() {
        val open = "<UN+><SO+><CS:\"Bold\"><JL:Jump,\"outer\">" +
            "<WW:Web,\"https://invalid.example\"><TA><CE>open"
        val document = FolioText.document(listOf(
            Paragraph(1, open, "", ""),
            Paragraph(2, "<EL></CS><UN><SO>normal", "", ""),
        ), "en", ReaderPreferences())
        val body = document.substringAfter("<main class=\"reader\">").substringBefore("</main>")
        assertTrue(body.contains("data-record=\"2\" dir=\"auto\">${block("normal")}</div>"))
        assertFalse(body.contains("href="))
        assertBalanced(body)
        assertEquals("open", FolioText.plain(open))
        assertEquals(block("normal"), FolioText.fragment("normal"))
        assertEquals("normal", FolioText.plain("normal"))
    }

    @Test
    fun hardSpacesAndSoftHyphensAreUnicodeContentEvenAtEdgesAndInTables() {
        val source = "<HS>कृ<SH>ष्ण .<HS>.<HS>.<HS>"
        val expected = "\u00a0कृ\u00adष्ण .\u00a0.\u00a0.\u00a0"
        assertEquals(expected, FolioText.plain(source))
        assertEquals(block(expected), FolioText.fragment(source))
        assertEquals("\u00a0", FolioText.plain("<HS>"))
        assertEquals("\u00ad", FolioText.plain("<SH>"))
        assertEquals("\u00a0x\u00a0", FolioText.plain("\u00a0x\u00a0"))
        val table = FolioText.fragment("<TA><HS></TA>")
        assertTrue(table.contains("<td>${block("\u00a0")}</td>"))
        assertEquals("\u00a0", FolioText.plain("<TA><HS></TA>"))
        assertBalanced(table)
    }

    @Test
    fun validatedMetadataDiscardsOnlyMarkersAndNeverHiddenOrNoteBodies() {
        val metadata = listOf("<LS:1.25>", "<SP:2p>", "<SB:0.5p>", "<SD:12,34,56>",
            "<SD:NO>", "<SD:DC>", "<LH:0.25>", "<HD+>", "<HD>", "<HD->",
            "<PN:\"Blue background\">", "</PN>", "<NT:2,3,Note>", "</NT>", "</SS>", "<BUILDVIEW>")
        for (tag in metadata) {
            assertEquals(tag, "", FolioText.fragment(tag))
            assertEquals(tag, "", FolioText.plain(tag))
        }
        val source = "<HD+>a<HD>b<HD->c<PN:\"Blue background\">d</PN>" +
            "<NT:2,3,Note>कृष्ण a\u0301</NT></SS><BUILDVIEW>" + metadata.joinToString("") + "e"
        assertEquals(block("abcdकृष्ण a\u0301e"), FolioText.fragment(source))
        assertEquals("abcdकृष्ण a\u0301e", FolioText.plain(source))
        // /SS is not /CS; presentation names are not character-style directives either.
        assertEquals(block("<u>ab</u>c"), FolioText.fragment("<CS:\"Underline\">a</SS>b</CS>c"))
        assertEquals(block("visible"), FolioText.fragment("<PN:\"Underline\">visible</PN>"))
    }

    @Test
    fun suppressedWebProgramAndCommandLinksKeepLabelsAndBalancedEndScopes() {
        val outer = "https://reader.local/links/jump?target=outer"
        val links = listOf(Triple("WW", "Web", "https://invalid.example/?x=1&y=2"),
            Triple("PL", "Program", "file:///private/program"), Triple("ML", "Command", "Go Back"))
        for ((code, kind, target) in links) {
            for (labelKind in listOf(kind, quoted(kind))) {
                val start = "<$code:$labelKind,${quoted(target)}>"
                assertEquals(block("label"), FolioText.fragment("${start}label<EL>"))
                val source = "<JL:Jump,\"outer\">a<UN+>${start}b" +
                    "<PL:\"Program\",\"javascript:bad()\">c<EL>d<EL>e<UN><EL>f"
                val html = FolioText.fragment(source)
                assertEquals(block("<a href=\"$outer\">a</a><u>bcd</u><a href=\"$outer\"><u>e</u></a>f"), html)
                assertEquals("abcdef", FolioText.plain(source))
                assertFalse(html.contains(target))
                assertFalse(html.contains("javascript:"))
                assertBalanced(html)
            }
            val malformed = FolioText.fragment("<JL:Jump,\"outer\">a<$code:$kind,unquoted>b<EL>c<EL>")
            assertEquals(block("<a href=\"$outer\">a</a>b<a href=\"$outer\">c</a>"), malformed)
            assertBalanced(malformed)
        }
        val nestedLocal = FolioText.fragment("<WW:\"Web\",\"https://invalid.example\">a" +
            "<JL:Jump,\"outer\">b<EL>c<EL>")
        assertEquals(block("a<a href=\"$outer\">b</a>c"), nestedLocal)
        assertBalanced(nestedLocal)
    }

    @Test
    fun quotedLocalLinkKindsUseOnlyExistingOpaqueLocalRoutes() {
        val links = listOf(Triple("QL", "Query", "query"), Triple("JL", "Jump", "jump"),
            Triple("PX", "Popup", "popup"), Triple("DL", "Data", "audio"))
        for ((code, kind, path) in links) {
            val source = "<$code: \"$kind\" ,\"javascript:bad()\">label<EL>"
            val html = FolioText.fragment(source)
            assertEquals(block("<a href=\"https://reader.local/links/$path?target=javascript%3Abad%28%29\">label</a>"), html)
            assertEquals("label", FolioText.plain(source))
            assertBalanced(html)
        }
        assertEquals(block("label"), FolioText.fragment("<PX:\"Popup\" junk,\"target\">label<EL>"))
        assertEquals(block("label"), FolioText.fragment("<PX:\"Popup,wrong\",\"target\">label<EL>"))
    }

    @Test
    fun auditedTableParametersProduceOnlyBareStructureIncludingHeaderRows() {
        val tableArgs = "2,25%,75%; HG:0.02; VG:0; BP:0; AP:0.1; IN:LF:0; JU:CN; " +
            "BR:AL:0.01,0,HZ:0.01,0,VT:0.01,0; SD:1,2,3"
        val cellArgs = "VA:TO; HI:0.25; MR:1; MD:2; KT+; " +
            "BR:LF:0.01,0,FC:1,2,3,TP:0,0,RT:0,0,BT:0,0; SD:3,2,1"
        val source = "<TA:$tableArgs><RO,HE><CE:$cellArgs>क<CE>q<RO><CE:SD:2,3,4>r</CE></RO></TA>"
        val bare = "<TA><RO><CE>क<CE>q<RO><CE>r</CE></RO></TA>"
        val html = FolioText.fragment(source)
        assertEquals(FolioText.fragment(bare), html)
        assertEquals("क\tq\nr", FolioText.plain(source))
        assertEquals(2, Regex("<tr>").findAll(html).count())
        assertEquals(3, Regex("<td>").findAll(html).count())
        assertBalanced(html)
        for (parameters in listOf("; BR:AL:0.01,0", "; HG:0.03", "1,2", "1,50%; IN:LF:0")) {
            assertEquals(FolioText.fragment("<TA><CE>x</TA>"), FolioText.fragment("<TA:$parameters><CE>x</TA>"))
            assertEquals("x", FolioText.plain("<TA:$parameters><CE>x</TA>"))
        }
    }

    @Test
    fun literalAngleWordsHostileHtmlAndUnauditedOrMalformedParametersRemainEscaped() {
        val source = listOf("<aa>", "<Hindi>", "<SS>", "<Unknown:1>", "<UN:1>", "<SO+:1>",
            "<HS:1>", "<SH:1>", "<HD:1>", "<HD+oops>", "<PN:unquoted>", "</PN:x>",
            "<PN:\"<script>\">", "<NT:1,2,Other>", "<LS:expression(bad)>", "<SP:3px>", "<SB:3>",
            "<SD:1,2>", "<LH:1em>", "<TA:99;arbitrary>", "<CE:\"layout\">", "<TA:2,1>",
            "<TA:; ZZ:1>", "<CE:VA:BO>", "<RO:HE>", "<RO,UNKNOWN>", "<BUILDVIEW:x>",
            "<TA:1,${"1".repeat(2050)}>", "<UN+", "<u onclick=bad()>x</u>", "<s>x</s>",
            "<script src=\"https://invalid.example\">bad()</script>", "<img src=x onerror=bad()>",
            "<a href=\"javascript:bad()\">label</a>", "&lt;UN+&gt; & \"quoted\" 'single'", "<unfinished")
            .joinToString(" ")
        val html = FolioText.fragment(source)
        assertEquals(source, FolioText.plain(source))
        assertEquals(block(escape(source)), html)
        assertBalanced(html)
    }

    @Test
    fun mixedAndUnclosedLegacyScopesAlwaysProduceBalancedRestrictedMarkup() {
        val random = Random(951L)
        val pieces = listOf("x & y", "कृष्ण", "<aa>", "<Hindi>", "<UN+>", "<UN>", "<UN->",
            "<SO+>", "<SO>", "<SO->", "<BD+>", "<BD>", "<IT+>", "<IT>",
            "<CS:\"Bold_Italic_Underline_Strikeout\">", "</CS>", "</SS>", "<HD+>", "<HD->",
            "<HS>", "<SH>", "<NT:1,2,Note>", "</NT>", "<WW:Web,\"https://invalid.example\">",
            "<PL:Program,unquoted>", "<ML:Command,\"Go Back\">", "<JL:\"Jump\",\"local\">", "<EL>",
            "<HR>", "<CR>", "<TA:; HG:0.02>", "</TA>", "<RO,HE>", "</RO>",
            "<CE:VA:CN; SD:1,2,3>", "</CE>", "<script>bad()</script>", "<TA:99;arbitrary>")
        repeat(100) {
            val source = buildString { repeat(40) { append(pieces[random.nextInt(pieces.size)]) } }
            assertBalanced(FolioText.fragment(source))
        }
    }

    private fun block(content: String) = "<div class=\"folio-block text\" dir=\"auto\">$content</div>"
    private fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** Validate tag/attribute allowlists, proper nesting, bare table structure and local links. */
    private fun assertBalanced(html: String) {
        val allowed = setOf("div", "a", "strong", "em", "u", "s", "br", "hr", "table", "tbody", "tr", "td")
        val inline = setOf("a", "strong", "em", "u", "s")
        val blocks = setOf("div", "hr", "table", "tbody", "tr", "td")
        val stack = mutableListOf<String>()
        val tagPattern = Regex("<(/?)([a-z]+)([^>]*)>")
        val attributePattern = Regex("\\s+([a-z-]+)=\"([^\"]*)\"")
        var cursor = 0
        for (match in tagPattern.findAll(html)) {
            val text = html.substring(cursor, match.range.first)
            assertFalse("Unescaped text: $text", '<' in text || '>' in text)
            cursor = match.range.last + 1
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2]
            val attributes = match.groupValues[3]
            assertTrue("Unexpected tag: $name", name in allowed)
            if (closing) {
                assertTrue(attributes.isEmpty())
                assertEquals("Crossed tags in $html", name, stack.lastOrNull())
                stack.removeAt(stack.lastIndex)
            } else {
                if (name == "a") assertFalse("Nested links", "a" in stack)
                if (name in blocks) assertFalse("Block inside inline tags", stack.any { it in inline })
                when (name) {
                    "tbody" -> assertEquals("table", stack.lastOrNull())
                    "tr" -> assertEquals("tbody", stack.lastOrNull())
                    "td" -> assertEquals("tr", stack.lastOrNull())
                    "div", "hr" -> assertFalse(stack.lastOrNull() in setOf("table", "tbody", "tr"))
                }
                assertEquals("", attributePattern.replace(attributes, "").trim())
                val allowedAttributes = when (name) {
                    "div" -> setOf("class", "dir", "id", "data-record")
                    "a" -> setOf("href")
                    else -> emptySet()
                }
                for (attribute in attributePattern.findAll(attributes)) {
                    assertTrue(attribute.groupValues[1] in allowedAttributes)
                    val value = attribute.groupValues[2]
                    assertFalse('<' in value || '>' in value)
                    if (attribute.groupValues[1] == "href") {
                        assertTrue(value.matches(Regex("https://reader\\.local/links/(jump|query|popup|audio)\\?target=[^\\s]+")))
                    }
                }
                if (name !in setOf("br", "hr")) stack.add(name)
            }
        }
        assertFalse('<' in html.substring(cursor) || '>' in html.substring(cursor))
        assertTrue("Unclosed tags: $stack", stack.isEmpty())
    }
}