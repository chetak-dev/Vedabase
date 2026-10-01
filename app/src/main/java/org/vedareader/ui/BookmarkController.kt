package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkException
import org.vedareader.data.BookmarkIssue

/**
 * Caller-thread state: invoke on the scope's thread (normally main), like SearchController.
 * The owner calls reload after initialization; construction never invokes onChange or a loader.
 * Store callbacks and the VM's create callback own their worker dispatching. No source access,
 * optimistic edits, automatic retries, or ownership of the supplied scope live here.
 */
class BookmarkController(
    private val scope: CoroutineScope,
    private val load: suspend () -> List<Bookmark>,
    private val add: suspend (Bookmark) -> List<Bookmark>,
    private val remove: suspend (String, String) -> List<Bookmark>,
    private val onChange: (BookmarkUiState) -> Unit,
) {
    var state: BookmarkUiState = BookmarkUiState()
        private set

    private var job: Job? = null
    private var generation = 0L
    private var feedbackId = 0L
    private var loaded = false
    private var closed = false

    fun reload() {
        // Initial loading=true describes the UI, not a running job. The first reload must start.
        if (!active() || job != null || state.busy) return
        loaded = false
        start(loading = true, message = null) { load() }
    }

    fun add(create: suspend () -> Bookmark) {
        if (!canWrite()) return
        start(loading = false, message = BookmarkMessage.SAVED) {
            val bookmark = create()
            // A non-cooperative snapshot loader must not start a write after close/cancellation.
            currentCoroutineContext().ensureActive()
            add(bookmark)
        }
    }

    fun remove(bookmark: Bookmark) {
        if (!canWrite()) return
        // IDs are library-local. Also reject stale/forged snapshots of an existing key.
        if (state.items.none { it == bookmark }) return
        start(loading = false, message = BookmarkMessage.REMOVED) {
            remove(bookmark.id, bookmark.location.libraryId)
        }
    }

    fun acknowledge(id: Long) {
        if (closed || state.feedback?.id != id) return
        publish(state.copy(feedback = null))
    }

    /** Terminal and silent, including when a store has already entered a durable commit. */
    fun close() {
        if (closed) return
        closed = true
        generation++
        val pending = job
        job = null
        pending?.cancel()
    }

    private fun active() = !closed && scope.isActive

    private fun canWrite() = active() && loaded && job == null &&
        !state.loading && !state.busy && state.error == null

    private fun start(
        loading: Boolean,
        message: BookmarkMessage?,
        operation: suspend () -> List<Bookmark>,
    ) {
        val request = ++generation
        fun current() = !closed && generation == request
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            val items = try {
                currentCoroutineContext().ensureActive()
                // Detach store-owned lists; older published snapshots must remain unchanged.
                operation().toList().also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (current()) {
                    val issue = if (loading) BookmarkIssue.LOAD else
                        (failure as? BookmarkException)?.issue ?: BookmarkIssue.SAVE
                    val failed = when (issue) {
                        BookmarkIssue.LOAD -> BookmarkMessage.LOAD_FAILED
                        BookmarkIssue.SAVE -> BookmarkMessage.SAVE_FAILED
                        BookmarkIssue.LIMIT -> BookmarkMessage.LIMIT_REACHED
                    }
                    publish(state.copy(loading = false, busy = false, error = issue,
                        feedback = feedback(failed)))
                }
                return@launch
            }
            if (current()) {
                if (loading) loaded = true
                publish(state.copy(items = items, loading = false, busy = false, error = null,
                    feedback = message?.let(::feedback)))
            }
        }
        // Reserve the single slot before callbacks or immediate/unconfined execution can reenter.
        job = pending
        publish(state.copy(loading = loading, busy = !loading, error = null, feedback = null))
        // Unlike a body finally, this also runs if cancellation precedes the first dispatch.
        pending.invokeOnCompletion {
            if (current() && job === pending) {
                job = null
                if (state.loading || state.busy) publish(state.copy(loading = false, busy = false))
            }
        }
        pending.start()
    }

    private fun feedback(message: BookmarkMessage) = BookmarkFeedback(++feedbackId, message)

    private fun publish(value: BookmarkUiState) {
        if (closed || value == state) return
        state = value
        onChange(value)
    }
}