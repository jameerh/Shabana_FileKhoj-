package com.shabana.filekhoj.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfEditorPageOperationsTest {
    @Test
    fun initialCreatesZeroBasedPageOrder() {
        assertEquals(listOf(0, 1, 2), PdfEditorPageOperations.initial(3).map { it.sourceIndex })
    }

    @Test
    fun moveReordersPagesWithoutChangingTheirIdentity() {
        val moved = PdfEditorPageOperations.move(PdfEditorPageOperations.initial(4), 0, 2)
        assertEquals(listOf(1, 2, 0, 3), moved.map { it.sourceIndex })
    }

    @Test
    fun moveSelectedMovesContiguousSelectionOnePositionUp() {
        val pages = PdfEditorPageOperations.initial(5)
        val moved = PdfEditorPageOperations.moveSelected(pages, setOf(2, 3), direction = -1)
        assertEquals(listOf(0, 2, 3, 1, 4), moved.map { it.sourceIndex })
    }

    @Test
    fun moveSelectedMovesMultiplePagesDownAsAStableGroup() {
        val pages = PdfEditorPageOperations.initial(5)
        val moved = PdfEditorPageOperations.moveSelected(pages, setOf(1, 3), direction = 1)
        assertEquals(listOf(0, 2, 1, 4, 3), moved.map { it.sourceIndex })
    }

    @Test
    fun rotateAppliesClockwiseAndAnticlockwiseDeltas() {
        val pages = PdfEditorPageOperations.initial(2)
        val clockwise = PdfEditorPageOperations.rotate(pages, setOf(0), clockwise = true)
        assertEquals(90, clockwise.first().rotationDelta)
        val anticlockwise = PdfEditorPageOperations.rotate(clockwise, setOf(0), clockwise = false)
        assertEquals(0, anticlockwise.first().rotationDelta)
    }

    @Test
    fun rotationWrapsAt360Degrees() {
        val pages = listOf(PdfEditorPage(0, 270))
        assertEquals(0, PdfEditorPageOperations.rotate(pages, setOf(0), true).first().rotationDelta)
    }

    @Test
    fun deleteRemovesOnlySelectedPages() {
        val remaining = PdfEditorPageOperations.delete(PdfEditorPageOperations.initial(4), setOf(1, 3))
        assertEquals(listOf(0, 2), remaining.map { it.sourceIndex })
    }

    @Test
    fun deleteCannotRemoveEveryPage() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfEditorPageOperations.delete(PdfEditorPageOperations.initial(1), setOf(0))
        }
    }

    @Test
    fun validationRejectsDuplicatePageReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfEditorPageOperations.validate(listOf(PdfEditorPage(0), PdfEditorPage(0)), 1)
        }
    }
}
