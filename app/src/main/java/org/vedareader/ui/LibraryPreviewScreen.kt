package org.vedareader.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.Formatter
import android.app.Activity
import android.os.SystemClock
import kotlinx.coroutines.launch
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.ImportProgress
import org.vedareader.data.ImportStage
import org.vedareader.data.LibraryInfo
import java.text.NumberFormat
import java.util.Locale

private val Ink = ReadingColors.Ink
private val Paper = ReadingColors.Paper
private val Gold = ReadingColors.Accent
private val Muted = ReadingColors.Muted
private val Border = ReadingColors.Rule

@Composable
fun VedaPreviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ReadingColors.Header,
            onPrimary = ReadingColors.OnHeader,
            primaryContainer = ReadingColors.Chapter,
            onPrimaryContainer = Ink,
            inversePrimary = ReadingColors.Chapter,
            secondary = Gold,
            onSecondary = ReadingColors.OnHeader,
            secondaryContainer = ReadingColors.Chapter,
            onSecondaryContainer = Ink,
            tertiary = ReadingColors.Header,
            onTertiary = ReadingColors.OnHeader,
            tertiaryContainer = ReadingColors.Chapter,
            onTertiaryContainer = Ink,
            background = Paper,
            onBackground = Ink,
            surface = Paper,
            onSurface = Ink,
            surfaceVariant = ReadingColors.Chapter,
            onSurfaceVariant = Muted,
            surfaceTint = Paper,
            inverseSurface = Ink,
            inverseOnSurface = Paper,
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFEDEA),
            onErrorContainer = Color(0xFF7D231B),
            outline = Muted,
            outlineVariant = Border,
            scrim = Ink,
            surfaceBright = Paper,
            surfaceDim = ReadingColors.Chapter,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Paper,
            surfaceContainer = ReadingColors.Chapter,
            surfaceContainerHigh = ReadingColors.Chapter,
            surfaceContainerHighest = ReadingColors.Chapter,
        ),
        typography = AppTypography,
    ) {
        ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = AppFontFamily)) {
            content()
        }
    }
}

/** Two Back presses within this window leave the app; one press only shows the hint. */
private const val EXIT_CONFIRM_MILLIS = 2_000L

private data class LibraryCollection(
    val node: ContentNode,
    val monogram: String,
    @StringRes val subtitle: Int? = null,
    val title: String
)
private fun collection(node: ContentNode): LibraryCollection = when (node.code) {
    "bgaiiwa" -> LibraryCollection(node, "BG", R.string.home_subtitle_gita, node.displayTitle)
    "sbbp" -> LibraryCollection(node, "SB", R.string.home_subtitle_bhagavatam, node.displayTitle)
    "scc" -> LibraryCollection(node, "CC", R.string.home_subtitle_caitanya, node.displayTitle)
    "cl" -> LibraryCollection(node, "LT", title = node.displayTitle)
    else -> LibraryCollection(node, monogramOf(node), title = node.displayTitle)
}

/** Initials of the first two significant words ("Other Books by …" -> "OB"); digits are never shown. */
private fun monogramOf(node: ContentNode): String {
    val words = node.displayTitle.split(Regex("[\\s,()\u2014-]+")).filter { it.isNotBlank() && it[0].isLetter() }
        .filterNot { it.lowercase(Locale.ENGLISH) in setOf("and", "of", "the", "by", "from", "in", "to") }
    return words.take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "\u2022" }
}

