package com.shabana.filekhoj.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class ImportDocumentResult(
    val imported: Int,
    val alreadyPresent: Int,
    val failed: Int
)

data class DeleteDocumentResult(
    val deleted: Int,
    val failed: Int
)

class DocumentRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = DocumentDatabase.getInstance(appContext).documentDao()

    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeAll()
    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun importDocuments(uris: List<Uri>): ImportDocumentResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var alreadyPresent = 0
            var failed = 0
            uris.distinctBy { it.toString() }.forEach { uri ->
                try {
                    val metadata = queryMetadata(uri)
                    val rowId = dao.insert(
                        DocumentEntity(
                            displayName = metadata.displayName,
                            mimeType = metadata.mimeType,
                            sizeBytes = metadata.sizeBytes,
                            sourceUri = uri.toString(),
                            dateAdded = System.currentTimeMillis(),
                            sourceLastModified = metadata.lastModified
                        )
                    )
                    if (rowId == -1L) alreadyPresent++ else imported++
                } catch (_: Exception) {
                    failed++
                }
            }
            ImportDocumentResult(imported, alreadyPresent, failed)
        }

    /**
     * Removes selected entries from FileKhoj. It never deletes the provider's original
     * document. A managed copy is removed only when its path is inside app-private files.
     */
    suspend fun deleteDocuments(ids: List<Long>): DeleteDocumentResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var failed = 0
        ids.distinct().forEach { id ->
            try {
                val document = dao.findById(id) ?: return@forEach
                val managedPath = document.managedPath
                if (!managedPath.isNullOrBlank()) {
                    val managedFile = File(managedPath).canonicalFile
                    val privateRoot = appContext.filesDir.canonicalFile
                    if (managedFile.path.startsWith(privateRoot.path + File.separator) && managedFile.exists()) {
                        if (!managedFile.delete()) throw IllegalStateException("Could not remove managed copy")
                    }
                }
                if (dao.deleteByIds(listOf(id)) > 0) deleted++
            } catch (_: Exception) {
                failed++
            }
        }
        DeleteDocumentResult(deleted, failed)
    }

    suspend fun setFavourite(id: Long, favourite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavourite(id, favourite)
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled document"
        var sizeBytes = 0L
        var lastModified: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf(String::isNotBlank)?.let {
                    displayName = it
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
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
