package org.vedareader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import org.vedareader.data.ContentObject
import org.vedareader.data.HighlightSelection
import org.vedareader.data.HighlightSpan
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReadingLocation

data class ReaderPageStatus(
    val busy: Boolean = true,
    val hasPage: Boolean = false,
    val renderFailed: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    /** BOOK column progress, or the unchanged VERSE scroll progress; a single page is complete. */
    val progress: Float = 0f,
    /** Zero-based, visually fenced BOOK page. A zero count means no painted book layout. */
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    /** First visible source record in either resource layout, or zero without a painted anchor. */
    val anchorRecord: Int = 0,
    /** Display-only heading reference; map through the outline, never persist instead of the anchor. */
    val referenceRecord: Int = anchorRecord,
)

/** Session-scoped, bounded double buffer. Only a visually ready page can replace the current one. */
class ReaderPageHost(context: Context, private val paginateBooks: Boolean = true) : FrameLayout(context) {
    var onStatusChanged: (ReaderPageStatus) -> Unit = {}
    var onNextUnit: () -> Unit = {}
    var onPreviousUnit: () -> Unit = {}
    var onScrollPosition: (Int) -> Unit = {}
    /** Publish the painted resource anchor in either mode and acknowledge enterAtEnd after settle. */
    var onBookAnchor: (Int) -> Unit = {}
    var onReaderTap: () -> Unit = {}
    var onLinkUnavailable: () -> Unit = {}
    var onLinkActivated: (ReaderLink) -> Unit = { onLinkUnavailable() }
    var onHighlightSelected: (HighlightSelection, Boolean) -> Unit = { _, _ -> }
    var onHighlightTapped: (String) -> Unit = {}
    var documentDescription: String = ""

    var isBusy: Boolean = true
        private set
    /** The displayed page may be retained (but inert) while the next request is pending. */
    val currentView: OfflineReaderView? get() = current?.view
    val settledView: OfflineReaderView? get() = currentView?.takeUnless { isBusy || it.isReaderStylePending }

    private data class PageKey(val library: String, val start: Int, val end: Int, val position: Int,
        val mode: ReadingMode, val language: String) {
        constructor(location: ReadingLocation, mode: ReadingMode, language: String, resource: Boolean) :
            this(location.libraryId, location.start, location.end,
                if (mode == ReadingMode.BOOK || resource) 0 else location.position, mode, language)
    }
    private class Page(val key: PageKey, val view: OfflineReaderView, val generation: Long) {
        var ready = false
        var transitionFinished = false
    }
    private enum class Phase { LOADING, DOCUMENT, FAILED, EMPTY }

    private var current: Page? = null
    private var incoming: Page? = null
    private var animation: AnimatorSet? = null
    private var bookTurn: Any? = null
    private var reviewedScrollAnimation: ValueAnimator? = null
    private var renderTimeout: Runnable? = null
    private var generation = 0L
    private var released = false
    private var requestKey: PageKey? = null
    private var phase: Phase? = null
    private var requestedDocument: ReaderDocument? = null
    private var renderFailed = false
    private var latestState: ReaderState? = null
    private var textZoom = 100
    private var readerStyle = ReaderStyle()
    private var highlights: List<HighlightSpan> = emptyList()
    private var imageLoader: (String) -> ContentObject? = { null }
    private var lastStatus: ReaderPageStatus? = null

    init {
        setBackgroundColor(Color.parseColor(ReaderPaperAppearance.Paper))
        clipChildren = true
        clipToPadding = true
    }

    /** Update native paper and the same current/incoming documents; never submit or load data. */
    fun setReaderStyle(style: ReaderStyle) {
        if (released) return
        val next = style.normalized()
        if (readerStyle == next) return
        readerStyle = next
        setBackgroundColor(Color.parseColor(next.palette.Paper))
        listOfNotNull(current, incoming).forEach { it.view.setReaderStyle(next) }
        report()
    }

