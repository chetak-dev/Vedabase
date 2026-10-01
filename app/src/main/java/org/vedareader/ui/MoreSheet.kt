package org.vedareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.vedareader.R
import org.vedareader.data.LibraryInfo

/** A route, not a modal: the owner retains the previous screen and its scroll position. */
@Composable
fun MoreScreen(
    library: LibraryInfo?,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("more-screen"),
        containerColor = ReadingColors.Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = Color.Transparent, contentColor = ReadingColors.OnHeader,
                modifier = Modifier.background(ReadingColors.HeaderBrush)) {
                Row(Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("more-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.more_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.testTag("more-title").semantics { heading() })
                        Text(library?.title?.ifBlank { stringResource(R.string.bookmark_unnamed_library) }
                            ?: stringResource(R.string.bookmark_no_library),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("more-library"))
                    }
                }
            }
        },
        bottomBar = {
            AppNavigationBar(AppDestination.MORE, onHome, onSearch, {}, tagPrefix = "more")
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .testTag("more-content").padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.more_study_heading), style = MaterialTheme.typography.titleLarge,
                    fontFamily = BookFontFamily, color = ReadingColors.Ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.more_study_body), style = MaterialTheme.typography.bodyMedium,
                    color = ReadingColors.Muted)
                MoreCard("more-bookmarks", R.string.bookmark_title, R.string.more_bookmarks_body,
                    Color(0xFFFBEEDC) to ReadingColors.Header, { Icon(painterResource(R.drawable.ic_bookmark_filled), null, Modifier.size(22.dp)) }) { onOpen(0) }
                MoreCard("more-highlights", R.string.highlights_tab, R.string.more_highlights_body,
                    Color(0xFFFFF1C2) to Color(0xFF8A6A00), { Box(Modifier.size(18.dp).background(Color(0xFFFFE58A), CircleShape)) }) { onOpen(1) }
                MoreCard("more-notes", R.string.notes_tab, R.string.more_notes_body,
                    Color(0xFFEDE4F3) to Color(0xFF5B3D7A), { Icon(Icons.Default.Edit, null, Modifier.size(22.dp)) }) { onOpen(2) }
            }
        }
    }
}
@Composable
private fun MoreCard(tag: String, title: Int, body: Int, tint: Pair<Color, Color>, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = ReadingColors.Paper,
        border = BorderStroke(1.dp, ReadingColors.Rule), modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(tag)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = tint.first, contentColor = tint.second, shape = RoundedCornerShape(14.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ReadingColors.Ink)
                Text(stringResource(body), fontSize = 14.sp, lineHeight = 20.sp, color = ReadingColors.Muted)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint.second)
        }
    }
}

