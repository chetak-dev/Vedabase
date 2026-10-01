package org.vedareader.data

import java.text.BreakIterator
import java.util.Locale

/** Pure search rules. Offsets always address the original, unnormalised UTF-16 string. */
object SearchText {
    const val MAX_QUERY_CHARACTERS = 160
    const val MAX_QUERY_WORDS = 8
    private val words = Regex("[\\p{L}\\p{N}\\p{M}]+")
    private val spaces = Regex("\\s+")
    private val referencePattern = Regex(
        "(?<![\\p{L}\\p{N}.])(?:(bg|sb)\\s+|(?:cc\\s+)?(adi|madhya|antya)\\s+)" +
            "([1-9][0-9]*(?:\\.[1-9][0-9]*){1,2})(?:-([1-9][0-9]*))?(?![\\p{L}\\p{N}.\\-])",
    )
    private val referenceNames = listOf("bg", "sb", "cc", "cc adi", "cc madhya", "cc antya", "adi", "madhya", "antya")
    private val verseKinds = setOf("versetext", "verse", "uvacaline", "devanagari", "devanagariq", "bengro")
    private val nonVerseKinds = setOf("purport", "first", "afterverse", "translation", "synonyms", "synonymssection",
        "chapter", "collection", "versenumber", "versenumberheading", "textsection", "versesection", "titles")
    private val referenceNumberPrefix = Regex("[0-9]+(?:\\.[0-9]*){0,2}(?:-[0-9]*)?")

    data class Token(val value: String, val start: Int, val endExclusive: Int)
    /** One typed word as looked up: any of [forms] (folded spellings), or any index word starting with [prefix]. */
    data class Term(val forms: List<String>, val prefix: String? = null) {
        val typed: String get() = forms.first()
        fun accepts(word: String): Boolean = word in forms || (prefix != null && word.startsWith(prefix))
    }
    data class Query(val terms: List<String>, val titleKey: String, val reference: Reference?,
        val groups: List<Term> = terms.map { Term(listOf(it)) }) {
        /** Cache identity of what is actually looked up, including variants and prefixes. */
        val signature: List<String> get() = groups.map { it.forms.joinToString("|") + (it.prefix?.let { "*" } ?: "") }
    }
    data class Snippet(val text: String, val highlights: List<SearchHighlight>)
    data class Reference(val stem: String, val first: Int, val last: Int) {
        val key: String get() = stem + first + if (first == last) "" else "-$last"
        // The supplied corpus spells CC simpletitle as "madhya 1.1", not "cc madhya 1.1".
        val lookupStems: List<String> get() = aliases(stem)
        fun queryKey(typedKey: String): String = if (stem.startsWith("cc ") && typedKey != "cc" && !typedKey.startsWith("cc "))
            key.removePrefix("cc ") else key
    }

    fun normalize(value: String): String = WordIndex.normalize(value)

    /** Unlike WordIndex.simpleTitle, dots in numeric references are significant. */
    fun titleKey(value: String): String = normalize(value)
        .replace('–', '-').replace('—', '-')
        .replace(spaces, " ").trim()
        .replace(Regex("\\s*([.:-])\\s*"), "$1")
        .replace(Regex("^(bg|sb)\\.?\\s*(?=\\d)"), "$1 ")
        .replace(Regex("^cc\\.?\\s*(?=[a-z])"), "cc ")
        .replace(Regex("\\b(adi|madhya|antya)\\s*(?=\\d)"), "$1 ")

    fun tokens(value: String): List<Token> = words.findAll(value).mapNotNull { match ->
        val folded = normalize(match.value)
        if (folded.codePoints().anyMatch { Character.isLetterOrDigit(it) }) {
            Token(folded, match.range.first, match.range.last + 1)
        } else null
    }.toList()

    fun validate(value: String, target: SearchTarget = SearchTarget.ALL_TEXT, allowPrefix: Boolean = false): Query {
        require(value.length <= MAX_QUERY_CHARACTERS) { "Use at most 160 characters" }
        val tokens = tokens(value)
        val terms = tokens.map { it.value }
        // Count BEFORE deduplication: nine repetitions are not an eight-word query.
        require(terms.isNotEmpty() && terms.size <= MAX_QUERY_WORDS) { "Use one to eight words" }
        val key = titleKey(value)
        val reference = references(key).singleOrNull()?.takeIf { it.queryKey(key) == key }
        require(target != SearchTarget.VERSE_REFERENCE || reference != null || (allowPrefix && isReferencePrefix(key))) {
            "Use a complete BG, SB, or CC verse reference"
        }
        val groups = tokens.map { token ->
            val wildcard = token.endExclusive < value.length && value[token.endExclusive] == '*'
            if (wildcard) {
                require(token.value.length >= MIN_PREFIX_CHARACTERS) { "Type at least three letters before *" }
                Term(listOf(token.value), prefix = token.value)
            } else Term(spellingVariants(token.value))
        }
        return Query(terms, key, reference, groups)
    }

