package org.vedareader.reader

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ContentObject
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Synthetic source only, real WebView layout/fonts/native gestures. No database/device settings
 * writes, sleeps, JS bridges, screenshots, copied chapters, or external network dependencies.
 * Intentionally exercises Folio's production bookMode CSS rather than a test-only paginator.
 */
@RunWith(AndroidJUnit4::class)
class BookPaginationTest {
    @Test
    fun fractionalExtentRoundingDoesNotAddABlankLastPage() {
        assertEquals(1, BookPagination.pageCount(0.0, 360.0))
        assertEquals(1, BookPagination.pageCount(360.0, 360.0))
        assertEquals(3, BookPagination.pageCount(1080.0, 360.0))
        assertEquals(3, BookPagination.pageCount(1081.0, 360.0))
        assertEquals(4, BookPagination.pageCount(1082.0, 360.0))
        assertEquals(3, BookPagination.pageCount(1079.0, 359.5))
        assertEquals(742 * 3, BookPagination.offset(3, 742))
        assertEquals(944, BookPagination.offset(1, 359.75, 2.625))
        assertEquals(34941, BookPagination.offset(37, 359.75, 2.625)) // Round the absolute offset, not each step.
    }

    @Test
    fun oneLongParagraphFragmentsAcrossExactViewportColumnsWithoutVerticalScrolling() {
        Fixture(book(prose(101))).use { fixture ->
            fixture.awaitReady()
            val first = fixture.info()
            assertTrue("One paragraph must span at least three screen pages", first.count >= 3)
            assertEquals(0, first.page)
            assertEquals(101, first.anchorRecord)
            assertTrue(fixture.json("""
                (function(){return {fragments:document.getElementById('record-101').getClientRects().length,
                    y:window.scrollY, fonts:document.fonts.status};})()
            """).getInt("fragments") >= 3)

            for (page in 1 until first.count) {
                fixture.turn(true)
                fixture.assertPage(page, first.count)
                assertEquals(101, fixture.info().anchorRecord)
            }
            var boundaryCallbacks = 0
            fixture.ui { assertFalse(turnBookPage(true) { boundaryCallbacks++ }) }
            fixture.drain()
            assertEquals(0, boundaryCallbacks)
            for (page in first.count - 2 downTo 0) {
                fixture.turn(false)
                fixture.assertPage(page, first.count)
            }
            fixture.ui {
                assertFalse(turnBookPage(false) { boundaryCallbacks++ })
                scrollTo(scrollX, 9999)
                assertEquals(0, scrollY)
                assertEquals(0, scrollRange)
            }
            assertEquals(0, boundaryCallbacks)
            assertEquals(1, fixture.rendered.get())
            assertEquals("Only initial layout and accepted turns publish pages", first.count * 2 - 1, fixture.reports.size)
            val geometry = fixture.geometry()
            val pitch = geometry.getDouble("pitch")
            fixture.reports.forEach { report ->
                assertEquals(report.info.nativeOffset, report.x)
                assertEquals((report.info.page * pitch * report.scale).roundToInt(), report.x)
                assertEquals(0, report.y)
                assertEquals((pitch * report.scale).roundToInt(), report.info.viewportWidth)
                assertEquals(geometry.getDouble("dpr") * geometry.getDouble("visualScale"), report.scale, .00001)
                assertEquals(report.width / report.scale, geometry.getDouble("width"), 1.0)
            }
        }
    }

