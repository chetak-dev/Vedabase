package org.vedareader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil

/** Pure coordinate regressions; no Android stubs, layout/font assumptions or device changes. */
class NativeScaleGeometryTest {
    @Test
    fun observedApi35LateParagraphUsesContentScaleNotTheWidthRatio() {
        val pageTop = 6679.619140625
        val visualWidth = 412.19049072265625
        val scale = 2.625
        assertEquals(17534, BookPagination.nativeOffset(pageTop, scale))
        assertTrue(abs(pageTop * 1080 / visualWidth - 17534) > 32)
        assertTrue(abs(pageTop * scale - 17534) < .001)
    }

    @Test
    fun fractionalPitchRoundsTheAbsoluteOffsetOnceEvenAMillionColumnsDeep() {
        // Binary-exact CSS layout-unit fractions, deliberately NOT a rounded native page step.
        val pitch = BigDecimal("411.421875")
        val scale = BigDecimal("2.625")
        val roundedStep = BookPagination.offset(1, pitch.toDouble(), scale.toDouble())
        for (page in listOf(0, 1, 2, 37, 1000, 10_000, 100_000, 1_000_000)) {
            val expected = pitch.multiply(scale).multiply(BigDecimal(page))
                .setScale(0, RoundingMode.HALF_UP).intValueExact()
            assertEquals("page=$page", expected, BookPagination.offset(page, pitch.toDouble(), scale.toDouble()))
            if (page >= 1000) assertNotEquals(page * roundedStep, expected)
        }
        val page = 10_000
        val delta = BookPagination.offset(page + 1, pitch.toDouble(), scale.toDouble()) -
            BookPagination.offset(page, pitch.toDouble(), scale.toDouble())
        assertTrue(delta in roundedStep - 1..roundedStep + 1)
    }

    @Test
    fun overflowCountUsesColumnPitchNotTheWiderVisualClip() {
        val pitch = 411.421875
        val visualWidth = 412.19049072265625
        for (count in listOf(1, 2, 37, 1000, 10_000, 100_000)) {
            val integerCssExtent = ceil(count * pitch)
            assertEquals(count, BookPagination.pageCount(integerCssExtent, pitch))
            if (count >= 1000) assertNotEquals(count, BookPagination.pageCount(integerCssExtent, visualWidth))
        }
    }

    @Test
    fun integralCallersKeepTheirDefaultOffsetAndFractionalCallersSupplyTheExactOffset() {
        val legacy = BookPageInfo(3, 4, 101, 742)
        assertEquals(2226, legacy.nativeOffset)
        assertEquals(101, legacy.referenceRecord)
        assertEquals(2226, BookPagination.offset(3, 742))
        val page = 1000
        val pitch = 411.421875
        val scale = 2.625
        val info = BookPageInfo(page, page + 1, 101, BookPagination.offset(1, pitch, scale),
            nativeOffset = BookPagination.offset(page, pitch, scale))
        assertEquals(1080, info.viewportWidth)
        assertEquals(1079982, info.nativeOffset)
        assertNotEquals(page * info.viewportWidth, info.nativeOffset)
    }

    @Test
    fun invalidScalePitchCoordinatesAndNativeOverflowAreRejectedRatherThanSaturated() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { BookPagination.nativeOffset(1.0, bad) }
            assertThrows(IllegalArgumentException::class.java) { BookPagination.offset(1, bad, 2.625) }
        }
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { BookPagination.nativeOffset(bad, 2.625) }
        }
        assertThrows(IllegalArgumentException::class.java) { BookPagination.offset(-1, 411.421875, 2.625) }
        assertThrows(IllegalArgumentException::class.java) { BookPagination.offset(Int.MAX_VALUE, 411.421875, 2.625) }
        assertThrows(IllegalArgumentException::class.java) { BookPagination.nativeOffset(Double.MAX_VALUE, 2.625) }
        assertEquals(0, BookPagination.nativeOffset(0.0, 2.625))
        assertEquals(Int.MAX_VALUE, BookPagination.nativeOffset(Int.MAX_VALUE.toDouble(), 1.0))
    }
}