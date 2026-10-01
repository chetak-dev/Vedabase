package org.vedareader.reader

import org.vedareader.data.Paragraph
import org.vedareader.data.ContentTitles
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.SearchText
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * A deliberately small, original Folio reader, not an HTML passthrough or a layout emulator.
 * Only the directives below have meaning. Unknown/malformed markup is displayed as escaped
 * text; recognized font/layout metadata is discarded. Source entities are never decoded.
 * Each record has independent formatting state. No Android APIs or external resources are used.
 */
object FolioText {
    private const val BASE = "https://reader.local/"

    /** Readable text for snippets/definitions, not HTML. Explicit breaks and tabs are retained. */
    fun plain(source: String): String = PlainRenderer().render(source)

    /** Balanced HTML blocks, suitable for insertion into [document]'s styled body. */
    fun fragment(source: String): String = HtmlRenderer(ParagraphKind.TEXT).render(source)

    /**
     * Complete HTML5 document. Stable record anchors are named `record-<record number>`.
     * Compact verse mode only conceals redundant, explicitly styled labels; their HTML is retained.
        * Verse synonyms use initially collapsed native disclosures; book synonyms always flow
        * as ordinary visible text, with their source heading retained but visually hidden.
     */
    fun document(
        paragraphs: List<Paragraph>,
        languageTag: String,
        preferences: ReaderPreferences,
        compactVerse: Boolean = false,
        collapsibleSynonyms: Boolean = true,
        bookMode: Boolean = false,
        @Suppress("UNUSED_PARAMETER") useReadingFont: Boolean = false,
        scrollingBook: Boolean = false,
        /** Folded whole words (SearchText.highlightTerms) to wrap in app-owned marks; source text is unchanged. */
        highlight: Set<String> = emptySet(),
    ): String {
        val language = escape(languageTag)
        return buildString {
            append("<!DOCTYPE html><html lang=\"").append(language).append("\" dir=\"auto\"><head>")
            append("<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>Reader</title>")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            if (bookMode) append(BookPagination.CSP) else {
                append("default-src 'none'; img-src https://reader.local; style-src 'unsafe-inline'; ")
                append("font-src https://reader.local; base-uri https://reader.local; form-action 'none'")
            }
            append("\">")
            append("<base href=\"").append(BASE).append("\"><style>")
            append(css(preferences, live = bookMode))
            if (bookMode) append(paperTextureCss())
            if (bookMode) append(bookCss(preferences))
            if (bookMode && scrollingBook) append(scrollingBookCss())
            append("</style>")
            if (bookMode) {
                append(ReaderStyle.STYLE_OPEN)
                append(ReaderStyle(preferences.paper, preferences.lineSpacing).css())
                append("</style>")
            }
            append("</head><body")
            if (bookMode) append(if (scrollingBook) " class=\"book-reading book-scrolling\"" else " class=\"book-reading\"")
            else if (compactVerse) append(" class=\"verse-reading\"")
            append(" lang=\"").append(language).append("\" dir=\"auto\"><main class=\"reader")
            if (bookMode) append(" book-flow")
            append("\">")
            var verseGroup = false
            var synonymsGroup = false
            var inPurport = false
            for ((index, paragraph) in paragraphs.withIndex()) {
                // Semantic levels take precedence over generic paragraph styles.
                val kind = paragraphKind(paragraph.level) ?: paragraphKind(paragraph.style) ?: ParagraphKind.TEXT
                inPurport = purportContext(paragraph, kind, inPurport)
                val verseLine = kind == ParagraphKind.VERSE_LINE
                if (verseGroup && !verseLine) append("</div>")
                val synonymsBody = !bookMode && collapsibleSynonyms && isSynonymsBody(paragraph)
                val synonymsHeading = !bookMode && collapsibleSynonyms && isSynonymsHeading(paragraph) &&
                    paragraphs.getOrNull(index + 1)?.let(::isSynonymsBody) == true
                // Only consecutive source-styled bodies belong inside the disclosure.
                if (synonymsGroup && !synonymsBody) {
                    append("</details>")
                    synonymsGroup = false
                }
                if (synonymsHeading || (synonymsBody && !synonymsGroup)) {
                    append("<details class=\"synonyms-group\">")
                    if (!synonymsHeading) append("<summary>Synonyms</summary>")
                    synonymsGroup = true
                }
                if (!verseGroup && verseLine) append("<div class=\"verse-group\">")
                verseGroup = verseLine
                val tag = if (synonymsHeading) "summary" else "div"
                append('<').append(tag).append(" class=\"record ").append(kind.cssClass)
                if (inPurport && kind != ParagraphKind.TEXT_SECTION) append(" purport-content")
                if (bookMode && isSynonymsHeading(paragraph)) append(" book-synonyms-heading")
                // Audio launchers and Bengali stored in a private symbol-font encoding are not readable text.
                if (isAudioLauncher(paragraph)) append(" audio-link")
                if (isLegacyScript(paragraph)) append(" legacy-script")
                if (bookMode && kind == ParagraphKind.TEXT_SECTION) {
                    when (plain(paragraph.text).uppercase(Locale.ROOT)) {
                        "TRANSLATION" -> append(" book-translation-heading")
                        "PURPORT" -> append(" book-purport-heading")
                    }
                }
                // A disclosure control must stay visible even if its source also has a label style.
                if ((compactVerse || (bookMode && paragraph.style.trim() in setOf("PA_Textnum", "PA_Text_Section"))) &&
                    !synonymsHeading && isRepeatedVerseLabel(paragraph)) append(" repeated-label")
                append("\" id=\"record-").append(paragraph.record)
                append("\" data-record=\"").append(paragraph.record).append("\" dir=\"auto\">")
                val bookTitle = if (bookMode && kind in setOf(ParagraphKind.VERSE_NUMBER, ParagraphKind.VERSE_NUMBER_HEADING))
                    bookVerseTitle(paragraph) else null
                // Section labels (TEXT 1, PURPORT) carry Folio popups; render them as plain headings.
                val links = kind !in HEADING_KINDS
                if (bookTitle != null) {
                    // Keep the exact source text and record anchor; only the displayed label is normalized.
                    append("<div class=\"source-book-heading\">")
                    append(HtmlRenderer(kind, highlight = highlight, linksEnabled = links).render(paragraph.text)).append("</div>")
                    append("<div class=\"folio-block verse-number-heading\" dir=\"auto\">")
                    append(escape(bookTitle)).append("</div>")
                } else append(HtmlRenderer(kind, phrasingOnly = synonymsHeading, highlight = highlight, linksEnabled = links).render(paragraph.text))
                append("</").append(tag).append('>')
            }
            if (verseGroup) append("</div>")
            if (synonymsGroup) append("</details>")
            append("</main></body></html>")
        }
    }

