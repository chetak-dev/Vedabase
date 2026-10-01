package org.vedareader.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.LibraryStore
import org.vedareader.data.ReaderPaper
import org.vedareader.reader.ReadingMode
import java.util.UUID

private val DpRect.width get() = right - left
private val DpRect.height get() = bottom - top

/** Isolated panel callbacks and UUID-owned preferences. No MainActivity, source DB or native host. */
@RunWith(AndroidJUnit4::class)
class ReaderTextSizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun buttonsApplySingleStepsResetToBaselineAndStayOpenUntilDone() {
        val fixture = showDialog()
        assertPercent(80)
        compose.onNodeWithTag("reader-text-size-reset").assertIsNotEnabled()
        compose.onNodeWithTag("reader-text-size-decrease")
            .assertContentDescriptionEquals(label(R.string.reader_text_size_decrease))
        compose.onNodeWithTag("reader-text-size-increase")
            .assertContentDescriptionEquals(label(R.string.reader_text_size_increase)).performClick()
        assertPercent(85)
        compose.onNodeWithTag("reader-text-size-increase").performClick()
        assertPercent(90)
        compose.onNodeWithTag("reader-text-size-decrease").performClick()
        assertPercent(85)
        compose.onNodeWithTag("reader-text-size-reset").assertIsEnabled()
            .assertContentDescriptionEquals(label(R.string.reader_text_size_reset)).performClick()
        assertPercent(80)
        compose.onNodeWithTag("reader-text-size-reset").assertIsNotEnabled()
        compose.onNodeWithTag("reader-text-size-dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(17, 18, 17, 16), fixture.changes)
            assertEquals(0, fixture.dismissals)
        }
        compose.onNodeWithTag("reader-text-size-done").performClick()
        compose.onNodeWithTag("reader-text-size-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.dismissals) }
        compose.onNodeWithTag("reopen-text-size").performClick()
        assertPercent(80)
        compose.runOnIdle { assertEquals(listOf(17, 18, 17, 16), fixture.changes) }
    }

