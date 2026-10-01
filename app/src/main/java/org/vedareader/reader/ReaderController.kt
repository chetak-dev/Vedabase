package org.vedareader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.vedareader.data.BookPosition
import org.vedareader.data.BookReadingSection
import org.vedareader.data.BookResource
import org.vedareader.data.ContentNode
import org.vedareader.data.ReadingException
import org.vedareader.data.ReadingFailure
import org.vedareader.data.ReadingLocation
import org.vedareader.data.ReadingSection

data class ReaderDocument(
    val section: ReadingSection,
    val html: String,
    val bookPosition: BookPosition? = null,
    val bookResource: BookResource? = null,
)

data class ReaderState(
    val location: ReadingLocation,
    val languageTag: String,
    val document: ReaderDocument? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val failure: ReadingFailure = ReadingFailure.LOAD,
    val scrollY: Int = 0,
    val mode: ReadingMode = ReadingMode.VERSE,
    val enterAtEnd: Boolean = false,
)

/** Loads one bounded section in either mode; no whole-book HTML or other-mode document cache. */
class ReaderController(
    private val scope: CoroutineScope,
    private val loadDocument: suspend (ReadingLocation, String) -> ReaderDocument,
    private val onChange: (ReaderState?) -> Unit,
    private val loadBookDocument: suspend (ReadingLocation, String) -> ReaderDocument = loadDocument,
    initialMode: ReadingMode = ReadingMode.VERSE,
    private val onModeChange: (ReadingMode) -> Unit = {},
) {
    var state: ReaderState? = null
        private set
    var selectedMode: ReadingMode = initialMode
        private set

    private data class BookIdentity(val library: String, val node: ContentNode)
    private data class ScrollSnapshot(val library: String, val start: Int, val end: Int, val offset: Int)
    private data class Request(
        val state: ReaderState,
        // On failure this remains available without retaining the previous document/HTML.
        val otherModeLocation: ReadingLocation = state.location,
        val book: BookIdentity? = null,
        val scroll: ScrollSnapshot? = null,
    )

    private var pending: Job? = null
    private var activeRequest: Request? = null
    private var generation = 0L

    fun open(library: String, language: String, node: ContentNode) {
        require(library.isNotBlank() && node.record > 0 && node.end >= node.record)
        open(node.location(library), language)
    }

    /** Explicit search opens preserve the matching paragraph, not only its containing heading. */
    fun open(location: ReadingLocation, language: String) {
        require(location.libraryId.isNotBlank() && location.start > 0 && location.end >= location.start &&
            location.position in location.start..location.end)
        // Explicit opens can select another work/library, but do not reset the mode preference.
        load(Request(ReaderState(location.copy(scrollY = 0), language, mode = selectedMode)))
    }

    fun next() = move(forward = true)
    fun previous() = move(forward = false)

    private fun move(forward: Boolean) {
        val current = state?.takeUnless { it.loading || it.failed } ?: return
        val document = current.document ?: return
        val section = document.section
        val target = (if (forward) section.next else section.previous) ?: return
        val resource = document.bookResource
        val position = document.bookPosition.takeIf { current.mode == ReadingMode.BOOK }
        // A resource can contain many entries. Previous selects its final entry, not the
        // entry before the original selection; the host still receives explicit end intent.
        val anchor = if (resource != null && !forward) target.end else target.record
        val source = when {
            resource != null -> resource.position.sourceAt(anchor) ?: return
            position != null -> position.outline.entries[position.index + if (forward) 1 else -1].source
            else -> target
        }
        load(Request(
            ReaderState(target.location(current.location.libraryId).copy(position = anchor), current.languageTag,
                mode = current.mode, enterAtEnd = !forward && (resource != null || current.mode == ReadingMode.BOOK)),
            otherModeLocation = source.location(current.location.libraryId).let {
                if (resource != null) it.copy(position = anchor) else it
            },
            book = activeRequest?.book,
        ))
    }

    /** Toggle even after a failed load, but never replace an in-flight navigation. */
    fun toggleMode() {
        val current = state?.takeUnless { it.loading } ?: return
        val previousRequest = activeRequest ?: return
        val mode = if (current.mode == ReadingMode.VERSE) ReadingMode.BOOK else ReadingMode.VERSE
        val resource = current.document?.bookResource
        val target = when {
            current.failed -> previousRequest.otherModeLocation
            resource != null -> {
                val source = resource.position.sourceAt(current.location.position) ?: return
                source.location(current.location.libraryId).copy(position = current.location.position)
            }
            current.mode == ReadingMode.BOOK -> {
                val document = current.document ?: return
                val position = document.bookPosition ?: return
                val source = position.outline.entries[position.index].source
                source.location(current.location.libraryId)
            }
            else -> current.location
        }
        // Pixel offsets are compatible only for legacy same-range documents, never across
        // paged/scrolling resource layouts. Both resource loaders accept the source anchor.
        val scroll = when {
            current.failed -> previousRequest.scroll
            resource != null -> null
            else -> current.document?.section?.node?.let {
                ScrollSnapshot(current.location.libraryId, it.record, it.end, current.scrollY)
            }
        }
        load(Request(
            ReaderState(target, current.languageTag, mode = mode),
            otherModeLocation = current.location,
            book = previousRequest.book,
            scroll = scroll,
        ), modeChanged = true)
    }

    /** Regenerate the current resource document (e.g. after a typeface change) at the same paragraph. */
    fun reloadDocument() {
        val current = state?.takeUnless { it.loading || it.failed } ?: return
        val previousRequest = activeRequest ?: return
        val resource = current.document?.bookResource ?: return
        val source = resource.position.sourceAt(current.location.position) ?: return
        load(Request(
            ReaderState(source.location(current.location.libraryId).copy(position = current.location.position),
                current.languageTag, mode = current.mode),
            otherModeLocation = current.location,
            book = previousRequest.book,
        ))
    }

    /** Only exact entry starts from the currently displayed book outline are valid jump targets. */
    fun jumpToBookEntry(record: Int) {
        val current = state?.takeUnless { it.loading || it.failed } ?: return
        val document = current.document ?: return
        if (document.bookResource == null && current.mode != ReadingMode.BOOK) return
        val position = document.bookResource?.position ?: document.bookPosition ?: return
        val entry = position.outline.entries.firstOrNull { it.node.record == record } ?: return
        load(Request(
            ReaderState(entry.node.location(current.location.libraryId), current.languageTag, mode = current.mode),
            otherModeLocation = entry.source.location(current.location.libraryId).let {
                if (document.bookResource != null) it.copy(position = entry.node.record) else it
            },
            book = activeRequest?.book,
        ))
    }

    /** Save a visible source paragraph without replacing the loaded resource or its HTML. */
    fun saveBookAnchor(record: Int) {
        val current = state?.takeUnless { it.loading || it.failed } ?: return
        val document = current.document ?: return
        val resource = document.bookResource ?: return
        if (record !in current.location.start..current.location.end ||
            document.section.paragraphs.binarySearchBy(record) { it.record } < 0) return
        val source = resource.position.sourceAt(record) ?: return
        val location = current.location.copy(position = record, title = source.displayTitle)
        if (location != current.location || current.enterAtEnd) publish(current.copy(location = location, enterAtEnd = false))
    }

    fun saveScroll(position: Int) {
        val current = state?.takeUnless { it.loading || it.failed } ?: return
        val offset = position.coerceAtLeast(0)
        // The native host reports the actual offset after settling an enter-at-end request.
        if (offset != current.scrollY || current.enterAtEnd) publish(current.copy(scrollY = offset, enterAtEnd = false))
    }

    fun retry() {
        val current = state?.takeIf { it.failed && !it.loading } ?: return
        activeRequest?.let { load(it.copy(state = current)) }
    }

    fun close() {
        invalidate()
        activeRequest = null
        publish(null)
    }

    private fun load(requested: Request, modeChanged: Boolean = false) {
        invalidate()
        val request = generation
        val loading = requested.state.copy(loading = true, failed = false, document = null)
        activeRequest = requested.copy(state = loading)
        selectedMode = loading.mode
        publish(loading)
        if (request != generation) return
        if (modeChanged) {
            onModeChange(loading.mode)
            if (request != generation) return
        }
        // Assign before starting: immediate dispatch or re-entrant observers must not overwrite
        // the pending job of a newer open/close request.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loader = if (loading.mode == ReadingMode.BOOK) loadBookDocument else loadDocument
                val document = loader(loading.location, loading.languageTag)
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                val book = validate(document, loading, requested.book)
                val unit = document.section.node
                val resource = document.bookResource
                val location = unit.location(loading.location.libraryId).let {
                    if (resource != null) it.copy(position = loading.location.position, title = resource.source.displayTitle)
                    else it
                }
                val scroll = if (resource != null) 0 else requested.scroll?.takeIf {
                    it.library == loading.location.libraryId && it.start == unit.record && it.end == unit.end
                }?.offset ?: loading.scrollY
                activeRequest = requested.copy(state = loading, book = book)
                publish(loading.copy(location = location,
                    loading = false, document = document, scrollY = scroll))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (request == generation) publish(loading.copy(loading = false, failed = true,
                    failure = (error as? ReadingException)?.reason ?: ReadingFailure.LOAD))
            }
        }
        pending = job
        job.start()
    }

    private fun validate(document: ReaderDocument, loading: ReaderState, expectedBook: BookIdentity?): BookIdentity? {
        val section = document.section
        val unit = section.node
        val resource = document.bookResource
        check(unit.record > 0 && unit.end >= unit.record)
        if (resource != null || loading.mode == ReadingMode.BOOK) {
            // Front matter/prose may be split: only the selected anchor must fit the loaded chunk.
            check(loading.location.position in unit.record..unit.end)
        } else {
            // Legacy purports may promote to containing verses, never unrelated or partial units.
            check(loading.location.start in unit.record..unit.end && loading.location.end <= unit.end)
        }
        val records = section.paragraphs.map { it.record }
        check(records.all { it in unit.record..unit.end } && records.zipWithNext().all { (a, b) -> a < b })
        section.next?.let { check(it.record > unit.end && it.end >= it.record) }
        section.previous?.let { check(it.record > 0 && it.end >= it.record && it.end < unit.record) }

        val book = if (resource != null || loading.mode == ReadingMode.BOOK) {
            val position = if (resource != null) {
                // BookResource enforces inclusive entry bounds, the selected entry/anchor and
                // adjacent resource-or-entry neighbours. The document must be that exact section.
                check(section == resource.section)
                check(resource.anchorRecord == loading.location.position)
                resource.position
            } else {
                // Retain the single-entry contract for existing bookSection loaders/fixtures.
                checkNotNull(document.bookPosition).also { BookReadingSection(section, it) }
            }
            val root = position.outline.book
            check(root.record > 0 && root.end >= root.record)
            check(position.outline.entries.all {
                it.source.record >= root.record && it.source.end <= root.end &&
                    it.node.title == it.source.title
            })
            BookIdentity(loading.location.libraryId, root).also {
                check(expectedBook == null || it == expectedBook)
            }
        } else expectedBook
        book?.let {
            check(it.library == loading.location.libraryId)
            check(unit.record >= it.node.record && unit.end <= it.node.end)
            section.previous?.let { previous -> check(previous.record >= it.node.record) }
            section.next?.let { next -> check(next.end <= it.node.end) }
        }
        return book
    }

    /** Outline ranges are ordered and disjoint; source ranges may overlap (chapter/prose). */
    private fun BookPosition.sourceAt(record: Int): ContentNode? {
        val entries = outline.entries
        val index = entries.binarySearch {
            when {
                it.node.end < record -> -1
                it.node.record > record -> 1
                else -> 0
            }
        }
        return entries.getOrNull(index)?.source
    }

    private fun invalidate() { generation++; pending?.cancel(); pending = null }
    private fun publish(next: ReaderState?) { state = next; onChange(next) }
}