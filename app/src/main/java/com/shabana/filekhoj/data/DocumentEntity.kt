package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["sourceUri"], unique = true),
        Index(value = ["displayName"]),
        Index(value = ["dateAdded"]),
        Index(value = ["mimeType"]),
        Index(value = ["signatureStatus"])
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
    val indexStatus: String = INDEX_STATUS_NOT_INDEXED,
    val signatureStatus: String = SIGNATURE_STATUS_UNKNOWN,
    val signatureCount: Int = 0,
    val isEncryptedSource: Boolean = false,
    val isStoredDecrypted: Boolean = false,
    val managedPath: String? = null,
    val isAvailable: Boolean = true,
    val isFavourite: Boolean = false
) {
    companion object {
        const val INDEX_STATUS_NOT_INDEXED = "NOT_INDEXED"
        const val INDEX_STATUS_QUEUED = "QUEUED"
        const val INDEX_STATUS_INDEXED = "INDEXED"
        const val INDEX_STATUS_FAILED = "FAILED"

        const val SIGNATURE_STATUS_UNKNOWN = "UNKNOWN"
        const val SIGNATURE_STATUS_UNSIGNED = "UNSIGNED"
        const val SIGNATURE_STATUS_VERIFIED = "VERIFIED"
        const val SIGNATURE_STATUS_UNTRUSTED = "UNTRUSTED"
        const val SIGNATURE_STATUS_INVALID = "INVALID"
        const val SIGNATURE_STATUS_INCOMPLETE = "INCOMPLETE"
    }
}