    /** Only a complete, unambiguous source reference becomes a display label (any book); no inferred numbers. */
    private fun bookVerseTitle(paragraph: Paragraph): String? {
        val reference = ContentTitles.display(plain(paragraph.text))
        // The book name carries no digits or commas, so "Bg 1.16, Bg 1.18" style lists never qualify.
        val match = Regex("^([^\\d,]\\S*(?: [^\\d,]\\S*)*) ([1-9][0-9]*(?:\\.[1-9][0-9]*)*)\\.([1-9][0-9]*)(?:-([1-9][0-9]*))?$").matchEntire(reference) ?: return null
        val verse = match.groupValues[3] + match.groupValues[4].takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()
        return "TEXT $verse ( $reference )"
    }

    /** Exact source styles only; a structural or conflicting level must never be hidden. */
    private fun isSynonymsBody(paragraph: Paragraph): Boolean = when (paragraph.style.trim()) {
        "PA_Synonyms", "PA_Synonyms_Section" ->
            paragraph.level.isBlank() || paragraphKind(paragraph.level) == ParagraphKind.SYNONYMS
        else -> false
    }

    /** A body word is not a heading, and a title style cannot override a different level. */
    private fun isSynonymsHeading(paragraph: Paragraph): Boolean =
        (paragraph.level.trim() == "LE_Verse_Section" ||
            (paragraph.level.isBlank() && paragraph.style.trim() == "PA_Titles")) &&
            plain(paragraph.text).equals("SYNONYMS", ignoreCase = true)

    /** Carry purport ink across quoted verses and unstyled paragraphs, but never into the next unit. */
    private fun purportContext(paragraph: Paragraph, kind: ParagraphKind, previous: Boolean): Boolean = when (kind) {
        ParagraphKind.TEXT_SECTION -> plain(paragraph.text).equals("PURPORT", ignoreCase = true)
        ParagraphKind.PURPORT, ParagraphKind.VERSE_IN_PURPORT -> true
        ParagraphKind.CHAPTER, ParagraphKind.COLLECTION, ParagraphKind.VERSE_NUMBER,
        ParagraphKind.VERSE_NUMBER_HEADING, ParagraphKind.TRANSLATION, ParagraphKind.SYNONYMS -> false
        else -> when {
            paragraph.style.trim() == "PA_Textnum" -> false
            // Unknown structural boundaries also end the scope; verse-text levels may occur in quotations.
            paragraph.level.isNotBlank() && paragraphKind(paragraph.level) == null -> false
            else -> previous
        }
    }

    private enum class ParagraphKind(val cssClass: String) {
        TEXT("text"), PURPORT("purport"), TRANSLATION("translation"), SYNONYMS("synonyms"),
        DEVANAGARI("devanagari"), VERSE("verse"), VERSE_IN_PURPORT("verse-in-purport"),
        VERSE_LINE("verse-line"), CHAPTER("chapter"), COLLECTION("collection"),
        VERSE_NUMBER("verse-number"), VERSE_NUMBER_HEADING("verse-number-heading"), TEXT_SECTION("text-section"),
    }

    private fun paragraphKind(name: String): ParagraphKind? = when (
        name.trim().lowercase(Locale.ROOT).removePrefix("pa_").removePrefix("le_")
            .replace("_", "").replace(" ", "").replace("-", "")
    ) {
        "purport", "first", "afterverse" -> ParagraphKind.PURPORT
        "translation" -> ParagraphKind.TRANSLATION
        "synonyms", "synonymssection" -> ParagraphKind.SYNONYMS
        "devanagari", "devanagariq" -> ParagraphKind.DEVANAGARI
        "verse" -> ParagraphKind.VERSE
        "verseinpurp", "verseinpurport" -> ParagraphKind.VERSE_IN_PURPORT
        "versetext", "uvacaline" -> ParagraphKind.VERSE_LINE
        "chapter" -> ParagraphKind.CHAPTER
        "collection" -> ParagraphKind.COLLECTION
        "versenumber" -> ParagraphKind.VERSE_NUMBER
        "versenumberheading" -> ParagraphKind.VERSE_NUMBER_HEADING
        "textsection", "versesection", "titles" -> ParagraphKind.TEXT_SECTION
        else -> null
    }

