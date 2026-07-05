package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ChunkWithEmbedding(
    val chunkId: Long,
    val documentId: Long,
    val text: String,
    val order: Int,
    val vector: FloatArray
)

@Dao
interface DocDao {

    // --- Document Queries ---
    @Query("SELECT * FROM documents ORDER BY indexedAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents")
    suspend fun getAllDocumentsSync(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getDocumentByUri(uri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    // --- Chunk Queries ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>): List<Long>

    @Query("SELECT * FROM chunks WHERE documentId = :documentId ORDER BY `order` ASC")
    suspend fun getChunksForDocument(documentId: Long): List<ChunkEntity>

    @Query("DELETE FROM chunks WHERE documentId = :documentId")
    suspend fun deleteChunksByDocumentId(documentId: Long)

    @Query("DELETE FROM chunks")
    suspend fun deleteAllChunks()

    // --- Embedding Queries ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("SELECT * FROM embeddings WHERE chunkId = :chunkId LIMIT 1")
    suspend fun getEmbeddingByChunkId(chunkId: Long): EmbeddingEntity?

    @Query(
        """
        SELECT chunks.id as chunkId, chunks.documentId, chunks.text, chunks.`order`, embeddings.vector 
        FROM chunks 
        INNER JOIN embeddings ON chunks.id = embeddings.chunkId
    """
    )
    suspend fun getAllChunksWithEmbeddings(): List<ChunkWithEmbedding>

    @Query("DELETE FROM embeddings WHERE chunkId IN (SELECT id FROM chunks WHERE documentId = :documentId)")
    suspend fun deleteEmbeddingsByDocumentId(documentId: Long)

    @Query("DELETE FROM embeddings")
    suspend fun deleteAllEmbeddings()

    // --- OCR Queries ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOcr(ocr: OcrEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOcrs(ocrs: List<OcrEntity>)

    @Query("SELECT * FROM ocr_cache WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getOcrForDocument(documentId: Long): List<OcrEntity>

    @Query("DELETE FROM ocr_cache WHERE documentId = :documentId")
    suspend fun deleteOcrByDocumentId(documentId: Long)

    @Query("DELETE FROM ocr_cache")
    suspend fun deleteAllOcrCache()

    // --- Search History Queries ---
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    fun getSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(searchHistory: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchHistoryQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // --- Document Usage Queries ---
    @Query("SELECT * FROM document_usages WHERE documentId = :documentId LIMIT 1")
    suspend fun getDocumentUsage(documentId: Long): DocumentUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentUsage(usage: DocumentUsageEntity)

    @Query("SELECT * FROM document_usages ORDER BY lastOpened DESC")
    fun getAllDocumentUsages(): Flow<List<DocumentUsageEntity>>
}
