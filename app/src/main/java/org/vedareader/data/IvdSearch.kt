package org.vedareader.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.vedareader.reader.FolioText
import java.util.BitSet
import kotlin.coroutines.CoroutineContext

/** Read-only, per-library engine. Only immutable-by-ownership candidate snapshots are cached. */
class IvdSearch(private val db: SQLiteDatabase, private val libraryId: String) : AutoCloseable {
    private val serial = Mutex()
    private val lifecycle = Any()
    private var closed = false
    private val activeSignals = mutableSetOf<CancellationSignal>()
    private var catalog: List<SearchBook>? = null
    private var levels: Map<Int, String>? = null
    private var levelNames: Map<String, Int>? = null
    private var bounds: IntRange? = null
    private val candidates = LinkedHashMap<Key, Candidates>(4, 0.75f, true)
    private val suggestions = LinkedHashMap<SuggestionKey, List<SearchSuggestion>>(16, 0.75f, true)

    /** Per-read counters contain no query or source text. Elapsed time includes queueing and I/O. */
    data class ReadMetrics(
        val elapsedMillis: Long,
        val sqlQueries: Int,
        val postingMetadataQueries: Int,
        val postingBytes: Long,
        val plainTextReads: Int,
        val verseMetadataQueries: Int,
        val referenceRows: Int,
        val suggestionCacheHit: Boolean,
    )
    @Volatile var lastReadMetrics: ReadMetrics? = null
        private set

