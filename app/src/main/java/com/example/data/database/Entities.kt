package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String, // String representation of Uri
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val indexedAt: Long,
    val hash: String,
    val documentType: String, // "PDF", "Word", "Excel", "PowerPoint", "Text", etc.
    val ocrCompleted: Boolean = false,
    val embeddingCompleted: Boolean = false
)

@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val text: String,
    val order: Int,
    val tokenIds: String? = null // Comma-separated list of token IDs for caching
)

@Entity(tableName = "embeddings")
data class EmbeddingEntity(
    @PrimaryKey val chunkId: Long,
    val vector: FloatArray // Handled by TypeConverter
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingEntity
        if (chunkId != other.chunkId) return false
        if (!vector.contentEquals(other.vector)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

@Entity(tableName = "ocr_cache")
data class OcrEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageNumber: Int,
    val text: String,
    val confidence: Float
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "document_usages")
data class DocumentUsageEntity(
    @PrimaryKey val documentId: Long,
    val openCount: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)