    @Test
    fun successiveVerseTranslationsShareAPageInsteadOfForcingVerseBreaks() {
        val paragraphs = listOf(
            Paragraph(10, "TEXT 1", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(11, "First short synthetic translation.", "PA_Translation", ""),
            Paragraph(20, "TEXT 2", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(21, "Second short synthetic translation.", "PA_Translation", ""),
        )
        Fixture(book(*paragraphs.toTypedArray(), fontSize = 16)).use { fixture ->
            fixture.awaitReady()
            assertEquals(1, fixture.info().count)
            val positions = fixture.json("""
                (function(){var pitch=document.querySelector('.book-flow').getBoundingClientRect().width;
                    var a=document.getElementById('record-11').getClientRects()[0];
                    var b=document.getElementById('record-21').getClientRects()[0];
                    return {a:Math.floor((a.left+window.scrollX+.5)/pitch),
                        b:Math.floor((b.left+window.scrollX+.5)/pitch), below:b.top>a.top};})()
            """)
            assertEquals(0, positions.getInt("a"))
            assertEquals(0, positions.getInt("b"))
            assertTrue(positions.getBoolean("below"))
        }
    }

    @Test
    fun initialAnchorUsesTheFirstFragmentAndEndLandingOverridesTheAnchor() {
        val html = book(prose(100, 170), prose(200, 200), prose(300, 70))
        Fixture(html, initialAnchor = 200).use { fixture ->
            fixture.awaitReady()
            val location = fixture.record(200)
            assertTrue(location.getInt("firstPage") > 0)
            assertEquals(location.getInt("firstPage"), fixture.info().page)
            assertTrue(location.getBoolean("visible"))
            fixture.assertPage(fixture.info().page, fixture.info().count)
        }
        Fixture(html, initialAnchor = 100, atEnd = true).use { fixture ->
            fixture.awaitReady()
            val info = fixture.info()
            fixture.assertPage(info.count - 1, info.count)
            assertTrue(fixture.record(300).getBoolean("visible"))
            var callbacks = 0
            fixture.ui { assertFalse(turnBookPage(true) { callbacks++ }) }
            assertEquals(0, callbacks)
            fixture.turn(false)
            fixture.assertPage(info.count - 2, info.count)
        }
    }

    @Test
    fun missingAnchorAndShortFinalDocumentClampToARealPage() {
        Fixture(book(prose(10, 4)), initialAnchor = Int.MAX_VALUE, atEnd = true).use { fixture ->
            fixture.awaitReady()
            fixture.assertPage(0, 1)
            fixture.ui {
                assertFalse(turnBookPage(true) { error("No next page") })
                assertFalse(turnBookPage(false) { error("No previous page") })
            }
        }
        Fixture(book(prose(10, 4)), initialAnchor = 9999).use { fixture ->
            fixture.awaitReady()
            fixture.assertPage(0, 1)
        }
    }

    @Test
    fun nativeResizeAndTextZoomAutomaticallyReflowWithoutLosingTheVisibleParagraph() {
        Fixture(book(prose(101, 320))).use { fixture ->
            fixture.awaitReady()
            fixture.turn(true)
            fixture.turn(true)
            val before = fixture.info()
            var newWidth = 0
            fixture.ui {
                newWidth = width * 3 / 4
                layoutParams = layoutParams.apply { width = newWidth; height = this@ui.height * 3 / 4 }
                requestLayout()
            }
            fixture.awaitPage("Resized CSS viewport") { width == newWidth && it.count > before.count }
            val resized = fixture.info()
            assertEquals(before.anchorRecord, resized.anchorRecord)
            assertTrue("Do not reset a multi-column paragraph to its beginning", resized.page > 0)
            fixture.assertPage(resized.page, resized.count)

            fixture.ui { settings.textZoom = 155; invalidate() }
            fixture.awaitPage("Text zoom must trigger anchored reflow without reloading") { it.count > resized.count }
            val zoomed = fixture.info()
            assertEquals(before.anchorRecord, zoomed.anchorRecord)
            assertTrue(zoomed.page > 0)
            fixture.assertPage(zoomed.page, zoomed.count)
            assertEquals(1, fixture.rendered.get())
        }
    }

    @Test
    fun visibleSynonymsBodyAnchorLandsOnItsLaterPageWithSourceHeadingHidden() {
        val body = synonyms(21)
        Fixture(book(prose(10, 170), synonymsHeading(20), body, prose(30, 4)), initialAnchor = 21).use { fixture ->
            fixture.awaitReady()
            assertEquals("0", fixture.eval("document.querySelectorAll('details,summary').length"))
            val heading = fixture.record(20)
            assertEquals("record-20", heading.getString("id"))
            assertEquals(20, heading.getInt("record"))
            assertEquals("SYNONYMS", heading.getString("text"))
            assertEquals("none", heading.getString("display"))
            assertEquals(0, heading.getInt("fragments"))
            assertFalse(heading.getBoolean("visible"))
            val location = fixture.record(21)
            assertEquals(body.text, location.getString("text"))
            assertEquals("block", location.getString("display"))
            assertTrue("The real body starts on a later page without opening a disclosure", location.getInt("firstPage") > 0)
            assertTrue(location.getBoolean("visible"))
            fixture.assertPage(location.getInt("firstPage"), fixture.info().count)
            assertTrue("A hidden heading must never be the painted anchor", fixture.info().anchorRecord != 20)
            assertEquals(0, fixture.taps.get())
            assertEquals(0, fixture.navigation.get())
        }
    }

    @Test
    fun bodyOnlySynonymsAreVisibleWithoutAnImplicitSummaryAndRetainSourceAnchor() {
        val body = synonyms(21)
        Fixture(book(body), initialAnchor = 21).use { fixture ->
            fixture.awaitReady()
            assertTrue("The body is laid out in full from the start", fixture.info().count > 1)
            fixture.assertPage(0, fixture.info().count)
            assertEquals(21, fixture.info().anchorRecord)
            val location = fixture.record(21)
            assertEquals("record-21", location.getString("id"))
            assertEquals(21, location.getInt("record"))
            assertEquals(body.text, location.getString("text"))
            assertEquals("block", location.getString("display"))
            assertTrue(location.getBoolean("visible"))
            assertEquals("0", fixture.eval("document.querySelectorAll('details,summary,.book-synonyms-heading').length"))
            assertEquals("1", fixture.eval("document.querySelectorAll('.record[data-record]').length"))
        }
    }

    @Test
    fun alwaysVisibleSynonymsPaginateWithoutDisclosureOrSpuriousCallbacks() {
        Fixture(book(synonymsHeading(20), synonyms(21), prose(30, 3))).use { fixture ->
            fixture.awaitReady()
            val first = fixture.info()
            assertTrue("Synonyms must occupy their real pages before any interaction", first.count > 1)
            fixture.assertPage(0, first.count)
            assertEquals(21, first.anchorRecord)
            assertTrue(fixture.record(21).getBoolean("visible"))
            assertEquals("0", fixture.eval("document.querySelectorAll('details,summary').length"))
            for (page in 1 until first.count) {
                fixture.turn(true)
                fixture.assertPage(page, first.count)
                val anchor = fixture.info().anchorRecord
                assertTrue("Every page has visible body text, never a hidden heading or blank page", anchor in setOf(21, 30))
                assertTrue(fixture.record(anchor).getBoolean("visible"))
            }
            assertTrue(fixture.record(30).getBoolean("visible"))
            fixture.ui { assertFalse(turnBookPage(true) { error("No blank trailing page") }) }
            for (page in first.count - 2 downTo 0) {
                fixture.turn(false)
                fixture.assertPage(page, first.count)
            }
            fixture.drain()
            assertEquals(21, fixture.info().anchorRecord)
            assertTrue(fixture.record(21).getBoolean("visible"))
            assertEquals("0", fixture.eval("document.querySelectorAll('details,summary').length"))
            assertEquals(0, fixture.taps.get())
            assertEquals(0, fixture.links.get())
            assertEquals(0, fixture.navigation.get())
            assertEquals(1, fixture.rendered.get())
            assertEquals(first.count * 2 - 1, fixture.reports.size)
        }
    }

    @Test
    fun bookReferenceAndSectionTypographyPreserveSourceAndDefaultGeometry() {
        val paragraphs = listOf(
            Paragraph(10, "<PS:\"Verse Number Heading\">Bg 1.4", "PA_Verse_Number_Heading", "LE_Verse_Number"),
            Paragraph(11, "नमूना", "PA_DevaNagariQ", ""),
            Paragraph(12, "ā a\u0304", "", "LE_Verse_Text"),
            synonymsHeading(13),
            Paragraph(14, "<CS:\"Italic\">śabda</CS> — word.", "PA_Synonyms", ""),
            Paragraph(15, "TRANSLATION", "PA_Titles", "LE_Verse_Section"),
            Paragraph(16, "Sample.", "PA_Translation", ""),
            Paragraph(17, "PURPORT", "PA_Titles", "LE_Verse_Section"),
            Paragraph(18, "Note.", "PA_Purport", ""),
        )
        Fixture(FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true)).use { fixture ->
            fixture.awaitReady()
            // Keep source lines short, but the 360px-high fixture can still span two pages with
            // real section margins. Only the reference must be on the initial page; inspect
            // computed styles for later sections without requiring all content in one viewport.
            fixture.assertPage(0, fixture.info().count)
            val layout = fixture.json("""
                (function(){var v=visualViewport,flow=document.querySelector('.book-flow');
                    function visible(e){var s=getComputedStyle(e);
                        return s.display!=='none'&&s.visibility==='visible'&&
                            Array.from(e.getClientRects()).some(function(r){return r.width>0&&r.height>0&&
                                r.right-v.offsetLeft>0&&r.left-v.offsetLeft<v.width&&
                                r.bottom-v.offsetTop>0&&r.top-v.offsetTop<v.height;});}
                    function style(selector){var e=document.querySelector(selector),s=getComputedStyle(e);
                        return {text:e.textContent,align:s.textAlign,weight:s.fontWeight,style:s.fontStyle,
                            size:parseFloat(s.fontSize),color:s.color,family:s.fontFamily,visible:visible(e)};}
                    var source=document.querySelector('#record-10 > .source-book-heading');
                    var synonyms=document.getElementById('record-13');
                    return {reference:style('#record-10 > .folio-block.verse-number-heading'),
                        referenceCount:document.querySelectorAll('#record-10 > .folio-block.verse-number-heading').length,
                        sourceCount:document.querySelectorAll('.source-book-heading').length,
                        sourceHtml:source.innerHTML,sourceText:source.textContent,sourceParent:source.parentElement.id,
                        sourceDisplay:getComputedStyle(source).display,sourceFragments:source.getClientRects().length,
                        translationLabel:style('#record-15 .folio-block'),translation:style('#record-16 .folio-block'),
                        purportLabel:style('#record-17 .folio-block'),transliteration:style('#record-12 .folio-block'),
                        synonymTerm:style('#record-14 em'),synonymsDisplay:getComputedStyle(synonyms).display,
                        synonymsFragments:synonyms.getClientRects().length,
                        visibleSynonymsLabels:Array.from(document.querySelectorAll('.folio-block')).filter(function(e){
                            return e.textContent.trim().toUpperCase()==='SYNONYMS'&&visible(e);}).length,
                        disclosures:document.querySelectorAll('details,summary').length,
                        synonymsBodyDisplay:getComputedStyle(document.getElementById('record-14')).display,
                        body:style('body'),width:v.width,flowWidth:flow.getBoundingClientRect().width,
                        gutters:Array.from(flow.children).every(function(e){var s=getComputedStyle(e);
                            return parseFloat(s.paddingLeft)===16&&parseFloat(s.paddingRight)===16;}),
                        unpadded:Array.from(document.querySelectorAll('body,.reader,.verse-group > .record,.folio-block'))
                            .every(function(e){var s=getComputedStyle(e);
                                return parseFloat(s.paddingLeft)===0&&parseFloat(s.paddingRight)===0;}),
                        records:Array.from(document.querySelectorAll('.record[data-record]')).map(function(e){
                            return {id:e.id,record:Number(e.dataset.record),
                                idCount:document.querySelectorAll('[id="'+e.id+'"]').length,
                                recordCount:document.querySelectorAll('[data-record="'+e.dataset.record+'"]').length};})};})()
            """)
            val reference = layout.getJSONObject("reference")
            assertEquals("TEXT 4 ( BG 1.4 )", reference.getString("text"))
            assertTrue("The normalized display label is painted on the initial page", reference.getBoolean("visible"))
            assertEquals(1, layout.getInt("referenceCount"))
            assertEquals(1, layout.getInt("sourceCount"))
            assertEquals(FolioText.fragment(paragraphs[0].text), layout.getString("sourceHtml"))
            assertEquals("Bg 1.4", layout.getString("sourceText"))
            assertEquals("record-10", layout.getString("sourceParent"))
            assertEquals("none", layout.getString("sourceDisplay"))
            assertEquals(0, layout.getInt("sourceFragments"))
            for ((key, weight) in listOf("reference" to "700", "translationLabel" to "700", "purportLabel" to "400")) {
                val heading = layout.getJSONObject(key)
                assertEquals(key, "center", heading.getString("align"))
                assertEquals(key, weight, heading.getString("weight"))
                assertEquals(key, 20.0, heading.getDouble("size"), .01)
            }
            assertEquals("TRANSLATION", layout.getJSONObject("translationLabel").getString("text"))
            assertEquals("PURPORT", layout.getJSONObject("purportLabel").getString("text"))
            assertEquals("700", layout.getJSONObject("translation").getString("weight"))
            assertEquals(20.0, layout.getJSONObject("translation").getDouble("size"), .01)
            assertEquals(20.0, layout.getJSONObject("body").getDouble("size"), .01)
            assertEquals("italic", layout.getJSONObject("transliteration").getString("style"))
            assertEquals("italic", layout.getJSONObject("synonymTerm").getString("style"))
            assertEquals(layout.getJSONObject("body").getString("color"),
                layout.getJSONObject("transliteration").getString("color"))
            // Loaded font-face coverage is in fontReadyBookUsesBundledFacesAndStillDeniesNetworkStorageFileAndContentAccess.
            for (key in listOf("body", "reference", "translationLabel", "translation", "purportLabel", "transliteration", "synonymTerm")) {
                assertEquals(key, "Fira Sans Condensed",
                    layout.getJSONObject(key).getString("family").substringBefore(',').trim().trim('"', '\''))
            }
            assertEquals("none", layout.getString("synonymsDisplay"))
            assertEquals(0, layout.getInt("synonymsFragments"))
            assertEquals(0, layout.getInt("visibleSynonymsLabels"))
            assertEquals(0, layout.getInt("disclosures"))
            assertEquals("block", layout.getString("synonymsBodyDisplay"))
            assertTrue(layout.getBoolean("gutters"))
            assertTrue("Nested records must not double the 16px gutters", layout.getBoolean("unpadded"))
            assertEquals(layout.getDouble("width"), layout.getDouble("flowWidth"), 1.0)
            val records = layout.getJSONArray("records")
            assertEquals(paragraphs.size, records.length())
            paragraphs.forEachIndexed { index, paragraph ->
                val record = records.getJSONObject(index)
                assertEquals("record-${paragraph.record}", record.getString("id"))
                assertEquals(paragraph.record, record.getInt("record"))
                assertEquals(1, record.getInt("idCount"))
                assertEquals(1, record.getInt("recordCount"))
                if (index > 0) assertEquals(FolioText.plain(paragraph.text), fixture.record(paragraph.record).getString("text"))
            }
        }
    }

    @Test
    fun defaultsModestMarginsAndJustifiedBodyDoNotAddBlankPages() {
        val preferences = ReaderPreferences() // Deliberately do not use book()'s explicit 24px geometry fixture.
        assertEquals(20, preferences.fontSize)
        val paragraphs = listOf(
            Paragraph(10, "TEXT 1", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(11, "<BD+>Short synthetic verse.<BD->", "", "LE_Verse_Text"),
            synonymsHeading(12),
            Paragraph(13, "term - synthetic meaning.", "PA_Synonyms", ""),
            Paragraph(20, "TEXT 2", "PA_Textnum", "LE_Verse_Number"),
            Paragraph(21, "Short synthetic translation.", "PA_Translation", ""),
            Paragraph(30, "Short synthetic prose.", "PA_Purport", ""),
        )
        Fixture(FolioText.document(paragraphs, "en", preferences, bookMode = true)).use { fixture ->
            fixture.awaitReady()
            fixture.assertPage(0, 1) // Neither verse labels nor hidden headings force empty columns.
            val layout = fixture.json("""
                (function(){var v=window.visualViewport,flow=document.querySelector('main.reader');
                    var style=getComputedStyle(flow);
                    return {font:parseFloat(getComputedStyle(document.body).fontSize),
                        paragraphFont:parseFloat(getComputedStyle(document.querySelector('#record-21 .folio-block')).fontSize),
                        width:v.width,height:v.height,flowWidth:flow.getBoundingClientRect().width,
                        maxWidth:style.maxWidth,gap:parseFloat(style.columnGap),
                        unpadded:Array.from(document.querySelectorAll('body,.reader,.verse-group > .record,.folio-block'))
                            .every(function(e){var s=getComputedStyle(e);
                                return parseFloat(s.paddingLeft)===0&&parseFloat(s.paddingRight)===0;}),
                        gutters:Array.from(flow.children).every(function(e){var s=getComputedStyle(e);
                            return parseFloat(s.paddingLeft)===16&&parseFloat(s.paddingRight)===16;}),
                        verseStyle:getComputedStyle(document.querySelector('#record-11 .folio-block')).fontStyle,
                        emphasis:getComputedStyle(document.querySelector('#record-11 strong')).fontWeight,
                        boxes:[11,13,21,30].map(function(id){
                            var e=document.querySelector('#record-'+id+' .folio-block'),s=getComputedStyle(e);
                            var r=e.getClientRects()[0];
                            return {record:id,left:r.left-v.offsetLeft,right:r.right-v.offsetLeft,width:r.width,
                                align:s.textAlign,last:s.textAlignLast,spacing:s.letterSpacing,
                                top:r.top-v.offsetTop,bottom:r.bottom-v.offsetTop};})};})()
            """)
            assertEquals(20.0, layout.getDouble("font"), .01)
            assertEquals(20.0, layout.getDouble("paragraphFont"), .01)
            assertEquals("none", layout.getString("maxWidth"))
            assertEquals(0.0, layout.getDouble("gap"), .01)
            assertTrue("The full-pitch flow and nested records must not double the gutters", layout.getBoolean("unpadded"))
            assertTrue("Only direct records and verse groups provide the 16px gutters", layout.getBoolean("gutters"))
            assertEquals("italic", layout.getString("verseStyle"))
            assertTrue("Source bold emphasis survives", layout.getString("emphasis").toInt() >= 700)
            val pitch = layout.getDouble("flowWidth")
            assertEquals(layout.getDouble("width"), pitch, 1.0)
            val boxes = layout.getJSONArray("boxes")
            for (index in 0 until boxes.length()) {
                val box = boxes.getJSONObject(index)
                val label = "Record ${box.getInt("record")} has a single 16px inset on each side"
                assertEquals(label, 16.0, box.getDouble("left"), 1.0)
                assertEquals(label, pitch - 16.0, box.getDouble("right"), 1.0)
                assertEquals(label, pitch - 32.0, box.getDouble("width"), 1.0)
                assertEquals(if (box.getInt("record") == 11) "center" else "justify", box.getString("align"))
                if (box.getInt("record") != 11) assertEquals("start", box.getString("last"))
                assertEquals("normal", box.getString("spacing"))
                assertTrue(box.getDouble("top") >= -1.0)
                assertTrue(box.getDouble("bottom") <= layout.getDouble("height") + 1.0)
                assertTrue(fixture.record(box.getInt("record")).getBoolean("visible"))
            }
            assertEquals("0", fixture.eval("document.querySelectorAll('details,summary').length"))
            assertEquals("none", fixture.record(12).getString("display"))
            for (paragraph in paragraphs) {
                assertEquals(FolioText.plain(paragraph.text), fixture.record(paragraph.record).getString("text"))
            }
            fixture.ui {
                assertFalse(turnBookPage(true) { error("No blank trailing page") })
                assertFalse(turnBookPage(false) { error("No blank leading page") })
            }
        }
    }

    @Test
    fun verseGroupSplitsIntoRemainingPageSpaceWithoutLostTextOrEmptyColumns() {
        // Six 30px lines leave room for part, but not all, of a group that fits on an empty
        // 328px flow. No CSS injection: source CRs control only the synthetic lead's length.
        val lead = Paragraph(100, (1..6).joinToString("<CR>") { "Lead line $it." }, "", "")
        val verses = (1..8).map { Paragraph(100 + it, "Verse line $it.", "", "LE_Verse_Text") }
        val paragraphs = listOf(lead) + verses
        Fixture(book(*paragraphs.toTypedArray(), fontSize = 20)).use { fixture ->
            fixture.awaitReady()
            fixture.assertPage(0, 2)
            val group = fixture.json("""
                (function(){var v=visualViewport,flow=document.querySelector('.book-flow');
                    var pitch=flow.getBoundingClientRect().width;
                    var group=document.querySelector('.verse-group'),s=getComputedStyle(group);
                    return {pages:Array.from(group.getClientRects()).filter(function(r){return r.width>0&&r.height>0;})
                        .map(function(r){return Math.floor((r.left+window.scrollX+.5)/pitch);}),
                        height:flow.clientHeight,bottom:flow.getBoundingClientRect().bottom-v.offsetTop,
                        inside:s.breakInside,widows:s.widows,orphans:s.orphans,
                        unbrokenHeight:Array.from(group.children).reduce(function(n,e){var c=getComputedStyle(e);
                            return n+parseFloat(c.lineHeight)+parseFloat(c.marginTop)+parseFloat(c.marginBottom);},0),
                        records:document.querySelectorAll('.record[data-record]').length};})()
            """)
            assertEquals("auto", group.getString("inside"))
            assertEquals("1", group.getString("widows"))
            assertEquals("1", group.getString("orphans"))
            assertTrue("The group must fit on an empty page, so break-inside:avoid would expose the regression",
                group.getDouble("unbrokenHeight") < group.getDouble("height"))
            val pages = group.getJSONArray("pages")
            assertEquals(setOf(0, 1), (0 until pages.length()).map { pages.getInt(it) }.toSet())
            assertEquals(paragraphs.size, group.getInt("records"))
            assertEquals(0, fixture.record(101).getInt("firstPage"))
            assertTrue(fixture.record(108).getInt("firstPage") > 0)
            for (paragraph in paragraphs) {
                // textContent omits BRs, while the separate range check below counts every line.
                assertEquals(FolioText.plain(paragraph.text).replace("\n", ""),
                    fixture.record(paragraph.record).getString("text"))
            }
            for (page in 0..1) {
                if (page > 0) fixture.turn(true)
                fixture.assertPage(page, 2)
                var visibleWords = 0
                var bottom = 0.0
                var lineHeight = 0.0
                for (paragraph in paragraphs) {
                    val layout = fixture.textLayout(paragraph.record)
                    val words = layout.getJSONArray("words")
                    val expected = FolioText.plain(paragraph.text).split(Regex("\\s+"))
                    assertEquals("No source words lost or duplicated", expected,
                        (0 until words.length()).map { words.getJSONObject(it).getString("text") })
                    lineHeight = maxOf(lineHeight, layout.getDouble("lineHeight"))
                    for (index in 0 until words.length()) {
                        val word = words.getJSONObject(index)
                        if (word.getInt("page") == page) {
                            assertTrue("Word is painted in the current visual viewport", word.getBoolean("visible"))
                            visibleWords++
                            bottom = maxOf(bottom, word.getDouble("bottom"))
                        }
                    }
                }
                assertTrue("No empty column $page", visibleWords > 0)
                assertTrue(bottom <= group.getDouble("bottom") + 1.0)
                if (page == 0) {
                    assertTrue("Fill the first page to within one line plus the 0.12em record margins",
                        group.getDouble("bottom") - bottom <= lineHeight + 2 * .12 * 20 + 1)
                }
            }
            fixture.ui { assertFalse(turnBookPage(true) { error("No empty final column") }) }
        }
    }

    @Test
    fun justifiedProseKeepsBothContinuationInsetsAndDoesNotStretchTheLastLine() {
        // All naturally wrapped lines except the one before CR should justify. An explicit
        // short final source line makes the ragged-last-line assertion independent of font metrics.
        val source = List(45) { "alpha beta gamma delta" }.joinToString(" ") + "<CR>The end."
        Fixture(book(Paragraph(101, source, "PA_Purport", ""), fontSize = 20)).use { fixture ->
            fixture.awaitReady()
            val count = fixture.info().count
            assertTrue("Exercise continuation fragments", count >= 3)
            val expected = FolioText.plain(source).split(Regex("\\s+"))
            for (page in 0 until count) {
                if (page > 0) fixture.turn(true)
                fixture.assertPage(page, count)
                val layout = fixture.textLayout(101)
                assertEquals(20.0, layout.getDouble("font"), .01)
                assertEquals("justify", layout.getString("align"))
                assertEquals("start", layout.getString("last"))
                assertEquals("normal", layout.getString("spacing"))
                val pitch = layout.getDouble("pitch")
                val fragments = layout.getJSONArray("fragments")
                assertEquals("Exactly one paragraph fragment per real column", count, fragments.length())
                for (index in 0 until fragments.length()) {
                    val fragment = fragments.getJSONObject(index)
                    assertEquals(index, fragment.getInt("page"))
                    assertEquals("Left continuation inset", 16.0, fragment.getDouble("columnLeft"), 1.0)
                    assertEquals("Right continuation inset", pitch - 16.0, fragment.getDouble("columnRight"), 1.0)
                    if (index == page) {
                        assertEquals(16.0, fragment.getDouble("left"), 1.0)
                        assertEquals(pitch - 16.0, fragment.getDouble("right"), 1.0)
                    }
                }
                val words = layout.getJSONArray("words")
                val wordList = (0 until words.length()).map { words.getJSONObject(it) }
                assertEquals("All words retain source order across page turns", expected, wordList.map { it.getString("text") })
                assertTrue("No empty column $page", wordList.any { it.getInt("page") == page && it.getBoolean("visible") })
                val lines = mutableListOf<MutableList<JSONObject>>()
                for (word in wordList) {
                    val previous = lines.lastOrNull()?.first()
                    if (previous == null || previous.getInt("page") != word.getInt("page") ||
                        abs(previous.getDouble("top") - word.getDouble("top")) > 1.0) {
                        lines.add(mutableListOf())
                    }
                    lines.last().add(word)
                }
                assertTrue(lines.size > 3)
                for (line in lines.dropLast(2)) {
                    assertEquals("Natural non-last line starts at left inset", 16.0, line.first().getDouble("columnLeft"), 1.0)
                    assertEquals("Range of final word reaches the right inset", pitch - 16.0, line.last().getDouble("columnRight"), 1.5)
                }
                val lastLine = lines.last()
                assertEquals(listOf("The", "end."), lastLine.map { it.getString("text") })
                assertEquals(16.0, lastLine.first().getDouble("columnLeft"), 1.0)
                assertTrue("Last line is not stretched across the column",
                    lastLine.last().getDouble("columnRight") < pitch / 2)
            }
            fixture.ui { assertFalse(turnBookPage(true) { error("No blank trailing page") }) }
        }
    }

    @Test
    fun refreshesAndRapidTurnsCompleteEachAcceptedRequestExactlyOnceEvenWhileHostLocked() {
        Fixture(book(prose(101))).use { fixture ->
            fixture.awaitReady()
            val completed = CountDownLatch(7)
            val turns = AtomicInteger()
            val refreshes = AtomicInteger()
            val coalesced = AtomicInteger()
            fixture.ui {
                interactionEnabled = false // Required integration order: host locks BEFORE turn.
                assertTrue(turnBookPage(true) { turns.incrementAndGet(); completed.countDown() })
                repeat(4) { assertTrue(turnBookPage(true) { coalesced.incrementAndGet(); completed.countDown() }) }
                refreshBookLayout { refreshes.incrementAndGet(); completed.countDown() }
                refreshBookLayout { refreshes.incrementAndGet(); completed.countDown() }
            }
            await(completed, "Coalesced native page/refresh completions")
            fixture.drain()
            assertEquals(1, turns.get())
            assertEquals(2, refreshes.get())
            assertEquals(4, coalesced.get())
            assertEquals("Coalesced requests paint only one additional page", 2, fixture.reports.size)
            fixture.assertPage(1, fixture.info().count)
            fixture.ui { interactionEnabled = true }
            fixture.turn(false)
            fixture.assertPage(0, fixture.info().count)
        }
    }

    @Test
    fun replacementAndDuplicatePageFinishedEventsCannotPublishStalePagesOrCompletions() {
        val latest = book(prose(901, 160))
        Fixture(book(prose(101)), replacementBeforeLoad = latest).use { fixture ->
            fixture.awaitReady()
            assertEquals(1, fixture.rendered.get())
            assertTrue(fixture.reports.all { it.info.anchorRecord == 901 })
            val latestReady = CountDownLatch(1)
            val staleCompletions = AtomicInteger()
            fixture.ui {
                assertTrue(turnBookPage(true) { staleCompletions.incrementAndGet() })
                val oldClient = webViewClient
                val oldUrl = requireNotNull(url)
                onRendered = { fixture.rendered.incrementAndGet(); latestReady.countDown() }
                render("replacement", book(prose(1001, 10)), 0) { null }
                oldClient.onPageFinished(this, oldUrl)
            }
            await(latestReady, "Replacement book paints")
            fixture.ui {
                assertEquals(1001, bookPageInfo!!.anchorRecord)
                webViewClient.onPageFinished(this, requireNotNull(url))
                render("replacement", "Duplicate key must not reload invalid HTML", 0) { null }
            }
            fixture.drain()
            assertEquals(0, staleCompletions.get())
            assertEquals(2, fixture.rendered.get())
            fixture.assertPage(0, 1)
        }
    }

    @Test
    fun releaseDropsInFlightTurnsRefreshesAndTouchResultsAndIsIdempotent() {
        Fixture(book(prose(101))).use { fixture ->
            fixture.awaitReady()
            val callbacks = AtomicInteger()
            var published = 0
            fixture.ui {
                val previous = requireNotNull(bookPageInfo)
                val client = webViewClient
                val oldUrl = requireNotNull(url)
                published = fixture.reports.size
                fixture.tap(this, width - 2f, height * .75f) // Leave a safe-hit-test callback pending.
                assertTrue(turnBookPage(true) { callbacks.incrementAndGet() })
                refreshBookLayout { callbacks.incrementAndGet() }
                (parent as ViewGroup).removeView(this)
                release()
                release()
                client.onPageFinished(this, oldUrl)
                onBookPageChanged(previous)
                onRendered()
                onReaderTap()
                refreshBookLayout { callbacks.incrementAndGet() }
                assertFalse(turnBookPage(true) { callbacks.incrementAndGet() })
                assertNull(bookPageInfo)
                assertTrue(isReleased)
            }
            // A live sibling drains compositor/UI work without querying the destroyed view or
            // launching a second ActivityScenario that could destroy the first scenario's task.
            fixture.paintProbe()
            assertEquals(0, callbacks.get())
            assertEquals(published, fixture.reports.size)
            assertEquals(1, fixture.rendered.get())
            assertEquals(0, fixture.taps.get())
        }
    }

    @Test
    fun horizontalSwipesUseUnitCallbacksButVerticalDragsAndLongPressesDoNot() {
        Fixture(book(prose(101))).use { fixture ->
            fixture.awaitReady()
            val next = CountDownLatch(1)
            val previous = CountDownLatch(1)
            fixture.ui {
                onNextUnit = { fixture.navigation.incrementAndGet(); next.countDown() }
                onPreviousUnit = { fixture.navigation.incrementAndGet(); previous.countDown() }
            }
            fixture.swipe(horizontal = true, forward = true)
            await(next, "Left swipe dispatches next-unit callback")
            fixture.swipe(horizontal = true, forward = false)
            await(previous, "Right swipe dispatches previous-unit callback")
            fixture.swipe(horizontal = false, forward = true)
            fixture.swipe(horizontal = false, forward = false)
            fixture.ui { fixture.tap(this, width / 2f, height / 2f, ViewConfiguration.getLongPressTimeout().toLong() + 50) }
            fixture.drain()
            fixture.assertPage(0, fixture.info().count) // Callbacks were deliberately not wired to turn.
            assertEquals(2, fixture.navigation.get())
            assertEquals(0, fixture.taps.get())
        }
    }

    @Test
    fun bothEdgesToggleChromeButLinkAndSelectionTapsAreProtected() {
        val html = book(Paragraph(10, "<JL:Jump,\"fixture\">Safe link<EL>", "", ""), prose(20, 10))
        Fixture(html).use { fixture ->
            fixture.awaitReady()
            for (right in listOf(false, true)) {
                val tapped = CountDownLatch(1)
                fixture.ui {
                    onReaderTap = { fixture.taps.incrementAndGet(); tapped.countDown() }
                    fixture.tap(this, if (right) width - 2f else 2f, height * .75f)
                }
                await(tapped, "A genuine edge tap toggles chrome, never page navigation")
            }
            fixture.tapElement("a")
            fixture.drain()
            assertEquals(2, fixture.taps.get())
            assertEquals(0, fixture.navigation.get())
            assertTrue(fixture.links.get() > 0)

            // An accessibility/DOM selection can exist before WebView opens a native ActionMode.
            assertEquals("true", fixture.eval("""
                (function(){var text=document.getElementById('record-20').querySelector('.folio-block').firstChild;
                    var range=document.createRange();range.setStart(text,0);range.setEnd(text,4);
                    var selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
                    return !selection.isCollapsed;})()
            """))
            fixture.ui { fixture.tap(this, width - 2f, height * .75f) }
            fixture.drain()
            assertEquals("A tap clearing a selection is not a chrome tap", 2, fixture.taps.get())
            assertEquals(0, fixture.navigation.get())
        }
    }

    @Test
    fun aNativeRasterImageTapDoesNotToggleChromeOrNavigate() {
        val png = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        )
        // Only the image box is enlarged for hitting; all pagination still uses production CSS.
        val html = book(Paragraph(10, "<OB:\"OB\";\"fixture.png\">", "", ""), prose(20, 3))
            .replace("</style>", "img{width:80px;height:80px}</style>")
        Fixture(html, image = { if (it == "fixture.png") ContentObject("image/png", png) else null }).use { fixture ->
            fixture.awaitReady()
            assertEquals("true", fixture.eval("document.querySelector('img').naturalWidth > 0"))
            fixture.tapElement("img")
            fixture.drain()
            assertEquals(0, fixture.taps.get())
            assertEquals(0, fixture.navigation.get())
            fixture.assertPage(0, 1)
        }
    }

    @Test
    fun fontReadyBookUsesBundledFacesAndStillDeniesNetworkStorageFileAndContentAccess() {
        val paragraphs = listOf(
            Paragraph(10, "Roman ā a\u0304 <IT+>italic ś ṛ<IT->", "", ""),
            Paragraph(20, "Synthetic bold translation Kṛṣṇa.", "PA_Translation", ""),
            Paragraph(30, "धर्मक्षेत्रे कृष्ण नमूना", "PA_DevaNagariQ", ""),
        )
        val latin = ReferenceTypography.LATIN
        val devanagari = ReferenceTypography.DEVANAGARI
        val boldWeight = "700"
        val regularWeight = "400"
        val bodyWeight = "400"
        val html = FolioText.document(paragraphs, "en", ReaderPreferences(fontSize = 24), bookMode = true)
        Fixture(html).use { fixture ->
            fixture.awaitReady()
            @Suppress("DEPRECATION")
            fixture.ui {
                with(settings) {
                    assertTrue(javaScriptEnabled)
                    assertFalse(javaScriptCanOpenWindowsAutomatically)
                    assertFalse(domStorageEnabled)
                    assertFalse(databaseEnabled)
                    assertFalse(allowFileAccess)
                    assertFalse(allowContentAccess)
                    assertFalse(allowFileAccessFromFileURLs)
                    assertFalse(allowUniversalAccessFromFileURLs)
                    assertTrue(blockNetworkLoads)
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
                    assertFalse(supportMultipleWindows())
                    assertFalse(supportZoom())
                }
            }
            val fonts = fixture.json("""
                (function(){var loaded=Array.from(document.fonts).filter(function(f){return f.status==='loaded';});
                    function hasFace(family,weight,style){return loaded.some(function(f){
                        return f.family.replace(/["']/g,'')===family&&f.weight===weight&&f.style===style;});}
                    var translation=getComputedStyle(document.querySelector('#record-20 .folio-block'));
                    var deva=getComputedStyle(document.querySelector('#record-30 .folio-block'));
                    return {status:document.fonts.status, family:getComputedStyle(document.body).fontFamily,
                        roman:hasFace('$latin','$bodyWeight','normal'),
                        italic:hasFace('$latin','$regularWeight','italic'),
                        bold:hasFace('$latin','$boldWeight','normal'),
                        devanagari:hasFace('$devanagari','100 900','normal'),
                        translationWeight:translation.fontWeight,translationFamily:translation.fontFamily,
                        devanagariFamily:deva.fontFamily,devanagariStyle:deva.fontStyle,
                        glyphs:document.querySelector('#record-30 .folio-block').textContent};})()
            """)
            assertEquals("loaded", fonts.getString("status"))
            assertEquals(latin, fonts.getString("family").substringBefore(',').trim().trim('"', '\''))
            assertTrue("regular $latin must load before page reporting", fonts.getBoolean("roman"))
            assertTrue("italic $latin must load before page reporting", fonts.getBoolean("italic"))
            assertTrue("bold $latin must load before page reporting", fonts.getBoolean("bold"))
            assertTrue("Variable $devanagari must load before page reporting", fonts.getBoolean("devanagari"))
            assertEquals("700", fonts.getString("translationWeight"))
            assertEquals(latin, fonts.getString("translationFamily").substringBefore(',').trim().trim('"', '\''))
            assertEquals(devanagari, fonts.getString("devanagariFamily").substringBefore(',').trim().trim('"', '\''))
            assertEquals("normal", fonts.getString("devanagariStyle"))
            assertEquals(paragraphs[2].text, fonts.getString("glyphs"))
            for (paragraph in paragraphs) {
                assertEquals(FolioText.plain(paragraph.text), fixture.record(paragraph.record).getString("text"))
            }
        }
    }

    @Test
    fun fontAllowlistServesOnlySevenExactGetSubresourcesWithoutDecodingOrTraversal() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val fonts = listOf(
            "FiraSansCondensed-Regular.ttf" to R.font.fira_sans_condensed_regular,
            "FiraSansCondensed-Italic.ttf" to R.font.fira_sans_condensed_italic,
            "FiraSansCondensed-Medium.ttf" to R.font.fira_sans_condensed_medium,
            "FiraSansCondensed-SemiBold.ttf" to R.font.fira_sans_condensed_semibold,
            "FiraSansCondensed-Bold.ttf" to R.font.fira_sans_condensed_bold,
            "FiraSansCondensed-BoldItalic.ttf" to R.font.fira_sans_condensed_bold_italic,
            "NotoSerifDevanagari.ttf" to R.font.noto_serif_devanagari,
        )
        assertEquals(7, fonts.size)
        assertEquals(fonts.map { "https://reader.local/fonts/${it.first}" },
            BookPagination.CSP.substringAfter("font-src ").substringBefore(';').split(' '))
        for ((name, resource) in fonts) {
            val address = "https://reader.local/fonts/$name"
            val response = ReaderFonts.response(Uri.parse(address), "GET", false, resources)
            assertEquals(200, response.statusCode)
            assertEquals("font/ttf", response.mimeType)
            assertNull(response.encoding)
            assertEquals("nosniff", response.responseHeaders?.get("X-Content-Type-Options"))
            assertEquals("no-store", response.responseHeaders?.get("Cache-Control"))
            val expected = resources.openRawResource(resource).use { it.readBytes() }
            requireNotNull(response.data).use { assertArrayEquals(expected, it.readBytes()) }
            listOf("HEAD", "POST", "get", " GET").forEach {
                assertDenied(ReaderFonts.response(Uri.parse(address), it, false, resources))
            }
            assertDenied(ReaderFonts.response(Uri.parse(address), "GET", true, resources))
            for (variant in listOf("$address?", "$address?x=1", "$address#", "$address#fragment",
                address.replace("https:", "HTTPS:"), address.replace("reader.local", "READER.LOCAL"),
                address.replace("reader.local", "reader.local:443"),
                "https://reader.local/fonts/${name.lowercase(java.util.Locale.ROOT)}")) {
                assertDenied(ReaderFonts.response(Uri.parse(variant), "GET", false, resources))
            }
        }
        val blocked = listOf(
            "http://reader.local/fonts/FiraSansCondensed-Regular.ttf",
            "https://reader.local:8443/fonts/FiraSansCondensed-Regular.ttf",
            "https://user@reader.local/fonts/FiraSansCondensed-Regular.ttf",
            "https://reader.local.example/fonts/FiraSansCondensed-Regular.ttf",
            "https://reader.local/Fonts/FiraSansCondensed-Regular.ttf",
            "https://reader.local/fonts/Literata-Regular.ttf",
            "https://reader.local/fonts/Literata-Italic.ttf",
            "https://reader.local/fonts/NotoSansDevanagari.ttf",
            "https://reader.local/fonts/NotoSerifBengali.ttf",
            "https://reader.local/fonts/Literata-OFL.txt",
            "https://reader.local/fonts/NotoSerifDevanagari-OFL.txt",
            "https://reader.local/fonts/FiraSansCondensed-OFL.txt",
            "https://reader.local/fonts/NotoSansDevanagari-OFL.txt",
            "https://reader.local/fonts/other.ttf",
            "https://reader.local/fonts/other.svg",
            "https://reader.local/fonts/other.mp3",
            "https://reader.local/fonts/../fonts/FiraSansCondensed-Regular.ttf",
            "https://reader.local/fonts/../../other.ttf",
            "https://reader.local/fonts/%2e%2e/FiraSansCondensed-Regular.ttf",
            "https://reader.local/fonts/%46iraSansCondensed-Regular.ttf",
            "https://reader.local/fonts%2fFiraSansCondensed-Regular.ttf",
            "https://reader.local/fonts/FiraSansCondensed-Regular.ttf%00",
            "file:///android_res/font/fira_sans_condensed_regular.ttf",
            "file:///android_asset/fonts/FiraSansCondensed-Regular.ttf",
            "content://reader.local/fonts/FiraSansCondensed-Regular.ttf",
        )
        blocked.forEach { assertDenied(ReaderFonts.response(Uri.parse(it), "GET", false, resources)) }
    }