    /** Paint user highlights on the same current/incoming documents; never submits or loads data. */
    fun setHighlights(spans: List<HighlightSpan>) {
        if (released || highlights == spans) return
        highlights = spans
        listOfNotNull(current, incoming).forEach { it.view.setHighlights(spans) }
    }

    fun submit(state: ReaderState, zoom: Int, image: (String) -> ContentObject?) {
        if (released) return
        // true -> false acknowledges the painted anchor, not another end-landing request.
        val newEndRequest = state.enterAtEnd && latestState?.enterAtEnd != true
        latestState = state
        textZoom = zoom
        imageLoader = image
        val resource = state.document?.bookResource != null
        val key = PageKey(state.location, state.mode, state.languageTag, resource)
        val nextPhase = when {
            state.loading -> Phase.LOADING
            state.failed -> Phase.FAILED
            state.document?.section?.paragraphs.isNullOrEmpty() -> Phase.EMPTY
            else -> Phase.DOCUMENT
        }
        // Live resource position/title publication keeps this key and document identity. An explicit
        // contents jump supplies a new document, even when the resource range/HTML is identical.
        val changed = key != requestKey || nextPhase != phase ||
            (nextPhase == Phase.DOCUMENT && (state.document !== requestedDocument || newEndRequest))
        if (changed) {
            invalidateIncoming()
            requestKey = key
            phase = nextPhase
            requestedDocument = state.document
            renderFailed = false
            isBusy = true
            when (nextPhase) {
                Phase.LOADING -> Unit // Keep the previous ready page mounted; never load into it.
                Phase.FAILED, Phase.EMPTY -> clearCurrent()
                Phase.DOCUMENT -> prepare(key, requireNotNull(state.document), state.scrollY,
                    state.location.position, (state.mode == ReadingMode.BOOK || resource) && state.enterAtEnd)
            }
        }
        listOfNotNull(current, incoming).forEach {
            it.view.contentDescription = documentDescription
            it.view.setReaderTextZoom(zoom)
        }
        report()
    }

    /** The synchronous gate also blocks a second toolbar click before Compose recomposes. */
    fun next() = navigate(forward = true)
    fun previous() = navigate(forward = false)

    private fun navigate(forward: Boolean) {
        if (released) return
        val page = current?.takeIf { isCurrentInteractive(it) } ?: return
        val view = page.view
        val offset = view.scrollY.coerceIn(0, view.scrollRange)
        val reviewedBookScroll = !paginateBooks && page.key.mode == ReadingMode.BOOK && view.viewportHeight > 0 &&
            (if (forward) offset < view.scrollRange else offset > 0)
        // Only painted native columns or actual resource neighbours make a turn available.
        if (!reviewedBookScroll && !hasBookPage(page, forward) && !hasNeighbour(forward)) return
        isBusy = true
        report()
        if (!isCurrentRequest(page)) return // A status observer may synchronously replace/release us.
        if (reviewedBookScroll) {
            val step = (view.viewportHeight * .92f).toInt().coerceIn(1, view.viewportHeight)
            val target = if (forward) offset + minOf(step, view.scrollRange - offset) else offset - minOf(step, offset)
            scrollReviewedPage(page, target)
            return
        }
        if (view.paginatedBook) {
            val turn = Any()
            bookTurn = turn
            // Native owns the animation, reduced-motion path and final compositor fence. Its
            // onBookPageChanged arrives BEFORE this completion, while our gate is still locked.
            if (page.view.turnBookPage(forward) {
                if (bookTurn === turn && isCurrentRequest(page)) {
                    bookTurn = null
                    isBusy = false
                    report()
                    publishPosition(page)
                }
            }) return
            bookTurn = null // False is a boundary/not-ready result; it has no completion callback.
        }
        if (hasNeighbour(forward)) {
            if (forward) onNextUnit() else onPreviousUnit()
        } else {
            isBusy = false
            report()
        }
    }

