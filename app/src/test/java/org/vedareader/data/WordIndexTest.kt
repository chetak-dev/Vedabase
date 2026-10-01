package org.vedareader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/** Synthetic postings only: four unsigned LE offset bytes, followed by two position bytes. */
class WordIndexTest {
    @Test
    fun decodesAllFourLittleEndianOffsetBytesAndAddsIndexBase() {
        val records = BitSet()
        val base = 321
        val bytes = byteArrayOf(0x04, 0x03, 0x02, 0x01, 0xff.toByte(), 0x80.toByte())
        WordIndex.addRecords(records, base, bytes)
        assertEquals(1, records.cardinality())
        assertTrue(records[base + 0x01020304])
    }

    @Test
    fun ignoresPositionBytesAndCombinesDuplicatesAsASet() {
        val records = BitSet().apply { set(9) }
        WordIndex.addRecords(records, 100, posting(7, 0) + posting(7, 0xffff) + posting(8, 0x1234))
        WordIndex.addRecords(records, 105, posting(2, 42))
        assertEquals(BitSet().apply { set(9); set(107); set(108) }, records)
    }

    @Test
    fun unsignedLowBytesAreNotSignExtended() {
        val records = BitSet()
        WordIndex.addRecords(records, 10, byteArrayOf(0xff.toByte(), 0xff.toByte(), 0, 0, 0, 0))
        assertEquals(1, records.cardinality())
        assertTrue(records[65_545])
    }

    @Test
    fun emptyPostingsDoNotChangeTheExistingSet() {
        val records = BitSet().apply { set(12) }
        WordIndex.addRecords(records, 100, byteArrayOf())
        assertEquals(BitSet().apply { set(12) }, records)
    }

    @Test
    fun malformedSixByteTailsAreRejectedBeforeAnyRecordIsAdded() {
        for (tail in 1..5) {
            for (bytes in listOf(ByteArray(tail), posting(3) + ByteArray(tail))) {
                val records = BitSet().apply { set(12) }
                assertThrows(IllegalArgumentException::class.java) { WordIndex.addRecords(records, 0, bytes) }
                assertEquals(BitSet().apply { set(12) }, records)
            }
        }
    }

    @Test
    fun negativeBasesAreRejectedEvenForEmptyData() {
        for (bytes in listOf(byteArrayOf(), posting(1))) {
            assertThrows(IllegalArgumentException::class.java) { WordIndex.addRecords(BitSet(), -1, bytes) }
        }
    }

    @Test
    fun recordBoundsAreInclusiveAtZeroAndTwentyMillion() {
        val records = BitSet()
        WordIndex.addRecords(records, 0, posting(0) + posting(20_000_000))
        WordIndex.addRecords(records, 20_000_000, posting(0))
        assertEquals(2, records.cardinality())
        assertTrue(records[0])
        assertTrue(records[20_000_000])
    }

    @Test
    fun rejectsOutOfBoundsAndOverflowingUnsignedOffsets() {
        val invalid = listOf(
            0 to posting(20_000_001),
            1 to posting(20_000_000),
            20_000_001 to posting(0),
            Int.MAX_VALUE to posting(1),
            0 to posting(0x80000000L),
            1 to posting(0xffffffffL),
        )
        for ((base, bytes) in invalid) {
            assertThrows(IllegalArgumentException::class.java) { WordIndex.addRecords(BitSet(), base, bytes) }
        }
    }

    @Test
    fun foldsLatinTransliterationAndBothUnicodeNormalizationForms() {
        assertEquals("krsna", WordIndex.normalize("Kṛṣṇa"))
        assertEquals("sri krsna", WordIndex.normalize("Śrī Kṛṣṇa"))
        assertEquals("sri krsna", WordIndex.normalize("S\u0301ri\u0304 Kr\u0323s\u0323n\u0323a"))
        assertEquals("i i", WordIndex.normalize("I İ"))
        assertEquals("", WordIndex.normalize(""))
    }

    @Test
    fun retainsHindiAndBengaliVowelSignsViramasAndCombiningMarks() {
        val hindi = "कृष्ण हिन्दी श्री कि की कु कृ क्"
        val bengali = "কৃষ্ণ শ্রী বাংলা কি কী কু কৃ ক্"
        assertEquals(hindi, WordIndex.normalize(hindi))
        assertEquals(bengali, WordIndex.normalize(bengali))
        assertEquals("krsna कृष्ण কৃষ্ণ", WordIndex.normalize("Kṛṣṇa कृष्ण কৃষ্ণ"))
        assertEquals("a कि কি", WordIndex.normalize("a\u0301 कि কি"))
    }

    @Test
    fun tokenizationFoldsLatinDeduplicatesAndKeepsScriptMarksAttached() {
        val query = "Kṛṣṇa, krsna; कृष्ण/कृष्ण কৃষ্ণ — BG 18.66 foo-bar 42 42"
        assertEquals(listOf("krsna", "कृष्ण", "কৃষ্ণ", "bg", "18", "66", "foo", "bar", "42"), WordIndex.terms(query))
        assertEquals(listOf("हिन्दी", "বাংলা", "१२৩"), WordIndex.terms("हिन्दी বাংলা १२৩"))
    }

    @Test
    fun tokenizationKeepsLetterNumberRunsButSplitsPunctuation() {
        val query = "Śrī-Kṛṣṇa BG18 BG 18.66 कृष्ण, কৃষ্ণ! ŚRĪ"
        assertEquals(listOf("sri", "krsna", "bg18", "bg", "18", "66", "कृष्ण", "কৃষ্ণ"), WordIndex.terms(query))
        assertTrue(WordIndex.terms("").isEmpty())
        assertTrue(WordIndex.terms(" ,.;!? [] / + — 😀 ").isEmpty())
    }

    @Test
    fun simpleTitlesFoldAccentsAndPunctuationWithoutDestroyingScripts() {
        assertEquals("bg 1866 sri-krsna", WordIndex.simpleTitle(" [BG]   18.66: Śrī-Kṛṣṇa! "))
        assertEquals("कृष्ण বাংলা", WordIndex.simpleTitle("\"कृष्ण\"   — বাংলা"))
    }

    private fun posting(offset: Long, position: Int = 0): ByteArray = byteArrayOf(
        (offset and 0xff).toByte(),
        ((offset ushr 8) and 0xff).toByte(),
        ((offset ushr 16) and 0xff).toByte(),
        ((offset ushr 24) and 0xff).toByte(),
        (position and 0xff).toByte(),
        ((position ushr 8) and 0xff).toByte(),
    )
}