    private val HEADING_KINDS = setOf(ParagraphKind.VERSE_NUMBER, ParagraphKind.VERSE_NUMBER_HEADING,
        ParagraphKind.TEXT_SECTION, ParagraphKind.CHAPTER, ParagraphKind.COLLECTION)

    /** The source's per-verse "Audio" launcher line (PA_Audio_*), whose only content is a data link. */
    private fun isAudioLauncher(paragraph: Paragraph): Boolean =
        paragraph.style.trim().startsWith("PA_Audio", ignoreCase = true)

    /** PA_Bengro stores Bengali through a private symbol font (U+F0xx code points), not Unicode text. */
    private fun isLegacyScript(paragraph: Paragraph): Boolean =
        paragraph.style.trim().equals("PA_Bengro", ignoreCase = true) &&
            plain(paragraph.text).any { it in '\uE000'..'\uF8FF' }

    /** Never infer labels from their text, or let a label style override a conflicting level. */
    private fun isRepeatedVerseLabel(paragraph: Paragraph): Boolean {
        val level = paragraphKind(paragraph.level)
        return when (paragraph.style.trim()) {
            "PA_Textnum", "LE_Verse_Number", "PA_Verse_Number_Heading" ->
                paragraph.level.isBlank() || level == ParagraphKind.VERSE_NUMBER || level == ParagraphKind.VERSE_NUMBER_HEADING
            "PA_Text_Section" -> paragraph.level.isBlank() || level == ParagraphKind.TEXT_SECTION
            else -> false
        }
    }

    private enum class Alignment(val cssClass: String) { CENTER("align-center"), RIGHT("align-right") }
    private enum class LinkKind(val path: String, val label: String) {
        QUERY("query", "Query"), JUMP("jump", "Jump"), POPUP("popup", "Popup"), AUDIO("audio", "Data"),
        SUPPRESSED("", ""),
    }

    private sealed interface Event
    private data class Text(val value: String) : Event
    private data class CharacterStyle(val name: String) : Event
    private data class ParagraphStyle(val kind: ParagraphKind?) : Event
    private data class Bold(val enabled: Boolean) : Event
    private data class Italic(val enabled: Boolean) : Event
    private data class Underline(val enabled: Boolean) : Event
    private data class Strikeout(val enabled: Boolean) : Event
    private data class Align(val alignment: Alignment) : Event
    private data class LinkStart(val kind: LinkKind, val target: String?) : Event
    private data class Image(val name: String) : Event
    private enum class Control : Event {
        IGNORE, END_STYLE, END_LINK, BREAK, SEPARATOR, TAB,
        TABLE, END_TABLE, ROW, END_ROW, CELL, END_CELL,
    }

    /** A linear, quote-aware scan: '>' inside a quoted target is data, not a tag boundary. */
    private fun visit(source: String, emit: (Event) -> Unit) {
        var offset = 0
        while (offset < source.length) {
            val start = source.indexOf('<', offset)
            if (start < 0) {
                emit(Text(source.substring(offset)))
                break
            }
            if (start > offset) emit(Text(source.substring(offset, start)))
            var end = start + 1
            var quoted = false
            while (end < source.length) {
                val character = source[end]
                if (character == '"') quoted = !quoted
                else if (!quoted && (character == '>' || character == '<')) break
                end++
            }
            if (end == source.length) {
                emit(Text(source.substring(start)))
                break
            }
            if (source[end] == '<') {
                // An incomplete tag must not swallow the following, independently valid tag.
                emit(Text(source.substring(start, end)))
                offset = end
            } else {
                emit(parseTag(source.substring(start + 1, end)) ?: Text(source.substring(start, end + 1)))
                offset = end + 1
            }
        }
    }

    private val layoutTags = setOf("IN", "PT", "FC", "FT", "FD")

    // Audited legacy syntax only. These values are validated then discarded, never copied to CSS.
    private const val LEGACY_NUMBER = "[0-9]{1,6}(?:\\.[0-9]{1,9})?"
    private const val LEGACY_RGB = "[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}"
    private val legacyNumber = Regex(LEGACY_NUMBER)
    private val legacyPoints = Regex("${LEGACY_NUMBER}p", RegexOption.IGNORE_CASE)
    private val legacyShade = Regex("(?:$LEGACY_RGB|NO|DC)", RegexOption.IGNORE_CASE)
    private val legacyNote = Regex("$LEGACY_NUMBER,$LEGACY_NUMBER,Note", RegexOption.IGNORE_CASE)
    private val legacyWidth = Regex("$LEGACY_NUMBER%?")
    private const val LEGACY_BORDER_SIDE = "(?:AL|LF|RT|TP|BT|HZ|VT):$LEGACY_NUMBER,$LEGACY_NUMBER(?:,FC:$LEGACY_RGB)?"
    private val legacyBorder = Regex("BR:$LEGACY_BORDER_SIDE(?:,$LEGACY_BORDER_SIDE){0,7}", RegexOption.IGNORE_CASE)
    private val tableLayoutField = Regex("(?:(?:HG|VG|BP|AP):$LEGACY_NUMBER|IN:LF:$LEGACY_NUMBER|JU:CN)", RegexOption.IGNORE_CASE)
    private val cellLayoutField = Regex("(?:HI:$LEGACY_NUMBER|(?:MR|MD):[0-9]{1,3}|VA:(?:TO|CN)|KT\\+)", RegexOption.IGNORE_CASE)