    /** Folded whole words to mark in an opened passage. A verse reference or invalid query marks nothing. */
    fun highlightTerms(query: String?, target: SearchTarget = SearchTarget.ALL_TEXT): Set<String> {
        val value = query?.trim().orEmpty()
        if (value.isEmpty()) return emptySet()
        val parsed = try { validate(value, target) } catch (_: IllegalArgumentException) { return emptySet() }
        return if (parsed.reference != null) emptySet() else parsed.groups.flatMap { it.forms }.toSet()
    }

    /**
     * Popular English spellings folded to the index's accent-stripped IAST (Krishna -> krsna, Chaitanya -> caitanya,
     * Vishwa -> visva, Arjun -> arjuna). The typed form always comes first; Indic script words are left alone.
     */
    fun spellingVariants(folded: String): List<String> {
        // Short English function words (and, too, she) would otherwise alias real Sanskrit words (anda, tu, se).
        if (folded.length < 4 || !folded.all { it in 'a'..'z' }) return listOf(folded)
        val variants = linkedSetOf(folded)
        fun grow(seed: String) {
            if (variants.size >= MAX_VARIANTS) return
            for ((from, to) in respellings) {
                if (from !in seed) continue
                val next = seed.replace(from, to)
                if (next.length >= 2 && variants.add(next)) grow(next)
                if (variants.size >= MAX_VARIANTS) return
            }
        }
        grow(folded)
        // Hindi-style dropped final "a": arjun -> arjuna, sankirtan -> sankirtana.
        for (variant in variants.toList()) {
            if (variants.size >= MAX_VARIANTS) break
            if (variant.last() !in "aeiou") variants.add(variant + "a")
        }
        return variants.toList()
    }

    private const val MIN_PREFIX_CHARACTERS = 3
    private const val MAX_VARIANTS = 12
    private val respellings = listOf(
        "chh" to "ch", "ch" to "c", "sh" to "s", "ri" to "r", "ee" to "i", "oo" to "u", "aa" to "a", "w" to "v",
    )

    fun references(value: String): List<Reference> = referencePattern.findAll(titleKey(value)).mapNotNull { match ->
        val book = match.groupValues[1].ifEmpty { "cc" }
        val part = match.groupValues[2]
        val numbers = match.groupValues[3].split('.')
        if ((book == "sb" && numbers.size != 3) ||
            (book != "sb" && numbers.size != 2) || (book == "cc") != part.isNotEmpty()
        ) return@mapNotNull null
        if (numbers.any { it.toIntOrNull() == null || it.toInt() < 1 }) return@mapNotNull null
        val first = numbers.last().toInt()
        val last = match.groupValues[4].ifEmpty { numbers.last() }.toIntOrNull() ?: return@mapNotNull null
        if (first < 1 || last < first) return@mapNotNull null
        Reference("$book " + if (part.isEmpty()) "" else "$part ", first, last).let {
            it.copy(stem = it.stem + numbers.dropLast(1).joinToString(".") + ".")
        }
    }.toList()

    fun referenceMatches(title: String, query: Reference): Boolean {
        var next = query.first.toLong()
        for (part in references(title).filter { it.stem == query.stem }.sortedBy { it.first }) {
            if (part.last < next) continue
            if (part.first > next) return false
            next = part.last.toLong() + 1
            if (next > query.last) return true
        }
        return false
    }

    /** Only lexical reference prefixes qualify; quoted references and prose never do. */
    fun isReferencePrefix(value: String): Boolean {
        val key = titleKey(value)
        if (key.isEmpty()) return false
        return referenceNames.any { name -> name.startsWith(key) ||
            (name != "cc" && key.startsWith("$name ") &&
                referenceNumberPrefix.matches(key.removePrefix("$name "))) }
    }

    fun referencePrefixes(query: Query): List<String> =
        (aliases(query.titleKey) + query.reference?.lookupStems.orEmpty()).distinct()

    private fun aliases(key: String): List<String> = when {
        key == "cc" -> listOf("adi ", "madhya ", "antya ", "cc ")
        key.startsWith("cc ") -> listOf(key.removePrefix("cc "), key)
        listOf("adi", "madhya", "antya").any { it.startsWith(key) || key.startsWith("$it ") } ->
            listOf(key, "cc $key")
        else -> listOf(key)
    }

