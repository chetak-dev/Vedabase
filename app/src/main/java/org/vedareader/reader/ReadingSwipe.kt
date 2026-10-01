package org.vedareader.reader

import kotlin.math.abs

/** Axis-locking keeps vertical reading, diagonal drags, taps, and long-press selection independent. */
class ReadingSwipe(private val slop: Float, private val distance: Float, private val longPressMs: Long) {
    private enum class Axis { PENDING, HORIZONTAL, VERTICAL, CANCELLED }
    private var axis = Axis.CANCELLED
    private var startX = 0f
    private var startY = 0f
    private var startedAt = 0L

    fun start(x: Float, y: Float, time: Long) {
        startX = x; startY = y; startedAt = time; axis = Axis.PENDING
    }

    /** True means the WebView should consume this as navigation, not scroll or click a link. */
    fun move(x: Float, y: Float, time: Long): Boolean {
        if (axis == Axis.PENDING) {
            val dx = abs(x - startX)
            val dy = abs(y - startY)
            if (time - startedAt >= longPressMs) axis = Axis.CANCELLED
            else if (dx > slop || dy > slop) axis = if (dx > dy * 1.8f && dx > slop) Axis.HORIZONTAL else Axis.VERTICAL
        }
        return axis == Axis.HORIZONTAL
    }

    /** +1 is next (swipe left), -1 previous; RTL scripts keep this explicitly documented order. */
    fun finish(x: Float, canPrevious: Boolean, canNext: Boolean): Int {
        val delta = x - startX
        val direction = when {
            axis != Axis.HORIZONTAL || abs(delta) < distance -> 0
            delta < 0 && canNext -> 1
            delta > 0 && canPrevious -> -1
            else -> 0
        }
        cancel()
        return direction
    }

    fun cancel() { axis = Axis.CANCELLED }
}