    /** Recognize structure, not legacy dimensions, borders, colours, spans or header styling. */
    private fun tableParameters(argument: String, table: Boolean): Boolean {
        if (argument.length !in 1..2048) return false
        val fields = argument.split(';').map(String::trim)
        if (fields.size > 16) return false
        if (table) {
            val widths = fields.first()
            if (widths.isNotEmpty()) {
                val values = widths.split(',')
                val columns = values.first().takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toInt() ?: return false
                if (values.size != columns + 1 || values.drop(1).any { !legacyWidth.matches(it) }) return false
            } else if (fields.size == 1) return false
        }
        return (if (table) fields.drop(1) else fields).all { field ->
            legacyBorder.matches(field) ||
                (field.startsWith("SD:", ignoreCase = true) && legacyShade.matches(field.substring(3))) ||
                (if (table) tableLayoutField else cellLayoutField).matches(field)
        }
    }

    private fun parseTag(body: String): Event? {
        val code = body.substringBefore(':').trim().uppercase(Locale.ROOT)
        val argument = if (':' in body) body.substringAfter(':') else null
        if (code.removePrefix("/") in layoutTags || code == "/PS") return Control.IGNORE
        if (code == "PS") return ParagraphStyle(quotedValue(argument.orEmpty())?.let(::paragraphKind))
        if (code == "CS" && argument != null) return CharacterStyle(quotedValue(argument).orEmpty())
        if (argument != null) {
            return when (code) {
                "JU" -> when (argument.trim().uppercase(Locale.ROOT)) {
                    "CN" -> Align(Alignment.CENTER)
                    "RT" -> Align(Alignment.RIGHT)
                    else -> Control.IGNORE
                }
                "QL" -> LinkStart(LinkKind.QUERY, linkTarget(argument, LinkKind.QUERY))
                "JL" -> LinkStart(LinkKind.JUMP, linkTarget(argument, LinkKind.JUMP))
                "PX" -> LinkStart(LinkKind.POPUP, linkTarget(argument, LinkKind.POPUP))
                "DL" -> LinkStart(LinkKind.AUDIO, linkTarget(argument, LinkKind.AUDIO))
                // Never retain an executable/external target, even when the argument is malformed.
                // A null scope also prevents its EL from closing an enclosing local link.
                "WW", "PL", "ML" -> LinkStart(LinkKind.SUPPRESSED, null)
                "LS", "LH" -> Control.IGNORE.takeIf { legacyNumber.matches(argument.trim()) }
                "SP", "SB" -> Control.IGNORE.takeIf { legacyPoints.matches(argument.trim()) }
                "SD" -> Control.IGNORE.takeIf { legacyShade.matches(argument.trim()) }
                "PN" -> Control.IGNORE.takeIf {
                    argument.length <= 512 && quotedValue(argument)?.let { name ->
                        name.isNotBlank() && name.none { it.isISOControl() || it == '<' || it == '>' }
                    } == true
                }
                "NT" -> Control.IGNORE.takeIf { legacyNote.matches(argument.trim()) }
                "TA" -> Control.TABLE.takeIf { tableParameters(argument, table = true) }
                "CE" -> Control.CELL.takeIf { tableParameters(argument, table = false) }
                "OB" -> objectName(argument)?.let(::Image) ?: Control.IGNORE
                else -> null
            }
        }
        return when (code) {
            "/CS" -> Control.END_STYLE
            "BD+" -> Bold(true)
            "BD", "BD-" -> Bold(false)
            "IT+" -> Italic(true)
            "IT", "IT-" -> Italic(false)
            "UN+" -> Underline(true)
            "UN", "UN-" -> Underline(false)
            "SO+" -> Strikeout(true)
            "SO", "SO-" -> Strikeout(false)
            "HS" -> Text("\u00a0")
            "SH" -> Text("\u00ad")
            // Hidden/presentation/note bodies remain readable; only their markers are metadata.
            "HD", "HD+", "HD-", "/PN", "/NT", "/SS", "BUILDVIEW" -> Control.IGNORE
            "CR" -> Control.BREAK
            "HR" -> Control.SEPARATOR
            "TB" -> Control.TAB
            "EL" -> Control.END_LINK
            // Parameterized openings above use the same bare, balanced structural output.
            "TA" -> Control.TABLE
            "/TA" -> Control.END_TABLE
            "RO", "RO,HE" -> Control.ROW
            "/RO" -> Control.END_ROW
            "CE" -> Control.CELL
            "/CE" -> Control.END_CELL
            else -> null
        }
    }

    private data class Quoted(val value: String, val next: Int)

