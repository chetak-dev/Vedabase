package org.vedareader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vedareader.R
import org.vedareader.data.HighlightColor
import org.vedareader.data.HighlightStore
import org.vedareader.reader.ReaderStyle

/** Compose colour of a marker on the given paper family; mirrors the document's CSS variables. */
internal fun highlightColor(color: HighlightColor, dark: Boolean): Color {
    val hex = (if (dark) ReaderStyle.DARK_HIGHLIGHTS else ReaderStyle.LIGHT_HIGHLIGHTS).getValue(color.name.lowercase())
    return Color(0xFF000000 or hex.removePrefix("#").toLong(16))
}

@Composable
internal fun highlightColorLabel(color: HighlightColor): String = stringResource(when (color) {
    HighlightColor.YELLOW -> R.string.highlight_color_yellow
    HighlightColor.GREEN -> R.string.highlight_color_green
    HighlightColor.BLUE -> R.string.highlight_color_blue
    HighlightColor.PINK -> R.string.highlight_color_pink
})

/**
 * Details for an existing annotation. Colour selection belongs only in Reading settings.
 * The colour arguments remain for source compatibility; this tray never recolours a mark.
 */
@Composable
internal fun HighlightPalette(
    excerpt: String,
    current: HighlightColor,
    note: String,
    onPick: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = readerPaperColors()
    Surface(modifier.testTag("highlight-palette"), color = colors.Paper, contentColor = colors.Ink,
        shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, border = BorderStroke(1.dp, colors.Rule)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.highlight_palette_edit),
                        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
                    if (excerpt.isNotBlank()) Text(excerpt, Modifier.testTag("highlight-palette-excerpt"),
                        color = colors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("highlight-cancel"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Muted)) {
                    Icon(Icons.Default.Close, stringResource(R.string.cancel))
                }
            }
            Spacer(Modifier.size(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("highlight-remove"),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.Accent)) {
                    Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.annotation_remove_highlight))
                }
            }
            Surface(onClick = onNote, color = colors.Chapter, contentColor = colors.Ink, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp).heightIn(min = 48.dp).testTag("highlight-note")) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(18.dp), tint = colors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (note.isBlank()) R.string.note_add else R.string.note_edit),
                            fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp, color = colors.Accent)
                        if (note.isNotBlank()) Text(note, Modifier.testTag("highlight-note-text"), fontSize = 13.sp, lineHeight = 18.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Compact settings-only colour selector; all four choices have labelled 48dp radio targets. */
@Composable
internal fun DefaultHighlightColorChoices(current: HighlightColor, onPick: (HighlightColor) -> Unit) {
    val colors = readerPaperColors()
    Row(Modifier.fillMaxWidth().selectableGroup().testTag("reader-highlight-colors"),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        for (choice in HighlightColor.entries) {
            val selected = choice == current
            val label = highlightColorLabel(choice)
            Box(Modifier.size(48.dp).testTag("highlight-color-${choice.name}")
                .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onPick(choice) })
                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).background(highlightColor(choice, colors.dark), CircleShape)
                    .border(if (selected) 2.dp else 1.dp, if (selected) colors.Ink else colors.Muted, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp), tint = colors.Ink)
                }
            }
        }
    }
}

/** Save validates without truncating; the owner closes only after a successful commit. */
@Composable
internal fun NoteEditorDialog(
    excerpt: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    editorId: String = excerpt,
    allowEmpty: Boolean = true,
    saving: Boolean = false,
    error: String? = null,
) {
    val colors = readerPaperColors()
    var draft by rememberSaveable(editorId) { mutableStateOf(initial) }
    val tooLong = draft.length > HighlightStore.MAX_NOTE
    val validation = if (tooLong) stringResource(R.string.annotation_note_too_long, HighlightStore.MAX_NOTE) else error
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("note-editor"),
        containerColor = colors.Paper, titleContentColor = colors.Ink, textContentColor = colors.Ink,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(if (initial.isBlank()) R.string.note_add else R.string.note_edit), fontFamily = AppFontFamily) },
        text = {
            Column {
                if (validation != null) Text(validation, Modifier.testTag("note-editor-error"),
                    color = colors.Accent, fontSize = 13.sp, lineHeight = 18.sp)
                if (excerpt.isNotBlank()) Surface(color = colors.Chapter, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("\u201C$excerpt\u201D", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.Muted,
                        fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus).testTag("note-editor-field"),
                    placeholder = { Text(stringResource(R.string.note_hint), color = colors.Muted) },
                    minLines = 3, maxLines = 4,
                    isError = validation != null,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.Accent, cursorColor = colors.Accent,
                        focusedTextColor = colors.Ink, unfocusedTextColor = colors.Ink, unfocusedBorderColor = colors.Rule))
                LaunchedEffect(editorId) { if (!saving) focus.requestFocus() }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft.trim()) }, enabled = !saving && !tooLong && (allowEmpty || draft.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp).testTag("note-editor-save"),
                colors = ButtonDefaults.buttonColors(containerColor = colors.Accent, contentColor = colors.Paper)) {
                Text(stringResource(R.string.note_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("note-editor-cancel"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.Accent)) { Text(stringResource(R.string.cancel)) }
        },
    )
}