    @Test
    fun folioEscapesInjectedHtmlAndNativeCspIndependentlyDeniesExecutableSource() {
        val injection = "<script>window.sourceExecuted=1</script>" +
            "<img src=x onerror=\"window.sourceExecuted=2\"><iframe srcdoc=\"bad\"></iframe>" +
            "<svg onload=\"window.sourceExecuted=3\"></svg>"
        val escaped = book(Paragraph(10, injection, "", ""))
        assertTrue("Production Folio CSP must explicitly prohibit source scripts", escaped.contains("script-src 'none'"))
        assertFalse(escaped.contains("<script>"))
        Fixture(escaped).use { fixture ->
            fixture.awaitReady()
            val result = fixture.json("""
                (function(){return {executed:typeof window.sourceExecuted!=='undefined',
                    active:document.querySelectorAll('script,iframe,svg,[onerror],[onload]').length,
                    literal:document.body.textContent.indexOf('<script>')>=0};})()
            """)
            assertFalse(result.getBoolean("executed"))
            assertEquals(0, result.getInt("active"))
            assertTrue(result.getBoolean("literal"))
            fixture.ui {
                val response = requireNotNull(webViewClient.shouldInterceptRequest(this, Request(requireNotNull(url), main = true)))
                assertEquals(200, response.statusCode)
                val csp = requireNotNull(response.responseHeaders?.get("Content-Security-Policy"))
                assertTrue(csp.contains("script-src 'none'"))
                assertTrue(csp.contains("frame-src 'none'"))
                assertTrue(csp.contains("connect-src 'none'"))
                requireNotNull(response.data).close()
            }
        }
        // Deliberately bypass the Folio escaper in this synthetic fixture: the response CSP is
        // a second line of defense even against a future accidental source-escaping regression.
        val raw = book(prose(20, 8)).replace("</head>", "<script>window.sourceExecuted=4</script></head>")
            .replace("</main>", "<img src=\"https://reader.local/not-an-object\" onerror=\"window.sourceExecuted=5\">" +
                "<iframe srcdoc=\"<script>parent.sourceExecuted=6</script>\"></iframe></main>")
        Fixture(raw).use { fixture ->
            fixture.awaitReady()
            fixture.drain()
            assertEquals("true", fixture.eval("typeof window.sourceExecuted === 'undefined'"))
            fixture.ui {
                val address = requireNotNull(url)
                val client = webViewClient
                assertDenied(requireNotNull(client.shouldInterceptRequest(this, Request(address, main = false))))
                assertDenied(requireNotNull(client.shouldInterceptRequest(this, Request("https://example.invalid/script.js"))))
                assertDenied(requireNotNull(client.shouldInterceptRequest(this, Request("file:///android_asset/fonts/FiraSansCondensed-Regular.ttf"))))
                assertDenied(requireNotNull(client.shouldInterceptRequest(this, Request("https://reader.local/fonts/FiraSansCondensed-Regular.ttf", main = true))))
                assertTrue(client.shouldOverrideUrlLoading(this, Request("javascript:window.sourceExecuted=7", main = true)))
                assertEquals(address, url)
            }
            assertEquals("true", fixture.eval("typeof window.sourceExecuted === 'undefined'"))
        }
    }

