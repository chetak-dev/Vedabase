package org.vedareader.reader

import android.os.SystemClock
import android.util.Base64
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.BookEntry
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.ContentObject
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Real WebView/host, synthetic resource HTML and bundled fonts. No source scripts or bridges.
 * Test-only native evaluation locates hit targets and creates an accessibility-style selection.
 * Visual fences/event predicates replace sleeps; no database or device settings are touched.
 */
@RunWith(AndroidJUnit4::class)
class ScrollingReaderTapTest {
    @Test
    fun blankAndProseTapsEmitOnceInBothModes() {
        for (mode in listOf(ReadingMode.VERSE, ReadingMode.BOOK)) {
            Fixture(mode).use { fixture ->
                fixture.awaitReady()
                var anchor = 0
                var offset = 0
                fixture.ui {
                    assertEquals(mode == ReadingMode.VERSE, view.scrollingBook)
                    assertEquals(mode == ReadingMode.BOOK, view.paginatedBook)
                    anchor = status.anchorRecord
                    offset = view.scrollY
                }
                val prose = fixture.point("#record-100 .folio-block", word = true)
                for ((index, point) in listOf(fixture.blank(), prose).withIndex()) {
                    assertEquals("A rendered non-interactive target", "0", fixture.hit(point))
                    fixture.ui { tap(point) }
                    fixture.await("A genuine $mode tap reaches the host") { taps == index + 1 }
                    fixture.drain()
                    fixture.ui {
                        assertEquals("Exactly one callback per DOWN/UP", index + 1, taps)
                        assertEquals(0, navigation)
                        assertEquals(anchor, status.anchorRecord)
                        assertEquals(offset, view.scrollY)
                        assertEquals(1, host.childCount)
                    }
                }
            }
        }
    }

    @Test
    fun swipesReversalsLongPressMultitouchAndOffsetChangesNeverEmitTaps() {
        Fixture().use { fixture ->
            fixture.awaitReady()
            fixture.ui {
                assertTrue(view.scrollRange > view.viewportHeight)
                swipe(horizontal = false)
            }
            fixture.await("Native vertical scrolling remains functional") { view.scrollY > 0 }
            fixture.quiet()
            fixture.ui { swipe(horizontal = true) }
            fixture.quiet()
            // At-slop excursions, including a reversal hidden in MotionEvent history, never
            // become taps merely because UP is back at DOWN. Exercise both coordinate axes.
            for (horizontal in listOf(false, true)) {
                for (batched in listOf(false, true)) {
                    fixture.ui { reversal(horizontal, batched) }
                    fixture.quiet()
                }
            }
            val blank = fixture.blank()
            fixture.ui { tap(blank, duration = ViewConfiguration.getLongPressTimeout().toLong() + 1) }
            fixture.drain()
            fixture.ui { multiTouch(blank) }
            fixture.quiet()
            fixture.ui { view.scrollTo(0, view.scrollRange / 2) }
            fixture.quiet()
            fixture.ui {
                val offset = view.scrollY
                tap(blank) // Its asynchronous DOWN/UP results cannot run until this UI call returns.
                view.scrollTo(0, offset + 2)
                assertEquals(offset + 2, view.scrollY)
                view.scrollTo(0, offset)
            }
            fixture.drain()
            fixture.ui {
                assertEquals(0, taps)
                assertEquals(0, navigation)
                assertEquals(0, view.scrollX)
            }
        }
    }

