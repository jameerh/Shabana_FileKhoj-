package com.shabana.filekhoj.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ImportDocumentResult(
    val imported: Int,
    val alreadyPresent: Int,
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
                    // One inaccessible or malformed URI must not abort a multi-file import.
                    failed++
                }
            }

            ImportDocumentResult(imported, alreadyPresent, failed)
        }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled document"
        var sizeBytes = 0L
        var lastModified: Long? = null

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
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

                // OpenableColumns does not standardize a last-modified column.
                // Leave this nullable rather than depending on provider-specific columns.
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
