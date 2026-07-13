package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        DocumentEntity::class,
        ChunkEntity::class,
        EmbeddingEntity::class,
        OcrEntity::class,
        SearchHistoryEntity::class,
        DocumentUsageEntity::class,
        IndexingLedgerEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DocDatabase : RoomDatabase() {

    abstract fun docDao(): DocDao

    companion object {
        @Volatile
        private var INSTANCE: DocDatabase? = null

        fun getDatabase(context: Context): DocDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocDatabase::class.java,
                    "seekmydocs_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
