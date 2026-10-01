package org.vedareader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.ContentsItem
import org.vedareader.data.VersePreview
import org.vedareader.data.groupIntroduction
import java.text.NumberFormat

@Composable
internal fun nodeTitle(node: ContentNode): String = node.displayTitle.ifBlank {
    stringResource(R.string.browse_untitled, node.record)
}

private sealed interface ChapterRow {
    data class Section(val section: ContentsItem.Section, val number: Int) : ChapterRow
    data class Introduction(val sections: List<ContentsItem.Section>, val expanded: Boolean) : ChapterRow
}

/** Source-aware chapter overview; generic non-verse sections retain their existing contents list. */
@Composable
internal fun BrowseContent(
    state: BrowseState,
    onOpen: (Int) -> Unit,
    onAncestor: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val current = state.current ?: return
    val colors = MaterialTheme.colorScheme
    val overview = state.chapter
    val translations = remember(overview) { overview?.verses?.associateBy { it.node.record }.orEmpty() }
    val contents = remember(state.entries, state.introductionRecords, overview) {
        groupIntroduction(state.entries, if (overview == null) state.introductionRecords else emptySet())
    }
    var introductionExpanded by rememberSaveable(state.libraryId, current.record) { mutableStateOf(false) }
    val rows = remember(contents, introductionExpanded) {
        buildList {
            var position = 0
            contents.forEach { entry ->
                when (entry) {
                    is ContentsItem.Section -> add(ChapterRow.Section(entry, ++position))
                    is ContentsItem.Introduction -> {
                        add(ChapterRow.Introduction(entry.sections, introductionExpanded))
                        if (introductionExpanded) entry.sections.forEachIndexed { index, section ->
                            add(ChapterRow.Section(section, index + 1))
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        if (overview == null) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("browse-path"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                TextButton(onClick = { onAncestor(0) }, modifier = Modifier.testTag("browse-crumb-0")) {
                    Text(stringResource(R.string.library))
                }
            }
            itemsIndexed(state.path, key = { _, node -> node.record }) { index, node ->
                val title = nodeTitle(node)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                TextButton(
                    onClick = { onAncestor(index + 1) }, enabled = index < state.path.lastIndex,
                    modifier = Modifier.widthIn(max = 230.dp).testTag("browse-crumb-${index + 1}"),
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().testTag("browse-list-${current.record}"),
            contentPadding = PaddingValues(start = if (overview != null) 12.dp else 16.dp,
                end = if (overview != null) 12.dp else 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            if (overview == null) item(key = "heading") {
                Text(nodeTitle(current), Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() }.testTag("browse-title"),
                    fontFamily = AppFontFamily, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            when {
                state.loading -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(stringResource(R.string.browse_loading), Modifier.padding(top = 16.dp), color = colors.onSurfaceVariant)
                    }
                }
                state.failed -> item(key = "error") {
                    Surface(color = colors.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.browse_error), color = colors.onErrorContainer)
                            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.try_again)) }
                        }
                    }
                }
                state.entries.isEmpty() -> item(key = "leaf") {
                    Surface(color = colors.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Text(stringResource(R.string.browse_leaf_title), fontFamily = AppFontFamily, fontSize = 23.sp)
                            Text(stringResource(R.string.browse_leaf_body), Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
                            TextButton(onClick = { onAncestor(state.path.size - 1) }, modifier = Modifier.padding(top = 10.dp)) {
                                Text(stringResource(R.string.browse_back))
                            }
                        }
                    }
                }
                else -> {
                    items(rows,
                        key = { row -> when (row) {
                            is ChapterRow.Section -> "section-${row.section.node.record}"
                            is ChapterRow.Introduction -> "introduction-group"
                        } },
                        contentType = { row -> when (row) {
                            is ChapterRow.Section -> "section"
                            is ChapterRow.Introduction -> "introduction"
                        } },
                    ) { row ->
                        when (row) {
                            is ChapterRow.Section -> {
                                val preview = translations[row.section.node.record]
                                if (preview != null) VerseTranslationRow(preview, onOpen)
                                else SectionRow(row.section, row.number, onOpen)
                            }
                            is ChapterRow.Introduction -> {
                                IntroductionHeader(introductionExpanded) { introductionExpanded = !introductionExpanded }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroductionHeader(expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val action = stringResource(if (expanded) R.string.contents_collapse_introduction else R.string.contents_expand_introduction)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle)
            .testTag("introduction-group").semantics { heading(); contentDescription = action }
            .padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.contents_introduction), Modifier.weight(1f),
                fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** Dense list row: number and name only; no card chrome so more fits on a page. */
@Composable
private fun SectionRow(section: ContentsItem.Section, number: Int, onOpen: (Int) -> Unit) {
    val node = section.node
    val colors = MaterialTheme.colorScheme
    val title = nodeTitle(node)
    val description = stringResource(R.string.browse_open, title)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onOpen(node.record) }
            .testTag("section-${node.record}").semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(NumberFormat.getIntegerInstance().format(number), color = colors.secondary,
                fontFamily = AppFontFamily, fontSize = 15.sp, modifier = Modifier.widthIn(min = 28.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** Verse row: full reference (e.g. "SB 2.4.3-4") above the translation, which spans the whole row width. */
@Composable
private fun VerseTranslationRow(preview: VersePreview, onOpen: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = nodeTitle(preview.node)
    val actionLabel = stringResource(R.string.chapter_read_verse, title)
    // Glossary-style entries carry their own text as the title; showing it twice adds nothing.
    val repeatsBody = preview.translation?.startsWith(title.take(48)) == true
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onOpen(preview.node.record) }
            .testTag("verse-preview-${preview.node.record}").semantics { contentDescription = actionLabel }
            .padding(horizontal = 4.dp, vertical = 10.dp)) {
            if (!repeatsBody) Text(title, color = colors.secondary, fontFamily = AppFontFamily,
                fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.translation != null) {
                Text(preview.translation, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("verse-translation-${preview.node.record}"),
                    fontFamily = AppFontFamily, fontSize = 16.sp, lineHeight = 23.sp, color = ReadingColors.Ink,
                    fontWeight = FontWeight.SemiBold)
            } else {
                Text(stringResource(R.string.chapter_translation_missing), Modifier.fillMaxWidth().padding(top = 2.dp), color = colors.onSurfaceVariant,
                    fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}