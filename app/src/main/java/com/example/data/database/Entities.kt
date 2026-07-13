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

/**
 * Per-file lifecycle tracker for the indexing pipeline. A row is written for every discovered
 * file before any processing starts, so a file can never silently disappear from the pipeline -
 * it must end in COMMITTED or FAILED (with a reason). Carries the same fields as the pipeline's
 * internal DocumentMetadata so a FAILED entry can be re-attempted without re-scanning the device.
 */
@Entity(tableName = "indexing_ledger")
data class IndexingLedgerEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val modifiedAt: Long,
    val hash: String,
    val documentType: String,
    val status: String,
    val failedStage: String? = null,
    val failureReason: String? = null,
    val attemptCount: Int = 1,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

object LedgerStatus {
    const val DISCOVERED = "DISCOVERED"
    const val EXTRACTED = "EXTRACTED"
    const val CHUNKED = "CHUNKED"
    const val EMBEDDED = "EMBEDDED"
    const val COMMITTED = "COMMITTED"
    const val FAILED = "FAILED"
}