    private data class Key(val terms: List<String>, val title: String, val books: List<Int>, val mode: SearchMode,
        val target: SearchTarget)
    private data class SuggestionKey(val query: String, val books: List<Int>, val target: SearchTarget, val mode: SearchMode?)
    private data class Candidates(val records: BitSet, val titles: BitSet) {
        val bytes: Long get() = (records.size().toLong() + titles.size()) / 8
    }
    private data class Posting(val row: Long, val base: Int, val bytes: Int)
    private data class Heading(val node: ContentNode, val semantic: String)
    private data class Scope(val ranges: List<IntRange>?, val source: IntRange) {
        fun contains(record: Int): Boolean {
            if (record !in source) return false
            val selected = ranges ?: return true
            var low = 0
            var high = selected.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val range = selected[middle]
                when {
                    record < range.first -> high = middle - 1
                    record > range.last -> low = middle + 1
                    else -> return true
                }
            }
            return false
        }
    }

    suspend fun searchBooks(): List<SearchBook> = read { books().toList() }

    /**
     * Resolve a Folio link from the document at [near] to a reading location, popup or search.
     * Candidates inside the same work as the tapped page win; otherwise the earliest record does.
     */
    suspend fun resolveLink(link: ReaderLink, near: Int): LinkResolution {
        require(link.target.length in 1..LinkQuery.MAX_TARGET_CHARACTERS) { "Invalid link target" }
        return read(timeoutMillis = LINK_TIMEOUT_MS) {
            fun open(candidates: List<Int>): LinkResolution? {
                val source = sourceBounds()
                val valid = candidates.filter { it in source }
                if (valid.isEmpty()) return null
                val work = containingBook(near)
                val record = valid.firstOrNull { work != null && it in work.node.record..work.node.end } ?: valid.first()
                return LinkResolution.Open(location(record, containingBook(record)))
            }
            when (link.kind) {
                ReaderLinkKind.JUMP -> open(records("SELECT recid FROM jumplinks WHERE title=? ORDER BY recid LIMIT $LINK_CANDIDATES", link.target)
                    .ifEmpty { records("SELECT recid FROM jumplinks WHERE lower(title)=? ORDER BY recid LIMIT $LINK_CANDIDATES", LinkQuery.fold(link.target)) })
                    ?: LinkResolution.Unavailable
                ReaderLinkKind.QUERY -> {
                    val heading = LinkQuery.heading(link.target)
                    if (heading != null) {
                        // A heading lookup that misses is a broken reference, not a word search.
                        val level = levelIds()[heading.level] ?: return@read LinkResolution.Unavailable
                        open(records("SELECT record FROM contents WHERE level=? AND simpletitle=? ORDER BY record LIMIT $LINK_CANDIDATES",
                            level.toString(), heading.title)) ?: LinkResolution.Unavailable
                    } else LinkQuery.searchText(link.target)?.let { LinkResolution.Search(it) } ?: LinkResolution.Unavailable
                }
                ReaderLinkKind.POPUP -> sql("SELECT substr(plain,1,${POPUP_CHARACTERS + 1}),class FROM popup WHERE title=? LIMIT 1", link.target).use {
                    if (!it.moveToFirst()) return@read LinkResolution.Unavailable
                    val plain = it.getString(0).orEmpty()
                    check(plain.length <= POPUP_CHARACTERS) { "Oversized popup" }
                    LinkResolution.Popup(ReaderPopup(link.target, it.getString(1).orEmpty().takeUnless { style -> style == "(null)" }.orEmpty(), plain))
                }
                ReaderLinkKind.AUDIO -> LinkResolution.Unavailable
            }
        }
    }

    suspend fun searchPage(request: SearchRequest, afterRecord: Int = 0): SearchPage {
        val query = SearchText.validate(request.query, request.target)
        require(afterRecord in 0..MAX_RECORD) { "Invalid search cursor" }
        val selected = request.bookRecords.toSet()
        return read {
            val scope = scope(selected)
            val key = Key(query.signature, query.titleKey, selected.sorted(), request.mode, request.target)
            val found = synchronized(lifecycle) { candidates[key] } ?:
                buildCandidates(query, scope, request.target).also { cache(key, it) }
            val hits = mutableListOf<ScopedSearchHit>()
            var record = found.records.nextSetBit(afterRecord + 1)
            var scanned = afterRecord
            var inspected = 0
            var rawInspected = 0
            var metadataThrough = afterRecord
            var verseStyles = emptySet<Int>()
            while (record >= 0 && hits.size < PAGE_SIZE && inspected < VERIFY_LIMIT && rawInspected < RAW_SCAN_LIMIT) {
                checkpoint()
                if (request.target == SearchTarget.VERSE_WORDS && record > metadataThrough) {
                    val batch = candidateBatch(found.records, record, minOf(METADATA_BATCH, RAW_SCAN_LIMIT - rawInspected))
                    verseStyles = verseWordStyles(batch)
                    metadataThrough = batch.last()
                }
                val current = record
                scanned = current
                rawInspected++
                record = found.records.nextSetBit(current + 1)
                // Prefetched metadata never advances the source cursor or spends text verification.
                if (request.target == SearchTarget.VERSE_WORDS &&
                    (current !in verseStyles || !inMainVerse(current))) continue
                inspected++
                // Reference-only results never read/verify body words (even a quoted reference).
                val plain = if (request.target == SearchTarget.VERSE_REFERENCE) heading(current)?.node?.displayTitle else plain(current)
                if (plain != null) {
                    val matched = if (found.titles[current]) null else SearchText.matches(plain, query, request.mode)
                    if (matched != null || found.titles[current]) {
                        val snippet = SearchText.snippet(plain, matched.orEmpty())
                        val work = containingBook(current)
                        hits.add(ScopedSearchHit(location(current, work), work?.node?.displayTitle.orEmpty(),
                            snippet.text, snippet.highlights))
                    }
                }
            }
            // This is the last inspected record, even when verification found no visible hits.
            SearchPage(hits, if (record >= 0) scanned else null)
        }
    }

    /** Best-effort indexed completions: 650ms cancellable deadline, 2MiB postings, 64 keyed bodies.
     * A timeout is propagated as unavailable, never reported as an exhaustive empty dictionary.
     */
    suspend fun searchSuggestions(request: SearchRequest): List<SearchSuggestion> {
        require(request.query.length <= SearchText.MAX_QUERY_CHARACTERS) { "Use at most 160 characters" }
        val selected = request.bookRecords.toSet()
        return read(timeoutMillis = SUGGEST_TIMEOUT_MS) {
            val scope = scope(selected)
            if (request.query.trim().length < 2) return@read emptyList()
            val query = SearchText.validate(request.query, request.target, allowPrefix = true)
            val referenceOnly = request.target != SearchTarget.VERSE_WORDS && SearchText.isReferencePrefix(query.titleKey)
            // Mode cannot affect references or single-token completions. Multiword validation still needs it.
            val key = SuggestionKey(request.query, selected.sorted(), request.target,
                request.mode.takeUnless { referenceOnly || query.terms.size == 1 })
            synchronized(lifecycle) { suggestions[key] }?.let {
                suggestionCacheHit = true
                return@read it.toList()
            }
            val result = LinkedHashMap<String, SearchSuggestion>()
            fun finish(): List<SearchSuggestion> = result.values.toList().also { values ->
                synchronized(lifecycle) {
                    if (!closed) {
                        suggestions[key] = values.toList() // Cache owns a separate snapshot from the caller.
                        while (suggestions.size > SUGGEST_CACHE_ENTRIES) suggestions.remove(suggestions.keys.first())
                    }
                }
            }
            if (referenceOnly) {
                // A combined heading may begin with 1.16 but also contain the typed 1.17.
                for (prefix in SearchText.referencePrefixes(query)) {
                    if (result.size >= SUGGEST_SIZE) break
                    titleRows(prefix, true, SUGGEST_TITLE_LIMIT, scope, versesOnly = true,
                        stopWhen = { result.size >= SUGGEST_SIZE }) { node, simple ->
                        for (reference in SearchText.referenceSuggestions(simple, query, SUGGEST_SIZE)) {
                            if (result.size >= SUGGEST_SIZE) break
                            result.putIfAbsent(reference, SearchSuggestion(reference, node.displayTitle, true))
                        }
                    }
                }
                // Never fall through to common/numeric word postings for reference input.
                return@read finish()
            }
            if (request.target == SearchTarget.VERSE_REFERENCE) return@read finish()
            val last = SearchText.tokens(request.query).last()
            // Do not discard a trailing separator, or replace words earlier in the typed phrase.
            if (last.endExclusive != request.query.length) return@read finish()
            val preceding = request.query.substring(0, last.start)
            val completions = sql(
                "SELECT substr(word,1,513) FROM words WHERE word>=? AND word<? AND idx IN ('','Devanagari') " +
                    "GROUP BY word ORDER BY word LIMIT $SUGGEST_WORD_LIMIT",
                last.value, SearchText.prefixEnd(last.value),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    checkpoint()
                    val word = cursor.getString(0)
                    check(word.length <= 512) { "Oversized index word" }
                    add(word)
                }
            } }
            val budget = SuggestBudget()
            for (word in completions) {
                checkpoint()
                if (result.size >= SUGGEST_SIZE || budget.bytes <= 0 || budget.texts <= 0) break
                val completion = preceding + word
                if (completion.length > SearchText.MAX_QUERY_CHARACTERS ||
                    SearchText.tokens(word).map { it.value } != listOf(word)) continue
                // Snapshot ALL filters, especially the target; never construct an ALL_TEXT default here.
                val completedRequest = request.copy(query = completion, bookRecords = selected)
                val completed = SearchText.validate(completedRequest.query, completedRequest.target)
                if (suggestionHasMatch(word, completed, completedRequest, scope, budget)) {
                    result.putIfAbsent(completion, SearchSuggestion(completion))
                }
            }
            finish()
        }
    }

    /** Cancels in-flight reads, prevents cache repopulation; never owns/closes the caller's DB. */
    override fun close() {
        val signals = synchronized(lifecycle) {
            closed = true
            candidates.clear()
            suggestions.clear()
            catalog = null
            levels = null
            levelNames = null
            bounds = null
            activeSignals.toList()
        }
        signals.forEach { it.cancel() }
    }

    private fun cache(key: Key, value: Candidates) = synchronized(lifecycle) {
        if (!closed && value.bytes <= CACHE_BYTES) {
            candidates[key] = value
            while (candidates.size > CACHE_ENTRIES || candidates.values.sumOf { it.bytes } > CACHE_BYTES) {
                candidates.remove(candidates.keys.first())
            }
        }
    }

    private suspend fun <T> read(timeoutMillis: Long = TIMEOUT_MS, block: suspend Read.() -> T): T = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        withTimeout(timeoutMillis) {
            serial.withLock {
                coroutineScope {
                    val signal = CancellationSignal()
                    synchronized(lifecycle) {
                        check(!closed) { "Search engine is closed" }
                        db.acquireReference()
                        activeSignals.add(signal)
                    }
                    // Runs on another dispatcher so cancellation can interrupt a blocking SQLite step.
                    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { signal.cancel() }
                    }
                    val reader = Read(currentCoroutineContext(), signal)
                    try {
                        reader.checkpoint()
                        reader.block().also { reader.checkpoint() }
                    } catch (cancelled: OperationCanceledException) {
                        currentCoroutineContext().ensureActive()
                        throw CancellationException("Search read cancelled").apply { initCause(cancelled) }
                    } finally {
                        lastReadMetrics = reader.metrics(started)
                        watcher.cancel()
                        synchronized(lifecycle) { activeSignals.remove(signal) }
                        db.releaseReference()
                    }
                }
            }
        }
    }

    private class SuggestBudget(var bytes: Int = 2 * 1024 * 1024, var texts: Int = 64)

    private inner class Read(private val context: CoroutineContext, private val signal: CancellationSignal) {
        private var postingBytes = 0L
        private var sqlQueries = 0
        private var postingMetadataQueries = 0
        private var plainTextReads = 0
        private var verseMetadataQueries = 0
        private var referenceRows = 0
        var suggestionCacheHit = false
        private val mainVerseRegions = LinkedHashMap<Int, Pair<IntRange, Int>>(64, 0.75f, true)

        fun metrics(started: Long) = ReadMetrics((System.nanoTime() - started) / 1_000_000,
            sqlQueries, postingMetadataQueries, postingBytes, plainTextReads, verseMetadataQueries,
            referenceRows, suggestionCacheHit)
        fun checkpoint() { context.ensureActive(); signal.throwIfCanceled() }
        fun sql(statement: String, vararg args: String): Cursor {
            checkpoint()
            sqlQueries++
            return db.rawQuery(statement, args, signal)
        }

        fun semanticLevels(): Map<Int, String> {
            synchronized(lifecycle) { levels }?.let { return it }
            val loaded = sql("SELECT id,level FROM levels LIMIT 513").use { cursor -> buildMap {
                while (cursor.moveToNext()) {
                    checkpoint()
                    check(size < 512) { "Too many semantic levels" }
                    val id = cursor.getInt(0)
                    check(!containsKey(id)) { "Duplicate semantic level" }
                    put(id, cursor.getString(1).orEmpty().trim())
                }
            } }
            synchronized(lifecycle) { if (!closed) levels = loaded }
            return loaded
        }

        /** Folio query links name levels by their display name ("Verse Number"), not the LE_ code. */
        fun levelIds(): Map<String, Int> {
            synchronized(lifecycle) { levelNames }?.let { return it }
            val loaded = sql("SELECT id,original FROM levels LIMIT 513").use { cursor -> buildMap {
                while (cursor.moveToNext()) {
                    checkpoint()
                    check(size < 512) { "Too many semantic levels" }
                    val name = LinkQuery.fold(cursor.getString(1).orEmpty())
                    if (name.isNotEmpty()) putIfAbsent(name, cursor.getInt(0))
                }
            } }
            synchronized(lifecycle) { if (!closed) levelNames = loaded }
            return loaded
        }

        fun records(statement: String, vararg args: String): List<Int> = sql(statement, *args).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                checkpoint()
                val record = cursor.getLong(0)
                if (record in 1..MAX_RECORD.toLong()) add(record.toInt())
            }
        } }

        fun sourceBounds(): IntRange {
            synchronized(lifecycle) { bounds }?.let { return it }
            val first = sql("SELECT recid FROM texts ORDER BY recid LIMIT 1").use { if (it.moveToFirst()) it.getLong(0) else null }
            val last = sql("SELECT recid FROM texts ORDER BY recid DESC LIMIT 1").use { if (it.moveToFirst()) it.getLong(0) else null }
            val range = if (first == null || last == null) IntRange.EMPTY else {
                check(first in 1..MAX_RECORD.toLong() && last in first..MAX_RECORD.toLong()) { "Invalid source record bounds" }
                first.toInt()..last.toInt()
            }
            synchronized(lifecycle) { if (!closed) bounds = range }
            return range
        }

        fun books(): List<SearchBook> {
            synchronized(lifecycle) { catalog }?.let { return it }
            val nodes = sortedMapOf<Int, ContentNode>()
            fun collect(where: String, vararg args: String) {
                sql("$NODE_SELECT FROM contents WHERE $where ORDER BY record LIMIT ${CATALOG_LIMIT + 1}", *args).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkpoint()
                        val node = cursor.node()
                        check(nodes.size < CATALOG_LIMIT || nodes.containsKey(node.record)) { "Search catalog is too large" }
                        nodes[node.record] = node
                    }
                }
            }
            collect("parent=0")
            for ((level, name) in semanticLevels()) if (name in WORK_LEVELS) collect("level=?", level.toString())
            val loaded = nodes.values.map { node ->
                val parent = if (node.parent == 0) null else nodes[node.parent] ?: heading(node.parent)?.node
                if (parent != null) check(parent.record < node.record && parent.end >= node.end) { "Invalid book parent range" }
                SearchBook(node, parent?.displayTitle.orEmpty())
            }
            synchronized(lifecycle) { if (!closed) catalog = loaded }
            return loaded
        }

        fun scope(selected: Set<Int>): Scope {
            val available = books().associateBy { it.node.record }
            require(selected.all { it in available }) { "Unknown search book selection" }
            if (selected.isEmpty()) return Scope(null, sourceBounds())
            val merged = mutableListOf<IntRange>()
            for (node in selected.map { available.getValue(it).node }.sortedBy { it.record }) {
                val previous = merged.lastOrNull()
                if (previous != null && node.record.toLong() <= previous.last.toLong() + 1) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, node.end)
                } else merged.add(node.record..node.end)
            }
            return Scope(merged, sourceBounds())
        }

        fun postings(terms: List<String>): List<List<Posting>> = groupPostings(terms.map { SearchText.Term(listOf(it)) })

        /** One posting list per term group: all spelling variants, or every index word under a prefix. */
        fun groupPostings(groups: List<SearchText.Term>): List<List<Posting>> {
            val lists = groups.map { mutableListOf<Posting>() }
            fun accept(cursor: Cursor, rows: MutableList<Posting>) {
                check(rows.size < 1024 && !cursor.isNull(1) && !cursor.isNull(2)) { "Invalid word index metadata" }
                val base = cursor.getLong(1)
                val length = cursor.getLong(2)
                check(base in 0..MAX_RECORD.toLong() && length in 0..MAX_POSTING_BYTES && length % 6 == 0L) {
                    "Invalid word index data"
                }
                rows.add(Posting(cursor.getLong(0), base.toInt(), length.toInt()))
            }
            val exact = groups.flatMap { it.forms }.distinct()
            if (exact.isNotEmpty()) {
                postingMetadataQueries++
                // One cheap indexed metadata read; never load the blobs to choose the cheapest term.
                sql("SELECT rowid,indexbase,length(data),substr(word,1,513) FROM words " +
                    "WHERE word IN (${exact.joinToString(",") { "?" }}) AND idx IN ('','Devanagari') " +
                    "LIMIT ${exact.size * 1024 + 1}", *exact.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkpoint()
                        val word = cursor.getString(3)
                        groups.forEachIndexed { index, group -> if (word in group.forms) accept(cursor, lists[index]) }
                    }
                }
            }
            groups.forEachIndexed { index, group ->
                val prefix = group.prefix ?: return@forEachIndexed
                postingMetadataQueries++
                // Bounded expansion: the rarest few hundred index words under the prefix, never the whole dictionary.
                sql("SELECT rowid,indexbase,length(data),substr(word,1,513) FROM words " +
                    "WHERE word>=? AND word<? AND idx IN ('','Devanagari') ORDER BY word LIMIT ${PREFIX_WORD_LIMIT + 1}",
                    prefix, SearchText.prefixEnd(prefix)).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        checkpoint()
                        check(++count <= PREFIX_WORD_LIMIT) { "Too many words start with \"$prefix\"; type more letters" }
                        accept(cursor, lists[index])
                    }
                }
            }
            return lists.sortedBy { rows -> rows.sumOf { it.bytes.toLong() } }
        }

        fun chunk(posting: Posting, offset: Int, count: Int = POSTING_CHUNK): ByteArray {
            val expected = minOf(count, posting.bytes - offset)
            postingBytes += expected
            check(postingBytes <= MAX_READ_BYTES) { "Search posting read budget exceeded" }
            return sql("SELECT substr(data,?,?) FROM words WHERE rowid=? LIMIT 1",
                (offset + 1).toString(), expected.toString(), posting.row.toString()).use {
                check(it.moveToFirst() && !it.isNull(0)) { "Missing word index chunk" }
                it.getBlob(0).also { bytes -> check(bytes.size == expected) { "Incomplete word index chunk" } }
            }
        }

        fun decode(bytes: ByteArray, base: Int, accept: (Int) -> Unit) {
            check(bytes.size % 6 == 0) { "Invalid word index chunk" }
            var offset = 0
            while (offset < bytes.size) {
                if (offset % 768 == 0) checkpoint()
                var relative = 0L
                for (part in 0..3) relative = relative or ((bytes[offset + part].toLong() and 255L) shl (8 * part))
                val absolute = base.toLong() + relative
                check(absolute in 0..MAX_RECORD.toLong()) { "Word index record is out of range" }
                accept(absolute.toInt())
                offset += 6 // The position is deliberately not used as a plaintext token offset.
            }
        }

        fun buildCandidates(query: SearchText.Query, scope: Scope, target: SearchTarget): Candidates {
            val reference = query.reference.takeUnless { target == SearchTarget.VERSE_WORDS }
            val titles = BitSet()
            if (reference != null) {
                for (prefix in reference.lookupStems) {
                    titleRows(prefix, true, TITLE_LIMIT, scope, failOnOverflow = true, versesOnly = true) { node, simple ->
                        if (SearchText.referenceMatches(simple, reference)) titles.set(node.record)
                    }
                }
                // Explicit references have no plaintext-word interpretation, including in ALL_TEXT.
                return Candidates(titles.clone() as BitSet, titles)
            }
            var intersection: BitSet? = null
            // Starting with the cheapest list avoids reading common lists when a rare term is absent.
            val lists = groupPostings(query.groups.distinct())
            for (rows in lists) {
                checkpoint()
                val current = BitSet()
                val previous = intersection
                for (posting in rows) {
                    var offset = 0
                    while (offset < posting.bytes) {
                        val bytes = chunk(posting, offset)
                        decode(bytes, posting.base) { record ->
                            if (scope.contains(record) && (previous == null || previous[record])) current.set(record)
                        }
                        offset += bytes.size
                    }
                }
                if (intersection == null) intersection = current else intersection.and(current)
                if (intersection.isEmpty) break
            }
            if (target == SearchTarget.ALL_TEXT) {
                titleRows(query.titleKey, false, TITLE_LIMIT, scope, failOnOverflow = true) { node, _ ->
                    titles.set(node.record)
                }
            }
            val combined = intersection ?: BitSet()
            // Cache raw indexed intersections; verse classification is bounded by each page.
            combined.or(titles)
            return Candidates(combined, titles)
        }

        fun titleRows(prefix: String, isPrefix: Boolean, limit: Int, scope: Scope, failOnOverflow: Boolean = false,
            versesOnly: Boolean = false,
            stopWhen: () -> Boolean = { false },
            consume: (ContentNode, String) -> Unit) {
            val condition = if (isPrefix) "simpletitle>=? AND simpletitle<?" else "simpletitle=?"
            val verseLevels = if (versesOnly) semanticLevels().filterValues { it == "LE_Verse_Number" }.keys else emptySet()
            if (versesOnly && verseLevels.isEmpty()) return
            val semantic = if (versesOnly) " AND level IN (${verseLevels.joinToString(",") { "?" }})" else ""
            val ranges = (scope.ranges ?: listOf(scope.source)).map {
                maxOf(it.first, scope.source.first)..minOf(it.last, scope.source.last)
            }.filterNot { it.isEmpty() }
            var count = 0
            // Already merged, disjoint ranges: filter BEFORE the limit without exceeding SQLite's bind limit.
            for (batch in ranges.chunked(128)) {
                if (stopWhen()) break
                val remaining = limit - count + if (failOnOverflow) 1 else 0
                if (remaining <= 0) break
                val args = mutableListOf(prefix)
                if (isPrefix) args.add(SearchText.prefixEnd(prefix))
                args.addAll(verseLevels.map { it.toString() })
                batch.forEach { args.add(it.first.toString()); args.add(it.last.toString()) }
                val selected = batch.joinToString(" OR ") { "record BETWEEN ? AND ?" }
                sql("$NODE_SELECT,substr(simpletitle,1,4097) FROM contents WHERE $condition$semantic AND ($selected) " +
                    "ORDER BY simpletitle LIMIT $remaining", *args.toTypedArray()).use { cursor ->
                    while (!stopWhen() && cursor.moveToNext()) {
                        checkpoint()
                        referenceRows++
                        check(++count <= limit) { "Too many matching reference headings" }
                        val simple = cursor.getString(7).orEmpty()
                        check(simple.length <= 4096) { "Oversized reference title" }
                        consume(cursor.node(), simple)
                    }
                }
            }
        }

        fun plain(record: Int): String? {
            plainTextReads++
            return sql(
                // rawQuery binds strings: cast the bound, or SQLite compares INTEGER < TEXT by storage class.
                "SELECT length(CAST(plain AS BLOB)),CASE WHEN length(CAST(plain AS BLOB))<=CAST(? AS INTEGER) THEN plain ELSE NULL END " +
                    "FROM texts WHERE recid=? LIMIT 1", TEXT_BYTES.toString(), record.toString(),
            ).use {
                if (!it.moveToFirst()) return@use null
                check(it.getLong(0) <= TEXT_BYTES) { "Search paragraph is too large" }
                FolioText.plain(it.getString(1).orEmpty()).also { checkpoint() }
            }
        }

        fun suggestionHasMatch(word: String, query: SearchText.Query, request: SearchRequest, scope: Scope, budget: SuggestBudget): Boolean {
            val seen = BitSet()
            for (posting in postings(listOf(word)).single()) {
                var offset = 0
                while (offset < posting.bytes && budget.bytes >= 6 && budget.texts > 0) {
                    val count = minOf(POSTING_CHUNK, budget.bytes / 6 * 6)
                    val bytes = chunk(posting, offset, count)
                    budget.bytes -= bytes.size
                    offset += bytes.size
                    val records = BitSet()
                    // Validate the entire fetched chunk, even after a match was encountered.
                    decode(bytes, posting.base) { record ->
                        if (scope.contains(record) && !seen[record]) records.set(record)
                    }
                    seen.or(records)
                    var record = records.nextSetBit(0)
                    while (record >= 0 && budget.texts > 0) {
                        val batch = candidateBatch(records, record)
                        val styled = if (request.target == SearchTarget.VERSE_WORDS) verseWordStyles(batch) else emptySet()
                        for (candidate in batch) {
                            checkpoint()
                            if (request.target == SearchTarget.VERSE_WORDS &&
                                (candidate !in styled || !inMainVerse(candidate))) continue
                            budget.texts--
                            val text = plain(candidate)
                            if (text != null && SearchText.matches(text, query, request.mode) != null) return true
                            if (budget.texts <= 0) break
                        }
                        record = records.nextSetBit(batch.last() + 1)
                    }
                }
                if (budget.bytes < 6 || budget.texts <= 0) break
            }
            return false
        }

        fun candidateBatch(records: BitSet, first: Int, limit: Int = METADATA_BATCH): List<Int> = buildList {
            var next = first
            while (next >= 0 && size < limit) {
                checkpoint()
                add(next)
                next = records.nextSetBit(next + 1)
            }
        }

        /** Only prefetch styles; ancestry/main-verse checks run lazily for inspected candidates. */
        fun verseWordStyles(batch: List<Int>): Set<Int> {
            check(batch.isNotEmpty() && batch.size <= METADATA_BATCH) { "Invalid verse metadata batch" }
            val styled = mutableSetOf<Int>()
            verseMetadataQueries++
            sql("SELECT recid,substr(levelname,1,129),substr(stylename,1,129) FROM texts " +
                "WHERE recid IN (${batch.joinToString(",") { "?" }}) ORDER BY recid LIMIT ${METADATA_BATCH + 1}",
                *batch.map { it.toString() }.toTypedArray()).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    checkpoint()
                    check(++count <= batch.size) { "Duplicate search text metadata" }
                    val level = cursor.getString(1).orEmpty()
                    val style = cursor.getString(2).orEmpty()
                    check(level.length <= 128 && style.length <= 128) { "Oversized text metadata" }
                    if (SearchText.isVerseWordRecord(level, style)) styled.add(cursor.getInt(0))
                }
            }
            return styled
        }

        fun inMainVerse(record: Int): Boolean {
            mainVerseRegions.values.firstOrNull { record in it.first }?.let { return record <= it.second }
            val chain = ancestry(record)
            val verse = chain.firstOrNull { it.semantic == "LE_Verse_Number" }?.node ?: return false
            val range = verse.record..minOf(verse.end, sourceBounds().last)
            var after = verse.record
            var mainEnd = range.last
            // Read only small style/level columns until the first non-main section. A very long
            // purport costs no plaintext reads and is never walked after its boundary is found.
            scan@ while (after < range.last) {
                verseMetadataQueries++
                var count = 0
                sql("SELECT recid,substr(levelname,1,129),substr(stylename,1,129) FROM texts " +
                    "WHERE recid>? AND recid<=? ORDER BY recid LIMIT $METADATA_BATCH",
                    after.toString(), range.last.toString()).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkpoint()
                        val id = cursor.getInt(0)
                        check(id > after) { "Invalid verse metadata order" }
                        after = id
                        count++
                        val level = cursor.getString(1).orEmpty().trim()
                        val style = cursor.getString(2).orEmpty().trim()
                        check(level.length <= 128 && style.length <= 128) { "Oversized text metadata" }
                        val section = level in SECTION_LEVELS || (level.isEmpty() && style in SECTION_STYLES)
                        val boundary = if (section) {
                            // TEXT is source heading metadata, not a word inferred from a body/quote.
                            !heading(id)?.node?.title.orEmpty().trim().equals("TEXT", ignoreCase = true)
                        } else !SearchText.isVerseWordRecord(level, style) &&
                            (level.isNotEmpty() || style in NON_MAIN_STYLES)
                        if (boundary) {
                            mainEnd = id - 1
                            break
                        }
                    }
                }
                if (mainEnd < range.last || count < METADATA_BATCH) break@scan
            }
            mainVerseRegions[verse.record] = range to mainEnd
            while (mainVerseRegions.size > 256) mainVerseRegions.remove(mainVerseRegions.keys.first())
            return record in range && record <= mainEnd
        }

        fun containingBook(record: Int): SearchBook? = books().filter { record in it.node.record..it.node.end }
            .minWithOrNull(compareBy<SearchBook> { it.node.end.toLong() - it.node.record }.thenByDescending { it.node.record })

        fun heading(record: Int): Heading? = sql("$NODE_SELECT FROM contents WHERE record=? LIMIT 1", record.toString()).use {
            if (it.moveToFirst()) it.node().let { node -> Heading(node, semanticLevels()[node.level].orEmpty()) } else null
        }

        fun ancestry(record: Int): List<Heading> {
            val nearest = sql("$NODE_SELECT FROM contents WHERE record<=? ORDER BY record DESC LIMIT 1", record.toString()).use {
                if (it.moveToFirst()) it.node().let { node -> Heading(node, semanticLevels()[node.level].orEmpty()) } else null
            }
            val chain = mutableListOf<Heading>()
            val visited = mutableSetOf<Int>()
            var current = nearest
            while (current != null) {
                checkpoint()
                val item = current
                check(visited.add(item.node.record) && visited.size <= 64) { "Invalid search heading ancestry" }
                if (record in item.node.record..item.node.end) chain.add(item)
                if (item.node.parent == 0) break
                val parent = heading(item.node.parent) ?: break
                check(parent.node.record < item.node.record && parent.node.end >= item.node.end) { "Invalid search parent range" }
                current = parent
            }
            return chain
        }

        fun location(record: Int, book: SearchBook?): ReadingLocation {
            val chain = ancestry(record)
            val insideWork = chain.filter { book == null ||
                (it.node.record >= book.node.record && it.node.end <= book.node.end) }
            val chosen = insideWork.firstOrNull { it.semantic == "LE_Verse_Number" }
                ?: insideWork.firstOrNull { it.semantic == "LE_Chapter" }
                ?: insideWork.firstOrNull { it.semantic in WORK_LEVELS }
            val node = chosen?.node ?: book?.node ?: insideWork.firstOrNull()?.node
            val source = sourceBounds()
            return if (node == null) ReadingLocation(libraryId, "", record, record, record)
            else ReadingLocation(libraryId, node.displayTitle, maxOf(node.record, source.first), minOf(node.end, source.last), record)
        }

        fun Cursor.node(): ContentNode {
            val record = getLong(0)
            val parent = getLong(1)
            val end = getLong(3)
            check(record in 1..MAX_RECORD.toLong() && end in record..MAX_RECORD.toLong() &&
                parent in 0..MAX_RECORD.toLong()) { "Invalid contents range" }
            val title = getString(2).orEmpty()
            check(title.length <= 4096) { "Oversized contents title" }
            return ContentNode(record.toInt(), parent.toInt(), title, end.toInt(), getInt(4), getInt(5), getString(6).orEmpty())
        }
    }

    companion object {
        private const val MAX_RECORD = 20_000_000
        private const val PAGE_SIZE = 30
        private const val VERIFY_LIMIT = 1000
        private const val RAW_SCAN_LIMIT = 4096
        private const val TIMEOUT_MS = 15_000L
        private const val SUGGEST_TIMEOUT_MS = 650L
        private const val LINK_TIMEOUT_MS = 5_000L
        private const val LINK_CANDIDATES = 64
        private const val POPUP_CHARACTERS = 65_536
        private const val TEXT_BYTES = 131_072
        private const val POSTING_CHUNK = 98_304 // divisible by six, below CursorWindow size
        private const val MAX_POSTING_BYTES = 128L * 1024 * 1024
        private const val MAX_READ_BYTES = 256L * 1024 * 1024
        private const val CATALOG_LIMIT = 4096
        private const val TITLE_LIMIT = 4096
        private const val SUGGEST_TITLE_LIMIT = 256
        private const val SUGGEST_WORD_LIMIT = 96
        /** Distinct index words one trailing-* term may expand to before asking for more letters. */
        private const val PREFIX_WORD_LIMIT = 256
        private const val SUGGEST_SIZE = 8
        private const val SUGGEST_CACHE_ENTRIES = 24
        private const val METADATA_BATCH = 128
        private const val CACHE_ENTRIES = 4
        private const val CACHE_BYTES = 8L * 1024 * 1024
        private val WORK_LEVELS = setOf("LE_Book", "LE_Collection")
        private val SECTION_LEVELS = setOf("LE_Verse_Section", "LE_Text_Section")
        private val SECTION_STYLES = setOf("PA_Titles", "PA_Text_Section")
        // The eight translation styles are the same corpus-verified identifiers as IvdChapterOverview.
        private val NON_MAIN_STYLES = setOf("PA_Purport", "PA_First", "PA_After_Verse", "PA_AfterVerse",
            "PA_Verse_in_purp", "PA_Verse_in_Purport", "PA_Verse_in_purport", "PA_Synonyms", "PA_Synonyms_Section",
            "PA_Translation", "PA_Spec_Translation", "PA_Deity_95Book_95Translation", "PA_Prema_45Dhvani_Translation",
            "PA_TKQ_47TLK_Translation", "PA_Sloka_Translation", "PA_Purple_95BG_95Translations", "PA_TKQTranslation")
        private const val NODE_SELECT = "SELECT record,parent,substr(title,1,4097),next_sibling,node_children,level,substr(node_code,1,128)"
    }
}