package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "document_text")
data class DocumentTextEntity(
    @PrimaryKey val documentId: Long,
    val extractedText: String,
    val indexedAt: Long,
    val pageCount: Int
)
