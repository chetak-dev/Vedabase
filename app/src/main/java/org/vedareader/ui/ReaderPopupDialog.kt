package org.vedareader.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.vedareader.R
import org.vedareader.data.ContentObject
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderLink
import org.vedareader.data.ReaderPopup
import org.vedareader.data.ReaderPreferences
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderStyle
import kotlin.math.roundToInt

/** Folio popup text through the same closed offline renderer as the page; links route back to the reader. */
@Composable
fun ReaderPopupDialog(
    popup: ReaderPopup,
    languageTag: String,
    style: ReaderStyle,
    fontSize: Int,
    image: (String) -> ContentObject?,
    onDismiss: () -> Unit,
    onLink: (ReaderLink) -> Unit,
) {
    val colors = readerPaperColors()
    val html = remember(popup, languageTag, style.paper, style.lineSpacing) {
        FolioText.document(listOf(Paragraph(0, popup.source, popup.style, "")), languageTag,
            ReaderPreferences(paper = style.paper, lineSpacing = style.lineSpacing))
    }
    val zoom = (LocalDensity.current.fontScale * 100 * (fontSize.coerceIn(14, 30) / 20f)).roundToInt()
    var rendered by remember(popup) { mutableStateOf(false) }
    var failed by remember(popup) { mutableStateOf(false) }
    val title = popup.title.takeUnless { it.startsWith("InlinePopupText", ignoreCase = true) || it.isBlank() }
        ?: stringResource(R.string.reader_popup_title)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 160.dp, max = 640.dp)
            .testTag("reader-popup").semantics { paneTitle = title },
            shape = RoundedCornerShape(24.dp), color = colors.Paper, contentColor = colors.Ink) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).semantics { heading() }.testTag("reader-popup-title"))
                HorizontalDivider(color = colors.Rule)
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 96.dp, max = 480.dp)) {
                    if (failed) {
                        Text(FolioText.plain(popup.source), Modifier.padding(20.dp).testTag("reader-popup-plain"),
                            style = MaterialTheme.typography.bodyLarge)
                    } else AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("reader-popup-document"),
                        factory = { context ->
                            OfflineReaderView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                interactionEnabled = false
                                setReaderStyle(style)
                                setReaderTextZoom(zoom)
                                onRendered = { rendered = true; interactionEnabled = true }
                                onRenderFailed = { failed = true }
                                onLinkActivated = { link -> if (rendered) onLink(link) }
                                render("popup-${popup.title}", html, 0, image)
                            }
                        },
                        update = { view -> view.setReaderTextZoom(zoom) },
                        onRelease = { view -> view.release() },
                    )
                    if (!rendered && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),
                        color = colors.Accent, strokeWidth = 2.dp)
                }
                HorizontalDivider(color = colors.Rule)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("reader-popup-close")) {
                        Text(stringResource(R.string.reader_popup_close), color = colors.Accent)
                    }
                }
            }
        }
    }
}
