package org.vedareader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vedareader.R
import org.vedareader.data.ReaderPreferences
import kotlin.math.roundToInt

/** Reader-only size: preview locally while dragging, persist/reflow once on release. */
@Composable
internal fun ReaderTextSizeDialog(fontSize: Int, onFontSizeChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val chosen = fontSize.coerceIn(14, 30)
    var draft by rememberSaveable(chosen) { mutableIntStateOf(chosen) }
    fun applySize(value: Int) {
        draft = value.coerceIn(14, 30)
        if (draft != chosen) onFontSizeChange(draft)
    }
    val title = stringResource(R.string.reader_text_size)
    val percent = stringResource(R.string.reader_text_size_percent, draft * 5)
    val decrease = stringResource(R.string.reader_text_size_decrease)
    val increase = stringResource(R.string.reader_text_size_increase)
    val reset = stringResource(R.string.reader_text_size_reset)
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = ReaderPaperColors.Accent)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reader-text-size-dialog"),
        shape = RoundedCornerShape(28.dp),
        containerColor = ReaderPaperColors.Paper,
        titleContentColor = ReaderPaperColors.Ink,
        textContentColor = ReaderPaperColors.Ink,
        title = { Text(title, fontFamily = AppFontFamily) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = ReaderPaperColors.Chapter, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, ReaderPaperColors.Rule)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 144.dp).padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reader_text_size_preview),
                            modifier = Modifier.testTag("reader-text-size-preview"),
                            fontFamily = AppFontFamily, fontSize = draft.sp, lineHeight = (draft * 1.4f).sp,
                            color = ReaderPaperColors.Ink, textAlign = TextAlign.Center)
                    }
                }
                // Control sizes never depend on the reader preference; system accessibility
                // font scale still applies normally throughout the app and this dialog.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { applySize(draft - 1) }, enabled = draft > 14,
                        modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)
                            .testTag("reader-text-size-decrease").semantics { contentDescription = decrease },
                        contentPadding = PaddingValues(horizontal = 8.dp), colors = buttonColors) {
                        Text("A−", fontFamily = AppFontFamily, fontSize = 18.sp)
                    }
                    Text(percent, Modifier.weight(1f).testTag("reader-text-size-value"),
                        fontFamily = AppFontFamily, fontSize = 24.sp, lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    TextButton(onClick = { applySize(draft + 1) }, enabled = draft < 30,
                        modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)
                            .testTag("reader-text-size-increase").semantics { contentDescription = increase },
                        contentPadding = PaddingValues(horizontal = 8.dp), colors = buttonColors) {
                        Text("A+", fontFamily = AppFontFamily, fontSize = 22.sp)
                    }
                }
                Slider(value = draft.toFloat(), onValueChange = { draft = it.roundToInt().coerceIn(14, 30) },
                    onValueChangeFinished = { applySize(draft) }, valueRange = 14f..30f, steps = 15,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader-text-size-slider")
                        .semantics { contentDescription = title; stateDescription = percent },
                    colors = SliderDefaults.colors(thumbColor = ReaderPaperColors.Accent,
                        activeTrackColor = ReaderPaperColors.Accent, inactiveTrackColor = ReaderPaperColors.Rule))
            }
        },
        dismissButton = {
            TextButton(onClick = { applySize(ReaderPreferences.DEFAULT_TEXT_SIZE) }, enabled = draft != ReaderPreferences.DEFAULT_TEXT_SIZE, colors = buttonColors,
                modifier = Modifier.heightIn(min = 48.dp).testTag("reader-text-size-reset")
                    .semantics { contentDescription = reset }) {
                Text(stringResource(R.string.reader_text_size_default))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = buttonColors,
                modifier = Modifier.heightIn(min = 48.dp).testTag("reader-text-size-done")) {
                Text(stringResource(R.string.reader_text_size_done))
            }
        },
    )
}