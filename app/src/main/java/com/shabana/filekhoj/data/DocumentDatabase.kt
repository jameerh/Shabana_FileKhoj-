package com.shabana.filekhoj.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, DocumentTextEntity::class, DocumentFtsEntity::class],
    version = 4,
    exportSchema = false
)
abstract class DocumentDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN signatureStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
                database.execSQL("ALTER TABLE documents ADD COLUMN signatureCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE documents ADD COLUMN isEncryptedSource INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE documents ADD COLUMN isStoredDecrypted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE documents ADD COLUMN managedPath TEXT")
                database.execSQL("ALTER TABLE documents ADD COLUMN isAvailable INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE documents ADD COLUMN isFavourite INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_documents_mimeType ON documents(mimeType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_documents_signatureStatus ON documents(signatureStatus)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS document_text (documentId INTEGER NOT NULL PRIMARY KEY, extractedText TEXT NOT NULL, indexedAt INTEGER NOT NULL, pageCount INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS document_fts USING FTS4(displayName TEXT NOT NULL, extractedText TEXT NOT NULL, tokenize=unicode61)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Room's generated FTS4 schema has no explicit tokenizer option and
                // expects columns in this order. Recreate the table to match Room's
                // schema validation, then restore existing searchable content.
                database.execSQL("DROP TABLE IF EXISTS document_fts")
                database.execSQL(
                    "CREATE VIRTUAL TABLE document_fts USING FTS4(extractedText TEXT NOT NULL, displayName TEXT NOT NULL)"
                )
                database.execSQL(
                    """
                    INSERT INTO document_fts(rowid, extractedText, displayName)
                    SELECT documents.id, COALESCE(document_text.extractedText, ''), documents.displayName
                    FROM documents
                    LEFT JOIN document_text ON document_text.documentId = documents.id
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: DocumentDatabase? = null

        fun getInstance(context: Context): DocumentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DocumentDatabase::class.java,
                    "filekhoj.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