    /** Retain the installed v0.9 behaviour while pagination is awaiting its own review. */
    private fun scrollReviewedPage(page: Page, target: Int) {
        val turn = Any()
        bookTurn = turn
        val request = generation
        fun active() = bookTurn === turn && request == generation && isCurrentRequest(page)
        fun finish() {
            if (!active()) return
            page.view.scrollTo(0, target.coerceAtMost(page.view.scrollRange))
            page.view.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!active()) return
                    reviewedScrollAnimation = null
                    bookTurn = null
                    isBusy = false
                    report()
                    publishPosition(page)
                }
            })
            page.view.invalidate()
        }
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return }
        reviewedScrollAnimation = ValueAnimator.ofInt(page.view.scrollY, target).apply {
            duration = ReaderMotion.pageTurn
            interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            addUpdateListener { if (active()) page.view.scrollTo(0, (it.animatedValue as Int).coerceIn(0, page.view.scrollRange)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            })
            start()
        }
    }

    private fun hasBookPage(page: Page, forward: Boolean): Boolean {
        if (!page.view.paginatedBook) return false
        val info = page.view.bookPageInfo ?: return false
        return if (forward) info.page < info.count - 1 else info.page > 0
    }

    private fun hasNeighbour(forward: Boolean): Boolean {
        val section = latestState?.document?.section ?: return false
        // Outline entries inside the current resource are NOT neighbouring documents. This
        // also preserves the old single-entry BOOK and whole-unit VERSE document contracts.
        return (if (forward) section.next else section.previous) != null
    }

    /** Native rendering failures can retry the already loaded document without a controller API change. */
    fun retryRender() {
        if (!renderFailed || released) return
        val state = latestState ?: return
        requestedDocument = null
        submit(state, textZoom, imageLoader)
    }

    private fun prepare(key: PageKey, document: ReaderDocument, scrollY: Int,
        initialAnchor: Int, initialAtEnd: Boolean) {
        val view = OfflineReaderView(context)
        val page = Page(key, view, generation)
        incoming = page
        view.alpha = 0f // VISIBLE and measured: INVISIBLE/GONE cannot provide the visual-state guarantee.
        view.interactionEnabled = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.setReaderStyle(readerStyle) // Before either render configuration or HTML load.
        view.setHighlights(highlights)
        view.setReaderTextZoom(textZoom)
        view.contentDescription = documentDescription
        view.paginatedBook = paginateBooks && key.mode == ReadingMode.BOOK
        view.scrollingBook = key.mode == ReadingMode.VERSE && document.bookResource != null
        view.initialBookAnchor = if (view.paginatedBook || view.scrollingBook) {
            initialAnchor.takeIf { it > 0 && it in key.start..key.end } ?: document.bookResource?.anchorRecord
        } else null
        view.onNextUnit = { if (isCurrentInteractive(page)) next() }
        view.onPreviousUnit = { if (isCurrentInteractive(page)) previous() }
        view.onScrollPosition = {
            if (!view.paginatedBook && isCurrentInteractive(page)) {
                onScrollPosition(it)
                if (isCurrentInteractive(page)) report()
            }
        }
        view.onBookPageChanged = {
            // Initial layout publishes before onRendered; a turn publishes before onComplete.
            // Those anchors are picked up by settle/completion, not sent to a busy controller.
            if (page.key.mode == ReadingMode.BOOK && isCurrentInteractive(page)) {
                report()
                publishPosition(page)
            }
        }
        view.onReadingAnchor = {
            // Initial notifications arrive while incoming; settle publishes that anchor once.
            if (view.scrollingBook && isCurrentInteractive(page)) {
                report()
                publishPosition(page)
            }
        }
        // Both layouts classify safe taps natively. Only the current, interactive page may
        // forward them; scrolling mode must not suppress the fullscreen reveal gesture.
        view.onReaderTap = { if (isCurrentInteractive(page)) onReaderTap() }
        view.onViewportChanged = {
            if (isCurrentRequest(page)) {
                report()
                publishPosition(page)
            }
            else if (isIncoming(page) && page.ready) showReadyPage(page)
        }
        view.onLinkUnavailable = { if (isCurrentInteractive(page)) onLinkUnavailable() }
        view.onLinkActivated = { link -> if (isCurrentInteractive(page)) onLinkActivated(link) }
        view.onHighlightSelected = { selection, withNote -> if (isCurrentInteractive(page)) onHighlightSelected(selection, withNote) }
        view.onHighlightTapped = { id -> if (isCurrentInteractive(page)) onHighlightTapped(id) }
        view.onRendered = {
            if (isIncoming(page)) {
                cancelTimeout()
                page.ready = true
                showReadyPage(page)
            }
        }
        view.onRenderFailed = {
            // Do not destroy a WebView from inside one of its own client callbacks.
            post {
                if (!released && current === page && phase == Phase.LOADING) {
                    clearCurrent() // Its replacement is still loading in the controller; do not strand Retry.
                    report()
                } else if (isIncoming(page) || isCurrentRequest(page)) failRender()
            }
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderTimeout = Runnable { if (isIncoming(page)) failRender() }.also { postDelayed(it, 20_000L) }
        view.render("$key", document.html, if (view.paginatedBook || view.scrollingBook) 0 else scrollY,
            imageLoader, initialAtEnd = initialAtEnd)
    }

    private fun isIncoming(page: Page) = !released && incoming === page && page.generation == generation
    private fun isCurrentRequest(page: Page) = !released && current === page && page.key == requestKey &&
        page.generation == generation && phase == Phase.DOCUMENT
    private fun isCurrentInteractive(page: Page) = !isBusy && !page.view.isReaderStylePending && isCurrentRequest(page)

    private fun showReadyPage(page: Page) {
        if (!isIncoming(page) || !page.ready || page.view.isReaderStylePending || animation != null || width == 0 || height == 0) return
        val outgoing = current
        if (page.transitionFinished || outgoing == null || outgoing.key == page.key || !ValueAnimator.areAnimatorsEnabled()) {
            settle(page)
            return
        }
        val direction = if (page.key.start >= outgoing.key.start) 1f else -1f
        val vertical = page.view.scrollingBook && outgoing.view.scrollingBook
        val axis = if (vertical) View.TRANSLATION_Y else View.TRANSLATION_X
        val distance = (if (vertical) height else width).toFloat() * direction
        if (vertical) page.view.translationY = distance else page.view.translationX = distance
        page.view.alpha = 1f
        val transition = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(outgoing.view, axis, 0f, -distance),
                ObjectAnimator.ofFloat(page.view, axis, distance, 0f),
            )
            duration = ReaderMotion.transition
            interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isIncoming(page)) {
                        page.transitionFinished = true
                        this@ReaderPageHost.animation = null
                        settle(page)
                    }
                }
            })
        }
        animation = transition
        transition.start()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        incoming?.takeIf { it.ready }?.let { showReadyPage(it) }
    }

    private fun settle(page: Page) {
        if (!isIncoming(page) || page.view.isReaderStylePending) return
        animation = null
        clearCurrent()
        incoming = null
        current = page
        page.view.translationX = 0f
        page.view.translationY = 0f
        page.view.alpha = 1f
        isBusy = false
        report()
        // Do not drop the initial resource anchor just because its native notification arrived
        // while incoming. End acknowledgement must not reload or return to the resource start.
        publishPosition(page)
    }

    private fun publishPosition(page: Page) {
        if (!isCurrentInteractive(page)) return
        report() // A new visible reference can retain exactly the same persisted paragraph anchor.
        if (!isCurrentInteractive(page)) return
        if (page.view.paginatedBook) {
            page.view.bookPageInfo?.anchorRecord?.takeIf { it > 0 }?.let { onBookAnchor(it) }
        } else if (page.view.scrollingBook) {
            page.view.readingAnchor.takeIf { it > 0 }?.let { onBookAnchor(it) }
            if (isCurrentInteractive(page)) onScrollPosition(page.view.scrollY.coerceIn(0, page.view.scrollRange))
        } else {
            onScrollPosition(page.view.scrollY.coerceIn(0, page.view.scrollRange))
        }
    }

    private fun failRender() {
        invalidateIncoming()
        clearCurrent() // Errors must never leave an obsolete document interactable behind the message.
        renderFailed = true
        isBusy = true
        report()
    }

    private fun invalidateIncoming() {
        generation++ // Invalidate before cancelling: Animator.cancel also calls onAnimationEnd.
        bookTurn = null
        reviewedScrollAnimation?.removeAllUpdateListeners()
        reviewedScrollAnimation?.removeAllListeners()
        reviewedScrollAnimation?.cancel()
        reviewedScrollAnimation = null
        cancelTimeout()
        animation?.removeAllListeners()
        animation?.cancel()
        animation = null
        incoming?.let { dispose(it) }
        incoming = null
        current?.view?.apply { translationX = 0f; translationY = 0f; alpha = 1f }
    }

    private fun cancelTimeout() {
        renderTimeout?.let { removeCallbacks(it) }
        renderTimeout = null
    }

    private fun clearCurrent() {
        current?.let { dispose(it) }
        current = null
    }

    private fun dispose(page: Page) {
        removeView(page.view)
        page.view.release()
    }

    private fun report() {
        if (released) return
        val page = current
        val view = page?.view
        val range = view?.scrollRange ?: 0
        val offset = view?.scrollY?.coerceIn(0, range) ?: 0
        val interactive = page?.let { isCurrentInteractive(it) } == true
        val book = view?.paginatedBook == true
        val info = if (book) view?.bookPageInfo else null
        val reviewedBook = !paginateBooks && page?.key?.mode == ReadingMode.BOOK && (view?.viewportHeight ?: 0) > 0
        val canNext = interactive && ((info != null && info.page < info.count - 1) ||
            (reviewedBook && offset < range) || hasNeighbour(forward = true))
        val canPrevious = interactive && ((info != null && info.page > 0) ||
            (reviewedBook && offset > 0) || hasNeighbour(forward = false))
        listOfNotNull(current, incoming).forEach { page ->
            val enabled = isCurrentInteractive(page)
            page.view.interactionEnabled = enabled
            page.view.canNextUnit = enabled && canNext
            page.view.canPreviousUnit = enabled && canPrevious
            page.view.importantForAccessibility = if (enabled) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val progress = when {
            view == null -> 0f
            book -> if (info == null) 0f else if (info.count <= 1) 1f else info.page.toFloat() / (info.count - 1)
            range == 0 -> 1f
            else -> offset.toFloat() / range
        }
        val status = ReaderPageStatus(isBusy || view?.isReaderStylePending == true, current != null, renderFailed, canPrevious, canNext, progress,
            pageIndex = info?.page ?: 0, pageCount = info?.count ?: 0,
            anchorRecord = info?.anchorRecord ?: view?.readingAnchor ?: 0,
            referenceRecord = info?.referenceRecord ?: view?.readingReference ?: 0)
        if (status != lastStatus) {
            lastStatus = status
            onStatusChanged(status)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (currentView?.isReaderStylePending == true) return true
        val isScrollingView = currentView?.scrollingBook == true && currentView?.paginatedBook == false
        if (isBusy && !isScrollingView) return true
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentView?.isReaderStylePending == true) return true
        val isScrollingView = currentView?.scrollingBook == true && currentView?.paginatedBook == false
        if (isBusy && !isScrollingView) return true
        return super.onTouchEvent(event)
    }

    fun release() {
        if (released) return
        released = true
        onStatusChanged = {}
        onNextUnit = {}
        onPreviousUnit = {}
        onScrollPosition = {}
        onBookAnchor = {}
        onReaderTap = {}
        onLinkUnavailable = {}
        onLinkActivated = {}
        invalidateIncoming()
        clearCurrent()
        latestState = null
        requestedDocument = null
        imageLoader = { null }
    }
}
