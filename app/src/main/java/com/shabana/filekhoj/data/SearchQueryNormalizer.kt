package com.shabana.filekhoj.data

import java.text.Normalizer

/**
 * Converts user-entered text into a literal FTS4 query. Search operators are always quoted,
 * control/format characters are normalized away, and Unicode is normalized consistently.
 */
internal object SearchQueryNormalizer {
    fun toFtsQuery(query: String): String? {
        val normalized = Normalizer.normalize(query, Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .trim()
        if (normalized.isEmpty()) return null

        val terms = normalized.split(Regex("\\s+"))
            .filter { term -> term.any(::isSearchableCharacter) }
        if (terms.isEmpty()) return null

        return terms.joinToString(" AND ") { term ->
            "\"${term.replace("\"", "\"\"")}\""
        }
    }

    private fun isSearchableCharacter(char: Char): Boolean {
        if (char.isLetterOrDigit()) return true
        return when (Character.getType(char)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
