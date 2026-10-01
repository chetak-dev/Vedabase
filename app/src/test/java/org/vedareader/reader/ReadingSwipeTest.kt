package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic coordinates and timestamps only; no Android runtime, clocks, or sleeps. */
class ReadingSwipeTest {
    private val swipe = ReadingSwipe(slop = 10f, distance = 80f, longPressMs = 500L)

    @Test
    fun leftSwipeNavigatesNextOnlyAtOrBeyondDistanceThreshold() {
        for ((endX, expected) in listOf(121f to 0, 120f to 1, 119f to 1)) {
            swipe.start(200f, 300f, 1_000L)
            assertTrue(swipe.move(188f, 300f, 1_010L))
            assertTrue(swipe.move(endX, 300f, 1_020L))
            assertEquals("endX=$endX", expected, swipe.finish(endX, canPrevious = true, canNext = true))
        }
    }

    @Test
    fun rightSwipeNavigatesPreviousOnlyAtOrBeyondDistanceThreshold() {
        for ((endX, expected) in listOf(279f to 0, 280f to -1, 281f to -1)) {
            swipe.start(200f, 300f, 1_000L)
            assertTrue(swipe.move(212f, 300f, 1_010L))
            assertTrue(swipe.move(endX, 300f, 1_020L))
            assertEquals("endX=$endX", expected, swipe.finish(endX, canPrevious = true, canNext = true))
        }
    }

    @Test
    fun tapsAndMovementWithinSlopDoNotConsumeOrNavigate() {
        swipe.start(200f, 300f, 1_000L)
        assertEquals(0, swipe.finish(200f, canPrevious = true, canNext = true))

        for (offset in listOf(-10f, -9f, 0f, 9f, 10f)) {
            swipe.start(200f, 300f, 1_000L)
            assertFalse("offset=$offset", swipe.move(200f + offset, 300f, 1_010L))
            assertEquals(0, swipe.finish(200f + offset, canPrevious = true, canNext = true))
        }

        // Reaching slop exactly must leave the axis pending, not lock it vertically.
        swipe.start(200f, 300f, 1_000L)
        assertFalse(swipe.move(210f, 310f, 1_010L))
        assertTrue(swipe.move(211f, 300f, 1_020L))
        assertEquals(1, swipe.finish(120f, canPrevious = true, canNext = true))
    }

    @Test
    fun verticalScrollLocksOutNavigationEvenAfterLargeHorizontalMovement() {
        for (verticalOffset in listOf(-20f, 20f)) {
            swipe.start(200f, 300f, 1_000L)
            assertFalse(swipe.move(204f, 300f + verticalOffset, 1_010L))
            assertFalse(swipe.move(20f, 300f, 1_020L))
            assertFalse(swipe.move(380f, 300f, 1_030L))
            assertEquals(0, swipe.finish(380f, canPrevious = true, canNext = true))
        }
    }

    @Test
    fun diagonalMovementIncludingAxisRatioBoundaryCannotLaterBecomeHorizontal() {
        for (direction in listOf(-1f, 1f)) {
            // With dy=20, dx=36 is exactly the 1.8:1 boundary, not horizontal.
            for (horizontalOffset in listOf(20f, 35f, 36f)) {
                swipe.start(200f, 300f, 1_000L)
                assertFalse(swipe.move(200f + direction * horizontalOffset, 320f, 1_010L))
                val endX = 200f + direction * 180f
                assertFalse(swipe.move(endX, 300f, 1_020L))
                assertEquals(0, swipe.finish(endX, canPrevious = true, canNext = true))
            }

            swipe.start(200f, 300f, 1_000L)
            assertTrue(swipe.move(200f + direction * 37f, 320f, 1_010L))
            assertEquals(
                if (direction < 0f) 1 else -1,
                swipe.finish(200f + direction * 80f, canPrevious = true, canNext = true),
            )
        }
    }

