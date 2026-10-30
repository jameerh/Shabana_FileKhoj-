package com.shabana.filekhoj.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        if (id <= 0L) return@withContext Result.failure()

        val dao = DocumentDatabase.getInstance(applicationContext).documentDao()
        val document = dao.findById(id) ?: return@withContext Result.success()
        dao.updateIndexStatus(id, DocumentEntity.INDEX_STATUS_INDEXING)

        if (document.mimeType.startsWith("image/")) {
            val temporary = File.createTempFile("filekhoj-ocr-", ".img", applicationContext.cacheDir)
            try {
                openSourceStream(document).use { input -> copyBounded(input, temporary, MAX_SOURCE_BYTES) }
                val bitmap = decodeScaledBitmap(temporary)
                if (bitmap == null) {
                    dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                } else {
                    val text = try {
                        OnDeviceOcrEngine().use { it.recognize(bitmap) }
                    } finally {
                        bitmap.recycle()
                    }
                    if (text.isBlank()) dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_OCR_REQUIRED)
                    else dao.saveExtractedContent(id, document.displayName, text, 1)
                }
                Result.success()
            } catch (_: SecurityException) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_SOURCE_UNAVAILABLE)
                Result.success()
            } catch (_: IOException) {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                    dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                    Result.failure()
                }
            } catch (_: Exception) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_OCR_REQUIRED)
                Result.success()
            } finally {
                temporary.delete()
            }
        } else if (document.mimeType.contains("pdf", true) || document.displayName.endsWith(".pdf", true)) {
            val temporary = File.createTempFile("filekhoj-index-", ".pdf", applicationContext.cacheDir)
            try {
                openSourceStream(document).use { input -> copyBounded(input, temporary, MAX_SOURCE_BYTES) }
                val ocrEngine = runCatching { OnDeviceOcrEngine() }.getOrNull()
                val extracted = try {
                    extractPdf(temporary, ocrEngine)
                } finally {
                    ocrEngine?.close()
                }
                if (extracted.text.isBlank()) {
                    dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_OCR_REQUIRED)
                } else {
                    dao.saveExtractedContent(id, document.displayName, extracted.text, extracted.pageCount)
                }
                Result.success()
            } catch (_: SecurityException) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_SOURCE_UNAVAILABLE)
                Result.success()
            } catch (_: IOException) {
                if (runAttemptCount < MAX_RETRIES) Result.retry() else {
                    dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                    Result.failure()
                }
            } catch (_: Exception) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                Result.failure()
            } finally {
                temporary.delete()
            }
        } else if (document.mimeType == "text/plain" || document.displayName.endsWith(".txt", true)) {
            try {
                val text = openSourceStream(document).use { readTextBounded(it, MAX_TEXT_CHARS) }
                if (text.isBlank()) dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                else dao.saveExtractedContent(id, document.displayName, text, 1)
                Result.success()
            } catch (_: SecurityException) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_SOURCE_UNAVAILABLE)
                Result.success()
            } catch (_: Exception) {
                dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                Result.failure()
            }
        } else {
            dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_UNSUPPORTED)
            Result.success()
        }
    }

    private fun openSourceStream(document: DocumentEntity): InputStream {
        val managedPath = document.managedPath
        if (!managedPath.isNullOrBlank()) {
            val file = File(managedPath)
            if (file.isFile) return file.inputStream()
        }
        return applicationContext.contentResolver.openInputStream(Uri.parse(document.sourceUri))
            ?: throw IOException("Document source is no longer accessible")
    }

    private fun extractPdf(file: File, ocrEngine: OnDeviceOcrEngine?): ExtractedPdf {
        PDDocument.load(file).use { pdf ->
            if (pdf.isEncrypted) throw IOException("PDF is still encrypted")
            val stripper = PDFTextStripper()
            val text = StringBuilder()
            val maxPages = minOf(pdf.numberOfPages, MAX_PAGES)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                for (pageNumber in 1..maxPages) {
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    var pageText = stripper.getText(pdf).trim()
                    if (pageText.isBlank() && pageNumber <= MAX_OCR_PAGES && ocrEngine != null) {
                        val bitmap = renderPage(renderer, pageNumber - 1)
                        if (bitmap != null) {
                            pageText = try {
                                ocrEngine.recognize(bitmap).trim()
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    if (pageText.isNotBlank()) {
                        text.append("\n[Page ").append(pageNumber).append("]\n")
                        val remaining = MAX_TEXT_CHARS - text.length
                        if (remaining <= 0) break
                        text.append(pageText.take(remaining))
                    }
                    if (text.length >= MAX_TEXT_CHARS) break
                }
            }
            return ExtractedPdf(text.toString(), maxPages)
        }
    }

    private fun renderPage(renderer: PdfRenderer, pageIndex: Int): Bitmap? {
        if (pageIndex !in 0 until renderer.pageCount) return null
        renderer.openPage(pageIndex).use { page ->
            val width = minOf(MAX_RENDER_WIDTH, page.width.coerceAtLeast(1))
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private fun decodeScaledBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_RENDER_WIDTH ||
            bounds.outHeight / sampleSize > MAX_RENDER_WIDTH
        ) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun copyBounded(input: InputStream, destination: File, maxBytes: Long) {
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("File exceeds indexing size limit")
                output.write(buffer, 0, read)
            }
        }
    }

    private fun readTextBounded(input: InputStream, maxChars: Int): String {
        val output = StringBuilder()
        input.bufferedReader(Charset.forName("UTF-8")).use { reader ->
            val buffer = CharArray(8192)
            while (output.length < maxChars) {
                val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
                if (read < 0) break
                output.append(buffer, 0, read)
            }
        }
        return output.toString()
    }

    private data class ExtractedPdf(val text: String, val pageCount: Int)

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        private const val MAX_SOURCE_BYTES = 512L * 1024L * 1024L
        private const val MAX_TEXT_CHARS = 2_000_000
        private const val MAX_PAGES = 2_000
        private const val MAX_OCR_PAGES = 100
        private const val MAX_RENDER_WIDTH = 1600
        private const val MAX_RETRIES = 2
    }
}
