package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Random

/** All examples are synthetic; no corpus, Android runtime, or network is required. */
class FolioTextTest {
    @Test
    fun escapesTextAndUnknownHtmlWithoutDecodingEntities() {
        val source = "<script src=\"https://invalid.example/x\">bad()</script>" +
            "<img src=x onerror=bad()><svg onload=bad()>x</svg>" +
            "<a href=\"javascript:bad()\">label</a> & \"quoted\" 'single' &lt;b&gt; 2 < 3 > 1"
        val html = FolioText.fragment(source)
        assertTrue(html.contains("&lt;script src=&quot;https://invalid.example/x&quot;&gt;bad()&lt;/script&gt;"))
        assertTrue(html.contains("&lt;img src=x onerror=bad()&gt;"))
        assertTrue(html.contains("&amp; &quot;quoted&quot; &#39;single&#39; &amp;lt;b&amp;gt; 2 &lt; 3 &gt; 1"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<a "))
        assertEquals(source, FolioText.plain(source))
        assertSafeFragment(html)
    }

    @Test
    fun preservesUnicodeWithoutTransliterationOrNormalization() {
        val text = "Kṛṣṇa कृष्ण हिन्दी কৃষ্ণ বাংলা śrī a\u0301 𑀓 😀"
        val source = "<CS:\"Italic\">$text</CS>"
        assertEquals(text, FolioText.plain(source))
        assertEquals(block("<em>$text</em>"), FolioText.fragment(source))
    }

    @Test
    fun reconcilesCrossedBoldAndItalicResets() {
        val source = "<BD+>b<IT+>both<BD>i<IT>plain<BD+>b2<BD->off<IT+>i2<IT->end"
        val html = FolioText.fragment(source)
        assertEquals(block("<strong>b<em>both</em></strong><em>i</em>plain<strong>b2</strong>off<em>i2</em>end"), html)
        assertEquals("bbothiplainb2offi2end", FolioText.plain(source))
        assertSafeFragment(html)
    }

    @Test
    fun namedCharacterStylesNestAndExplicitOffOverridesTheCurrentStyle() {
        val source = "<CS:\"Bold\">b<CS:\"Ref_Italic\">both<BD->i</CS>b</CS>normal"
        val html = FolioText.fragment(source)
        assertEquals(block("<strong>b<em>both</em></strong><em>i</em><strong>b</strong>normal"), html)
        assertSafeFragment(html)
        val injectedName = "Bold\" onclick=\"bad() <script>"
        val safe = FolioText.fragment("<CS:${quoted(injectedName)}>word</CS>")
        assertEquals(block("<strong>word</strong>"), safe)
        assertSafeFragment(safe)
    }

    @Test
    fun suppressesParagraphFontAndLayoutMetadataButKeepsItsText() {
        val source = "<PS:\"Purport\"><IN:1;2><PT:24><FC:255;0;255>" +
            "<FT:\"url(https://invalid.example/font)\"><FD:Devanagari>देवनागरी</FD:Devanagari>"
        assertEquals("देवनागरी", FolioText.plain(source))
        val html = FolioText.fragment(source)
        assertEquals(block("देवनागरी", "purport"), html)
        assertFalse(html.contains("invalid.example"))
        assertFalse(html.contains("255"))
        assertSafeFragment(html)
    }

    @Test
    fun preservesBreaksTabsSeparatorsAndWhitelistedAlignment() {
        val source = "<JU:CN>one<CR>two<TB>x<HR><JU:RT>three"
        assertEquals("one\ntwo\tx\n\nthree", FolioText.plain(source))
        val html = FolioText.fragment(source)
        assertEquals(block("one<br>two&#9;x", "text align-center") + "<hr>" +
            block("three", "text align-right"), html)
        assertSafeFragment(html)
        assertEquals(block("safe"), FolioText.fragment("<JU:expression(bad)>safe"))
    }

    @Test
    fun routesAllFourLinkKindsToLocalEncodedTargets() {
        val examples = listOf(
            Triple("<QL:Query,\"[Headings Verse Number,BG 18.66]\">18.66<EL>", "18.66",
                "query?target=%5BHeadings%20Verse%20Number%2CBG%2018.66%5D"),
            Triple("<JL:Jump,\"GM: 1. Example chapter\">chapter<EL>", "chapter",
                "jump?target=GM%3A%201.%20Example%20chapter"),
            Triple("<PX:Popup,\"BG_TextPop\">TEXT 1<EL>", "TEXT 1", "popup?target=BG_TextPop"),
            Triple("<DL:Data,\"Bg-01-01\">Audio<EL>", "Audio", "audio?target=Bg-01-01"),
        )
        for ((source, label, route) in examples) {
            val html = FolioText.fragment(source)
            assertEquals(block("<a href=\"https://reader.local/links/$route\">$label</a>"), html)
            assertEquals(label, FolioText.plain(source))
            assertSafeFragment(html)
        }
    }

    @Test
    fun hostileAndUnicodeLinkTargetsRemainOpaqueLocalRouteParameters() {
        val targets = listOf(
            "javascript:alert(1)", "file:///sdcard/private", "data:text/html,<script>bad()</script>",
            "https://invalid.example/?x=1&y=2#part", "x\" onclick=\"bad() <svg>",
            "कृष्ण Kṛṣṇa + / ? & # > < ' \" \n",
        )
        for (target in targets) {
            val html = FolioText.fragment("<JL:Jump,${quoted(target)}>safe<EL>")
            val href = Regex("<a href=\"([^\"]+)\">").find(html)!!.groupValues[1]
            assertTrue(href.startsWith("https://reader.local/links/jump?target="))
            assertFalse(href.contains('+'))
            assertEquals(target, decode(href.substringAfter("?target=")))
            assertFalse(html.contains("onclick="))
            assertFalse(html.contains("<script>"))
            assertSafeFragment(html)
        }
    }

    @Test
    fun nestedLinksAreSplitAndTheOuterLinkResumesWithoutNestedAnchors() {
        val source = "<JL:Jump,\"outer\">a<QL:Query,\"inner\">b<EL>c<EL>"
        val outer = "https://reader.local/links/jump?target=outer"
        val inner = "https://reader.local/links/query?target=inner"
        val html = FolioText.fragment(source)
        assertEquals(block("<a href=\"$outer\">a</a><a href=\"$inner\">b</a><a href=\"$outer\">c</a>"), html)
        assertEquals("abc", FolioText.plain(source))
        assertSafeFragment(html)
    }

    @Test
    fun malformedNestedLinkDoesNotConsumeItsParentsEndMarker() {
        val source = "<JL:Jump,\"outer\">a<QL:Query,unquoted>b<EL>c<EL>"
        val outer = "https://reader.local/links/jump?target=outer"
        val html = FolioText.fragment(source)
        assertEquals(block("<a href=\"$outer\">a</a>b<a href=\"$outer\">c</a>"), html)
        assertSafeFragment(html)
    }

    @Test
    fun objectsUseEncodedLocalPathsEscapedAltAndNoLegacyDimensions() {
        val names = listOf("NEW VB-Intro-image 2016.bmp", "चित्र \" & '/../x.bmp")
        for (name in names) {
            val source = "<OB:\"OB\";${quoted(name)};6.03125;5.05208>"
            val html = FolioText.fragment(source)
            val src = Regex("<img src=\"([^\"]+)\"").find(html)!!.groupValues[1]
            assertTrue(src.startsWith("https://reader.local/objects/"))
            assertEquals(name, decode(src.substringAfter("/objects/")))
            assertFalse(src.contains('+'))
            assertFalse(html.contains(" width="))
            assertFalse(html.contains(" height="))
            assertFalse(html.contains("6.03125"))
            assertEquals("", FolioText.plain(source))
            assertSafeFragment(html)
        }
        val simple = FolioText.fragment("<OB:\"OB\";\"NEW VB-Intro-image 2016.bmp\";6.03125;5.05208>")
        assertTrue(simple.contains("https://reader.local/objects/NEW%20VB-Intro-image%202016.bmp"))
        assertEquals("", FolioText.fragment("<OB:\"unsupported\";\"x\";1;2>"))
    }

    @Test
    fun quotedTagDelimitersAndMalformedTailsAreNotExecuted() {
        val source = "before<unknown title=\"<BD+> > x\">after</unknown><unfinished"
        val html = FolioText.fragment(source)
        assertFalse(html.contains("<strong>"))
        assertTrue(html.contains("&lt;unknown title=&quot;&lt;BD+&gt; &gt; x&quot;&gt;"))
        assertTrue(html.contains("&lt;unfinished"))
        assertEquals(source, FolioText.plain(source))
        assertSafeFragment(html)
        assertSafeFragment(FolioText.fragment("a <incomplete <BD+>b"))
        assertSafeFragment(FolioText.fragment("<JL:Jump,\"unclosed <script>bad</script>"))
    }

    @Test
    fun tablesSupportImplicitAndExplicitRowAndCellBoundaries() {
        val implicit = "<TA>\n<RO><CE>a<CE>b<RO><CE>c</TA>"
        val explicit = "<TA><RO><CE>a</CE><CE>b</CE></RO><RO><CE>c</CE></RO></TA>"
        for (source in listOf(implicit, explicit)) {
            val html = FolioText.fragment(source)
            assertEquals(1, Regex("<table>").findAll(html).count())
            assertEquals(2, Regex("<tr>").findAll(html).count())
            assertEquals(3, Regex("<td>").findAll(html).count())
            assertEquals("a\tb\nc", FolioText.plain(source))
            assertSafeFragment(html)
        }
    }

    @Test
    fun tablesAndSeparatorsCloseAndReopenInlineMarkupEvenWhenSourceIsUnclosed() {
        val source = "<CS:\"Bold_Italic\"><JL:Jump,\"outer\">a<HR>b" +
            "<TA><RO><CE>c<TA><CE>d</TA>e<CE>f"
        val html = FolioText.fragment(source)
        assertEquals(2, Regex("<table>").findAll(html).count())
        assertTrue(html.contains("<strong><em>f</em></strong></a></div></td></tr></tbody></table></div>"))
        assertSafeFragment(html)
        assertEquals("", FolioText.fragment("</CS><EL></TA><RO><CE></RO></CE>"))
    }

    @Test
    fun doesNotGuessParameterizedTableSyntax() {
        val html = FolioText.fragment("<TA:99;arbitrary><CE:\"layout\">text")
        assertFalse(html.contains("<table>"))
        assertTrue(html.contains("&lt;TA:99;arbitrary&gt;"))
        assertTrue(html.contains("&lt;CE:&quot;layout&quot;&gt;"))
        assertSafeFragment(html)
    }

    @Test
    fun documentHasHtml5ViewportFixedBaseRestrictiveCspAndEscapedLanguage() {
        val language = "hi\" dir=\"ltr\"><script>alert('x')</script>&"
        val escaped = "hi&quot; dir=&quot;ltr&quot;&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;"
        val html = FolioText.document(listOf(Paragraph(7,
            "<FT:\"url(https://invalid.example/source-font.ttf)\"><PT:99>safe",
            "\" onclick=\"attacker", "<style>attacker")),
            language, ReaderPreferences())
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<meta charset=\"UTF-8\">"))
        assertTrue(html.contains("name=\"viewport\" content=\"width=device-width, initial-scale=1\""))
        assertTrue(html.contains("<base href=\"https://reader.local/\">"))
        assertTrue(html.contains("http-equiv=\"Content-Security-Policy\""))
        assertTrue(html.contains("default-src 'none'; img-src https://reader.local; style-src 'unsafe-inline';"))
        assertTrue(html.contains("font-src https://reader.local; base-uri https://reader.local; form-action 'none'"))
        assertTrue(html.contains("<body lang=\"$escaped\" dir=\"auto\">"))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("attacker"))
        assertFalse(html.contains("invalid.example"))
        assertOnlyBundledFontUrls(css(html))
        assertSafeFragment(main(html))
    }

    @Test
    fun recognizesRecordStylesWithoutCopyingSourceNamesIntoAttributes() {
        val styles = linkedMapOf(
            "PA_Purport" to "purport", "PA_Translation" to "translation", "PA_Synonyms" to "synonyms",
            "PA_Synonyms_Section" to "synonyms",
            "PA_DevaNagariQ" to "devanagari", "PA_Verse" to "verse", "PA_Verse_in_purp" to "verse-in-purport",
            "LE_Verse_Text" to "verse-line", "LE_Chapter" to "chapter", "LE_Collection" to "collection",
            "LE_Verse_Number" to "verse-number", "PA_Verse_Number_Heading" to "verse-number-heading",
            "PA_Text_Section" to "text-section",
        )
        val paragraphs = styles.keys.mapIndexed { index, style -> Paragraph(index + 1, "sample", style, "") }
        val html = FolioText.document(paragraphs, "sa", ReaderPreferences())
        for ((index, entry) in styles.entries.withIndex()) {
            assertTrue(Regex("class=\"record ${entry.value}(?: purport-content)?\" id=\"record-${index + 1}\"").containsMatchIn(html))
            assertFalse(html.contains(entry.key))
        }
        assertSafeFragment(main(html))
    }

    @Test
    fun collapsedSynonymsKeepFormattedHeadingUnicodeEscapedSourceAndUniqueRecordAnchors() {
        val unicode = "Kṛṣṇa कृष्ण हिन्दी কৃষ্ণ বাংলা śrī a\u0301 𑀓 😀"
        val paragraphs = listOf(
            Paragraph(11, "<BD+>SYNONYMS<BD->", "PA_Titles", "LE_Verse_Section"),
            Paragraph(12, "<CS:\"Italic\">$unicode</CS> & \"quoted\" 'single' &lt;b&gt; " +
                "<script>bad()</script><unknown>x</unknown>", "PA_Synonyms", ""),
            Paragraph(13, "Original second paragraph — $unicode", "PA_Synonyms_Section", ""),
        )
        val original = paragraphs.toList()
        val html = FolioText.document(paragraphs, "sa", ReaderPreferences())
        val group = disclosures(html).single().value
        assertTrue(group.startsWith("<details class=\"synonyms-group\"><summary class=\"record text-section\" " +
            "id=\"record-11\" data-record=\"11\" dir=\"auto\">"))
        assertTrue(group.contains("<span class=\"folio-block text-section\" dir=\"auto\"><strong>SYNONYMS</strong></span></summary>"))
        assertTrue(group.contains("<em>$unicode</em> &amp; &quot;quoted&quot; &#39;single&#39; &amp;lt;b&amp;gt;"))
        assertTrue(group.contains("&lt;script&gt;bad()&lt;/script&gt;&lt;unknown&gt;x&lt;/unknown&gt;"))
        assertTrue(group.contains("Original second paragraph — $unicode"))
        assertFalse(Regex("<details[^>]*\\bopen\\b").containsMatchIn(main(html)))
        assertFalse(group.contains("<script>"))
        for (paragraph in paragraphs) {
            assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(group).count())
            assertEquals(1, Regex("data-record=\"${paragraph.record}\"").findAll(group).count())
        }
        assertEquals(original, paragraphs)
        assertSafeFragment(main(html))
    }

    @Test
    fun synonymsCloseBeforeEveryOtherKindAndKeepMultipleVerseWrappersBalanced() {
        val boundaries = listOf(
            Paragraph(4, "translation", "PA_Translation", ""),
            Paragraph(4, "purport", "PA_Purport", ""),
            Paragraph(4, "first purport", "PA_First", ""),
            Paragraph(4, "after verse", "PA_AfterVerse", ""),
            Paragraph(4, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(4, "PURPORT", "PA_Titles", ""),
            Paragraph(4, "Another heading", "PA_Titles", "LE_Verse_Section"),
            Paragraph(4, "Unknown section", "PA_Unknown", "LE_Section"),
            Paragraph(4, "Body paragraph", "", ""),
            Paragraph(4, "Next verse", "PA_Synonyms", "LE_Verse_Number"),
            Paragraph(4, "Next chapter", "PA_Synonyms_Section", "LE_Chapter"),
        )
        for (boundary in boundaries) {
            val paragraphs = listOf(
                Paragraph(1, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
                Paragraph(2, "first", "PA_Synonyms", ""),
                Paragraph(3, "second", "PA_Synonyms_Section", ""),
                boundary,
                Paragraph(5, "separate group", "PA_Synonyms", ""),
            )
            val html = FolioText.document(paragraphs, "en", ReaderPreferences())
            val groups = disclosures(html)
            assertEquals(2, groups.size)
            assertTrue(groups[0].value.contains("id=\"record-3\""))
            val boundaryPosition = main(html).indexOf("id=\"record-4\"")
            assertTrue(boundaryPosition > groups[0].range.last)
            assertTrue(boundaryPosition < groups[1].range.first)
            assertTrue(groups[1].value.contains("id=\"record-5\""))
            assertSafeFragment(main(html))
        }
        val multiVerse = FolioText.document(listOf(
            Paragraph(1, "first line", "", "LE_Verse_Text"),
            Paragraph(2, "second line", "", "LE_Verse_Text"),
            Paragraph(3, "first synonyms", "PA_Synonyms", ""),
            Paragraph(4, "TEXT 2", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(5, "next first line", "", "LE_Verse_Text"),
            Paragraph(6, "next second line", "", "LE_Verse_Text"),
            Paragraph(7, "next synonyms", "PA_Synonyms", ""),
        ), "en", ReaderPreferences(), compactVerse = true)
        assertEquals(2, disclosures(multiVerse).size)
        assertEquals(2, Regex("<div class=\"verse-group\">").findAll(main(multiVerse)).count())
        assertEquals(7, Regex("data-record=\"").findAll(main(multiVerse)).count())
        assertTrue(disclosures(multiVerse)[0].range.last < main(multiVerse).indexOf("id=\"record-4\""))
        assertSafeFragment(main(multiVerse))
    }

    @Test
    fun synonymsNeverInferBodiesFromWordsLooseStylesOrConflictingLevels() {
        val nonBodies = listOf(
            "" to "", "PA_Text" to "", "Synonyms" to "", "LE_Synonyms" to "",
            "PA_Synonyms_extra" to "", "PA_Synonyms_Section_extra" to "",
            "PA_Synonyms" to "LE_Translation", "PA_Synonyms" to "LE_Purport",
            "PA_Synonyms_Section" to "LE_Verse_Text", "PA_Synonyms" to "LE_Unknown",
            "PA_Titles" to "LE_Purport", "PA_Titles" to "", "" to "LE_Verse_Section",
        ).mapIndexed { index, (style, level) -> Paragraph(index + 1, "SYNONYMS", style, level) } +
            Paragraph(99, "<PS:\"PA_Synonyms\">SYNONYMS", "", "")
        val html = FolioText.document(nonBodies, "en", ReaderPreferences(), compactVerse = true)
        assertTrue(disclosures(html).isEmpty())
        assertEquals(nonBodies.size, Regex("data-record=\"").findAll(main(html)).count())
        assertFalse(main(html).contains("repeated-label"))
        assertSafeFragment(main(html))

        val nonHeadings = listOf(
            Paragraph(1, "SYNONYMS", "", ""),
            Paragraph(1, "SYNONYMS", "PA_Titles_extra", ""),
            Paragraph(1, "SYNONYMS", "PA_Titles", "LE_Purport"),
            Paragraph(1, "SYNONYMS explained", "PA_Titles", "LE_Verse_Section"),
            Paragraph(1, "Other heading", "PA_Titles", "LE_Verse_Section"),
        )
        for (heading in nonHeadings) {
            val document = FolioText.document(listOf(heading,
                Paragraph(2, "actual synonyms", "PA_Synonyms", "")), "en", ReaderPreferences())
            val group = disclosures(document).single()
            assertTrue(group.value.startsWith("<details class=\"synonyms-group\"><summary>Synonyms</summary>"))
            assertTrue(main(document).indexOf("id=\"record-1\"") < group.range.first)
            assertSafeFragment(main(document))
        }
    }

    @Test
    fun standaloneSynonymsHaveFriendlySummaryAndOptOutKeepsFlatRecordsInBothModes() {
        val paragraphs = listOf(
            Paragraph(1, "कृष्ण — Kṛṣṇa", "PA_Synonyms", ""),
            Paragraph(2, "śrī a\u0301", "PA_Synonyms_Section", ""),
            Paragraph(3, "SYNONYMS", "PA_Titles", ""),
            Paragraph(4, "বাংলা", "PA_Synonyms", ""),
            Paragraph(5, "Synonyms", "PA_Text_Section", "LE_Verse_Section"),
            Paragraph(6, "देवनागरी", "PA_Synonyms", "LE_Synonyms"),
        )
        for (compact in listOf(false, true)) {
            val default = FolioText.document(paragraphs, "en", ReaderPreferences(), compactVerse = compact)
            assertEquals(default, FolioText.document(paragraphs, "en", ReaderPreferences(),
                compactVerse = compact, collapsibleSynonyms = true))
            val groups = disclosures(default)
            assertEquals(3, groups.size)
            assertTrue(groups[0].value.startsWith("<details class=\"synonyms-group\"><summary>Synonyms</summary>"))
            assertTrue(groups[1].value.contains("<summary class=\"record text-section\" id=\"record-3\""))
            assertTrue(groups[2].value.contains("<summary class=\"record text-section\" id=\"record-5\""))
            assertFalse(main(default).contains("repeated-label"))
            assertSafeFragment(main(default))
            val flat = FolioText.document(paragraphs, "en", ReaderPreferences(),
                compactVerse = compact, collapsibleSynonyms = false)
            assertFalse(main(flat).contains("<details"))
            assertFalse(main(flat).contains("<summary"))
            assertFalse(main(flat).contains("<span"))
            for (paragraph in paragraphs) {
                assertTrue(main(flat).contains(paragraph.text))
                assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(main(flat)).count())
            }
            assertSafeFragment(main(flat))
        }
    }

    @Test
    fun nativeSummaryKeepsKeyboardSemanticsSafePhrasingAndNoJavaScript() {
        val target = "javascript:bad()\" onclick=\"bad()"
        val heading = "<TA><RO><CE><BD+><JL:Jump,${quoted(target)}>SYNONYMS<EL><BD-></CE></RO></TA>"
        for (paper in ReaderPaper.entries) {
            val html = FolioText.document(listOf(
                Paragraph(1, heading, "PA_Titles", "LE_Verse_Section"),
                Paragraph(2, "<script>bad()</script><img src=x onerror=bad()> original", "PA_Synonyms", ""),
            ), "en", ReaderPreferences(paper = paper))
            val group = disclosures(html).single().value
            assertTrue(group.startsWith("<details class=\"synonyms-group\"><summary "))
            val summary = group.substringBefore("</summary>")
            assertTrue(summary.contains("<span class=\"folio-block text-section\""))
            assertFalse(summary.contains("<strong><a href=\"")) // Renderer puts links outside styles.
            // Section headings are plain labels: their source popup/jump links are not rendered.
            assertFalse(summary.contains("<a href="))
            assertFalse(summary.contains("javascript"))
            assertTrue(summary.contains("<strong>SYNONYMS</strong>"))
            assertFalse(summary.contains("<div"))
            assertFalse(summary.contains("<table"))
            assertFalse(group.contains(" role="))
            assertFalse(group.contains(" tabindex="))
            assertFalse(group.contains(" open"))
            assertFalse(html.contains("<script"))
            assertFalse(html.contains("onclick=\""))
            assertFalse(html.contains("addJavascriptInterface"))
            assertTrue(html.contains("default-src 'none'; img-src https://reader.local; style-src 'unsafe-inline';"))
            assertTrue(css(html).contains(".synonyms-group > summary:focus-visible { outline: 2px solid currentColor;"))
            assertFalse(css(html).contains("list-style: none"))
            assertOnlyBundledFontUrls(css(html))
            assertSafeFragment(main(html))
        }
    }

    @Test
    fun groupsSuccessiveVerseLinesButNotOtherParagraphs() {
        val paragraphs = listOf(
            Paragraph(1, "line one", "PA_Verse", "LE_Verse_Text"),
            Paragraph(2, "line two", "", "LE_Verse_Text"),
            Paragraph(3, "translation", "PA_Translation", ""),
            Paragraph(4, "line three", "LE_Verse_Text", ""),
            Paragraph(5, "chapter", "PA_Text", "LE_Chapter"),
        )
        val html = FolioText.document(paragraphs, "en", ReaderPreferences())
        val groups = Regex("<div class=\"verse-group\">").findAll(html).map { it.range.first }.toList()
        assertEquals(2, groups.size)
        assertTrue(groups[0] < html.indexOf("id=\"record-1\""))
        assertTrue(html.indexOf("id=\"record-2\"") < html.indexOf("id=\"record-3\""))
        assertTrue(html.indexOf("id=\"record-3\"") < groups[1])
        assertTrue(groups[1] < html.indexOf("id=\"record-4\""))
        assertTrue(css(html).contains(".verse-group > .record { margin-block: .12em; }"))
        assertTrue(html.contains("class=\"record chapter\" id=\"record-5\""))
        assertSafeFragment(main(html))
    }

    @Test
    fun formattingStateCannotLeakBetweenRecords() {
        val html = FolioText.document(listOf(
            Paragraph(1, "<BD+><JL:Jump,\"target\">open", "", ""),
            Paragraph(2, "normal", "", ""),
        ), "en", ReaderPreferences())
        assertTrue(html.contains("data-record=\"2\" dir=\"auto\">${block("normal")}</div>"))
        assertSafeFragment(main(html))
    }

    @Test
    fun readerThemesAreMutedResponsiveAndFontSizesAreBounded() {
        for (paper in ReaderPaper.entries) {
            val palette = ReaderPaperAppearance.palette(paper)
            for ((bookMode, scrollingBook) in listOf(false to false, true to false, true to true)) {
                val stylesheet = css(FolioText.document(emptyList(), "en", ReaderPreferences(paper = paper),
                    bookMode = bookMode, scrollingBook = scrollingBook))
                assertTrue(stylesheet.contains("background: ${palette.Paper};"))
                assertTrue(stylesheet.contains("color: ${palette.Ink};"))
                assertTrue(stylesheet.contains("font-size: 20px;"))
                assertTrue(stylesheet.contains("max-width: none; margin-inline: 0; padding: .65rem 16px 1.25rem;"))
                assertDefaultLineHeight(stylesheet, "1.3", stylesheet.substringAfter("body {").substringBefore('}'))
                assertTrue(stylesheet.contains("font-family: 'Fira Sans Condensed', 'Reader Serif Devanagari', \"Noto Sans Bengali\", sans-serif;"))
                assertOnlyBundledFontUrls(stylesheet)
                assertTrue(stylesheet.contains("max-width: 100%; height: auto;"))
                assertTrue(stylesheet.contains("font-style: italic; color: ${palette.Accent};"))
                // Reference links: a step lighter than body ink with a faint rule-coloured underline.
                assertTrue(stylesheet.contains("a { color: ${palette.Link}; text-decoration-color: ${palette.Rule}; text-decoration-thickness: 1px;"))
            }
        }
        for ((requested, expected) in listOf(Int.MIN_VALUE to 14, 13 to 14, 14 to 14, 23 to 23, 30 to 30, 31 to 30, Int.MAX_VALUE to 30)) {
            val stylesheet = css(FolioText.document(emptyList(), "en", ReaderPreferences(fontSize = requested)))
            assertTrue(stylesheet.contains("font-size: ${expected}px;"))
        }
    }

    @Test
    fun compactVerseLabelsAreTargetedButKeepEveryRecordAndUnicodeInTheHtml() {
        val paragraphs = listOf(
            Paragraph(1, "Bg. 9.9 — कृष्ण a\u0301", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(2, "TEXT 9", "LE_Verse_Number", ""),
            Paragraph(3, "TEXT 9–10", " PA_Verse_Number_Heading ", "LE_Verse_Number"),
            Paragraph(4, "TEXT", "PA_Text_Section", "LE_Verse_Section"),
        )
        val original = paragraphs.toList()
        val html = FolioText.document(paragraphs, "sa", ReaderPreferences(), compactVerse = true)
        assertTrue(html.contains("<body class=\"verse-reading\" lang=\"sa\" dir=\"auto\">"))
        assertEquals(4, Regex("class=\"record [^\"]* repeated-label\"").findAll(main(html)).count())
        for (paragraph in paragraphs) {
            assertTrue(main(html).contains("id=\"record-${paragraph.record}\" data-record=\"${paragraph.record}\""))
            assertTrue(main(html).contains(paragraph.text))
        }
        assertTrue(css(html).contains(".verse-reading .record.repeated-label { display: none; }"))
        // Only the compact-verse labels plus the always-hidden audio launchers / legacy symbol-font script.
        assertEquals(2, Regex("display: none;").findAll(css(html)).count())
        assertTrue(css(html).contains(".record.audio-link, .record.legacy-script { display: none; }"))
        assertEquals(original, paragraphs)
        assertSafeFragment(main(html))
    }

    @Test
    fun compactVerseDoesNotHideBodyTranslationPurportChapterOrOtherSectionHeadings() {
        val paragraphs = mutableListOf(
            Paragraph(1, "TEXT", "", ""),
            Paragraph(2, "TEXT", "PA_Unknown", ""),
            Paragraph(3, "TEXT", "PA_Translation", ""),
            Paragraph(4, "TEXT", "PA_Purport", ""),
            Paragraph(5, "TEXT", "PA_Chapter", "LE_Chapter"),
            Paragraph(6, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
            Paragraph(7, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(8, "PURPORT", "PA_Titles", "LE_Verse_Section"),
            Paragraph(9, "कृष्ण Kṛṣṇa", "PA_DevaNagariQ", ""),
            Paragraph(10, "TEXT", "PA_Unknown", "LE_Verse_Number"),
            Paragraph(11, "TEXT", "PA_Text_Section_extra", "LE_Verse_Section"),
            Paragraph(12, "TEXT", "PA_Verse", ""),
        )
        // Even exact label styles must not override body/structural levels or unknown levels.
        for (style in listOf("PA_Textnum", "LE_Verse_Number", "PA_Verse_Number_Heading", "PA_Text_Section")) {
            for (level in listOf("LE_Translation", "LE_Purport", "LE_Chapter", "LE_Collection",
                "LE_Synonyms", "LE_Verse_Text", "LE_Devanagari", "LE_Unknown", "PA_Text")) {
                paragraphs.add(Paragraph(paragraphs.size + 1, "TEXT", style, level))
            }
        }
        val html = FolioText.document(paragraphs, "sa", ReaderPreferences(), compactVerse = true)
        assertFalse(main(html).contains("repeated-label"))
        assertEquals(paragraphs.size, Regex("data-record=\"").findAll(main(html)).count())
        for (paragraph in paragraphs) assertTrue(main(html).contains(paragraph.text))
        assertTrue(html.contains("class=\"record translation\" id=\"record-3\""))
        assertTrue(html.contains("class=\"record purport purport-content\" id=\"record-4\""))
        assertTrue(html.contains("class=\"record chapter\" id=\"record-5\""))
        assertSafeFragment(main(html))
    }

    @Test
    fun purportInkIncludesQuotedVersesNestedStylesLinksAndUnstyledContinuation() {
        val paragraphs = listOf(
            Paragraph(1, "Opening transliteration", "PA_Verse", ""),
            Paragraph(2, "<BD+>PURPORT<BD->", "PA_Titles", "LE_Verse_Section"),
            Paragraph(3, "Original prose कृष्ण a\u0301", "PA_First", ""),
            Paragraph(4, "Quoted śloka", "PA_Verse_in_purp", ""),
            Paragraph(5, "Another quoted verse", "PA_Verse", ""),
            Paragraph(6, "Quoted verse line", "", "LE_Verse_Text"),
            Paragraph(7, "<PS:\"Verse\"><CS:\"Italic\">Nested quote</CS><JL:Jump,\"target\">reference<EL>", "PA_Purport", ""),
            Paragraph(8, "Unstyled continuation", "", ""),
            Paragraph(9, "Original following prose", "PA_After_Verse", ""),
        )
        val original = paragraphs.toList()
        val html = FolioText.document(paragraphs, "en", ReaderPreferences())
        val marked = Regex("class=\"record [^\"]*purport-content\" id=\"record-(\\d+)\"")
            .findAll(main(html)).map { it.groupValues[1].toInt() }.toList()
        assertEquals((3..9).toList(), marked)
        assertTrue(main(html).contains("<em>Nested quote</em>"))
        assertTrue(main(html).contains("<a href=\"https://reader.local/links/jump?target=target\">reference</a>"))
        assertTrue(main(html).contains("Original prose कृष्ण a\u0301"))
        assertEquals(paragraphs.size, Regex("data-record=\"").findAll(main(html)).count())
        assertEquals(original, paragraphs)
        assertSafeFragment(main(html))
    }

    @Test
    fun purportInkStopsAtSectionVerseChapterAndUnknownStructuralBoundaries() {
        val boundaries = listOf(
            Paragraph(3, "Next verse", "PA_Verse_Number_Heading", "LE_Verse_Number"),
            Paragraph(3, "Next verse", "PA_Textnum", ""),
            Paragraph(3, "TEXT", "PA_Text_Section", "LE_Verse_Section"),
            Paragraph(3, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(3, "Another chapter", "PA_Purport", "LE_Chapter"),
            Paragraph(3, "Another collection", "", "LE_Collection"),
            Paragraph(3, "Unknown section", "", "LE_Section"),
            Paragraph(3, "Translation body", "PA_Translation", ""),
            Paragraph(3, "Synonyms body", "PA_Synonyms", ""),
        )
        for (boundary in boundaries) {
            val html = FolioText.document(listOf(
                Paragraph(1, "Prose", "PA_Purport", ""),
                Paragraph(2, "Quoted verse", "PA_Verse", ""),
                boundary,
                Paragraph(4, "Next main verse remains accented", "PA_Verse", ""),
            ), "en", ReaderPreferences())
            val marked = Regex("class=\"record [^\"]*purport-content\" id=\"record-(\\d+)\"")
                .findAll(main(html)).map { it.groupValues[1].toInt() }.toList()
            assertEquals("Boundary: $boundary", listOf(1, 2), marked)
            assertSafeFragment(main(html))
        }
    }

    @Test
    fun purportScopeNeedsSourceMetadataNotAnOrdinaryBodyWord() {
        val html = FolioText.document(listOf(
            Paragraph(1, "PURPORT", "", ""),
            Paragraph(2, "Main verse", "PA_Verse", ""),
            Paragraph(3, "Recognized standalone quoted verse", "PA_Verse_in_purp", ""),
        ), "en", ReaderPreferences())
        assertFalse(main(html).contains("class=\"record verse purport-content\""))
        assertTrue(main(html).contains("class=\"record verse-in-purport purport-content\" id=\"record-3\""))
        assertSafeFragment(main(html))
    }

    @Test
    fun purportColourOverridesEveryDescendantButPreservesLinkUnderlinesInAllThemes() {
        for (paper in ReaderPaper.entries) {
            val ink = ReaderPaperAppearance.palette(paper).Ink
            for ((bookMode, scrollingBook) in listOf(false to false, true to false, true to true)) {
                val stylesheet = css(FolioText.document(emptyList(), "en", ReaderPreferences(paper = paper),
                    bookMode = bookMode, scrollingBook = scrollingBook))
                val rule = stylesheet.substringAfter(".record.purport-content,").substringBefore('}')
                assertTrue(rule.contains(".record.purport-content *"))
                assertTrue(rule.contains(".folio-block.purport *"))
                assertTrue(rule.contains(".folio-block.verse-in-purport *"))
                assertTrue(rule.contains("color: $ink;"))
                assertTrue(stylesheet.indexOf(".record.purport-content,") > stylesheet.indexOf("a { color:"))
                assertTrue(stylesheet.indexOf(".record.purport-content,") > stylesheet.indexOf(".folio-block.verse,"))
                assertFalse(rule.contains("font-style"))
                assertFalse(rule.contains("text-align"))
                assertFalse(rule.contains("text-decoration"))
                assertTrue(stylesheet.contains("text-underline-offset: .18em;"))
            }
        }
    }

    @Test
    fun genericModeKeepsItsBodyStructureAndAllLabelClassesUnchanged() {
        val paragraphs = listOf(
            Paragraph(1, "Bg. 9.9", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(2, "TEXT 9", "LE_Verse_Number", ""),
            Paragraph(3, "TEXT 9", "PA_Verse_Number_Heading", ""),
            Paragraph(4, "TEXT", "PA_Text_Section", "LE_Verse_Section"),
        )
        val html = FolioText.document(paragraphs, "en", ReaderPreferences())
        assertEquals(html, FolioText.document(paragraphs, "en", ReaderPreferences(), compactVerse = false))
        assertTrue(html.contains("<body lang=\"en\" dir=\"auto\"><main class=\"reader\">"))
        assertFalse(html.contains("<body class="))
        assertFalse(main(html).contains("repeated-label"))
        val classes = listOf("verse-number", "verse-number", "verse-number-heading", "text-section")
        for ((paragraph, kind) in paragraphs.zip(classes)) {
            assertTrue(main(html).contains("<div class=\"record $kind\" id=\"record-${paragraph.record}\" " +
                "data-record=\"${paragraph.record}\" dir=\"auto\">${block(paragraph.text, kind)}</div>"))
        }
        assertSafeFragment(main(html))
    }

    @Test
    fun brightHomePaletteStaysSeparateFromReaderPaperColorsAndSharedTypography() {
        assertEquals("#B34E15", ReaderAppearance.Header)
        assertEquals("#FFF9EF", ReaderAppearance.Paper)
        assertEquals("#FBEEDC", ReaderAppearance.Chapter)
        assertEquals("#2F2B26", ReaderAppearance.Ink)
        assertEquals("#8A5A12", ReaderAppearance.Accent)
        assertEquals("#8A4A0E", ReaderAppearance.Link)
        assertEquals("#FFFFFF", ReaderAppearance.OnHeader)
        assertEquals("#6C6053", ReaderAppearance.Muted)
        assertEquals("#E6CFAE", ReaderAppearance.Rule)
        assertEquals(20, ReaderPreferences().fontSize)
        val stylesheet = css(FolioText.document(emptyList(), "en", ReaderPreferences()))
        assertTrue(stylesheet.contains("background: ${ReaderPaperAppearance.Paper};"))
        assertTrue(stylesheet.contains("background: ${ReaderPaperAppearance.Rule};"))
        assertTrue(stylesheet.contains("color: ${ReaderPaperAppearance.Ink};"))
        assertTrue(stylesheet.contains("a { color: ${ReaderPaperAppearance.Link};"))
        assertTrue(stylesheet.contains("font-style: italic; color: ${ReaderPaperAppearance.Accent};"))
        assertTrue(stylesheet.contains("color: ${ReaderPaperAppearance.Muted}; font-size: .75em; letter-spacing: .08em;"))
        assertTrue(stylesheet.contains("font-family: 'Fira Sans Condensed', 'Reader Serif Devanagari', \"Noto Sans Bengali\", sans-serif;"))
        assertTrue(stylesheet.contains("padding: .65rem 16px 1.25rem;"))
        assertTrue(stylesheet.contains("font-family: 'Reader Serif Devanagari', \"Noto Serif Devanagari\", \"Noto Serif Bengali\", serif;"))
        val devanagari = stylesheet.substringAfter(".folio-block.devanagari {").substringBefore('}')
        assertTrue(devanagari.contains("font-size: 1.15em;"))
        assertTrue(devanagari.contains("font-weight: 600;"))
        assertEquals(7, Regex("@font-face", RegexOption.IGNORE_CASE).findAll(stylesheet).count())
        assertFalse(stylesheet.contains("Literata"))
        assertDefaultLineHeight(stylesheet, "1.4", devanagari)
        assertTrue(devanagari.contains("font-style: normal;"))
        assertTrue(stylesheet.contains(".folio-block.devanagari em { font-style: normal; }"))
        assertTrue(stylesheet.contains("text-align: justify; text-align-last: start; text-justify: inter-word;"))
        assertTrue(stylesheet.contains(".folio-block.translation { font-weight: 700; }"))
        assertTrue(stylesheet.contains(".folio-block.synonyms { color: ${ReaderPaperAppearance.Ink};"))
        assertTrue(stylesheet.contains(".record:empty { margin: 0; }"))
        assertFalse(stylesheet.contains("border-inline-start"))
        assertOnlyBundledFontUrls(stylesheet)
    }

    @Test
    fun modestMarginsAndJustifiedBodyKeepStylesScopedAndSourceUnchanged() {
        val paragraphs = listOf(
            Paragraph(1, "<BD+>Plain<BD-> & original text.", "", ""),
            Paragraph(2, "<IT+>Original prose<IT-> continues.", "PA_Purport", ""),
            Paragraph(3, "<JL:Jump,\"target\">Original translation<EL>", "PA_Translation", ""),
            Paragraph(4, "term — original meaning कृष्ण a\u0301", "PA_Synonyms", ""),
            Paragraph(5, "<JU:CN><BD+>First line<BD-><CR>Second line", "", "LE_Verse_Text"),
            Paragraph(6, "<IT+>Another original line<IT->", "", "LE_Verse_Text"),
            Paragraph(7, "PURPORT", "PA_Titles", "LE_Verse_Section"),
        )
        val original = paragraphs.toList()
        val documents = listOf(false, true).map { bookMode ->
            FolioText.document(paragraphs, "en", ReaderPreferences(),
                collapsibleSynonyms = false, bookMode = bookMode)
        }
        assertEquals("Layout changes must not rewrite source markup or record order",
            main(documents[0]), main(documents[1]).replace(" book-purport-heading", ""))
        for ((index, html) in documents.withIndex()) {
            val bookMode = index == 1
            val rules = Regex("([^{}]+)\\{([^{}]*)}").findAll(css(html)).map { match ->
                match.groupValues[1].split(',').map(String::trim).toSet() to match.groupValues[2]
            }.toList()
            fun rule(selector: String) = rules.filter { selector in it.first }.joinToString("\n") { it.second }
            val bodySelectors = setOf(".folio-block.text", ".folio-block.purport",
                ".folio-block.translation", ".folio-block.synonyms")
            val justified = rules.filter { it.second.contains("text-align: justify;") }
            assertEquals(if (bookMode) 2 else 1, justified.size)
            val body = justified.single { ".folio-block.text" in it.first }
            assertEquals("Only prose kinds share the body justification rule", bodySelectors, body.first)
            assertTrue(body.second.contains("text-align-last: start;"))
            assertTrue(body.second.contains("text-justify: inter-word;"))
            assertFalse(body.second.contains("letter-spacing"))
            assertTrue(rule(".reader").contains("padding: .65rem 16px 1.25rem;"))
            assertFalse(rule(".record").contains("padding"))
            assertFalse(rule(".verse-group").contains("padding"))
            for (selector in listOf(".folio-block.verse", ".folio-block.verse-line",
                ".folio-block.verse-in-purport", ".folio-block.devanagari", ".folio-block.text-section",
                ".folio-block.align-center")) {
                assertTrue("$selector retains source centering", rule(selector).contains("text-align: center;"))
                assertFalse(rule(selector).contains("text-align-last: start;"))
            }
            assertTrue(rule(".folio-block.verse-line").contains("font-style: italic;"))
            assertTrue(rule(".folio-block.align-right").contains("text-align: right;"))
            val fragmentation = rules.filter { it.second.contains("break-inside:") }
            if (bookMode) {
                val flow = rule(".book-flow.reader")
                for (declaration in listOf("width: 100vw;", "margin: 16px 0;", "padding: 0;",
                    "height: calc(100vh - 32px);", "column-gap: 0;", "column-fill: auto;")) {
                    assertTrue(declaration, flow.contains(declaration))
                }
                val gutters = rules.single { ".book-flow > .record" in it.first }
                assertEquals(setOf(".book-flow > .record", ".book-flow > .verse-group"), gutters.first)
                assertTrue(gutters.second.contains("padding-inline: 16px;"))
                assertEquals(1, fragmentation.size)
                assertEquals(setOf(".book-flow .record", ".book-flow .folio-block", ".book-flow .verse-group"),
                    fragmentation.single().first)
                for (declaration in listOf("break-inside: auto;", "widows: 1;", "orphans: 1;")) {
                    assertTrue(fragmentation.single().second.contains(declaration))
                }
                assertTrue(rule(".book-flow .record.text-section").contains("break-after: avoid;"))
            } else {
                assertTrue("Book fragmentation must not affect scrolling verse mode", fragmentation.isEmpty())
                assertFalse(css(html).contains(".book-flow"))
            }
            val content = main(html)
            assertEquals(paragraphs.map { it.record }, Regex("data-record=\"(\\d+)\"").findAll(content)
                .map { it.groupValues[1].toInt() }.toList())
            for (paragraph in paragraphs) {
                assertEquals(1, Regex("id=\"record-${paragraph.record}\"").findAll(content).count())
            }
            assertTrue(content.contains(block("<strong>Plain</strong> &amp; original text.")))
            assertTrue(content.contains(block("<em>Original prose</em> continues.", "purport")))
            assertTrue(content.contains(block("<a href=\"https://reader.local/links/jump?target=target\">Original translation</a>", "translation")))
            assertTrue(content.contains(block(paragraphs[3].text, "synonyms")))
            assertTrue(content.contains(block("<strong>First line</strong><br>Second line", "verse-line align-center")))
            assertTrue(content.contains(block("<em>Another original line</em>", "verse-line")))
            assertTrue(content.contains(block("PURPORT", "text-section")))
            assertSafeFragment(content)
        }
        assertEquals(original, paragraphs)
    }

    @Test
    fun verseSynonymsSummaryIsCenteredAndStillStartsCollapsed() {
        val html = FolioText.document(listOf(
            Paragraph(1, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
            Paragraph(2, "word — meaning", "PA_Synonyms", ""),
        ), "en", ReaderPreferences(), compactVerse = true, useReadingFont = true)
        val group = disclosures(html).single().value
        assertTrue(group.contains("<summary"))
        assertTrue(group.contains("word — meaning"))
        assertFalse(Regex("<details[^>]*\\bopen\\b").containsMatchIn(main(html)))
        val summaryStyle = css(html).substringAfter(".synonyms-group > summary {").substringBefore('}')
        assertTrue(summaryStyle.contains("text-align: center;"))
        assertTrue(summaryStyle.contains("list-style-position: inside;"))
        assertFalse(main(html).contains("book-synonyms-heading"))
        assertSafeFragment(main(html))
    }

    @Test
    fun bookSynonymsAlwaysFlowWithoutDisclosureAndOnlyTheirSourceHeadingsAreHidden() {
        val paragraphs = listOf(
            Paragraph(1, "<BD+>SYNONYMS<BD->", "PA_Titles", "LE_Verse_Section"),
            Paragraph(2, "<IT+>Kṛṣṇa कृष्ण a\u0301<IT-> — <JL:Jump,\"target\">meaning<EL>", "PA_Synonyms", ""),
            Paragraph(3, "More original meanings", "PA_Synonyms_Section", ""),
            Paragraph(4, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(5, "Original translation", "PA_Translation", ""),
            Paragraph(6, "SYNONYMS", "PA_Synonyms_Section", "LE_Verse_Section"),
            Paragraph(7, "Next verse meanings", "PA_Synonyms", ""),
        )
        val original = paragraphs.toList()
        for (disclosurePreference in listOf(true, false)) {
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(),
                collapsibleSynonyms = disclosurePreference, bookMode = true)
            val body = main(html)
            assertFalse(body.contains("<details"))
            assertFalse(body.contains("<summary"))
            assertTrue(body.contains("class=\"record text-section book-synonyms-heading\" id=\"record-1\""))
            assertTrue(body.contains("class=\"record text-section book-synonyms-heading\" id=\"record-6\""))
            assertEquals(2, Regex("book-synonyms-heading").findAll(body).count())
            assertEquals(paragraphs.size, Regex("data-record=\"").findAll(body).count())
            assertTrue(body.contains("<em>Kṛṣṇa कृष्ण a\u0301</em>"))
            assertTrue(body.contains("https://reader.local/links/jump?target=target"))
            assertTrue(body.contains("More original meanings"))
            assertTrue(body.contains("Next verse meanings"))
            assertTrue(body.contains("Original translation"))
            assertTrue(css(html).contains(".book-flow .record.book-synonyms-heading { display: none; }"))
            assertSafeFragment(body)
        }
        assertEquals(original, paragraphs)
    }

    @Test
    fun bookDoesNotHideOrdinarySynonymsWordsOrGenerateAHeadingForBodyOnlyText() {
        val html = FolioText.document(listOf(
            Paragraph(1, "SYNONYMS", "PA_Translation", "LE_Translation"),
            Paragraph(2, "SYNONYMS", "PA_Synonyms", ""),
            Paragraph(3, "SYNONYMS", "PA_Purport", "LE_Purport"),
            Paragraph(4, "SYNONYMS", "", ""),
        ), "en", ReaderPreferences(), bookMode = true)
        val body = main(html)
        assertFalse(body.contains("book-synonyms-heading"))
        assertFalse(body.contains("<details"))
        assertFalse(body.contains("<summary"))
        assertEquals(4, Regex("SYNONYMS").findAll(body).count())
        assertEquals(4, Regex("data-record=\"").findAll(body).count())
        assertSafeFragment(body)
    }

    @Test
    fun searchHighlightMarksFoldedWholeWordsOnlyAndLeavesSourceAndMarkupSafe() {
        val source = Paragraph(7, "Kṛṣṇa said: <CS:\"Italic\">kṛṣṇa</CS> & Krsnaloka; <script>krsna</script>", "PA_Purport", "")
        val terms = setOf("krsna")
        val plain = FolioText.document(listOf(source), "en", ReaderPreferences(), bookMode = true)
        val marked = FolioText.document(listOf(source), "en", ReaderPreferences(), bookMode = true, highlight = terms)
        val body = main(marked)
        assertEquals(3, Regex("<mark class=\"search-hit\">").findAll(body).count())
        assertTrue(body.contains("<mark class=\"search-hit\">Kṛṣṇa</mark> said: "))
        assertTrue("Marks nest inside existing inline styling", body.contains("<em><mark class=\"search-hit\">kṛṣṇa</mark></em>"))
        assertTrue("Partial words are not marked", body.contains("&amp; Krsnaloka; "))
        assertTrue("Injected markup stays escaped even when it contains a hit",
            body.contains("&lt;script&gt;<mark class=\"search-hit\">krsna</mark>&lt;/script&gt;"))
        assertFalse(body.contains("<script"))
        assertEquals("Marks are the only difference", main(plain), body.replace(Regex("</?mark[^>]*>"), ""))
        assertEquals("Source text is never rewritten", "Kṛṣṇa said: kṛṣṇa & Krsnaloka; <script>krsna</script>", FolioText.plain(source.text))
        assertSafeFragment(body.replace("<mark class=\"search-hit\">", "<em>").replace("</mark>", "</em>"))
        assertEquals(main(plain), main(FolioText.document(listOf(source), "en", ReaderPreferences(), bookMode = true, highlight = emptySet())))
        val css = ReaderCssTestSupport.stylesheet(marked)
        assertTrue(css.contains("mark.search-hit { background: var(--reader-hit); color: inherit;"))
        assertEquals("#F6DF7C", ReaderCssTestSupport.definitions(css)["--reader-hit"])
        assertEquals("#6a5a1f", ReaderCssTestSupport.definitions(ReaderCssTestSupport.stylesheet(
            FolioText.document(listOf(source), "en", ReaderPreferences(paper = ReaderPaper.DARK), bookMode = true)))["--reader-hit"])
    }

    @Test
    fun emptyAndMetadataOnlyInputProduceNoVisibleBlocks() {
        for (source in listOf("", "<PS:\"Purport\"><FC:1;2;3><CS:\"Italic\"></CS>")) {
            assertEquals("", FolioText.plain(source))
            assertEquals("", FolioText.fragment(source))
        }
        assertEquals("", main(FolioText.document(emptyList(), "en", ReaderPreferences())))
    }

    @Test
    fun syntheticMalformedCombinationsAlwaysHaveBalancedWhitelistedMarkup() {
        val random = Random(741L)
        val pieces = listOf("x & y", "कृष्ण", "<", "<script>bad</script>", "<BD+>", "<BD>", "<BD->",
            "<IT+>", "<IT>", "<CS:\"Bold_Italic\">", "</CS>", "<JL:Jump,\"a>b\">",
            "<QL:Query,unquoted>", "<EL>", "<HR>", "<CR>", "<TB>", "<TA>", "</TA>", "<RO>",
            "</RO>", "<CE>", "</CE>", "<JU:CN>", "<JU:RT>", "<PS:\"Purport\">", "<OB:\"OB\";\"x.bmp\";1;2>")
        repeat(100) {
            val source = buildString { repeat(40) { append(pieces[random.nextInt(pieces.size)]) } }
            assertSafeFragment(FolioText.fragment(source))
        }
    }

    private fun block(content: String, classes: String = "text") = "<div class=\"folio-block $classes\" dir=\"auto\">$content</div>"
    private fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun css(html: String) = ReaderCssTestSupport.resolvePalette(ReaderCssTestSupport.stylesheet(html))
    private fun main(html: String) = html.substringAfter("<main class=\"reader").substringAfter('>').substringBefore("</main>")
    private fun disclosures(html: String) = Regex("<details class=\"synonyms-group\">.*?</details>",
        RegexOption.DOT_MATCHES_ALL).findAll(main(html)).toList()

    private fun assertDefaultLineHeight(stylesheet: String, base: String, rule: String) {
        if (stylesheet.contains("var(--reader-line-factor)")) {
            assertEquals("1.00", ReaderCssTestSupport.definitions(stylesheet)["--reader-line-factor"])
            assertTrue(rule.contains("line-height: calc($base * var(--reader-line-factor));"))
        } else assertTrue(rule.contains("line-height: $base;"))
    }

    private fun assertOnlyBundledFontUrls(stylesheet: String) {
        val expected = listOf(
            "FiraSansCondensed-Regular.ttf", "FiraSansCondensed-Italic.ttf",
            "FiraSansCondensed-Medium.ttf", "FiraSansCondensed-SemiBold.ttf",
            "FiraSansCondensed-Bold.ttf", "FiraSansCondensed-BoldItalic.ttf", "NotoSerifDevanagari.ttf",
        ).map { "https://reader.local/fonts/$it" }
        val urls = Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE).findAll(stylesheet)
            .map { it.groupValues[1].trim().trim('\'', '"') }.toList()
        assertEquals("Only the seven exact bundled default font URLs may enter CSS", expected, urls)
        assertEquals(7, Regex("@font-face", RegexOption.IGNORE_CASE).findAll(stylesheet).count())
        assertFalse(stylesheet.contains("@import", ignoreCase = true))
        assertFalse(stylesheet.contains("Literata", ignoreCase = true))
        assertFalse(stylesheet.contains("Georgia", ignoreCase = true))
    }

    /** Checks our deliberately restricted output grammar, including table parents and anchor scopes. */
    private fun assertSafeFragment(html: String) {
        val allowed = setOf("div", "span", "details", "summary", "a", "strong", "em", "u", "s", "br", "hr", "img", "table", "tbody", "tr", "td")
        val voids = setOf("br", "hr", "img")
        val inline = setOf("span", "a", "strong", "em", "u", "s")
        val blocks = setOf("div", "details", "summary", "hr", "table", "tbody", "tr", "td")
        val stack = mutableListOf<String>()
        val tagPattern = Regex("<(/?)([a-z][a-z0-9]*)([^>]*)>")
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
                assertFalse("Closing void tag: $name", name in voids)
                assertEquals("Crossed or missing tag in $html", name, stack.lastOrNull())
                stack.removeAt(stack.lastIndex)
            } else {
                if (name == "a") assertFalse("Nested anchors", "a" in stack)
                if (name in blocks) assertFalse("Block inside inline markup", stack.any { it in inline })
                if (name in blocks) assertFalse("Non-phrasing summary content", "summary" in stack)
                when (name) {
                    "details" -> assertFalse("Nested disclosures", "details" in stack)
                    "summary" -> assertEquals("details", stack.lastOrNull())
                    "tbody" -> assertEquals("table", stack.lastOrNull())
                    "tr" -> assertEquals("tbody", stack.lastOrNull())
                    "td" -> assertEquals("tr", stack.lastOrNull())
                    "div", "hr" -> assertFalse(stack.lastOrNull() in setOf("table", "tbody", "tr"))
                }
                val allowedAttributes = when (name) {
                    "div", "summary" -> setOf("class", "dir", "id", "data-record")
                    "span" -> setOf("class", "dir")
                    "details" -> setOf("class")
                    "a" -> setOf("href")
                    "img" -> setOf("src", "alt", "loading", "decoding")
                    else -> emptySet()
                }
                assertEquals("Unquoted or malformed attributes", "", attributePattern.replace(attributes, "").trim())
                for (attribute in attributePattern.findAll(attributes)) {
                    val key = attribute.groupValues[1]
                    val value = attribute.groupValues[2]
                    assertTrue("Unexpected attribute: $key", key in allowedAttributes)
                    assertFalse("Unescaped attribute", '<' in value || '>' in value)
                    if (key == "href") assertTrue(value.matches(Regex("https://reader\\.local/links/(jump|query|popup|audio)\\?target=[^\\s]+")))
                    if (key == "src") assertTrue(value.startsWith("https://reader.local/objects/"))
                }
                if (name !in voids) stack.add(name)
            }
        }
        val tail = html.substring(cursor)
        assertFalse("Unescaped trailing text", '<' in tail || '>' in tail)
        assertTrue("Unclosed tags: $stack", stack.isEmpty())
    }
}