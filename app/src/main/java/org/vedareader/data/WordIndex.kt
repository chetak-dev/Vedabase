package org.vedareader.data

import java.text.Normalizer
import java.util.BitSet
import java.util.Locale

/** The IVD word index stores six-byte little-endian (record offset, word position) postings. */
object WordIndex {
    fun addRecords(target: BitSet, base: Int, bytes: ByteArray) {
        require(base >= 0 && bytes.size % 6 == 0) { "Invalid word index data" }
        var offset = 0
        while (offset < bytes.size) {
            var record = 0L
            for (byte in 0..3) record = record or ((bytes[offset + byte].toLong() and 255) shl (byte * 8))
            val absolute = base.toLong() + record
            require(absolute in 0..20_000_000) { "Word index record is out of range" }
            target.set(absolute.toInt())
            offset += 6
        }
    }

    /** Fold Latin transliteration accents, but retain vowel signs in Hindi/Bengali/etc. */
    fun normalize(value: String): String {
        val result = StringBuilder()
        var latin = false
        for (character in Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)) {
            val mark = Character.getType(character) in setOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
            )
            if (!mark) latin = Character.UnicodeScript.of(character.code) == Character.UnicodeScript.LATIN
            if (!mark || !latin) result.append(character)
        }
        return Normalizer.normalize(result, Normalizer.Form.NFC)
    }

    fun terms(query: String): List<String> = Regex("[\\p{L}\\p{N}\\p{M}]+")
        .findAll(normalize(query)).map { it.value }.distinct().toList()

    fun simpleTitle(value: String): String = normalize(value).replace(Regex("[^\\p{L}\\p{N}\\p{M}\\s-]"), "")
        .replace(Regex("\\s+"), " ").trim()
}