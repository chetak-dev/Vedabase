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

/** UUID-owned preferences only. Never read, snapshot, clear or write the real reader registry. */
@RunWith(AndroidJUnit4::class)
class ReadingModePreferenceTest {
    private lateinit var fixtureContext: FixtureContext
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        fixtureContext = FixtureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        preferences = fixtureContext.getSharedPreferences("reader", Context.MODE_PRIVATE)
        assertTrue(preferences.all.isEmpty())
        check(preferences.edit().putString("fixture-owner", "synthetic-mode-test").commit())
    }

    @After
    fun tearDown() {
        if (::fixtureContext.isInitialized) fixtureContext.deleteOwnedPreferences()
    }

    @Test
    fun missingPreferenceDefaultsToVerseWithoutWritingAnything() {
        val before = preferences.all.toMap()
        assertEquals(ReadingMode.VERSE, LibraryStore(fixtureContext).readingMode)
        assertEquals(before, preferences.all)
    }

    @Test
    fun selectedBookAndVerseModesReloadThroughNewStoreInstancesWithoutChangingOtherKeys() {
        val before = preferences.all.toMap()
        for (mode in listOf(ReadingMode.BOOK, ReadingMode.VERSE, ReadingMode.BOOK)) {
            LibraryStore(fixtureContext).readingMode = mode
            // A commit on this isolated file fences preceding apply() writes, without sleeps.
            assertTrue(preferences.edit().commit())
            assertEquals(mode.name, preferences.getString("readingMode", null))
            assertEquals(mode, LibraryStore(fixtureContext).readingMode)
            assertEquals(before + ("readingMode" to mode.name), preferences.all)
        }
    }

    @Test
    fun unknownStoredModeFallsBackToVerseWithoutRewritingThePreference() {
        for (value in listOf("FUTURE_READING_MODE", "book", "")) {
            check(preferences.edit().putString("readingMode", value).commit())
            val before = preferences.all.toMap()
            assertEquals(ReadingMode.VERSE, LibraryStore(fixtureContext).readingMode)
            assertEquals(before, preferences.all)
        }
    }

    private class FixtureContext(target: Context) : ContextWrapper(target) {
        private val ownedName = "reading-mode-preference-test-${UUID.randomUUID()}-reader"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "reader" && mode == Context.MODE_PRIVATE)
            return baseContext.getSharedPreferences(ownedName, mode)
        }
        fun deleteOwnedPreferences() {
            check(ownedName.startsWith("reading-mode-preference-test-") && ownedName != "reader")
            check(baseContext.deleteSharedPreferences(ownedName))
        }
    }
}