package org.vedareader.ui

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ReaderPaper
import org.vedareader.reader.ReaderPaperAppearance

/** Callback-driven synthetic state only: no MainActivity, preferences, native host, or source DB. */
@RunWith(AndroidJUnit4::class)
class ReaderSettingsDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onePanelUsesIndependentDefaultsAndRetainsCallerValuesAcrossReopening() {
        val fixture = showDialog()
        compose.runOnIdle { assertNoChanges(fixture) }
        reveal("reader-text-size-value").assertTextEquals(percent(80))
        reveal("reader-text-size-reset").assertIsNotEnabled()
        reveal("reader-line-spacing-default").assertIsNotEnabled()
        reveal("reader-text-size-increase").performClick()
        reveal("reader-line-spacing-increase").performClick()
        reveal("reader-paper-DARK").performClick().assertIsSelected()
        compose.onNodeWithTag("reader-settings-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-text-size-dialog").assertDoesNotExist()
        compose.onNodeWithTag("reader-text-size-done").performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
        compose.onNodeWithTag("reopen-reader-settings").performClick()
        reveal("reader-text-size-value").assertTextEquals(percent(85))
        reveal("reader-line-spacing-value").assertTextEquals(percent(105))
        reveal("reader-paper-DARK").assertIsSelected()

