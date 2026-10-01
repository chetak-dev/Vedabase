package org.vedareader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.vedareader.R

enum class AppDestination { LIBRARY, SEARCH, BOOKMARKS, MORE }

/** BOOKMARKS remains a selected More child route; its More action returns to the landing page. */
@Composable
fun AppNavigationBar(
    selected: AppDestination,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onBookmarks: () -> Unit,
    tagPrefix: String,
    libraryTag: String = "$tagPrefix-nav-library",
    searchTag: String = "$tagPrefix-nav-search",
    moreTag: String = "$tagPrefix-nav-bookmarks",
) {
    val moreSelected = selected == AppDestination.BOOKMARKS || selected == AppDestination.MORE
    val largeText = LocalDensity.current.fontScale >= 1.8f
    val destinations = listOf(AppDestination.LIBRARY, AppDestination.SEARCH, AppDestination.MORE)
    val labels = listOf(R.string.library, R.string.search, R.string.more_title)
    val tags = listOf(libraryTag, searchTag, moreTag)
    val actions = listOf(onLibrary, onSearch, onBookmarks)
    val barModifier = Modifier.fillMaxWidth().selectableGroup().testTag("$tagPrefix-navigation")
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
    Surface(color = ReadingColors.Paper, contentColor = ReadingColors.Ink, shadowElevation = 3.dp) {
        if (largeText) {
            // Preserve the user's font size rather than squeezing three labels into narrow columns.
            Column(barModifier) {
                destinations.forEachIndexed { index, destination ->
                    val active = if (destination == AppDestination.MORE) moreSelected else selected == destination
                    val tint = if (active) ReadingColors.Header else ReadingColors.Muted
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = active, role = Role.Tab, onClick = actions[index])
                        .testTag(tags[index]).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        NavigationIcon(destination, active)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(labels[index]), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = tint)
                    }
                }
            }
        } else Row(barModifier, verticalAlignment = Alignment.CenterVertically) {
            destinations.forEachIndexed { index, destination ->
                val active = if (destination == AppDestination.MORE) moreSelected else selected == destination
                val tint = if (active) ReadingColors.Header else ReadingColors.Muted
                Column(Modifier.weight(1f).heightIn(min = 80.dp)
                    .selectable(selected = active, role = Role.Tab, onClick = actions[index])
                    .testTag(tags[index]).padding(horizontal = 4.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(color = if (active) ReadingColors.Chapter else ReadingColors.Paper,
                        contentColor = tint, shape = RoundedCornerShape(20.dp)) {
                        Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            NavigationIcon(destination, active)
                        }
                    }
                    Text(stringResource(labels[index]), style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, color = tint)
                }
            }
        }
    }
}

@Composable
private fun NavigationIcon(destination: AppDestination, selected: Boolean) {
    val tint = if (selected) ReadingColors.Header else ReadingColors.Muted
    when (destination) {
        AppDestination.LIBRARY -> Icon(Icons.Default.Home, null, Modifier.size(24.dp), tint)
        AppDestination.SEARCH -> Icon(Icons.Default.Search, null, Modifier.size(24.dp), tint)
        AppDestination.BOOKMARKS, AppDestination.MORE -> Icon(painterResource(if (selected)
            R.drawable.ic_more_grid_filled else R.drawable.ic_more_grid_outline), null, Modifier.size(24.dp), tint)
    }
}
