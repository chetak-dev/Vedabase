package org.vedareader.reader

/** Shared reader font declarations; passive local files only, no external font service. */
internal object ReferenceTypography {
    const val LATIN = "Fira Sans Condensed"
    /** Devanagari uses the serif face, matching the printed editions' bold verse type. */
    const val DEVANAGARI = "Reader Serif Devanagari"
    const val BODY_STACK = "'$LATIN', '$DEVANAGARI', \"Noto Sans Bengali\", sans-serif"
    const val DEVANAGARI_STACK = "'$DEVANAGARI', \"Noto Serif Devanagari\", \"Noto Serif Bengali\", serif"
    private const val BASE = "https://reader.local/fonts/"
    private const val DEVANAGARI_FILE = "NotoSerifDevanagari.ttf"
    val FILES = listOf("FiraSansCondensed-Regular.ttf", "FiraSansCondensed-Italic.ttf", "FiraSansCondensed-Medium.ttf",
        "FiraSansCondensed-SemiBold.ttf", "FiraSansCondensed-Bold.ttf", "FiraSansCondensed-BoldItalic.ttf", DEVANAGARI_FILE)
    val CSP_FONTS = FILES.joinToString(" ") { BASE + it }

    val CSS: String = buildString {
        for ((file, weight, style) in listOf(
            Triple("Regular", 400, "normal"), Triple("Italic", 400, "italic"),
            Triple("Medium", 500, "normal"), Triple("SemiBold", 600, "normal"),
            Triple("Bold", 700, "normal"), Triple("BoldItalic", 700, "italic"),
        )) {
            append("@font-face { font-family: '$LATIN'; font-style: $style; font-weight: $weight; ")
            append("src: url('${BASE}FiraSansCondensed-$file.ttf') format('truetype'); }\n")
        }
        append("@font-face { font-family: '$DEVANAGARI'; font-style: normal; font-weight: 100 900; ")
        append("src: url('$BASE$DEVANAGARI_FILE') format('truetype'); }\n")
    }
}
