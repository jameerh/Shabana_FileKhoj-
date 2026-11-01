package com.shabana.filekhoj.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document workspace is an organizational profile, not a user-registration account.
 * Profiles only control library organization; they are not a security boundary.
 */
@Entity(
    tableName = "document_profiles",
    indices = [Index(value = ["name"], unique = true)]
)
data class DocumentProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val isDefault: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val DEFAULT_NAME = "Personal"
        const val MAX_NAME_LENGTH = 40
    }
}