    /** Quoted fields may contain delimiters and doubled quotes; backslash escapes are not guessed. */
    private fun readQuoted(source: String, start: Int = 0): Quoted? {
        var offset = start
        while (offset < source.length && source[offset].isWhitespace()) offset++
        if (offset >= source.length || source[offset] != '"') return null
        offset++
        val value = StringBuilder()
        while (offset < source.length) {
            val character = source[offset++]
            if (character != '"') value.append(character)
            else if (offset < source.length && source[offset] == '"') {
                value.append('"')
                offset++
            } else return Quoted(value.toString(), offset)
        }
        return null
    }

    private fun quotedValue(source: String): String? {
        val field = readQuoted(source) ?: return null
        return field.value.takeIf { source.substring(field.next).isBlank() }
    }

    private fun linkTarget(argument: String, kind: LinkKind): String? {
        val quotedKind = readQuoted(argument)
        var comma = quotedKind?.next ?: argument.indexOf(',')
        if (quotedKind != null) while (comma < argument.length && argument[comma].isWhitespace()) comma++
        if (comma !in argument.indices || argument[comma] != ',') return null
        val label = quotedKind?.value ?: argument.substring(0, comma).trim()
        if (!label.equals(kind.label, ignoreCase = true)) return null
        return quotedValue(argument.substring(comma + 1))?.takeIf { it.isNotEmpty() }
    }

