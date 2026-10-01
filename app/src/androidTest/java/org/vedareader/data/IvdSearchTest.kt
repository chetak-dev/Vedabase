package org.vedareader.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only newly-created synthetic databases are opened. No importer, registry, or user files. */
@RunWith(AndroidJUnit4::class)
class IvdSearchTest {
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var db: SQLiteDatabase
    private lateinit var engine: IvdSearch
    private lateinit var originalHash: ByteArray
    private lateinit var originalSchema: List<String>

    @Before
    fun prepareDirectory() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        directory = File(cache, "search-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        source = File(directory, "fixture.ivd")
    }

    @After
    fun verifyReadOnlyAndCleanUp() {
        try {
            if (::engine.isInitialized) engine.close()
            if (::db.isInitialized) {
                assertEquals(originalSchema, schema(db))
                db.rawQuery("SELECT total_changes()", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0L, it.getLong(0))
                }
                db.close()
            }
            if (::originalHash.isInitialized) assertArrayEquals(originalHash, digest(source))
        } finally {
            if (::db.isInitialized && db.isOpen) db.close()
            if (::directory.isInitialized) check(directory.deleteRecursively())
        }
    }

    @Test
    fun catalogIncludesRootsAndNestedIndependentWorksButNotChaptersAndKeepsOriginalEnds() = runBlocking {
        fixture()
        val books = engine.searchBooks()
        assertEquals(listOf(10, 20, 200, 10000), books.map { it.node.record })
        assertEquals("Outer library", books.single { it.node.record == 20 }.parentTitle)
        assertEquals(2_000_000, books.last().node.end)
        assertTrue(books.filter { it.node.parent == 0 }.all { it.parentTitle.isEmpty() })
        assertFalse(books.any { it.node.record == 30 || it.node.record == 150 })
    }

    @Test
    fun scopeIsAppliedBeforePageLimitRatherThanFilteringFirstThirtyGlobalHits() = runBlocking {
        fixture {
            for (record in 80..139) row(record, "light")
            for (record in 201..208) row(record, "light")
            index("light", (80..139).toList() + (201..208).toList())
        }
        val page = engine.searchPage(SearchRequest("light", setOf(200)))
        assertEquals((201..208).toList(), page.hits.map { it.location.position })
        assertNull(page.nextAfter)
        assertTrue(page.hits.all { it.bookTitle == "Other work" })
    }

    @Test
    fun overlappingRangesAndDuplicatePostingsAreDeduplicatedBeforePagination() = runBlocking {
        fixture {
            for (record in 80..139) row(record, "light")
            index("light", (80..139).flatMap { listOf(it, it, it) })
        }
        val request = SearchRequest("light", setOf(10, 20))
        val first = engine.searchPage(request)
        assertEquals((80..109).toList(), first.hits.map { it.location.position })
        assertEquals(109, first.nextAfter)
        val second = engine.searchPage(request, requireNotNull(first.nextAfter))
        assertEquals((110..139).toList(), second.hits.map { it.location.position })
        assertNull(second.nextAfter)
        assertEquals(60, (first.hits + second.hits).map { it.location.position }.toSet().size)
    }

    @Test
    fun emptyScopeIncludesRealRecordsOutsideEveryRootAndRejectsNonCatalogSelections() = runBlocking {
        fixture {
            row(2, "outside")
            row(500, "outside")
            row(201, "outside")
            index("outside", listOf(2, 201, 500))
        }
        val all = engine.searchPage(SearchRequest("outside"))
        assertEquals(listOf(2, 201, 500), all.hits.map { it.location.position })
        for (record in listOf(2, 500)) {
            val hit = all.hits.single { it.location.position == record }
            assertEquals(record, hit.location.start)
            assertEquals(record, hit.location.end)
            assertEquals("", hit.bookTitle)
        }
        assertEquals(listOf(201), engine.searchPage(SearchRequest("outside", setOf(200))).hits.map { it.location.position })
        for (invalid in setOf(30, 40, 999)) {
            rejects<IllegalArgumentException> { engine.searchPage(SearchRequest("outside", setOf(invalid))) }
            rejects<IllegalArgumentException> { engine.searchSuggestions(SearchRequest("out", setOf(invalid))) }
        }
    }

    @Test
    fun boundedPhraseVerificationReturnsAScannedCursorEvenForAnEmptyPage() = runBlocking {
        fixture {
            for (record in 10001..11100) row(record, "blue quiet sky")
            row(11101, "blue sky")
            index("blue", (10001..11101).toList())
            index("sky", (10001..11101).toList())
        }
        val request = SearchRequest("blue sky", setOf(10000), SearchMode.EXACT_PHRASE)
        val first = engine.searchPage(request)
        assertTrue(first.hits.isEmpty())
        assertEquals(11000, first.nextAfter)
        val later = engine.searchPage(request, requireNotNull(first.nextAfter))
        assertEquals(listOf(11101), later.hits.map { it.location.position })
        assertNull(later.nextAfter)
    }

    @Test
    fun allWordsShareAParagraphAndPhraseModeHonorsOrderAndRepeatedWords() = runBlocking {
        fixture {
            row(80, "go now go")
            row(81, "go go")
            row(82, "go")
            row(83, "sky")
            index("go", listOf(80, 81, 82))
            index("sky", listOf(83))
        }
        assertTrue(engine.searchPage(SearchRequest("go sky")).hits.isEmpty())
        assertEquals(listOf(80, 81, 82), engine.searchPage(SearchRequest("go go")).hits.map { it.location.position })
        assertEquals(listOf(81), engine.searchPage(SearchRequest("go go", mode = SearchMode.EXACT_PHRASE))
            .hits.map { it.location.position })
        // No "now" posting means even matching plaintext cannot bypass the candidate index.
        assertTrue(engine.searchPage(SearchRequest("now go", mode = SearchMode.EXACT_PHRASE)).hits.isEmpty())
    }

    @Test
    fun exactDottedReferencesAndCombinedTitleMembersWorkWithoutBodyPostings() = runBlocking {
        fixture()
        for ((query, record) in listOf("BG 2.8" to 40, "SB 1.2.3" to 210, "CC Ādi 1.2" to 230,
            "BG 1.16" to 250, "BG 1.17" to 250, "BG 1.18" to 250)) {
            val page = engine.searchPage(SearchRequest(query, mode = SearchMode.EXACT_PHRASE))
            assertEquals(query, listOf(record), page.hits.map { it.location.position })
            assertTrue(page.hits.single().highlights.isEmpty())
            assertTrue(page.hits.single().snippet.contains("Original heading body"))
        }
        assertTrue(engine.searchPage(SearchRequest("BG 1.1")).hits.isEmpty())
        assertTrue(engine.searchPage(SearchRequest("BG 28")).hits.isEmpty())
        assertTrue(engine.searchPage(SearchRequest("BG 2.8", setOf(200))).hits.isEmpty())
    }

    @Test
    fun referenceTargetsUseCorpusCcSpellingAndNeverReadNumericOrCommonWordPostings() = runBlocking {
        fixture {
            node(240, 200, 244, 40, "Madhya 1.1", "madhya 1.1")
            node(245, 200, 249, 40, "Antya 1.1", "antya 1.1")
            node(270, 200, 279, 40, "Ādi 1.1", "adi 1.1")
            row(80, "BG 2.8 SB 1.2.3 CC Madhya 1.1 quoted in prose")
            for (term in listOf("bg", "sb", "cc", "madhya", "adi", "antya", "1", "2", "3", "8")) {
                // If even posting metadata is consulted these malformed five-byte rows fail the read.
                rawIndex(term, ByteArray(5))
            }
        }
        for (target in listOf(SearchTarget.VERSE_REFERENCE, SearchTarget.ALL_TEXT)) {
            for ((query, record) in listOf("BG2.8" to 40, "SB1.2.3" to 210, "CCMadhya1.1" to 240,
                "Madhya1.1" to 240, "CC Adi1.2" to 230, "Adi1.2" to 230, "Antya1.1" to 245, "Adi1.1" to 270)) {
                val hits = engine.searchPage(SearchRequest(query, target = target)).hits
                assertEquals(query, listOf(record), hits.map { it.location.position })
                val metrics = metrics("reference-$target")
                assertEquals(0, metrics.postingMetadataQueries)
                assertEquals(0L, metrics.postingBytes)
                assertEquals(if (target == SearchTarget.VERSE_REFERENCE) 0 else 1, metrics.plainTextReads)
            }
        }
    }

    @Test
    fun referenceOnlyRejectsIncompletePlainAndQuotedInputsAndIgnoresNonVerseMetadata() = runBlocking {
        fixture {
            node(240, 200, 244, 30, "Madhya 1.1", "madhya 1.1")
            row(80, "Madhya 1.1")
            index("madhya", listOf(80))
            index("1", listOf(80))
        }
        for (invalid in listOf("BG2.", "SB1.2", "CCMadhya", "Madhya1.", "light", "\"BG 2.8\"", "quoted BG 2.8")) {
            rejects<IllegalArgumentException> { engine.searchPage(SearchRequest(invalid, target = SearchTarget.VERSE_REFERENCE)) }
        }
        assertTrue(engine.searchPage(SearchRequest("CCMadhya1.1", target = SearchTarget.VERSE_REFERENCE)).hits.isEmpty())
        assertEquals(0L, metrics("reference-no-metadata-match").postingBytes)
        assertTrue(engine.searchPage(SearchRequest("BG2.8", target = SearchTarget.VERSE_WORDS)).hits.isEmpty())
        for (target in SearchTarget.entries) {
            rejects<IllegalArgumentException> {
                engine.searchSuggestions(SearchRequest("b", setOf(999), target = target))
            }
        }
    }

    @Test
    fun referenceSuggestionsPreserveCcPrefixAndOriginalLabelWithoutPostingsOrPlaintextReads() = runBlocking {
        fixture {
            node(240, 200, 249, 40, "Madhya 1.1", "madhya 1.1")
            rawIndex("madhya", ByteArray(5))
            rawIndex("1", ByteArray(5))
        }
        engine.searchBooks() // Report the completion timing separately from cold catalog loading.
        for (target in listOf(SearchTarget.VERSE_REFERENCE, SearchTarget.ALL_TEXT)) {
            for ((prefix, expected) in listOf("CCMadhya1." to "cc madhya 1.1", "Madhya1." to "madhya 1.1")) {
                val request = SearchRequest(prefix, setOf(200), target = target)
                assertEquals(listOf(SearchSuggestion(expected, "Madhya 1.1", true)), engine.searchSuggestions(request))
                val stats = metrics("reference-completion-$target")
                assertEquals(0, stats.postingMetadataQueries)
                assertEquals(0L, stats.postingBytes)
                assertEquals(0, stats.plainTextReads)
                assertTrue(stats.referenceRows in 1..256)
                assertFalse(stats.suggestionCacheHit)
                assertEquals(listOf(expected), engine.searchSuggestions(request.copy(mode = SearchMode.EXACT_PHRASE)).map { it.query })
                assertTrue(metrics("reference-completion-cached").suggestionCacheHit)
                assertTrue(engine.searchSuggestions(request.copy(bookRecords = setOf(20))).isEmpty())
            }
        }
    }

    @Test
    fun lateReferenceCompletionAndCombinedCcRangeAreNotLostBehindThePrefixLimit() = runBlocking {
        fixture {
            for (number in 1..399) node(11000 + number, 10000, 11000 + number, 40,
                "Madhya 77.$number", "madhya 77.$number")
            node(12000, 10000, 12009, 40, "Madhya 1.16–18", "madhya 1.16-18")
        }
        engine.searchBooks()
        val late = SearchRequest("CCMadhya77.399", setOf(10000), target = SearchTarget.VERSE_REFERENCE)
        assertEquals(listOf("cc madhya 77.399"), engine.searchSuggestions(late).map { it.query })
        assertEquals(listOf(11399), engine.searchPage(late).hits.map { it.location.position })
        for (query in listOf("CCMadhya1.17", "Madhya1.18", "CC Madhya 1.16-18")) {
            val request = late.copy(query = query)
            assertEquals(listOf(12000), engine.searchPage(request).hits.map { it.location.position })
            assertTrue(engine.searchSuggestions(request).any { it.query == SearchText.titleKey(query) })
            val stats = metrics("late-combined-reference")
            assertEquals(0L, stats.postingBytes)
            assertEquals(0, stats.plainTextReads)
        }
    }

    @Test
    fun verseWordsExcludeTranslationSynonymsAndStyledPurportQuotesAndKeepMainSourceScripts() = runBlocking {
        fixture {
            row(41, "needle main transliteration", "PA_Verse", "LE_Verse_Text")
            row(42, "needle Bengali roman", "PA_Bengro")
            row(43, "needle कृष्ण", "PA_DevaNagariQ")
            row(44, "needle uvaca", "PA_Uvaca_Line")
            row(46, "needle translation quotes BG 2.8", "PA_Translation")
            row(47, "needle quoted verse in translation", "PA_Verse", "LE_Verse_Text")
            row(49, "needle synonyms", "PA_Synonyms")
            node(50, 40, 59, 50, "PURPORT")
            row(51, "needle purport", "PA_First")
            row(52, "needle quote with verse level", "PA_Verse", "LE_Verse_Text")
            row(53, "needle Bengali quote", "PA_Bengro")
            row(54, "needle Devanagari quote", "PA_DevaNagariQ")
            row(55, "needle explicit quote", "PA_Verse_in_purp")
            row(56, "needle semantic conflict", "PA_Verse", "LE_Purport")
            row(71, "needle next main verse", "PA_Verse")
            row(80, "needle verse style outside a verse", "PA_Verse")
            index("needle", listOf(41, 42, 43, 44, 46, 47, 49, 51, 52, 53, 54, 55, 56, 71, 80))
        }
        val request = SearchRequest("needle", setOf(20))
        assertEquals(15, engine.searchPage(request).hits.size)
        val verses = engine.searchPage(request.copy(target = SearchTarget.VERSE_WORDS))
        assertEquals(listOf(41, 42, 43, 44, 71), verses.hits.map { it.location.position })
        assertEquals(5, metrics("verse-main-only").plainTextReads)
        assertEquals(15, engine.searchPage(request).hits.size) // Target-separated candidate cache.
        val completion = request.copy(query = "nee", target = SearchTarget.VERSE_WORDS)
        assertEquals(listOf("needle"), engine.searchSuggestions(completion).map { it.query })
        assertTrue(metrics("verse-completion").plainTextReads <= 5)
        assertTrue(engine.searchSuggestions(completion.copy(bookRecords = setOf(200))).isEmpty())
    }

    @Test
    fun verseContextUsesSourceStylesEvenWhenThereIsNoPurportContentsHeading() = runBlocking {
        fixture {
            row(211, "needle opening", "PA_Bengro", "LE_Verse_Text")
            row(212, "prose starts here", "PA_First")
            row(213, "needle quoted verse", "PA_Verse", "LE_Verse_Text")
            row(214, "needle quote", "PA_DevaNagariQ")
            index("needle", listOf(211, 213, 214))
        }
        assertEquals(listOf(211), engine.searchPage(SearchRequest("needle", target = SearchTarget.VERSE_WORDS))
            .hits.map { it.location.position })
        assertEquals(1, metrics("style-only-purport-boundary").plainTextReads)
    }

    @Test
    fun targetFilterPrecedesVerificationLimitAndSuggestionBudgetUsingBatchMetadataNotBodies() = runBlocking {
        fixture {
            for (record in 10001..11100) row(record, "needle prose", "PA_Purport")
            node(12000, 10000, 12099, 40, "Madhya 3.1", "madhya 3.1")
            for (record in 12001..12034) row(record, "needle main", "PA_Bengro", "LE_Verse_Text")
            index("needle", (10001..11100).toList() + (12001..12034).toList())
        }
        val request = SearchRequest("needle", setOf(10000), target = SearchTarget.VERSE_WORDS)
        val first = engine.searchPage(request)
        assertEquals((12001..12030).toList(), first.hits.map { it.location.position })
        assertEquals(12030, first.nextAfter)
        val stats = metrics("1100-nonverse-postings-before-main-verse")
        assertEquals(30, stats.plainTextReads)
        assertEquals(10, stats.verseMetadataQueries) // Nine style batches plus one main-region read.
        val next = engine.searchPage(request, requireNotNull(first.nextAfter))
        assertEquals((12031..12034).toList(), next.hits.map { it.location.position })
        assertNull(next.nextAfter)
        val continuation = metrics("1100-nonverse-cached-continuation")
        assertEquals(4, continuation.plainTextReads)
        assertEquals(2, continuation.verseMetadataQueries)
        assertEquals(0L, continuation.postingBytes)
        assertEquals(listOf("needle"), engine.searchSuggestions(request.copy(query = "nee")).map { it.query })
        val completion = metrics("1100-nonverse-completion")
        assertEquals(1, completion.plainTextReads)
        assertEquals(10, completion.verseMetadataQueries)
        assertEquals(6804L, completion.postingBytes)
    }

    @Test
    fun globalVersePagesBoundMetadataAndKeepSparseRawCursorsWithoutSkippingPrefetchedHits() = runBlocking {
        val leading = List(1200) { 10001 + it * 3 }
        val opening = List(60) { 20001 + it * 2 }
        val trailing = List(10000) { 30000 + it * 3 }
        fixture {
            for (record in leading + trailing) row(record, "needle prose", "PA_Purport")
            node(20000, 10000, 20200, 40, "Madhya 3.1", "madhya 3.1")
            for (record in opening) row(record, "needle main", "PA_Bengro", "LE_Verse_Text")
            node(70000, 10000, 70003, 40, "Madhya 3.2", "madhya 3.2")
            row(70001, "needle last main", "PA_Verse", "LE_Verse_Text")
            index("needle", leading + opening + trailing + listOf(70001))
        }
        val request = SearchRequest("needle", target = SearchTarget.VERSE_WORDS)
        val first = engine.searchPage(request)
        assertEquals(opening.take(30), first.hits.map { it.location.position })
        assertEquals(opening[29], first.nextAfter)
        val firstStats = metrics("global-11261-raw-candidates-first-thirty")
        assertEquals(30, firstStats.plainTextReads)
        assertEquals(11, firstStats.verseMetadataQueries) // Ten batches, one verse; not the whole posting.
        assertEquals(1, firstStats.postingMetadataQueries)

        assertEquals(first, engine.searchPage(request)) // Re-reading cannot mutate the raw cached snapshot.
        val repeated = metrics("global-verse-repeat-raw-cache")
        assertEquals(0L, repeated.postingBytes)
        assertEquals(0, repeated.postingMetadataQueries)
        assertEquals(11, repeated.verseMetadataQueries)

        val second = engine.searchPage(request, requireNotNull(first.nextAfter))
        assertEquals(opening.drop(30), second.hits.map { it.location.position })
        assertEquals(opening.last(), second.nextAfter) // Not the end of either metadata prefetch.
        val secondStats = metrics("global-verse-unprocessed-batch-continuation")
        assertEquals(30, secondStats.plainTextReads)
        assertEquals(2, secondStats.verseMetadataQueries)
        assertEquals(0L, secondStats.postingBytes)

        val emptyOne = engine.searchPage(request, requireNotNull(second.nextAfter))
        assertTrue(emptyOne.hits.isEmpty())
        assertEquals(trailing[4095], emptyOne.nextAfter) // Count candidates, not numeric record distance.
        val emptyOneStats = metrics("global-verse-first-raw-scan-cap")
        assertEquals(0, emptyOneStats.plainTextReads)
        assertEquals(32, emptyOneStats.verseMetadataQueries)
        assertEquals(0L, emptyOneStats.postingBytes)

        val emptyTwo = engine.searchPage(request, requireNotNull(emptyOne.nextAfter))
        assertTrue(emptyTwo.hits.isEmpty())
        assertEquals(trailing[8191], emptyTwo.nextAfter)
        val emptyTwoStats = metrics("global-verse-second-raw-scan-cap")
        assertEquals(0, emptyTwoStats.plainTextReads)
        assertEquals(32, emptyTwoStats.verseMetadataQueries)

        val last = engine.searchPage(request, requireNotNull(emptyTwo.nextAfter))
        assertEquals(listOf(70001), last.hits.map { it.location.position })
        assertNull(last.nextAfter)
        val lastStats = metrics("global-verse-after-two-empty-pages")
        assertEquals(1, lastStats.plainTextReads)
        assertEquals(16, lastStats.verseMetadataQueries)
        assertEquals(opening + listOf(70001), (first.hits + second.hits + last.hits).map { it.location.position })
    }

    @Test
    fun versePhraseVerificationBudgetExcludesNonverseAndResumesInsideThePrefetchedBatch() = runBlocking {
        val prose = (10001..11100).toList()
        val verses = List(1101) { 20001 + it * 2 }
        fixture {
            for (record in prose) row(record, "blue sky prose", "PA_Purport")
            node(20000, 10000, 24000, 40, "Madhya 3.1", "madhya 3.1")
            for (record in verses) row(record, if (record == verses.last()) "blue sky" else "blue quiet sky",
                "PA_Verse", "LE_Verse_Text")
            index("blue", prose + verses)
            index("sky", prose + verses)
        }
        val request = SearchRequest("blue sky", mode = SearchMode.EXACT_PHRASE, target = SearchTarget.VERSE_WORDS)
        val first = engine.searchPage(request)
        assertTrue(first.hits.isEmpty())
        assertEquals(verses[999], first.nextAfter)
        val firstStats = metrics("verse-phrase-text-budget-after-nonverse")
        assertEquals(1000, firstStats.plainTextReads)
        assertEquals(26, firstStats.verseMetadataQueries) // 17 candidate batches + nine main-region reads.
        val next = engine.searchPage(request, requireNotNull(first.nextAfter))
        assertEquals(listOf(verses.last()), next.hits.map { it.location.position })
        assertNull(next.nextAfter)
        val nextStats = metrics("verse-phrase-resume-after-verification-cap")
        assertEquals(101, nextStats.plainTextReads)
        assertEquals(10, nextStats.verseMetadataQueries)
        assertEquals(0L, nextStats.postingBytes)
    }

    @Test
    fun verseSuggestionsStopClassifyingAfterFirstMatchInsteadOfTheWholePostingChunk() = runBlocking {
        fixture {
            node(20000, 10000, 20004, 40, "Madhya 3.1", "madhya 3.1")
            row(20001, "needle main", "PA_Verse", "LE_Verse_Text")
            for (record in 30000..49999) row(record, "needle prose", "PA_Purport")
            index("needle", listOf(20001) + (30000..49999).toList())
        }
        engine.searchBooks()
        val request = SearchRequest("nee", target = SearchTarget.VERSE_WORDS)
        assertEquals(listOf("needle"), engine.searchSuggestions(request).map { it.query })
        val stats = metrics("verse-completion-first-of-16384-postings-in-chunk")
        assertEquals(1, stats.plainTextReads)
        assertEquals(2, stats.verseMetadataQueries) // One 128-record style batch and one main-region read.
        assertEquals(98304L, stats.postingBytes) // Still validate the entire fetched chunk.
        assertEquals(1, stats.postingMetadataQueries)
        assertEquals(listOf("needle"), engine.searchSuggestions(request).map { it.query })
        val cached = metrics("verse-completion-small-batch-cache")
        assertTrue(cached.suggestionCacheHit)
        assertEquals(0, cached.verseMetadataQueries)
        assertEquals(0, cached.plainTextReads)
    }

    @Test
    fun referenceAndVerseTargetsNeverFetchUnrelatedOversizedBodies() = runBlocking {
        fixture {
            execSQL("UPDATE texts SET plain=? WHERE recid=40", arrayOf("x".repeat(2_200_000)))
            row(41, "needle main", "PA_Verse", "LE_Verse_Text")
            row(46, "needle " + "x".repeat(2_200_000), "PA_Translation")
            index("needle", listOf(41, 46))
        }
        assertEquals(listOf(40), engine.searchPage(SearchRequest("BG2.8", target = SearchTarget.VERSE_REFERENCE))
            .hits.map { it.location.position })
        assertEquals(0, metrics("reference-no-body-fetch").plainTextReads)
        assertEquals(listOf(41), engine.searchPage(SearchRequest("needle", target = SearchTarget.VERSE_WORDS))
            .hits.map { it.location.position })
        assertEquals(1, metrics("verse-no-translation-fetch").plainTextReads)
        rejects<IllegalStateException> { engine.searchPage(SearchRequest("needle")) }
    }

    @Test
    fun wordSuggestionsVerifyOnlyBoundedKeyedBodiesAndDoNotScanAllTextForAbsence() = runBlocking {
        fixture {
            for (record in 10001..11100) row(record, "unrelated source body")
            index("needle", (10001..11100).toList())
        }
        engine.searchBooks()
        assertTrue(engine.searchSuggestions(SearchRequest("nee")).isEmpty())
        val stats = metrics("best-effort-word-suggestion-budget")
        assertEquals(64, stats.plainTextReads)
        assertEquals(6600L, stats.postingBytes)
        assertEquals(1, stats.postingMetadataQueries)
        assertTrue(engine.searchSuggestions(SearchRequest("absent-prefix")).isEmpty())
        assertEquals(0, metrics("absent-prefix-no-text-scan").plainTextReads)
    }

    @Test
    fun suggestionCacheSeparatesTargetsScopesAndMultiwordModeButSharesSingleWordMode() = runBlocking {
        fixture {
            row(41, "krsna pure", "PA_Verse", "LE_Verse_Text")
            row(201, "krsnadasa pure", "PA_Translation")
            index("krsna", listOf(41))
            index("krsnadasa", listOf(201))
        }
        val all = SearchRequest("kr")
        val initial = engine.searchSuggestions(all)
        assertEquals(listOf("krsna", "krsnadasa"), initial.map { it.query })
        (initial as MutableList<SearchSuggestion>).clear() // Returned list must not own the cached snapshot.
        assertEquals(listOf("krsna", "krsnadasa"), engine.searchSuggestions(all).map { it.query })
        engine.searchSuggestions(all.copy(mode = SearchMode.EXACT_PHRASE))
        assertTrue(metrics("single-word-mode-cache").suggestionCacheHit)
        val verse = all.copy(target = SearchTarget.VERSE_WORDS)
        assertEquals(listOf("krsna"), engine.searchSuggestions(verse).map { it.query })
        assertFalse(metrics("target-isolated-suggestions").suggestionCacheHit)
        assertTrue(engine.searchSuggestions(verse.copy(bookRecords = setOf(200))).isEmpty())
        val multi = verse.copy(query = "pure kr")
        assertEquals(listOf("pure krsna"), engine.searchSuggestions(multi).map { it.query })
        assertTrue(engine.searchSuggestions(multi.copy(mode = SearchMode.EXACT_PHRASE)).isEmpty())
        assertFalse(metrics("multiword-mode-isolated").suggestionCacheHit)
        assertEquals(listOf("pure krsna"), engine.searchSuggestions(multi).map { it.query })
        assertTrue(metrics("multiword-cache").suggestionCacheHit)
        repeat(25) { engine.searchSuggestions(SearchRequest("unused$it")) }
        engine.searchSuggestions(all)
        assertFalse(metrics("bounded-lru-eviction").suggestionCacheHit)
    }

    @Test
    fun termMetadataIsOneQueryAndMissingRareTermAvoidsDecodingCommonPostingBlob() = runBlocking {
        fixture {
            row(80, "common rare")
            index("common", List(100000) { 80 })
            index("rare", listOf(80))
        }
        assertTrue(engine.searchPage(SearchRequest("common absent rare")).hits.isEmpty())
        val missing = metrics("absent-term-before-common-blob")
        assertEquals(1, missing.postingMetadataQueries)
        assertEquals(0L, missing.postingBytes)
        assertEquals(0, missing.plainTextReads)
        assertEquals(listOf(80), engine.searchPage(SearchRequest("common rare")).hits.map { it.location.position })
        val present = metrics("common-and-rare")
        assertEquals(1, present.postingMetadataQueries)
        assertEquals(600006L, present.postingBytes) // No unproven sorted-posting early exit loses matches.
        assertEquals(1, present.plainTextReads)
    }

    @Test
    fun boundedPrefixProjectionUsesWordIndexWithoutDistinctOrGroupTempTree() = runBlocking {
        fixture {
            repeat(100) { index("lamp", listOf(40)) }
            rawIndex("l".repeat(513), ByteArray(0))
        }
        val plan = db.rawQuery("EXPLAIN QUERY PLAN SELECT substr(word,1,513) FROM words " +
            "WHERE word>=? AND word<? AND idx IN ('','Devanagari') GROUP BY word ORDER BY word LIMIT 96",
            arrayOf("la", "lb")).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            } }
        assertTrue(plan.any { it.contains("iwords", ignoreCase = true) })
        assertFalse(plan.any { it.contains("TEMP B-TREE", ignoreCase = true) })
        rejects<IllegalStateException> { engine.searchSuggestions(SearchRequest("ll")) }
    }

    @Test
    fun hitLocationUsesContainingSemanticVerseAndNarrowestWorkWithoutMovingTheMatchedRecord() = runBlocking {
        fixture {
            row(46, "Kṛṣṇa keeps the original Unicode.")
            index("krsna", listOf(46))
        }
        val hit = engine.searchPage(SearchRequest("krsna")).hits.single()
        assertEquals("synthetic", hit.location.libraryId)
        assertEquals(40, hit.location.start)
        assertEquals(59, hit.location.end)
        assertEquals(46, hit.location.position)
        assertEquals("BG 2.8", hit.location.title)
        assertEquals("Inner work", hit.bookTitle)
        assertEquals("Kṛṣṇa", hit.highlights.single().let { hit.snippet.substring(it.start, it.endExclusive) })
    }

    @Test
    fun nearestFinishedVerseIsNotInventedAsContainingHeadingAndSentinelEndIsClamped() = runBlocking {
        fixture {
            row(85, "light")
            row(10005, "light")
            index("light", listOf(85, 10005))
        }
        val hits = engine.searchPage(SearchRequest("light")).hits
        assertEquals(30, hits.first().location.start)
        assertEquals(99, hits.first().location.end)
        assertEquals(85, hits.first().location.position)
        assertEquals(10000, hits.last().location.start)
        assertEquals(10005, hits.last().location.end)
        assertEquals(2_000_000, engine.searchBooks().last().node.end)
    }

    @Test
    fun suggestionsVerifyScopeAndPreserveTypedPrecedingPhrase() = runBlocking {
        fixture {
            row(80, "pure Kṛṣṇa")
            row(201, "pure krsnadasa")
            row(202, "other krsnaka")
            index("krsna", listOf(80))
            index("krsnadasa", listOf(201))
            index("krsnaka", listOf(202))
            index("krsnarare", listOf(80), category = "Note")
        }
        val request = SearchRequest("pure kr", setOf(200), SearchMode.EXACT_PHRASE)
        assertEquals(listOf("pure krsnadasa"), engine.searchSuggestions(request).map { it.query })
        assertEquals(listOf("pure krsna"), engine.searchSuggestions(request.copy(bookRecords = setOf(20))).map { it.query })
        assertTrue(engine.searchSuggestions(request.copy(query = "k")).isEmpty())
        assertTrue(engine.searchSuggestions(request.copy(query = "pure %_")).isEmpty())
    }

    @Test
    fun referenceSuggestionsPreserveDotsAndAreScopedAndLimited() = runBlocking {
        fixture()
        val gita = engine.searchSuggestions(SearchRequest("BG 2", setOf(20)))
        assertEquals(setOf("bg 2.8", "bg 2.9"), gita.map { it.query }.toSet())
        assertTrue(gita.all { it.reference })
        assertTrue(engine.searchSuggestions(SearchRequest("BG 2", setOf(200))).isEmpty())
        val combined = engine.searchSuggestions(SearchRequest("bg 1.1", setOf(200)))
        assertTrue(combined.any { it.query == "bg 1.17" && it.reference })
        assertTrue(combined.size <= 8)
        assertTrue(engine.searchSuggestions(SearchRequest("bg 1.17", setOf(200))).any { it.query == "bg 1.17" })
    }

    @Test
    fun referenceSuggestionScopeIsAppliedBeforeTheMetadataCandidateLimit() = runBlocking {
        fixture {
            node(1000, 0, 1999, 20, "Unselected reference collection")
            for (record in 1100..1399) node(record, 1000, record, 40, "BG 9.1", "bg 9.1")
            node(240, 200, 249, 40, "BG 9.1", "bg 9.1")
        }
        val suggestions = engine.searchSuggestions(SearchRequest("bg 9", setOf(200)))
        assertEquals(listOf("bg 9.1"), suggestions.map { it.query })
        assertEquals(listOf(240), engine.searchPage(SearchRequest("bg 9.1", setOf(200))).hits.map { it.location.position })
    }

    @Test
    fun completionLimitIsEightAndOnlyPlaintextCategoriesAreSearchable() = runBlocking {
        fixture {
            for (number in 0..11) {
                row(80 + number, "lamp$number")
                index("lamp$number", listOf(80 + number))
            }
            row(201, "कृष्ण")
            index("कृष्ण", listOf(201), category = "Devanagari")
            row(202, "hidden")
            index("hidden", listOf(202), category = "Popup")
        }
        assertEquals(8, engine.searchSuggestions(SearchRequest("lam", setOf(20))).size)
        assertEquals(listOf(201), engine.searchPage(SearchRequest("कृष्ण")).hits.map { it.location.position })
        assertTrue(engine.searchPage(SearchRequest("hidden")).hits.isEmpty())
        assertTrue(engine.searchSuggestions(SearchRequest("hi")).isEmpty())
    }

    @Test
    fun postingsAreChunkedAndLittleEndianBaseOffsetsAreRespected() = runBlocking {
        fixture {
            row(10005, "chunked")
            index("chunked", List(40000) { 10005 }, base = 10000)
        }
        assertEquals(listOf(10005), engine.searchPage(SearchRequest("chunked")).hits.map { it.location.position })
    }

    @Test
    fun malformedPostingLengthsAndOutOfRangeOffsetsFailExplicitly() = runBlocking {
        fixture {
            row(80, "broken overflow negative")
            rawIndex("broken", byteArrayOf(0, 0, 0, 0, 0))
            rawIndex("overflow", byteArrayOf(-1, -1, -1, -1, 0, 0))
            rawIndex("negative", byteArrayOf(0, 0, 0, 0, 0, 0), base = -1)
        }
        for (word in listOf("broken", "overflow", "negative")) {
            rejects<IllegalStateException> { engine.searchPage(SearchRequest(word)) }
            rejects<IllegalStateException> { engine.searchSuggestions(SearchRequest(word.take(3))) }
        }
    }

    @Test
    fun oversizedParagraphFailsInsteadOfReadingAPartialRowOrReturningFalsePhraseResults() = runBlocking {
        fixture {
            // Larger than a typical CursorWindow: the CASE guard must keep the body out of the cursor.
            row(80, "x".repeat(2_200_000) + " needle")
            index("needle", listOf(80))
        }
        rejects<IllegalStateException> { engine.searchPage(SearchRequest("needle")) }
    }

    @Test
    fun literalSqlMetacharactersDoNotBroadenQueriesOrChangeTheCorpus() = runBlocking {
        fixture {
            row(80, "light")
            row(201, "unrelated")
            index("light", listOf(80))
            index("unrelated", listOf(201))
        }
        assertEquals(listOf(80), engine.searchPage(SearchRequest("light%_")).hits.map { it.location.position })
        assertTrue(engine.searchPage(SearchRequest("' OR 1=1 --")).hits.isEmpty())
        rejects<IllegalArgumentException> { engine.searchPage(SearchRequest("light"), -1) }
        rejects<IllegalArgumentException> { engine.searchPage(SearchRequest("light"), 20_000_001) }
        rejects<IllegalArgumentException> { engine.searchPage(SearchRequest("light ".repeat(9))) }
        rejects<IllegalArgumentException> { engine.searchPage(SearchRequest("x".repeat(161))) }
    }

    @Test
    fun concurrentQueriesConvergeWithoutExposingMutableCachedBitsets() = runBlocking {
        fixture {
            row(80, "light")
            row(201, "light")
            index("light", listOf(80, 201))
        }
        val selected = mutableSetOf(20)
        val original = engine.searchPage(SearchRequest("light", selected))
        selected.clear()
        selected.add(200)
        assertEquals(listOf(201), engine.searchPage(SearchRequest("light", selected)).hits.map { it.location.position })
        val results = coroutineScope {
            List(6) { async { engine.searchPage(SearchRequest("light", setOf(20))) } }.awaitAll()
        }
        assertTrue(results.all { it == original })
        assertTrue(engine.searchPage(SearchRequest("light", setOf(20)), 80).hits.isEmpty())
        assertEquals(original, engine.searchPage(SearchRequest("light", setOf(20))))
    }

    @Test
    fun cancellationPropagatesAndDoesNotPoisonTheNextRead() = runBlocking {
        fixture {
            row(80, "light")
            index("light", List(300000) { 80 })
        }
        val entering = CompletableDeferred<Unit>()
        var returned = false
        val searching = launch(start = CoroutineStart.UNDISPATCHED) {
            entering.complete(Unit)
            engine.searchPage(SearchRequest("light"))
            returned = true
        }
        entering.await()
        withTimeout(5000) { searching.cancelAndJoin() }
        assertTrue(searching.isCancelled)
        assertFalse(returned)
        assertEquals(listOf(80), engine.searchPage(SearchRequest("light")).hits.map { it.location.position })
        val cancelled = async(start = CoroutineStart.LAZY) { engine.searchBooks() }
        cancelled.cancel()
        rejects<CancellationException> { cancelled.await() }
    }

    @Test
    fun linksResolveHeadingsJumpsAndPopupsPreferringTheCurrentWorkAndFallBackToSearchOnlyForFreeText() = runBlocking {
        fixture {
            execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf("Lifes Final Exam", 75))
            execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf("last", 45))
            execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf("last", 215))
            execSQL("INSERT INTO jumplinks VALUES (?,?)", arrayOf("Orphan", 5_000_000))
            execSQL("INSERT INTO popup VALUES (?,?,?)", arrayOf("InlinePopupText_1", "(null)", "<BD+>Note<BD> <QL:Query,\"[Headings Verse Number,bg 2.9]\">2.9<EL>"))
            execSQL("INSERT INTO popup VALUES (?,?,?)", arrayOf("styled", "PA_Purport", "Plain popup"))
            row(75, "Jump destination body.")
            row(215, "Other work body.")
        }
        val verse = engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, "[Headings Verse Number,bg 2.9]"), near = 45)
        assertEquals(LinkResolution.Open(ReadingLocation("synthetic", "BG 2.9", 70, 79, 70)), verse)
        assertEquals(verse, engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, " [Headings Verse Number,BG 2.9"), near = 45))
        assertEquals(LinkResolution.Open(ReadingLocation("synthetic", "Chapter", 30, 99, 30)),
            engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, "[Headings Chapter,chapter]"), near = 215))
        // Duplicate jump titles resolve inside the tapped work first; elsewhere the earliest wins.
        assertEquals(45, (engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "last"), near = 70) as LinkResolution.Open).location.position)
        assertEquals(215, (engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "last"), near = 230) as LinkResolution.Open).location.position)
        assertEquals(45, (engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "LAST"), near = 10_500) as LinkResolution.Open).location.position)
        val jump = engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "Lifes Final Exam"), near = 45) as LinkResolution.Open
        assertEquals("A body record opens its verse, anchored at the exact destination", ReadingLocation("synthetic", "BG 2.9", 70, 79, 75), jump.location)
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "Orphan"), near = 45))
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "missing"), near = 45))
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, "[Headings Verse Number,bg 9.99]"), near = 45))
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, "[Headings Unknown Level,bg 2.9]"), near = 45))
        assertEquals(LinkResolution.Search("3.14"), engine.resolveLink(ReaderLink(ReaderLinkKind.QUERY, "3.14"), near = 45))
        assertEquals(LinkResolution.Popup(ReaderPopup("InlinePopupText_1", "", "<BD+>Note<BD> <QL:Query,\"[Headings Verse Number,bg 2.9]\">2.9<EL>")),
            engine.resolveLink(ReaderLink(ReaderLinkKind.POPUP, "InlinePopupText_1"), near = 45))
        assertEquals(LinkResolution.Popup(ReaderPopup("styled", "PA_Purport", "Plain popup")),
            engine.resolveLink(ReaderLink(ReaderLinkKind.POPUP, "styled"), near = 45))
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.POPUP, "nope"), near = 45))
        assertEquals(LinkResolution.Unavailable, engine.resolveLink(ReaderLink(ReaderLinkKind.AUDIO, "Bg-01-01"), near = 45))
        rejects<IllegalArgumentException> { engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, ""), near = 45) }
        rejects<IllegalArgumentException> { engine.resolveLink(ReaderLink(ReaderLinkKind.JUMP, "x".repeat(513)), near = 45) }
    }

    @Test
    fun closeIsIdempotentAndEngineNeverClosesTheCallersDatabase() = runBlocking {
        fixture()
        engine.searchBooks()
        engine.close()
        engine.close()
        assertTrue(db.isOpen)
        rejects<IllegalStateException> { engine.searchBooks() }
        rejects<IllegalStateException> { engine.searchPage(SearchRequest("light")) }
    }

    private fun fixture(extra: SQLiteDatabase.() -> Unit = {}) {
        SQLiteDatabase.openOrCreateDatabase(source, null).use { writable ->
            writable.execSQL("CREATE TABLE levels(id INTEGER,level TEXT,original TEXT)")
            writable.execSQL("CREATE TABLE contents(record INTEGER,parent INTEGER,title TEXT,next_sibling INTEGER," +
                "node_children INTEGER,level INTEGER,node_code TEXT,simpletitle TEXT)")
            writable.execSQL("CREATE TABLE texts(recid INTEGER,plain TEXT,stylename TEXT,levelname TEXT)")
            writable.execSQL("CREATE TABLE words(word TEXT,indexbase INTEGER,data BLOB,idx TEXT)")
            writable.execSQL("CREATE TABLE jumplinks(title TEXT,recid INTEGER)")
            writable.execSQL("CREATE TABLE popup(title TEXT,class TEXT,plain TEXT)")
            writable.execSQL("CREATE INDEX iwords ON words(word)")
            writable.execSQL("CREATE INDEX icontents1 ON contents(parent)")
            writable.execSQL("CREATE INDEX icontents2 ON contents(level)")
            writable.execSQL("CREATE INDEX icontents3 ON contents(record,next_sibling)")
            writable.execSQL("CREATE INDEX icontents4 ON contents(simpletitle)")
            writable.execSQL("CREATE INDEX itexts ON texts(recid)")
            writable.beginTransaction()
            try {
                for ((number, name) in listOf(10 to "LE_Collection", 20 to "LE_Book", 30 to "LE_Chapter",
                    40 to "LE_Verse_Number", 50 to "LE_Verse_Section")) {
                    writable.execSQL("INSERT INTO levels VALUES (?,?,?)", arrayOf(number, name,
                        name.removePrefix("LE_").replace('_', ' ')))
                }
                writable.node(10, 0, 199, 10, "Outer library")
                writable.node(20, 10, 149, 20, "Inner work")
                writable.node(30, 20, 99, 30, "Chapter")
                writable.node(40, 30, 59, 40, "BG 2.8", "bg 2.8")
                writable.node(45, 40, 48, 50, "Translation")
                writable.node(70, 30, 79, 40, "BG 2.9", "bg 2.9")
                writable.node(150, 10, 189, 30, "Not independent")
                writable.node(200, 0, 299, 20, "Other work")
                writable.node(210, 200, 219, 40, "SB 1.2.3", "sb 1.2.3")
                // Actual Vedabase CC metadata omits the cc prefix; don't test an invented spelling only.
                writable.node(230, 200, 239, 40, "Ādi 1.2", "adi 1.2")
                writable.node(250, 200, 269, 40, "BG 1.16, BG 1.17, BG 1.18, BG 1.16-18",
                    "bg 1.16 bg 1.17 bg 1.18 bg 1.16-18")
                writable.node(10000, 0, 2_000_000, 10, "Final work")
                writable.extra()
                writable.setTransactionSuccessful()
            } finally { writable.endTransaction() }
        }
        originalHash = digest(source)
        db = SQLiteDatabase.openDatabase(source.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        originalSchema = schema(db)
        engine = IvdSearch(db, "synthetic")
    }

    private fun SQLiteDatabase.node(record: Int, parent: Int, end: Int, level: Int, title: String,
        simple: String = title.lowercase()) {
        execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?,?)", arrayOf(record, parent, title, end, 0, level, "", simple))
        val semantic = when (level) {
            10 -> "LE_Collection"
            20 -> "LE_Book"
            30 -> "LE_Chapter"
            40 -> "LE_Verse_Number"
            50 -> "LE_Verse_Section"
            else -> ""
        }
        row(record, "Original heading body.", level = semantic)
    }

    private fun SQLiteDatabase.row(record: Int, plain: String, style: String = "", level: String = "") {
        execSQL("INSERT INTO texts VALUES (?,?,?,?)", arrayOf(record, plain, style, level))
    }

    private fun metrics(label: String): IvdSearch.ReadMetrics = requireNotNull(engine.lastReadMetrics).also {
        // Measured on the test device when run; counters/timings here are not phone latency promises.
        Log.i("IvdSearchTest", "$label: $it")
        assertTrue(it.elapsedMillis >= 0)
    }

    private fun SQLiteDatabase.index(word: String, records: List<Int>, base: Int = 0, category: String = "") {
        val bytes = ByteArray(records.size * 6)
        for ((index, record) in records.withIndex()) {
            val relative = record - base
            for (part in 0..3) bytes[index * 6 + part] = (relative ushr (part * 8)).toByte()
            bytes[index * 6 + 4] = (index and 255).toByte()
            bytes[index * 6 + 5] = ((index ushr 8) and 255).toByte()
        }
        rawIndex(word, bytes, base, category)
    }

    private fun SQLiteDatabase.rawIndex(word: String, bytes: ByteArray, base: Int = 0, category: String = "") {
        execSQL("INSERT INTO words VALUES (?,?,?,?)", arrayOf(word, base, bytes, category))
    }

    private fun schema(database: SQLiteDatabase): List<String> = database.rawQuery(
        "SELECT type,name,coalesce(sql,'') FROM sqlite_master ORDER BY type,name", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add((0..2).joinToString("|") { cursor.getString(it) }) } }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    private suspend inline fun <reified T : Throwable> rejects(crossinline block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}