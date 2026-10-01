package org.vedareader.reader

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.Paragraph
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingSection
import org.vedareader.data.ReadingUnitKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Font-only checkpoint, deliberately using the reviewed host rather than the pagination draft. */
@RunWith(AndroidJUnit4::class)
class FontPreviewHostTest {
    @Test
    fun bundledSevenFontsAreLocalValidAndBothLicensesAreIncluded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fonts = listOf(
            "FiraSansCondensed-Regular.ttf" to R.font.fira_sans_condensed_regular,
            "FiraSansCondensed-Italic.ttf" to R.font.fira_sans_condensed_italic,
            "FiraSansCondensed-Medium.ttf" to R.font.fira_sans_condensed_medium,
            "FiraSansCondensed-SemiBold.ttf" to R.font.fira_sans_condensed_semibold,
            "FiraSansCondensed-Bold.ttf" to R.font.fira_sans_condensed_bold,
            "FiraSansCondensed-BoldItalic.ttf" to R.font.fira_sans_condensed_bold_italic,
            "NotoSerifDevanagari.ttf" to R.font.noto_serif_devanagari,
        )
        assertEquals(7, fonts.size)
        for ((name, resource) in fonts) {
            val response = ReaderFonts.response(Uri.parse("https://reader.local/fonts/$name"), "GET", false, context.resources)
            assertEquals(200, response.statusCode)
            assertEquals("font/ttf", response.mimeType)
            val bytes = requireNotNull(response.data).use { it.readBytes() }
            assertTrue(name, bytes.size > 100_000)
            assertArrayEquals(name, byteArrayOf(0, 1, 0, 0), bytes.take(4).toByteArray())
            val expected = context.resources.openRawResource(resource).use { it.readBytes() }
            assertArrayEquals(name, expected, bytes)
        }
        for (name in listOf("FiraSansCondensed-OFL.txt", "NotoSerifDevanagari-OFL.txt")) {
            val license = context.assets.open("fonts/$name").bufferedReader().use { it.readText() }
            assertTrue(name, license.contains("SIL OPEN FONT LICENSE", ignoreCase = true))
            assertTrue(name, license.contains("Version 1.1"))
        }
        val denied = ReaderFonts.response(Uri.parse("https://reader.local/fonts/../FiraSansCondensed-Regular.ttf"),
            "GET", false, context.resources)
        assertEquals(403, denied.statusCode)
        requireNotNull(denied.data).use { assertEquals(-1, it.read()) }
    }

    @Test
    fun reviewedBookStillScrollsVerticallyWithoutLoadingAnotherSectionOrEnablingScripts() {
        val node = ContentNode(1, 0, "Synthetic font preview", 100, 0, 1)
        val paragraphs = List(70) { index -> Paragraph(index + 1,
            "Synthetic prose ā ś ṛ. This paragraph tests the temporary reading font and the existing scrolling behaviour. ", "PA_Purport", "") }
        val section = ReadingSection(node, ReadingUnitKind.SECTION, "Synthetic book", paragraphs)
        val html = FolioText.document(paragraphs, "en", ReaderPreferences(), useReadingFont = true)
        val state = ReaderState(node.location("synthetic"), "en", ReaderDocument(section, html), loading = false, mode = ReadingMode.BOOK)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: ReaderPageHost
            var nextCalls = 0
            val ready = CountDownLatch(1)
            scenario.onActivity { activity ->
                host = ReaderPageHost(activity, paginateBooks = false)
                host.onNextUnit = { nextCalls++ }
                host.onStatusChanged = { if (!it.busy && it.hasPage) ready.countDown() }
                activity.setContentView(host)
                host.submit(state, 100) { null }
            }
            assertTrue("Font preview must render", ready.await(10, TimeUnit.SECONDS))
            lateinit var view: OfflineReaderView
            val turned = CountDownLatch(1)
            scenario.onActivity {
                view = requireNotNull(host.settledView)
                assertFalse(view.paginatedBook)
                assertFalse(view.settings.javaScriptEnabled)
                assertTrue(view.scrollRange > view.viewportHeight)
                assertEquals(0, view.scrollY)
                host.onStatusChanged = { if (!it.busy && it.hasPage) turned.countDown() }
                host.next()
            }
            assertTrue("Existing book page step must settle", turned.await(10, TimeUnit.SECONDS))
            scenario.onActivity {
                assertSame(view, host.settledView)
                assertTrue(view.scrollY > 0)
                assertEquals(0, view.scrollX)
                assertEquals(0, nextCalls)
                host.release()
            }
        }
    }
}