/** Badge tints per shelf; all pairs keep >= 4.5:1 on their light background. */
private fun shelfTint(tint: Int): Pair<Color, Color> = when (tint) {
    0 -> Color(0xFFFBEEDC) to ReadingColors.Header
    1 -> Color(0xFFEDE4F3) to Color(0xFF5B3D7A)
    2 -> Color(0xFFE4EFE2) to Color(0xFF3E6B3B)
    else -> Color(0xFFF1EEE8) to Color(0xFF6C6053)
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onChooseDocument: (String) -> Unit = {},
    onSelectLibrary: (String) -> Unit = {},
    onCancelImport: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenSection: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onAncestor: (Int) -> Unit = {},
    onRetrySection: () -> Unit = {},
    onSearch: () -> Unit = {},
    onBookmarks: (Int) -> Unit = {},
    onMore: () -> Unit = {},
) {
    var showInformation by rememberSaveable { mutableStateOf(false) }
    var showLibraries by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val arrangement = remember(state.collections) { HomeLayout.arrange(state.collections) }
    val featured = remember(arrangement.featured) { arrangement.featured.map(::collection) }
    val englishLibraries = remember(state.libraries) {
        state.libraries.filter { Locale.forLanguageTag(it.languageTag.trim()).language == "en" }
    }
    val importAction: () -> Unit = { onChooseDocument("en") }
    val screenStates = rememberSaveableStateHolder()
    val browsing = state.browser.current != null
    val chapter = state.browser.chapter
    BackHandler(enabled = browsing && state.progress == null) { onBack() }
    BackHandler(enabled = state.progress != null) { if (!state.cancelling) onCancelImport() }
    val activity = LocalContext.current as? Activity
    val exitHint = stringResource(R.string.home_press_back_again)
    var lastBackPress by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !browsing && state.progress == null && activity != null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPress <= EXIT_CONFIRM_MILLIS) activity?.finish()
        else {
            lastBackPress = now
            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(exitHint, duration = SnackbarDuration.Short) }
        }
    }

    Scaffold(
        containerColor = if (chapter != null) ReadingColors.Chapter else Paper,
        topBar = {
            if (chapter != null) ChapterHeader(state.browser, onBack, { onAncestor(0) }, onSearch)
            else PreviewHeader(browsing, onBack, onSearch = onSearch) { showInformation = true }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (chapter == null) AppNavigationBar(AppDestination.LIBRARY,
                onLibrary = { onAncestor(0) }, onSearch = onSearch, onBookmarks = onMore,
                tagPrefix = "library", searchTag = "library-search", moreTag = "library-more")
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            if (state.loading) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(stringResource(R.string.library_loading), Modifier.padding(top = 16.dp), color = Muted)
                }
            } else screenStates.SaveableStateProvider("${state.active?.id ?: "empty"}:${state.browser.current?.record ?: 0}") {
                if (browsing) {
                    BrowseContent(state.browser, onOpenSection, onAncestor, onRetrySection)
                } else LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("library-list"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    if (state.active == null) {
                        item { ImportCard(importAction) }
                    } else {
                        item(key = "search-entry") { SearchEntry(onSearch); Spacer(Modifier.height(18.dp)) }
                        if (featured.isNotEmpty()) {
                            item(key = "featured-heading") { HomeHeading(stringResource(R.string.home_featured)); Spacer(Modifier.height(8.dp)) }
                            items(featured, key = { "featured-${it.node.record}" }, contentType = { "featured" }) { collection ->
                                FeaturedRow(collection) { onOpenSection(collection.node.record) }
                            }
                        }
                        for (shelf in arrangement.shelves) {
                            item(key = "shelf-${shelf.title}") {
                                HomeHeading(stringResource(shelf.title), Modifier.padding(top = 14.dp, bottom = 4.dp), shelfTint(shelf.tint))
                            }
                            itemsIndexed(shelf.collections, key = { _, node -> "row-${node.record}" }, contentType = { _, _ -> "collection" }) { index, node ->
                                Column {
                                    CollectionRow(collection(node), shelfTint(shelf.tint)) { onOpenSection(node.record) }
                                    if (index < shelf.collections.lastIndex) HorizontalDivider(color = Border,
                                        modifier = Modifier.padding(start = 50.dp))
                                }
                            }
                        }
                        if (featured.isEmpty() && arrangement.shelves.isEmpty()) item { Text(stringResource(R.string.library_filter_empty), color = Muted) }
                    }
                }
            }
        }
    }

    if (showInformation) {
        InfoDialog(
            library = state.active,
            onDismiss = { showInformation = false },
            onChooseLibrary = { showInformation = false; showLibraries = true },
            onImport = {
                showInformation = false
                importAction()
            },
        )
    }
    if (showLibraries) {
        AlertDialog(
            onDismissRequest = { showLibraries = false },
            title = { Text("Choose existing library") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    englishLibraries.forEach { library ->
                        TextButton(onClick = { showLibraries = false; onSelectLibrary(library.id) }) {
                            Text(library.title)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLibraries = false }) { Text(stringResource(R.string.done)) } },
        )
    }
    if (state.progress != null) ImportDialog(state.progress, state.cancelling, onCancelImport)
    if (state.error != null && state.progress == null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(stringResource(state.error)) },
            confirmButton = {
                TextButton(onClick = { onDismissError(); if (state.error == R.string.library_load_error) onRetry() else importAction() }) {
                    Text(stringResource(R.string.try_again))
                }
            },
            dismissButton = { TextButton(onClick = onDismissError) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun PreviewHeader(browsing: Boolean, onBack: () -> Unit, onSearch: () -> Unit, onInformation: () -> Unit) {
    Surface(color = Color.Transparent, contentColor = ReadingColors.OnHeader, modifier = Modifier.background(ReadingColors.HeaderBrush)) {
    Box {
    if (!browsing) Text("\u0950", Modifier.align(Alignment.CenterEnd).padding(end = 52.dp).windowInsetsPadding(WindowInsets.statusBars),
        color = ReadingColors.OnHeader.copy(alpha = .16f), fontSize = 44.sp, fontFamily = BookFontFamily)
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (browsing) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("browse-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back))
            }
        } else Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            BookMark(Modifier.size(26.dp), ReadingColors.OnHeader)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), Modifier.weight(1f), fontFamily = AppFontFamily, fontSize = 21.sp)
        if (browsing) IconButton(onClick = onSearch, modifier = Modifier.testTag("browse-search")) {
            Icon(Icons.Default.Search, stringResource(R.string.search_ui_this_book))
        }
        IconButton(onClick = onInformation) {
            Icon(Icons.Default.Info, stringResource(R.string.library_about), tint = ReadingColors.OnHeader)
        }
    }
    }
    }
}

