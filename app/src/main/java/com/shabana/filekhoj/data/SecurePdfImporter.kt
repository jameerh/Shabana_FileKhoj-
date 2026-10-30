package com.shabana.filekhoj.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface PdfPreparationResult {
    data class OriginalUnencrypted(val signatureStatus: String, val signatureCount: Int) : PdfPreparationResult
    data class DecryptedCopy(
        val file: File,
        val signatureStatus: String,
        val signatureCount: Int
    ) : PdfPreparationResult
    data object PasswordRequired : PdfPreparationResult
    data class Failed(val reason: PdfPreparationFailure) : PdfPreparationResult
}

enum class PdfPreparationFailure {
    NOT_A_READABLE_PDF,
    UNSUPPORTED_OR_CORRUPT_PDF,
    STORAGE_UNAVAILABLE
}

/**
 * Performs PDF password handling locally. Passwords are only passed for the duration
 * of this call and are never persisted or included in exception messages/logs.
 *
 * The source URI is never modified. An encrypted PDF's unlocked derivative is saved
 * under app-private files and must not inherit the source's digital-signature status.
 */
class SecurePdfImporter(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private const val MAX_IMPORT_SOURCE_BYTES = 512L * 1024L * 1024L
    }

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    fun prepare(sourceUri: Uri, password: String?): PdfPreparationResult {
        val temporary = File.createTempFile("filekhoj-pdf-", ".tmp", appContext.cacheDir)
        return try {
            val copied = appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMPORT_SOURCE_BYTES) throw IOException("PDF exceeds import size limit")
                        output.write(buffer, 0, read)
                    }
                }
                true
            } ?: false

            if (!copied || temporary.length() == 0L) {
                return PdfPreparationResult.Failed(PdfPreparationFailure.NOT_A_READABLE_PDF)
            }

            try {
                PDDocument.load(temporary, password ?: "").use { document ->
                    val signatureResult = PdfSignatureVerifier().verify(document, temporary)
                    if (!document.isEncrypted) {
                        return PdfPreparationResult.OriginalUnencrypted(
                            signatureResult.status,
                            signatureResult.signatureCount
                        )
                    }

                    val destinationDirectory = File(appContext.filesDir, "documents").apply {
                        if (!exists() && !mkdirs()) {
                            return PdfPreparationResult.Failed(PdfPreparationFailure.STORAGE_UNAVAILABLE)
                        }
                    }
                    val destination = File(destinationDirectory, "${UUID.randomUUID()}.pdf")
                    try {
                        document.setAllSecurityToBeRemoved(true)
                        document.save(destination)
                        if (!destination.exists() || destination.length() == 0L) {
                            destination.delete()
                            return PdfPreparationResult.Failed(PdfPreparationFailure.STORAGE_UNAVAILABLE)
                        }
                        PdfPreparationResult.DecryptedCopy(
                            destination,
                            signatureResult.status,
                            signatureResult.signatureCount
                        )
                    } catch (_: Exception) {
                        destination.delete()
                        PdfPreparationResult.Failed(PdfPreparationFailure.STORAGE_UNAVAILABLE)
                    }
                }
            } catch (_: InvalidPasswordException) {
                PdfPreparationResult.PasswordRequired
            } catch (_: IOException) {
                PdfPreparationResult.Failed(PdfPreparationFailure.UNSUPPORTED_OR_CORRUPT_PDF)
            } catch (_: Exception) {
                PdfPreparationResult.Failed(PdfPreparationFailure.UNSUPPORTED_OR_CORRUPT_PDF)
            }
        } catch (_: SecurityException) {
            PdfPreparationResult.Failed(PdfPreparationFailure.NOT_A_READABLE_PDF)
        } catch (_: IOException) {
            PdfPreparationResult.Failed(PdfPreparationFailure.STORAGE_UNAVAILABLE)
        } finally {
            temporary.delete()
        }
    }
}