    private fun objectName(argument: String): String? {
        val type = readQuoted(argument) ?: return null
        if (type.value != "OB") return null
        var offset = type.next
        while (offset < argument.length && argument[offset].isWhitespace()) offset++
        if (offset >= argument.length || argument[offset] != ';') return null
        val name = readQuoted(argument, offset + 1) ?: return null
        val tail = argument.substring(name.next).trim()
        // Dimensions and other legacy layout fields are intentionally not interpreted.
        return name.value.takeIf { it.isNotEmpty() && (tail.isEmpty() || tail.startsWith(';')) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            append(when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character.toString()
            })
        }
    }

    private val highlightWords = Regex("[\\p{L}\\p{N}\\p{M}]+")

    /** Same folding and whole-word rule as the search engine, so marks match the reported hits. */
    private fun marked(value: String, highlight: Set<String>): String = buildString(value.length + 32) {
        var last = 0
        for (match in highlightWords.findAll(value)) {
            if (SearchText.normalize(match.value) !in highlight) continue
            append(escape(value.substring(last, match.range.first)))
            append("<mark class=\"search-hit\">").append(escape(match.value)).append("</mark>")
            last = match.range.last + 1
        }
        append(escape(value.substring(last)))
    }

    // Hard spaces are content, including at record edges and before an implicit table cell.
    private fun isLayoutWhitespace(character: Char): Boolean = character.isWhitespace() && character != '\u00a0'

    private class TableState(var rowOpen: Boolean = false, var cellOpen: Boolean = false, var cells: Int = 0)
    private data class InlineStyle(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikeout: Boolean = false,
    )
    private data class InlineTag(val name: String, val href: String? = null)

    private class HtmlRenderer(initialKind: ParagraphKind, private val phrasingOnly: Boolean = false,
        private val highlight: Set<String> = emptySet(), private val linksEnabled: Boolean = true) {
        private val output = StringBuilder()
        private val tables = mutableListOf<TableState>()
        private val styles = mutableListOf<InlineStyle>()
        private val links = mutableListOf<String?>()
        private val openInline = mutableListOf<InlineTag>()
        private var style = InlineStyle()
        private var kind = initialKind
        private var alignment: Alignment? = null
        private var blockOpen = false
        private val blockTag = if (phrasingOnly) "span" else "div"

        fun render(source: String): String {
            visit(source, ::accept)
            closeBlock()
            while (tables.isNotEmpty()) endTable()
            return output.toString()
        }

        private fun accept(event: Event) {
            // A native summary accepts phrasing content, not divs/tables. Only layout is
            // flattened in its one-label heading; text, inline styling and safe links survive.
            if (phrasingOnly) {
                when (event) {
                    Control.SEPARATOR, Control.TABLE, Control.END_TABLE,
                    Control.ROW, Control.END_ROW, Control.CELL, Control.END_CELL -> {
                        closeBlock()
                        return
                    }
                    else -> Unit
                }
            }
            when (event) {
                is Text -> {
                    if (tables.lastOrNull()?.cellOpen == false && event.value.all(::isLayoutWhitespace)) return
                    inline()
                    output.append(if (highlight.isEmpty()) escape(event.value) else marked(event.value, highlight))
                }
                is CharacterStyle -> {
                    styles.add(style)
                    style = style.copy(
                        bold = style.bold || event.name.contains("bold", ignoreCase = true),
                        italic = style.italic || event.name.contains("italic", ignoreCase = true),
                        underline = style.underline || event.name.contains("underline", ignoreCase = true),
                        strikeout = style.strikeout || event.name.contains("strikeout", ignoreCase = true),
                    )
                }
                is ParagraphStyle -> if (event.kind != null && kind != event.kind) {
                    closeBlock()
                    kind = event.kind
                }
                is Bold -> style = style.copy(bold = event.enabled)
                is Italic -> style = style.copy(italic = event.enabled)
                is Underline -> style = style.copy(underline = event.enabled)
                is Strikeout -> style = style.copy(strikeout = event.enabled)
                is Align -> if (alignment != event.alignment) {
                    closeBlock()
                    alignment = event.alignment
                }
                // A malformed nested link still occupies a scope, so EL cannot close its parent.
                is LinkStart -> links.add(event.target?.takeIf { linksEnabled }?.let { "${BASE}links/${event.kind.path}?target=${encode(it)}" })
                is Image -> {
                    inline()
                    output.append("<img src=\"").append(escape("${BASE}objects/${encode(event.name)}"))
                    output.append("\" alt=\"").append(escape(event.name)).append("\" loading=\"lazy\" decoding=\"async\">")
                }
                is Control -> when (event) {
                    Control.IGNORE -> Unit
                    Control.END_STYLE -> if (styles.isNotEmpty()) style = styles.removeAt(styles.lastIndex)
                    Control.END_LINK -> if (links.isNotEmpty()) links.removeAt(links.lastIndex)
                    Control.BREAK -> { inline(); output.append("<br>") }
                    Control.TAB -> { inline(); output.append("&#9;") }
                    Control.SEPARATOR -> { ensureCell(); closeBlock(); output.append("<hr>") }
                    Control.TABLE -> {
                        ensureCell()
                        closeBlock()
                        output.append("<div class=\"folio-table\"><table><tbody>")
                        tables.add(TableState())
                    }
                    Control.END_TABLE -> endTable()
                    Control.ROW -> startRow()
                    Control.END_ROW -> endRow()
                    Control.CELL -> startCell()
                    Control.END_CELL -> endCell()
                }
            }
        }

        /** Reconcile semantic state instead of emitting possibly crossed tags or nested anchors. */
        private fun inline() {
            ensureCell()
            if (!blockOpen) {
                output.append('<').append(blockTag).append(" class=\"folio-block ").append(kind.cssClass)
                alignment?.let { output.append(' ').append(it.cssClass) }
                output.append("\" dir=\"auto\">")
                blockOpen = true
            }
            val wanted = buildList {
                links.lastOrNull()?.let { add(InlineTag("a", it)) }
                if (style.bold) add(InlineTag("strong"))
                if (style.italic) add(InlineTag("em"))
                if (style.underline) add(InlineTag("u"))
                if (style.strikeout) add(InlineTag("s"))
            }
            var common = 0
            while (common < openInline.size && common < wanted.size && openInline[common] == wanted[common]) common++
            while (openInline.size > common) closeInlineTag()
            for (index in common until wanted.size) {
                val tag = wanted[index]
                output.append('<').append(tag.name)
                tag.href?.let { output.append(" href=\"").append(escape(it)).append('"') }
                output.append('>')
                openInline.add(tag)
            }
        }

        private fun closeInlineTag() {
            output.append("</").append(openInline.removeAt(openInline.lastIndex).name).append('>')
        }

        private fun closeBlock() {
            while (openInline.isNotEmpty()) closeInlineTag()
            if (blockOpen) output.append("</").append(blockTag).append('>')
            blockOpen = false
        }

        private fun ensureCell() {
            val table = tables.lastOrNull() ?: return
            if (!table.rowOpen) startRow()
            if (!table.cellOpen) startCell()
        }

        private fun startRow() {
            val table = tables.lastOrNull() ?: return
            endRow()
            output.append("<tr>")
            table.rowOpen = true
        }

        private fun endRow() {
            val table = tables.lastOrNull() ?: return
            endCell()
            if (table.rowOpen) output.append("</tr>")
            table.rowOpen = false
        }

        private fun startCell() {
            val table = tables.lastOrNull() ?: return
            if (!table.rowOpen) startRow()
            endCell()
            output.append("<td>")
            table.cellOpen = true
        }

        private fun endCell() {
            val table = tables.lastOrNull() ?: return
            closeBlock()
            if (table.cellOpen) output.append("</td>")
            table.cellOpen = false
        }

        private fun endTable() {
            if (tables.isEmpty()) return
            endRow()
            output.append("</tbody></table></div>")
            tables.removeAt(tables.lastIndex)
        }
    }

    private class PlainRenderer {
        private val output = StringBuilder()
        private val tables = mutableListOf<TableState>()

        fun render(source: String): String {
            visit(source, ::accept)
            return output.toString().trim(::isLayoutWhitespace)
        }

        private fun accept(event: Event) {
            when (event) {
                is Text -> {
                    if (tables.lastOrNull()?.cellOpen == false && event.value.all(::isLayoutWhitespace)) return
                    ensureCell()
                    output.append(event.value)
                }
                Control.BREAK -> { ensureCell(); output.append('\n') }
                Control.SEPARATOR -> { ensureCell(); output.append("\n\n") }
                Control.TAB -> { ensureCell(); output.append('\t') }
                Control.TABLE -> { ensureCell(); newline(); tables.add(TableState()) }
                Control.END_TABLE -> if (tables.isNotEmpty()) { newline(); tables.removeAt(tables.lastIndex) }
                Control.ROW -> tables.lastOrNull()?.let(::startRow)
                Control.END_ROW -> tables.lastOrNull()?.let { newline(); it.rowOpen = false; it.cellOpen = false }
                Control.CELL -> tables.lastOrNull()?.let(::startCell)
                Control.END_CELL -> tables.lastOrNull()?.let { it.cellOpen = false }
                else -> Unit // Formatting, link targets, and object filenames are not snippet text.
            }
        }

        private fun newline() {
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
        }

        private fun startRow(table: TableState) {
            newline()
            table.rowOpen = true
            table.cellOpen = false
            table.cells = 0
        }

        private fun startCell(table: TableState) {
            if (!table.rowOpen) startRow(table)
            if (table.cells > 0) output.append('\t')
            table.cells++
            table.cellOpen = true
        }

        private fun ensureCell() {
            val table = tables.lastOrNull() ?: return
            if (!table.cellOpen) startCell(table)
        }
    }

    private data class Palette(
        val background: String,
        val text: String,
        val link: String,
        val rule: String,
        val accent: String,
        val muted: String,
        val scheme: String,
        val hit: String,
    )

    /** Root canvas texture: covers every column and scrolls on the compositor. A fixed body
     * attachment forces main-thread scrolling in Chromium, which judders during page turns. */
    private fun paperTextureCss(): String = """
        html:has(body.book-reading) {
            background-image: var(--reader-texture);
            background-size: 31px 29px, 37px 41px, 23px 43px, 100vw 100vh;
        }
    """.trimIndent()

    /** Full-viewport column pitch, with modest internal gutters that do not change page offsets. */
    private fun bookCss(preferences: ReaderPreferences): String = """
        html:has(body.book-reading) { height: 100%; }
        body.book-reading { height: 100vh; direction: ltr;
            font-size: ${preferences.fontSize.coerceIn(14, 30)}px;
            line-height: calc(${ReaderStyle.BODY_LINE_HEIGHT} * var(--reader-line-factor)); -webkit-text-size-adjust: 100%; }
        .book-flow.reader { width: 100vw; max-width: none;
            height: calc(100vh - 32px); margin: 16px 0; padding: 0;
            column-count: 1; column-gap: 0; column-fill: auto; overflow: visible;
            text-align: justify; widows: 1; orphans: 1; }
        .book-flow > .record, .book-flow > .verse-group { padding-inline: 16px; }
        .book-flow .record, .book-flow .folio-block, .book-flow .verse-group {
            break-inside: auto; widows: 1; orphans: 1; }
        .book-flow .record.book-synonyms-heading { display: none; }
        .book-flow .record.repeated-label { display: none; }
        .book-flow .source-book-heading { display: none; }
        .book-flow .folio-block.verse, .book-flow .folio-block.verse-line,
        .book-flow .folio-block.verse-in-purport { color: inherit; }
        .book-flow .folio-block.text-section { color: inherit; font-size: 1em; letter-spacing: normal; font-weight: 400; }
        .book-flow .book-translation-heading .folio-block { color: inherit; font-size: 1em; font-weight: 700; }
        .book-flow .book-purport-heading .folio-block { color: inherit; font-size: 1em; font-weight: 400; }
        .book-flow .record.verse-number, .book-flow .record.verse-number-heading,
        .book-flow .record.text-section { break-after: avoid; }
        .book-flow img { max-height: calc(100vh - 48px); object-fit: contain; }
        .book-flow .folio-table { max-height: calc(100vh - 48px); overflow: auto; }
    """.trimIndent()

    /** Same typography/source markup as paged books; only the flow axis and viewport sizing differ. */
    private fun scrollingBookCss(): String = """
        html:has(body.book-scrolling) { height: auto; }
        body.book-reading.book-scrolling { height: auto; }
        .book-scrolling .book-flow.reader { width: 100%; height: auto; column-count: auto;
            margin: 0; padding-block: 16px; }
    """.trimIndent()

    /** Live documents follow the paper through a variable; JS-off documents pin the paper's shade. */
    private fun highlightBackground(name: String, live: Boolean, dark: Boolean): String =
        if (live) "var(--reader-hl-$name)" else (if (dark) ReaderStyle.DARK_HIGHLIGHTS else ReaderStyle.LIGHT_HIGHLIGHTS).getValue(name)

    private fun css(preferences: ReaderPreferences, live: Boolean): String {
        val colors = ReaderPaperAppearance.palette(preferences.paper)
        val palette = if (live) Palette("var(--reader-paper)", "var(--reader-ink)", "var(--reader-link)",
            "var(--reader-rule)", "var(--reader-accent)", "var(--reader-muted)", "var(--reader-scheme)", "var(--reader-hit)")
        else Palette(colors.Paper, colors.Ink, colors.Link, colors.Rule, colors.Accent, colors.Muted,
            if (colors.dark) "dark" else "light", colors.Hit)
        fun lineHeight(base: String) = when {
            live -> "calc($base * var(--reader-line-factor))"
            preferences.lineSpacing == 100 -> base // Preserve default legacy (JS-off) stylesheet.
            else -> "calc($base * ${preferences.lineSpacing.coerceIn(85, 150)} / 100)"
        }
        return """
            ${ReferenceTypography.CSS}
            :root { color-scheme: ${palette.scheme}; background: ${palette.background}; }
            * { box-sizing: border-box; }
            body {
                margin: 0;
                background: ${palette.background};
                color: ${palette.text};
                font-family: ${ReferenceTypography.BODY_STACK};
                font-size: ${preferences.fontSize.coerceIn(14, 30)}px;
                line-height: ${lineHeight(ReaderStyle.BODY_LINE_HEIGHT)};
            }
            .reader { width: 100%; max-width: none; margin-inline: 0; padding: .65rem 16px 1.25rem; }
            .record { margin-block: 0 .7em; }
            .folio-block { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; tab-size: 4; }
            .folio-block + .folio-block { margin-block-start: .55em; }
            .folio-block.text, .folio-block.purport, .folio-block.translation, .folio-block.synonyms {
                text-align: justify; text-align-last: start; text-justify: inter-word; }
            .folio-block.translation { font-weight: 700; }
            .folio-block.synonyms { color: ${palette.text}; font-size: .96em; }
            .synonyms-group { margin-block: .8em; padding-block: .35em; border-block: 1px solid ${palette.rule}; }
            .synonyms-group > summary { margin: 0; padding-block: .35em; color: ${palette.link}; font-weight: 500; cursor: pointer;
                text-align: center; list-style-position: inside; }
            .synonyms-group > summary .folio-block { color: inherit; font-size: 1em; font-weight: inherit; letter-spacing: normal; }
            .synonyms-group > summary:focus-visible { outline: 2px solid currentColor; outline-offset: .15em; }
            .synonyms-group[open] > summary { margin-block-end: .55em; }
            .folio-block.devanagari {
                text-align: center; color: ${palette.text};
                font-family: ${ReferenceTypography.DEVANAGARI_STACK};
                font-size: 1.15em; line-height: ${lineHeight(ReaderStyle.DEVANAGARI_LINE_HEIGHT)}; font-style: normal; font-weight: 600;
            }
            .folio-block.devanagari em { font-style: normal; }
            .folio-block.verse, .folio-block.verse-in-purport, .folio-block.verse-line { text-align: center; font-style: italic; color: ${palette.accent}; }
            .folio-block.verse-in-purport { font-size: .98em; }
            .verse-group { margin-block: .55em .8em; }
            .verse-group > .record { margin-block: .12em; }
            .verse-group .folio-block { margin-block: 0; }
            .record.chapter, .record.collection { margin-block: .65em .6em; }
            .folio-block.chapter, .folio-block.collection { text-align: center; background: ${palette.rule}; font-size: 1.25em; font-weight: 700; line-height: ${lineHeight(ReaderStyle.CHAPTER_LINE_HEIGHT)}; }
            .record.verse-number, .record.verse-number-heading { margin-block: .65em .45em; }
            .folio-block.verse-number, .folio-block.verse-number-heading, .folio-block.text-section { text-align: center; font-weight: 700; }
            .record.text-section { margin-block: .8em .35em; }
            .folio-block.text-section { color: ${palette.muted}; font-size: .75em; letter-spacing: .08em; }
            .record:empty { margin: 0; }
            .verse-reading .record.repeated-label { display: none; }
            .record.audio-link, .record.legacy-script { display: none; }
            mark.user-hl { color: inherit; padding: 0; margin: 0; border-radius: .15em; box-decoration-break: clone; -webkit-box-decoration-break: clone; }
            mark.user-hl.hl-yellow { --annotation-fill: ${highlightBackground("yellow", live, colors.dark)}; background: var(--annotation-fill); }
            mark.user-hl.hl-green { --annotation-fill: ${highlightBackground("green", live, colors.dark)}; background: var(--annotation-fill); }
            mark.user-hl.hl-blue { --annotation-fill: ${highlightBackground("blue", live, colors.dark)}; background: var(--annotation-fill); }
            mark.user-hl.hl-pink { --annotation-fill: ${highlightBackground("pink", live, colors.dark)}; background: var(--annotation-fill); }
            mark.user-hl.note-only { background: transparent !important; }
            mark.user-hl.has-note { text-decoration-line: underline; text-decoration-style: solid;
                text-decoration-thickness: 2px; text-underline-offset: .18em;
                text-decoration-color: ${palette.accent};
                text-decoration-color: color-mix(in srgb, var(--annotation-fill) 45%, ${palette.text}); }
            .folio-block.align-center { text-align: center; text-align-last: auto; }
            .folio-block.align-right { text-align: right; text-align-last: auto; }
            a { color: ${palette.link}; text-decoration-color: ${palette.rule}; text-decoration-thickness: 1px; text-underline-offset: .18em; overflow-wrap: anywhere; }
            mark.search-hit { background: ${palette.hit}; color: inherit; border-radius: 2px; }
            .record.purport-content, .record.purport-content *,
            .folio-block.purport, .folio-block.purport *,
            .folio-block.verse-in-purport, .folio-block.verse-in-purport * { color: ${palette.text}; }
            a:focus-visible { outline: 2px solid currentColor; outline-offset: .15em; }
            img { display: inline-block; max-width: 100%; height: auto; vertical-align: middle; }
            hr { border: 0; border-block-start: 1px solid ${palette.rule}; margin-block: 1.25em; }
            .folio-table { max-width: 100%; overflow-x: auto; margin-block: 1em; }
            table { width: 100%; border-collapse: collapse; }
            td { border: 1px solid ${palette.rule}; padding: .5em .7em; vertical-align: top; }
        """.trimIndent()
    }
}