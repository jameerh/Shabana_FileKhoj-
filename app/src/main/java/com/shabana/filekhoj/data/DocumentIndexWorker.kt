package com.shabana.filekhoj.data

import android.content.Context
import android.net.Uri
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
            dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_OCR_REQUIRED)
            return@withContext Result.success()
        }

        if (document.mimeType.contains("pdf", true) || document.displayName.endsWith(".pdf", true)) {
            val temporary = File.createTempFile("filekhoj-index-", ".pdf", applicationContext.cacheDir)
            try {
                openSourceStream(document).use { input -> copyBounded(input, temporary, MAX_SOURCE_BYTES) }
                val extracted = extractPdf(temporary)
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
        } else if (document.mimeType == "text/plain" ||
            document.displayName.endsWith(".txt", true)
        ) {
            try {
                val text = openSourceStream(document).use { readTextBounded(it, MAX_TEXT_CHARS) }
                if (text.isBlank()) {
                    dao.clearExtractedContent(id, DocumentEntity.INDEX_STATUS_FAILED)
                } else {
                    dao.saveExtractedContent(id, document.displayName, text, 1)
                }
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

    private fun extractPdf(file: File): ExtractedPdf {
        PDDocument.load(file).use { pdf ->
            if (pdf.isEncrypted) throw IOException("PDF is still encrypted")
            val stripper = PDFTextStripper()
            val text = StringBuilder()
            val maxPages = minOf(pdf.numberOfPages, MAX_PAGES)
            for (page in 1..maxPages) {
                stripper.startPage = page
                stripper.endPage = page
                val pageText = stripper.getText(pdf).trim()
                if (pageText.isNotEmpty()) {
                    text.append("\n[Page ").append(page).append("]\n")
                    val remaining = MAX_TEXT_CHARS - text.length
                    if (remaining <= 0) break
                    text.append(pageText.take(remaining))
                }
                if (text.length >= MAX_TEXT_CHARS) break
            }
            return ExtractedPdf(text.toString(), maxPages)
        }
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
        private const val MAX_RETRIES = 2
    }
}
