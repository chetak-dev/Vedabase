package org.vedareader.ui

import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.data.*
import org.vedareader.reader.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit local emulator preview. No import, production preferences, or bookmark writes. */
@RunWith(AndroidJUnit4::class)
class ReaderAppearancePreviewCheck {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureWindowAndSettings() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureReaderAppearance") == "true")
        val db = SQLiteDatabase.openDatabase("/data/local/tmp/vedareader-window-source.ivd", null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        val resource = try { IvdBookReader(db).readWindow(227161) } finally { db.close() }
        val document = ReaderDocument(resource.section,
            FolioText.document(resource.section.paragraphs, "en", ReaderPreferences(), bookMode = true),
            resource.position, resource)
        var paper by mutableStateOf(ReaderPaper.LIGHT)
        var spacing by mutableIntStateOf(100)
        var font by mutableIntStateOf(20)
        var state by mutableStateOf(ReaderState(resource.section.node.location("preview").copy(position = 227161),
            "en", document, loading = false, mode = ReadingMode.BOOK))
        compose.setContent {
            VedaPreviewTheme {
                ReviewedReaderScreen(state, {}, {}, {}, {}, {},
                    onBookAnchor = { state = state.copy(location = state.location.copy(position = it)) },
                    fontSize = font, onFontSizeChange = { font = it }, paper = paper, onPaperChange = { paper = it },
                    lineSpacing = spacing, onLineSpacingChange = { spacing = it })
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("reader-text-size") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        capture("reader-window-light")
        compose.onNodeWithTag("reader-text-size").performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertIsDisplayed()
        capture("reader-settings-font")
        compose.onNodeWithTag("reader-paper-DARK").performScrollTo().performClick()
        compose.onNodeWithTag("reader-line-spacing-value").performScrollTo()
        capture("reader-settings-dark-spacing")
        compose.onNodeWithTag("reader-text-size-done").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("reader-text-size") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        capture("reader-window-dark")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val frame = CountDownLatch(1)
        compose.runOnIdle { compose.activity.window.decorView.postOnAnimation {
            compose.activity.window.decorView.postOnAnimation { frame.countDown() }
        } }
        check(frame.await(5, TimeUnit.SECONDS))
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { image.recycle() }
    }
}