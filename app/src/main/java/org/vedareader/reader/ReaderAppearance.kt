package org.vedareader.reader

/** Global library/search palette, independent of Android and Compose; not the book-paper theme. */
object ReaderAppearance {
    // Saffron: white on Header and Accent on Chapter both stay above 4.5:1.
    const val Header = "#B34E15"
    /** Lighter end of the header gradient; text sits on the darker start. */
    const val HeaderGlow = "#BF5A1F"
    const val Paper = "#FFF9EF"
    const val Chapter = "#FBEEDC"
    const val Ink = "#2F2B26"
    const val Accent = "#8A5A12"
    const val Link = "#8A4A0E"
    const val OnHeader = "#FFFFFF"
    const val Muted = "#6C6053"
    const val Rule = "#E6CFAE"
}