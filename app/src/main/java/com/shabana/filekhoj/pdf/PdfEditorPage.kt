package com.shabana.filekhoj.pdf

data class PdfEditorPage(
    val sourceIndex: Int,
    val rotationDelta: Int = 0
)

object PdfEditorPageOperations {
    fun initial(pageCount: Int): List<PdfEditorPage> {
        require(pageCount > 0) { "The PDF does not contain any pages." }
        return (0 until pageCount).map { index -> PdfEditorPage(sourceIndex = index) }
    }

    fun move(pages: List<PdfEditorPage>, fromIndex: Int, toIndex: Int): List<PdfEditorPage> {
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) return pages
        return pages.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun moveSelected(
        pages: List<PdfEditorPage>,
        selectedSourceIndices: Set<Int>,
        direction: Int
    ): List<PdfEditorPage> {
        require(direction == -1 || direction == 1) {
            "Move direction must be -1 (up) or 1 (down)."
        }
        if (selectedSourceIndices.isEmpty()) return pages

        val result = pages.toMutableList()
        if (direction < 0) {
            for (index in 1 until result.size) {
                val currentSelected = result[index].sourceIndex in selectedSourceIndices
                val previousSelected = result[index - 1].sourceIndex in selectedSourceIndices
                if (currentSelected && !previousSelected) {
                    val item = result.removeAt(index)
                    result.add(index - 1, item)
                }
            }
        } else {
            for (index in result.lastIndex - 1 downTo 0) {
                val currentSelected = result[index].sourceIndex in selectedSourceIndices
                val nextSelected = result[index + 1].sourceIndex in selectedSourceIndices
                if (currentSelected && !nextSelected) {
                    val item = result.removeAt(index)
                    result.add(index + 1, item)
                }
            }
        }
        return result
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
