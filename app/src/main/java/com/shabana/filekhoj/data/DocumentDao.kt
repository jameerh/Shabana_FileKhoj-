package com.shabana.filekhoj.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY dateAdded DESC, id DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents")
    fun observeCount(): Flow<Int>

    @Query("""
        SELECT documents.* FROM documents
        INNER JOIN document_fts ON document_fts.rowid = documents.id
        WHERE document_fts MATCH :ftsQuery
        ORDER BY documents.dateAdded DESC, documents.id DESC
    """)
    suspend fun searchFullText(ftsQuery: String): List<DocumentEntity>

    @Query("SELECT * FROM document_text WHERE documentId = :documentId LIMIT 1")
    suspend fun findExtractedText(documentId: Long): DocumentTextEntity?

    @Query("SELECT * FROM documents WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(document: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtractedText(text: DocumentTextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFullTextIndex(index: DocumentFtsEntity)

    @Query("DELETE FROM document_text WHERE documentId = :documentId")
    suspend fun deleteExtractedText(documentId: Long)

    @Query("DELETE FROM document_fts WHERE rowid = :documentId")
    suspend fun deleteFullTextIndex(documentId: Long)

    @Query("UPDATE documents SET indexStatus = :status WHERE id = :id")
    suspend fun updateIndexStatus(id: Long, status: String)

    @Transaction
    suspend fun saveExtractedContent(
        id: Long,
        displayName: String,
        extractedText: String,
        pageCount: Int
    ) {
        upsertExtractedText(
            DocumentTextEntity(id, extractedText, System.currentTimeMillis(), pageCount)
        )
        upsertFullTextIndex(DocumentFtsEntity(id, displayName, extractedText))
        updateIndexStatus(id, DocumentEntity.INDEX_STATUS_INDEXED)
    }

    @Transaction
    suspend fun clearExtractedContent(id: Long, status: String) {
        deleteExtractedText(id)
        deleteFullTextIndex(id)
        updateIndexStatus(id, status)
    }

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity): Int

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Transaction
    suspend fun deleteDocumentCompletely(id: Long): Int {
        deleteExtractedText(id)
        deleteFullTextIndex(id)
        return deleteByIds(listOf(id))
    }

    @Query("UPDATE documents SET isFavourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE documents SET displayName = :displayName, category = :category WHERE id = :id")
    suspend fun updateUserMetadataFields(id: Long, displayName: String, category: String?)

    @Query("UPDATE document_fts SET displayName = :displayName WHERE rowid = :id")
    suspend fun updateIndexedDisplayName(id: Long, displayName: String)

    @Transaction
    suspend fun updateUserMetadata(id: Long, displayName: String, category: String?) {
        updateUserMetadataFields(id, displayName, category)
        updateIndexedDisplayName(id, displayName)
    }

    @Query("UPDATE documents SET signatureStatus = :status, signatureCount = :count WHERE id = :id")
    suspend fun updateSignatureStatus(id: Long, status: String, count: Int)
}