    @Test
    fun boundsDisableRealTapsAndAccessibleSliderExposesAllSeventeenSizes() {
        val fixture = showDialog(initialSize = -100)
        assertPercent(70)
        assertRange(14)
        compose.onNodeWithTag("reader-text-size-decrease").assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertTrue(fixture.changes.isEmpty()) }
        compose.onNodeWithTag("reader-text-size-slider")
            .assertContentDescriptionEquals(label(R.string.reader_text_size))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
        assertPercent(150)
        assertRange(30)
        compose.onNodeWithTag("reader-text-size-increase").assertIsNotEnabled()
            .performTouchInput { click() }
        compose.onNodeWithTag("reader-text-size-decrease").assertIsEnabled().performClick()
        assertPercent(145)
        compose.runOnIdle {
            assertEquals(listOf(30, 29), fixture.changes)
            // An externally supplied invalid value is clamped locally without a write on open.
            fixture.fontSize = 999
        }
        assertPercent(150)
        compose.onNodeWithTag("reader-text-size-increase").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(30, 29), fixture.changes) }
    }

    @Test
    fun sliderPreviewsDuringDragAndAppliesOnlyOnReleaseIncludingAccessibleChanges() {
        val fixture = showDialog()
        val slider = compose.onNodeWithTag("reader-text-size-slider").assertIsDisplayed()
        slider.performTouchInput {
            down(pointerId = 0, position = center)
            moveTo(pointerId = 0, position = Offset(width - 1f, centerY), delayMillis = 160)
        }
        try {
            assertPercent(150)
            assertRange(30)
            assertEquals(30f, textLayout("reader-text-size-preview").layoutInput.style.fontSize.value, 0f)
            compose.runOnIdle {
                assertEquals(16, fixture.fontSize)
                assertTrue("Dragging must not persist or reflow the reader", fixture.changes.isEmpty())
            }
        } finally {
            slider.performTouchInput { up(pointerId = 0) }
        }
        assertPercent(150)
        compose.runOnIdle { assertEquals(listOf(30), fixture.changes); assertEquals(30, fixture.fontSize) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(21.4f)) }
        assertPercent(105)
        assertRange(21)
        compose.onNodeWithTag("reader-text-size-dialog").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(30, 21), fixture.changes); assertEquals(0, fixture.dismissals) }
    }

    @Test
    fun previewUsesAppFontWithoutScalingOtherTextControlsOrAppDensity() {
        val systemFontScale = compose.activity.resources.configuration.fontScale
        val fixture = showDialog(fontScale = 1.3f)
        val outside = textLayout("outside-text-size")
        val preview = textLayout("reader-text-size-preview")
        val value = textLayout("reader-text-size-value")
        val increase = compose.onNodeWithTag("reader-text-size-increase").getUnclippedBoundsInRoot()
        val decrease = compose.onNodeWithTag("reader-text-size-decrease").getUnclippedBoundsInRoot()
        assertEquals(AppFontFamily, preview.layoutInput.style.fontFamily)
        assertEquals(label(R.string.reader_text_size_preview), preview.layoutInput.text.text)
        assertEquals(16f, preview.layoutInput.style.fontSize.value, 0f)
        assertTrue(increase.width.value >= 48f && increase.height.value >= 48f)
        assertTrue(decrease.width.value >= 48f && decrease.height.value >= 48f)

        compose.onNodeWithTag("reader-text-size-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(30f)) }
        val enlarged = textLayout("reader-text-size-preview")
        assertEquals(AppFontFamily, enlarged.layoutInput.style.fontFamily)
        assertEquals(30f, enlarged.layoutInput.style.fontSize.value, 0f)
        assertTrue(enlarged.size.height > preview.size.height)
        assertFalse(enlarged.didOverflowHeight)
        assertEquals(outside.layoutInput.style, textLayout("outside-text-size").layoutInput.style)
        assertEquals(outside.size, textLayout("outside-text-size").size)
        assertEquals(value.layoutInput.style, textLayout("reader-text-size-value").layoutInput.style)
        assertEquals(increase.width, compose.onNodeWithTag("reader-text-size-increase").getUnclippedBoundsInRoot().width)
        assertEquals(increase.height, compose.onNodeWithTag("reader-text-size-increase").getUnclippedBoundsInRoot().height)
        assertEquals(decrease.width, compose.onNodeWithTag("reader-text-size-decrease").getUnclippedBoundsInRoot().width)
        assertEquals(decrease.height, compose.onNodeWithTag("reader-text-size-decrease").getUnclippedBoundsInRoot().height)
        // Dialog has its own Android window density; the reader preference must not
        // change that density (nor the parent's synthetic accessibility scale).
        assertEquals(preview.layoutInput.density.fontScale, enlarged.layoutInput.density.fontScale, 0f)
        compose.runOnIdle {
            assertEquals(1.3f, fixture.appFontScale, 0f)
            assertEquals(systemFontScale, compose.activity.resources.configuration.fontScale, 0f)
        }
    }

    @Test
    fun isolatedStoreDefaultsClampsAndPersistsForBothModesWithoutChangingPaperOrMetadata() {
        val context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
            assertTrue(preferences.all.isEmpty())
            assertEquals(16, LibraryStore(context).readerPreferences().fontSize)
            assertTrue("Default reads must not create preferences", preferences.all.isEmpty())
            check(preferences.edit().putString("fixture-owner", "synthetic-text-size-test")
                .putString("paper", ReaderPaper.SEPIA.name).putString("active", "synthetic-library")
                .putString("libraries", "[]").putString("position:synthetic-library", "synthetic-location").commit())
            for ((stored, expected) in listOf(-50 to 14, 900 to 30)) {
                check(preferences.edit().putInt("textSize", stored).commit())
                val before = preferences.all.toMap()
                assertEquals(expected, LibraryStore(context).readerPreferences().fontSize)
                assertEquals(before, preferences.all)
            }
            for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE)) {
                val store = LibraryStore(context)
                store.readingMode = mode
                for (size in listOf(14, 16, 23, 30, 20)) {
                    val before = preferences.all.toMap()
                    store.savePreferences(store.readerPreferences().copy(fontSize = size))
                    // Fence apply() on the UUID-owned file only; no sleeps or real registry reads.
                    assertTrue(preferences.edit().commit())
                    val reopened = LibraryStore(context)
                    assertEquals(size, reopened.readerPreferences().fontSize)
                    assertEquals(ReaderPaper.SEPIA, reopened.readerPreferences().paper)
                    assertEquals(mode, reopened.readingMode)
                    assertEquals(before + ("textSize" to size) + ("lineFactor" to 100), preferences.all)
                }
            }
        } finally {
            context.deleteOwnedPreferences()
        }
    }

    private fun showDialog(initialSize: Int = 16, fontScale: Float = 1f): Fixture {
        val fixture = Fixture(initialSize)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val appFontScale = LocalDensity.current.fontScale
                SideEffect { fixture.appFontScale = appFontScale }
                VedaPreviewTheme {
                    Column {
                        Text("Unchanged app text", Modifier.testTag("outside-text-size"))
                        TextButton(onClick = { fixture.open = true }, modifier = Modifier.testTag("reopen-text-size")) {
                            Text("Open synthetic text size")
                        }
                    }
                    if (fixture.open) ReaderTextSizeDialog(fixture.fontSize,
                        onFontSizeChange = { fixture.changes += it; fixture.fontSize = it },
                        onDismiss = { fixture.dismissals++; fixture.open = false })
                }
            }
        }
        compose.onNodeWithTag("reader-text-size-dialog").assertIsDisplayed()
        return fixture
    }

    private fun assertPercent(percent: Int) {
        compose.onNodeWithTag("reader-text-size-value").assertIsDisplayed()
            .assertTextEquals(compose.activity.getString(R.string.reader_text_size_percent, percent))
    }

    private fun assertRange(size: Int) {
        val range = compose.onNodeWithTag("reader-text-size-slider").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(14f..30f, range.range)
        assertEquals(15, range.steps)
        assertEquals(size.toFloat(), range.current, 0f)
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private class Fixture(initialSize: Int) {
        var fontSize by mutableIntStateOf(initialSize)
        var open by mutableStateOf(true)
        val changes = mutableListOf<Int>()
        var dismissals = 0
        var appFontScale = 0f
    }

    private class FixtureContext(target: Context) : ContextWrapper(target) {
        private val ownedName = "reader-text-size-test-${UUID.randomUUID()}-reader"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "reader" && mode == Context.MODE_PRIVATE)
            return baseContext.getSharedPreferences(ownedName, mode)
        }
        fun deleteOwnedPreferences() {
            check(ownedName.startsWith("reader-text-size-test-") && ownedName != "reader")
            check(baseContext.deleteSharedPreferences(ownedName))
        }
    }
}