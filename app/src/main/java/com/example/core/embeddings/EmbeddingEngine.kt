package com.example.core.embeddings

interface EmbeddingEngine {
    suspend fun embed(text: String): FloatArray
    suspend fun embed(text: String, isQuery: Boolean): FloatArray = embed(text)
    suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    fun tokenizeToIds(text: String): List<Int> = emptyList()
}