    @Test
    fun interactiveEndpointsAndSelectionsKeepNativeBehaviorWithoutTaps() {
        Fixture().use { fixture ->
            fixture.awaitReady()
            val link = fixture.point("a")
            fixture.ui { tap(link) }
            fixture.await("The real link still reaches its native unavailable handler") { links > 0 }
            fixture.drain()
            assertEquals("true", fixture.eval("document.querySelector('img').naturalWidth > 0"))
            val image = fixture.point("img")
            fixture.ui { tap(image) }
            fixture.drain()
            val summary = fixture.point("summary")
            fixture.ui { tap(summary) }
            fixture.drain()
            assertEquals("Native summary UP is not swallowed", "true",
                fixture.eval("document.querySelector('details').open"))
            fixture.ui { tap(summary) }
            fixture.drain()
            assertEquals("false", fixture.eval("document.querySelector('details').open"))

            // Cross a link boundary without reaching touch slop. BOTH actual endpoints must
            // be safe: querying DOWN coordinates again on UP would fail the first direction.
            val edge = fixture.point("a", leftEdge = true)
            var distance = 0f
            fixture.ui { distance = ViewConfiguration.get(view.context).scaledTouchSlop / 4f }
            val outside = edge.copy(x = edge.x - distance)
            val inside = edge.copy(x = edge.x + distance)
            assertEquals("0", fixture.hit(outside))
            assertEquals(BookPagination.HIT_INTERACTIVE.toString(), fixture.hit(inside))
            fixture.ui { tap(outside, inside) }
            fixture.drain()
            fixture.ui { tap(inside, outside) }
            fixture.drain()

            assertEquals("true", fixture.eval("""
                (function(){var t=document.querySelector('#record-100 .folio-block').firstChild;
                    var r=document.createRange();r.setStart(t,0);r.setEnd(t,4);
                    var s=window.getSelection();s.removeAllRanges();s.addRange(r);
                    return !s.isCollapsed;})()
            """))
            val blank = fixture.blank()
            fixture.ui { tap(blank) }
            fixture.drain()
            fixture.eval("window.getSelection().removeAllRanges()")
            // Real native ActionMode, including one created and destroyed while a hit result
            // is pending. Checking only selectionMode at callback time is insufficient.
            fixture.ui {
                val selection = selectionMode()
                tap(blank)
                selection.finish()
            }
            fixture.drain()
            fixture.ui {
                tap(blank)
                selectionMode().finish()
            }
            fixture.drain()
            fixture.ui { assertEquals(0, taps); assertEquals(0, navigation) }
            fixture.eval("window.getSelection().removeAllRanges()")
            fixture.drain()
            fixture.ui { tap(blank) }
            fixture.await("Ending selection must not permanently disable normal taps") { taps == 1 }
            fixture.drain()
            fixture.ui { assertEquals(1, taps) }
        }
    }

    @Test
    fun pendingHitsAreInvalidatedByNewTouchReplacementBusyHostAndRelease() {
        Fixture().use { fixture ->
            fixture.awaitReady()
            val blank = fixture.blank()
            fixture.ui {
                tap(blank)
                val now = SystemClock.uptimeMillis()
                event(now, 0, MotionEvent.ACTION_DOWN, blank)
                event(now, 1, MotionEvent.ACTION_CANCEL, blank)
            }
            fixture.drain()
            fixture.ui { assertEquals("Old touch identity cannot emit", 0, taps) }

            val repainted = CountDownLatch(1)
            fixture.ui {
                tap(blank)
                val ready = view.onRendered
                view.onRendered = { ready(); repainted.countDown() }
                view.render("replacement-generation", state.document!!.html, 0, ::image)
            }
            assertTrue("The same WebView paints a new generation", repainted.await(20, TimeUnit.SECONDS))
            fixture.drain()
            fixture.ui { assertEquals("Old document hit results cannot emit", 0, taps) }

            lateinit var outgoing: OfflineReaderView
            lateinit var oldTap: () -> Unit
            fixture.ui {
                outgoing = view
                oldTap = outgoing.onReaderTap
                tap(blank)
                host.submit(state.copy(loading = true), 100, ::image)
                oldTap()
                assertTrue(host.isBusy)
                assertEquals(0, taps)
                state = state.copy(document = state.document!!.copy())
                host.submit(state, 100, ::image)
            }
            fixture.awaitReady()
            fixture.drain()
            fixture.ui {
                assertTrue(outgoing.isReleased)
                oldTap()
                assertEquals(0, taps)
            }
            fixture.ui { tap(blank) }
            fixture.await("The replacement still forwards taps") { taps == 1 }
            fixture.drain()
            fixture.ui {
                val released = view
                val callback = released.onReaderTap
                tap(blank) // Queue native hit work, then release in the same UI turn.
                host.release()
                // Re-arm counters so a missing lifecycle guard cannot hide behind no-op callbacks.
                released.onReaderTap = { taps++ }
                host.onReaderTap = { taps++ }
                callback()
                assertTrue(released.isReleased)
                assertNull(host.currentView)
                assertEquals(0, host.childCount)
            }
            fixture.paintProbe() // Never query the destroyed WebView.
            fixture.ui { assertEquals(1, taps); assertEquals(0, navigation) }
        }
    }

    private data class Point(val x: Float, val y: Float)

    private class Fixture(mode: ReadingMode = ReadingMode.VERSE) : AutoCloseable {
        private val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var host: ReaderPageHost
        var state = readerState(mode)
        var status = ReaderPageStatus()
        var taps = 0
        var links = 0
        var navigation = 0
        val view: OfflineReaderView get() = requireNotNull(host.settledView)

