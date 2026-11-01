package com.shabana.filekhoj.data

/**
 * Conservative, deterministic quality gates for searchable text.
 *
 * OCR confidence is the model's confidence estimate, not a guarantee of semantic correctness.
 * The text-shape check catches empty, tiny, and mostly-symbol output; it cannot prove that
 * recognized words match the source image.
 */
internal object IndexQualityPolicy {
    const val MIN_QUALITY = 0.75f
    private const val MIN_CONTENT_CHARACTERS = 8

    fun isUsableText(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.count(::isContentCharacter) < MIN_CONTENT_CHARACTERS) return false
        if (compact.isEmpty()) return false
        val contentCharacters = compact.count(::isContentCharacter)
        return contentCharacters.toFloat() / compact.length >= MIN_QUALITY
    }

    fun isUsableOcr(text: String, confidence: Float): Boolean =
        confidence >= MIN_QUALITY && isUsableText(text)

    fun hasEnoughPageCoverage(usablePages: Int, totalPages: Int): Boolean =
        totalPages > 0 && usablePages.toFloat() / totalPages >= MIN_QUALITY

    /**
     * Keeps usable extracted text searchable when a long PDF exceeds the OCR page budget,
     * while making incomplete coverage explicit instead of silently discarding all text.
     */
    fun pdfIndexStatus(text: String, usablePages: Int, totalPages: Int): String = when {
        text.isBlank() || usablePages <= 0 || totalPages <= 0 -> DocumentEntity.INDEX_STATUS_OCR_REQUIRED
        hasEnoughPageCoverage(usablePages, totalPages) -> DocumentEntity.INDEX_STATUS_INDEXED
        else -> DocumentEntity.INDEX_STATUS_PARTIALLY_INDEXED
    }

    private fun isContentCharacter(char: Char): Boolean {
        if (char.isLetterOrDigit()) return true
        return when (Character.getType(char)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