    /** Bounded range expansion; a typed late member is included without walking a huge range. */
    fun referenceSuggestions(title: String, query: Query, limit: Int = 8): List<String> = buildList {
        if (limit <= 0) return@buildList
        query.reference?.takeIf { referenceMatches(title, it) }?.let {
            val key = it.queryKey(query.titleKey)
            if (key.startsWith(query.titleKey)) add(key)
        }
        for (reference in references(title)) {
            if (size >= limit) break
            val numbers = linkedSetOf(reference.first, reference.last)
            for (delta in 0 until limit) {
                val number = reference.first.toLong() + delta
                if (number <= reference.last) numbers.add(number.toInt())
            }
            for (member in listOf(reference) + numbers.map { reference.copy(first = it, last = it) }) {
                val key = member.queryKey(query.titleKey)
                if (key.startsWith(query.titleKey) && key !in this && size < limit) add(key)
            }
        }
    }

    /** Mirrors Folio's semantic precedence, with the corpus's Bengali roman style included.
     * A positive style still needs containing-main-verse context; LE_Verse_Text also occurs in quotes.
     */
    fun isVerseWordRecord(level: String, style: String): Boolean {
        fun kind(value: String) = value.trim().lowercase(Locale.ROOT).removePrefix("pa_").removePrefix("le_")
            .replace("_", "").replace(" ", "").replace("-", "")
        val semantic = kind(level)
        val paragraph = kind(style)
        if (paragraph in setOf("verseinpurp", "verseinpurport")) return false
        return when {
            semantic in nonVerseKinds -> false
            semantic in verseKinds -> true
            semantic.isNotEmpty() -> false // Unknown structural levels must not widen the target.
            else -> paragraph in verseKinds
        }
    }

    /** null means no match; an exact phrase uses ordered tokens, including repetitions. */
    fun matches(plain: String, query: Query, mode: SearchMode): List<SearchHighlight>? {
        val source = tokens(plain)
        val groups = query.groups
        if (mode == SearchMode.EXACT_PHRASE) {
            val first = (0..(source.size - groups.size)).firstOrNull { offset ->
                groups.indices.all { groups[it].accepts(source[offset + it].value) }
            } ?: return null
            return source.subList(first, first + groups.size).map { SearchHighlight(it.start, it.endExclusive) }
        }
        val found = source.filter { token -> groups.any { it.accepts(token.value) } }
        if (groups.any { group -> found.none { group.accepts(it.value) } }) return null
        return found.map { SearchHighlight(it.start, it.endExclusive) }
    }

    /** A contiguous source slice, not a reconstructed/normalised passage. */
    fun snippet(plain: String, matches: List<SearchHighlight> = emptyList(), maxCharacters: Int = 260): Snippet {
        require(maxCharacters > 0)
        require(matches.all { it.start >= 0 && it.endExclusive > it.start && it.endExclusive <= plain.length })
        if (plain.isEmpty()) return Snippet("", emptyList())
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(plain) }
        fun safe(index: Int): Boolean {
            if (index == 0 || index == plain.length) return true
            if (Character.isLowSurrogate(plain[index]) && Character.isHighSurrogate(plain[index - 1])) return false
            val kind = Character.getType(plain.codePointAt(index))
            return kind != Character.NON_SPACING_MARK.toInt() &&
                kind != Character.COMBINING_SPACING_MARK.toInt() && kind != Character.ENCLOSING_MARK.toInt()
        }
        fun forward(index: Int): Int {
            var result = index.coerceIn(0, plain.length)
            while (!boundaries.isBoundary(result) || !safe(result)) result++
            return result
        }
        fun backward(index: Int): Int {
            var result = index.coerceIn(0, plain.length)
            while (!boundaries.isBoundary(result) || !safe(result)) result--
            return result
        }
        val focus = matches.firstOrNull()?.start ?: 0
        val desired = (focus - maxCharacters / 3).coerceAtLeast(0)
            .coerceAtMost((plain.length - maxCharacters).coerceAtLeast(0))
        val start = forward(desired)
        val end = backward((start.toLong() + maxCharacters).coerceAtMost(plain.length.toLong()).toInt())
            .coerceAtLeast(start)
        val visible = matches.filter {
            it.start >= start && it.endExclusive <= end &&
                boundaries.isBoundary(it.start) && boundaries.isBoundary(it.endExclusive) &&
                safe(it.start) && safe(it.endExclusive)
        }.map { SearchHighlight(it.start - start, it.endExclusive - start) }
        return Snippet(plain.substring(start, end), visible)
    }

    /** Exclusive lexical bound for SQLite's default BINARY index; no LIKE metacharacters. */
    fun prefixEnd(prefix: String): String {
        require(prefix.isNotEmpty())
        val points = prefix.codePoints().toArray()
        for (index in points.indices.reversed()) {
            if (points[index] < Character.MAX_CODE_POINT) {
                points[index]++
                if (points[index] in 0xD800..0xDFFF) points[index] = 0xE000
                return String(points, 0, index + 1)
            }
        }
        throw IllegalArgumentException("Unsupported prefix")
    }
}