        init {
            scenario.onActivity { activity ->
                host = ReaderPageHost(activity)
                host.onStatusChanged = { status = it }
                host.onReaderTap = { taps++ }
                host.onLinkUnavailable = { links++ }
                host.onNextUnit = { navigation++ }
                host.onPreviousUnit = { navigation++ }
                val root = FrameLayout(activity)
                val density = activity.resources.displayMetrics.density
                root.addView(host, FrameLayout.LayoutParams(
                    minOf((360 * density).toInt(), activity.resources.displayMetrics.widthPixels), (360 * density).toInt()))
                activity.setContentView(root)
                host.submit(state, 100, ::image)
            }
        }

        fun image(name: String): ContentObject? = if (name == "tap.png") ContentObject("image/png", PNG) else null
        fun ui(block: Fixture.() -> Unit) { scenario.onActivity { block(this) } }

        fun await(label: String, condition: Fixture.() -> Boolean) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            ui {
                val deadline = SystemClock.uptimeMillis() + 20_000L
                val observer = object : Runnable {
                    override fun run() {
                        try {
                            check(!status.renderFailed) { "Native render failed" }
                            if (condition()) { done.countDown(); return }
                            check(SystemClock.uptimeMillis() < deadline) {
                                "$label; busy=${host.isBusy} taps=$taps scroll=${host.currentView?.scrollY}"
                            }
                            host.postOnAnimation(this)
                        } catch (error: Throwable) { failure = error; done.countDown() }
                    }
                }
                observer.run()
            }
            assertTrue(label, done.await(25, TimeUnit.SECONDS))
            failure?.let { throw AssertionError(label, it) }
        }

        fun awaitReady() = await("Resource must paint before input") { host.settledView != null && status.anchorRecord > 0 }

        fun eval(script: String): String {
            val done = CountDownLatch(1)
            var result: String? = null
            ui { view.evaluateJavascript(script) { result = it; done.countDown() } }
            assertTrue("Native test inspection", done.await(20, TimeUnit.SECONDS))
            return requireNotNull(result)
        }

