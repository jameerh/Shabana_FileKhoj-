package com.shabana.filekhoj.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class ImportDocumentResult(
    val imported: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val passwordRequiredUris: List<Uri> = emptyList()
)

data class DeleteDocumentResult(val deleted: Int, val failed: Int)

class DocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = DocumentDatabase.getInstance(appContext).documentDao()
    private val securePdfImporter by lazy { SecurePdfImporter(appContext) }

    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeAll()
    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Reprocess the existing library once after the OCR quality gate is introduced.
     * This corrects legacy INDEXED labels written without confidence/coverage checks.
     */
    suspend fun reindexExistingDocumentsForOcrQualityGate() = withContext(Dispatchers.IO) {
        val migrationPrefs = appContext.getSharedPreferences("filekhoj_index_quality", Context.MODE_PRIVATE)
        if (migrationPrefs.getBoolean("quality_gate_reindex_v1_complete", false)) return@withContext

        dao.getAllForBackup().forEach { document ->
            dao.updateIndexStatus(document.id, DocumentEntity.INDEX_STATUS_QUEUED)
            val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to document.id))
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "filekhoj-index-${document.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
        migrationPrefs.edit().putBoolean("quality_gate_reindex_v1_complete", true).commit()
    }

    suspend fun searchDocuments(query: String): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val ftsQuery = SearchQueryNormalizer.toFtsQuery(query) ?: return@withContext emptyList()
        try {
            dao.searchFullText(ftsQuery)
        } catch (_: Exception) {
            // Malformed FTS expressions must never crash the library search.
            emptyList()
        }
    }

    suspend fun importDocuments(
        uris: List<Uri>,
        passwordsByUri: Map<String, String> = emptyMap()
    ): ImportDocumentResult = withContext(Dispatchers.IO) {
        var imported = 0
        var alreadyPresent = 0
        var failed = 0
        val passwordRequired = mutableListOf<Uri>()

        uris.distinctBy { it.toString() }.forEach { uri ->
            var managedCopy: File? = null
            try {
                if (dao.findBySourceUri(uri.toString()) != null) {
                    alreadyPresent++
                    return@forEach
                }

                val metadata = queryMetadata(uri)
                var encryptedSource = false
                var storedDecrypted = false
                var signatureStatus = DocumentEntity.SIGNATURE_STATUS_UNSIGNED
                var signatureCount = 0

                if (metadata.mimeType.contains("pdf", ignoreCase = true) ||
                    metadata.displayName.endsWith(".pdf", ignoreCase = true)
                ) {
                    when (val preparation = securePdfImporter.prepare(uri, passwordsByUri[uri.toString()])) {
                        is PdfPreparationResult.OriginalUnencrypted -> {
                            signatureStatus = preparation.signatureStatus
                            signatureCount = preparation.signatureCount
                        }
                        is PdfPreparationResult.DecryptedCopy -> {
                            managedCopy = preparation.file
                            encryptedSource = true
                            storedDecrypted = true
                            signatureStatus = preparation.signatureStatus
                            signatureCount = preparation.signatureCount
                        }
                        PdfPreparationResult.PasswordRequired -> {
                            passwordRequired += uri
                            return@forEach
                        }
                        is PdfPreparationResult.Failed -> {
                            failed++
                            return@forEach
                        }
                    }
                }

                val rowId = dao.insert(
                    DocumentEntity(
                        displayName = metadata.displayName,
                        mimeType = metadata.mimeType,
                        sizeBytes = managedCopy?.length() ?: metadata.sizeBytes,
                        sourceUri = uri.toString(),
                        dateAdded = System.currentTimeMillis(),
                        sourceLastModified = metadata.lastModified,
                        isEncryptedSource = encryptedSource,
                        isStoredDecrypted = storedDecrypted,
                        managedPath = managedCopy?.absolutePath,
                        signatureStatus = signatureStatus,
                        signatureCount = signatureCount
                    )
                )
                if (rowId == -1L) {
                    alreadyPresent++
                    managedCopy?.delete()
                } else {
                    imported++
                    val indexRequest = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                        .setInputData(workDataOf(DocumentIndexWorker.KEY_DOCUMENT_ID to rowId))
                        .build()
                    WorkManager.getInstance(appContext).enqueueUniqueWork(
                        "filekhoj-index-$rowId",
                        ExistingWorkPolicy.KEEP,
                        indexRequest
                    )
                }
            } catch (_: Exception) {
                managedCopy?.delete()
                failed++
            }
        }
        ImportDocumentResult(imported, alreadyPresent, failed, passwordRequired.distinctBy { it.toString() })
    }

    /**
     * Removes library entries, never provider originals. A managed copy is first moved aside;
     * if the Room transaction fails, the file is moved back so the visible row remains usable.
     */
    suspend fun deleteDocuments(ids: List<Long>): DeleteDocumentResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        ids.distinct().forEach { id ->
            var originalFile: File? = null
            var quarantineFile: File? = null
            try {
                val document = dao.findById(id) ?: return@forEach
                val managedPath = document.managedPath
                if (!managedPath.isNullOrBlank()) {
                    val managedFile = File(managedPath).canonicalFile
                    val privateRoot = appContext.filesDir.canonicalFile
                    if (managedFile.path.startsWith(privateRoot.path + File.separator) && managedFile.isFile) {
                        originalFile = managedFile
                        quarantineFile = File(
                            managedFile.parentFile,
                            ".${managedFile.name}.pending-delete-${System.nanoTime()}"
                        )
                        if (!managedFile.renameTo(quarantineFile!!)) {
                            throw IllegalStateException("Could not safely stage managed copy for deletion.")
                        }
                    }
                }

                val removed = try {
                    dao.deleteDocumentCompletely(id)
                } catch (error: Exception) {
                    quarantineFile?.takeIf { it.exists() }?.renameTo(originalFile!!)
                    throw error
                }
                if (removed > 0) {
                    deleted++
                    // A failed cleanup only leaves a private orphan; it never loses the source document.
                    quarantineFile?.delete()
                } else {
                    quarantineFile?.takeIf { it.exists() }?.renameTo(originalFile!!)
                }
            } catch (_: Exception) {
                if (quarantineFile?.exists() == true && originalFile?.exists() == false) {
                    quarantineFile?.renameTo(originalFile!!)
                }
                failed++
            }
        }
        DeleteDocumentResult(deleted, failed)
    }

    suspend fun setFavourite(id: Long, favourite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavourite(id, favourite)
    }

    suspend fun updateUserMetadata(id: Long, displayName: String, category: String?) = withContext(Dispatchers.IO) {
        require(displayName.isNotBlank()) { "Document name cannot be empty." }
        dao.updateUserMetadata(id, displayName.trim(), category?.trim()?.takeIf(String::isNotBlank))
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled document"
        var sizeBytes = 0L
        var lastModified: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf(String::isNotBlank)?.let {
                        displayName = it
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    }
                    val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        lastModified = cursor.getLong(modifiedIndex).takeIf { it > 0L }
                    }
                }
            }
        return SourceMetadata(
            displayName = displayName,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = sizeBytes,
            lastModified = lastModified
        )
    }

    private data class SourceMetadata(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val lastModified: Long?
    )
}
