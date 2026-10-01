package org.vedareader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import org.vedareader.data.ReaderPaper
import org.vedareader.reader.ReaderPaperAppearance

/** Default static colors retained for non-composable callers and legacy tests. */
object ReaderPaperColors {
    val Header = color(ReaderPaperAppearance.Header)
    val Paper = color(ReaderPaperAppearance.Paper)
    val Ink = color(ReaderPaperAppearance.Ink)
    val Muted = color(ReaderPaperAppearance.Muted)
    val Accent = color(ReaderPaperAppearance.Accent)
    val OnHeader = color(ReaderPaperAppearance.OnHeader)
    val Rule = color(ReaderPaperAppearance.Rule)
    val Chapter = color(ReaderPaperAppearance.Chapter)
    val Link = color(ReaderPaperAppearance.Link)

    private fun color(hex: String) = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
}

/** Reader-local Compose projection of the same pure palette used by the native document. */
@Immutable
internal data class ReaderPaperPalette(
    val Paper: Color,
    val Ink: Color,
    val Header: Color,
    val OnHeader: Color,
    val Accent: Color,
    val Link: Color,
    val Muted: Color,
    val Rule: Color,
    val Chapter: Color,
    val dark: Boolean,
)

internal fun paperPalette(paper: ReaderPaper): ReaderPaperPalette {
    val palette = ReaderPaperAppearance.palette(paper)
    fun color(hex: String) = Color(0xFF000000 or hex.removePrefix("#").toLong(16))
    return ReaderPaperPalette(
        Paper = color(palette.Paper), Ink = color(palette.Ink), Header = color(palette.Header),
        OnHeader = color(palette.OnHeader), Accent = color(palette.Accent), Link = color(palette.Link),
        Muted = color(palette.Muted), Rule = color(palette.Rule), Chapter = color(palette.Chapter),
        dark = palette.dark,
    )
}

internal val LocalReaderPaperColors = staticCompositionLocalOf { paperPalette(ReaderPaper.LIGHT) }

/** Use in active reader UI and contents/settings dialogs instead of the static compatibility object. */
@Composable
internal fun readerPaperColors(): ReaderPaperPalette = LocalReaderPaperColors.current

private fun ReaderPaperPalette.colorScheme() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = Accent, onPrimary = Paper, primaryContainer = Chapter, onPrimaryContainer = Ink,
    inversePrimary = Header,
    secondary = Accent, onSecondary = Paper, secondaryContainer = Chapter, onSecondaryContainer = Ink,
    tertiary = Accent, onTertiary = Paper, tertiaryContainer = Chapter, onTertiaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink, surfaceVariant = Chapter, onSurfaceVariant = Muted,
    surfaceTint = Paper, inverseSurface = Ink, inverseOnSurface = Paper,
    outline = Muted, outlineVariant = Rule,
    surfaceDim = Chapter, surfaceBright = Paper, surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper, surfaceContainer = Paper,
    surfaceContainerHigh = Chapter, surfaceContainerHighest = Header,
)

/** Local theme only; bar appearance has exactly the same lifetime as the reader session. */
@Composable
@Suppress("DEPRECATION") // Explicit bar colors remain necessary on supported older Android versions.
internal fun ReaderPaperTheme(paper: ReaderPaper = ReaderPaper.LIGHT, content: @Composable () -> Unit) {
    val colors = remember(paper) { paperPalette(paper) }
    val scheme = remember(colors) { colors.colorScheme() }
    val context = LocalContext.current
    val view = LocalView.current
    // Capture once per reader lifetime, not once per paper change. Otherwise dismissing
    // after several changes could restore an intermediate reader palette instead of home.
    DisposableEffect(context, view) {
        val activity = context.paperReaderActivity()
        if (view.isInEditMode || activity == null) return@DisposableEffect onDispose { }
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val oldStatusColor = window.statusBarColor
        val oldNavigationColor = window.navigationBarColor
        val oldLightStatus = controller.isAppearanceLightStatusBars
        val oldLightNavigation = controller.isAppearanceLightNavigationBars
        val oldStatusContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else false
        val oldNavigationContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else false
        onDispose {
            // Restore synchronously, before the next frame; never post reader colors after exit.
            // Only appearance is owned here. ReaderFullscreen owns visibility and immersive bits.
            window.statusBarColor = oldStatusColor
            window.navigationBarColor = oldNavigationColor
            controller.isAppearanceLightStatusBars = oldLightStatus
            controller.isAppearanceLightNavigationBars = oldLightNavigation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = oldStatusContrast
                window.isNavigationBarContrastEnforced = oldNavigationContrast
            }
        }
    }
    SideEffect {
        val activity = context.paperReaderActivity()
        if (!view.isInEditMode && activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            window.statusBarColor = colors.Paper.toArgb()
            window.navigationBarColor = colors.Paper.toArgb()
            controller.isAppearanceLightStatusBars = !colors.dark
            controller.isAppearanceLightNavigationBars = !colors.dark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(LocalReaderPaperColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

private fun Context.paperReaderActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}