package org.vedareader.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.SearchMode
import org.vedareader.data.SearchTarget
import kotlin.math.abs

/** Synthetic UI/pixel contracts only; never opens a repository, corpus, or book reader. */
@RunWith(AndroidJUnit4::class)
class SearchAppearanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appThemeSpecifiesWarmRolesAndNormalTextContrastWithoutChangingTypography() {
        var observed: ColorScheme? = null
        var typography: Typography? = null
        compose.setContent {
            VedaPreviewTheme {
                val colors = MaterialTheme.colorScheme
                val type = MaterialTheme.typography
                SideEffect { observed = colors; typography = type }
            }
        }
        compose.runOnIdle {
            val colors = requireNotNull(observed)
            assertEquals(AppTypography, typography)
            assertEquals(Color(0xFFFFF9EF), ReadingColors.Paper)
            assertEquals(Color(0xFFFBEEDC), ReadingColors.Chapter)
            assertEquals(Color(0xFFB34E15), ReadingColors.Header)
            assertEquals(Color(0xFF2F2B26), ReadingColors.Ink)
            assertEquals(Color(0xFF8A5A12), ReadingColors.Accent)
            assertEquals(Color.White, ReadingColors.OnHeader)
            assertEquals(ReadingColors.Header, colors.primary)
            assertEquals(ReadingColors.Accent, colors.secondary)
            assertEquals(ReadingColors.Header, colors.tertiary)
            listOf(colors.onPrimary, colors.onSecondary, colors.onTertiary).forEach {
                assertEquals(ReadingColors.OnHeader, it)
            }
            listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer,
                colors.surfaceVariant, colors.surfaceDim, colors.surfaceContainer,
                colors.surfaceContainerHigh, colors.surfaceContainerHighest, colors.inversePrimary).forEach {
                assertEquals(ReadingColors.Chapter, it)
            }
            listOf(colors.onPrimaryContainer, colors.onSecondaryContainer, colors.onTertiaryContainer,
                colors.onBackground, colors.onSurface, colors.inverseSurface, colors.scrim).forEach {
                assertEquals(ReadingColors.Ink, it)
            }
            listOf(colors.background, colors.surface, colors.surfaceBright, colors.surfaceContainerLow,
                colors.surfaceTint, colors.inverseOnSurface).forEach { assertEquals(ReadingColors.Paper, it) }
            assertEquals(Color.White, colors.surfaceContainerLowest)
            assertEquals(ReadingColors.Muted, colors.onSurfaceVariant)
            assertEquals(ReadingColors.Muted, colors.outline)
            assertEquals(ReadingColors.Rule, colors.outlineVariant)
            assertEquals(Color(0xFFBA1A1A), colors.error)
            assertEquals(Color.White, colors.onError)
            assertEquals(Color(0xFFFFEDEA), colors.errorContainer)
            assertEquals(Color(0xFF7D231B), colors.onErrorContainer)