        fun drain() {
            repeat(3) {
                eval("0") // Drain both the DOWN callback and the UP query it can enqueue.
                val done = CountDownLatch(1)
                ui {
                    view.postVisualStateCallback(901L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { done.countDown() }
                    })
                    view.invalidate()
                }
                assertTrue("Native visual fence", done.await(20, TimeUnit.SECONDS))
            }
        }

        fun quiet() {
            var previous = -1
            var since = SystemClock.uptimeMillis()
            await("Native scroll must settle") {
                if (view.scrollY != previous) { previous = view.scrollY; since = SystemClock.uptimeMillis() }
                SystemClock.uptimeMillis() - since >= 200
            }
            drain()
        }

        fun point(selector: String, word: Boolean = false, leftEdge: Boolean = false): Point {
            require(selector in setOf("a", "img", "summary", "#record-100 .folio-block"))
            val json = JSONObject(eval("""
                (function(){var e=document.querySelector('$selector'),v=visualViewport,r;
                    if ($word) {var t=document.createRange();t.setStart(e.firstChild,0);t.setEnd(e.firstChild,4);
                        r=t.getClientRects()[0];} else r=e.getClientRects()[0];
                    return {x:(r.left-v.offsetLeft+($leftEdge?0:Math.min(r.width/2,20)))/v.width,
                        y:(r.top-v.offsetTop+r.height/2)/v.height};})()
            """))
            assertTrue("Target is vertically visible", json.getDouble("y") in 0.0..1.0)
            var point = Point(0f, 0f)
            ui { point = Point((json.getDouble("x") * view.width).toFloat(), (json.getDouble("y") * view.height).toFloat()) }
            return point
        }

        fun blank(): Point {
            var point = Point(0f, 0f)
            ui { point = Point(2f, view.height * .8f) }
            return point
        }

        fun hit(point: Point): String {
            var query = ""
            ui { query = BookPagination.hitTest(point.x.roundToInt(), point.y.roundToInt(), view.width, view.height) }
            return eval(query)
        }

        fun tap(down: Point, up: Point = down, duration: Long = 60) {
            val now = SystemClock.uptimeMillis()
            event(now, 0, MotionEvent.ACTION_DOWN, down)
            event(now, duration, MotionEvent.ACTION_UP, up)
        }

        fun event(down: Long, elapsed: Long, action: Int, point: Point) {
            dispatch(MotionEvent.obtain(down, down + elapsed, action, point.x, point.y, 0))
        }

        private fun dispatch(event: MotionEvent) {
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; host.dispatchTouchEvent(event) }
            finally { event.recycle() }
        }

        fun swipe(horizontal: Boolean) {
            val now = SystemClock.uptimeMillis()
            for ((index, fraction) in listOf(.85f, .6f, .35f, .15f).withIndex()) {
                val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; 3 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                event(now, index * 60L, action, Point(view.width * if (horizontal) fraction else .5f,
                    view.height * if (horizontal) .5f else fraction))
            }
        }

        fun reversal(horizontal: Boolean, batched: Boolean) {
            val now = SystemClock.uptimeMillis()
            val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
            val start = Point(view.width * .5f, view.height * .5f)
            val away = if (horizontal) start.copy(x = start.x + slop) else start.copy(y = start.y + slop)
            event(now, 0, MotionEvent.ACTION_DOWN, start)
            if (batched) {
                val move = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, away.x, away.y, 0)
                move.addBatch(now + 40, start.x, start.y, 1f, 1f, 0)
                assertEquals(1, move.historySize)
                dispatch(move)
            } else {
                event(now, 20, MotionEvent.ACTION_MOVE, away)
                event(now, 40, MotionEvent.ACTION_MOVE, start)
            }
            event(now, 60, MotionEvent.ACTION_UP, start)
        }

        fun multiTouch(point: Point) {
            val now = SystemClock.uptimeMillis()
            event(now, 0, MotionEvent.ACTION_DOWN, point)
            val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(2) { id -> MotionEvent.PointerCoords().apply {
                x = point.x + id * 30f; y = point.y; pressure = 1f; size = 1f
            } }
            for ((index, action) in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP).withIndex()) {
                dispatch(MotionEvent.obtain(now, now + 20 + index * 20L,
                    action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
            }
            event(now, 60, MotionEvent.ACTION_UP, point)
        }

        fun selectionMode(): ActionMode = requireNotNull(view.startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { menu.add("Synthetic selection"); return true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = true
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }, ActionMode.TYPE_FLOATING))

        fun paintProbe() {
            val painted = CountDownLatch(1)
            var probe: OfflineReaderView? = null
            try {
                scenario.onActivity { activity ->
                    val reader = OfflineReaderView(activity)
                    probe = reader
                    reader.onRendered = { painted.countDown() }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(reader,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    reader.render("release-fence", "<html><body>Synthetic release fence.</body></html>", 0) { null }
                }
                assertTrue("Live sibling drains stale renderer work", painted.await(20, TimeUnit.SECONDS))
            } finally {
                scenario.onActivity {
                    probe?.let { reader -> (reader.parent as? ViewGroup)?.removeView(reader); reader.release() }
                }
            }
        }

        override fun close() {
            try { scenario.onActivity { host.release() } } finally { scenario.close() }
        }
    }

    companion object {
        private val PNG = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", Base64.DEFAULT)

        private fun readerState(mode: ReadingMode): ReaderState {
            val node = ContentNode(100, 0, "Synthetic work", 199, 0, 1)
            val paragraphs = listOf(
                Paragraph(100, "Synthetic prose नमूना ā.", "PA_Purport", ""),
                Paragraph(101, "<JL:Jump,\"fixture\">Native link<EL>", "", ""),
                Paragraph(102, "<OB:\"OB\";\"tap.png\">", "", ""),
                Paragraph(103, "Disclosure fixture.", "", ""),
                Paragraph(104, "Synthetic flowing text for native scrolling. ".repeat(150), "PA_Purport", ""),
                Paragraph(105, "Synthetic ending.", "PA_Purport", ""),
            )
            val section = ReadingSection(node, ReadingUnitKind.CHAPTER, node.title, paragraphs)
            val position = BookPosition(BookOutline(node, listOf(BookEntry(node, node, section.kind, node.title))), 0)
            val resource = BookResource(section, position, 0, 0, 100)
            // Static test-only image size/disclosure; keep the production resource flow/CSP/fonts.
            val html = FolioText.document(paragraphs, "en", ReaderPreferences(), bookMode = true,
                scrollingBook = mode == ReadingMode.VERSE)
                .replace("</style>", "img{width:40px;height:40px}</style>")
                .replace("Disclosure fixture.", "<details><summary>Native summary</summary>Disclosure body.</details>")
            return ReaderState(node.location("synthetic-tap"), "en", ReaderDocument(section, html, position, resource),
                loading = false, mode = mode)
        }
    }
}