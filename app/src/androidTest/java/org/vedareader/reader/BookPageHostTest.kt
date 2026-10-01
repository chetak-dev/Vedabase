package org.vedareader.reader

import android.animation.ValueAnimator
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Real native rendering and controller, synthetic documents only. No corpus, stores or prefs I/O.
 * BOOK fixtures use Folio's production columns/CSP; VERSE keeps scrolling and JavaScript disabled.
 * Waits use host events and visual fences, never animation-duration sleeps or JS bridges.
 */
@RunWith(AndroidJUnit4::class)
class BookPageHostTest {
    @Test
    fun bookSwipeTurnsFixedPagesThenRequestsExactlyOneNeighbourResource() {
        Fixture(documents()).use { fixture ->
            fixture.awaitReady(0)
            lateinit var original: OfflineReaderView
            lateinit var document: ReaderDocument
            fixture.ui {
                original = view
                document = state!!.document!!
                assertBookPage()
                assertBookSecurity()
                assertTrue(status.pageCount > 2)
                assertEquals(0, status.pageIndex)
                assertFalse(status.canPrevious)
                assertTrue(status.canNext)
                assertEquals(0f, status.progress, 0f)
                assertEquals(status.anchorRecord, anchorReports.single().second)
            }

            // Every native swipe advances one whole horizontal column, never a vertical screenful.
            turnToBoundary(fixture, forward = true)
            fixture.ui {
                assertSame(original, view)
                assertSame(document, state!!.document)
                assertEquals(1, host.childCount)
                assertEquals(1, loads)
                assertEquals(0, nextCalls)
                assertEquals(1f, status.progress, 0f)
                assertTrue(status.canPrevious)
                assertTrue(status.canNext) // Only the next resource remains, not another member verse.
                val resource = document.bookResource!!
                assertEquals(0, resource.position.index) // Loaded selection metadata never mutates.
                val visible = resource.position.outline.entries.single {
                    status.anchorRecord in it.node.record..it.node.end
                }
                assertEquals(visible.source.displayTitle, state!!.location.title)
                assertEquals(1, resource.position.outline.entries.indexOf(visible))
                host.next()
                repeat(4) { host.next(); host.previous(); swipe(forward = true) }
                assertEquals(1, nextCalls)
                assertEquals(2, loads)
                assertTrue(host.childCount <= 2)
            }
            fixture.awaitReady(1)
            fixture.ui {
                assertNotSame(original, view)
                assertTrue(original.isReleased)
                assertBookPage()
                assertEquals(0, status.pageIndex)
                assertEquals(0, view.scrollY)
                assertEquals(0, state!!.scrollY)
                assertEquals(1, nextCalls)
                assertEquals(1, host.childCount)
                assertTrue(scrollReports.isEmpty())
            }
        }
    }