    @Test
    fun verseModeRemainsScriptDisabledAndDoesNotExposeBookPages() {
        val verse = FolioText.document(listOf(prose(101)), "en", ReaderPreferences())
        Fixture(verse, paginated = false).use { fixture ->
            fixture.awaitReady()
            fixture.ui {
                assertFalse(settings.javaScriptEnabled)
                assertNull(bookPageInfo)
                assertTrue(scrollRange > 0)
                assertFalse(turnBookPage(true) { error("Not a book page") })
                var complete = false
                refreshBookLayout { complete = true }
                assertTrue(complete)
                assertFalse(settings.javaScriptEnabled)
                // A bundled font is passive data, and does not require enabling script in VERSE.
                val font = requireNotNull(webViewClient.shouldInterceptRequest(this,
                    Request("https://reader.local/fonts/FiraSansCondensed-Regular.ttf")))
                assertEquals(200, font.statusCode)
                requireNotNull(font.data).close()
                scrollTo(0, viewportHeight / 2)
                assertTrue(scrollY > 0)
            }
            assertTrue(fixture.reports.isEmpty())
        }
    }

    private fun prose(record: Int, words: Int = 240) = Paragraph(record,
        (0 until words).joinToString(" ") { "word$it" } + " नमूना বাংলা ā a\u0304.", "PA_Purport", "")