@Composable
private fun ChapterHeader(state: BrowseState, onBack: () -> Unit, onLibrary: () -> Unit, onSearch: () -> Unit) {
    val chapter = state.current ?: return
    Surface(color = Color.Transparent, contentColor = ReadingColors.OnHeader, shadowElevation = 2.dp,
        modifier = Modifier.background(ReadingColors.HeaderBrush)) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("browse-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browse_back))
            }
            Column(Modifier.weight(1f)) {
                state.path.firstOrNull()?.let {
                    Text(nodeTitle(it), fontSize = 14.sp, lineHeight = 19.sp,
                        color = ReadingColors.OnHeader.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(nodeTitle(chapter), Modifier.testTag("browse-title"), fontSize = 17.sp, lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSearch, modifier = Modifier.testTag("chapter-search")) {
                Icon(Icons.Default.Search, stringResource(R.string.search_ui_this_book))
            }
            IconButton(onClick = onLibrary, modifier = Modifier.testTag("chapter-library")) {
                Icon(Icons.Default.Home, stringResource(R.string.browse_home))
            }
        }
    }
}

@Composable
private fun SearchEntry(onSearch: () -> Unit) {
    Surface(onClick = onSearch, shape = RoundedCornerShape(28.dp), color = ReadingColors.Chapter,
        border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("library-search-entry")) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = ReadingColors.Header)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.search_ui_query_hint), color = Muted, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeHeading(text: String, modifier: Modifier = Modifier, tint: Pair<Color, Color> = shelfTint(0)) {
    Row(modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp))
            .background(Brush.verticalGradient(listOf(tint.second, tint.second.copy(alpha = .35f)))))
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint.second, fontSize = 13.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(Modifier.weight(1f), color = tint.second.copy(alpha = .25f))
    }
}