    @Test
    fun previousResourceIsAtItsFinalColumnBeforeReadinessAndAnchorAcknowledgementDoesNotReload() {
        Fixture(documents(), initialIndex = 1).use { fixture ->
            fixture.awaitReady(1)
            val observedEnd = CountDownLatch(1)
            lateinit var incoming: OfflineReaderView
            fixture.ui {
                val outgoing = view
                assertEquals(0, status.pageIndex)
                val anchors = anchorReports.size
                val taps = readerTaps
                host.previous()
                assertTrue(state!!.enterAtEnd)
                assertEquals(0, state!!.scrollY)
                incoming = (0 until host.childCount).map { host.getChildAt(it) }
                    .filterIsInstance<OfflineReaderView>().single { it !== outgoing }
                val ready = incoming.onRendered
                incoming.onRendered = {
                    assertTrue(host.isBusy)
                    assertFalse(incoming.interactionEnabled)
                    assertEquals(0f, incoming.alpha, 0f)
                    val info = requireNotNull(incoming.bookPageInfo)
                    assertTrue(info.count > 2)
                    assertEquals(info.count - 1, info.page)
                    assertEquals(info.nativeOffset, incoming.scrollX)
                    assertEquals(0, incoming.scrollY)
                    assertEquals(0, incoming.scrollRange)
                    assertTrue(incoming.paginatedBook)
                    assertEquals("Incoming page notifications must not persist early", anchors, anchorReports.size)
                    incoming.onReaderTap()
                    outgoing.onReaderTap()
                    assertEquals(taps, readerTaps)
                    observedEnd.countDown()
                    ready()
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        assertTrue("The slide stays locked after the incoming native fence", host.isBusy)
                        assertEquals(2, host.childCount)
                        host.next()
                        host.previous()
                        assertEquals(1, previousCalls)
                        assertEquals(2, loads)
                    }
                }
                repeat(3) { host.previous(); host.next(); swipe(forward = false) }
                assertEquals(1, previousCalls)
                assertEquals(2, loads)
            }
            assertTrue("The final column must paint before host readiness", observedEnd.await(20, TimeUnit.SECONDS))
            fixture.awaitReady(0)
            fixture.fence()
            fixture.ui {
                assertSame(incoming, view)
                assertBookPage()
                assertEquals(status.pageCount - 1, status.pageIndex)
                assertEquals(status.anchorRecord, state!!.location.position)
                assertEquals(0, state!!.scrollY)
                assertFalse(state!!.enterAtEnd)
                assertEquals(1f, status.progress, 0f)
                assertEquals(2, loads)
                assertEquals(1, host.childCount)
                val reports = anchorReports.filter { it.first == docs[0].section.node.record }
                assertTrue(reports.isNotEmpty())
                assertTrue("Never publish a transient first-page anchor on end landing",
                    reports.all { it.second == status.anchorRecord })
                assertTrue(scrollReports.isEmpty())
            }
            fixture.turn(forward = false)
            fixture.ui {
                assertSame(incoming, view)
                assertEquals(status.pageCount - 2, status.pageIndex)
                assertEquals(1, previousCalls) // Move back within this resource before leaving it.
            }
        }
    }

    @Test
    fun legacySingleEntryBookAllowsFixedPageTurnsAtBothBookBoundsThenStopsExactly() {
        Fixture(documents(count = 1, legacy = true)).use { fixture ->
            fixture.awaitReady(0)
            fixture.ui {
                assertNull(state!!.document!!.bookResource)
                assertNull(state!!.document!!.section.previous)
                assertNull(state!!.document!!.section.next)
                assertBookPage()
                assertFalse(view.canPreviousUnit)
                assertTrue(view.canNextUnit)
                swipe(forward = false)
                host.previous()
                assertFalse(host.isBusy)
                swipe(forward = true, horizontal = false)
                swipe(forward = false, horizontal = false)
                view.scrollTo(view.scrollX, 9_999)
                assertEquals(0, view.scrollY)
            }
            fixture.fence()
            fixture.ui {
                assertEquals(0, status.pageIndex)
                assertEquals(0, readerTaps)
            }
            turnToBoundary(fixture, forward = true)
            fixture.ui {
                assertTrue(view.canPreviousUnit)
                assertFalse(view.canNextUnit)
                assertFalse(status.canNext)
                repeat(3) { swipe(forward = true); host.next() }
                assertFalse(host.isBusy)
                assertEquals(status.pageCount - 1, status.pageIndex)
                assertBookPage()
            }
            turnToBoundary(fixture, forward = false)
            fixture.ui {
                assertEquals(0, status.pageIndex)
                assertEquals(0, view.scrollX)
                assertEquals(0f, status.progress, 0f)
                assertFalse(status.canPrevious)
                assertTrue(status.canNext)
                repeat(3) { swipe(forward = false); host.previous() }
                assertFalse(host.isBusy)
                assertEquals(0, nextCalls + previousCalls)
                assertEquals(1, loads)
                assertTrue(anchorReports.isNotEmpty())
                assertTrue(scrollReports.isEmpty())
            }
        }
    }

    @Test
    fun verseSwipesStillNavigateWholeUnitsFromMidPurportAndPreviousStartsAtTop() {
        Fixture(documents(legacy = true), initialMode = ReadingMode.VERSE).use { fixture ->
            fixture.awaitReady(0)
            lateinit var old: OfflineReaderView
            fixture.ui {
                old = view
                assertFalse(view.paginatedBook)
                assertFalse(view.settings.javaScriptEnabled)
                assertNull(view.bookPageInfo)
                assertTrue(view.scrollRange > view.viewportHeight)
                view.scrollTo(0, view.scrollRange / 2)
                assertTrue(view.scrollY > 0)
                assertEquals(view.scrollY.toFloat() / view.scrollRange, status.progress, .0001f)
                assertEquals(0, status.pageCount)
                assertEquals(0, status.anchorRecord)
                assertFalse(view.canPreviousUnit) // VERSE does not turn back within the viewport.
                swipe(forward = true)
                repeat(3) { host.next(); host.previous() }
                assertEquals(1, nextCalls)
            }
            fixture.awaitReady(1)
            fixture.ui {
                assertTrue(old.isReleased)
                assertEquals(0, view.scrollY)
                swipe(forward = false)
            }
            fixture.awaitReady(0)
            fixture.ui {
                assertEquals(0, view.scrollY)
                assertFalse(state!!.enterAtEnd)
                assertEquals(1, nextCalls)
                assertEquals(1, previousCalls)
                assertEquals(3, loads)
                assertFalse(view.settings.javaScriptEnabled)
                assertTrue(anchorReports.isEmpty())
                assertTrue(scrollReports.isNotEmpty())
            }
        }
    }

    @Test
    fun nativePageTurnLocksRapidGesturesAndDefersAnchorPublicationUntilItsCompletionFence() {
        Fixture(documents(count = 1)).use { fixture ->
            fixture.awaitReady(0)
            lateinit var retained: OfflineReaderView
            var expected = 0
            var anchors = 0
            val observedPaint = CountDownLatch(1)
            fixture.ui {
                retained = view
                expected = status.pageIndex + 1
                anchors = anchorReports.size
                val changed = retained.onBookPageChanged
                retained.onBookPageChanged = { info ->
                    assertTrue("The page notification precedes the host's completion fence", host.isBusy)
                    assertEquals(expected, info.page)
                    assertEquals(info.nativeOffset, retained.scrollX)
                    assertEquals(0, retained.scrollY)
                    changed(info)
                    assertEquals("A busy page must not publish a controller anchor", anchors, anchorReports.size)
                    observedPaint.countDown()
                }
                host.next()
                // The same checks exercise the fenced immediate-column path on devices with
                // system animation scale zero. Tests never write global animation preferences.
                assertTrue(host.isBusy)
                assertNull(host.settledView)
                assertFalse(retained.interactionEnabled)
                assertFalse(status.canNext)
                assertFalse(status.canPrevious)
                assertEquals(0, retained.scrollY)
                retained.onReaderTap()
                host.submit(state!!, zoom) { null }
                repeat(8) { swipe(forward = true); swipe(forward = false); host.next(); host.previous() }
                assertTrue(host.isBusy)
                assertEquals(1, host.childCount)
                assertEquals(0, nextCalls + previousCalls)
                assertEquals(0, readerTaps)
                assertEquals(anchors, anchorReports.size)
            }
            assertTrue("Native page publication must remain locked", observedPaint.await(20, TimeUnit.SECONDS))
            fixture.awaitReady(0)
            fixture.ui {
                assertSame(retained, view)
                assertBookPage()
                assertEquals(expected, status.pageIndex)
                assertEquals(requireNotNull(view.bookPageInfo).nativeOffset, view.scrollX)
                assertEquals(status.anchorRecord, state!!.location.position)
                assertEquals(anchors + 1, anchorReports.size)
                assertEquals(0, state!!.scrollY)
                assertTrue(status.canNext)
                assertTrue(status.canPrevious)
                assertTrue(scrollReports.isEmpty())
            }
        }
    }

    @Test
    fun pendingNeighbourKeepsOneInertPageAndCannotIssueDuplicateRequests() {
        Fixture(documents(), initialIndex = 1).use { fixture ->
            fixture.awaitReady(1)
            turnToBoundary(fixture, forward = true)
            val response = CompletableDeferred<ReaderDocument>()
            lateinit var old: OfflineReaderView
            fixture.ui {
                old = view
                val info = requireNotNull(old.bookPageInfo)
                val anchors = anchorReports.size
                val taps = readerTaps
                pendingResponse = response
                host.next()
                assertTrue(state!!.loading)
                assertTrue(host.isBusy)
                assertSame(old, host.currentView)
                assertEquals(1, host.childCount)
                repeat(5) { host.next(); host.previous(); swipe(forward = true) }
                old.onScrollPosition(999)
                old.onBookPageChanged(info)
                old.onReaderTap()
                assertEquals(0, state!!.scrollY)
                assertEquals(docs[2].section.node.record, state!!.location.position)
                assertEquals(anchors, anchorReports.size)
                assertEquals(taps, readerTaps)
                assertEquals(1, nextCalls)
                assertEquals(2, loads)
                response.complete(docs[2])
            }
            fixture.awaitReady(2)
            fixture.ui {
                assertTrue(old.isReleased)
                assertEquals(1, host.childCount)
                assertBookPage()
                assertEquals(0, status.pageIndex)
                assertTrue(scrollReports.isEmpty())
            }
        }
    }

    @Test
    fun modeAndDocumentIdentityRedrawWithoutAssumingMatchingSourceLabelHeights() {
        // Cover both integration choices: compact labels in both modes, or visible labels in BOOK.
        for (compactBook in listOf(false, true)) {
            val bookDocs = documents(count = 1, compact = compactBook, legacy = true)
            val verseDocs = documents(count = 1, compact = true, legacy = true, bookMode = false)
            Fixture(bookDocs, verseDocs = verseDocs, initialMode = ReadingMode.VERSE).use { fixture ->
                fixture.awaitReady(0)
                lateinit var verse: OfflineReaderView
                var saved = 0
                var scrolls = 0
                fixture.ui {
                    verse = view
                    assertFalse(view.settings.javaScriptEnabled)
                    saved = view.viewportHeight / 2
                    view.scrollTo(0, saved)
                    assertEquals(saved, state!!.scrollY)
                    scrolls = scrollReports.size
                    controller.toggleMode()
                }
                fixture.awaitReady(0)
                lateinit var book: OfflineReaderView
                fixture.ui {
                    book = view
                    assertNotSame(verse, book)
                    assertTrue(verse.isReleased)
                    assertEquals(ReadingMode.BOOK, state!!.mode)
                    assertBookPage()
                    assertEquals(0, status.pageIndex)
                    assertEquals(0, view.scrollY) // BOOK never applies a saved VERSE pixel offset.
                    assertEquals(scrolls, scrollReports.size)
                    assertTrue(anchorReports.isNotEmpty())
                    assertEquals(state!!.location.start, view.initialBookAnchor!!.toInt())
                    controller.toggleMode()
                }
                fixture.awaitReady(0)
                fixture.ui {
                    assertNotSame(book, view)
                    assertTrue(book.isReleased)
                    assertEquals(ReadingMode.VERSE, state!!.mode)
                    assertEquals(saved, view.scrollY)
                    assertEquals(saved, state!!.scrollY)
                    assertFalse(view.settings.javaScriptEnabled)
                    assertNull(view.bookPageInfo)
                    assertEquals(0, status.pageCount)
                    assertEquals(1, host.childCount)
                }
            }
        }
    }

    @Test
    fun sameDocumentModeKeyAndFreshEndIntentAreDistinctButEndAcknowledgementIsNot() {
        Fixture(documents(count = 1)).use { fixture ->
            fixture.awaitReady(0)
            lateinit var old: OfflineReaderView
            fixture.ui {
                old = view
                // No controller loading phase or new document identity to conceal a missing mode key.
                submitDirect(state!!.copy(mode = ReadingMode.VERSE))
            }
            fixture.awaitReady(0)
            fixture.ui {
                assertNotSame(old, view)
                assertFalse(view.paginatedBook)
                assertFalse(view.settings.javaScriptEnabled)
                assertNull(view.bookPageInfo)
                old = view
                submitDirect(state!!.copy(mode = ReadingMode.BOOK, enterAtEnd = true))
            }
            fixture.awaitReady(0)
            fixture.ui {
                assertNotSame(old, view)
                assertBookPage()
                assertEquals(status.pageCount - 1, status.pageIndex)
                assertFalse(state!!.enterAtEnd)
            }
            fixture.turn(forward = false)
            fixture.ui {
                old = view
                // A genuinely new false -> true request at the same key must reposition as well.
                assertEquals(status.pageCount - 2, status.pageIndex)
                submitDirect(state!!.copy(enterAtEnd = true))
            }
            fixture.awaitReady(0)
            fixture.fence()
            fixture.ui {
                assertNotSame(old, view)
                assertBookPage()
                assertEquals(status.pageCount - 1, status.pageIndex)
                assertEquals(status.anchorRecord, state!!.location.position)
                assertFalse(state!!.enterAtEnd)
                assertEquals(1, host.childCount)
            }
        }
    }

    @Test
    fun liveResourceAnchorDoesNotReloadButAnExplicitSameResourceJumpAndChangedHtmlDo() {
        Fixture(documents(count = 1)).use { fixture ->
            fixture.awaitReady(0)
            lateinit var old: OfflineReaderView
            lateinit var document: ReaderDocument
            var target = 0
            fixture.ui {
                old = view
                document = state!!.document!!
                val resource = document.bookResource!!
                val selected = resource.lastEntry
                val entry = resource.position.outline.entries[selected]
                target = entry.node.record
                val page = status.pageIndex
                // This is header/bookmark publication, not a request to regenerate HTML.
                controller.saveBookAnchor(target)
                assertSame(document, state!!.document)
                assertSame(old, host.settledView)
                assertEquals(entry.source.displayTitle, state!!.location.title)
                assertEquals(target, state!!.location.position)
                assertEquals(page, status.pageIndex)
                assertEquals(1, loads)

                // No loading phase or different range/HTML to hide a missing identity check.
                val requested = resource.copy(position = BookPosition(resource.position.outline, selected), anchorRecord = target)
                submitDirect(state!!.copy(document = document.copy(bookPosition = requested.position, bookResource = requested)))
            }
            fixture.awaitReady(0)
            fixture.fence()
            val targetPage = fixture.recordFirstPage(target)
            fixture.ui {
                assertNotSame(old, view)
                assertTrue(old.isReleased)
                assertBookPage()
                assertSame(document.html, state!!.document!!.html)
                assertEquals(target, view.initialBookAnchor!!.toInt())
                assertTrue(targetPage > 0)
                assertEquals(targetPage, status.pageIndex)
                assertEquals(1, loads)
                old = view
                // Resource metadata is the fallback when a direct caller has no usable position.
                submitDirect(state!!.copy(location = state!!.location.copy(position = 0),
                    document = state!!.document!!.copy()))
            }
            fixture.awaitReady(0)
            fixture.ui {
                assertNotSame(old, view)
                assertEquals(target, view.initialBookAnchor!!.toInt())
                assertEquals(targetPage, status.pageIndex)
                old = view
                val replacement = state!!.document!!.withParagraphs(listOf(
                    Paragraph(docs[0].section.node.record, "Synthetic short replacement.", "PA_Purport", ""),
                ))
                submitDirect(state!!.copy(document = replacement))
            }
            fixture.awaitReady(0)
            fixture.fence()
            fixture.ui {
                assertNotSame(old, view)
                assertTrue(old.isReleased)
                assertBookPage()
                assertEquals(0, status.pageIndex)
                assertEquals(1, status.pageCount)
                assertEquals(docs[0].section.node.record, status.anchorRecord)
                assertEquals(status.anchorRecord, state!!.location.position)
                assertEquals(1f, status.progress, 0f)
                // There are two outline entries, but no neighbouring resource or native column.
                assertFalse(status.canPrevious)
                assertFalse(status.canNext)
                repeat(3) { host.previous(); host.next() }
                assertFalse(host.isBusy)
                assertEquals(0, nextCalls + previousCalls)
                assertTrue(scrollReports.isEmpty())
                assertEquals(1, host.childCount)
            }
        }
    }

    @Test
    fun alwaysVisibleSynonymsResizeAndTextZoomReflowColumnsAndAnchorsWithoutReloadOrStatusLoops() {
        val docs = documents(count = 1).map { it.withParagraphs(listOf(
            Paragraph(it.section.node.record, "SYNONYMS", "PA_Titles", "LE_Verse_Section"),
            Paragraph(it.section.node.record + 1,
                (0 until 320).joinToString(" ") { word -> "synthetic$word" } + " नमूना ā.", "PA_Synonyms", ""),
            Paragraph(it.section.node.end, "Synthetic closing text.", "PA_Purport", ""),
        )) }
        val headingRecord = docs.single().section.node.record
        val bodyRecord = headingRecord + 1
        Fixture(docs).use { fixture ->
            fixture.awaitReady(0)
            lateinit var retained: OfflineReaderView
            lateinit var document: ReaderDocument
            lateinit var resource: BookResource
            lateinit var documentUrl: String
            fixture.ui {
                retained = view
                document = state!!.document!!
                resource = requireNotNull(document.bookResource)
                documentUrl = requireNotNull(view.url)
                assertBookPage()
                assertBookSecurity()
                assertTrue("Synonyms occupy their full columns before any interaction", status.pageCount > 1)
                assertFalse(status.canPrevious)
                assertTrue(status.canNext)
                assertEquals(0, status.pageIndex)
                assertEquals(0f, status.progress, 0f)
                assertEquals(bodyRecord, status.anchorRecord)
                assertTrue(view.canNextUnit)
                assertEquals(0, readerTaps)
            }
            val synonyms = JSONObject(fixture.eval("""
                (function(){var h=document.getElementById('record-'+$headingRecord);
                    var b=document.getElementById('record-'+$bodyRecord),v=window.visualViewport;
                    return {disclosures:document.querySelectorAll('details,summary').length,
                        heading:h.textContent,headingDisplay:getComputedStyle(h).display,
                        bodyDisplay:getComputedStyle(b).display,
                        visible:Array.from(b.getClientRects()).some(function(r){return r.width>0&&r.height>0&&
                            r.right-v.offsetLeft>0&&r.left-v.offsetLeft<v.width&&
                            r.bottom-v.offsetTop>0&&r.top-v.offsetTop<v.height;})};})()
            """))
            assertEquals(0, synonyms.getInt("disclosures"))
            assertEquals("SYNONYMS", synonyms.getString("heading"))
            assertEquals("none", synonyms.getString("headingDisplay"))
            assertEquals("block", synonyms.getString("bodyDisplay"))
            assertTrue(synonyms.getBoolean("visible"))
            var reports = 0
            var anchors = 0
            fixture.ui {
                reports = statusReports
                anchors = anchorReports.size
                repeat(10) { host.submit(state!!, zoom) { null } }
            }
            fixture.fence()
            fixture.fence()
            fixture.ui {
                assertEquals(reports, statusReports)
                assertEquals(anchors, anchorReports.size)
                assertEquals(0, nextCalls + previousCalls)
                assertEquals(0f, status.progress, 0f)
                assertEquals(0, readerTaps)
            }
            fixture.turn(forward = true)
            var previousCount = 0
            var visibleAnchor = 0
            var newWidth = 0
            fixture.ui {
                previousCount = status.pageCount
                visibleAnchor = status.anchorRecord
                assertEquals(bodyRecord, visibleAnchor)
                newWidth = host.width * 3 / 4
                host.layoutParams = host.layoutParams.apply { width = newWidth; height = host.height * 3 / 4 }
                host.requestLayout()
            }
            fixture.await("Native resize must repaginate around the visible paragraph") {
                host.settledView?.width == newWidth && status.pageCount > previousCount
            }
            fixture.awaitReady(0)
            fixture.ui {
                assertBookPage()
                assertEquals(visibleAnchor, status.anchorRecord)
                assertTrue(status.pageIndex > 0)
                assertSame(retained, view)
                assertSame(document, state!!.document)
                assertSame(resource, state!!.document!!.bookResource)
                assertEquals(documentUrl, view.url)
                previousCount = status.pageCount
                zoom = 150
                host.submit(state!!, zoom) { null }
            }
            fixture.await("Text zoom must repaginate without replacing the document") { status.pageCount > previousCount }
            fixture.awaitReady(0)
            fixture.ui {
                assertBookPage()
                assertEquals(visibleAnchor, status.anchorRecord)
                assertTrue(status.pageIndex > 0)
                assertSame(retained, view)
                assertSame(document, state!!.document)
                assertSame(resource, state!!.document!!.bookResource)
                assertEquals(documentUrl, view.url)
                assertEquals(1, loads)
                assertEquals(1, host.childCount)
                assertTrue(scrollReports.isEmpty())
                assertEquals(0, readerTaps)
            }
            // Native post-zoom draws can schedule one more reflow after the host reports settled.
            fixture.await("Native layout settles after zoom") { view.settledReadingRecord == visibleAnchor }
            fixture.fence()
            fixture.ui { tapChrome() }
            fixture.await("A settled native background tap is forwarded to chrome") { readerTaps == 1 }
            fixture.ui {
                assertEquals(0, nextCalls + previousCalls)
                assertSame(retained, view)
            }
        }
    }

    @Test
    fun interruptedBookTurnAndSupersededIncomingCallbacksCannotPublishToTheNewResource() {
        Fixture(documents()).use { fixture ->
            fixture.awaitReady(0)
            lateinit var old: OfflineReaderView
            lateinit var superseded: OfflineReaderView
            lateinit var staleReady: () -> Unit
            lateinit var staleScroll: (Int) -> Unit
            lateinit var staleBook: (BookPageInfo) -> Unit
            lateinit var staleTap: () -> Unit
            lateinit var staleViewport: () -> Unit
            lateinit var staleFailure: () -> Unit
            lateinit var oldInfo: BookPageInfo
            fixture.ui {
                old = view
                oldInfo = requireNotNull(old.bookPageInfo)
                staleScroll = old.onScrollPosition
                staleBook = old.onBookPageChanged
                staleTap = old.onReaderTap
                staleViewport = old.onViewportChanged
                staleFailure = old.onRenderFailed
                val anchors = anchorReports.size
                host.next() // Starts a native column animation/fence, not a controller request.
                controller.open("synthetic-book", "en", docs[1].section.node)
                superseded = (0 until host.childCount).map { host.getChildAt(it) }
                    .filterIsInstance<OfflineReaderView>().single { it !== old }
                staleReady = superseded.onRendered
                controller.open("synthetic-book", "en", docs[2].section.node)
                staleReady()
                staleScroll(12345)
                staleBook(oldInfo)
                staleTap()
                staleViewport()
                staleFailure()
                assertTrue(superseded.isReleased)
                assertTrue(host.childCount <= 2)
                assertEquals(0, state!!.scrollY)
                assertEquals(anchors, anchorReports.size)
                assertEquals(0, readerTaps)
            }
            fixture.awaitReady(2)
            fixture.fence()
            fixture.ui {
                val ready = view
                val reports = anchorReports.size
                staleReady(); staleScroll(12345); staleBook(oldInfo); staleTap(); staleViewport(); staleFailure()
                assertSame(ready, view)
                assertBookPage()
                assertEquals(0, view.scrollY)
                assertEquals(0, state!!.scrollY)
                assertEquals(reports, anchorReports.size)
                assertEquals(0, readerTaps)
                assertTrue(scrollReports.isEmpty())
                assertTrue(old.isReleased)
                assertFalse(status.renderFailed)
            }
            fixture.fence() // Drain the posted stale failure too, without waiting a guessed duration.
            fixture.ui { assertFalse(status.renderFailed); assertEquals(1, host.childCount) }
        }
    }

    @Test
    fun releaseDuringBookTurnInvalidatesAnchorTapAndReadinessCallbacksAndRemainsIdempotent() {
        Fixture(documents(count = 1)).use { fixture ->
            fixture.awaitReady(0)
            fixture.ui {
                val releasedView = view
                val ready = releasedView.onRendered
                val scroll = releasedView.onScrollPosition
                val book = releasedView.onBookPageChanged
                val info = requireNotNull(releasedView.bookPageInfo)
                val tap = releasedView.onReaderTap
                val viewport = releasedView.onViewportChanged
                val failed = releasedView.onRenderFailed
                val next = releasedView.onNextUnit
                host.next()
                val statuses = statusReports
                val scrolls = scrollReports.size
                val anchors = anchorReports.size
                val taps = readerTaps
                host.release()
                host.release()
                ready(); scroll(9999); book(info); tap(); viewport(); failed(); next()
                host.next(); host.previous(); host.submit(state!!, zoom) { null }
                assertTrue(releasedView.isReleased)
                assertNull(host.currentView)
                assertNull(host.settledView)
                assertEquals(0, host.childCount)
                assertEquals(statuses, statusReports)
                assertEquals(scrolls, scrollReports.size)
                assertEquals(anchors, anchorReports.size)
                assertEquals(taps, readerTaps)
                assertEquals(0, nextCalls + previousCalls)
            }
        }
    }

    private fun turnToBoundary(fixture: Fixture, forward: Boolean) {
        repeat(100) {
            var canTurn = false
            fixture.ui {
                val info = requireNotNull(view.bookPageInfo)
                canTurn = if (forward) info.page < info.count - 1 else info.page > 0
            }
            if (!canTurn) return
            fixture.turn(forward)
        }
        throw AssertionError("Fixed-column turns must reach a finite resource boundary without skipping content")
    }

    private fun documents(count: Int = 3, compact: Boolean = false, legacy: Boolean = false,
        bookMode: Boolean = true): List<ReaderDocument> {
        val root = ContentNode(100, 0, "Synthetic book", 99 + count * 200, count, 1)
        val nodes = List(count) { index -> ContentNode(100 + index * 200, 100, "Synthetic resource $index",
            299 + index * 200, if (legacy) 0 else 2, 2) }
        val members = if (legacy) 1 else 2
        val entries = nodes.flatMapIndexed { index, node ->
            if (legacy) listOf(node) else List(members) { member ->
                ContentNode(node.record + member * 100, node.record, "Synthetic verse ${index * members + member + 1}",
                    node.record + member * 100 + 99, 0, 3)
            }
        }
        val outline = BookOutline(root, entries.map { BookEntry(it, it, ReadingUnitKind.VERSE, root.title) })
        return nodes.mapIndexed { index, node ->
            val first = index * members
            val last = first + members - 1
            val paragraphs = (first..last).flatMap { entryIndex ->
                val entry = entries[entryIndex]
                val bodies = if (index == 0) 12 / members else 1
                listOf(Paragraph(entry.record, "TEXT ${entryIndex + 1}", "PA_Textnum", "LE_Verse_Number")) +
                    List(bodies) { paragraph ->
                        Paragraph(if (paragraph == bodies - 1) entry.end else entry.record + paragraph + 1,
                            if (index == 0) "Synthetic purport $paragraph नमूना ā. " +
                                "Continuous original prose keeps neighbouring verses together on fixed horizontal pages. ".repeat(2)
                            else "Synthetic verse ${entryIndex + 1} complete.",
                            "PA_Purport", "")
                    }
            }
            val section = ReadingSection(node, if (legacy) ReadingUnitKind.VERSE else ReadingUnitKind.CHAPTER,
                root.title, paragraphs, nodes.getOrNull(index - 1), nodes.getOrNull(index + 1))
            val position = BookPosition(outline, first)
            ReaderDocument(section,
                FolioText.document(paragraphs, "en", ReaderPreferences(), compactVerse = compact, bookMode = bookMode),
                position, if (legacy) null else BookResource(section, position, first, last, node.record))
        }
    }

    private fun ReaderDocument.withParagraphs(paragraphs: List<Paragraph>): ReaderDocument {
        val replacement = section.copy(paragraphs = paragraphs)
        return copy(section = replacement,
            html = FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true),
            bookResource = bookResource?.copy(section = replacement))
    }

    private class Fixture(
        val docs: List<ReaderDocument>,
        verseDocs: List<ReaderDocument> = docs.map { document ->
            document.copy(html = FolioText.document(document.section.paragraphs, "en", ReaderPreferences(),
                compactVerse = true, bookMode = false), bookPosition = null, bookResource = null)
        },
        initialIndex: Int = 0,
        initialMode: ReadingMode = ReadingMode.BOOK,
    ) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var host: ReaderPageHost
        lateinit var controller: ReaderController
        var state: ReaderState? = null
        var status = ReaderPageStatus()
        var loads = 0
        var nextCalls = 0
        var previousCalls = 0
        var statusReports = 0
        var readerTaps = 0
        var zoom = 100
        var pendingResponse: CompletableDeferred<ReaderDocument>? = null
        val scrollReports = mutableListOf<Pair<Int, Int>>()
        val anchorReports = mutableListOf<Pair<Int, Int>>()
        private var observer: () -> Unit = {}
        private var direct = false
        private var anchoredView: OfflineReaderView? = null
        private var acknowledgedPage: BookPageInfo? = null
        private var fenceId = 9_000L
        val view: OfflineReaderView get() = requireNotNull(host.settledView)

        init {
            scenario.onActivity { activity ->
                host = ReaderPageHost(activity)
                val root = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                val width = minOf((360 * density).toInt(), activity.resources.displayMetrics.widthPixels)
                root.addView(host, FrameLayout.LayoutParams(width, (360 * density).toInt()))
                activity.setContentView(root)
                host.onStatusChanged = {
                    assertTrue("The session retains at most two native documents", host.childCount <= 2)
                    status = it
                    statusReports++
                    observer()
                }
                controller = ReaderController(scope,
                    loadDocument = { location, _ ->
                        loads++
                        (pendingResponse?.await() ?: verseDocs.first { it.section.node.record == location.start })
                            .copy(bookPosition = null, bookResource = null)
                    },
                    loadBookDocument = { location, _ ->
                        loads++
                        val document = pendingResponse?.await() ?: docs.first {
                            location.position in it.section.node.record..it.section.node.end
                        }
                        val resource = document.bookResource
                        if (resource == null) document else {
                            val outline = resource.position.outline
                            val selected = outline.entries.indexOfFirst { location.position in it.node.record..it.node.end }
                            val position = BookPosition(outline, selected)
                            document.copy(bookPosition = position,
                                bookResource = resource.copy(position = position, anchorRecord = location.position))
                        }
                    },
                    onChange = { value ->
                        state = value
                        value?.let { host.submit(it, zoom) { null } }
                        observer()
                    }, initialMode = initialMode)
                host.onNextUnit = { nextCalls++; controller.next() }
                host.onPreviousUnit = { previousCalls++; controller.previous() }
                host.onScrollPosition = { offset ->
                    assertFalse("Offsets are published only after settling", host.isBusy)
                    assertEquals("BOOK must never publish a pixel bookmark", ReadingMode.VERSE, state!!.mode)
                    scrollReports += state!!.location.start to offset
                    if (direct) {
                        state = state!!.copy(scrollY = offset, enterAtEnd = false)
                        host.submit(state!!, zoom) { null }
                    } else controller.saveScroll(offset)
                    observer()
                }
                host.onBookAnchor = { record ->
                    assertFalse("Anchors are published only after settling", host.isBusy)
                    val current = requireNotNull(state)
                    assertEquals(ReadingMode.BOOK, current.mode)
                    val document = requireNotNull(current.document)
                    val info = requireNotNull(view.bookPageInfo)
                    assertEquals(info.anchorRecord, record)
                    assertEquals(info.nativeOffset, view.scrollX)
                    assertEquals(0, view.scrollY)
                    assertTrue(document.section.paragraphs.any { it.record == record })
                    anchoredView = view
                    acknowledgedPage = info
                    anchorReports += current.location.start to record
                    if (direct) {
                        val position = document.bookResource?.position ?: document.bookPosition
                        val source = position?.outline?.entries?.firstOrNull { record in it.node.record..it.node.end }?.source
                        state = current.copy(location = current.location.copy(position = record,
                            title = source?.displayTitle ?: current.location.title), enterAtEnd = false)
                        host.submit(state!!, zoom) { null }
                    } else {
                        // The production BOOK acknowledgement is saveBookAnchor, never saveScroll.
                        controller.saveBookAnchor(record)
                    }
                    observer()
                }
                host.onReaderTap = {
                    assertFalse("Only the settled foreground page can toggle chrome", host.isBusy)
                    assertSame(host.currentView, host.settledView)
                    readerTaps++
                    observer()
                }
                controller.open("synthetic-book", "en", docs[initialIndex].section.node)
            }
        }

        fun submitDirect(value: ReaderState) {
            direct = true
            state = value
            host.submit(value, zoom) { null }
        }

        fun ui(block: Fixture.() -> Unit) { scenario.onActivity { block(this) } }

        fun await(message: String, condition: Fixture.() -> Boolean) {
            val ready = CountDownLatch(1)
            ui { observer = { if (condition()) ready.countDown() }; observer() }
            try {
                if (!ready.await(20, TimeUnit.SECONDS)) {
                    var detail = ""
                    ui {
                        detail = "status=$status state.loading=${state?.loading} enterAtEnd=${state?.enterAtEnd} " +
                            "position=${state?.location?.position} info=${host.currentView?.bookPageInfo} " +
                            "settled=${host.settledView != null} anchored=${anchoredView === host.currentView} " +
                            "acknowledged=$acknowledgedPage children=${host.childCount}"
                    }
                    fail("$message: $detail")
                }
                ui { assertTrue(message, condition()) }
            } finally { ui { observer = {} } }
        }

        private fun settled(): Boolean {
            val reader = host.settledView ?: return false
            val current = state ?: return false
            if (status.busy || status.renderFailed || current.loading || current.failed || current.enterAtEnd) return false
            if (current.mode != ReadingMode.BOOK) return true
            val info = reader.bookPageInfo ?: return false
            return anchoredView === reader && acknowledgedPage == info &&
                (current.document?.bookResource == null || current.location.position == info.anchorRecord)
        }

        fun awaitReady(index: Int) {
            await("Resource $index must paint, settle and acknowledge its anchor") {
                settled() && state?.location?.start == docs[index].section.node.record
            }
            var paginated = false
            ui { paginated = view.paginatedBook }
            if (paginated) assertBookGeometry()
        }

        fun fence() {
            val painted = CountDownLatch(1)
            ui {
                val reader = view
                reader.postVisualStateCallback(++fenceId, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { reader.post { painted.countDown() } }
                })
                reader.invalidate()
            }
            assertTrue("Native visual fence must complete", painted.await(20, TimeUnit.SECONDS))
        }

        fun turn(forward: Boolean) {
            lateinit var before: BookPageInfo
            var requestCount = 0
            var anchors = 0
            lateinit var retained: OfflineReaderView
            lateinit var document: ReaderDocument
            ui {
                retained = view
                before = requireNotNull(view.bookPageInfo)
                document = state!!.document!!
                requestCount = loads
                anchors = anchorReports.size
                assertTrue(if (forward) before.page < before.count - 1 else before.page > 0)
                // BOOK swipes perform a closed asynchronous selection check before dispatching.
                swipe(forward)
            }
            val target = before.page + if (forward) 1 else -1
            await("The adjacent native column must paint and acknowledge its source anchor") {
                settled() && view.bookPageInfo?.page == target
            }
            ui {
                assertSame(retained, view)
                assertSame(document, state!!.document)
                assertBookPage()
                assertEquals(before.count, status.pageCount)
                assertEquals(target, status.pageIndex)
                val after = requireNotNull(view.bookPageInfo)
                val delta = view.scrollX - before.nativeOffset
                assertEquals("Each turn reaches the adjacent absolute rounded offset",
                    after.nativeOffset - before.nativeOffset, delta)
                assertEquals(requestCount, loads)
                assertEquals(anchors + 1, anchorReports.size)
                assertEquals(1, host.childCount)
            }
            assertBookGeometry()
        }

        fun assertBookPage() {
            val info = requireNotNull(view.bookPageInfo)
            assertTrue(view.paginatedBook)
            assertTrue(view.settings.javaScriptEnabled)
            assertTrue(info.count >= 1 && info.page in 0 until info.count)
            assertEquals(info.page, status.pageIndex)
            assertEquals(info.count, status.pageCount)
            assertEquals(info.anchorRecord, status.anchorRecord)
            assertTrue(info.viewportWidth > 0)
            assertEquals(info.nativeOffset, view.scrollX)
            assertEquals(0, view.scrollY)
            assertEquals(0, view.scrollRange)
            val progress = if (info.count == 1) 1f else info.page.toFloat() / (info.count - 1)
            assertEquals(progress, status.progress, .0001f)
            assertTrue(state!!.document!!.section.paragraphs.any { it.record == info.anchorRecord })
            if (state!!.document!!.bookResource != null) assertEquals(info.anchorRecord, state!!.location.position)
        }

        /** Called off the UI thread after readiness/turns, including resize and zoom reflows. */
        private fun assertBookGeometry() {
            val dom = JSONObject(eval("""
                (function(){var v=visualViewport,flow=document.querySelector('.book-flow');
                    return {width:v.width,pitch:flow.getBoundingClientRect().width,x:v.pageLeft,y:v.pageTop,
                        dpr:devicePixelRatio,visualScale:v.scale,fonts:document.fonts.status,
                        extent:Math.max(document.scrollingElement.scrollWidth,document.documentElement.scrollWidth,
                            document.body.scrollWidth,v.width)};})()
            """))
            val pitch = dom.getDouble("pitch")
            assertTrue(pitch > 0)
            assertEquals("loaded", dom.getString("fonts"))
            ui {
                val info = requireNotNull(view.bookPageInfo)
                val scale = view.nativeContentScale
                assertTrue(scale.isFinite() && scale > 0)
                assertEquals(dom.getDouble("dpr") * dom.getDouble("visualScale"), scale, .00001)
                assertEquals("Native and visual widths agree within one CSS pixel",
                    view.width / scale, dom.getDouble("width"), 1.0)
                assertEquals((pitch * scale).roundToInt(), info.viewportWidth)
                assertEquals((info.page * pitch * scale).roundToInt(), info.nativeOffset)
                assertEquals(info.nativeOffset, view.scrollX)
                assertEquals(view.scrollX.toDouble(), dom.getDouble("x") * scale, 2.0)
                assertEquals(info.page * pitch, dom.getDouble("x"), 1.0)
                assertEquals(0.0, dom.getDouble("y"), .01)
                assertEquals(ceil((dom.getDouble("extent") - 1.0).coerceAtLeast(pitch) / pitch).toInt(), info.count)
            }
        }

        fun assertBookSecurity() {
            assertTrue("Folio explicitly prohibits source scripts despite native DOM queries",
                state!!.document!!.html.contains("script-src 'none'"))
            assertTrue(view.settings.javaScriptEnabled)
            assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
            assertFalse(view.settings.domStorageEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertTrue(view.settings.blockNetworkLoads)
            val response = requireNotNull(view.webViewClient.shouldInterceptRequest(view,
                MainDocumentRequest(requireNotNull(view.url))))
            try {
                assertEquals(200, response.statusCode)
                val policy = requireNotNull(response.responseHeaders?.get("Content-Security-Policy"))
                assertTrue(policy.contains("script-src 'none'"))
                assertTrue(policy.contains("connect-src 'none'"))
                assertTrue(policy.contains("frame-src 'none'"))
            } finally { response.data?.close() }
        }

        fun eval(script: String): String {
            val evaluated = CountDownLatch(1)
            var result: String? = null
            ui {
                assertTrue("Read-only DOM inspection is BOOK-only", view.paginatedBook)
                view.evaluateJavascript(script) { result = it; evaluated.countDown() }
            }
            assertTrue("Read-only native DOM query must finish", evaluated.await(20, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        fun recordFirstPage(record: Int): Int {
            require(record > 0)
            return eval("""
                (function(){var r=document.getElementById('record-'+$record).getClientRects()[0];
                    var pitch=document.querySelector('.book-flow').getBoundingClientRect().width;
                    return Math.floor((r.left+window.scrollX+.5)/pitch);})()
            """.trimIndent()).toInt()
        }

        fun swipe(forward: Boolean, horizontal: Boolean = true) {
            val start = if (forward) .85f else .15f
            val end = if (forward) .15f else .85f
            val now = SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN, start, 0L),
                Triple(MotionEvent.ACTION_MOVE, (start + end) / 2, 35L),
                Triple(MotionEvent.ACTION_MOVE, end, 65L),
                Triple(MotionEvent.ACTION_UP, end, 80L)).forEach { (action, position, elapsed) ->
                val x = host.width * if (horizontal) position else .5f
                val y = host.height * if (horizontal) .55f else position
                val event = MotionEvent.obtain(now, now + elapsed, action, x, y, 0)
                host.dispatchTouchEvent(event)
                event.recycle()
            }
        }

        fun tapChrome() = tap(host.width - 2f, host.height * .75f)

        private fun tap(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val time = if (action == MotionEvent.ACTION_UP) now + 60 else now
                val event = MotionEvent.obtain(now, time, action, x, y, 0)
                host.dispatchTouchEvent(event)
                event.recycle()
            }
        }

        override fun close() {
            try {
                scenario.onActivity { observer = {}; host.release(); controller.close(); scope.cancel() }
            } finally { scenario.close() }
        }
    }

    private class MainDocumentRequest(private val address: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}