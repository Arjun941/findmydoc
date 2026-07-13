package com.example.core.embeddings

import android.content.Context

class DynamicEmbeddingEngine(private val context: Context) : EmbeddingEngine {

    val arcticEngine by lazy { ArcticEmbedder(context) }

    override suspend fun embed(text: String): FloatArray {
        return arcticEngine.embed(text)
    }

    override suspend fun embed(text: String, isQuery: Boolean): FloatArray {
        return arcticEngine.embed(text, isQuery)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return arcticEngine.embedBatch(texts)
    }

    override fun tokenizeToIds(text: String): List<Int> {
        return arcticEngine.tokenizeToIds(text)
    }
}
