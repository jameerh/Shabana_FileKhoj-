package com.shabana.filekhoj.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.shabana.filekhoj.data.DocumentEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Owns one temporary working copy of a source PDF for the editor's lifetime.
 * The source URI/file is opened read-only and is never saved back to.
 */
class PdfEditorSession private constructor(
    private val context: Context,
    private val document: PDDocument,
    private val workingFile: File
) : Closeable {
    val pageCount: Int
        get() = document.numberOfPages

    private val renderer = PDFRenderer(document)
    private var closed = false

    @Synchronized
    fun renderThumbnail(pageIndex: Int, maxDimension: Int = 150): Bitmap {
        checkOpen()
        require(pageIndex in 0 until pageCount) { "Page number is out of range." }
        require(maxDimension in 64..1200) { "Preview dimension is out of range." }
        val cropBox = document.getPage(pageIndex).cropBox
        val longestSide = maxOf(cropBox.width, cropBox.height).coerceAtLeast(1f)
        val scale = (maxDimension / longestSide).coerceIn(0.1f, 2.0f)
        return renderer.renderImage(pageIndex, scale)
    }

    /**
     * Reorders/removes pages and applies rotations in the in-memory working copy,
     * writes to a different file, verifies the output, then restores the session
     * so a user can continue editing or save another copy.
     */
    @Synchronized
    fun exportCopy(pages: List<PdfEditorPage>): File {
        checkOpen()
        PdfEditorPageOperations.validate(pages, pageCount)

        val sourcePages = (0 until pageCount).map(document::getPage)
        val originalRotations = sourcePages.map { it.rotation }
        val exportDirectory = File(context.cacheDir, "filekhoj_pdf_exports")
        if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
            throw IllegalStateException("Could not create the PDF export workspace.")
        }
        val outputFile = File(exportDirectory, "FileKhoj_${UUID.randomUUID()}.pdf")

        try {
            val orderedPages = pages.map { editorPage ->
                val page = sourcePages[editorPage.sourceIndex]
                page.rotation = PdfEditorPageOperations.normalizeRotation(
                    originalRotations[editorPage.sourceIndex] + editorPage.rotationDelta
                )
                page
            }

            sourcePages.forEach(document::removePage)
            orderedPages.forEach(document::addPage)
            document.save(outputFile)

            PDDocument.load(outputFile, memoryUsageSetting(context)).use { verification ->
                check(verification.numberOfPages == pages.size) {
                    "The exported PDF page count did not match the editor."
                }
                pages.forEachIndexed { index, editorPage ->
                    val expected = PdfEditorPageOperations.normalizeRotation(
                        originalRotations[editorPage.sourceIndex] + editorPage.rotationDelta
                    )
                    check(verification.getPage(index).rotation == expected) {
                        "The exported PDF rotation did not pass validation."
                    }
                }
            }
            return outputFile
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            // Restore the in-memory source session; only the separate export is changed.
            document.pages.toList().forEach(document::removePage)
            sourcePages.forEachIndexed { index, page ->
                page.rotation = originalRotations[index]
                document.addPage(page)
            }
        }
    }

    private fun checkOpen() {
        check(!closed) { "The PDF editor session is already closed." }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            document.close()
        } finally {
            workingFile.delete()
        }
    }

    companion object {
        private const val MAX_SOURCE_BYTES = 250L * 1024L * 1024L
        private const val MAX_PDFBOX_MEMORY_BYTES = 16L * 1024L * 1024L
        private const val MAX_PDFBOX_STORAGE_BYTES = 512L * 1024L * 1024L

        private fun memoryUsageSetting(context: Context): MemoryUsageSetting {
            val scratchDirectory = File(context.cacheDir, "filekhoj_pdf_scratch")
            if (!scratchDirectory.exists() && !scratchDirectory.mkdirs()) {
                throw IllegalStateException("Could not create the PDF processing scratch directory.")
            }
            return MemoryUsageSetting.setupMixed(
                MAX_PDFBOX_MEMORY_BYTES,
                MAX_PDFBOX_STORAGE_BYTES
            ).setTempDir(scratchDirectory)
        }

        fun open(context: Context, source: DocumentEntity): PdfEditorSession {
            val appContext = context.applicationContext
            PDFBoxResourceLoader.init(appContext)

            val workspace = File(appContext.cacheDir, "filekhoj_pdf_editor")
            if (!workspace.exists() && !workspace.mkdirs()) {
                throw IllegalStateException("Could not create the PDF editor workspace.")
            }
            val workingFile = File(workspace, "source_${UUID.randomUUID()}.pdf")

            try {
                val input = if (source.isStoredDecrypted) {
                    val path = source.managedPath
                        ?: throw IllegalStateException("The unlocked PDF copy is unavailable.")
                    val managed = File(path)
                    require(managed.isFile) { "The unlocked PDF copy is missing." }
                    managed.inputStream()
                } else {
                    appContext.contentResolver.openInputStream(Uri.parse(source.sourceUri))
                        ?: throw IllegalStateException("FileKhoj cannot open this PDF. Re-import the file.")
                }

                input.use { inputStream ->
                    FileOutputStream(workingFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_SOURCE_BYTES) {
                                "This PDF is larger than the 250 MB editor limit."
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }

                val pdf = PDDocument.load(workingFile, memoryUsageSetting(appContext))
                require(pdf.numberOfPages > 0) { "This PDF does not contain any pages." }
                require(pdf.numberOfPages <= 10_000) {
                    "This PDF has more than 10,000 pages and cannot be edited safely on this device."
                }
                return PdfEditorSession(appContext, pdf, workingFile)
            } catch (error: Exception) {
                workingFile.delete()
                throw error
            }
        }
    }
}
