package com.shabana.filekhoj.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocumentDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var instance: DocumentDatabase? = null

        fun getInstance(context: Context): DocumentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DocumentDatabase::class.java,
                    "filekhoj.db"
                ).build().also { instance = it }
            }
    }
}
