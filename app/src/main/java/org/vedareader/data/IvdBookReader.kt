package org.vedareader.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream

/** A read-only, record-complete alternative to IvdSections. The database remains caller-owned. */
internal class IvdBookReader(
    private val database: SQLiteDatabase,
    private val cache: OutlineCache = OutlineCache(),
) {
    private data class Heading(val node: ContentNode, val levelName: String)
    private data class RecordSize(val record: Int, val bytes: Long, val levelName: String)

    /** Partition targets, not permission to relax completeText's hard guards. There are exactly
     * two profiles, so caching both cannot grow an unbounded set of partitions per outline.
     */
    internal enum class PartitionProfile(val maxVerses: Int, val maxRows: Int, val maxBytes: Long) {
        LEGACY(Int.MAX_VALUE, MAX_RECORDS, MAX_CHARACTERS * 4),
        READER_WINDOW(12, 384, 48_000L),
    }

    /** Metadata only; a fixed partition shared by every selection in this outline. */
    internal data class ResourceRange(
        val firstEntry: Int,
        val lastEntry: Int,
        val node: ContentNode,
        val kind: ReadingUnitKind,
        val parentTitle: String,
    )

    suspend fun read(record: Int): BookReadingSection {
        val position = position(record)
        val entries = position.outline.entries
        val entry = entries[position.index]
        val section = ReadingSection(entry.node, entry.kind, entry.parentTitle, completeText(entry.node),
            entries.getOrNull(position.index - 1)?.node, entries.getOrNull(position.index + 1)?.node)
        return BookReadingSection(section, position)
    }

    /** Unlike read(), one document includes adjacent verses and all intervening source paragraphs.
     * Only this resource's text is loaded. Resource boundaries never depend on the requested anchor.
     * The legacy hard-budget grouping remains the default for existing callers.
     */
    suspend fun readResource(record: Int): BookResource = resource(record, PartitionProfile.LEGACY)

    /** Opt-in smaller documents, comprising whole existing outline entries. An indivisible entry
     * above a soft target loads alone, never truncated, and still passes the same hard text guards.
     * Neighbours are windows; previous-with-end-intent remains the controller's responsibility.
     * Every new document starts a fresh layout: page breaks can differ from a whole chapter.
     */
    suspend fun readWindow(record: Int): BookResource = resource(record, PartitionProfile.READER_WINDOW)

    private suspend fun resource(record: Int, profile: PartitionProfile): BookResource {
        val position = position(record)
        val ranges = cache.resources(database, position.outline, profile) { resourceRanges(position.outline, profile) }
        currentCoroutineContext().ensureActive()
        val index = ranges.binarySearch { range ->
            when {
                range.lastEntry < position.index -> -1
                range.firstEntry > position.index -> 1
                else -> 0
            }
        }
        valid(index >= 0)
        val range = ranges[index]
        val entries = position.outline.entries.subList(range.firstEntry, range.lastEntry + 1)
        val section = ReadingSection(range.node, range.kind, range.parentTitle, withVerseHeadings(completeText(range.node), entries),
            ranges.getOrNull(index - 1)?.node, ranges.getOrNull(index + 1)?.node)
        // Exact verse openings already are source records. Do not move a body/subsection selection
        // to that verse's opening, or move either selection to the resource's chapter header.
        return BookResource(section, position, range.firstEntry, range.lastEntry, record)
    }

    /** A source verse whose own heading paragraph is empty (BG 1.7) shows its contents title instead. */
    private fun withVerseHeadings(paragraphs: List<Paragraph>, entries: List<BookEntry>): List<Paragraph> {
        val titles = entries.asSequence().filter { it.kind == ReadingUnitKind.VERSE && it.source.title.isNotBlank() }
            .associate { it.source.record to it.source.title }
        if (titles.isEmpty()) return paragraphs
        return paragraphs.map { paragraph ->
            val title = titles[paragraph.record]
            if (title == null || paragraph.style != "PA_Verse_Number_Heading" || org.vedareader.reader.FolioText.plain(paragraph.text).isNotBlank()) paragraph
            else paragraph.copy(text = "<PS:\"Verse Number Heading\">$title")
        }
    }

    private suspend fun position(record: Int): BookPosition {
        currentCoroutineContext().ensureActive()
        valid(record > 0 && database.isOpen && database.isReadOnly)
        // A body row (or a position in a sparse range) need not have its own contents row.
        val preceding = database.rawQuery(
            "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record<=? ORDER BY c.record DESC LIMIT 1",
            arrayOf(record.toString()),
        ).use { if (it.moveToFirst()) it.heading() else null } ?: fail()
        val ancestry = ancestors(preceding)
        val containing = ancestry.dropWhile { record > it.node.end }
        if (containing.isEmpty()) fail()
        val work = resolveWork(containing)
        val outline = cache.get(database, work.node.record) {
            val workAncestry = ancestry.dropWhile { it.node.record != work.node.record }
            validateAncestrySiblings(workAncestry)
            outline(work, workAncestry.drop(1).firstOrNull()?.node)
        }
        currentCoroutineContext().ensureActive()
        val index = outline.entries.binarySearch { entry ->
            when {
                entry.node.end < record -> -1
                entry.node.record > record -> 1
                else -> 0
            }
        }
        valid(index >= 0)
        return BookPosition(outline, index)
    }

    /** Book/Collection are independent works; Canto is a subdivision, including appendix cantos.
     * A collection whose direct semantic divisions are only cantos is read one direct canto at a
     * time (never the nearest nested canto). Direct chapters/verses keep the entire collection;
     * independent child works also disable this shortcut so anthology traversal still fails closed.
     * Nonsemantic front matter may coexist with cantos, but opening the collection/gaps in that
     * layout requires choosing a canto. A standalone canto uses its outermost canto ancestor.
     */
    private suspend fun resolveWork(containing: List<Heading>): Heading {
        val independent = containing.firstOrNull { it.levelName in INDEPENDENT_WORKS }
        if (independent == null) return containing.lastOrNull { it.levelName == CANTO }
            ?: throw ReadingException(ReadingFailure.CHOOSE_SECTION)
        if (independent.levelName != COLLECTION || !usesCantoScopes(independent)) return independent
        return containing.firstOrNull { it.node.parent == independent.node.record && it.levelName == CANTO }
            ?: throw ReadingException(ReadingFailure.CHOOSE_SECTION)
    }

    /** Indexed, bounded direct-child metadata only; no title, ID, numeric-level or child-count guesses. */
    private suspend fun usesCantoScopes(collection: Heading): Boolean {
        var hasCanto = false
        var hasOtherDivision = false
        scanDirectChildren(collection.node.record,
            "SELECT c.record,$LEVEL_SELECT,$LEVEL_BYTES FROM contents c LEFT JOIN texts t ON t.recid=c.record",
        ) { cursor ->
            val level = cursor.levelName(1, 2)
            if (level == CANTO) hasCanto = true
            if (level == CHAPTER || level == VERSE || level in INDEPENDENT_WORKS) hasOtherDivision = true
        }
        return hasCanto && !hasOtherDivision
    }

    /** Same keyset/lookahead discipline as the full outline, but with the smaller sibling budget.
     * The first query has no lower record bound: malformed NULL/nonpositive siblings must fail,
     * not be filtered away. Callers supply metadata-only SELECTs whose first column is c.record.
     */
    private suspend fun scanDirectChildren(parent: Int, select: String, visit: (Cursor) -> Unit) {
        var from: Long? = null
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val lower = from
            val keyset = if (lower == null) "" else " AND c.record>=?"
            val arguments = if (lower == null) arrayOf(parent.toString()) else arrayOf(parent.toString(), lower.toString())
            var count = 0
            var previous = 0
            database.rawQuery("$select WHERE c.parent=?$keyset ORDER BY c.record LIMIT $METADATA_BATCH", arguments).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val record = cursor.positiveInt(0)
                    valid(record > previous)
                    previous = record
                    if (++count > METADATA_PAGE_ROWS) continue
                    if (++total > MAX_DIRECT_CHILDREN) tooLarge()
                    visit(cursor)
                    from = record.toLong() + 1
                }
            }
            if (count <= METADATA_PAGE_ROWS) break
            yield()
        }
    }

    private suspend fun ancestors(start: Heading): List<Heading> {
        val result = mutableListOf<Heading>()
        var current = start
        while (true) {
            currentCoroutineContext().ensureActive()
            valid(result.size < MAX_DEPTH && result.none { it.node.record == current.node.record })
            result += current
            if (current.node.parent == 0) return result
            val parent = database.rawQuery(
                "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record=? LIMIT 2",
                arrayOf(current.node.parent.toString()),
            ).use {
                if (!it.moveToFirst()) fail()
                val found = it.heading()
                valid(!it.moveToNext())
                found
            }
            valid(parent.node.record < current.node.record && current.node.end <= parent.node.end)
            current = parent
        }
    }

    /** Also catches a preceding overlapping work, which a scan starting at this work cannot see.
     * Indexed parent queries, metadata only. Numeric levels and advertised child counts are not types.
     */
    private suspend fun validateAncestrySiblings(ancestry: List<Heading>) {
        for ((index, heading) in ancestry.withIndex()) {
            currentCoroutineContext().ensureActive()
            val parent = ancestry.getOrNull(index + 1)?.node
            var previousEnd = 0
            var found = false
            scanDirectChildren(heading.node.parent, "SELECT c.record,c.next_sibling FROM contents c") { cursor ->
                val start = cursor.positiveInt(0)
                val end = cursor.positiveInt(1)
                valid(start > previousEnd && end >= start)
                if (parent != null) valid(start > parent.record && end <= parent.end)
                if (start == heading.node.record) found = true
                previousEnd = end
            }
            valid(found)
        }
    }

    /** Total descendants are not direct siblings: a legitimate canto can have >20,000 headings.
     * Keep the complete metadata for validation, under separate row and encoded-string budgets.
     * SQL guards each string BEFORE it enters CursorWindow (including NUL/UTF-16 titles). Each
     * keyset query returns at most 512 rows: 511 consumed plus a checked lookahead, so a duplicate
     * contents/text join at a page boundary cannot disappear behind the next record key.
     */
    private suspend fun loadHeadings(work: ContentNode): List<Heading> {
        val headings = mutableListOf<Heading>()
        var bytes = 0L
        var from = work.record.toLong()
        while (from <= work.end) {
            currentCoroutineContext().ensureActive()
            var count = 0
            var previous = from - 1
            database.rawQuery(
                "$SELECT FROM contents c LEFT JOIN texts t ON t.recid=c.record " +
                    "WHERE c.record>=? AND c.record<=? ORDER BY c.record LIMIT $METADATA_BATCH",
                arrayOf(from.toString(), work.end.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val record = cursor.positiveInt(0)
                    valid(record > previous)
                    previous = record.toLong()
                    if (++count > METADATA_PAGE_ROWS) continue
                    if (headings.size == MAX_TOTAL_HEADINGS) tooLarge()
                    bytes += cursor.headingBytes()
                    if (bytes > MAX_METADATA_BYTES) tooLarge()
                    headings += cursor.heading()
                    from = record.toLong() + 1
                }
            }
            if (count <= METADATA_PAGE_ROWS) break
            yield()
        }
        return headings
    }

    private suspend fun outline(work: Heading, outsideParent: ContentNode?): BookOutline {
        val headings = loadHeadings(work.node)
        valid(headings.firstOrNull() == work)
        val stack = mutableListOf<Heading>()
        val children = mutableMapOf<Int, MutableList<Heading>>()
        var previous = 0
        var nestedWork = false
        for ((index, heading) in headings.withIndex()) {
            currentCoroutineContext().ensureActive()
            val node = heading.node
            valid(node.record > previous && node.end <= work.node.end)
            previous = node.record
            while (stack.isNotEmpty() && stack.last().node.end < node.record) stack.removeAt(stack.lastIndex)
            if (heading != work) {
                val parent = stack.lastOrNull() ?: fail()
                // A sibling cannot overlap, a descendant cannot bypass its actual enclosing parent.
                valid(node.parent == parent.node.record && node.end <= parent.node.end)
                if (heading.levelName in INDEPENDENT_WORKS) nestedWork = true
                if (heading.levelName == VERSE || heading.levelName == CHAPTER) {
                    valid(stack.none { it.levelName == VERSE })
                }
                val siblings = children.getOrPut(node.parent) { mutableListOf() }
                if (siblings.size == MAX_DIRECT_CHILDREN) tooLarge()
                siblings.add(heading)
            }
            valid(stack.size < MAX_DEPTH)
            stack += heading
            if ((index + 1) % METADATA_BATCH == 0) yield()
        }
        // A child claiming a parent in this scope but lying beyond its range is malformed too.
        for (batch in headings.chunked(METADATA_BATCH)) {
            currentCoroutineContext().ensureActive()
            val placeholders = batch.joinToString(",") { "?" }
            val arguments = (batch.map { it.node.record.toString() } +
                listOf(work.node.record.toString(), work.node.end.toString())).toTypedArray()
            database.rawQuery(
                "SELECT record FROM contents WHERE parent IN ($placeholders) AND (record<? OR record>?) LIMIT 1", arguments,
            ).use { valid(!it.moveToFirst()) }
            yield()
        }
        // Never flatten an anthology by skipping its nested works, nor read through them.
        if (nestedWork) throw ReadingException(ReadingFailure.CHOOSE_SECTION)
        for ((parent, siblings) in children) {
            if (siblings.none { it.node.title.isBlank() }) continue
            val titled = ContentTitles.interpolateBlank(siblings.map { it.node })
            children[parent] = siblings.mapIndexed { index, heading -> heading.copy(node = titled[index]) }.toMutableList()
        }

        val ranges = mutableListOf<BookEntry>()
        // A long chapter title is shared by all its entries, not normalized/copied per verse.
        val parentTitles = mutableMapOf<Int, String>()
        var partitioned = 0
        suspend fun partition(heading: Heading, parent: ContentNode?) {
            currentCoroutineContext().ensureActive()
            if (++partitioned % METADATA_BATCH == 0) yield()
            val source = heading.node
            val kind = when (heading.levelName) {
                VERSE -> ReadingUnitKind.VERSE
                CHAPTER -> ReadingUnitKind.CHAPTER
                else -> ReadingUnitKind.SECTION
            }
            fun add(start: Long, end: Int) {
                if (start > end) return
                if (ranges.size == MAX_ENTRIES) tooLarge()
                val node = if (start == source.record.toLong() && end == source.end) source
                    else source.copy(record = start.toInt(), end = end)
                val parentTitle = parent?.let { parentTitles.getOrPut(it.record) { it.displayTitle } }.orEmpty()
                ranges += BookEntry(node, source, kind, parentTitle)
            }
            if (kind == ReadingUnitKind.VERSE) {
                add(source.record.toLong(), source.end) // All subsections stay inside their original verse.
                return
            }
            var start = source.record.toLong()
            for (child in children[source.record].orEmpty()) {
                add(start, child.node.record - 1)
                partition(child, source)
                start = child.node.end.toLong() + 1
            }
            add(start, source.end)
        }
        partition(work, outsideParent)
        return BookOutline(work.node, chunkProse(work.node, ranges))
    }

    /** One indexed, keyset-paged metadata scan, never a whole-book plain-text join/materialization.
     * Encoded byte lengths conservatively bound prose below the character budget. One larger paragraph
     * gets its own entry (and is checked against the legacy hard limits when loaded), never sliced.
     */
    private suspend fun chunkProse(work: ContentNode, ranges: List<BookEntry>): List<BookEntry> {
        val entries = mutableListOf<BookEntry>()
        var index = 0
        var start = ranges.first().node.record
        var rows = 0
        var bytes = 0L
        fun emit(end: Int) {
            if (entries.size == MAX_ENTRIES) tooLarge()
            val entry = ranges[index]
            entries += if (start == entry.node.record && end == entry.node.end) entry
                else entry.copy(node = entry.node.copy(record = start, end = end))
        }
        fun finish() {
            emit(ranges[index].node.end)
            index++
            if (index < ranges.size) start = ranges[index].node.record
            rows = 0
            bytes = 0
        }
        var from = work.record.toLong()
        while (from <= work.end) {
            currentCoroutineContext().ensureActive()
            val page = database.rawQuery(
                "SELECT recid,coalesce(length(CAST(plain AS BLOB)),0),$LEVEL_SELECT,$LEVEL_BYTES FROM texts t " +
                    "WHERE recid>=? AND recid<=? ORDER BY recid LIMIT $METADATA_BATCH",
                arrayOf(from.toString(), work.end.toString()),
            ).use { cursor -> buildList {
                var previous = from - 1
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val record = cursor.positiveInt(0)
                    valid(record > previous)
                    previous = record.toLong()
                    add(RecordSize(record, cursor.getLong(1), cursor.levelName(2, 3)))
                }
            } }
            if (page.isEmpty()) break
            for (row in page.take(METADATA_PAGE_ROWS)) {
                currentCoroutineContext().ensureActive()
                // An unindexed semantic work marker would otherwise silently cross a book boundary.
                valid(row.levelName !in INDEPENDENT_WORKS || row.record == work.record)
                while (row.record > ranges[index].node.end) finish()
                if (ranges[index].kind != ReadingUnitKind.VERSE) {
                    if (rows > 0 && (rows == PROSE_ROWS || bytes + row.bytes > PROSE_BYTES)) {
                        emit(row.record - 1)
                        start = row.record
                        rows = 0
                        bytes = 0
                    }
                    rows++
                    bytes += row.bytes
                }
            }
            if (page.size <= METADATA_PAGE_ROWS) break
            from = page[METADATA_PAGE_ROWS - 1].record.toLong() + 1
            yield()
        }
        while (index < ranges.size) finish()
        return entries
    }

    /** Partition each contiguous semantic scope from its beginning, never from a selection.
     * The second keyset scan reads only sizes/levels. Each profile caches its own partition without
     * changing the outline, its prose chunks, or the hard work boundaries. Oversized entries stand
     * alone; completeText still enforces the same hard guards when those entries are loaded.
     */
    private suspend fun resourceRanges(outline: BookOutline, profile: PartitionProfile): List<ResourceRange> {
        val entries = outline.entries
        val sources = entries.associateBy { it.source.record }
        val cantos = mutableSetOf<Int>()
        val rows = IntArray(entries.size)
        val characters = LongArray(entries.size)
        val bytes = LongArray(entries.size)
        var entryIndex = 0
        var from = outline.book.record.toLong()
        while (from <= outline.book.end) {
            currentCoroutineContext().ensureActive()
            var count = 0
            var previous = from - 1
            database.rawQuery(
                "SELECT recid,coalesce(length(plain),0),coalesce(length(CAST(plain AS BLOB)),0)," +
                    "coalesce(instr(plain,char(0)),0),$LEVEL_SELECT,$LEVEL_BYTES FROM texts t " +
                    "WHERE recid>=? AND recid<=? ORDER BY recid LIMIT $METADATA_BATCH",
                arrayOf(from.toString(), outline.book.end.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val record = cursor.positiveInt(0)
                    valid(record > previous)
                    previous = record.toLong()
                    // Validate the extra row too, so a duplicate at a page boundary cannot be skipped.
                    if (++count > METADATA_PAGE_ROWS) continue
                    while (record > entries[entryIndex].node.end) entryIndex++
                    val level = cursor.levelName(4, 5)
                    valid(level !in INDEPENDENT_WORKS || record == outline.book.record)
                    if (level == CANTO && record in sources) cantos += record
                    val byteCount = cursor.getLong(2)
                    // SQLite length(text) stops at NUL. For those rare rows use an encoded-byte
                    // upper bound, not an underestimated prefix. An isolated entry is still checked
                    // by its actual complete Unicode character count when loaded (UTF-8 or UTF-16).
                    val characterCount = if (cursor.getLong(3) > 0) maxOf(cursor.getLong(1), byteCount)
                        else cursor.getLong(1)
                    rows[entryIndex] = minOf(MAX_RECORDS + 1, rows[entryIndex] + 1)
                    characters[entryIndex] = minOf(MAX_CHARACTERS + 1, characters[entryIndex] + characterCount)
                    bytes[entryIndex] = minOf(MAX_CHARACTERS * 4 + 1, bytes[entryIndex] + byteCount)
                    from = record.toLong() + 1
                }
            }
            if (count <= METADATA_PAGE_ROWS) break
            yield()
        }

        val scopes = mutableMapOf<Int, BookEntry>()
        fun scope(index: Int): BookEntry = scopes.getOrPut(entries[index].source.record) {
            resourceScope(entries[index], sources, cantos, outline.book.record)
        }
        val ranges = mutableListOf<ResourceRange>()
        var first = 0
        var current = scope(0)
        var verseCount = 0
        var rowCount = 0
        var characterCount = 0L
        var byteCount = 0L
        fun emit(last: Int) {
            val source = current.source
            val start = entries[first].node.record
            val end = entries[last].node.end
            val node = if (start == source.record && end == source.end) source else source.copy(record = start, end = end)
            ranges += ResourceRange(first, last, node, current.kind, current.parentTitle)
        }
        for (index in entries.indices) {
            currentCoroutineContext().ensureActive()
            val next = scope(index)
            val verses = if (entries[index].kind == ReadingUnitKind.VERSE) 1 else 0
            // Test BEFORE adding a whole entry. The first entry is always admitted on its own;
            // even a legacy prose chunk can exceed a soft target and must not be sliced again.
            if (index > first && (next.source.record != current.source.record ||
                    verseCount + verses > profile.maxVerses || rowCount + rows[index] > profile.maxRows ||
                    characterCount + characters[index] > MAX_CHARACTERS || byteCount + bytes[index] > profile.maxBytes)) {
                emit(index - 1)
                first = index
                current = next
                verseCount = 0
                rowCount = 0
                characterCount = 0
                byteCount = 0
            }
            verseCount += verses
            rowCount += rows[index]
            characterCount += characters[index]
            byteCount += bytes[index]
            if ((index + 1) % METADATA_BATCH == 0) yield()
        }
        emit(entries.lastIndex)
        return ranges.toList()
    }

    /** Prefer the nearest semantic chapter, including prose/header entries; otherwise use a canto
     * (e.g. an appendix), then the work root. Searching stops at the already-validated work, so an
     * enclosing anthology's chapter can never absorb a nested independent book. Returning to an
     * outer scope after a subchapter starts a new contiguous run rather than joining across it.
     */
    private fun resourceScope(entry: BookEntry, sources: Map<Int, BookEntry>, cantos: Set<Int>, work: Int): BookEntry {
        var current = entry
        var canto: BookEntry? = null
        repeat(MAX_DEPTH) {
            if (current.kind == ReadingUnitKind.CHAPTER) return current
            if (canto == null && current.source.record in cantos) canto = current
            if (current.source.record == work) return canto ?: current
            current = sources[current.source.parent] ?: fail()
        }
        fail()
    }

    private suspend fun completeText(node: ContentNode): List<Paragraph> {
        currentCoroutineContext().ensureActive()
        val arguments = arrayOf(node.record.toString(), node.end.toString())
        // Stop at the hard limits instead of aggregating an arbitrarily oversized source range.
        // Only lengths cross the cursor before the guard passes; no partial text can escape.
        database.rawQuery(
            "SELECT length(plain),length(CAST(plain AS BLOB)) FROM texts " +
                "WHERE recid BETWEEN ? AND ? ORDER BY recid LIMIT ${MAX_RECORDS + 1}", arguments,
        ).use { cursor ->
            var rows = 0
            var characters = 0L
            var bytes = 0L
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                rows++
                characters += cursor.getLong(0)
                bytes += cursor.getLong(1)
                if (rows > MAX_RECORDS || characters > MAX_CHARACTERS || bytes > MAX_CHARACTERS * 4) tooLarge()
                if (rows % PROSE_ROWS == 0) yield()
            }
        }
        currentCoroutineContext().ensureActive()
        return database.rawQuery(
            "SELECT recid,coalesce(length(CAST(plain AS BLOB)),0)," +
                "CASE WHEN length(CAST(plain AS BLOB))<=$TEXT_BATCH THEN plain ELSE NULL END,stylename,levelname " +
                "FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid LIMIT ${MAX_RECORDS + 1}", arguments,
        ).use { cursor -> buildList {
            var previous = node.record.toLong() - 1
            var characters = 0L
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (size == MAX_RECORDS) tooLarge()
                val record = cursor.positiveInt(0)
                valid(record > previous)
                previous = record.toLong()
                val byteCount = cursor.getLong(1)
                val plain = if (byteCount <= TEXT_BATCH) cursor.getString(2).orEmpty() else largeParagraph(record, byteCount)
                // Match SQLite's Unicode character count, also counting any text after embedded NUL.
                characters += plain.codePointCount(0, plain.length)
                if (characters > MAX_CHARACTERS) tooLarge()
                add(Paragraph(record, plain, cursor.getString(3).orEmpty().trim(), cursor.getString(4).orEmpty()))
                if (size % PROSE_ROWS == 0) yield()
            }
        } }
    }

    /** Assemble one oversized row below CursorWindow limits; byte slicing also preserves embedded NUL. */
    private suspend fun largeParagraph(record: Int, byteCount: Long): String {
        if (byteCount > MAX_CHARACTERS * 4) tooLarge()
        val bytes = ByteArrayOutputStream(byteCount.toInt())
        var offset = 0L
        while (offset < byteCount) {
            currentCoroutineContext().ensureActive()
            val part = database.rawQuery(
                "SELECT substr(CAST(plain AS BLOB),?,$TEXT_BATCH) FROM texts WHERE recid=? LIMIT 1",
                arrayOf((offset + 1).toString(), record.toString()),
            ).use { valid(it.moveToFirst()); it.getBlob(0) }
            valid(part.isNotEmpty() && offset + part.size <= byteCount)
            bytes.write(part)
            offset += part.size
            yield()
        }
        // CAST(text AS BLOB) uses the database's encoding, not necessarily UTF-8.
        val encoding = database.rawQuery("PRAGMA encoding", null).use {
            valid(it.moveToFirst())
            when (it.getString(0).lowercase()) {
                "utf-8" -> Charsets.UTF_8
                "utf-16le" -> Charsets.UTF_16LE
                "utf-16be" -> Charsets.UTF_16BE
                else -> fail()
            }
        }
        return bytes.toString(encoding.name())
    }

    private fun Cursor.heading(): Heading {
        headingBytes()
        val record = positiveInt(0)
        valid(getType(1) == Cursor.FIELD_TYPE_INTEGER && getLong(1) in 0..Int.MAX_VALUE.toLong())
        val end = positiveInt(3)
        valid(end >= record)
        return Heading(ContentNode(record, getInt(1), getString(2).orEmpty(), end, getInt(4), getInt(5), getString(6).orEmpty()),
            getString(7).orEmpty().trim())
    }

    private fun Cursor.headingBytes(): Long {
        val title = getLong(8)
        val code = getLong(9)
        val level = getLong(10)
        if (code > MAX_METADATA_FIELD_BYTES || level > MAX_METADATA_FIELD_BYTES) tooLarge()
        // A long title is shortened in the SELECT, never a reason to refuse the section.
        return minOf(title, MAX_TITLE_BYTES.toLong()) + code + level
    }

    private fun Cursor.levelName(column: Int, bytesColumn: Int): String {
        if (getLong(bytesColumn) > MAX_METADATA_FIELD_BYTES) tooLarge()
        return getString(column).orEmpty().trim()
    }

    private fun Cursor.positiveInt(column: Int): Int {
        valid(getType(column) == Cursor.FIELD_TYPE_INTEGER && getLong(column) in 1..Int.MAX_VALUE.toLong())
        return getInt(column)
    }

    /** Shared by a repository, keyed by connection identity AND root, at most four outlines total.
     * Concurrent builders converge on one snapshot. clear() prevents an in-flight build repopulating
     * a closed repository's cache. The two separate partitions belong to the same LRU snapshot;
     * no paragraph lists, HTML or database ownership live in the cache.
     */
    internal class OutlineCache {
        private data class Key(val database: SQLiteDatabase, val record: Int)
        private data class Snapshot(
            val outline: BookOutline,
            val partitions: MutableMap<PartitionProfile, List<ResourceRange>> = mutableMapOf(),
        )
        private val entries = LinkedHashMap<Key, Snapshot>(4, 0.75f, true)
        private var generation = 0L

        suspend fun get(database: SQLiteDatabase, record: Int, build: suspend () -> BookOutline): BookOutline {
            val key = Key(database, record)
            val version = synchronized(this) {
                entries[key]?.let { return it.outline }
                generation
            }
            val outline = build()
            currentCoroutineContext().ensureActive()
            return synchronized(this) {
                valid(version == generation && database.isOpen)
                entries[key]?.outline ?: outline.also {
                    entries[key] = Snapshot(it)
                    if (entries.size > 4) entries.remove(entries.keys.first())
                }
            }
        }

        suspend fun resources(
            database: SQLiteDatabase,
            outline: BookOutline,
            profile: PartitionProfile = PartitionProfile.LEGACY,
            build: suspend () -> List<ResourceRange>,
        ): List<ResourceRange> {
            val key = Key(database, outline.book.record)
            val version = synchronized(this) {
                entries[key]?.takeIf { it.outline === outline }?.partitions?.get(profile)?.let { return it }
                generation
            }
            val ranges = build()
            currentCoroutineContext().ensureActive()
            return synchronized(this) {
                valid(version == generation && database.isOpen)
                val snapshot = entries[key]?.takeIf { it.outline === outline }
                // An evicted outline can finish its current read, but cannot reinsert itself or
                // attach ranges to a different snapshot built concurrently for the same root.
                if (snapshot == null) ranges else snapshot.partitions[profile] ?: ranges.also { snapshot.partitions[profile] = it }
            }
        }

        fun clear() = synchronized(this) { generation++; entries.clear() }
    }

    companion object {
        private const val VERSE = "LE_Verse_Number"
        private const val CHAPTER = "LE_Chapter"
        private const val CANTO = "LE_Canto"
        private const val COLLECTION = "LE_Collection"
        private val INDEPENDENT_WORKS = setOf("LE_Book", COLLECTION)
        private const val MAX_DEPTH = 64
        private const val MAX_DIRECT_CHILDREN = 20_000
        private const val MAX_TOTAL_HEADINGS = 100_000
        private const val MAX_TITLE_BYTES = 8_192
        /** Characters kept from an oversized title; the full text is still read from the paragraph itself. */
        private const val TITLE_DISPLAY_CHARACTERS = 512
        private const val MAX_METADATA_FIELD_BYTES = 256
        private const val MAX_METADATA_BYTES = 8_000_000L
        private const val MAX_ENTRIES = 40_000
        private const val MAX_RECORDS = 12_000
        private const val MAX_CHARACTERS = 4_000_000L
        private const val PROSE_ROWS = 256
        private const val PROSE_BYTES = 512_000L
        private const val METADATA_BATCH = 512
        private const val METADATA_PAGE_ROWS = METADATA_BATCH - 1
        private const val TEXT_BATCH = 131_072
        private const val TITLE_BYTES = "coalesce(length(CAST(c.title AS BLOB)),0)"
        private const val CODE_BYTES = "coalesce(length(CAST(c.node_code AS BLOB)),0)"
        private const val LEVEL_BYTES = "coalesce(length(CAST(t.levelname AS BLOB)),0)"
        private const val LEVEL_SELECT = "CASE WHEN $LEVEL_BYTES<=$MAX_METADATA_FIELD_BYTES THEN t.levelname ELSE NULL END"
        private const val SELECT = "SELECT c.record,c.parent," +
            "CASE WHEN $TITLE_BYTES<=$MAX_TITLE_BYTES THEN c.title ELSE substr(c.title,1,$TITLE_DISPLAY_CHARACTERS) END," +
            "c.next_sibling,c.node_children,c.level," +
            "CASE WHEN $CODE_BYTES<=$MAX_METADATA_FIELD_BYTES THEN c.node_code ELSE NULL END," +
            "$LEVEL_SELECT,$TITLE_BYTES,$CODE_BYTES,$LEVEL_BYTES"
        private fun valid(condition: Boolean) { if (!condition) fail() }
        private fun fail(): Nothing = throw ReadingException(ReadingFailure.LOAD)
        private fun tooLarge(): Nothing = throw ReadingException(ReadingFailure.SECTION_TOO_LARGE)
    }
}