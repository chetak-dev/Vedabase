package org.vedareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vedareader.R
import org.vedareader.data.BookContentsItem
import org.vedareader.data.BookOutline
import org.vedareader.data.BookPosition
import org.vedareader.data.bookContents

private data class ContentsRow(val item: BookContentsItem, val depth: Int, val readStart: Boolean = false)
private data class PreparedContents(
    val roots: List<BookContentsItem>? = null,
    val selectedRoot: Int = 0,
    val failed: Boolean = false,
)
private val prepareContents: suspend (BookOutline) -> List<BookContentsItem> = { bookContents(it) }

@Composable
internal fun HierarchicalBookContentsDialog(
    position: BookPosition,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    loadContents: suspend (BookOutline) -> List<BookContentsItem> = prepareContents,
) = key(position.outline) {
    val colors = readerPaperColors()
    var attempt by remember { mutableIntStateOf(0) }
    var prepared by remember { mutableStateOf(PreparedContents()) }
    LaunchedEffect(attempt) {
        prepared = PreparedContents()
        prepared = try {
            withContext(Dispatchers.Default) {
                val roots = loadContents(position.outline)
                val selected = position.outline.entries[position.index].source.record
                fun containsSelected(item: BookContentsItem): Boolean =
                    item.source.record == selected || item.children.any(::containsSelected)
                PreparedContents(roots, roots.indexOfFirst(::containsSelected).coerceAtLeast(0))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled // Dismissal/outline replacement must not publish a late error/result.
        } catch (_: Exception) {
            PreparedContents(failed = true)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = colors.Paper,
        titleContentColor = colors.Ink, textContentColor = colors.Ink,
        title = { Text(position.outline.book.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            val roots = prepared.roots
            when {
                roots != null -> BookContentsList(position, roots, prepared.selectedRoot, onSelect)
                prepared.failed -> Column(Modifier.fillMaxWidth().testTag("reader-contents-error"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.reader_contents_error))
                    TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("reader-contents-retry")) {
                        Text(stringResource(R.string.try_again))
                    }
                }
                else -> Column(Modifier.fillMaxWidth().heightIn(min = 160.dp)
                    .testTag("reader-contents-loading").semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = colors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.reader_preparing_contents))
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.Accent)) { Text(stringResource(R.string.reader_close_contents)) } })
}

@Composable
private fun BookContentsList(position: BookPosition, roots: List<BookContentsItem>, selectedRoot: Int, onSelect: (Int) -> Unit) {
    val colors = readerPaperColors()
    var expanded by remember(position.outline) { mutableStateOf(emptySet<Int>()) }
    val selectedSource = position.outline.entries[position.index].source.record
    val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedRoot)
    val rows = remember(roots, expanded) {
        buildList {
            fun addRows(items: List<BookContentsItem>, depth: Int) {
                for (item in items) {
                    add(ContentsRow(item, depth))
                    if (item.source.record in expanded && item.children.isNotEmpty()) {
                        add(ContentsRow(item, depth + 1, readStart = true))
                        addRows(item.children, depth + 1)
                    }
                }
            }
            addRows(roots, 0)
        }
    }
    LazyColumn(state = list, modifier = Modifier.heightIn(max = 460.dp).testTag("reader-book-contents-list")) {
        items(rows, key = { "${it.item.source.record}:${it.readStart}" }) { row ->
            val item = row.item
            val group = item.children.isNotEmpty() && !row.readStart
            val open = item.source.record in expanded
            val title = if (row.readStart) stringResource(R.string.book_contents_read_start)
                else if (item.source.record == position.outline.book.record) stringResource(R.string.book_contents_beginning)
                else nodeTitle(item.source)
            val action = stringResource(if (open) R.string.book_contents_collapse else R.string.book_contents_expand, title)
            val expansionState = stringResource(if (open) R.string.book_contents_expanded else R.string.book_contents_collapsed)
            TextButton(onClick = {
                if (group) expanded = if (open) expanded - item.source.record else expanded + item.source.record
                else onSelect(item.jumpRecord)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .padding(start = (row.depth.coerceAtMost(5) * 12).dp)
                .testTag(if (group) "book-chapter-${item.source.record}" else "book-entry-${item.jumpRecord}")
                .semantics {
                    if (group) { contentDescription = action; stateDescription = expansionState }
                    selected = item.source.record == selectedSource && !row.readStart
                }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.Ink)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = if (group || item.source.record == selectedSource) FontWeight.Medium else FontWeight.Normal)
                    if (group) Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}