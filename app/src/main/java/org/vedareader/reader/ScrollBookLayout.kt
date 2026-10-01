package org.vedareader.reader

import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native-only, read-only DOM inspection for Folio's non-column book flow. No page handlers. */
internal class ScrollBookLayout(
    private val view: OfflineReaderView,
    // Injectable only inside the module for deterministic cancellation/single-flight tests.
    private val evaluate: (String, (String) -> Unit) -> Unit = { script, receive ->
        view.evaluateJavascript(script) { receive(it) }
    },
) {
    private data class Viewport(
        val width: Int, val height: Int, val zoom: Int, val range: Int, val y: Int, val scale: Double,
    )
    private data class Layout(
        val width: Double, val height: Double, val extent: Double, val y: Double,
        val target: Double, val record: Int, val fraction: Double, val native: Viewport,
        val referenceRecord: Int,
    ) {
        fun sameGeometry(other: Layout) = abs(width - other.width) < .5 && abs(height - other.height) < .5 &&
            abs(extent - other.extent) < .5 && native.copy(y = 0) == other.native.copy(y = 0)
    }
    private data class Reposition(val anchor: Int?, val fraction: Double, val offset: Int)
    private class Session(
        val anchor: Int?, val offset: Int, val atEnd: Boolean, val fraction: Double,
        val ready: () -> Unit, val changed: (Int) -> Unit, val failed: () -> Unit,
    ) {
        var initialized = false
        var reposition: Reposition? = null
    }

    private var session: Session? = null
    private var work: Any? = null
    // Kept across stop/start: a replaced document's outstanding callback still owns this slot.
    private var queryInFlight = false
    private var pending = false
    private var reflowPaused = false
    private var touching = false
    private var observed: Viewport? = null
    private var painted: Viewport? = null
    private var lastMotion = 0L
    private var timeout: Runnable? = null
    var anchorFraction = 0.0
        private set
    var anchorRecord: Int = 0
        private set
    var referenceRecord: Int = 0
        private set
    val settled: Boolean get() = !touching && !reflowPaused && session?.initialized == true && session?.reposition == null && work == null && !pending &&
        !queryInFlight && painted == viewport() && SystemClock.uptimeMillis() - lastMotion >= QUIET_MS

    private val frame = Runnable {
        pending = false
        val current = session
        if (current != null && work == null && !touching && !reflowPaused) {
            if (current.initialized && SystemClock.uptimeMillis() - lastMotion < QUIET_MS) schedule()
            else {
                armTimeout(current) // Also bound waiting for a replaced document's query to return.
                if (!queryInFlight) measure(current)
            }
        }
    }

    fun start(anchor: Int?, offset: Int, atEnd: Boolean, ready: () -> Unit,
        changed: (Int) -> Unit, failed: () -> Unit, fraction: Double = 0.0) {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        stop()
        session = Session(anchor, offset, atEnd, fraction, ready, changed, failed)
        observed = viewport()
        schedule()
    }

    /** Snapshot BEFORE an explicit textZoom assignment; resize/ordinary observation never seeks.
     * Keep the first painted anchor through a burst, not intermediate geometry from earlier zooms.
     * An unfinished initial render retains its original anchor/offset/end intent and ready callback.
     */
    fun reflow() {
        val current = session ?: return
        if (view.isReleased) return
        if (current.initialized && current.reposition == null) {
            current.reposition = Reposition(anchorRecord.takeIf { it > 0 }, anchorFraction,
                painted?.y ?: view.scrollY)
        }
        work = null // Outstanding queries/fences cannot publish the pre-zoom geometry.
        lastMotion = SystemClock.uptimeMillis()
        // Retain both the single-flight query slot and the deadline across coalesced changes.
        schedule()
    }

    /** Snapshot the painted paragraph/fraction BEFORE native CSS mutation. A queued frame, query,
     * or visual fence must not publish old geometry while evaluateJavascript is still outstanding.
     */
    fun pauseReflow() {
        reflowPaused = true
        reflow()
        view.removeCallbacks(frame)
        pending = false
        session?.let { armTimeout(it) }
    }

    /** Called only after the latest CSS mutation is acknowledged. Retains the first burst anchor. */
    fun resumeReflow() {
        if (!reflowPaused) return
        reflowPaused = false
        lastMotion = SystemClock.uptimeMillis()
        schedule()
    }

    fun stop() {
        session = null
        work = null
        view.removeCallbacks(frame)
        pending = false
        reflowPaused = false
        clearTimeout()
        touching = false
        observed = null
        painted = null
        anchorRecord = 0
        anchorFraction = 0.0
        referenceRecord = 0
    }

    fun touch(active: Boolean) {
        touching = active
        if (active) invalidateObservation()
        if (!active) schedule()
    }

    private fun invalidateObservation() {
        if (session?.initialized != true) return
        work = null
        // Ordinary drag/fling is not a stalled renderer; explicit reflow still has a bounded deadline.
        if (session?.reposition == null) clearTimeout()
    }

    /** Called on actual native scroll/layout/draw events; never performs a per-pixel JS query. */
    fun observe() {
        if (session == null) return
        val next = viewport()
        if (next == observed) return
        observed = next
        lastMotion = SystemClock.uptimeMillis()
        invalidateObservation()
        schedule()
    }

    private fun viewport() = Viewport(view.width, view.viewportHeight, view.settings.textZoom,
        view.scrollRange, view.scrollY, view.nativeContentScale)

    private fun schedule() {
        if (session == null || view.isReleased || pending || work != null || touching || reflowPaused) return
        pending = true
        view.postOnAnimation(frame)
    }

    private fun active(current: Session, token: Any) = session === current && work === token && !view.isReleased && !reflowPaused

    private fun clearTimeout() {
        timeout?.let { view.removeCallbacks(it) }
        timeout = null
    }

    private fun armTimeout(current: Session) {
        if (timeout == null) timeout = Runnable { fail(current) }.also { view.postDelayed(it, 15_000L) }
    }

    private fun fail(current: Session) {
        if (session !== current) return
        stop()
        current.failed()
    }

    private fun retry(current: Session, token: Any) {
        if (!active(current, token)) return
        work = null
        // Keep the same deadline through font/layout retries; do not extend it every frame.
        schedule()
    }

    private fun fence(current: Session, token: Any, next: () -> Unit) {
        view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (active(current, token)) next()
            }
        })
        view.invalidate()
    }

    private fun read(current: Session, token: Any, next: (Layout) -> Unit) {
        if (!active(current, token)) return
        val before = viewport()
        if (before.width <= 0 || before.height <= 0 || !before.scale.isFinite() || before.scale <= 0) {
            retry(current, token)
            return
        }
        check(!queryInFlight)
        queryInFlight = true
        val reposition = current.reposition
        val anchor = if (current.initialized) reposition?.anchor else current.anchor
        val fraction = if (current.initialized) reposition?.fraction ?: 0.0 else current.fraction
        evaluate(measureScript(anchor, fraction)) receive@{ result ->
            queryInFlight = false
            if (!active(current, token)) { schedule(); return@receive }
            val json = try { JSONObject(result) } catch (_: Exception) { null }
            if (json == null || json.optBoolean("error")) { fail(current); return@receive }
            val native = viewport()
            if (!json.optBoolean("ready") || native != before || (current.initialized && touching)) {
                retry(current, token)
                return@receive
            }
            val width = json.optDouble("width")
            val height = json.optDouble("height")
            val extent = json.optDouble("extent")
            val y = json.optDouble("y")
            val target = json.optDouble("target", -1.0)
            val fraction = json.optDouble("fraction", 0.0)
            val reference = BookPagination.parseReferenceRecord(json.opt("referenceRecord"))
            if (!width.isFinite() || width <= 0 || !height.isFinite() || height <= 0 ||
                !extent.isFinite() || extent < 0 || !y.isFinite() || !target.isFinite() ||
                !fraction.isFinite() || fraction !in 0.0..1.0 || reference == null) {
                fail(current)
                return@receive
            }
            next(Layout(width, height, extent, y, target, json.optInt("record").coerceAtLeast(0), fraction, native, reference))
        }
    }

    private fun target(current: Session, layout: Layout): Int = when {
        !current.initialized && current.atEnd -> layout.native.range
        layout.target >= 0 -> (layout.target * layout.native.scale)
            .coerceIn(0.0, layout.native.range.toDouble()).roundToInt()
        else -> (current.reposition?.offset ?: current.offset).coerceIn(0, layout.native.range)
    }

    private fun measure(current: Session) {
        val token = Any()
        work = token
        read(current, token) { first ->
            fence(current, token) {
                read(current, token) { stable ->
                    // Font readiness and native geometry must agree across a compositor fence.
                    if (!first.sameGeometry(stable) || first.native.y != stable.native.y) retry(current, token)
                    else if (current.initialized && current.reposition == null) publish(current, token, stable)
                    else {
                        val offset = target(current, stable)
                        view.scrollTo(0, offset)
                        fence(current, token) {
                            read(current, token) { positioned ->
                                if (!stable.sameGeometry(positioned) || abs(target(current, positioned) - offset) > 1 ||
                                    abs(positioned.native.y - offset) > 1) retry(current, token)
                                else publish(current, token, positioned)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun publish(current: Session, token: Any, layout: Layout) {
        // Visual-viewport panning can leave window.scrollY at zero on Android WebView.
        if (abs(layout.y * layout.native.scale - layout.native.y) > 2 || view.scrollX != 0) {
            retry(current, token)
            return
        }
        fence(current, token) {
            if (viewport() != layout.native || touching) { retry(current, token); return@fence }
            // Initial seeking/reflow can itself scroll. Do not publish a checkpoint until the
            // same quiet/native-geometry gate used by synchronous capture is satisfied.
            if (SystemClock.uptimeMillis() - lastMotion < QUIET_MS) { retry(current, token); return@fence }
            val initial = !current.initialized
            val changed = (layout.record > 0 && (layout.record != anchorRecord || layout.fraction != anchorFraction)) ||
                layout.referenceRecord != referenceRecord || painted != layout.native
            current.initialized = true
            current.reposition = null
            if (layout.record > 0) {
                anchorRecord = layout.record
                anchorFraction = layout.fraction
            }
            referenceRecord = layout.referenceRecord
            painted = layout.native
            observed = layout.native
            work = null
            clearTimeout()
            if (session === current && !view.isReleased) view.onScrollBookLayoutSettled()
            if (changed) current.changed(anchorRecord)
            if (initial && session === current && !view.isReleased) current.ready()
        }
    }

    companion object {
        private const val QUIET_MS = 80L

        /** Only bounded native numbers are interpolated; source content never becomes JS. */
        private fun measureScript(anchor: Int?, fraction: Double) =
            "($MEASURE)(${anchor?.coerceAtLeast(0) ?: 0},${fraction.coerceIn(0.0, 1.0)})"

        private val MEASURE = """
            function(anchor, fraction) {
                var flow = document.querySelector('body.book-scrolling main.reader.book-flow');
                var root = document.scrollingElement;
                if (!flow || !root) return {error:true};
                var v = window.visualViewport;
                var width = v ? v.width : window.innerWidth, height = v ? v.height : window.innerHeight;
                var box = flow.getBoundingClientRect();
                if (!(width > 0 && height > 0 && box.height > 0)) return {ready:false};
                if (document.fonts && document.fonts.status !== 'loaded') return {ready:false};
                var y = v ? v.pageTop : window.scrollY;
                var offsetY = v ? v.offsetTop : 0, offsetX = v ? v.offsetLeft : 0;
                function id(e) {
                    var raw = e.getAttribute('data-record'), n = Number(raw);
                    return Number.isSafeInteger(n) && n > 0 && n <= 2147483647 && String(n) === raw ? n : 0;
                }
                var target = -1, visible = 0, within = 0, records = flow.querySelectorAll('.record[data-record]');
                // Source order is also vertical order. A concealed source heading resolves to
                // the following visible record, never an empty box at the document origin.
                var seeking = false;
                for (var i = 0; i < records.length; i++) {
                    var e = records[i], record = id(e);
                    if (!record) continue;
                    if (record === anchor && e.id === 'record-' + anchor) seeking = true;
                    var r = e.getBoundingClientRect();
                    if (!(r.width > 0 && r.height > 0)) continue;
                    // Leave a small visible tail, allowing for rounding to native pixel offsets.
                    if (seeking && target < 0) target = Math.max(0, r.top - offsetY + y +
                        Math.min(r.height * fraction, Math.max(0, r.height - 2)));
                    if (!visible && r.bottom - offsetY > .5 && r.top - offsetY < height - .5 &&
                        r.right - offsetX > .5 && r.left - offsetX < width - .5) {
                        visible = record;
                        within = Math.max(0, Math.min(1, (offsetY - r.top) / r.height));
                    }
                    if (visible && (!anchor || target >= 0)) break;
                }
                var reference = (${BookPagination.VISIBLE_REFERENCE})(flow, width, height, offsetX, offsetY, visible);
                if (document.fonts && document.fonts.status !== 'loaded') return {ready:false};
                return {ready:true, width:width, height:height, y:y, target:target, record:visible, fraction:within,
                    referenceRecord:reference,
                    extent:Math.max(root.scrollHeight, document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0, height)};
            }
        """.trimIndent()
    }
}