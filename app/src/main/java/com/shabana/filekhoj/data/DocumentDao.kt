package com.shabana.filekhoj.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY dateAdded DESC, id DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM documents WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): DocumentEntity?

    /** Returns -1 when this URI has already been registered. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long
}
