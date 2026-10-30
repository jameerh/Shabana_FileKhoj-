package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata for a user-selected document. The app stores a URI reference rather than
 * copying potentially large/private files into its own storage.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["sourceUri"], unique = true),
        Index(value = ["displayName"]),
        Index(value = ["dateAdded"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sourceUri: String,
    val dateAdded: Long,
    val sourceLastModified: Long?,
    val category: String? = null,
    val contentHash: String? = null,
    val indexStatus: String = INDEX_STATUS_NOT_INDEXED
) {
    companion object {
        const val INDEX_STATUS_NOT_INDEXED = "NOT_INDEXED"
    }
}
