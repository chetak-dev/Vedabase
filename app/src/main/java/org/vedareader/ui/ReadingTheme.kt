package org.vedareader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.vedareader.reader.ReaderAppearance

/** Global app colors. Reader-local paper choices use their own scoped palette. */
object ReadingColors {
    val Header = color(ReaderAppearance.Header)
    val HeaderGlow = color(ReaderAppearance.HeaderGlow)
    /** Saffron sweep used behind every app header; deeper at the start where titles sit. */
    val HeaderBrush: Brush get() = Brush.linearGradient(listOf(Header, HeaderGlow), start = Offset.Zero, end = Offset(1400f, 260f))
    val Paper = color(ReaderAppearance.Paper)
    val Chapter = color(ReaderAppearance.Chapter)
    val Ink = color(ReaderAppearance.Ink)
    val Accent = color(ReaderAppearance.Accent)
    val OnHeader = color(ReaderAppearance.OnHeader)
    val Muted = color(ReaderAppearance.Muted)
    val Rule = color(ReaderAppearance.Rule)
    private fun color(hex: String) = Color(android.graphics.Color.parseColor(hex))
}