            listOf(colors.onPrimary to colors.primary, colors.onSecondary to colors.secondary,
                colors.onTertiary to colors.tertiary, colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.inverseOnSurface to colors.inverseSurface, colors.inversePrimary to colors.inverseSurface,
                colors.onError to colors.error, colors.onErrorContainer to colors.errorContainer).forEach {
                assertContrast(it.first, it.second)
            }
            val surfaces = listOf(colors.background, colors.surface, colors.surfaceVariant, colors.surfaceBright,
                colors.surfaceDim, colors.surfaceContainerLowest, colors.surfaceContainerLow,
                colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest)
            for (surface in surfaces) {
                for (ink in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.secondary)) {
                    assertContrast(ink, surface)
                }
            }
        }
    }

    @Test
    fun targetChipsRenderPaperWhenUnselectedAndBrownWithWhiteCheckWhenSelected() {
        val fixture = showSearch()
        assertTargets(SearchTarget.ALL_TEXT)
        for (target in listOf(SearchTarget.VERSE_WORDS, SearchTarget.ALL_TEXT)) {
            reveal("search-target-${target.name}")
            compose.onNodeWithTag("search-target-${target.name}").performClick()
            assertTargets(target)
        }
        compose.onNodeWithTag("search-target-VERSE_REFERENCE").assertDoesNotExist()
        compose.onNodeWithTag("search-mode-ALL_WORDS").assertExists()
        compose.runOnIdle {
            assertEquals(listOf(SearchTarget.VERSE_WORDS, SearchTarget.ALL_TEXT), fixture.targets)
            assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun modeChipsSwapTheirActualFillTextAndCheckmarkWithoutSubmitting() {
        val fixture = showSearch()
        assertModes(SearchMode.ALL_WORDS)
        for (mode in listOf(SearchMode.EXACT_PHRASE, SearchMode.ALL_WORDS)) {
            reveal("search-mode-${mode.name}")
            compose.onNodeWithTag("search-mode-${mode.name}").performClick()
            assertModes(mode)
        }
        compose.runOnIdle {
            assertEquals(listOf(SearchMode.EXACT_PHRASE, SearchMode.ALL_WORDS), fixture.modes)
            assertEquals(0, fixture.searches)
        }
    }

    @Test
    fun disabledChipStylesRenderExplicitContainersLabelsAndBothIconColors() {
        // The real screen currently leaves filters enabled. Exercise the same production color
        // factory through Material's disabled rendering path without changing that behavior.
        compose.setContent {
            VedaPreviewTheme {
                Column(Modifier.fillMaxSize().background(ReadingColors.Paper)) {
                    for (selected in listOf(false, true)) {
                        val tag = "disabled-$selected"
                        FilterChip(selected = selected, enabled = false, onClick = {},
                            modifier = Modifier.testTag(tag),
                            label = { Text(tag) },
                            leadingIcon = {
                                Icon(Icons.Default.Check, contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("$tag-leading"))
                            },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("$tag-trailing"))
                            },
                            colors = searchFilterChipColors(selected))
                    }
                }
            }
        }
        for (selected in listOf(false, true)) {
            val tag = "disabled-$selected"
            val ink = if (selected) ReadingColors.OnHeader else ReadingColors.Muted
            val paper = if (selected) ReadingColors.Header else ReadingColors.Paper
            compose.onNodeWithTag(tag).assertIsNotEnabled()
            if (selected) compose.onNodeWithTag(tag).assertIsSelected()
            else compose.onNodeWithTag(tag).assertIsNotSelected()
            assertRenderedLabel(tag, tag, ink, paper)
            assertFill(tag, paper)
            assertIconInk("$tag-leading", ink)
            assertIconInk("$tag-trailing", ink)
        }
    }

    private fun assertTargets(selected: SearchTarget) {
        for (target in listOf(SearchTarget.ALL_TEXT, SearchTarget.VERSE_WORDS)) {
            val label = if (target == SearchTarget.ALL_TEXT) R.string.search_ui_target_all_text
                else R.string.search_ui_target_verse_words
            assertSearchChip("search-target-${target.name}", target == selected, label)
        }
    }

    private fun assertModes(selected: SearchMode) {
        for (mode in SearchMode.entries) {
            assertSearchChip("search-mode-${mode.name}", mode == selected,
                if (mode == SearchMode.ALL_WORDS) R.string.search_ui_all_words else R.string.search_ui_exact_phrase)
        }
    }

    private fun assertSearchChip(tag: String, selected: Boolean, label: Int) {
        reveal(tag)
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        if (selected) node.assertIsSelected() else node.assertIsNotSelected()
        val ink = if (selected) ReadingColors.OnHeader else ReadingColors.Ink
        val paper = if (selected) ReadingColors.Header else ReadingColors.Paper
        assertFill(tag, paper)
        assertRenderedLabel(tag, compose.activity.getString(label), ink, paper)
        if (selected) {
            compose.onNodeWithTag("$tag-check", useUnmergedTree = true).assertIsDisplayed()
            assertIconInk("$tag-check", ink)
        } else compose.onNodeWithTag("$tag-check", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertRenderedLabel(tag: String, label: String, ink: Color, paper: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        val style = layouts.single().layoutInput.style
        assertEquals("$tag label must use the resolved content color", ink, style.color)
        assertEquals(AppFontFamily, style.fontFamily)
        assertContrast(style.color, paper)
    }

    private fun assertFill(tag: String, expected: Color) {
        // Tap ripples/color animation can outlive Compose idleness. Wait boundedly for resting
        // pixels instead of weakening the palette or comparing a transient overlay to it.
        compose.waitUntil(5_000) { fractionOfColor(tag, expected) > .60f }
    }

    private fun assertIconInk(tag: String, expected: Color) {
        // The selected icon can be present in semantics while the compositor still has the old
        // paint. Keep the original >3%/RGB +/-2 contract; wait for two matching captures, not time.
        var consecutiveMatches = 0
        var captures = 0
        var last: ColorSample? = null
        val expectedArgb = "#${Integer.toHexString(expected.toArgb()).padStart(8, '0')}"
        try {
            compose.waitUntil(5_000) {
                val sample = sampleColor(tag, expected)
                last = sample
                captures++
                consecutiveMatches = if (sample.fraction > .03f) consecutiveMatches + 1 else 0
                consecutiveMatches >= 2
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$tag must settle to icon ink $expectedArgb (>3%, RGB +/-2); " +
                "captures=$captures consecutiveMatches=$consecutiveMatches last=$last", timeout)
        }
        // A fresh capture must still pass: a transient matching frame is not the assertion.
        val actual = sampleColor(tag, expected)
        val diagnostic = "$tag expectedInk=$expectedArgb captures=$captures settled=$last actual=$actual"
        Log.i("SearchAppearancePaint", diagnostic)
        assertTrue("$diagnostic must paint the specified icon ink (>3%, RGB +/-2)", actual.fraction > .03f)
    }

    private fun fractionOfColor(tag: String, expected: Color): Float = sampleColor(tag, expected).fraction

    private fun sampleColor(tag: String, expected: Color): ColorSample {
        val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val argb = expected.toArgb()
        var matching = 0
        var total = 0
        val counts = mutableMapOf<Int, Int>()
        // Stay inside rounded corners/touch padding, but include real text/icon pixels.
        for (y in image.height / 4 until image.height * 3 / 4) {
            for (x in image.width / 10 until image.width * 9 / 10) {
                val pixel = pixels[y * image.width + x]
                counts[pixel] = (counts[pixel] ?: 0) + 1
                if (abs((pixel shr 16 and 255) - (argb shr 16 and 255)) <= 2 &&
                    abs((pixel shr 8 and 255) - (argb shr 8 and 255)) <= 2 &&
                    abs((pixel and 255) - (argb and 255)) <= 2) matching++
                total++
            }
        }
        assertTrue("$tag must have visible pixels", total > 0)
        val dominant = counts.entries.sortedByDescending { it.value }.take(4).joinToString {
            "#${Integer.toHexString(it.key).padStart(8, '0')}:${it.value}"
        }
        return ColorSample(matching.toFloat() / total,
            "image=${image.width}x${image.height} matching=$matching/$total dominant=[$dominant]")
    }

    private data class ColorSample(val fraction: Float, val pixels: String)

    private fun assertContrast(ink: Color, paper: Color) {
        val ratio = ColorUtils.calculateContrast(ink.toArgb(), paper.toArgb())
        assertTrue("$ink on $paper has contrast $ratio; requires 4.5:1", ratio >= 4.5)
    }

    private fun reveal(tag: String) {
        compose.onNodeWithTag("search-list").performScrollToNode(hasTestTag(tag))
    }

    private fun showSearch(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            VedaPreviewTheme {
                SearchScreen(state = fixture.state, onBack = {}, onQuery = {}, onBooks = {},
                    onMode = { fixture.modes += it; fixture.state = fixture.state.copy(mode = it) },
                    onSearch = { fixture.searches++ }, onSuggestion = {}, onOpenHit = {}, onLoadMore = {},
                    onRetryCatalog = {}, onCancel = {}, onClearHistory = {},
                    onTarget = { fixture.targets += it; fixture.state = fixture.state.copy(target = it) })
            }
        }
        return fixture
    }

    private class Fixture {
        var state by mutableStateOf(SearchUiState(libraryId = "appearance-fixture", libraryTitle = "Synthetic library"))
        val targets = mutableListOf<SearchTarget>()
        val modes = mutableListOf<SearchMode>()
        var searches = 0
    }
}