package org.vedareader.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.R
import org.vedareader.data.BookResource
import org.vedareader.data.Bookmark
import org.vedareader.data.BookmarkStore
import org.vedareader.data.ContentNode
import org.vedareader.data.LibraryInfo
import org.vedareader.data.LibraryStore
import org.vedareader.data.ReaderPaper
import org.vedareader.data.ReaderPreferences
import org.vedareader.data.ReadingUnitKind
import org.vedareader.reader.FolioText
import org.vedareader.reader.OfflineReaderView
import org.vedareader.reader.ReaderDocument
import org.vedareader.reader.ReaderPageHost
import org.vedareader.reader.ReaderPaperAppearance
import org.vedareader.reader.ReaderState
import org.vedareader.reader.ReadingMode
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real VM -> LibraryRoute -> native reader, using only a small, read-only synthetic SQLite file.
 * Application/applicationContext, library storage and BookmarkStore are redirected under a UUID
 * cache root; preferences are separately UUID-namespaced. Never launch MainActivity or access the
 * production registry/corpus. The isolation pattern is a private copy of SearchNavigationTest's.
 *
 * Wait budgets: VM/search/bookmarks/DOM/cleanup 10 s; native style/layout 20 s with 200 ms of stable
 * geometry. DOM access is restricted to the two read-only queries below: no JS mutation, bridge,
 * observers, permissions changes, source HTML edits or test-side renderer/style replacement.
 */
