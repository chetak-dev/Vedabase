package org.vedareader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.vedareader.data.ContentNode
import org.vedareader.data.ChapterOverview

data class BrowseState(
    val libraryId: String? = null,
    val path: List<ContentNode> = emptyList(),
    val entries: List<ContentNode> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val chapter: ChapterOverview? = null,
    val introductionRecords: Set<Int> = emptySet(),
    /** Semantic level names of this library, so counts can be labelled chapters/verses. */
    val levelNames: Map<Int, String> = emptyMap(),
) {
    val current: ContentNode? get() = path.lastOrNull()
}

/** Pure labelling of contents counts by semantic level; unknown levels fall back to a neutral noun. */
enum class ContentsNoun { BOOKS, CANTOS, CHAPTERS, VERSES, TEXTS, ITEMS }

object ContentsNouns {
    private fun noun(levelName: String?): ContentsNoun? = when (levelName?.trim()) {
        "LE_Collection", "LE_Writings", "LE_Book", "LE_ContentsBook" -> ContentsNoun.BOOKS
        "LE_Canto" -> ContentsNoun.CANTOS
        "LE_Chapter", "LE_Section" -> ContentsNoun.CHAPTERS
        "LE_Verse_Number" -> ContentsNoun.VERSES
        "LE_Verse_Section", "LE_Verse_Text" -> ContentsNoun.TEXTS
        null, "" -> null
        else -> ContentsNoun.ITEMS
    }

    /** What the loaded children are, by their most common semantic level. */
    fun forChildren(children: List<ContentNode>, levelNames: Map<Int, String>): ContentsNoun {
        val level = children.groupingBy { it.level }.eachCount().maxByOrNull { it.value }?.key ?: return ContentsNoun.ITEMS
        return noun(levelNames[level]) ?: ContentsNoun.ITEMS
    }

    /** What a node's children usually are, from the node's own level, before they are loaded. */
    fun forParent(node: ContentNode, levelNames: Map<Int, String>): ContentsNoun = when (noun(levelNames[node.level])) {
        ContentsNoun.BOOKS -> if (levelNames[node.level]?.trim() == "LE_Book") ContentsNoun.CHAPTERS else ContentsNoun.BOOKS
        ContentsNoun.CANTOS -> ContentsNoun.CHAPTERS
        ContentsNoun.CHAPTERS -> ContentsNoun.VERSES
        ContentsNoun.VERSES -> ContentsNoun.TEXTS
        else -> ContentsNoun.ITEMS
    }
}

/** Main-thread navigation state; the supplied loader is responsible for off-thread database I/O. */
class BrowseNavigator(
    private val scope: CoroutineScope,
    private val loadChildren: suspend (library: String, parent: Int) -> List<ContentNode>,
    private val onChange: (BrowseState) -> Unit,
    private val loadOverview: suspend (String, Int, List<ContentNode>) -> ChapterOverview? = { _, _, _ -> null },
    private val onReadLeaf: ((String, ContentNode) -> Unit)? = null,
    private val loadIntroduction: suspend (String, Int, List<ContentNode>) -> Set<Int> = { _, _, _ -> emptySet() },
    private val loadLevelNames: suspend (String) -> Map<Int, String> = { emptyMap() },
) {
    var state = BrowseState()
        private set
    private var roots = emptyList<ContentNode>()
    private var pending: Job? = null
    private var generation = 0
    private var levelNames: Pair<String, Map<Int, String>>? = null

    fun setLibrary(id: String?, collections: List<ContentNode>) {
        roots = collections
        if (state.libraryId == id) return
        invalidate()
        publish(BrowseState(libraryId = id))
    }

    fun open(record: Int) {
        if (state.libraryId == null || state.loading || state.failed) return
        val available = if (state.current == null) roots else state.entries
        val parent = state.current?.record ?: 0
        val selected = available.firstOrNull { it.record == record && it.parent == parent } ?: return
        if (state.path.any { it.record == selected.record }) return
        load(state.path + selected, readingFallback = state)
    }

    fun back() { if (state.path.isNotEmpty()) goToDepth(state.path.size - 1) }

    /** A reader tap does not push a verse into the contents path, so Back restores the chapter. */
    fun readingTarget(record: Int): ContentNode? {
        if (state.loading || state.failed || state.libraryId == null) return null
        val preview = state.chapter?.verses?.firstOrNull { it.node.record == record } ?: return null
        return state.entries.firstOrNull { it == preview.node && it.parent == state.current?.record }
    }

    /** Depth zero is the library. Other depths refer only to ancestors in the current path. */
    fun goToDepth(depth: Int) {
        if (depth !in 0 until state.path.size) return
        if (depth == 0) {
            invalidate()
            publish(BrowseState(libraryId = state.libraryId))
        } else load(state.path.take(depth))
    }

    fun retry() { if (state.failed && state.path.isNotEmpty()) load(state.path) }

    fun close() { invalidate() }

    private fun load(path: List<ContentNode>, readingFallback: BrowseState? = null) {
        val library = state.libraryId ?: return
        invalidate()
        val request = generation
        publish(BrowseState(library, path, loading = true, levelNames = levelNames?.takeIf { it.first == library }?.second.orEmpty()))
        pending = scope.launch {
            try {
                val names = levelNames?.takeIf { it.first == library }?.second
                    ?: try { loadLevelNames(library) } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyMap() }
                levelNames = library to names
                val parent = path.last().record
                val children = loadChildren(library, parent)
                // Never allow malformed input to loop back to an ancestor or cross branches.
                check(children.all { it.parent == parent && it.record != 0 && path.none { ancestor -> ancestor.record == it.record } })
                check(children.map { it.record }.distinct().size == children.size)
                val opensWholeUnit = children.isNotEmpty() &&
                    children.all { levelNames?.second?.get(it.level)?.trim() in TEXT_SUBSECTION_LEVELS }
                if ((children.isEmpty() || opensWholeUnit) && readingFallback != null && onReadLeaf != null) {
                    if (request == generation) {
                        publish(readingFallback)
                        onReadLeaf.invoke(library, path.last())
                    }
                    return@launch
                }
                val overview = loadOverview(library, parent, children)
                if (overview != null) {
                    check(overview.chapter == path.last())
                    check(overview.verses.isNotEmpty() && overview.verses.map { it.node.record }.distinct().size == overview.verses.size)
                    check(overview.verses.all { it.node in children })
                }
                val introduction = if (overview == null) loadIntroduction(library, parent, children) else emptySet()
                check(introduction.all { record -> children.any { it.record == record } })
                if (request == generation) publish(BrowseState(library, path, children, chapter = overview,
                    introductionRecords = introduction, levelNames = names))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request == generation) publish(BrowseState(library, path, failed = true,
                    levelNames = levelNames?.takeIf { it.first == library }?.second.orEmpty()))
            }
        }
    }

    private fun invalidate() {
        generation++
        pending?.cancel()
        pending = null
    }

    private fun publish(value: BrowseState) { state = value; onChange(value) }

    private companion object {
        val TEXT_SUBSECTION_LEVELS = setOf("LE_Verse_Section", "LE_Verse_Text")
    }
}