    private fun synonymsHeading(record: Int) = Paragraph(record, "SYNONYMS", "PA_Titles", "LE_Verse_Section")
    private fun synonyms(record: Int) = prose(record, 220).copy(style = "PA_Synonyms")

    private fun book(vararg paragraphs: Paragraph, fontSize: Int = 24): String =
        FolioText.document(paragraphs.toList(), "en", ReaderPreferences(fontSize = fontSize), bookMode = true)

    private data class Report(val info: BookPageInfo, val x: Int, val y: Int, val width: Int, val scale: Double)

    private class Fixture(
        html: String, initialAnchor: Int? = null, atEnd: Boolean = false,
        paginated: Boolean = true, replacementBeforeLoad: String? = null,
        image: (String) -> ContentObject? = { null },
    ) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        private lateinit var reader: OfflineReaderView
        private val ready = CountDownLatch(1)
        private val failures = AtomicInteger()
        val rendered = AtomicInteger()
        val taps = AtomicInteger()
        val links = AtomicInteger()
        val navigation = AtomicInteger()
        val reports = CopyOnWriteArrayList<Report>()
        private var fenceId = 1_000_000L

        init {
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                reader = OfflineReaderView(activity).apply {
                    paginatedBook = paginated
                    initialBookAnchor = initialAnchor
                    canNextUnit = true
                    canPreviousUnit = true
                    onRendered = { rendered.incrementAndGet(); ready.countDown() }
                    onRenderFailed = { failures.incrementAndGet(); ready.countDown() }
                    onBookPageChanged = { reports += Report(it, scrollX, scrollY, width, nativeContentScale) }
                    onReaderTap = { taps.incrementAndGet() }
                    onLinkUnavailable = { links.incrementAndGet() }
                    onNextUnit = { navigation.incrementAndGet() }
                    onPreviousUnit = { navigation.incrementAndGet() }
                }
                val density = activity.resources.displayMetrics.density
                val width = minOf((360 * density).toInt(), activity.resources.displayMetrics.widthPixels)
                root.addView(reader, FrameLayout.LayoutParams(width, (360 * density).toInt()))
                activity.setContentView(root)
                reader.render("book", html, 0, image, initialAtEnd = atEnd)
                if (replacementBeforeLoad != null) reader.render("book-latest", replacementBeforeLoad, 0, image)
            }
        }

        fun ui(block: OfflineReaderView.() -> Unit) = scenario.onActivity { reader.block() }

        fun awaitReady() {
            await(ready, "Book/verse native visual readiness")
            assertEquals("No native render failure", 0, failures.get())
        }

        fun info(): BookPageInfo {
            var info: BookPageInfo? = null
            ui { info = bookPageInfo }
            return requireNotNull(info)
        }

        fun assertPage(page: Int, count: Int) {
            val dom = geometry()
            val pitch = dom.getDouble("pitch")
            assertTrue(pitch.isFinite() && pitch > 0)
            ui {
                val info = requireNotNull(bookPageInfo)
                val scale = nativeContentScale
                assertTrue(scale.isFinite() && scale > 0)
                assertEquals(page, info.page)
                assertEquals(count, info.count)
                assertEquals(dom.getDouble("dpr") * dom.getDouble("visualScale"), scale, .00001)
                // Android may round the CSS viewport; it is not the source of native scale or pitch.
                assertEquals(width / scale, dom.getDouble("width"), 1.0)
                assertEquals((pitch * scale).roundToInt(), info.viewportWidth)
                assertEquals((page * pitch * scale).roundToInt(), info.nativeOffset)
                assertEquals(info.nativeOffset, scrollX)
                assertEquals(scrollX.toDouble(), dom.getDouble("x") * scale, 2.0)
                assertEquals(0, scrollY)
                assertEquals(0, scrollRange)
            }
            assertEquals(0.0, dom.getDouble("y"), .01)
            assertEquals("DOM must agree with the authored column pitch", page * pitch, dom.getDouble("x"), 1.0)
            assertEquals("loaded", dom.getString("fonts"))
            assertEquals(count, ceil((dom.getDouble("extent") - 1.0).coerceAtLeast(pitch) / pitch).toInt())
        }

        fun geometry(): JSONObject = json("""
            (function(){var v=visualViewport,flow=document.querySelector('.book-flow');
                return {x:v.pageLeft,y:v.pageTop,width:v.width,pitch:flow.getBoundingClientRect().width,
                    dpr:devicePixelRatio,visualScale:v.scale,
                    extent:Math.max(document.scrollingElement.scrollWidth,document.documentElement.scrollWidth,
                        document.body.scrollWidth,v.width),fonts:document.fonts.status};})()
        """)

        fun turn(forward: Boolean) {
            val complete = CountDownLatch(1)
            val calls = AtomicInteger()
            val painted = AtomicReference<Report>()
            ui {
                interactionEnabled = false
                assertTrue("An intra-document page is available", turnBookPage(forward) {
                    painted.set(Report(requireNotNull(bookPageInfo), scrollX, scrollY, width, nativeContentScale))
                    calls.incrementAndGet()
                    interactionEnabled = true
                    complete.countDown()
                })
                assertEquals("Do not complete synchronously, even with animator scale zero", 0, calls.get())
            }
            if (!complete.await(20, TimeUnit.SECONDS)) {
                val geometry = eval("""(function(){return {x:window.scrollX,y:window.scrollY,w:innerWidth,
                    viewport:visualViewport.width,extent:document.scrollingElement.scrollWidth,
                    body:document.body.scrollWidth,flow:document.querySelector('main').scrollWidth};})()""")
                var native = ""
                ui { native = "x=$scrollX width=$width last=$bookPageInfo failures=${failures.get()}" }
                fail("Column did not settle: $native DOM=$geometry")
            }
            assertEquals(1, calls.get())
            val report = requireNotNull(painted.get())
            assertEquals(report.info.nativeOffset, report.x)
            assertEquals(0, report.y)
        }

        fun awaitPage(label: String, predicate: OfflineReaderView.(BookPageInfo) -> Boolean) {
            val changed = CountDownLatch(1)
            lateinit var previous: (BookPageInfo) -> Unit
            ui {
                previous = onBookPageChanged
                onBookPageChanged = { info -> previous(info); if (predicate(info)) changed.countDown() }
                bookPageInfo?.let { if (predicate(it)) changed.countDown() }
            }
            try { await(changed, label) } finally { ui { onBookPageChanged = previous } }
        }

        fun eval(script: String): String {
            val evaluated = CountDownLatch(1)
            val result = AtomicReference<String>()
            ui { evaluateJavascript(script) { result.set(it); evaluated.countDown() } }
            await(evaluated, "Read-only test DOM inspection")
            return requireNotNull(result.get())
        }

        fun json(script: String) = JSONObject(eval(script))

        /** Layout coordinates locate columns; visual offsets locate actual native page visibility. */
        fun textLayout(record: Int): JSONObject {
            require(record > 0)
            return json("""
                (function(){var e=document.querySelector('#record-$record .folio-block');
                    var v=visualViewport,s=getComputedStyle(e),words=[];
                    var pitch=document.querySelector('.book-flow').getBoundingClientRect().width;
                    function box(r){var page=Math.floor((r.left+window.scrollX+.5)/pitch);
                        return {page:page,columnLeft:r.left+window.scrollX-page*pitch,
                            columnRight:r.right+window.scrollX-page*pitch,
                            left:r.left-v.offsetLeft,right:r.right-v.offsetLeft,
                            top:r.top-v.offsetTop,bottom:r.bottom-v.offsetTop,
                            visible:r.width>0&&r.height>0&&r.right-v.offsetLeft>0&&r.left-v.offsetLeft<v.width&&
                                r.bottom-v.offsetTop>0&&r.top-v.offsetTop<v.height};}
                    var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT),node;
                    while((node=walker.nextNode())){var re=/\S+/g,match;
                        while((match=re.exec(node.textContent))){var range=document.createRange();
                            range.setStart(node,match.index);range.setEnd(node,match.index+match[0].length);
                            Array.from(range.getClientRects()).filter(function(r){return r.width>0&&r.height>0;})
                                .forEach(function(r){var word=box(r);word.text=match[0];words.push(word);});}}
                    return {width:v.width,pitch:pitch,font:parseFloat(s.fontSize),lineHeight:parseFloat(s.lineHeight),
                        align:s.textAlign,last:s.textAlignLast,spacing:s.letterSpacing,
                        fragments:Array.from(e.getClientRects()).map(box),words:words};})()
            """)
        }

        fun record(record: Int): JSONObject {
            require(record > 0)
            return json("""
                (function(){var e=document.getElementById('record-'+$record), boxes=e.getClientRects();
                    var v=window.visualViewport,w=v.width,h=v.height;
                    var pitch=document.querySelector('.book-flow').getBoundingClientRect().width;
                    return {id:e.id,record:Number(e.getAttribute('data-record')),text:e.textContent,
                        display:getComputedStyle(e).display,fragments:boxes.length,
                        firstPage:boxes.length?Math.floor((boxes[0].left+window.scrollX+.5)/pitch):-1,
                        visible:Array.from(boxes).some(function(b){return b.width>0&&b.height>0&&
                            b.right-v.offsetLeft>0&&b.left-v.offsetLeft<w&&
                            b.bottom-v.offsetTop>0&&b.top-v.offsetTop<h;})};})()
            """)
        }

        fun drain() {
            repeat(3) {
                eval("(function(){return 0;})()")
                val painted = CountDownLatch(1)
                ui {
                    postVisualStateCallback(++fenceId, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { painted.countDown() }
                    })
                    invalidate()
                }
                await(painted, "Drain native paint and queued DOM callbacks")
            }
        }

        fun paintProbe() {
            var probe: OfflineReaderView? = null
            val painted = CountDownLatch(1)
            try {
                scenario.onActivity { activity ->
                    val view = OfflineReaderView(activity)
                    probe = view
                    view.onRendered = { painted.countDown() }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(view,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    view.render("release-probe", "<html><body>Native release fence.</body></html>", 0) { null }
                }
                await(painted, "A sibling renderer drains stale work after release")
            } finally {
                scenario.onActivity {
                    probe?.let { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.release()
                    }
                }
            }
        }

        fun tapElement(selector: String) {
            require(selector in setOf("a", "img")) // Only fixed test selectors, never source strings.
            val point = json("""
                (function(){var r=document.querySelector('$selector').getClientRects()[0],v=window.visualViewport;
                    return {x:r.left-v.offsetLeft+Math.min(r.width/2,20),
                        y:r.top-v.offsetTop+r.height/2};})()
            """)
            ui { tap(this, (point.getDouble("x") * nativeContentScale).toFloat(),
                (point.getDouble("y") * nativeContentScale).toFloat()) }
        }

        fun tap(view: OfflineReaderView, x: Float, y: Float, duration: Long = 60) {
            val now = SystemClock.uptimeMillis()
            event(view, now, now, MotionEvent.ACTION_DOWN, x, y)
            event(view, now, now + duration, MotionEvent.ACTION_UP, x, y)
        }

        fun swipe(horizontal: Boolean, forward: Boolean) {
            ui {
                val now = SystemClock.uptimeMillis()
                val start = if (forward) .85f else .15f
                val end = if (forward) .15f else .85f
                val x1 = if (horizontal) width * start else width * .5f
                val x2 = if (horizontal) width * end else width * .5f
                val y1 = if (horizontal) height * .5f else height * start
                val y2 = if (horizontal) height * .5f else height * end
                event(this, now, now, MotionEvent.ACTION_DOWN, x1, y1)
                event(this, now, now + 50, MotionEvent.ACTION_MOVE, x2, y2)
                event(this, now, now + 100, MotionEvent.ACTION_UP, x2, y2)
            }
        }

        private fun event(view: OfflineReaderView, down: Long, time: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }

        override fun close() {
            try {
                ui {
                    (parent as? ViewGroup)?.removeView(this)
                    release()
                }
            } finally { scenario.close() }
        }
    }

    private class Request(private val address: String, private val main: Boolean = false) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    companion object {
        private fun await(latch: CountDownLatch, label: String) {
            assertTrue(label, latch.await(20, TimeUnit.SECONDS))
        }

        private fun assertDenied(response: WebResourceResponse) {
            assertEquals(403, response.statusCode)
            assertEquals("text/plain", response.mimeType)
            requireNotNull(response.data).use { assertEquals(-1, it.read()) }
        }
    }
}