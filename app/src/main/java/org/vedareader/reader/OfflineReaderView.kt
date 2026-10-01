package org.vedareader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.vedareader.R
import org.vedareader.data.ContentObject
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightSpan
import org.vedareader.data.ReaderLink
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** A local document view, not a browser. It never launches URLs or exposes a JavaScript bridge. */
class OfflineReaderView(context: Context) : WebView(context) {
    private companion object {
        val OWN_ACTIONS = setOf(R.id.reader_highlight_action, R.id.reader_note_action)
    }

    var onLinkUnavailable: () -> Unit = {}
    /** A recognised local reader link; anything else still reports unavailable. */
    var onLinkActivated: (ReaderLink) -> Unit = { onLinkUnavailable() }
    var onScrollPosition: (Int) -> Unit = {}
    var onNextUnit: () -> Unit = {}
    var onPreviousUnit: () -> Unit = {}
    /** Fired once, only after this document's native visual-state fence has completed. */
    var onRendered: () -> Unit = {}
    var onRenderFailed: () -> Unit = {}
    /** Configure before render. Only a book document enables the closed native DOM queries. */
    var paginatedBook: Boolean = false
    /** Configure before render for a continuous book resource in VERSE; pagination takes precedence. */
    var scrollingBook: Boolean = false
    /** Source record to reveal on initial render; initialAtEnd takes precedence. */
    var initialBookAnchor: Int? = null
    var onBookPageChanged: (BookPageInfo) -> Unit = {}
    /** Settled source paragraph; also fires for within-paragraph scroll and reference-only changes. */
    var onReadingAnchor: (Int) -> Unit = {}
    val readingAnchor: Int get() = scrollBookLayout.anchorRecord
    /** Display-only visible verse heading, else the painted anchor; never used for restoration. */
    val readingReference: Int get() = bookPageState?.referenceRecord ?: scrollBookLayout.referenceRecord
    var onReaderTap: () -> Unit = {}
    /** The user chose Highlight or Note from the native selection menu; the selection is already dismissed. */
    var onHighlightSelected: (HighlightSelection, Boolean) -> Unit = { _, _ -> }
    /** A short tap landed on a painted user highlight; carries its id. */
    var onHighlightTapped: (String) -> Unit = {}
    private var requestedHighlights: List<HighlightSpan> = emptyList()
    /** Last visually fenced page; null before initial book layout, after failure, or in VERSE. */
    val bookPageInfo: BookPageInfo? get() = bookPageState
    /** Includes the post-CSS compositor/layout fence, not merely JavaScript acknowledgement. */
    var isReaderStylePending: Boolean = false
        private set
    /** For explicit save actions only: never return the previous painted record during motion. */
    val settledReadingRecord: Int? get() = when {
        isReleased || !interactionEnabled || !scrollReady || isReaderStylePending -> null
        bookDocument -> bookPageState?.takeIf { bookWork == null && bookRendered &&
            lastBookViewport == nativeBookViewport() && scrollX == it.nativeOffset && scrollY == 0 }
            ?.anchorRecord?.takeIf { it > 0 }
        scrollingBookDocument && scrollBookLayout.settled -> readingAnchor.takeIf { it > 0 }
        else -> null
    }

