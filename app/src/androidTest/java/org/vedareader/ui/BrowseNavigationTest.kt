package org.vedareader.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.vedareader.R
import org.vedareader.data.ContentNode
import org.vedareader.data.LibraryInfo

/** Generic opt-out navigation: an in-memory tree, never MainActivity, a store, or a database. */
class BrowseNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = LibraryInfo(
        "synthetic-browse", "Synthetic navigation library", "en", "synthetic-only.ivd",
        32_768L, 8L, "Synthetic fixture only",
    )
    // The selected source is deliberately not the first root; sibling titles are not unique.
    private val letters = ContentNode(100, 0, "01. Synthetic letters", 199, 1, 1, "cl")
    private val collection = ContentNode(700, 0, "02. Synthetic collection", 799, 3, 1, "bgaiiwa")
    private val chapterA = ContentNode(710, 700, "Shared chapter", 719, 1, 2)
    private val entryA = ContentNode(711, 710, "Entry A", 711, 0, 3)
    private val chapterB = ContentNode(720, 700, "Shared chapter", 729, 1, 2)
    private val entryB = ContentNode(721, 720, "Entry B", 721, 0, 3)
    private val untitled = ContentNode(730, 700, "   ", 730, 0, 2)
    private val letter = ContentNode(101, 100, "Synthetic letter", 101, 0, 2)
    private val roots = listOf(letters, collection)
    private val tree = mapOf(
        letters.record to listOf(letter),
        collection.record to listOf(chapterA, chapterB, untitled),
        chapterA.record to listOf(entryA),
        chapterB.record to listOf(entryB),
        entryA.record to emptyList(),
        entryB.record to emptyList(),
        untitled.record to emptyList(),
        letter.record to emptyList(),
    )
    private val loads = mutableListOf<Pair<String, Int>>()
    private val frontMatter = listOf(
        ContentNode(701, collection.record, "Setting the Scene", 701, 0, 2),
        ContentNode(702, collection.record, "Dedication", 702, 0, 2),
        ContentNode(703, collection.record, "Introduction", 703, 0, 2),
    )
    private val frontMatterRecords = frontMatter.map { it.record }.toSet()
    private val leafRequests = mutableListOf<Pair<String, ContentNode>>()
    private lateinit var navigatorUnderTest: BrowseNavigator

    @Test
    fun collectionRowOpensItsMatchingRecordRatherThanTheFirstRootOrAFeaturedAction() {
        showLibrary()

        compose.onNodeWithText(label(R.string.library_featured)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.explore_collection)).assertDoesNotExist()
        scrollTo("library-list", hasTestTag("collection-${collection.record}"))
            .assertTextContains(collection.displayTitle).performClick()

        assertCurrent(collection)
        section(collection, chapterA).assertIsDisplayed()
            .assertTextEquals("1", chapterA.displayTitle) // Number and name only; no child count.
        section(collection, untitled).assertTextEquals("3", label(R.string.browse_untitled, untitled.record))
        compose.onNodeWithTag("section-${letter.record}").assertDoesNotExist()
        assertLoads(collection)
    }

    @Test
    fun genericOptOutFollowsChaptersAndBreadcrumbsToALeafWithoutAReadSectionButton() {
        showLibrary()
        openCollection()
        assertCurrent(collection)
        assertCrumb(1, collection.displayTitle, current = true)

        openSection(collection, chapterA)
        assertCurrent(chapterA)
        openSection(chapterA, entryA)
        assertCurrent(entryA)
        assertCrumb(0, label(R.string.library))
        assertCrumb(1, collection.displayTitle)
        assertCrumb(2, chapterA.displayTitle)
        assertCrumb(3, entryA.displayTitle, current = true)

        message(entryA, R.string.browse_leaf_title).assertIsDisplayed()
        message(entryA, R.string.browse_leaf_body).assertIsDisplayed()
        assertNoReadSection()
        compose.onNodeWithTag("library-list").assertDoesNotExist()
        compose.onNodeWithTag("section-${entryA.record}").assertDoesNotExist()
        message(entryA, R.string.browse_back).performClick()
        assertCurrent(chapterA)
        assertLoads(collection, chapterA, entryA, chapterA)
    }

    @Test
    fun toolbarSystemBackAndLibraryTabReturnToTheCorrectLevels() {
        showLibrary()
        compose.onNodeWithTag("category-filter-row").assertDoesNotExist()
        openCollection()
        openSection(collection, chapterA)
        openSection(chapterA, entryA)

        compose.onNodeWithTag("browse-back").performClick()
        assertCurrent(chapterA)
        // Exercise the activity's system-back route, not the navigator directly.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertCurrent(collection)

        openSection(collection, chapterA)
        // The selectable tab is distinct from the identically labelled Library breadcrumb.
        compose.onNode(hasText(label(R.string.library)) and isSelectable()).performClick()
        assertLibrary()
        compose.onNodeWithTag("library-search-entry").assertIsDisplayed()
        scrollTo("library-list", hasTestTag("collection-${letters.record}")).assertIsDisplayed()
        assertLoads(collection, chapterA, entryA, chapterA, collection, chapterA)
    }

    @Test
    fun ancestorBreadcrumbJumpsMultipleLevelsAndAllowsAnotherBranch() {
        showLibrary()
        openCollection()
        openSection(collection, chapterA)
        openSection(chapterA, entryA)

        crumb(1).performClick()
        assertCurrent(collection)
        assertCrumb(1, collection.displayTitle, current = true)
        openSection(collection, chapterB)
        assertCurrent(chapterB)
        assertCrumb(2, chapterB.displayTitle, current = true)
        section(chapterB, entryB).assertIsDisplayed()

        crumb(0).performClick()
        assertLibrary()
        assertLoads(collection, chapterA, entryA, collection, chapterB)
    }

    @Test
    fun duplicateTitlesUseRecordIdsToOpenDistinctChildren() {
        showLibrary()
        openCollection()
        section(collection, chapterA).assertTextContains("Shared chapter")
        section(collection, chapterB).assertTextContains("Shared chapter")

        openSection(collection, chapterA)
        section(chapterA, entryA).assertIsDisplayed()
        compose.onNodeWithTag("section-${entryB.record}").assertDoesNotExist()
        compose.onNodeWithTag("browse-back").performClick()
        waitFor(hasTestTag(browseList(collection)))

        openSection(collection, chapterB)
        section(chapterB, entryB).assertIsDisplayed()
        compose.onNodeWithTag("section-${entryA.record}").assertDoesNotExist()
        assertLoads(collection, chapterA, collection, chapterB)
    }

    @Test
    fun blankSourceTitleUsesLocalizedFallbackInCardHeadingAndBreadcrumb() {
        showLibrary()
        openCollection()
        val fallback = label(R.string.browse_untitled, untitled.record)

        section(collection, untitled).assertTextContains(fallback)
        openSection(collection, untitled)

        assertCurrent(untitled, fallback)
        assertCrumb(2, fallback, current = true)
        assertLoads(collection, untitled)
    }

    @Test
    fun returningFromACollectionRestoresTheLibraryScrollPosition() {
        val collections = List(60) { index ->
            val record = 2_000 + index
            ContentNode(record, 0, "Synthetic collection ${index + 1}", record, 0, 1)
        }
        val selected = collections[45]
        showLibrary(collections) { _, parent ->
            check(parent == selected.record)
            emptyList()
        }
        val card = "collection-${selected.record}"
        scrollTo("library-list", hasTestTag(card)).assertIsDisplayed()
        val before = scrollPosition("library-list")
        assertTrue("The fixture must actually scroll the library", before > 0f)

        compose.onNodeWithTag(card).performClick()
        assertCurrent(selected)
        compose.onNodeWithTag("browse-back").performClick()

        assertLibrary()
        // Do not scroll after returning: that would hide a lost saved position.
        compose.onNodeWithTag(card).assertIsDisplayed()
        assertEquals(before, scrollPosition("library-list"), 0.001f)
        assertLoads(selected)
    }

    @Test
    fun failedSectionKeepsItsPathAndRetryLoadsTheSameParent() {
        var attempts = 0
        showLibrary(loader = { _, parent ->
            if (parent == chapterA.record && ++attempts == 1) error("Synthetic load failure")
            tree.getValue(parent)
        })
        openCollection()
        openSection(collection, chapterA)

        assertCurrent(chapterA)
        assertCrumb(1, collection.displayTitle)
        assertCrumb(2, chapterA.displayTitle, current = true)
        message(chapterA, R.string.browse_error).assertIsDisplayed()
        assertNoReadSection()
        compose.onNodeWithTag("section-${entryA.record}").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.browse_leaf_body)).assertDoesNotExist()

        message(chapterA, R.string.try_again).performClick()
        section(chapterA, entryA).assertIsDisplayed()
        assertCurrent(chapterA)
        assertCrumb(2, chapterA.displayTitle, current = true)
        compose.onNodeWithText(label(R.string.browse_error)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, attempts) }
        assertLoads(collection, chapterA, chapterA)
    }

    @Test
    fun verifiedIntroductionStartsCollapsedAndExpandsOriginalOpenControlsWithoutNavigating() {
        showLibrary(
            introductionRecords = mapOf(collection.record to frontMatterRecords),
            loader = { _, parent -> if (parent == collection.record) frontMatter + chapterA else tree.getValue(parent) },
        )
        openCollection()

        introduction().assertTextEquals(label(R.string.contents_introduction))
            .assertContentDescriptionEquals(label(R.string.contents_expand_introduction))
        compose.onAllNodesWithTag("introduction-group").assertCountEquals(1)
        frontMatter.forEach { compose.onNodeWithTag("section-${it.record}").assertDoesNotExist() }
        section(collection, chapterA).assertTextContains(chapterA.displayTitle)
        introduction().performClick()

        for (node in frontMatter) {
            section(collection, node).assertIsDisplayed().assertTextContains(node.displayTitle)
                .assertContentDescriptionEquals(label(R.string.browse_open, node.displayTitle))
            compose.onAllNodesWithTag("section-${node.record}").assertCountEquals(1)
        }
        introduction().assertContentDescriptionEquals(label(R.string.contents_collapse_introduction)).performClick()
        frontMatter.forEach { compose.onNodeWithTag("section-${it.record}").assertDoesNotExist() }
        section(collection, chapterA).assertIsDisplayed()
        assertLoads(collection)
        compose.runOnIdle {
            assertEquals(listOf(collection), navigatorUnderTest.state.path)
            assertEquals(frontMatter + chapterA, navigatorUnderTest.state.entries)
        }
    }

    @Test
    fun unverifiedSingleAndUnknownIntroductionIdsLeaveOriginalRowsUngrouped() {
        val state = mutableStateOf(frontMatterState().let {
            it.copy(browser = it.browser.copy(introductionRecords = emptySet()))
        })
        compose.setContent { VedaPreviewTheme { LibraryScreen(state.value) } }
        for (ids in listOf(emptySet(), setOf(frontMatter.first().record), frontMatterRecords + 999)) {
            compose.runOnIdle { state.value = state.value.copy(browser = state.value.browser.copy(introductionRecords = ids)) }
            compose.onNodeWithTag("introduction-group").assertDoesNotExist()
            for (node in frontMatter) section(collection, node).assertIsDisplayed().assertTextContains(node.displayTitle)
            section(collection, chapterA).assertIsDisplayed()
        }
    }

    @Test
    fun introductionExpansionRestoresSavedStateAndIsIsolatedByLibraryAndCurrentRecord() {
        val original = frontMatterState()
        val state = mutableStateOf(original)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { VedaPreviewTheme { LibraryScreen(state.value) } }
        introduction().performClick()
        restoration.emulateSavedInstanceStateRestore()
        introduction().assertContentDescriptionEquals(label(R.string.contents_collapse_introduction))

        compose.runOnIdle { state.value = frontMatterState(library.copy(id = "other-library")) }
        introduction().assertContentDescriptionEquals(label(R.string.contents_expand_introduction))
        compose.onNodeWithTag("section-${frontMatter.first().record}").assertDoesNotExist()
        compose.runOnIdle { state.value = frontMatterState(current = letters) }
        scrollTo(browseList(letters), hasTestTag("introduction-group"))
            .assertContentDescriptionEquals(label(R.string.contents_expand_introduction))
        compose.runOnIdle { state.value = original }
        introduction().assertContentDescriptionEquals(label(R.string.contents_collapse_introduction))
        section(collection, frontMatter.first()).assertIsDisplayed()
    }

    @Test
    fun originalFrontMatterLeafCallbackAndBothBackRoutesRestoreExpandedParentWithoutSyntheticPath() {
        showLibrary(
            introductionRecords = mapOf(collection.record to frontMatterRecords), readLeaves = true,
            loader = { _, parent ->
                if (parent == collection.record) frontMatter + chapterA
                else if (parent in frontMatterRecords) emptyList()
                else tree.getValue(parent)
            },
        )
        openCollection()
        introduction().performClick()
        val selected = frontMatter.last()
        for ((index, systemBack) in listOf(false, true).withIndex()) {
            section(collection, selected).assertIsDisplayed()
            val position = scrollPosition(browseList(collection))
            compose.onNodeWithTag("section-${selected.record}").performClick()
            waitFor(hasTestTag("leaf-handoff"))
            compose.onNodeWithTag("leaf-handoff").assertTextEquals(selected.displayTitle)
            compose.onNodeWithTag(browseList(collection)).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(List(index + 1) { library.id to selected }, leafRequests)
                assertEquals(listOf(collection), navigatorUnderTest.state.path)
                assertEquals(frontMatter + chapterA, navigatorUnderTest.state.entries)
            }
            if (systemBack) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("leaf-back").performClick()
            waitFor(hasTestTag(browseList(collection)))
            // No scrolling after Back: verify the original row and list position really survived.
            compose.onNodeWithTag("section-${selected.record}").assertIsDisplayed()
            assertEquals(position, scrollPosition(browseList(collection)), 0.001f)
            introduction().assertContentDescriptionEquals(label(R.string.contents_collapse_introduction))
        }
        assertLoads(collection, selected, selected)
    }

    private fun frontMatterState(active: LibraryInfo = library, current: ContentNode = collection) = LibraryUiState(
        loading = false, libraries = listOf(active), active = active, collections = listOf(current),
        browser = BrowseState(
            libraryId = active.id, path = listOf(current),
            entries = (frontMatter + chapterA).map { it.copy(parent = current.record) },
            introductionRecords = frontMatterRecords,
        ),
    )

    private fun introduction() = scrollTo(browseList(collection), hasTestTag("introduction-group"))

    private fun showLibrary(
        collections: List<ContentNode> = roots,
        introductionRecords: Map<Int, Set<Int>> = emptyMap(),
        readLeaves: Boolean = false,
        loader: suspend (String, Int) -> List<ContentNode> = { _, parent -> tree.getValue(parent) },
    ) {
        compose.setContent {
            var browser by remember { mutableStateOf(BrowseState()) }
            var leaf by remember { mutableStateOf<ContentNode?>(null) }
            val scope = rememberCoroutineScope()
            val navigator = remember(scope) {
                BrowseNavigator(
                    scope = scope,
                    loadChildren = { id, parent ->
                        loads += id to parent
                        check(id == library.id)
                        loader(id, parent)
                    },
                    onChange = { browser = it },
                    // Only the front-matter handoff test opts in; generic tests retain leaf contents.
                    onReadLeaf = if (readLeaves) { id, node -> leafRequests += id to node; leaf = node } else null,
                )
            }
            DisposableEffect(navigator) {
                navigatorUnderTest = navigator
                navigator.setLibrary(library.id, collections)
                onDispose { navigator.close() }
            }
            val rootStates = rememberSaveableStateHolder()
            VedaPreviewTheme {
                val reading = leaf
                if (reading != null) {
                    // Minimal reader stand-in: tests the real navigator's direct-leaf handoff and
                    // saved contents state, not rendering (covered by the reader's own tests).
                    BackHandler { leaf = null }
                    Column {
                        Text(reading.displayTitle, Modifier.testTag("leaf-handoff"))
                        TextButton(onClick = { leaf = null }, modifier = Modifier.testTag("leaf-back")) { Text("Back") }
                    }
                } else rootStates.SaveableStateProvider("library") {
                    LibraryScreen(
                        state = LibraryUiState(
                            loading = false, libraries = listOf(library), active = library,
                            collections = collections,
                            // Supply verified metadata at the UI boundary; no semantic title guessing.
                            browser = browser.copy(introductionRecords = introductionRecords[browser.current?.record].orEmpty()),
                        ),
                        onOpenSection = navigator::open,
                        onBack = navigator::back,
                        onAncestor = navigator::goToDepth,
                        onRetrySection = navigator::retry,
                    )
                }
            }
        }
        assertLibrary()
    }

    private fun openCollection(node: ContentNode = collection) {
        scrollTo("library-list", hasTestTag("collection-${node.record}")).performClick()
        waitFor(hasTestTag(browseList(node)))
    }

    private fun openSection(parent: ContentNode, node: ContentNode) {
        section(parent, node).performClick()
        waitFor(hasTestTag(browseList(node)))
    }

    private fun assertCurrent(node: ContentNode, title: String = node.displayTitle) {
        waitFor(hasTestTag(browseList(node)))
        scrollTo(browseList(node), hasTestTag("browse-title")).assertIsDisplayed().assertTextEquals(title)
        compose.onNodeWithText(label(R.string.browse_path), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("browse-path").assertIsDisplayed()
        val browser = compose.runOnIdle { navigatorUnderTest.state }
        if (!browser.loading && !browser.failed && browser.entries.isNotEmpty()) {
            val count = browser.entries.size
            // Counts under headings were removed: the source's child counts do not match what is listed.
            compose.onNodeWithText(compose.activity.resources.getQuantityString(
                R.plurals.library_section_count, count, count)).assertDoesNotExist()
        }
        assertNoReadSection()
    }

    private fun assertLibrary() {
        waitFor(hasTestTag("library-list"))
        compose.onNodeWithTag("library-list").assertIsDisplayed()
        compose.onNodeWithTag("browse-back").assertDoesNotExist()
        compose.onNodeWithTag("browse-path").assertDoesNotExist()
        assertNoReadSection()
    }

    private fun assertNoReadSection() {
        compose.onNodeWithTag("read-section").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.read_section)).assertDoesNotExist()
    }

    private fun assertCrumb(depth: Int, title: String, current: Boolean = false) {
        val node = crumb(depth).assertIsDisplayed().assertTextEquals(title)
        if (current) node.assertIsNotEnabled() else node.assertIsEnabled()
    }

    private fun crumb(depth: Int) = scrollTo("browse-path", hasTestTag("browse-crumb-$depth"))

    private fun section(parent: ContentNode, child: ContentNode) =
        scrollTo(browseList(parent), hasTestTag("section-${child.record}"))

    private fun message(parent: ContentNode, @StringRes id: Int) =
        scrollTo(browseList(parent), hasText(label(id)))

    private fun scrollTo(list: String, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        waitFor(matcher)
        return compose.onNode(matcher)
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
    }

    private fun scrollPosition(list: String): Float =
        compose.onNodeWithTag(list).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertLoads(vararg nodes: ContentNode) {
        compose.runOnIdle { assertEquals(nodes.map { library.id to it.record }, loads.toList()) }
    }

    private fun browseList(node: ContentNode) = "browse-list-${node.record}"

    private fun label(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}