@RunWith(AndroidJUnit4::class)
class ReadingSettingsIntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var root: File
    private lateinit var application: IsolatedApplication
    private lateinit var preferences: SharedPreferences
    private lateinit var store: LibraryStore
    private lateinit var library: LibraryInfo
    private lateinit var source: File
    private lateinit var sourceHash: ByteArray
    private var sourceSize = 0L
    private lateinit var unrelated: Map<String, *>
    private lateinit var model: LibraryViewModel
    private var modelJob: Job? = null
    private val models = ViewModelStore()
    private val mounted = mutableStateOf(false)
    private var contentInstalled = false
    private lateinit var homeBars: Bars
    private var appColors: Pair<Int, Int>? = null
    private var systemFontScale = 1f

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir, "reading-settings-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        application = IsolatedApplication(target, root)
        assertSame(application, application.applicationContext)
        assertTrue(application.noBackupFilesDir.canonicalPath.startsWith(root.canonicalPath + File.separator))
        preferences = application.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        check(preferences.edit().putString("fixture-owner", root.name).putString("saved", "[]").commit())
        store = LibraryStore(application)
        assertTrue(store.libraries().isEmpty())
        library = registerFixture()
        unrelated = unrelatedPreferences()
        compose.runOnIdle {
            compose.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()),
                navigationBarStyle = SystemBarStyle.dark(ReadingColors.Header.toArgb()))
            homeBars = bars()
        }
    }

    @After
    fun tearDown() {
        try {
            if (contentInstalled) {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            }
        } finally {
            try {
                clearModel()
                if (::sourceHash.isInitialized) {
                    assertEquals(sourceSize, source.length())
                    assertArrayEquals("Settings/navigation must never write the synthetic source", sourceHash, digest(source))
                }
                if (::unrelated.isInitialized) assertEquals(unrelated, unrelatedPreferences())
            } finally {
                try {
                    if (::application.isInitialized) application.deleteOwnedPreferences()
                } finally {
                    if (::root.isInitialized) {
                        check(root.name.startsWith("reading-settings-"))
                        UUID.fromString(root.name.removePrefix("reading-settings-"))
                        check(root.deleteRecursively())
                    }
                }
            }
        }
    }

    @Test
    fun settingsSetBeforeInitialScrollingLoadReachNativeCssAndRestoreHomeOnExit() = initialStyle(ReadingMode.VERSE)

    @Test
    fun settingsSetBeforeInitialPagedLoadReachNativeCssAndRestoreHomeOnExit() = initialStyle(ReadingMode.BOOK)

    @Test
    fun scrollingSettingsCallbacksReflowTheSameWindowAndDefaultsRetainTheParagraphFraction() = settingsRoundTrip(ReadingMode.VERSE)

    @Test
    fun pagedSettingsCallbacksReflowTheSameWindowAndDefaultsRetainTheParagraph() = settingsRoundTrip(ReadingMode.BOOK)

    @Test
    fun toggleBookmarkAndNestedSearchOpenBoundedWindowsWithTheCurrentStyle() {
        startModel()
        openSyntheticHit("amber", MIDDLE)
        mountRoute()
        awaitStyle(ReaderPreferences())
        var chosen = ReaderPreferences(22, ReaderPaper.DARK, 125)
        setStyle(chosen)
        awaitStyle(chosen)
        seekInsideParagraph()
        awaitStyle(chosen)
        val retained = retain()
        compose.onNodeWithTag("reader-bookmark").assertIsEnabled().performClick()
        val bookmark = awaitBookmarks(1).single()
        assertEquals(MIDDLE, bookmark.location.position)
        assertEquals(library.id, bookmark.location.libraryId)
        assertEquals(work.record, bookmark.bookRecord)
        assertEquals(listOf(bookmark), durableBookmarks())
        compose.onNodeWithTag("reader-bookmark").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, compose.activity.getString(R.string.bookmark_marked_state)))
        assertRetained(retained)

        compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled().performClick()
        val paged = awaitReader(ReadingMode.BOOK)
        assertEquals(MIDDLE, requireNotNull(paged.document?.bookResource).anchorRecord)
        assertEquals(retained.resource.section.paragraphs, paged.document?.section?.paragraphs)
        awaitStyle(chosen)
        assertPreferences(chosen)
        assertEquals(ReadingMode.BOOK, store.readingMode)

        // Hiding the reader for search disposes its view/theme, but retains the VM document.
        val document = requireNotNull(model.state.value.reader?.document)
        compose.onNodeWithTag("reader-search").performClick()
        awaitCatalog()
        assertHomeAppearance()
        compose.onNodeWithTag("search-back").performClick()
        awaitStyle(chosen)
        assertSame(document, model.state.value.reader?.document)
        leaveSeededReader()
        compose.onNodeWithTag("library-more").performClick(); compose.onNodeWithTag("more-bookmarks").performClick()
        compose.onNodeWithTag("bookmarks-screen").assertIsDisplayed()

        // A saved location contains no appearance snapshot. The next open must use NEW settings.
        chosen = ReaderPreferences(24, ReaderPaper.SEPIA, 115)
        setStyle(chosen)
        compose.onNodeWithTag("bookmark-open-${bookmark.id}").performClick()
        val reopened = awaitReader(ReadingMode.BOOK)
        val earlierWindow = assertResource(reopened, MIDDLE)
        assertEquals(MIDDLE, earlierWindow.anchorRecord)
        awaitStyle(chosen)
        val selected = requireNotNull(model.state.value.reader).location.position
        compose.onNodeWithTag("reader-mode-toggle").assertIsEnabled().performClick()
        assertEquals(selected, awaitReader(ReadingMode.VERSE).document?.bookResource?.anchorRecord)
        awaitStyle(chosen, selected) // A paged column's first source record need not be MIDDLE.
        compose.onNodeWithTag("reader-back").performClick()
        compose.onNodeWithTag("bookmark-open-${bookmark.id}").assertIsDisplayed().performClick()
        assertEquals(MIDDLE, awaitReader(ReadingMode.VERSE).document?.bookResource?.anchorRecord)
        awaitStyle(chosen)

        compose.onNodeWithTag("reader-search").performClick()
        awaitCatalog()
        compose.onNodeWithTag("search-query").performTextReplacement("violet")
        compose.onNodeWithTag("search-query").performImeAction()
        awaitSearch("violet", LAST)
        compose.onNodeWithTag("search-result-$LAST").performClick()
        val later = assertResource(awaitReader(ReadingMode.VERSE), LAST)
        assertEquals(LAST, later.anchorRecord)
        // The whole chapter is one resource, so a later verse reuses the same section at a new anchor.
        assertEquals(earlierWindow.section.node, later.section.node)
        assertTrue(later.anchorRecord > earlierWindow.anchorRecord)
        awaitStyle(chosen, LAST)
        assertPreferences(chosen)
        assertEquals(listOf(bookmark), durableBookmarks())
        compose.onNodeWithTag("reader-back").performClick()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        compose.onNodeWithTag("search-back").performClick()
        compose.onNodeWithTag("bookmark-open-${bookmark.id}").assertIsDisplayed()
        assertFalse(model.state.value.searchVisible)
        assertNull(model.state.value.reader)
        assertHomeAppearance()
    }

    @Test
    fun vmSettersClampPreserveOtherFieldsAndDocumentsAndReloadFromAnIsolatedOwnerRestart() {
        startModel(initial = ReaderPreferences(24, ReaderPaper.SEPIA, 117))
        assertPreferences(ReaderPreferences(24, ReaderPaper.SEPIA, 117))
        openSyntheticHit("amber", MIDDLE)
        val document = requireNotNull(model.state.value.reader?.document)
        val resource = requireNotNull(document.bookResource)
        compose.runOnIdle { model.setReaderPaper(ReaderPaper.DARK) }
        assertPreferences(ReaderPreferences(24, ReaderPaper.DARK, 117))
        compose.runOnIdle { model.setReaderFontSize(Int.MAX_VALUE) }
        assertPreferences(ReaderPreferences(30, ReaderPaper.DARK, 117))
        compose.runOnIdle { model.setReaderLineSpacing(Int.MIN_VALUE) }
        assertPreferences(ReaderPreferences(30, ReaderPaper.DARK, 85))
        compose.runOnIdle { model.setReaderFontSize(Int.MIN_VALUE); model.setReaderLineSpacing(Int.MAX_VALUE) }
        val expected = ReaderPreferences(14, ReaderPaper.DARK, 150)
        assertPreferences(expected)
        assertSame(document, model.state.value.reader?.document)
        assertSame(resource, model.state.value.reader?.document?.bookResource)
        assertEquals(MIDDLE, model.state.value.reader?.location?.position)
        compose.runOnIdle { model.toggleReadingMode() }
        assertResource(awaitReader(ReadingMode.BOOK), MIDDLE)
        assertPreferences(expected)
        clearModel() // Cancel/join the old owner before constructing the next one; no shared VM IO.
        createModel()
        assertPreferences(expected)
        assertNull(model.state.value.reader)
        openSyntheticHit("violet", LAST)
        assertResource(awaitReader(ReadingMode.BOOK), LAST)
        assertPreferences(expected)
        assertEquals(ReadingMode.BOOK, LibraryStore(application).readingMode)
    }

    private fun initialStyle(mode: ReadingMode) {
        startModel(mode)
        val chosen = ReaderPreferences(23, ReaderPaper.DARK, 135)
        setStyle(chosen) // Before the first reader request AND before any native view exists.
        assertNull(model.state.value.reader)
        assertNull(compose.runOnIdle { readerHost() })
        val reading = openSyntheticHit("amber", MIDDLE)
        assertResource(reading, MIDDLE)
        val document = requireNotNull(reading.document)
        assertEquals("VM HTML is style-independent; live settings belong to the native host",
            FolioText.document(document.section.paragraphs, "en", ReaderPreferences(),
                bookMode = true, scrollingBook = mode == ReadingMode.VERSE, highlight = setOf("amber")), document.html)
        mountRoute()
        awaitStyle(chosen)
        val rendered = readDom(compose.runOnIdle { requireNotNull(readerHost()?.settledView) }, DomQuery.SOURCE)
        assertEquals("Native DOM must contain the original synthetic Unicode paragraph",
            FolioText.plain(sourceText(MIDDLE)).trim(), rendered.getString("text").trim())
        assertReaderBars(chosen.paper)
        openSettings()
        assertPanel(chosen)
        closeSettings()
        assertSame(document, model.state.value.reader?.document)
        leaveSeededReader()
        assertPreferences(chosen)
    }

    private fun settingsRoundTrip(mode: ReadingMode) {
        startModel(mode)
        openSyntheticHit("amber", MIDDLE)
        mountRoute()
        awaitStyle(ReaderPreferences())
        if (mode == ReadingMode.VERSE) seekInsideParagraph() else repeat(2) {
            val page = compose.runOnIdle { requireNotNull(readerHost()?.settledView?.bookPageInfo).page }
            compose.onNodeWithTag("reader-page-next").assertIsEnabled().performClick()
            await { compose.runOnIdle { readerHost()?.settledView?.bookPageInfo?.page == page + 1 } }
        }
        val before = awaitStyle(ReaderPreferences())
        if (mode == ReadingMode.VERSE) assertEquals(.37, before.getDouble("fraction"), .02)
        val retained = retain()
        var chosen = ReaderPreferences()

        fun verify() {
            assertPreferences(chosen)
            assertPanel(chosen)
            closeSettings()
            val after = awaitStyle(chosen)
            if (mode == ReadingMode.VERSE) {
                assertEquals("Reflow preserves progress INSIDE the long source paragraph",
                    before.getDouble("fraction"), after.getDouble("fraction"), .03)
            }
            assertRetained(retained)
            assertReaderBars(chosen.paper)
            openSettings()
        }

        openSettings()
        assertPanel(chosen)
        reveal("reader-text-size-increase").performClick()
        chosen = chosen.copy(fontSize = 21)
        verify()
        reveal("reader-text-size-decrease").performClick()
        chosen = chosen.copy(fontSize = 20)
        verify()
        for (paper in listOf(ReaderPaper.SEPIA, ReaderPaper.DARK)) {
            reveal("reader-paper-${paper.name}").performClick()
            chosen = chosen.copy(paper = paper)
            verify()
        }
        reveal("reader-line-spacing-increase").performClick()
        chosen = chosen.copy(lineSpacing = 105)
        verify()
        reveal("reader-line-spacing-decrease").performClick()
        chosen = chosen.copy(lineSpacing = 100)
        verify()
        reveal("reader-text-size-increase").performClick()
        reveal("reader-line-spacing-decrease").performClick()
        chosen = chosen.copy(fontSize = 21, lineSpacing = 95)
        verify()
        reveal("reader-text-size-reset").performClick()
        chosen = chosen.copy(fontSize = ReaderPreferences.DEFAULT_TEXT_SIZE)
        verify() // Reset size must not reset spacing or paper.
        reveal("reader-line-spacing-default").performClick()
        chosen = chosen.copy(lineSpacing = 100)
        verify() // Default spacing must retain DARK paper.
        reveal("reader-paper-LIGHT").performClick()
        chosen = chosen.copy(paper = ReaderPaper.LIGHT)
        verify()
        reveal("reader-text-size-reset").assertIsNotEnabled()
        reveal("reader-line-spacing-default").assertIsNotEnabled()
        closeSettings()
        // Disposing the real route while fullscreen must also restore the pre-reader bar policy.
        compose.onNodeWithTag("reader-fullscreen").assertIsEnabled().performClick()
        compose.runOnIdle { model.closeReader() }
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        assertHomeAppearance()
        compose.onNodeWithTag("search-back").performClick()
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        assertPreferences(chosen)
    }

    private fun startModel(mode: ReadingMode = ReadingMode.VERSE, initial: ReaderPreferences = ReaderPreferences()) {
        store.readingMode = mode
        store.savePreferences(initial)
        check(preferences.edit().commit())
        createModel()
    }

    private fun createModel() {
        compose.runOnIdle {
            model = LibraryViewModel(application)
            models.put("reading-settings", model)
            modelJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
        }
        await { !model.state.value.loading && model.state.value.browser.libraryId == library.id }
        assertNull(model.state.value.error)
        assertEquals(listOf(library), model.state.value.libraries)
        awaitBookmarks(0)
    }

    private fun clearModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
        modelJob?.let { job -> runBlocking { withTimeout(IO_TIMEOUT) { job.join() } } }
        modelJob = null
    }

    private fun setStyle(value: ReaderPreferences) {
        compose.runOnIdle {
            model.setReaderFontSize(value.fontSize)
            model.setReaderPaper(value.paper)
            model.setReaderLineSpacing(value.lineSpacing)
        }
        assertPreferences(value)
    }

    private fun assertPreferences(expected: ReaderPreferences) {
        val state = model.state.value
        assertEquals(expected, ReaderPreferences(state.readerFontSize, state.readerPaper, state.readerLineSpacing))
        check(preferences.edit().commit()) // Fence apply() without sleeping or touching another prefs file.
        assertEquals(expected, LibraryStore(application).readerPreferences())
        assertEquals(unrelated, unrelatedPreferences())
    }

    private fun mountRoute() {
        check(!contentInstalled)
        mounted.value = true
        compose.setContent {
            VedaPreviewTheme {
                val colors = MaterialTheme.colorScheme
                val scale = LocalDensity.current.fontScale
                SideEffect { appColors = colors.surface.toArgb() to colors.onSurface.toArgb(); systemFontScale = scale }
                if (mounted.value) LibraryRoute(model)
            }
        }
        contentInstalled = true
    }

    private fun openSettings() {
        compose.onNodeWithTag("reader-text-size").assertIsDisplayed().assertIsEnabled()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.reader_settings)).performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertIsDisplayed()
        compose.onNodeWithTag("reader-text-size-dialog").assertDoesNotExist()
    }

    private fun closeSettings() {
        compose.onNodeWithTag("reader-text-size-done").performClick()
        compose.onNodeWithTag("reader-settings-dialog").assertDoesNotExist()
    }

    private fun reveal(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun assertPanel(value: ReaderPreferences) {
        reveal("reader-text-size-value").assertTextEquals(
            compose.activity.getString(R.string.reader_text_size_percent, value.fontSize * 5))
        reveal("reader-text-size-slider")
        val range = compose.onNodeWithTag("reader-text-size-slider").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(value.fontSize.toFloat(), range.current, 0f)
        for (paper in ReaderPaper.entries) {
            val row = reveal("reader-paper-${paper.name}")
            if (paper == value.paper) row.assertIsSelected() else row.assertIsNotSelected()
        }
        reveal("reader-line-spacing-value").assertTextEquals(
            compose.activity.getString(R.string.reader_line_spacing_percent, value.lineSpacing))
    }

    private fun openSyntheticHit(query: String, record: Int): ReaderState {
        compose.runOnIdle { model.openSearch() }
        awaitCatalog()
        compose.runOnIdle { model.setSearchQuery(query); model.submitSearch() }
        awaitSearch(query, record)
        val hit = model.state.value.search.results.single()
        compose.runOnIdle { model.openSearchHit(hit) }
        return awaitReader().also { assertEquals(record, it.document?.bookResource?.anchorRecord) }
    }

    private fun awaitCatalog() {
        await { model.state.value.search.let { !it.catalogLoading && (it.catalogError || it.books.isNotEmpty()) } }
        assertFalse(model.state.value.search.catalogError)
        assertFalse(model.state.value.search.resolvingContext)
    }

    private fun awaitSearch(query: String, record: Int) {
        await { model.state.value.search.let { !it.searching && (it.error != null || it.submittedQuery == query) } }
        assertNull("Synthetic indexed search must succeed", model.state.value.search.error)
        assertEquals(listOf(record), model.state.value.search.results.map { it.location.position })
    }

    private fun awaitReader(mode: ReadingMode? = null): ReaderState {
        await { model.state.value.reader?.loading == false }
        return requireNotNull(model.state.value.reader).also {
            assertFalse("Synthetic outline/window must validate", it.failed)
            assertTrue(it.document != null)
            if (mode != null) assertEquals(mode, it.mode)
        }
    }

    private fun assertResource(reading: ReaderState, desiredRecord: Int): BookResource {
        val document = requireNotNull(reading.document)
        val resource = requireNotNull(document.bookResource)
        val node = resource.section.node
        assertTrue(desiredRecord in node.record..node.end)
        assertEquals((node.record..node.end).toList(), document.section.paragraphs.map { it.record })
        assertEquals(sourceText(desiredRecord), document.section.paragraphs.single { it.record == desiredRecord }.text)
        val entries = resource.position.outline.entries.subList(resource.firstEntry, resource.lastEntry + 1)
        assertTrue("VM must load the complete containing chapter as one resource",
            entries.count { it.kind == ReadingUnitKind.VERSE } == verses.size)
        assertEquals(chapter.record..chapter.end, node.record..node.end)
        return resource
    }

    private fun awaitBookmarks(count: Int): List<Bookmark> {
        await { model.state.value.bookmarks.let { !it.loading && !it.busy } }
        return model.state.value.bookmarks.let {
            assertNull(it.error)
            assertEquals(count, it.items.size)
            it.items
        }
    }

    private fun durableBookmarks(): List<Bookmark> = runBlocking {
        withTimeout(IO_TIMEOUT) { withContext(Dispatchers.IO) { BookmarkStore(application).load() } }
    }

    private fun leaveSeededReader() {
        compose.onNodeWithTag("reader-back").performClick()
        compose.onNodeWithTag("search-screen").assertIsDisplayed()
        assertHomeAppearance()
        compose.onNodeWithTag("search-back").performClick()
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        assertNull(model.state.value.reader)
        assertNull(compose.runOnIdle { readerHost() })
        assertHomeAppearance()
    }

    /** Poll the actual computed CSS and native/DOM geometry, not merely a pref or textZoom flag.
     * Single-flight reads; 200 ms quiet after the requested values and settled VM anchor agree.
     * Only the deliberately deep scrolling paragraph gets an exact-anchor/fraction assertion.
     */
    private fun awaitStyle(expected: ReaderPreferences, record: Int = MIDDLE): JSONObject {
        awaitReader()
        val palette = ReaderPaperAppearance.palette(expected.paper)
        var result: JSONObject? = null
        var failure: Throwable? = null
        var inFlight = false
        var previous = ""
        var since = SystemClock.uptimeMillis()
        var diagnostic = "No settled native reader"
        try {
            compose.waitUntil(NATIVE_TIMEOUT) {
                compose.runOnIdle {
                    failure?.let { throw AssertionError("Read-only style query failed", it) }
                    val reading = requireNotNull(model.state.value.reader)
                    assertFalse(reading.failed)
                    val view = readerHost()?.settledView
                    if (result == null && !inFlight && view != null && !view.isReleased) {
                        inFlight = true
                        view.evaluateJavascript(DomQuery.STYLE.script(record)) receive@{ raw ->
                            inFlight = false
                            if (view.isReleased) return@receive
                            try {
                                val css = JSONObject(raw)
                                val zoom = (systemFontScale * 100 * (expected.fontSize / 20f)).roundToInt().coerceIn(80, 300)
                                val scale = view.nativeContentScale.toDouble()
                                val anchor = view.settledReadingRecord
                                val geometry = if (view.paginatedBook) {
                                    val page = view.bookPageInfo
                                    page != null && view.scrollX == page.nativeOffset && view.scrollY == 0 &&
                                        abs(css.getDouble("x") * scale - view.scrollX) < 2
                                } else view.scrollX == 0 && abs(css.getDouble("y") * scale - view.scrollY) < 2
                                val current = requireNotNull(model.state.value.reader)
                                val valid = readerHost()?.settledView === view && !view.isReaderStylePending &&
                                    view.progress == 100 && view.title == "Reader" && view.contentHeight > 0 &&
                                    anchor != null && anchor == current.location.position &&
                                    anchor in current.location.start..current.location.end &&
                                    css.getBoolean("visible") && css.getString("fonts") == "loaded" &&
                                    css.getInt("styleCount") == 1 && css.getInt("scripts") == 0 &&
                                    css.getString("paper") == rgb(palette.Paper) && css.getString("ink") == rgb(palette.Ink) &&
                                    abs(css.getDouble("factor") - expected.lineSpacing / 100.0) < .0001 &&
                                    abs(css.getDouble("font") - 20.0 * zoom / 100) < .15 &&
                                    abs(css.getDouble("nativeLineCSS") - css.getDouble("font") * 1.3 * expected.lineSpacing / 100) < .3 &&
                                    view.settings.textZoom == zoom && scale.isFinite() && scale > 0 && geometry
                                val key = "$raw/${view.scrollX}/${view.scrollY}/$anchor"
                                diagnostic = "zoom=${view.settings.textZoom} anchor=$anchor vm=${current.location.position} css=$raw"
                                val now = SystemClock.uptimeMillis()
                                if (!valid || key != previous) { previous = key; since = now }
                                if (valid && now - since >= QUIET_MILLIS) result = css
                            } catch (error: Throwable) { failure = error }
                        }
                    }
                    result != null
                }
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Native style did not settle within $NATIVE_TIMEOUT ms: $diagnostic", error)
        }
        compose.onNodeWithTag("reader-document").assertIsDisplayed()
        compose.runOnIdle {
            val host = requireNotNull(readerHost())
            val view = requireNotNull(host.settledView)
            assertEquals(1, host.childCount)
            assertEquals(Color.parseColor(palette.Paper), (host.background as ColorDrawable).color)
            assertEquals(model.state.value.reader?.mode == ReadingMode.BOOK, view.paginatedBook)
            assertEquals(!view.paginatedBook, view.scrollingBook)
            assertTrue(view.settings.javaScriptEnabled) // Enabled by production book layouts, not tests.
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.domStorageEnabled)
        }
        return requireNotNull(result)
    }

    private fun seekInsideParagraph() {
        val view = compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
        val css = readDom(view, DomQuery.STYLE)
        compose.runOnIdle {
            assertTrue(view.scrollingBook)
            val offset = ((css.getDouble("top") + css.getDouble("height") * .37) * view.nativeContentScale).roundToInt()
            assertTrue("Exercise an interior paragraph, not a boundary", offset in 1 until view.scrollRange)
            view.scrollTo(0, offset) // Native scrolling; DOM probes never write or scroll the document.
        }
    }

    private fun retain(): Retained {
        val view = compose.runOnIdle { requireNotNull(readerHost()?.settledView) }
        val body = readDom(view, DomQuery.SOURCE).getString("body")
        return compose.runOnIdle {
            val document = requireNotNull(model.state.value.reader?.document)
            val renders = AtomicInteger(0)
            val previous = view.onRendered
            view.onRendered = { if (!view.isReleased) renders.incrementAndGet(); previous() }
            Retained(requireNotNull(readerHost()), view, document, requireNotNull(document.bookResource),
                requireNotNull(view.url), body, renders)
        }
    }

    private fun assertRetained(before: Retained) {
        compose.runOnIdle {
            assertSame(before.host, readerHost())
            assertSame(before.view, before.host.settledView)
            assertSame(before.document, model.state.value.reader?.document)
            assertSame(before.resource, model.state.value.reader?.document?.bookResource)
            assertEquals(before.url, before.view.url)
            assertEquals("Style/zoom is not another onRendered/load", 0, before.extraRenders.get())
            val location = requireNotNull(model.state.value.reader).location
            assertEquals(before.resource.section.node.record, location.start)
            assertEquals(before.resource.section.node.end, location.end)
            assertTrue(location.position in location.start..location.end)
        }
        assertEquals("Only the app-owned head style may change; source records stay intact",
            before.body, readDom(before.view, DomQuery.SOURCE).getString("body"))
        // Observable no-reload guards, not an invented SQL query counter or private-field hook.
    }

    private fun readDom(view: OfflineReaderView, query: DomQuery): JSONObject {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle {
            assertFalse(view.isReleased)
            view.evaluateJavascript(query.script(MIDDLE)) { result.set(it) }
        }
        await { result.get() != null }
        return JSONObject(requireNotNull(result.get()))
    }

    private enum class DomQuery {
        STYLE, SOURCE;

        fun script(record: Int): String {
            require(record in 10..200)
            if (this == SOURCE) return "(function(){return {body:document.querySelector('main.book-flow').innerHTML," +
                "text:document.getElementById('record-$record').textContent};})()"
            return """(function(){
                var e=document.getElementById('record-$record'),b=e&&e.querySelector('.folio-block'),
                    s=b&&getComputedStyle(b),body=getComputedStyle(document.body),v=visualViewport,
                    x=v?v.offsetLeft:0,y=v?v.offsetTop:0,w=v?v.width:innerWidth,h=v?v.height:innerHeight,
                    top=v?v.pageTop:scrollY,r=e&&e.getBoundingClientRect(),absolute=r?r.top+scrollY:0;
                return {paper:body.backgroundColor,ink:body.color,font:s?parseFloat(s.fontSize):0,
                    nativeLineCSS:s?parseFloat(s.lineHeight):0,
                    factor:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--reader-line-factor')),
                    fonts:document.fonts.status,scripts:document.scripts.length,
                    styleCount:document.querySelectorAll('style#reader-live-style').length,
                    x:v?v.pageLeft:scrollX,y:top,top:absolute,height:r?r.height:0,
                    fraction:r&&r.height?Math.max(0,Math.min(1,(top-absolute)/r.height)):0,
                    visible:!!e&&Array.from(e.getClientRects()).some(function(rect){return rect.width>0&&rect.height>0&&
                        rect.right-x>.5&&rect.left-x<w-.5&&rect.bottom-y>.5&&rect.top-y<h-.5;})};
                })()""".trimIndent()
        }
    }

    private fun assertReaderBars(paper: ReaderPaper) = compose.runOnIdle {
        val palette = ReaderPaperAppearance.palette(paper)
        val actual = bars()
        val enforcedEdgeToEdge = Build.VERSION.SDK_INT >= 35 && compose.activity.applicationInfo.targetSdkVersion >= 35
        assertEquals(if (enforcedEdgeToEdge) Color.TRANSPARENT else Color.parseColor(palette.Paper), actual.status)
        assertEquals(if (enforcedEdgeToEdge) Color.TRANSPARENT else Color.parseColor(palette.Paper), actual.navigation)
        assertEquals(!palette.dark, actual.lightStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) assertEquals(!palette.dark, actual.lightNavigation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(false, actual.statusContrast)
            assertEquals(false, actual.navigationContrast)
        }
    }

    private fun assertHomeAppearance() {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Leaving the reader restores the original window, not the last paper", homeBars, bars())
            assertEquals(ReadingColors.Paper.toArgb() to ReadingColors.Ink.toArgb(), appColors)
        }
    }

    @Suppress("DEPRECATION") // Exact appearance state owned by ReaderPaperTheme; no device setting writes.
    private fun bars(): Bars {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        return Bars(window.statusBarColor, window.navigationBarColor,
            controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced else null,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else null)
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(IO_TIMEOUT, condition)
    private fun readerHost(): ReaderPageHost? = findHost(compose.activity.window.decorView)
    private fun findHost(view: View): ReaderPageHost? {
        if (view is ReaderPageHost) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findHost(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun registerFixture(): LibraryInfo {
        val id = UUID.randomUUID().toString()
        val directory = File(application.noBackupFilesDir, "libraries").also { check(it.isDirectory || it.mkdirs()) }
        source = File(directory, "$id.ivd")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()) }
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE contents(record INTEGER PRIMARY KEY,parent INTEGER,title TEXT,next_sibling INTEGER," +
                    "node_children INTEGER,level INTEGER,node_code TEXT,simpletitle TEXT)")
                db.execSQL("CREATE TABLE texts(recid INTEGER PRIMARY KEY,plain TEXT,stylename TEXT,levelname TEXT)")
                db.execSQL("CREATE TABLE levels(id INTEGER PRIMARY KEY,level TEXT)")
                db.execSQL("CREATE TABLE words(word TEXT,indexbase INTEGER,data BLOB,idx TEXT)")
                db.execSQL("CREATE TABLE docinfo(name TEXT,valuex TEXT)")
                db.execSQL("CREATE TABLE objects(objectName TEXT,objectType TEXT,objectData BLOB)")
                db.execSQL("CREATE INDEX contents_parent ON contents(parent,record)")
                db.execSQL("CREATE INDEX contents_level ON contents(level)")
                db.execSQL("CREATE INDEX contents_simple ON contents(simpletitle)")
                db.execSQL("CREATE INDEX words_word ON words(word)")
                for ((level, name) in levels) db.execSQL("INSERT INTO levels VALUES (?,?)", arrayOf(level, name))
                for (node in headings) db.execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(node.record, node.parent, node.title, node.end, node.children, node.level, node.code, node.title.lowercase()))
                for (record in 10..200) {
                    val heading = headings.firstOrNull { it.record == record }
                    val style = when (heading?.level) {
                        40 -> "PA_Verse_Number_Heading"
                        50 -> "PA_Titles"
                        else -> "PA_ParaText"
                    }
                    db.execSQL("INSERT INTO texts VALUES (?,?,?,?)", arrayOf(record, sourceText(record), style,
                        heading?.let { levels.getValue(it.level) }.orEmpty()))
                }
                for ((word, record) in listOf("amber" to MIDDLE, "violet" to LAST)) {
                    val posting = ByteArray(6)
                    for (byte in 0..3) posting[byte] = (record ushr (8 * byte)).toByte()
                    posting[4] = 1
                    db.execSQL("INSERT INTO words VALUES (?,?,?,?)", arrayOf(word, 0, posting, ""))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(source.setReadOnly())
        sourceSize = source.length()
        sourceHash = digest(source)
        return LibraryInfo(id, "Settings synthetic library", "en", source.name, sourceSize, 191, "Synthetic only")
            .also(store::addLibrary)
    }

    private fun unrelatedPreferences() = preferences.all.filterKeys {
        it !in setOf("active", "readingMode", "textSize", "paper", "lineFactor", "lastReading", "highlightColor") &&
            !it.startsWith("searchHistory:") && !it.startsWith("study:")
    }.toMap()

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun rgb(hex: String): String = Color.parseColor(hex).let { "rgb(${Color.red(it)}, ${Color.green(it)}, ${Color.blue(it)})" }

    private data class Retained(val host: ReaderPageHost, val view: OfflineReaderView, val document: ReaderDocument,
        val resource: BookResource, val url: String, val body: String, val extraRenders: AtomicInteger)
    private data class Bars(val status: Int, val navigation: Int, val lightStatus: Boolean,
        val lightNavigation: Boolean, val statusContrast: Boolean?, val navigationContrast: Boolean?)

    private class IsolatedApplication(private val target: Context, private val root: File) : Application() {
        private val prefix = "reading-settings-${root.name}-"
        private val ownedPreferences = mutableSetOf<String>()
        init { attachBaseContext(target) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getFilesDir(): File = directory("files")
        override fun getCacheDir(): File = directory("cache")
        @Synchronized
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix$name"
            ownedPreferences += isolated
            return target.getSharedPreferences(isolated, mode)
        }
        @Synchronized
        fun deleteOwnedPreferences() {
            for (name in ownedPreferences) { check(name.startsWith(prefix)); check(target.deleteSharedPreferences(name)) }
        }
        private fun directory(name: String) = File(root, name).also { check(it.isDirectory || it.mkdirs()) }
    }

    companion object {
        private const val IO_TIMEOUT = 10_000L
        private const val NATIVE_TIMEOUT = 20_000L
        private const val QUIET_MILLIS = 200L
        private const val MIDDLE = 116
        private const val LAST = 196
        private val collection = ContentNode(10, 0, "Synthetic collection", 200, 1, 10)
        private val work = ContentNode(20, 10, "Synthetic settings work", 200, 1, 20)
        private val chapter = ContentNode(30, 20, "Synthetic chapter", 199, 16, 30)
        private val verses = (0 until 16).map { index ->
            ContentNode(40 + index * 10, 30, "BG 1.${index + 1}", 49 + index * 10, 1, 40)
        }
        private val headings = listOf(collection, work, chapter) + verses.flatMap {
            listOf(it, ContentNode(it.record + 1, it.record, "PURPORT", it.end, 0, 50))
        }
        private val levels = mapOf(10 to "LE_Collection", 20 to "LE_Book", 30 to "LE_Chapter",
            40 to "LE_Verse_Number", 50 to "LE_Verse_Section")
        private val middleText = "Amber synthetic paragraph — Kṛṣṇa कृष्ण नमूना a\u0304. " +
            "Continuous synthetic source text retains its position while reader settings change. ".repeat(180)
        private fun sourceText(record: Int): String = when (record) {
            MIDDLE -> middleText
            LAST -> "Violet synthetic paragraph in the later reader window — नमूना বাংলা."
            else -> headings.firstOrNull { it.record == record }?.title ?: "Synthetic source row $record."
        }
    }
}