    /** Native scroll/range/viewport changes, coalesced outside drawing; no DOM inspection. */
    var onViewportChanged: () -> Unit = {}
    val viewportHeight: Int get() = computeVerticalScrollExtent().coerceAtLeast(0)
    val scrollRange: Int get() = if (bookDocument) 0 else (computeVerticalScrollRange() - viewportHeight).coerceAtLeast(0)
    // UI-thread snapshot, not a width ratio or a cached onScaleChanged value. WebView's
    // scale can lag compositor updates, so both layouts compare it across queries/fences
    // and observe subsequent native draws before accepting new geometry.
    @Suppress("DEPRECATION")
    internal val nativeContentScale: Double get() = scale.toDouble()
    var canNextUnit: Boolean = false
    var canPreviousUnit: Boolean = false
    var interactionEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                readingSwipe?.cancel()
                horizontalGesture = false
                bookTouch = null
                scrollTouch = null
                scrollBookLayout.touch(false)
                val now = android.os.SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                super.onTouchEvent(cancel)
                cancel.recycle()
                clearFocus()
            }
        }
    @Volatile var isReleased: Boolean = false
        private set
    private var readingSwipe: ReadingSwipe? = null
    private var horizontalGesture = false
    private var contentKey: String? = null
    private var contentAtEnd = false
    @Volatile private var documentGeneration = 0L
    private var scrollReady = false
    private var bookDocument = false
    private var scrollingBookDocument = false
    private val scrollBookLayout = ScrollBookLayout(this)
    private var contentBookAnchor: BookAnchor? = null
    private var bookLoaded = false
    private var bookRendered = false
    private var bookPageState: BookPageInfo? = null
    private var visibleBookAnchor = BookAnchor(0)
    // Layout-only changes must not repeatedly floor/rebase fragment fractions. The first
    // painted record may be an earlier paragraph sharing a column with this reading intent.
    private var reflowBookAnchor = BookAnchor(0)
    /** Logical paragraph retained across layout-only changes; not the bookmark/display anchor. */
    internal val reflowReadingRecord: Int get() = reflowBookAnchor.record
    private var bookWorkId = 0L
    private var bookWork: BookWork? = null
    private var bookAnimation: ObjectAnimator? = null
    private var bookRetry: Runnable? = null
    private var bookTimeout: Runnable? = null
    private var bookReflowPending = false
    private var bookQueryInFlight = false
    private var pausedBookDelta = 0
    private var pausedTurnAnchor: BookAnchor? = null
    private var bookFailure: () -> Unit = {}
    private var requestedReaderStyle: ReaderStyle? = null
    private var appliedReaderStyle: ReaderStyle? = null
    private var readerDocumentLoaded = false
    private var stylePaused = false
    // Retain the single-flight slot across document replacement until its stale callback returns.
    private var styleQuery: Any? = null
    private var styleTimeout: Runnable? = null
    private var styleFailure: () -> Unit = {}
    private var initialStyleLayout: (() -> Unit)? = null
    private val styleFrame = Runnable { applyRequestedReaderStyle() }
    private var lastBookViewport: BookViewport? = null
    private val waitingBookCompletions = mutableListOf<() -> Unit>()
    private val verseOverScrollMode = overScrollMode
    private val verseVerticalScrollBar = isVerticalScrollBarEnabled
    private val verseHorizontalScrollBar = isHorizontalScrollBarEnabled

    private data class BookViewport(
        val width: Int, val height: Int, val zoom: Int, val range: Int, val scale: Double,
    )
    private data class BookLayout(
        val width: Double, val height: Double, val extent: Double, val x: Double, val y: Double,
        val count: Int, val targetPage: Int, val anchor: BookAnchor, val native: BookViewport,
        val referenceRecord: Int, val pitch: Double,
    ) {
        fun sameGeometry(other: BookLayout) = abs(width - other.width) < .5 && abs(height - other.height) < .5 &&
            pitch == other.pitch && abs(extent - other.extent) < .5 && count == other.count && native == other.native
    }
    private class BookWork(
        val document: Long, val id: Long, val anchor: BookAnchor?, val page: Int, val atEnd: Boolean,
        val delta: Int, var animate: Boolean, val completions: List<() -> Unit>,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
    }
    private val bookReflow = Runnable {
        bookReflowPending = false
        if (!isReleased && bookDocument && bookLoaded) refreshBookLayout()
    }

    private class BookTouch(val document: Long, val x: Float, val y: Float, val time: Long, val selection: Boolean) {
        var moved = false
        var cancelled = false
        var ended = false
        var shortTap = false
        var downHit: Int? = null
        var tapRequested = false
    }
    private var bookTouch: BookTouch? = null
    private class ScrollTouch(
        val document: Long, val x: Float, val y: Float, val time: Long,
        val atTop: Boolean, val atBottom: Boolean, var eligible: Boolean,
        val offsetX: Int, val offsetY: Int,
    ) {
        var axis = 0 // 1 vertical, 2 horizontal (cancelled, never navigation).
        var downHit: Int? = null
        var upX = x
        var upY = y
        var shortTap = false
        var ended = false
        var tapRequested = false
        var moved = false
    }
    private var scrollTouch: ScrollTouch? = null
    private var selectionMode: ActionMode? = null
    private data class Viewport(val offset: Int, val range: Int, val extent: Int)
    private var lastViewport: Viewport? = null
    private data class LegacyViewport(val viewport: Viewport, val width: Int, val zoom: Int, val scale: Double)
    private var legacyObservedViewport: LegacyViewport? = null
    private var legacyPaintedViewport: LegacyViewport? = null
    private fun legacyViewport() = LegacyViewport(Viewport(scrollY, scrollRange, viewportHeight),
        width, settings.textZoom, nativeContentScale)
    private val legacyCheckpointFence = Runnable {
        val observed = legacyObservedViewport
        val request = documentGeneration
        if (!isReleased && scrollReady && !bookDocument && !scrollingBookDocument && observed == legacyViewport()) {
            postVisualStateCallback(request, object : VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!isReleased && request == documentGeneration && observed == legacyViewport() && scrollReady) {
                        legacyPaintedViewport = observed
                        onViewportChanged()
                    }
                }
            })
            invalidate()
        }
    }
    private var viewportPending = false
    private var scrollPositionPending = false
    private val scrollPositionNotification = Runnable {
        scrollPositionPending = false
        if (!isReleased && scrollReady && interactionEnabled && !isReaderStylePending) {
            onScrollPosition(scrollY.coerceAtLeast(0))
        }
    }
    private val viewportNotification = Runnable {
        viewportPending = false
        if (!isReleased && scrollReady && !isReaderStylePending && (!bookDocument || bookWork == null)) {
            val viewport = Viewport(scrollY.coerceIn(0, scrollRange), scrollRange, viewportHeight)
            if (viewport != lastViewport) {
                lastViewport = viewport
                onViewportChanged()
            }
        }
    }

    init {
        setBackgroundColor(Color.parseColor(ReaderPaperAppearance.Paper))
        settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = false
            @Suppress("DEPRECATION") // Explicitly disable Web SQL on older installed WebViews too.
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
        }
        setDownloadListener { _, _, _, _, _ -> if (!isReleased && interactionEnabled) onLinkUnavailable() }
    }

    // Keep the four-argument overload: existing callers use a trailing image-loader lambda.
    fun render(key: String, html: String, initialScroll: Int, image: (String) -> ContentObject?) =
        render(key, html, initialScroll, image, initialAtEnd = false)

    private fun activateLink(url: Uri?) {
        val link = ReaderLinks.parse(url)
        if (link == null) onLinkUnavailable() else onLinkActivated(link)
    }

    fun render(key: String, html: String, initialScroll: Int, image: (String) -> ContentObject?, initialAtEnd: Boolean = false) {
        val book = paginatedBook
        val scrolling = scrollingBook && !book
        val anchor = if (book || scrolling) initialBookAnchor?.takeIf { it > 0 } else null
        val bookAnchor = anchor?.let(::BookAnchor)
        if (isReleased || (contentKey == key && contentAtEnd == initialAtEnd && bookDocument == book &&
            scrollingBookDocument == scrolling && contentBookAnchor == bookAnchor)) return
        cancelBookWork()
        scrollBookLayout.stop()
        waitingBookCompletions.clear()
        bookTouch = null
        scrollTouch = null
        selectionMode?.finish()
        selectionMode = null
        contentKey = key
        contentAtEnd = initialAtEnd
        val request = ++documentGeneration
        resetReaderStyleWork()
        bookDocument = book
        scrollingBookDocument = scrolling
        contentBookAnchor = bookAnchor
        bookLoaded = false
        bookRendered = false
        bookPageState = null
        visibleBookAnchor = BookAnchor(0)
        reflowBookAnchor = BookAnchor(0)
        lastBookViewport = null
        bookFailure = {}
        // CSP also denies scripts in BOOK. JS is enabled solely for our evaluateJavascript
        // queries, which run from native code, never from source paragraphs or a JS bridge.
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = book || scrolling
        overScrollMode = if (book) OVER_SCROLL_NEVER else verseOverScrollMode
        isVerticalScrollBarEnabled = !book && verseVerticalScrollBar
        isHorizontalScrollBarEnabled = !book && !scrolling && verseHorizontalScrollBar
        val documentUrl = "https://reader.local/document/$request"
        val initialStyle = requestedReaderStyle
        val styledHtml = if (initialStyle == null) html else initialStyle.documentHtml(html, book || scrolling)
        appliedReaderStyle = initialStyle
        val documentBytes = styledHtml?.toByteArray(Charsets.UTF_8)
        val documentResources = context.applicationContext.resources
        scrollReady = false
        lastViewport = null
        legacyObservedViewport = null
        legacyPaintedViewport = null
        removeCallbacks(legacyCheckpointFence)
        webViewClient = object : WebViewClient() {
            private var visualRequested = false
            private var failed = false

            private fun fail() {
                if (request != documentGeneration || failed || isReleased) return
                failed = true
                scrollReady = false
                bookLoaded = false
                bookPageState = null
                cancelBookWork()
                scrollBookLayout.stop()
                resetReaderStyleWork()
                scrollTouch = null
                waitingBookCompletions.clear()
                onRenderFailed()
            }

            override fun onReceivedError(view: WebView, resource: WebResourceRequest, error: WebResourceError) {
                if (resource.isForMainFrame && resource.url.toString() == documentUrl) fail()
            }

            override fun onReceivedHttpError(view: WebView, resource: WebResourceRequest, response: WebResourceResponse) {
                if (resource.isForMainFrame && resource.url.toString() == documentUrl && response.statusCode >= 400) fail()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail()
                return true // The host removes this unusable view and offers a fresh, local render.
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!isReleased && requestGeneration == documentGeneration && interactionEnabled) activateLink(request.url)
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (!isReleased && request == documentGeneration && interactionEnabled) activateLink(Uri.parse(url))
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (this@OfflineReaderView.isReleased || this@OfflineReaderView.documentGeneration != requestGeneration) {
                    return ReaderResources.denied()
                }
                // Permit only this app-generated main document, served from memory (not the network).
                if (request.isForMainFrame && request.method == "GET" && request.url.toString() == documentUrl) {
                    if (documentBytes == null) return ReaderResources.denied()
                    val headers = mutableMapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
                    if (book || scrolling) headers["Content-Security-Policy"] = BookPagination.CSP
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        headers, ByteArrayInputStream(documentBytes))
                }
                if (request.url.encodedPath.orEmpty().startsWith("/fonts/")) {
                    return ReaderFonts.response(request.url, request.method, request.isForMainFrame, documentResources)
                }
                return ReaderResources.response(request.url, request.method, request.isForMainFrame, image)
            }

            private val requestGeneration = request

            init { styleFailure = { fail() } }

            override fun onPageFinished(view: WebView, url: String) {
                if (request != documentGeneration || url != documentUrl || visualRequested || failed) return
                visualRequested = true
                readerDocumentLoaded = true
                // Paint before the first layout query: inline marks never move text, but the
                // renderer runs these closed scripts in order, so measurement sees the final DOM.
                if ((book || scrolling) && requestedHighlights.isNotEmpty()) applyHighlights()
                initialStyleLayout = initial@{
                    if (request != documentGeneration || isReleased || failed) return@initial
                    if (book) {
                        bookLoaded = true
                        bookFailure = { fail() }
                        refreshBookLayout()
                    } else if (scrolling) {
                        scrollBookLayout.start(anchor, initialScroll, initialAtEnd, ready = {
                            if (request == documentGeneration && !isReleased && !failed) {
                                scrollReady = true
                                finishReaderStyleLayout()
                                onRendered()
                                scheduleViewportNotification()
                            }
                        }, changed = { record ->
                            if (request == documentGeneration && !isReleased && !failed) onReadingAnchor(record)
                        }, failed = { fail() })
                    } else {
                        postVisualStateCallback(request, object : VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (requestId == documentGeneration && !isReleased && !failed) positionInitialViewport()
                            }
                        })
                    }
                }
                if (requestedReaderStyle == appliedReaderStyle) resumeReaderStyleLayout()
                else { pauseReaderStyleLayout(); scheduleReaderStyle() }
            }

            private fun positionInitialViewport() {
                if (request != documentGeneration || isReleased || failed) return
                if (width == 0 || viewportHeight == 0) {
                    postOnAnimation { positionInitialViewport() }
                    return
                }
                val range = scrollRange
                val extent = viewportHeight
                val target = if (initialAtEnd) range else initialScroll.coerceIn(0, range)
                scrollTo(0, target)
                // A content fence alone is insufficient: the restored/bottom offset must also paint
                // before a transparent incoming view can be exposed by the host.
                postVisualStateCallback(request, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (requestId != documentGeneration || isReleased || failed) return
                        if (range != scrollRange || extent != viewportHeight || scrollY != target) {
                            positionInitialViewport()
                            return
                        }
                        scrollReady = true
                        onRendered()
                        scheduleViewportNotification()
                    }
                })
                invalidate()
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                if (book && request == documentGeneration) scheduleBookReflow()
                if (scrolling && request == documentGeneration) {
                    scrollTouch?.moved = true
                    scrollBookLayout.observe()
                }
                scheduleViewportNotification()
            }
        }
        loadUrl(documentUrl)
    }

    /** UI thread. Store any time; the painted document is updated in place (no reload, no reflow). */
    fun setHighlights(spans: List<HighlightSpan>) {
        if (isReleased || requestedHighlights == spans) return
        requestedHighlights = spans
        if (readerDocumentLoaded) applyHighlights()
    }

    private fun applyHighlights() {
        if (isReleased || !readerDocumentLoaded || !settings.javaScriptEnabled) return
        try {
            evaluateJavascript(ReaderHighlights.apply(requestedHighlights)) { }
        } catch (_: Exception) {
            // A torn-down renderer cannot paint; the next render re-applies the stored spans.
        }
    }

    private fun requestSelectionHighlight(withNote: Boolean, onRead: () -> Unit) {
        if (isReleased || !settings.javaScriptEnabled) { onRead(); return }
        val request = documentGeneration
        try {
            evaluateJavascript(ReaderHighlights.SELECTION) { result ->
                onRead()
                if (isReleased || request != documentGeneration || !interactionEnabled) return@evaluateJavascript
                val selection = ReaderHighlights.parseSelection(result) ?: return@evaluateJavascript
                onHighlightSelected(selection, withNote)
            }
        } catch (_: Exception) {
            onRead()
        }
    }

    private fun reportHighlightAt(x: Int, y: Int, valid: () -> Boolean) {
        evaluateJavascript(ReaderHighlights.at(x, y, width, height)) { result ->
            if (!valid() || !interactionEnabled || selectionMode != null) return@evaluateJavascript
            ReaderHighlights.parseId(result)?.let(onHighlightTapped)
        }
    }

    /** UI thread. Store before render/configuration, or restyle the same live book/scroll resource.
     * Source HTML/URLs, callbacks, source images/fonts and CSP are never replaced at runtime.
     * Legacy JS-off documents support pre-load styling only; a live mismatch fails explicitly.
     */
    fun setReaderStyle(style: ReaderStyle) {
        if (isReleased) return
        val next = style.normalized()
        if (requestedReaderStyle == next) return
        requestedReaderStyle = next
        setBackgroundColor(Color.parseColor(next.palette.Paper))
        if (contentKey == null) return
        if (!bookDocument && !scrollingBookDocument) { styleFailure(); return }
        pauseReaderStyleLayout()
        if (readerDocumentLoaded) scheduleReaderStyle()
    }

    private fun pauseReaderStyleLayout() {
        isReaderStylePending = true
        stylePaused = true
        // Invalidate old measurements BEFORE even queueing the DOM mutation. Capture from painted
        // state, never from an intermediate style/zoom in a burst; retain any accepted turn intent.
        if (bookDocument) {
            bookWork?.let {
                waitingBookCompletions.addAll(it.completions)
                pausedBookDelta = it.delta
                pausedTurnAnchor = it.anchor.takeIf { _ -> it.delta != 0 }
            }
            cancelBookWork()
        }
        if (scrollingBookDocument) scrollBookLayout.pauseReflow()
        readingSwipe?.cancel()
        horizontalGesture = false
        bookTouch = null
        scrollTouch = null
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(cancel)
        cancel.recycle()
        scrollBookLayout.touch(false)
        if (styleTimeout == null) {
            val request = documentGeneration
            styleTimeout = Runnable {
                if (!isReleased && request == documentGeneration && isReaderStylePending) styleFailure()
            }.also { postDelayed(it, 15_000L) }
        }
    }

    private fun scheduleReaderStyle() {
        removeCallbacks(styleFrame)
        if (!isReleased && readerDocumentLoaded) postOnAnimation(styleFrame)
    }

    private fun applyRequestedReaderStyle() {
        if (isReleased || !readerDocumentLoaded || styleQuery != null) return
        if (requestedReaderStyle == appliedReaderStyle) { resumeReaderStyleLayout(); return }
        if ((!bookDocument && !scrollingBookDocument) || !settings.javaScriptEnabled) {
            styleFailure() // Never silently leave native paper and actual text in different themes.
            return
        }
        val style = requestedReaderStyle ?: return
        val request = documentGeneration
        val token = Any()
        styleQuery = token
        val script = """
            (function(css) {
                var e = document.getElementById('${ReaderStyle.STYLE_ID}');
                if (!e || e.tagName !== 'STYLE' || e.parentNode !== document.head ||
                    document.querySelectorAll('style#${ReaderStyle.STYLE_ID}').length !== 1 ||
                    !document.querySelector('body.book-reading main.reader.book-flow')) return false;
                e.textContent = css;
                return e.textContent === css;
            })(${JSONObject.quote(style.css())})
        """.trimIndent()
        try {
            evaluateJavascript(script) receive@{ result ->
                if (styleQuery !== token) return@receive
                styleQuery = null
                if (isReleased) return@receive
                if (request != documentGeneration) { scheduleReaderStyle(); return@receive }
                if (!readerDocumentLoaded) return@receive
                if (result != "true") { styleFailure(); return@receive }
                appliedReaderStyle = style
                if (requestedReaderStyle != style) scheduleReaderStyle()
                else resumeReaderStyleLayout()
            }
        } catch (_: Exception) {
            if (styleQuery === token) styleQuery = null
            if (!isReleased && request == documentGeneration) styleFailure()
        }
    }

    private fun resumeReaderStyleLayout() {
        removeCallbacks(styleFrame)
        stylePaused = false
        val initial = initialStyleLayout
        initialStyleLayout = null
        if (initial != null) initial()
        else if (bookDocument && bookLoaded) refreshBookLayout()
        else if (scrollingBookDocument) scrollBookLayout.resumeReflow()
    }

    private fun finishReaderStyleLayout() {
        if (stylePaused || !isReaderStylePending) return
        isReaderStylePending = false
        styleTimeout?.let { removeCallbacks(it) }
        styleTimeout = null
        lastViewport = null // Even a color-only reflow must notify/unlock the host once fenced.
        invalidate() // Remove any native initial-paper cover as soon as the latest layout is ready.
    }

    internal fun onScrollBookLayoutSettled() {
        if (isReleased || !scrollingBookDocument || !scrollBookLayout.settled) return
        finishReaderStyleLayout()
        scheduleViewportNotification()
    }

    private fun resetReaderStyleWork() {
        readerDocumentLoaded = false
        initialStyleLayout = null
        stylePaused = false
        isReaderStylePending = false
        pausedBookDelta = 0
        pausedTurnAnchor = null
        removeCallbacks(styleFrame)
        styleTimeout?.let { removeCallbacks(it) }
        styleTimeout = null
        styleFailure = {}
    }

    /**
     * Apply a native text-zoom percentage to this document, without regenerating HTML or reloading.
     * Call on the UI thread with the already system-scaled percentage (base HTML stays 20px).
     * Scrolling resources snapshot their last painted paragraph/fraction BEFORE changing settings;
     * columns use the existing fragment-aware reflow once. Initial rendering keeps its own intent.
     * Equal values and released views are no-ops. Direct WebSettings writes do not opt into scroll
     * anchor preservation; ordinary viewport-height changes continue retaining their pixel offset.
     */
    fun setReaderTextZoom(zoom: Int) {
        if (isReleased || settings.textZoom == zoom) return
        if (scrollingBookDocument) scrollBookLayout.reflow()
        settings.textZoom = zoom
        if (bookDocument) refreshBookLayout()
    }

    /**
     * Accept one intra-document turn. The host may lock interaction BEFORE calling this method.
        * Availability is based on the last painted layout. False means a boundary/not-ready/released
        * view; onComplete is not called in that case. Overlapping requests from the same painted
        * page coalesce instead of skipping pages. Each accepted request completes once, after the
        * exact column has painted. Replacement/release/failure invalidates its callback instead of
        * unlocking a different document. No chapter copy, second WebView, or bitmap is created here.
     */
    fun turnBookPage(forward: Boolean, onComplete: () -> Unit): Boolean {
        val page = bookPageState ?: return false
        if (isReleased || !bookDocument || !bookLoaded || !scrollReady || isReaderStylePending) return false
        val delta = if (forward) 1 else -1
        val target = page.page + delta
        if (target !in 0 until page.count) return false
        // Resolve the old visible record in the *new* geometry before advancing. Native size or
        // font changes can have started an automatic reflow before the host locks this request.
        val completions = bookWork?.completions.orEmpty() + onComplete
        startBookWork(visibleBookAnchor.takeIf { it.record > 0 }, page.page, atEnd = false,
            animate = bookWork == null, completions = completions, delta = delta)
        return true
    }

    /**
     * Reflow to the last painted source record, retaining its approximate paragraph fragment.
     * Also called automatically after resize/textZoom and native disclosure taps. Overlapping
     * refreshes coalesce but each completion survives exactly once in the same live document.
     * Before page load they wait for initial layout; in VERSE this is a synchronous no-op.
     */
    fun refreshBookLayout(onComplete: () -> Unit = {}) {
        if (isReleased) return
        if (!bookDocument) {
            onComplete()
            return
        }
        val completions = bookWork?.completions.orEmpty() + waitingBookCompletions + onComplete
        waitingBookCompletions.clear()
        if (!bookLoaded || stylePaused) {
            waitingBookCompletions.addAll(completions)
            return
        }
        // Keep an accepted turn's relative intent through coalesced refreshes. Its anchor, not
        // its stale absolute page index, is resolved again after size/zoom/disclosure changes.
        val delta = bookWork?.delta ?: pausedBookDelta
        val turnAnchor = bookWork?.takeIf { it.delta != 0 }?.anchor ?: pausedTurnAnchor
        val anchor = if (delta != 0 && turnAnchor != null) turnAnchor
            else if (!bookRendered) contentBookAnchor
            else reflowBookAnchor.takeIf { it.record > 0 } ?: visibleBookAnchor.takeIf { it.record > 0 }
        pausedBookDelta = 0
        pausedTurnAnchor = null
        startBookWork(anchor, bookPageState?.page ?: 0, !bookRendered && contentAtEnd, false, completions, delta)
    }

    private fun startBookWork(anchor: BookAnchor?, page: Int, atEnd: Boolean, animate: Boolean,
        completions: List<() -> Unit>, delta: Int = 0) {
        cancelBookWork()
        readingSwipe?.cancel()
        horizontalGesture = false
        bookTouch?.takeIf { !it.ended && !it.cancelled }?.let { touch ->
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(touch.time, now, MotionEvent.ACTION_CANCEL, touch.x, touch.y, 0)
            super.onTouchEvent(cancel)
            cancel.recycle()
            touch.cancelled = true
        }
        // A finished tap is kept: its chrome toggle completes after this reflow settles (see publishBook).
        val work = BookWork(documentGeneration, ++bookWorkId, anchor, page, atEnd, delta, animate, completions)
        bookWork = work
        bookTimeout = Runnable { if (currentBookWork(work)) bookFailure() }.also { postDelayed(it, 15_000L) }
        measureBook(work)
    }

    private fun currentBookWork(work: BookWork) = !isReleased && bookDocument && bookLoaded && !stylePaused &&
        work.document == documentGeneration && bookWork === work

    private fun activeBookWork(work: BookWork): Boolean {
        if (!currentBookWork(work)) return false
        if (SystemClock.uptimeMillis() >= work.deadline) {
            bookFailure()
            return false
        }
        return true
    }

    private fun nativeBookViewport() = BookViewport(width, height, settings.textZoom,
        computeHorizontalScrollRange(), nativeContentScale)

    private fun retryBook(work: BookWork) {
        if (!activeBookWork(work)) return
        bookRetry?.let { removeCallbacks(it) }
        bookRetry = Runnable {
            bookRetry = null
            if (activeBookWork(work)) measureBook(work)
        }.also { postOnAnimation(it) }
    }

    private fun readBookLayout(work: BookWork, receive: (BookLayout) -> Unit) {
        if (!activeBookWork(work)) return
        if (bookQueryInFlight) { retryBook(work); return }
        val before = nativeBookViewport()
        if (before.width <= 0 || before.height <= 0 || !before.scale.isFinite() || before.scale <= 0) {
            retryBook(work)
            return
        }
        bookQueryInFlight = true
        evaluateJavascript(BookPagination.measure(work.anchor)) { result ->
            bookQueryInFlight = false
            if (!activeBookWork(work)) return@evaluateJavascript
            val json = try { JSONObject(result) } catch (_: Exception) { null }
            if (json == null || json.optBoolean("error")) {
                bookFailure()
                return@evaluateJavascript
            }
            val native = nativeBookViewport()
            if (!json.optBoolean("ready") || before != native) {
                retryBook(work)
                return@evaluateJavascript
            }
            val cssWidth = json.optDouble("width")
            val cssHeight = json.optDouble("height")
            val pitch = json.optDouble("pitch")
            val extent = json.optDouble("extent")
            val x = json.optDouble("x")
            val y = json.optDouble("y")
            val reference = BookPagination.parseReferenceRecord(json.opt("referenceRecord"))
            if (!cssWidth.isFinite() || cssWidth <= 0 || !cssHeight.isFinite() || cssHeight <= 0 ||
                !pitch.isFinite() || pitch <= 0 || !extent.isFinite() || extent < 0 ||
                !x.isFinite() || !y.isFinite() || reference == null) {
                bookFailure()
                return@evaluateJavascript
            }
            val count = BookPagination.pageCount(extent, pitch)
            val nativePitch = pitch * native.scale
            if (!nativePitch.isFinite() || nativePitch < 1 || nativePitch > Int.MAX_VALUE.toDouble() ||
                (count - 1).toDouble() * pitch * native.scale > Int.MAX_VALUE.toDouble()) {
                bookFailure() // An impossible native scroll range must not overflow into another page.
                return@evaluateJavascript
            }
            receive(BookLayout(cssWidth, cssHeight, extent, x, y, count, json.optInt("targetPage", -1),
                BookAnchor(json.optInt("record").coerceAtLeast(0), json.optInt("fragment").coerceAtLeast(0),
                    json.optInt("fragments", 1).coerceAtLeast(1)), native, reference, pitch))
        }
    }

    private fun fenceBook(work: BookWork, ready: () -> Unit) {
        if (!activeBookWork(work)) return
        postVisualStateCallback(work.id, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (requestId == work.id && activeBookWork(work)) ready()
            }
        })
        invalidate()
    }

    private fun measureBook(work: BookWork) {
        readBookLayout(work) { first ->
            // document.fonts.status must be loaded on BOTH sides of a compositor fence.
            // A count calculated before font substitution is not a usable book layout.
            fenceBook(work) {
                readBookLayout(work) { stable ->
                    if (!first.sameGeometry(stable)) retryBook(work) else positionBook(work, stable)
                }
            }
        }
    }

    private fun targetPage(work: BookWork, layout: BookLayout): Int = (when {
        work.atEnd -> layout.count - 1
        work.anchor != null && layout.targetPage >= 0 -> layout.targetPage
        else -> work.page
    } + work.delta).coerceIn(0, layout.count - 1)

    private fun positionBook(work: BookWork, layout: BookLayout) {
        if (!activeBookWork(work)) return
        val page = targetPage(work, layout)
        val target = BookPagination.offset(page, layout.pitch, layout.native.scale)
        fun finish() {
            if (!activeBookWork(work)) return
            bookAnimation = null
            scrollTo(target, 0)
            fenceBook(work) {
                readBookLayout(work) { painted ->
                    if (!layout.sameGeometry(painted) || targetPage(work, painted) != page ||
                        abs(scrollX - target) > 1 || scrollY != 0 || abs(painted.y) > .5 ||
                        abs(painted.x - page * painted.pitch) > 1.0 ||
                        abs(painted.x * painted.native.scale - scrollX) > 2) {
                        retryBook(work)
                    } else {
                        // The measurement itself is read-only. Fence once more before exposing
                        // the offset/anchor or unlocking a native host transition.
                        fenceBook(work) {
                            if (painted.native != nativeBookViewport() || abs(scrollX - target) > 1 || scrollY != 0) retryBook(work)
                            else publishBook(work, painted, page)
                        }
                    }
                }
            }
        }
        val animate = work.animate && scrollX != target && ValueAnimator.areAnimatorsEnabled()
        work.animate = false // A late image/font/resize retry must not animate a second time.
        if (!animate) {
            finish()
            return
        }
        bookAnimation = ObjectAnimator.ofInt(this, "scrollX", scrollX, target).apply {
            duration = ReaderMotion.pageTurn
            interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            })
            start()
        }
    }

    private fun publishBook(work: BookWork, layout: BookLayout, page: Int) {
        if (!activeBookWork(work)) return
        val initial = !bookRendered
        val info = BookPageInfo(page, layout.count, layout.anchor.record,
            BookPagination.offset(1, layout.pitch, layout.native.scale), layout.referenceRecord,
            BookPagination.offset(page, layout.pitch, layout.native.scale))
        val changed = bookPageState != info || visibleBookAnchor != layout.anchor || lastBookViewport != layout.native
        cancelBookWork() // Invalidate before observers can synchronously render/release/turn again.
        bookPageState = info
        visibleBookAnchor = layout.anchor
        if (initial || work.delta != 0 || reflowBookAnchor.record <= 0) {
            reflowBookAnchor = if (initial && !work.atEnd && work.anchor != null) work.anchor else layout.anchor
        }
        lastBookViewport = nativeBookViewport()
        bookRendered = true
        scrollReady = true
        finishReaderStyleLayout()
        fun sameDocument() = !isReleased && bookDocument && work.document == documentGeneration
        if (changed) onBookPageChanged(info)
        if (initial && sameDocument()) onRendered()
        for (complete in work.completions) {
            if (!sameDocument()) break
            complete()
        }
        if (sameDocument() && bookWork == null) bookTouch?.let { completeBookTap(it) }
        if (sameDocument()) scheduleViewportNotification()
    }

    private fun scheduleBookReflow() {
        if (isReleased || !bookDocument || !bookLoaded || bookReflowPending || stylePaused) return
        bookReflowPending = true
        post(bookReflow)
    }

    private fun cancelBookWork() {
        bookWork = null
        bookAnimation?.removeAllUpdateListeners()
        bookAnimation?.removeAllListeners()
        bookAnimation?.cancel()
        bookAnimation = null
        bookRetry?.let { removeCallbacks(it) }
        bookRetry = null
        bookTimeout?.let { removeCallbacks(it) }
        bookTimeout = null
        removeCallbacks(bookReflow)
        bookReflowPending = false
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(if (scrollingBookDocument) 0 else x, if (bookDocument) 0 else y)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        // Remember even a transient offset change that returns to DOWN before the hit callback.
        // Do not revoke boundary eligibility: reaching an edge is still handled by the old rules.
        if (left != oldLeft || top != oldTop) scrollTouch?.moved = true
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (bookDocument) {
            // Native focus/selection/accessibility must not leave the reader between columns or
            // vertically scrolled. Only our bounded turn/reflow is allowed to move the viewport.
            val pinned = bookPageState?.takeIf { bookWork == null }?.nativeOffset
            if (top != 0 || (pinned != null && pinned != left)) super.scrollTo(pinned ?: left, 0)
            return
        }
        if (scrollingBookDocument) {
            if (left != 0) super.scrollTo(0, top)
            scrollBookLayout.observe()
        }
        if (scrollReady && interactionEnabled && !isReaderStylePending && !scrollPositionPending) {
            scrollPositionPending = true
            postOnAnimation(scrollPositionNotification)
        }
        scheduleViewportNotification()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            scrollTouch?.moved = true
            scheduleBookReflow()
        }
        if (scrollingBookDocument) scrollBookLayout.observe()
        scheduleViewportNotification()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isReaderStylePending && !scrollReady) {
            canvas.drawColor(Color.parseColor((requestedReaderStyle ?: ReaderStyle()).palette.Paper))
            // Keep native drawing/fences active, but conceal an obsolete initial theme.
        }
        // Native <details>, images and text-zoom reflow can change the range without a View layout
        // or scroll callback. Inspect only on actual draws, and never request another draw here.
        if (bookDocument && bookLoaded && bookWork == null && nativeBookViewport() != lastBookViewport) scheduleBookReflow()
        if (scrollingBookDocument) scrollBookLayout.observe()
        scheduleViewportNotification()
    }

    private fun scheduleViewportNotification() {
        if (!isReleased && scrollReady && !bookDocument && !scrollingBookDocument) {
            val next = legacyViewport()
            if (next != legacyObservedViewport) {
                legacyObservedViewport = next
                legacyPaintedViewport = null
                removeCallbacks(legacyCheckpointFence)
                postDelayed(legacyCheckpointFence, 80L)
            }
        }
        if (isReleased || !scrollReady || isReaderStylePending || viewportPending || (bookDocument && bookWork != null)) return
        viewportPending = true
        post(viewportNotification)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isReleased || !interactionEnabled || isReaderStylePending) return false
        if (bookDocument) {
            if (bookWork != null) return false
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> {
                    if (!canNextUnit) return false
                    onNextUnit()
                    return true
                }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> {
                    if (!canPreviousUnit) return false
                    onPreviousUnit()
                    return true
                }
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> return false
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        if (bookDocument && event.actionMasked == MotionEvent.ACTION_SCROLL) true else super.onGenericMotionEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled || isReleased || isReaderStylePending) return true
        if (bookDocument) return onBookTouchEvent(event)
        if (scrollingBookDocument) return onScrollingBookTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                horizontalGesture = false
                readingSwipe = ReadingSwipe(ViewConfiguration.get(context).scaledTouchSlop * 2f,
                    maxOf(width * .18f, 64f * resources.displayMetrics.density), ViewConfiguration.getLongPressTimeout().toLong())
                readingSwipe?.start(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                readingSwipe?.cancel()
                if (horizontalGesture) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) readingSwipe?.cancel()
                if (event.pointerCount == 1 && readingSwipe?.move(event.x, event.y, event.eventTime) == true) {
                    if (!horizontalGesture) {
                        horizontalGesture = true
                        // Cancel the native press so a navigation swipe cannot activate a link.
                        val cancelled = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancelled)
                        cancelled.recycle()
                    }
                    return true
                }
                if (horizontalGesture) return true
            }
            MotionEvent.ACTION_UP -> {
                val handled = horizontalGesture
                val direction = readingSwipe?.finish(event.x, canPreviousUnit, canNextUnit) ?: 0
                horizontalGesture = false
                if (handled) {
                    if (direction > 0) onNextUnit() else if (direction < 0) onPreviousUnit()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                readingSwipe?.cancel()
                horizontalGesture = false
            }
        }
        return super.onTouchEvent(event)
    }

    /** Only a fresh, settled boundary DOWN can leave a scrolling resource; flings never do. */
    private fun onScrollingBookTouchEvent(event: MotionEvent): Boolean {
        if (!scrollReady) return true
        val slop = ViewConfiguration.get(context).scaledTouchSlop * 2f
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            scrollTouch?.let { trackScrollTapMotion(it, event, slop / 2) }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touch = ScrollTouch(documentGeneration, event.x, event.y, event.eventTime,
                    scrollY <= 1, scrollY >= scrollRange - 1, selectionMode == null && event.pointerCount == 1,
                    scrollX, scrollY)
                scrollTouch = touch
                trackScrollTapMotion(touch, event, slop / 2)
                // Query before native DOWN can clear a selection. Native events still reach
                // WebView; these are closed read-only queries, never source JS or fake clicks.
                evaluateJavascript(BookPagination.hitTest(event.x.roundToInt(), event.y.roundToInt(), width, height)) { result ->
                    if (sameScrollTouch(touch)) {
                        touch.downHit = result.toIntOrNull() ?: BookPagination.HIT_OUTSIDE
                        if ((touch.downHit!! and BookPagination.HIT_SELECTION) != 0) touch.eligible = false
                        completeScrollTap(touch)
                    }
                }
                scrollBookLayout.touch(true)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> scrollTouch?.eligible = false
            MotionEvent.ACTION_MOVE -> {
                val touch = scrollTouch ?: return true
                val dx = abs(event.x - touch.x)
                val dy = abs(event.y - touch.y)
                if (event.pointerCount != 1 || selectionMode != null || (touch.axis == 0 &&
                        event.eventTime - touch.time >= ViewConfiguration.getLongPressTimeout())) touch.eligible = false
                if (selectionMode == null && touch.axis == 0 && maxOf(dx, dy) > slop) {
                    if (dx > dy * 1.25f) {
                        touch.axis = 2
                        touch.eligible = false
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    } else if (dy > dx * 1.25f) touch.axis = 1
                }
                if (touch.axis == 2) return true
            }
            MotionEvent.ACTION_UP -> {
                val touch = scrollTouch
                scrollBookLayout.touch(false)
                if (touch == null) return true
                touch.ended = true
                touch.upX = event.x
                touch.upY = event.y
                if (touch.axis == 2) return true
                val dy = event.y - touch.y
                val threshold = maxOf(height * .18f, 64f * resources.displayMetrics.density)
                val deliberate = touch.document == documentGeneration && touch.eligible && selectionMode == null &&
                    touch.axis == 1 && abs(dy) > threshold && abs(dy) > abs(event.x - touch.x) * 1.25f
                val nextBoundary = deliberate && dy < 0 && touch.atBottom && scrollY >= scrollRange - 1
                val previousBoundary = deliberate && dy > 0 && touch.atTop && scrollY <= 1
                if (nextBoundary || previousBoundary) {
                    scrollTouch = null
                    // Cancel the native UP/fling before the host can lock or replace this view.
                    // At the first/last book edge consume it too: an impossible outward fling
                    // must not keep moving after the reader changes direction.
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    if (nextBoundary && canNextUnit) onNextUnit()
                    else if (previousBoundary && canPreviousUnit) onPreviousUnit()
                    return true
                }
                touch.shortTap = touch.eligible && !touch.moved && touch.axis == 0 && selectionMode == null &&
                    event.eventTime - touch.time in 0 until ViewConfiguration.getLongPressTimeout().toLong()
                val handled = super.onTouchEvent(event)
                completeScrollTap(touch)
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                scrollTouch = null
                scrollBookLayout.touch(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun trackScrollTapMotion(touch: ScrollTouch, event: MotionEvent, slop: Float) {
        fun moved(x: Float, y: Float) = abs(x - touch.x) >= slop || abs(y - touch.y) >= slop ||
            x < 0 || y < 0 || x >= width || y >= height
        // Android can batch an excursion and reversal into one MOVE. The final coordinates
        // alone do not prove a stationary tap, even when no scroll/axis lock ever occurred.
        if (event.pointerCount != 1 || moved(event.x, event.y) ||
            (0 until event.historySize).any { moved(event.getHistoricalX(it), event.getHistoricalY(it)) }) {
            touch.moved = true
        }
        if (selectionMode != null) touch.eligible = false
    }

    private fun sameScrollTouch(touch: ScrollTouch) = !isReleased && scrollingBookDocument &&
        scrollReady && interactionEnabled && documentGeneration == touch.document && scrollTouch === touch

    private fun canCompleteScrollTap(touch: ScrollTouch) = sameScrollTouch(touch) && touch.ended &&
        touch.shortTap && touch.eligible && !touch.moved && touch.axis == 0 && selectionMode == null &&
        scrollX == touch.offsetX && scrollY == touch.offsetY

    private fun completeScrollTap(touch: ScrollTouch) {
        // Wait for the DOWN classification; only a plain or highlight DOWN can become a tap.
        val downHit = touch.downHit ?: return
        if (!canCompleteScrollTap(touch) || touch.tapRequested || (downHit and BookPagination.HIT_HIGHLIGHT.inv()) != 0) return
        touch.tapRequested = true
        evaluateJavascript(BookPagination.hitTest(touch.upX.roundToInt(), touch.upY.roundToInt(), width, height)) { result ->
            if (!canCompleteScrollTap(touch)) return@evaluateJavascript
            touch.shortTap = false // Consume before calling out: observers can replace/release this view.
            val hit = result.toIntOrNull() ?: BookPagination.HIT_OUTSIDE
            if (hit == 0) onReaderTap()
            else if (hit == BookPagination.HIT_HIGHLIGHT) reportHighlightAt(touch.upX.roundToInt(), touch.upY.roundToInt()) {
                sameScrollTouch(touch)
            }
        }
    }

    private fun sameBookTouch(touch: BookTouch) = !isReleased && bookDocument && bookLoaded &&
        documentGeneration == touch.document && bookTouch === touch

    private fun cancelNativeBookTouch(event: MotionEvent, touch: BookTouch) {
        if (touch.cancelled) return
        touch.cancelled = true
        val cancelled = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancelled)
        cancelled.recycle()
    }

    private fun onBookTouchEvent(event: MotionEvent): Boolean {
        if (!scrollReady || bookWork != null) return true
        val configuration = ViewConfiguration.get(context)
        val slop = configuration.scaledTouchSlop.toFloat()
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                horizontalGesture = false
                val touch = BookTouch(documentGeneration, event.x, event.y, event.eventTime, selectionMode != null)
                bookTouch = touch
                readingSwipe = ReadingSwipe(slop * 2f, maxOf(width * .18f, 64f * resources.displayMetrics.density), longPress)
                readingSwipe?.start(event.x, event.y, event.eventTime)
                if (touch.selection) readingSwipe?.cancel()
                // Native events still reach WebView: no synthetic click/toggle and no JS handlers.
                // Save the DOWN target as well as the UP target, since <details> can reflow on UP.
                // Queue inspection before native DOWN can clear an existing DOM selection.
                evaluateJavascript(BookPagination.hitTest(event.x.roundToInt(), event.y.roundToInt(), width, height)) { result ->
                    if (!sameBookTouch(touch)) return@evaluateJavascript
                    touch.downHit = result.toIntOrNull() ?: BookPagination.HIT_OUTSIDE
                    if ((touch.downHit!! and BookPagination.HIT_SELECTION) != 0) readingSwipe?.cancel()
                    completeBookTap(touch)
                }
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                readingSwipe?.cancel()
                bookTouch?.let { touch ->
                    touch.moved = true
                    // Do not steal an existing native selection handle gesture.
                    if (selectionMode == null && !touch.selection) cancelNativeBookTouch(event, touch)
                    if (touch.cancelled) return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val touch = bookTouch ?: return true
                val dx = abs(event.x - touch.x)
                val dy = abs(event.y - touch.y)
                if (dx > slop || dy > slop || event.pointerCount != 1) touch.moved = true
                val selecting = touch.selection || selectionMode != null ||
                    (((touch.downHit ?: 0) and BookPagination.HIT_SELECTION) != 0)
                if (selecting || (!horizontalGesture && !touch.cancelled && event.eventTime - touch.time >= longPress)) {
                    readingSwipe?.cancel()
                    return if (touch.cancelled) true else super.onTouchEvent(event)
                }
                if (event.pointerCount != 1) readingSwipe?.cancel()
                if (event.pointerCount == 1 && readingSwipe?.move(event.x, event.y, event.eventTime) == true) {
                    horizontalGesture = true
                    cancelNativeBookTouch(event, touch)
                    return true
                }
                if (horizontalGesture || touch.cancelled) return true
                if (touch.moved) {
                    // WebView starts its own root scroll at this same slop; a natively scrolled column
                    // would only be snapped back by the page pin, which reads as trembling.
                    if (dx > slop * 2 || dy > slop * 2) readingSwipe?.cancel() // Decided non-horizontal.
                    cancelNativeBookTouch(event, touch) // BOOK never becomes vertical/diagonal scrolling.
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val touch = bookTouch ?: return true
                touch.ended = true
                touch.moved = touch.moved || abs(event.x - touch.x) > slop || abs(event.y - touch.y) > slop
                val horizontal = horizontalGesture
                val direction = readingSwipe?.finish(event.x, canPreviousUnit, canNextUnit) ?: 0
                horizontalGesture = false
                if (horizontal || touch.cancelled) {
                    if (direction != 0) dispatchBookSwipe(touch, direction)
                    return true
                }
                touch.shortTap = !touch.moved && !touch.selection && selectionMode == null &&
                    event.eventTime - touch.time < longPress
                val handled = super.onTouchEvent(event)
                completeBookTap(touch)
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                readingSwipe?.cancel()
                horizontalGesture = false
                bookTouch = null
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dispatchBookSwipe(touch: BookTouch, direction: Int) {
        // Also protect selections made through accessibility/the DOM before a native ActionMode
        // exists. This is a closed read-only query, not an event handler installed in the page.
        evaluateJavascript(BookPagination.SELECTION) { result ->
            if (!sameBookTouch(touch) || !interactionEnabled || bookWork != null || selectionMode != null ||
                touch.selection || result != "false") return@evaluateJavascript
            if (direction > 0 && canNextUnit) onNextUnit()
            else if (direction < 0 && canPreviousUnit) onPreviousUnit()
        }
    }

    private fun completeBookTap(touch: BookTouch) {
        if (!sameBookTouch(touch) || !touch.ended || !touch.shortTap || touch.tapRequested || touch.downHit == null) return
        touch.tapRequested = true
        evaluateJavascript(BookPagination.hitTest(touch.x.roundToInt(), touch.y.roundToInt(), width, height)) { result ->
            if (!sameBookTouch(touch)) return@evaluateJavascript
            val hit = (touch.downHit ?: BookPagination.HIT_OUTSIDE) or (result.toIntOrNull() ?: BookPagination.HIT_OUTSIDE)
            if ((hit and BookPagination.HIT_SUMMARY) != 0) {
                val document = touch.document
                postVisualStateCallback(document, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!isReleased && bookDocument && document == documentGeneration && bookLoaded) refreshBookLayout()
                    }
                })
                invalidate()
            } else if (hit == 0 && interactionEnabled && selectionMode == null) {
                if (bookWork != null) { touch.tapRequested = false; return@evaluateJavascript } // Re-checked after the reflow publishes.
                // No edge zones: all genuine non-interactive taps toggle the host's chrome.
                onReaderTap()
            } else if (hit == BookPagination.HIT_HIGHLIGHT && interactionEnabled && selectionMode == null && bookWork == null) {
                reportHighlightAt(touch.x.roundToInt(), touch.y.roundToInt()) { sameBookTouch(touch) && bookWork == null }
            }
        }
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(selectionCallback(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(selectionCallback(callback), type)

    private fun selectionCallback(callback: ActionMode.Callback): ActionMode.Callback =
        if ((!bookDocument && !scrollingBookDocument) || callback is BookSelectionCallback) callback else BookSelectionCallback(callback)

    /** Delegate every native action, including Callback2's selection-handle content rectangle. */
    private inner class BookSelectionCallback(private val delegate: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = delegate.onCreateActionMode(mode, menu)
            if (created) {
                selectionMode = mode
                readingSwipe?.cancel()
                bookTouch?.shortTap = false
                scrollTouch?.eligible = false
                scrollTouch?.shortTap = false
                addHighlightAction(menu)
            }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = delegate.onPrepareActionMode(mode, menu)
            // WebView rebuilds its own items here; keep ours first whenever it does.
            return addHighlightAction(menu) || changed
        }

        /** Source text stays inside the app: no copy, share, web search or text-processing entries. */
        private fun addHighlightAction(menu: Menu): Boolean {
            var changed = false
            for (index in menu.size() - 1 downTo 0) {
                val item = menu.getItem(index)
                if (item.itemId !in OWN_ACTIONS) { menu.removeItem(item.itemId); changed = true }
            }
            if (menu.findItem(R.id.reader_highlight_action) == null) {
                menu.add(Menu.NONE, R.id.reader_highlight_action, 0, R.string.reader_highlight)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                changed = true
            }
            if (menu.findItem(R.id.reader_note_action) == null) {
                menu.add(Menu.NONE, R.id.reader_note_action, 1, R.string.reader_note)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
                changed = true
            }
            return changed
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in OWN_ACTIONS) return delegate.onActionItemClicked(mode, item)
            // Read the DOM range before dismissing: finish() clears the selection in the renderer.
            requestSelectionHighlight(withNote = item.itemId == R.id.reader_note_action) {
                if (selectionMode === mode) mode.finish()
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            if (selectionMode === mode) selectionMode = null
            delegate.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (delegate is ActionMode.Callback2) delegate.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        documentGeneration++
        scrollReady = false
        bookLoaded = false
        bookPageState = null
        cancelBookWork()
        scrollBookLayout.stop()
        resetReaderStyleWork()
        waitingBookCompletions.clear()
        bookFailure = {}
        bookTouch = null
        scrollTouch = null
        selectionMode?.finish()
        selectionMode = null
        onRendered = {}
        onRenderFailed = {}
        onBookPageChanged = {}
        onReadingAnchor = {}
        onReaderTap = {}
        onHighlightSelected = { _, _ -> }
        onHighlightTapped = {}
        onLinkUnavailable = {}
        onLinkActivated = {}
        onScrollPosition = {}
        onViewportChanged = {}
        onNextUnit = {}
        onPreviousUnit = {}
        removeCallbacks(scrollPositionNotification)
        removeCallbacks(viewportNotification)
        removeCallbacks(legacyCheckpointFence)
        legacyObservedViewport = null
        legacyPaintedViewport = null
        scrollPositionPending = false
        viewportPending = false
        readingSwipe?.cancel()
        stopLoading()
        webViewClient = WebViewClient()
        destroy()
    }
}

/** Every subresource is denied except a small raster image addressed by a source object name. */
object ReaderResources {
    fun response(uri: Uri, method: String, mainFrame: Boolean, image: (String) -> ContentObject?): WebResourceResponse {
        val prefix = "/objects/"
        val path = uri.encodedPath.orEmpty()
        if (mainFrame || method != "GET" || uri.scheme != "https" || uri.encodedAuthority != "reader.local" ||
            uri.query != null || uri.fragment != null || !path.startsWith(prefix)) return denied()
        val name = Uri.decode(path.removePrefix(prefix))
        if (name.isBlank() || name.length > 512 || name.any { it == '\u0000' }) return denied()
        val content = try { image(name) } catch (_: Exception) { null } ?: return denied()
        if (content.bytes.size > 1024 * 1024 || !isRaster(content)) return denied()
        return WebResourceResponse(content.mimeType, null, 200, "OK", mapOf(
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
        ), ByteArrayInputStream(content.bytes))
    }

    private fun isRaster(content: ContentObject): Boolean {
        val bytes = content.bytes
        fun begins(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { (bytes[it].toInt() and 255) == signature[it] }
        return when (content.mimeType) {
            "image/png" -> begins(137, 80, 78, 71, 13, 10, 26, 10)
            "image/jpeg" -> begins(255, 216, 255)
            "image/bmp" -> begins(66, 77)
            "image/gif" -> begins(71, 73, 70, 56)
            "image/webp" -> begins(82, 73, 70, 70) && bytes.size >= 12 && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
            else -> false
        }
    }

    internal fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}