    @Test
    fun longPressAtOrAfterDeadlineCancelsPendingGestureUntilANewStart() {
        for (elapsed in listOf(500L, 501L)) {
            swipe.start(200f, 300f, 1_000L)
            assertFalse(swipe.move(205f, 300f, 1_100L))
            // Expiration must win even if this move would otherwise lock horizontally.
            assertFalse(swipe.move(100f, 300f, 1_000L + elapsed))
            assertFalse(swipe.move(20f, 300f, 1_100L + elapsed))
            assertEquals(0, swipe.finish(20f, canPrevious = true, canNext = true))
        }

        swipe.start(200f, 300f, 2_000L)
        assertTrue(swipe.move(120f, 300f, 2_499L))
        assertEquals(1, swipe.finish(120f, canPrevious = true, canNext = true))
    }

    @Test
    fun explicitCancelStopsPendingAndLockedGesturesUntilANewStart() {
        for (lockFirst in listOf(false, true)) {
            swipe.start(200f, 300f, 1_000L)
            if (lockFirst) assertTrue(swipe.move(188f, 300f, 1_010L))

            // The gesture API has no pointer count; multi-pointer cancellation uses cancel().
            swipe.cancel()
            swipe.cancel()
            assertFalse(swipe.move(20f, 300f, 1_020L))
            assertEquals(0, swipe.finish(20f, canPrevious = true, canNext = true))
        }

        swipe.start(200f, 300f, 2_000L)
        assertTrue(swipe.move(280f, 300f, 2_010L))
        assertEquals(-1, swipe.finish(280f, canPrevious = true, canNext = true))
    }

    @Test
    fun unavailableNeighborBlocksOnlyItsOwnDirection() {
        for (canPrevious in listOf(false, true)) {
            for (canNext in listOf(false, true)) {
                swipe.start(200f, 300f, 1_000L)
                assertTrue(swipe.move(120f, 300f, 1_010L))
                assertEquals(
                    "left: previous=$canPrevious, next=$canNext",
                    if (canNext) 1 else 0,
                    swipe.finish(120f, canPrevious, canNext),
                )

                swipe.start(200f, 300f, 2_000L)
                assertTrue(swipe.move(280f, 300f, 2_010L))
                assertEquals(
                    "right: previous=$canPrevious, next=$canNext",
                    if (canPrevious) -1 else 0,
                    swipe.finish(280f, canPrevious, canNext),
                )
            }
        }
    }

    @Test
    fun finishCannotNavigateTwiceEvenAfterAnotherMoveOrABlockedFirstFinish() {
        for (endX in listOf(120f, 280f)) {
            for (neighborAvailable in listOf(false, true)) {
                swipe.start(200f, 300f, 1_000L)
                assertTrue(swipe.move(endX, 300f, 1_010L))
                val expected = if (!neighborAvailable) 0 else if (endX < 200f) 1 else -1
                assertEquals(expected, swipe.finish(endX, neighborAvailable, neighborAvailable))
                assertEquals(0, swipe.finish(endX, canPrevious = true, canNext = true))
                assertFalse(swipe.move(endX, 300f, 1_020L))
                assertEquals(0, swipe.finish(endX, canPrevious = true, canNext = true))
            }
        }
    }

    @Test
    fun longHorizontalSwipeRemainsUsableAfterInitialAxisLockDespiteVerticalDrift() {
        for (direction in listOf(-1f, 1f)) {
            swipe.start(200f, 300f, 1_000L)
            assertTrue(swipe.move(200f + direction * 11f, 300f, 1_010L))
            // Once horizontal, neither later vertical dominance nor elapsed time may change it.
            assertTrue(swipe.move(200f + direction * 20f, 500f, 1_020L))
            assertTrue(swipe.move(200f + direction * 40f, 500f, 1_500L))
            val endX = 200f + direction * 160f
            assertTrue(swipe.move(endX, 500f, 3_000L))
            assertEquals(
                if (direction < 0f) 1 else -1,
                swipe.finish(endX, canPrevious = true, canNext = true),
            )
        }
    }
}