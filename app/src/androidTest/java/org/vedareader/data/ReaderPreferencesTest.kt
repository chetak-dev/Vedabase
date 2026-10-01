package org.vedareader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vedareader.reader.ReadingMode
import java.util.UUID

/** Store-only checks. UUID-owned preferences; no Activity, repository, corpus or real reader prefs.
 * A synchronous empty commit fences apply() before each new owner reads. Recreating a store/context
 * tests persisted owner restart, not Android process death or clearing its SharedPreferences cache.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPreferencesTest {
    private lateinit var context: FixtureContext
    private lateinit var preferences: SharedPreferences
    private lateinit var unrelated: Map<String, *>

    @Before
    fun setUp() {
        context = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext,
            "reader-preferences-test-${UUID.randomUUID()}-reader")
        preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        check(preferences.edit().putString("fixture-owner", "synthetic-reader-preferences")
            .putString("libraries", "[]").putString("active", "synthetic-library")
            .putString("saved", "[]").putString("position:synthetic-library", "unrelated-location-sentinel")
            .putString("searchHistory:synthetic-library", "[\"synthetic history\"]").commit())
        unrelated = preferences.all.toMap()
    }

    @After
    fun tearDown() {
        try {
            if (::unrelated.isInitialized) assertUnrelatedPreserved()
        } finally {
            if (::context.isInitialized) context.deleteOwnedPreferences()
        }
    }

    @Test
    fun defaultAndLegacyTwoArgumentConstructorsAppendOneHundredPercentSpacing() {
        assertEquals(ReaderPreferences(20, ReaderPaper.LIGHT, 100), ReaderPreferences())
        for (paper in ReaderPaper.entries) {
            val legacy = ReaderPreferences(24, paper)
            assertEquals(24, legacy.fontSize)
            assertEquals(paper, legacy.paper)
            assertEquals(100, legacy.lineSpacing)
            assertEquals(ReaderPreferences(25, paper, 100), legacy.copy(fontSize = 25))
        }
        assertEquals(unrelated, preferences.all) // Constructors have no storage side effects.
    }

    @Test
    fun absentAndLegacyStoredFieldsUseDefaultsWithoutWritingOnRead() {
        assertEquals(ReaderPreferences(ReaderPreferences.DEFAULT_TEXT_SIZE, ReaderPaper.LIGHT, 100), LibraryStore(context).readerPreferences())
        assertEquals(unrelated, preferences.all)
        check(preferences.edit().putInt("textSize", 26).putString("paper", "SEPIA").commit())
        val before = preferences.all.toMap()
        assertEquals(ReaderPreferences(26, ReaderPaper.SEPIA, 100), LibraryStore(context).readerPreferences())
        assertEquals("Reading old settings must not migrate or rewrite unrelated metadata", before, preferences.all)
    }

    @Test
    fun eachPaperFontAndSpacingIncludingNonStepValuesSurvivesStoreRecreation() {
        for (paper in ReaderPaper.entries) for (size in listOf(14, 16, 20, 30)) {
            for (spacing in listOf(85, 100, 123, 150)) {
                val expected = ReaderPreferences(size, paper, spacing)
                LibraryStore(context).savePreferences(expected)
                assertEquals(expected, restartedStore().readerPreferences())
                assertEquals(size, preferences.getInt("textSize", -1))
                assertEquals(paper.name, preferences.getString("paper", null))
                assertEquals("Do not round non-step persisted spacing", spacing, preferences.getInt("lineFactor", -1))
                assertUnrelatedPreserved()
            }
        }
    }

    @Test
    fun saveClampsBothNumericFieldsAtTheirInclusiveBounds() {
        val sizes = listOf(Int.MIN_VALUE to 14, 13 to 14, 14 to 14, 30 to 30, 31 to 30, Int.MAX_VALUE to 30)
        val spacings = listOf(Int.MIN_VALUE to 85, 84 to 85, 85 to 85, 150 to 150, 151 to 150, Int.MAX_VALUE to 150)
        for ((inputSize, size) in sizes) for ((inputSpacing, spacing) in spacings) {
            LibraryStore(context).savePreferences(ReaderPreferences(inputSize, ReaderPaper.DARK, inputSpacing))
            assertEquals(ReaderPreferences(size, ReaderPaper.DARK, spacing), restartedStore().readerPreferences())
            assertEquals(size, preferences.getInt("textSize", -1))
            assertEquals(spacing, preferences.getInt("lineFactor", -1))
        }
    }

    @Test
    fun externallyStoredNumericBoundsAreClampedOnReadWithoutRepairWrites() {
        for ((size, spacing) in listOf(Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to Int.MIN_VALUE,
            14 to 85, 30 to 150, 23 to 117)) {
            check(preferences.edit().putInt("textSize", size).putString("paper", "SEPIA")
                .putInt("lineFactor", spacing).commit())
            val before = preferences.all.toMap()
            assertEquals(ReaderPreferences(size.coerceIn(14, 30), ReaderPaper.SEPIA, spacing.coerceIn(85, 150)),
                restartedStore().readerPreferences())
            assertEquals(before, preferences.all)
        }
    }

    @Test
    fun unknownPaperFallsBackToLightAndPreservesOtherFieldsAndRawValue() {
        for (paper in listOf("FUTURE_PAPER", "dark", "", "SEPIA ")) {
            check(preferences.edit().putInt("textSize", 27).putInt("lineFactor", 119)
                .putString("paper", paper).commit())
            val before = preferences.all.toMap()
            assertEquals(ReaderPreferences(27, ReaderPaper.LIGHT, 119), restartedStore().readerPreferences())
            assertEquals(before, preferences.all)
        }
    }

    @Test
    fun modeAndIndividualPreferenceCopiesPreserveAllOtherSettingsAcrossRestarts() {
        var expected = ReaderPreferences(24, ReaderPaper.DARK, 127)
        LibraryStore(context).savePreferences(expected)
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE, ReadingMode.BOOK)) {
            val store = restartedStore()
            val beforeMode = preferences.all.toMap()
            store.readingMode = mode
            assertEquals(expected, restartedStore().readerPreferences())
            assertEquals(beforeMode + ("readingMode" to mode.name), preferences.all)

            val edits = listOf<(ReaderPreferences) -> ReaderPreferences>(
                { it.copy(fontSize = if (it.fontSize == 24) 22 else 24) },
                { it.copy(paper = if (it.paper == ReaderPaper.DARK) ReaderPaper.SEPIA else ReaderPaper.DARK) },
                { it.copy(lineSpacing = if (it.lineSpacing == 127) 113 else 127) },
            )
            for (edit in edits) {
                expected = edit(expected)
                store.savePreferences(edit(store.readerPreferences()))
                val restarted = restartedStore()
                assertEquals(expected, restarted.readerPreferences())
                assertEquals(mode, restarted.readingMode)
                assertUnrelatedPreserved()
            }
        }
        LibraryStore(context).savePreferences(ReaderPreferences(ReaderPreferences.DEFAULT_TEXT_SIZE, ReaderPaper.LIGHT, 100))
        assertEquals(ReaderPreferences(ReaderPreferences.DEFAULT_TEXT_SIZE, ReaderPaper.LIGHT, 100), restartedStore().readerPreferences())
        assertEquals(ReadingMode.BOOK, restartedStore().readingMode)
    }

    private fun restartedStore(): LibraryStore {
        assertTrue("Fence prior apply() writes to this isolated preferences file", preferences.edit().commit())
        return LibraryStore(context.newOwner())
    }

    private fun assertUnrelatedPreserved() {
        assertEquals(unrelated, preferences.all.filterKeys {
            it !in setOf("textSize", "paper", "lineFactor", "readingMode")
        })
    }

    private class FixtureContext(target: Context, private val ownedName: String) : ContextWrapper(target) {
        init { check(ownedName.startsWith("reader-preferences-test-") && ownedName != "reader") }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "reader" && mode == Context.MODE_PRIVATE)
            return baseContext.getSharedPreferences(ownedName, mode)
        }
        fun newOwner() = FixtureContext(baseContext, ownedName)
        fun deleteOwnedPreferences() { check(baseContext.deleteSharedPreferences(ownedName)) }
    }
}