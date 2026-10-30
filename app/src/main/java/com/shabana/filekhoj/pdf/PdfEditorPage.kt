package com.shabana.filekhoj.pdf

data class PdfEditorPage(
    val sourceIndex: Int,
    val rotationDelta: Int = 0
)

object PdfEditorPageOperations {
    fun initial(pageCount: Int): List<PdfEditorPage> {
        require(pageCount > 0) { "The PDF does not contain any pages." }
        return (0 until pageCount).map(::PdfEditorPage)
    }

    fun move(pages: List<PdfEditorPage>, fromIndex: Int, toIndex: Int): List<PdfEditorPage> {
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) return pages
        return pages.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun rotate(
        pages: List<PdfEditorPage>,
        selectedSourceIndices: Set<Int>,
        clockwise: Boolean
    ): List<PdfEditorPage> {
        if (selectedSourceIndices.isEmpty()) return pages
        val delta = if (clockwise) 90 else -90
        return pages.map { page ->
            if (page.sourceIndex in selectedSourceIndices) {
                page.copy(rotationDelta = normalizeRotation(page.rotationDelta + delta))
            } else page
        }
    }

    fun delete(
        pages: List<PdfEditorPage>,
        selectedSourceIndices: Set<Int>
    ): List<PdfEditorPage> {
        require(selectedSourceIndices.isNotEmpty()) { "Select at least one page to remove." }
        val remaining = pages.filterNot { it.sourceIndex in selectedSourceIndices }
        require(remaining.isNotEmpty()) { "A PDF must contain at least one page." }
        return remaining
    }

    fun validate(pages: List<PdfEditorPage>, sourcePageCount: Int) {
        require(pages.isNotEmpty()) { "A PDF must contain at least one page." }
        require(pages.all { it.sourceIndex in 0 until sourcePageCount }) {
            "The page order contains an invalid source page."
        }
        require(pages.map { it.sourceIndex }.distinct().size == pages.size) {
            "A source page cannot appear more than once in this editor version."
        }
        require(pages.all { it.rotationDelta in setOf(0, 90, 180, 270) }) {
            "Page rotation must be a multiple of 90 degrees."
        }
    }

    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
