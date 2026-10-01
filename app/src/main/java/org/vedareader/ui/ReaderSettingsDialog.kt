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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vedareader.R
import org.vedareader.data.HighlightColor
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReaderPaper
import kotlin.math.roundToInt

/**
 * One reader-local panel, hosted inside ReaderPaperTheme(paper). Values are owned/persisted
 * by the caller; only a font-slider drag is held locally until release. No source or bookmark IO.
 */
@Composable
internal fun ReaderSettingsDialog(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    paper: ReaderPaper = ReaderPaper.LIGHT,
    onPaperChange: (ReaderPaper) -> Unit = {},
    lineSpacing: Int = 100,
    onLineSpacingChange: (Int) -> Unit = {},
    defaultHighlightColor: HighlightColor = HighlightColor.YELLOW,
    onDefaultHighlightColorChange: (HighlightColor) -> Unit = {},
) {
    val colors = readerPaperColors()
    val chosenSize = fontSize.coerceIn(14, 30)
    // Match the native/store clamp without silently rounding an externally saved preference.
    val chosenSpacing = lineSpacing.coerceIn(85, 150)
    var draftSize by rememberSaveable(chosenSize) { mutableIntStateOf(chosenSize) }
    fun applySize(value: Int) {
        draftSize = value.coerceIn(14, 30)
        if (draftSize != chosenSize) onFontSizeChange(draftSize)
    }
    fun applySpacing(value: Int) {
        val bounded = value.coerceIn(85, 150)
        if (bounded != chosenSpacing) onLineSpacingChange(bounded)
    }
    val sizeTitle = stringResource(R.string.reader_text_size)
    val sizePercent = stringResource(R.string.reader_text_size_percent, draftSize * 5)
    val spacingPercent = stringResource(R.string.reader_line_spacing_percent, chosenSpacing)
    val resetSize = stringResource(R.string.reader_text_size_reset)
    val resetSpacing = stringResource(R.string.reader_line_spacing_reset)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reader-settings-dialog"),
        shape = RoundedCornerShape(28.dp),
        containerColor = colors.Paper,
        titleContentColor = colors.Ink,
        textContentColor = colors.Ink,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("reader-settings-back"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_settings_back))
                }
                Text(stringResource(R.string.reader_settings), Modifier.weight(1f), fontFamily = AppFontFamily)
            }
        },
        text = {
            // AlertDialog keeps its title/back and Done outside this bounded scroll area.
            // Reader size/spacing only affect the preview, never controls or application density.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .testTag("reader-settings-scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = colors.Paper, contentColor = colors.Ink, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, colors.Rule)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reader_text_size_preview),
                            modifier = Modifier.testTag("reader-text-size-preview"),
                            fontFamily = AppFontFamily, fontSize = draftSize.sp,
                            lineHeight = (draftSize * 1.4f * chosenSpacing / 100f).sp,
                            color = colors.Ink, textAlign = TextAlign.Center)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsHeading(sizeTitle) {
                        ResetIconButton("reader-text-size-reset", resetSize,
                            enabled = draftSize != ReaderPreferences.DEFAULT_TEXT_SIZE) { applySize(ReaderPreferences.DEFAULT_TEXT_SIZE) }
                    }
                    ReaderSettingStepper(sizePercent, "reader-text-size",
                        decreaseLabel = stringResource(R.string.reader_text_size_decrease),
                        increaseLabel = stringResource(R.string.reader_text_size_increase),
                        canDecrease = draftSize > 14, canIncrease = draftSize < 30,
                        onDecrease = { applySize(draftSize - 1) }, onIncrease = { applySize(draftSize + 1) })
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader-text-size-slider")
                        .semantics(mergeDescendants = true) {
                            contentDescription = sizeTitle
                            stateDescription = sizePercent
                            progressBarRangeInfo = ProgressBarRangeInfo(draftSize.toFloat(), 14f..30f, 15)
                            setProgress { value ->
                                val size = value.roundToInt().coerceIn(14, 30)
                                if (size == draftSize) false else { applySize(size); true }
                            }
                        },
                        contentAlignment = Alignment.Center) {
                    Slider(value = draftSize.toFloat(),
                        onValueChange = { draftSize = it.roundToInt().coerceIn(14, 30) },
                        onValueChangeFinished = { applySize(draftSize) }, valueRange = 14f..30f, steps = 15,
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                        colors = SliderDefaults.colors(thumbColor = colors.Accent,
                            activeTrackColor = colors.Accent, inactiveTrackColor = colors.Rule))
                    }
                }
                HorizontalDivider(color = colors.Rule)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsHeading(stringResource(R.string.reader_paper))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (choice in ReaderPaper.entries) {
                            val selected = choice == paper
                            val swatch = paperPalette(choice)
                            val label = stringResource(when (choice) {
                                ReaderPaper.LIGHT -> R.string.reader_paper_light
                                ReaderPaper.SEPIA -> R.string.reader_paper_sepia
                                ReaderPaper.GREEN -> R.string.reader_paper_green
                                ReaderPaper.GRAY -> R.string.reader_paper_gray
                                ReaderPaper.DARK -> R.string.reader_paper_dark
                            })
                            Column(Modifier.weight(1f).testTag("reader-paper-${choice.name}")
                                .selectable(selected = selected, role = Role.RadioButton,
                                    onClick = { if (!selected) onPaperChange(choice) })
                                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                // Each swatch previews its own paper and ink, not the current theme's.
                                Surface(shape = RoundedCornerShape(12.dp), color = swatch.Paper,
                                    contentColor = swatch.Ink,
                                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.Accent else colors.Muted),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                        if (selected) Icon(Icons.Default.Check, contentDescription = null,
                                            modifier = Modifier.size(22.dp).testTag("reader-paper-selected-check"))
                                        else Text("Aa", fontFamily = AppFontFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text(label, Modifier.padding(top = 4.dp), fontFamily = AppFontFamily, fontSize = 12.sp,
                                    maxLines = 1, color = colors.Ink,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.Rule)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsHeading(stringResource(R.string.reader_line_spacing)) {
                        ResetIconButton("reader-line-spacing-default", resetSpacing, enabled = chosenSpacing != 100) { applySpacing(100) }
                    }
                    ReaderSettingStepper(spacingPercent, "reader-line-spacing",
                        decreaseLabel = stringResource(R.string.reader_line_spacing_decrease),
                        increaseLabel = stringResource(R.string.reader_line_spacing_increase),
                        canDecrease = chosenSpacing > 85, canIncrease = chosenSpacing < 150,
                        onDecrease = { applySpacing(chosenSpacing - 5) },
                        onIncrease = { applySpacing(chosenSpacing + 5) })
                }
                HorizontalDivider(color = colors.Rule)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsHeading(stringResource(R.string.annotation_default_color))
                    DefaultHighlightColorChoices(defaultHighlightColor, onDefaultHighlightColorChange)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = colors.Accent, contentColor = colors.Paper),
                modifier = Modifier.widthIn(min = 96.dp).heightIn(min = 48.dp).testTag("reader-text-size-done")) {
                Text(stringResource(R.string.reader_text_size_done))
            }
        },
    )
}

