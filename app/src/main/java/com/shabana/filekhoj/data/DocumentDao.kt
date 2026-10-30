package com.shabana.filekhoj.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY dateAdded DESC, id DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM documents WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity): Int

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("UPDATE documents SET isFavourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE documents SET signatureStatus = :status, signatureCount = :count WHERE id = :id")
    suspend fun updateSignatureStatus(id: Long, status: String, count: Int)
}
