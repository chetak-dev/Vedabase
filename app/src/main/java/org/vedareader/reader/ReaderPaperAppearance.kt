package org.vedareader.reader

import org.vedareader.data.ReaderPaper

/** Pure CSS hex colors shared by native reader surfaces and reader-scoped Compose chrome. */
data class ReaderPalette(
    val Paper: String,
    val Ink: String,
    val Header: String,
    val OnHeader: String,
    val Accent: String,
    val Link: String,
    val Muted: String,
    val Rule: String,
    val Chapter: String,
    val dark: Boolean,
) {
    /** Search-hit background; ink stays the paper's own so marked words remain readable. */
    val Hit: String get() = if (dark) "#6a5a1f" else "#F6DF7C"
}

/** Reader-only paper palette, independent of Android and Compose; home colors stay unchanged. */
object ReaderPaperAppearance {
    // White: the neutral e-reader default. Link ink is a step lighter than body ink, still >= 7:1.
    // Bright papers share the app's saffron header so the reader chrome matches the home screen.
    const val Paper = "#FFFFFF"
    const val Ink = "#1F1F1F"
    const val Header = ReaderAppearance.Header
    const val OnHeader = ReaderAppearance.OnHeader
    const val Accent = "#5B4636"
    const val Link = "#4E4E4E"
    const val Muted = "#6A6A6A"
    const val Rule = "#DCDCDC"
    const val Chapter = "#F6F6F6"

    fun palette(paper: ReaderPaper): ReaderPalette = when (paper) {
        ReaderPaper.LIGHT -> ReaderPalette(Paper, Ink, Header, OnHeader, Accent, Link, Muted, Rule, Chapter, false)
        ReaderPaper.SEPIA -> ReaderPalette("#F6ECD9", "#3B2F22", Header, OnHeader, "#8A4A1D",
            "#66584A", "#6B5A45", "#D9CBB0", "#EEDFC7", false)
        ReaderPaper.GREEN -> ReaderPalette("#E4EFE2", "#22301F", Header, OnHeader, "#3E6B3B",
            "#4A5E48", "#4F5F4D", "#C3D4C1", "#D9E7D7", false)
        ReaderPaper.GRAY -> ReaderPalette("#2F3136", "#E4E4E4", "#3A3D43", "#E4E4E4", "#E3B978",
            "#C4C4C4", "#AEAEAE", "#4B4E55", "#383B41", true)
        ReaderPaper.DARK -> ReaderPalette("#000000", "#E0E0E0", "#121212", "#E0E0E0", "#E0B26A",
            "#BDBDBD", "#A8A8A8", "#333333", "#101010", true)
    }
}