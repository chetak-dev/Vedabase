package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.BreakIterator
import java.util.Locale

class SearchTextTest {
    @Test
    fun latinAccentsFoldButIndicVowelsAndOriginalOffsetsSurvive() {
        val original = "Kṛṣṇa a\u0304 कृष्ण বাংলা"
        val tokens = SearchText.tokens(original)
        assertEquals(listOf("krsna", "a", "कृष्ण", "বাংলা"), tokens.map { it.value })
        assertEquals(listOf("Kṛṣṇa", "a\u0304", "कृष्ण", "বাংলা"), tokens.map {
            original.substring(it.start, it.endExclusive)
        })
        assertEquals("krsna", SearchText.normalize("KṚṢṆA"))
        assertFalse(SearchText.normalize("कृष्ण") == SearchText.normalize("कृषण"))
    }

    @Test
    fun phraseIsAnOrderedTokenSequenceNotAnUnorderedSetOrSubstring() {
        val query = SearchText.validate("blue sky")
        assertNotNull(SearchText.matches("A BLUE—sky above.", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("sky blue", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("blue open sky", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("blue skyward", query, SearchMode.EXACT_PHRASE))
        assertNotNull(SearchText.matches("sky, open and blue", query, SearchMode.ALL_WORDS))
    }

    @Test
    fun repeatedWordsAreRetainedForPhraseVerification() {
        val query = SearchText.validate("go go")
        assertEquals(listOf("go", "go"), query.terms)
        assertNull(SearchText.matches("go", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("go then go", query, SearchMode.EXACT_PHRASE))
        assertEquals(listOf(SearchHighlight(0, 2), SearchHighlight(3, 5)),
            SearchText.matches("go go", query, SearchMode.EXACT_PHRASE))
        assertNotNull(SearchText.matches("go", query, SearchMode.ALL_WORDS))
    }

    @Test
    fun popularSpellingsMatchTheFoldedTransliterationWhileShortEnglishWordsStayExact() {
        assertTrue("krsna" in SearchText.spellingVariants("krishna"))
        assertTrue("caitanya" in SearchText.spellingVariants("chaitanya"))
        assertTrue("visnu" in SearchText.spellingVariants("vishnu"))
        assertTrue("visva" in SearchText.spellingVariants("vishwa"))
        assertTrue("arjuna" in SearchText.spellingVariants("arjun"))
        assertTrue("sankirtana" in SearchText.spellingVariants("sankirtan"))
        assertTrue("laksmi" in SearchText.spellingVariants("lakshmi"))
        assertEquals("krishna", SearchText.spellingVariants("krishna").first())
        for (short in listOf("and", "too", "she", "is", "कृष्ण", "bg")) assertEquals(listOf(short), SearchText.spellingVariants(short))
        assertTrue(SearchText.spellingVariants("chhandogya").size <= 12)

        val query = SearchText.validate("Krishna consciousness")
        assertEquals(listOf("krishna", "consciousness"), query.terms)
        assertNotNull(SearchText.matches("Kṛṣṇa consciousness is eternal.", query, SearchMode.ALL_WORDS))
        assertNotNull(SearchText.matches("Kṛṣṇa consciousness", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("Krsnadasa consciousness", query, SearchMode.ALL_WORDS))
        assertTrue(SearchText.highlightTerms("Krishna").containsAll(listOf("krishna", "krsna")))
    }

    @Test
    fun trailingStarMatchesAnyEndingAfterAtLeastThreeLetters() {
        val query = SearchText.validate("bhakt* yoga")
        assertEquals(listOf("bhakt", "yoga"), query.terms)
        assertEquals("bhakt", query.groups[0].prefix)
        assertNull(query.groups[1].prefix)
        assertEquals(listOf("bhakt*", "yoga"), query.signature)
        assertNotNull(SearchText.matches("Bhakti-yoga is the path.", query, SearchMode.ALL_WORDS))
        assertNotNull(SearchText.matches("the bhaktas of yoga", query, SearchMode.ALL_WORDS))
        assertNull(SearchText.matches("bhava yoga", query, SearchMode.ALL_WORDS))
        assertNotNull(SearchText.matches("bhakti yoga", query, SearchMode.EXACT_PHRASE))
        assertNull(SearchText.matches("yoga bhakti", query, SearchMode.EXACT_PHRASE))
        rejects { SearchText.validate("bh* yoga") }
        assertEquals(listOf("go"), SearchText.validate("go *").terms) // A stray star is not a prefix.
        assertNull(SearchText.validate("go *").groups.single().prefix)
    }

    @Test
    fun validationRejectsRatherThanTruncates() {
        for (invalid in listOf("", " \n ", "..._%", "x".repeat(161), List(9) { "go" }.joinToString(" "))) {
            rejects { SearchText.validate(invalid) }
        }
        assertEquals(160, SearchText.validate("x".repeat(160)).terms.single().length)
        assertEquals(8, SearchText.validate(List(8) { "go" }.joinToString(" ")).terms.size)
        assertEquals(listOf("light"), SearchText.validate("light%_").terms)
    }

    @Test
    fun numericSeparatorsRemainSignificantAndCommonReferencesNormalize() {
        assertEquals("bg 2.8", SearchText.titleKey(" BG. 2 . 8 "))
        assertEquals("bg 2.8", SearchText.validate("BG2.8").reference?.key)
        assertEquals("sb 1.2.3", SearchText.validate("SB 1.2.3").reference?.key)
        assertEquals("cc adi 1.2", SearchText.validate("CC Ādi 1.2").reference?.key)
        assertNull(SearchText.validate("BG 28").reference)
        assertNull(SearchText.validate("BG 2.8.9").reference)
        assertNull(SearchText.validate("SB 1.2").reference)
        assertNull(SearchText.validate("CC 1.2").reference)
        assertFalse(SearchText.titleKey("BG 1.11") == SearchText.titleKey("BG 11.1"))
    }

    @Test
    fun highlightTermsFoldWordsAndSkipReferencesAndInvalidQueries() {
        // Highlights cover the folded words plus their accepted spelling variants.
        val highlights = SearchText.highlightTerms("  Kṛṣṇa devotional Krsna ")
        assertTrue(highlights.containsAll(setOf("krsna", "devotional")))
        assertTrue(highlights.all { it.startsWith("krsna") || it.startsWith("devotional") })
        assertEquals(setOf("krsna"), SearchText.highlightTerms("kṛṣṇa", SearchTarget.VERSE_WORDS))
        assertEquals(emptySet<String>(), SearchText.highlightTerms("Bg 2.8"))
        assertEquals(emptySet<String>(), SearchText.highlightTerms("CC Madhya 1.1"))
        assertEquals(emptySet<String>(), SearchText.highlightTerms(null))
        assertEquals(emptySet<String>(), SearchText.highlightTerms("   "))
        assertEquals(emptySet<String>(), SearchText.highlightTerms("a b c d e f g h i"))
        assertEquals(setOf("bg", "verse"), SearchText.highlightTerms("bg verse"))
    }

    @Test
    fun referenceTargetRequiresCompleteReferenceButAutocompleteAcceptsOnlyReferencePrefixes() {
        for (valid in listOf("BG2.8", "SB1.2.3", "CCMadhya1.1", "Madhya1.1", "Adi1.1", "Antya1.1")) {
            assertNotNull(valid, SearchText.validate(valid, SearchTarget.VERSE_REFERENCE).reference)
        }
        for (prefix in listOf("BG", "BG2.", "SB1.2.", "CC", "CCm", "CC Madhya", "Madhya1.", "Ad", "Antya")) {
            rejects { SearchText.validate(prefix, SearchTarget.VERSE_REFERENCE) }
            assertNull(SearchText.validate(prefix, SearchTarget.VERSE_REFERENCE, allowPrefix = true).reference)
        }
        for (invalid in listOf("Kṛṣṇa", "quoted BG 2.8", "\"BG 2.8\"", "BG2.8 text", "BG2.8.9", "SB1.2",
            "CC1.1", "BG0.8", "SB1.0.3", "Madhya1.0", "BG1.3-2", "BG1.1-0", "BG1.1.1.1")) {
            rejects { SearchText.validate(invalid, SearchTarget.VERSE_REFERENCE) }
        }
        for (invalid in listOf("quoted BG 2.8", "\"BG 2.8\"", "Krishna", "BG 2 sky")) {
            rejects { SearchText.validate(invalid, SearchTarget.VERSE_REFERENCE, allowPrefix = true) }
        }
        assertEquals(listOf("krishna"), SearchText.validate("Krishna", SearchTarget.VERSE_WORDS).terms)
        assertFalse(SearchText.normalize("Krishna") == SearchText.normalize("Kṛṣṇa"))
    }

    @Test
    fun ccAliasesMatchActualUnprefixedCorpusSpellingWithoutLosingTypedPrefix() {
        for (part in listOf("Adi", "Madhya", "Antya")) {
            val query = SearchText.validate("CC${part}1.1", SearchTarget.VERSE_REFERENCE)
            val source = "${part.lowercase()} 1.1"
            assertTrue(SearchText.referenceMatches(source, requireNotNull(query.reference)))
            assertEquals(listOf("${part.lowercase()} 1.", "cc ${part.lowercase()} 1."), query.reference!!.lookupStems)
            assertEquals(listOf("cc $source"), SearchText.referenceSuggestions(source, query))
            val unprefixed = SearchText.validate("${part}1.1", SearchTarget.VERSE_REFERENCE)
            assertEquals(listOf(source), SearchText.referenceSuggestions("cc $source", unprefixed))
        }
        val prefix = SearchText.validate("CC", SearchTarget.VERSE_REFERENCE, allowPrefix = true)
        assertEquals(listOf("cc madhya 1.1"), SearchText.referenceSuggestions("madhya 1.1", prefix))
    }

    @Test
    fun combinedCcRangesSupportLateMembersAndContiguousButNotGappedLists() {
        val late = SearchText.validate("CCMadhya1.2147483647", SearchTarget.VERSE_REFERENCE)
        val source = "madhya 1.1-2147483647"
        assertTrue(SearchText.referenceMatches(source, requireNotNull(late.reference)))
        assertEquals(listOf("cc madhya 1.2147483647"), SearchText.referenceSuggestions(source, late))
        val range = requireNotNull(SearchText.validate("Madhya1.16-18", SearchTarget.VERSE_REFERENCE).reference)
        assertTrue(SearchText.referenceMatches("madhya 1.16 madhya 1.17 madhya 1.18", range))
        assertFalse(SearchText.referenceMatches("madhya 1.16 madhya 1.18", range))
        assertFalse(SearchText.referenceMatches("adi 1.16-18", range))
    }

    @Test
    fun mainVerseStylesNeedSemanticPrecedenceAndDoNotAliasPurportQuotes() {
        for (style in listOf("PA_Verse", "PA_Bengro", "PA_DevaNagariQ", "PA_Devanagari", "PA_Uvaca_Line")) {
            assertTrue(style, SearchText.isVerseWordRecord("", style))
            assertFalse(style, SearchText.isVerseWordRecord("LE_Purport", style))
            assertFalse(style, SearchText.isVerseWordRecord("LE_Translation", style))
            assertFalse(style, SearchText.isVerseWordRecord("LE_Unknown", style))
        }
        assertTrue(SearchText.isVerseWordRecord(" LE_Verse_Text ", "PA_Bengro"))
        assertTrue(SearchText.isVerseWordRecord("LE_Verse_Text", "PA_Synonyms")) // Folio semantic precedence.
        for (style in listOf("PA_Verse_in_purp", "PA_Verse_in_purport", "PA_Translation", "PA_Synonyms", "PA_Purport")) {
            assertFalse(style, SearchText.isVerseWordRecord("", style))
        }
        assertFalse(SearchText.isVerseWordRecord("LE_Verse_Text", "PA_Verse_in_purp"))
        assertFalse(SearchText.isVerseWordRecord("LE_Verse_Number", "PA_Verse"))
    }

    @Test
    fun combinedReferenceMatchesExactMembersAndInclusiveRangesOnly() {
        val combined = "bg 1.16 bg 1.17 bg 1.18 bg 1.16-18"
        for (number in 16..18) {
            assertTrue(SearchText.referenceMatches(combined, requireNotNull(SearchText.validate("BG 1.$number").reference)))
        }
        assertTrue(SearchText.referenceMatches("BG 1.16–18", requireNotNull(SearchText.validate("BG 1.17").reference)))
        assertFalse(SearchText.referenceMatches(combined, requireNotNull(SearchText.validate("BG 1.1").reference)))
        assertFalse(SearchText.referenceMatches(combined, requireNotNull(SearchText.validate("BG 1.19").reference)))
        assertFalse(SearchText.referenceMatches(combined, requireNotNull(SearchText.validate("BG 2.17").reference)))
    }

    @Test
    fun snippetFindsLateMatchAndRetainsContextOnBothSides() {
        val plain = "Early context. ".repeat(90) + "Before Kṛṣṇa after. " + "Later context. ".repeat(90)
        val marks = requireNotNull(SearchText.matches(plain, SearchText.validate("krsna"), SearchMode.ALL_WORDS))
        val snippet = SearchText.snippet(plain, marks)
        assertTrue(snippet.text.length <= 260)
        assertTrue(snippet.text.contains("Before Kṛṣṇa after."))
        assertTrue(plain.contains(snippet.text))
        assertEquals("Kṛṣṇa", snippet.highlights.single().let { snippet.text.substring(it.start, it.endExclusive) })
        assertTrue(snippet.highlights.single().start > 0)
        assertTrue(snippet.highlights.single().endExclusive < snippet.text.length)
    }

    @Test
    fun unicodeSnippetAndHighlightEdgesNeverSplitSurrogatesOrCombiningSequences() {
        val plain = "😀a\u0304 ".repeat(40) + "Kṛṣṇa कृष्ण বাংলা 𐐀" + " e\u0301😀".repeat(40)
        for (term in listOf("krsna", "कृष्ण", "বাংলা", "𐐨")) {
            val matches = requireNotNull(SearchText.matches(plain, SearchText.validate(term), SearchMode.ALL_WORDS))
            for (width in 18..51) {
                val snippet = SearchText.snippet(plain, matches, width)
                assertTrue(snippet.text.length <= width)
                assertTrue(plain.contains(snippet.text))
                assertSafeEdges(snippet.text)
                val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(snippet.text) }
                for (mark in snippet.highlights) {
                    assertTrue(mark.start >= 0 && mark.endExclusive <= snippet.text.length)
                    assertTrue(graphemes.isBoundary(mark.start))
                    assertTrue(graphemes.isBoundary(mark.endExclusive))
                    assertEquals(term, SearchText.normalize(snippet.text.substring(mark.start, mark.endExclusive)))
                }
                assertTrue(snippet.highlights.isNotEmpty())
            }
        }
    }

    @Test
    fun absentTitleQueryIsAllowedToHaveAnUnhighlightedSnippet() {
        val snippet = SearchText.snippet("Original body without a reference.")
        assertEquals("Original body without a reference.", snippet.text)
        assertTrue(snippet.highlights.isEmpty())
        assertEquals("", SearchText.snippet("").text)
    }

    @Test
    fun snippetHighlightsUseWholeWordsNotNormalizedStringIndices() {
        val plain = "😀 Kṛṣṇa and a\u0304, not krsnax."
        val marks = requireNotNull(SearchText.matches(plain, SearchText.validate("krsna a"), SearchMode.ALL_WORDS))
        val snippet = SearchText.snippet(plain, marks)
        assertEquals(listOf("Kṛṣṇa", "a\u0304"), snippet.highlights.map { snippet.text.substring(it.start, it.endExclusive) })
    }

    @Test
    fun lexicalBoundsTreatSqlWildcardsLiterally() {
        assertEquals("krt", SearchText.prefixEnd("krs"))
        assertEquals("bg 2.9", SearchText.prefixEnd("bg 2.8"))
        assertEquals("a%`", SearchText.prefixEnd("a%_"))
        assertTrue("कृष्ण" >= "कृ")
        assertTrue("कृष्ण" < SearchText.prefixEnd("कृ"))
        rejects { SearchText.prefixEnd("") }
    }

    private fun assertSafeEdges(value: String) {
        if (value.isEmpty()) return
        assertFalse(Character.isLowSurrogate(value.first()))
        assertFalse(Character.isHighSurrogate(value.last()))
        assertFalse(Character.getType(value.codePointAt(0)) in setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        ))
    }

    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Expected validation to reject the input")
    }
}