package org.vedareader.reader

import org.vedareader.data.ReaderPaper

/** Reader-only runtime appearance. No source text, Android APIs, preferences, or DB access. */
data class ReaderStyle(val paper: ReaderPaper = ReaderPaper.LIGHT, val lineSpacing: Int = 100) {
    /** Clamp corrupt/external input; the UI's +/- step is 5, not an extra rounding of saved values. */
    fun normalized(): ReaderStyle = copy(lineSpacing = lineSpacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING))

    val palette: ReaderPalette get() = ReaderPaperAppearance.palette(paper)

    /** Closed CSS values only. Safe to JSON-encode for the app-owned native style mutation. */
    fun css(): String {
        val colors = palette
        val spacing = normalized().lineSpacing
        // Integer formatting is locale-independent (including locales with a decimal comma).
        val factor = "${spacing / 100}.${(spacing % 100).toString().padStart(2, '0')}"
        return """
            :root {
                --reader-paper: ${colors.Paper}; --reader-ink: ${colors.Ink};
                --reader-header: ${colors.Header}; --reader-on-header: ${colors.OnHeader};
                --reader-accent: ${colors.Accent}; --reader-link: ${colors.Link};
                --reader-muted: ${colors.Muted}; --reader-rule: ${colors.Rule};
                --reader-chapter: ${colors.Chapter}; --reader-scheme: ${if (colors.dark) "dark" else "light"};
                --reader-hit: ${colors.Hit};
                --reader-line-factor: $factor;
                ${highlightVariables(colors.dark)}
                --reader-texture: ${if (colors.dark) "none" else TEXTURE};
            }
        """.trimIndent()
    }

    /** Replace only Folio's app-owned head style before loading; never parse/rewrite source records.
     * Legacy documents receive a static override and keep JavaScript disabled for their lifetime.
     */
    internal fun documentHtml(html: String, book: Boolean): String? {
        val headEnd = html.indexOf("</head>")
        if (headEnd < 0) return null
        if (!book) return html.substring(0, headEnd) + "<style>" + css() + LEGACY_OVERRIDES +
            "</style>" + html.substring(headEnd)
        val start = html.indexOf(STYLE_OPEN)
        if (start < 0 || start >= headEnd || html.indexOf(STYLE_OPEN, start + STYLE_OPEN.length) >= 0) return null
        val end = html.indexOf("</style>", start + STYLE_OPEN.length)
        if (end < 0 || end >= headEnd) return null
        return html.substring(0, start + STYLE_OPEN.length) + css() + html.substring(end)
    }

    companion object {
        const val MIN_LINE_SPACING = 85
        const val MAX_LINE_SPACING = 150
        const val LINE_SPACING_STEP = 5
        /** Unitless base line heights at 100%; the user's factor scales all three together. */
        const val BODY_LINE_HEIGHT = "1.3"
        const val DEVANAGARI_LINE_HEIGHT = "1.4"
        const val CHAPTER_LINE_HEIGHT = "1.2"

        /** User highlight backgrounds per paper family; ink stays the paper's own in both. */
        val LIGHT_HIGHLIGHTS = mapOf("yellow" to "#FFE58A", "green" to "#BFEBAE", "blue" to "#BCDBFF", "pink" to "#FFC4DB")
        val DARK_HIGHLIGHTS = mapOf("yellow" to "#6E5A12", "green" to "#2E6229", "blue" to "#25497C", "pink" to "#73304F")

        fun highlightVariables(dark: Boolean): String =
            (if (dark) DARK_HIGHLIGHTS else LIGHT_HIGHLIGHTS).entries.joinToString(" ") { "--reader-hl-${it.key}: ${it.value};" }
        internal const val STYLE_ID = "reader-live-style"
        internal const val STYLE_OPEN = "<style id=\"$STYLE_ID\">"

        private val LEGACY_OVERRIDES = """
            :root { color-scheme: var(--reader-scheme); background: var(--reader-paper); }
            body { background: var(--reader-paper); color: var(--reader-ink);
                line-height: calc(${BODY_LINE_HEIGHT} * var(--reader-line-factor)); }
            .folio-block.synonyms, .folio-block.devanagari { color: var(--reader-ink); }
            .folio-block.devanagari { line-height: calc(${DEVANAGARI_LINE_HEIGHT} * var(--reader-line-factor)); }
            .folio-block.verse, .folio-block.verse-in-purport, .folio-block.verse-line { color: var(--reader-accent); }
            .folio-block.chapter, .folio-block.collection { background: var(--reader-rule);
                line-height: calc(${CHAPTER_LINE_HEIGHT} * var(--reader-line-factor)); }
            .folio-block.text-section { color: var(--reader-muted); }
            a, .synonyms-group > summary { color: var(--reader-link); }
            .record.purport-content, .record.purport-content *, .folio-block.purport, .folio-block.purport *,
            .folio-block.verse-in-purport, .folio-block.verse-in-purport * { color: var(--reader-ink); }
            .synonyms-group, hr, td { border-color: var(--reader-rule); }
        """.trimIndent()

        internal val TEXTURE = """
            radial-gradient(ellipse 1px 2px at 7px 11px, rgba(96,73,39,.055) 45%, transparent 65%),
            radial-gradient(ellipse 2px 1px at 19px 5px, rgba(96,73,39,.04) 45%, transparent 65%),
            radial-gradient(ellipse 1px 1px at 3px 17px, rgba(255,255,255,.20) 45%, transparent 65%),
            linear-gradient(90deg, rgba(112,82,40,.045), transparent 9%, rgba(255,255,255,.10) 50%, transparent 91%, rgba(112,82,40,.045))
        """.trimIndent()
    }
}

/** Native motion durations; all existing reduced-motion paths and compositor fences still apply. */
object ReaderMotion {
    const val pageTurn = 150L
    const val transition = 170L
}