/** Full-width row for a principal scripture: saffron cover badge with serif monogram, title and a subtitle. */
@Composable
private fun FeaturedRow(collection: LibraryCollection, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Paper, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("collection-${collection.node.record}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(ReadingColors.HeaderGlow, ReadingColors.Header, Color(0xFF8A3A0E)))),
                contentAlignment = Alignment.Center) {
                Text(collection.monogram, fontFamily = BookFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFE9B8))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(collection.title, color = Ink, fontFamily = AppFontFamily, fontSize = 17.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                collection.subtitle?.let {
                    Text(stringResource(it), Modifier.padding(top = 3.dp), color = Muted, fontFamily = AppFontFamily,
                        fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = ReadingColors.Header)
        }
    }
}

@Composable
private fun CollectionRow(collection: LibraryCollection, tint: Pair<Color, Color>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick)
        .testTag("collection-${collection.node.record}").padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(tint.first, CircleShape), contentAlignment = Alignment.Center) {
            Text(collection.monogram, fontFamily = BookFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = tint.second)
        }
        Spacer(Modifier.width(14.dp))

        Text(collection.title, Modifier.weight(1f), fontFamily = AppFontFamily, color = Ink,
            fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

@Composable
private fun ImportCard(onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onImport, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("choose-database")) {
            Text(stringResource(R.string.import_button), Modifier.padding(vertical = 5.dp))
        }
        Text("Select vedabase.ivd in Downloads.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoDialog(library: LibraryInfo?, onDismiss: () -> Unit, onChooseLibrary: () -> Unit, onImport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.about_description))
                if (library != null) {
                    Text(stringResource(R.string.library_source), Modifier.padding(top = 18.dp),
                        color = Gold, style = MaterialTheme.typography.labelMedium)
                    Text(library.title, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.library_details,
                        Locale.forLanguageTag(library.languageTag).getDisplayName(Locale.ENGLISH),
                        Formatter.formatFileSize(LocalContext.current, library.size),
                        NumberFormat.getIntegerInstance(Locale.ENGLISH).format(library.records)),
                        Modifier.padding(top = 5.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (library.copyright.isNotBlank()) {
                        Text(stringResource(R.string.source_copyright), Modifier.padding(top = 18.dp), fontWeight = FontWeight.Medium)
                        Text(library.copyright, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onChooseLibrary, modifier = Modifier.padding(top = 12.dp)) { Text("Choose existing library") }
                TextButton(onClick = onImport) { Text("Add library") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun ImportDialog(progress: ImportProgress, cancelling: Boolean, onCancel: () -> Unit) {
    val canCancel = !cancelling && progress.stage != ImportStage.FINISHING
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.importing)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val message = when {
                    cancelling -> stringResource(R.string.import_cancelling)
                    progress.stage == ImportStage.CHECKING -> stringResource(R.string.import_checking)
                    progress.stage == ImportStage.FINISHING -> stringResource(R.string.import_finishing)
                    progress.percent != null -> stringResource(R.string.import_copying, progress.percent)
                    else -> stringResource(R.string.import_copying_unknown)
                }
                Text(message, Modifier.testTag("import-progress"))
                Spacer(Modifier.height(18.dp))
                if (progress.stage == ImportStage.COPYING && progress.percent != null && !cancelling)
                    LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (cancelling) R.string.import_cancel_wait else R.string.import_keep_open),
                    Modifier.padding(top = 18.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onCancel, enabled = canCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Original line artwork; independent from the reference apps and their assets. */
@Composable
private fun BookMark(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .13f, h * .24f)
            quadraticTo(w * .32f, h * .17f, w * .5f, h * .3f)
            quadraticTo(w * .68f, h * .17f, w * .87f, h * .24f)
            lineTo(w * .87f, h * .78f)
            quadraticTo(w * .68f, h * .71f, w * .5f, h * .84f)
            quadraticTo(w * .32f, h * .71f, w * .13f, h * .78f)
            close()
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        drawLine(color, Offset(w * .5f, h * .3f), Offset(w * .5f, h * .84f), strokeWidth = 1.5.dp.toPx())
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PhoneLibraryPreview() { VedaPreviewTheme { LibraryScreen(LibraryUiState(loading = false)) } }

@Preview(showBackground = true, widthDp = 700, heightDp = 500, fontScale = 1.3f)
@Composable
private fun WideLibraryPreview() { VedaPreviewTheme { LibraryScreen(LibraryUiState(loading = false)) } }
