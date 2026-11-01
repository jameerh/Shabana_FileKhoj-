package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many document/profile membership. The document payload remains single-copy.
 * A profile-specific category may differ without mutating the global document metadata.
 */
@Entity(
    tableName = "document_profile_assignments",
    primaryKeys = ["documentId", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DocumentProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["profileId"]), Index(value = ["documentId"])]
)
data class DocumentProfileAssignmentEntity(
    val documentId: Long,
    val profileId: Long,
    val categoryOverride: String? = null
)
