package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_reminders",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["dueAt"]), Index(value = ["documentId"])]
)
data class DocumentReminderEntity(
    @androidx.room.PrimaryKey
    val documentId: Long,
    val label: String,
    val dueAt: Long,
    val enabled: Boolean = true,
    val notifiedAt: Long? = null,
    val createdAt: Long
)