@Composable
private fun SettingsHeading(text: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, color = readerPaperColors().Ink,
            fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
        trailing()
    }
}

/** Restores one setting's default; disabled while the default is already active. */
@Composable
private fun ResetIconButton(tag: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = readerPaperColors()
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label },
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.Accent, disabledContentColor = colors.Muted.copy(alpha = .45f))) {
        Icon(Icons.Default.Refresh, contentDescription = null)
    }
}

@Composable
private fun ReaderSettingStepper(
    valueText: String,
    tagPrefix: String,
    decreaseLabel: String,
    increaseLabel: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val colors = readerPaperColors()
    val buttonColors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Accent)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDecrease, enabled = canDecrease,
            modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp).testTag("$tagPrefix-decrease")
                .semantics { contentDescription = decreaseLabel },
            border = BorderStroke(1.dp, colors.Muted), colors = buttonColors,
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("−", fontFamily = AppFontFamily, fontSize = 22.sp)
        }
        Text(valueText, Modifier.weight(1f).testTag("$tagPrefix-value"), color = colors.Ink,
            fontFamily = AppFontFamily, fontSize = 24.sp, lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onIncrease, enabled = canIncrease,
            modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp).testTag("$tagPrefix-increase")
                .semantics { contentDescription = increaseLabel },
            border = BorderStroke(1.dp, colors.Muted), colors = buttonColors,
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("+", fontFamily = AppFontFamily, fontSize = 22.sp)
        }
    }
}