        reveal("reader-text-size-reset")
            .assertContentDescriptionEquals(label(R.string.reader_text_size_reset)).performClick()
        compose.runOnIdle {
            assertEquals(16, fixture.fontSize)
            assertEquals(105, fixture.lineSpacing)
            assertEquals(ReaderPaper.DARK, fixture.paper)
        }
        reveal("reader-text-size-increase").performClick()
        reveal("reader-line-spacing-default")
            .assertContentDescriptionEquals(label(R.string.reader_line_spacing_reset)).performClick()
        compose.runOnIdle {
            assertEquals(17, fixture.fontSize)
            assertEquals(100, fixture.lineSpacing)
            assertEquals(ReaderPaper.DARK, fixture.paper)
            assertEquals(listOf(17, 16, 17), fixture.fontChanges)
            assertEquals(listOf(105, 100), fixture.spacingChanges)
            assertEquals(listOf(ReaderPaper.DARK), fixture.paperChanges)
            assertEquals(1, fixture.dismissals)
        }
    }

    @Test
    fun fontBoundsAndAccessibleSliderKeepTheSeventeenSizeContract() {
        val fixture = showDialog(initialSize = -100)
        reveal("reader-text-size-value").assertTextEquals(percent(70))
        reveal("reader-text-size-decrease").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertNoChanges(fixture) }
        reveal("reader-text-size-slider")
            .assertContentDescriptionEquals(label(R.string.reader_text_size))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
        assertFontRange(30)
        reveal("reader-text-size-value").assertTextEquals(percent(150))
        reveal("reader-text-size-increase").assertIsNotEnabled().performTouchInput { click() }
        reveal("reader-text-size-decrease").assertIsEnabled().performClick()
        assertFontRange(29)
        compose.runOnIdle { fixture.fontSize = 999 }
        reveal("reader-text-size-value").assertTextEquals(percent(150))
        compose.runOnIdle {
            assertEquals(listOf(30, 29), fixture.fontChanges)
            assertTrue(fixture.paperChanges.isEmpty())
            assertTrue(fixture.spacingChanges.isEmpty())
        }
    }

    @Test
    fun sliderOnlyCommitsOnReleaseAndPreviewReflectsFontSpacingAndPaper() {
        val fixture = showDialog()
        val baselinePreview = textLayout("reader-text-size-preview")
        val outside = textLayout("outside-reader-settings")
        val slider = reveal("reader-text-size-slider")
        slider.performTouchInput {
            down(pointerId = 0, position = center)
            moveTo(pointerId = 0, position = Offset(width - 1f, centerY), delayMillis = 160)
        }
        try {
            assertFontRange(30)
            // Inspect the composed preview without scrolling while a pointer is still down.
            assertEquals(30f, textLayout("reader-text-size-preview").layoutInput.style.fontSize.value, 0f)
            compose.runOnIdle {
                assertEquals(16, fixture.fontSize)
                assertTrue("A drag must not persist/reflow on each move", fixture.fontChanges.isEmpty())
            }
        } finally {
            slider.performTouchInput { up(pointerId = 0) }
        }
        val controls = textLayout("reader-text-size-value").layoutInput.style
        repeat(4) { reveal("reader-line-spacing-increase").performClick() }
        reveal("reader-paper-DARK").performClick()
        reveal("reader-text-size-preview")
        val enlarged = textLayout("reader-text-size-preview")
        assertEquals(label(R.string.reader_text_size_preview), enlarged.layoutInput.text.text)
        assertEquals(AppFontFamily, enlarged.layoutInput.style.fontFamily)
        assertEquals(30f, enlarged.layoutInput.style.fontSize.value, 0f)
        assertEquals(30f * 1.4f * 1.2f, enlarged.layoutInput.style.lineHeight.value, 0.001f)
        assertEquals(color(ReaderPaperAppearance.palette(ReaderPaper.DARK).Ink), enlarged.layoutInput.style.color)
        assertFalse(enlarged.didOverflowHeight)
        assertTrue(enlarged.size.height > baselinePreview.size.height)
        assertEquals(baselinePreview.layoutInput.density, enlarged.layoutInput.density)
        // Reader preferences may recolor controls, but never resize their typography.
        assertEquals(controls.fontSize, textLayout("reader-text-size-value").layoutInput.style.fontSize)
        assertEquals(controls.lineHeight, textLayout("reader-text-size-value").layoutInput.style.lineHeight)
        assertEquals(outside.layoutInput.style, textLayout("outside-reader-settings").layoutInput.style)
        compose.runOnIdle {
            assertEquals(listOf(30), fixture.fontChanges)
            assertEquals(listOf(105, 110, 115, 120), fixture.spacingChanges)
        }
    }

    @Test
    fun spacingChangesInFivesClampsBoundsAndDoesNotWriteOnOpenOrExternalUpdates() {
        val fixture = showDialog(initialSize = 24, initialPaper = ReaderPaper.SEPIA, initialSpacing = -1)
        reveal("reader-line-spacing-value").assertTextEquals(percent(85))
        reveal("reader-line-spacing-decrease").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertNoChanges(fixture) }
        for (expected in 90..150 step 5) {
            reveal("reader-line-spacing-increase").performClick()
            reveal("reader-line-spacing-value").assertTextEquals(percent(expected))
        }
        reveal("reader-line-spacing-increase").assertIsNotEnabled().performTouchInput { click() }
        reveal("reader-line-spacing-default").performClick()
        reveal("reader-line-spacing-default").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals((90..150 step 5).toList() + 100, fixture.spacingChanges)
            assertEquals(24, fixture.fontSize)
            assertEquals(ReaderPaper.SEPIA, fixture.paper)
            fixture.lineSpacing = 999
        }
        reveal("reader-line-spacing-value").assertTextEquals(percent(150))
        reveal("reader-line-spacing-increase").assertIsNotEnabled()
        compose.runOnIdle { fixture.lineSpacing = 92 }
        reveal("reader-line-spacing-value").assertTextEquals(percent(92))
        reveal("reader-line-spacing-increase").performClick()
        compose.runOnIdle {
            assertEquals((90..150 step 5).toList() + listOf(100, 97), fixture.spacingChanges)
            assertTrue(fixture.fontChanges.isEmpty())
            assertTrue(fixture.paperChanges.isEmpty())
        }
    }

    @Test
    fun paperCallbacksUpdateOneSelectedCheckmarkAndTheActualLocalAndMaterialPalettes() {
        val fixture = showDialog()
        for (paper in listOf(ReaderPaper.LIGHT, ReaderPaper.SEPIA, ReaderPaper.GREEN, ReaderPaper.GRAY, ReaderPaper.DARK, ReaderPaper.LIGHT)) {
            reveal("reader-paper-${paper.name}").performClick().assertIsSelected()
            // Selecting the current choice again is not another persistence operation.
            reveal("reader-paper-${paper.name}").performClick()
            for (other in ReaderPaper.entries.filter { it != paper }) {
                compose.onNodeWithTag("reader-paper-${other.name}").assertIsNotSelected()
            }
            compose.onAllNodesWithTag("reader-paper-selected-check", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag("reader-paper-selected-check", useUnmergedTree = true)
                .assert(hasAnyAncestor(hasTestTag("reader-paper-${paper.name}")))
            val expected = ReaderPaperAppearance.palette(paper)
            compose.runOnIdle {
                val actual = requireNotNull(fixture.palette)
                assertEquals(color(expected.Paper), actual.Paper)
                assertEquals(color(expected.Ink), actual.Ink)
                assertEquals(color(expected.Header), actual.Header)
                assertEquals(color(expected.OnHeader), actual.OnHeader)
                assertEquals(color(expected.Accent), actual.Accent)
                assertEquals(color(expected.Link), actual.Link)
                assertEquals(color(expected.Muted), actual.Muted)
                assertEquals(color(expected.Rule), actual.Rule)
                assertEquals(color(expected.Chapter), actual.Chapter)
                assertEquals(expected.dark, actual.dark)
                assertEquals(actual.Paper, fixture.materialSurface)
                assertEquals(actual.Ink, fixture.materialInk)
                assertEquals("Static compatibility colors never change", color(ReaderPaperAppearance.Paper), ReaderPaperColors.Paper)
            }
            reveal("reader-text-size-preview")
            assertEquals(color(expected.Ink), textLayout("reader-text-size-preview").layoutInput.style.color)
        }
        compose.runOnIdle {
            assertEquals(listOf(ReaderPaper.SEPIA, ReaderPaper.GREEN, ReaderPaper.GRAY, ReaderPaper.DARK, ReaderPaper.LIGHT), fixture.paperChanges)
            assertTrue(fixture.fontChanges.isEmpty())
            assertTrue(fixture.spacingChanges.isEmpty())
        }
    }

    @Test
    fun allControlsHave48dpTargetsWithFixedDoneAndBackAcrossScrolling() {
        val fixture = showDialog(initialSize = 30, initialSpacing = 150)
        val doneBefore = compose.onNodeWithTag("reader-text-size-done").getUnclippedBoundsInRoot()
        val backBefore = compose.onNodeWithTag("reader-settings-back").getUnclippedBoundsInRoot()
        for (tag in listOf("reader-text-size-decrease", "reader-text-size-increase", "reader-text-size-slider",
            "reader-text-size-reset", "reader-paper-LIGHT", "reader-paper-SEPIA", "reader-paper-GREEN", "reader-paper-GRAY", "reader-paper-DARK",
            "reader-line-spacing-decrease", "reader-line-spacing-increase", "reader-line-spacing-default")) {
            reveal(tag)
            assert48DpTarget(tag)
            compose.onNodeWithTag("reader-text-size-done").assertIsDisplayed()
            compose.onNodeWithTag("reader-settings-back").assertIsDisplayed()
            assertEquals(doneBefore, compose.onNodeWithTag("reader-text-size-done").getUnclippedBoundsInRoot())
            assertEquals(backBefore, compose.onNodeWithTag("reader-settings-back").getUnclippedBoundsInRoot())
        }
        for (tag in listOf("reader-text-size-done", "reader-settings-back")) {
            assert48DpTarget(tag)
        }
        compose.onNodeWithTag("reader-settings-back").performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
        compose.runOnIdle { assertNoChanges(fixture); assertEquals(1, fixture.dismissals) }
    }

    @Test
    fun systemBackDismissesWithoutWritingPreferences() {
        val fixture = showDialog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            compose.waitUntil(5_000) { compose.runOnIdle {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.hasWindowFocus() && it !== compose.activity.window.decorView
                }
            } }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
        compose.runOnIdle { assertNoChanges(fixture); assertEquals(1, fixture.dismissals) }
    }

    @Test
    fun paperChangesUpdateSystemIconsAndRestoreOriginalBarsWhenReaderLeaves() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()),
                navigationBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()))
        }
        val original = compose.runOnIdle { barAppearance() }
        val targetSdk = compose.activity.applicationInfo.targetSdkVersion
        val enforcedEdgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            targetSdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        val fixture = showDialog()
        val outside = textLayout("outside-reader-settings").layoutInput.style
        for (immersive in listOf(false, true)) {
            compose.runOnIdle { fixture.readerMounted = true; fixture.fullscreen = immersive }
            // Finish with dark icons, unlike the original SystemBarStyle.dark (light icons),
            // so restoration remains observable even when bar colors are forced transparent.
            for (paper in listOf(ReaderPaper.SEPIA, ReaderPaper.DARK, ReaderPaper.LIGHT,
                ReaderPaper.DARK, ReaderPaper.LIGHT)) {
                compose.runOnIdle { fixture.paper = paper }
                compose.runOnIdle {
                    val palette = ReaderPaperAppearance.palette(paper)
                    val bars = barAppearance()
                    val diagnostic = "SDK=${Build.VERSION.SDK_INT} target=$targetSdk paper=$paper " +
                        "fullscreen=$immersive bars=$bars"
                    Log.i("ReaderSettingsBars", diagnostic)
                    // Window's API 35/target 35 contract forces transparent bar colors. The
                    // older-platform color contract, icon flags and local theme still matter.
                    val expectedBarColor = if (enforcedEdgeToEdge) Color.Transparent.toArgb()
                        else color(palette.Paper).toArgb()
                    assertEquals("$diagnostic status color", expectedBarColor, bars.status)
                    assertEquals("$diagnostic navigation color", expectedBarColor, bars.navigation)
                    assertEquals("$diagnostic status icons", !palette.dark, bars.lightStatus)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        assertEquals("$diagnostic navigation icons", !palette.dark, bars.lightNavigation)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        assertEquals("$diagnostic status contrast", false, bars.statusContrast)
                        assertEquals("$diagnostic navigation contrast", false, bars.navigationContrast)
                    }
                    // These are the actual composed roles under the transparent bars, not a
                    // claim about native reader/system-bar pixels (covered by the host fixture).
                    val actual = requireNotNull(fixture.palette)
                    assertEquals("$diagnostic local paper", color(palette.Paper), actual.Paper)
                    assertEquals("$diagnostic local ink", color(palette.Ink), actual.Ink)
                    assertEquals("$diagnostic local dark mode", palette.dark, actual.dark)
                    assertEquals("$diagnostic material background", actual.Paper, fixture.materialBackground)
                    assertEquals("$diagnostic material surface", actual.Paper, fixture.materialSurface)
                    assertEquals("$diagnostic material ink", actual.Ink, fixture.materialInk)
                }
                assertEquals(outside, textLayout("outside-reader-settings").layoutInput.style)
            }
            compose.runOnIdle {
                val beforeLeaving = barAppearance()
                assertNotEquals("Status icon restoration must not be a no-op", original.lightStatus, beforeLeaving.lightStatus)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    assertNotEquals("Navigation icon restoration must not be a no-op", original.lightNavigation, beforeLeaving.lightNavigation)
                }
                if (!enforcedEdgeToEdge) {
                    assertNotEquals("Older Android must also restore the bar color", original.status, beforeLeaving.status)
                }
            }
            compose.runOnIdle { fixture.readerMounted = false }
            compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("Restore the exact pre-reader appearance; fullscreen=$immersive", original, barAppearance())
                assertNoChanges(fixture)
            }
        }
    }

    @Test
    fun bothLoadingPresentationsUseTheSameTwoLinesAndSpinner() {
        compose.setContent {
            ReaderPaperTheme(ReaderPaper.DARK) {
                Column {
                    ReaderLoadingIndicator(Modifier.testTag("center-loader"))
                    ReaderLoadingIndicator(Modifier.testTag("compact-loader"), compact = true)
                }
            }
        }
        for (tag in listOf("center-loader", "compact-loader")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
                .assertTextContains(label(R.string.book_loading_greeting))
                .assertTextContains(label(R.string.book_loading_body))
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true)
            .assertCountEquals(2)
    }

    private fun showDialog(initialSize: Int = 16, initialPaper: ReaderPaper = ReaderPaper.LIGHT,
        initialSpacing: Int = 100): Fixture {
        val fixture = Fixture(initialSize, initialPaper, initialSpacing)
        compose.setContent {
            VedaPreviewTheme {
                Column {
                    Text("Unchanged app text", Modifier.testTag("outside-reader-settings"))
                    TextButton(onClick = { fixture.open = true }, modifier = Modifier.testTag("reopen-reader-settings")) {
                        Text("Open synthetic reading settings")
                    }
                }
                if (fixture.readerMounted) ReaderPaperTheme(fixture.paper) {
                    ReaderFullscreen(fixture.fullscreen)
                    val palette = readerPaperColors()
                    val material = MaterialTheme.colorScheme
                    SideEffect {
                        fixture.palette = palette
                        fixture.materialBackground = material.background
                        fixture.materialSurface = material.surface
                        fixture.materialInk = material.onSurface
                    }
                    if (fixture.open) ReaderSettingsDialog(fixture.fontSize,
                        onFontSizeChange = { fixture.fontChanges += it; fixture.fontSize = it },
                        onDismiss = { fixture.dismissals++; fixture.open = false },
                        paper = fixture.paper,
                        onPaperChange = { fixture.paperChanges += it; fixture.paper = it },
                        lineSpacing = fixture.lineSpacing,
                        onLineSpacingChange = { fixture.spacingChanges += it; fixture.lineSpacing = it })
                }
            }
        }
        compose.onNodeWithTag("reader-settings-dialog").assertIsDisplayed()
        return fixture
    }

    private fun reveal(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun assert48DpTarget(tag: String) {
        val interaction = compose.onNodeWithTag(tag)
        val bounds = interaction.getUnclippedBoundsInRoot()
        val node = interaction.fetchSemanticsNode()
        // A dialog has its own density; neither the activity nor compose.density proves its size.
        val density = node.layoutInfo.density.density
        val size = node.size
        val width = (bounds.right - bounds.left).value
        val height = (bounds.bottom - bounds.top).value
        val measuredWidth = size.width.toDouble() / density.toDouble()
        val measuredHeight = size.height.toDouble() / density.toDouble()
        val diagnostic = "$tag unclippedDp=[${bounds.left.value}, ${bounds.top.value}, " +
            "${bounds.right.value}, ${bounds.bottom.value}] extentDp=[$width, $height] " +
            "sizePx=[${size.width}, ${size.height}] density=$density " +
            "measuredDp=[$measuredWidth, $measuredHeight] positionPx=${node.positionInRoot}"
        Log.i("ReaderSettingsBounds", diagnostic)
        // First independently prove a real >=48dp layout. Only then tolerate <=0.1dp lost by
        // subtracting separately converted floating root coordinates; never accept a small target.
        assertTrue("$diagnostic measured width must be >=48dp", measuredWidth >= 48.0)
        assertTrue("$diagnostic measured height must be >=48dp", measuredHeight >= 48.0)
        assertTrue("$diagnostic bounds width must be >=48dp (coordinate rounding <=0.1dp)",
            width >= 48f || 48f - width <= 0.1f)
        assertTrue("$diagnostic bounds height must be >=48dp (coordinate rounding <=0.1dp)",
            height >= 48f || 48f - height <= 0.1f)
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun assertFontRange(size: Int) {
        val range = compose.onNodeWithTag("reader-text-size-slider").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(14f..30f, range.range)
        assertEquals(15, range.steps)
        assertEquals(size.toFloat(), range.current, 0f)
    }

    private fun assertNoChanges(fixture: Fixture) {
        assertTrue(fixture.fontChanges.isEmpty())
        assertTrue(fixture.paperChanges.isEmpty())
        assertTrue(fixture.spacingChanges.isEmpty())
    }

    private fun label(id: Int) = compose.activity.getString(id)
    private fun percent(value: Int) = compose.activity.getString(R.string.reader_text_size_percent, value)
    private fun color(hex: String) = Color(android.graphics.Color.parseColor(hex))

    @Suppress("DEPRECATION") // Snapshot exact appearance owned/restored by ReaderPaperTheme, not visibility.
    private fun barAppearance(): Bars {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return Bars(window.statusBarColor, window.navigationBarColor,
            controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else null)
    }

    private data class Bars(val status: Int, val navigation: Int, val lightStatus: Boolean,
        val lightNavigation: Boolean, val statusContrast: Boolean?, val navigationContrast: Boolean?)

    private class Fixture(initialSize: Int, initialPaper: ReaderPaper, initialSpacing: Int) {
        var fontSize by mutableIntStateOf(initialSize)
        var paper by mutableStateOf(initialPaper)
        var lineSpacing by mutableIntStateOf(initialSpacing)
        var open by mutableStateOf(true)
        var readerMounted by mutableStateOf(true)
        var fullscreen by mutableStateOf(false)
        val fontChanges = mutableListOf<Int>()
        val paperChanges = mutableListOf<ReaderPaper>()
        val spacingChanges = mutableListOf<Int>()
        var dismissals = 0
        var palette: ReaderPaperPalette? = null
        var materialBackground: Color? = null
        var materialSurface: Color? = null
        var materialInk: Color? = null
    }
}