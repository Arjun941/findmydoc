package com.example.core.embeddings

import android.content.Context

class DynamicEmbeddingEngine(private val context: Context) : EmbeddingEngine {

    val gemmaEngine by lazy { GemmaEmbeddingEngine(context) }

    fun getActiveEngine(): EmbeddingEngine {
        return gemmaEngine
    }

    override val modelDirName: String get() = gemmaEngine.modelDirName

    override fun isModelDownloaded(): Boolean = gemmaEngine.isModelDownloaded()

    override suspend fun downloadModelAndVocab(onProgress: (String, Float) -> Unit): Boolean {
        return gemmaEngine.downloadModelAndVocab(onProgress)
    }

    override fun ensureInitialized(): Boolean = gemmaEngine.ensureInitialized()

    override suspend fun embed(text: String): FloatArray {
        return gemmaEngine.embed(text)
    }

    override suspend fun embed(text: String, isQuery: Boolean): FloatArray {
        return gemmaEngine.embed(text, isQuery)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        return gemmaEngine.embedBatch(texts)
    }

    override fun tokenizeToIds(text: String): List<Int> {
        return gemmaEngine.tokenizeToIds(text)
    }
}
