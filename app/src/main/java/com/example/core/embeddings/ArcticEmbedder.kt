package com.example.core.embeddings

import ai.djl.modality.nlp.DefaultVocabulary
import ai.djl.modality.nlp.bert.BertFullTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Embeds text with Snowflake's arctic-embed-s (int8 ONNX, 384-dim, CLS pooling + L2 norm), run
 * fully on-device via ONNX Runtime Mobile. Model + tokenizer files are bundled as APK assets
 * (assets/arctic_embed/) exactly as retrieved from the Snowflake HF repo - no hand-written
 * tokenizer/vocab/pooling logic. Pooling mode and max sequence length were read from that repo's
 * 1_Pooling/config.json (pooling_mode_cls_token=true, all others false) and
 * sentence_bert_config.json (max_seq_length=512), not assumed.
 */
class ArcticEmbedder(private val context: Context) : EmbeddingEngine {

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var vocabulary: DefaultVocabulary? = null
    private var tokenizer: BertFullTokenizer? = null

    fun ensureInitialized(): Boolean {
        // Locks on the companion's shared lock (not a per-instance one): concurrent OrtSession
        // creation from multiple ArcticEmbedder instances against the shared process-wide
        // OrtEnvironment caused a real on-device SIGSEGV inside libonnxruntime.so. Serializing
        // creation across every instance fixed it; run() calls on an already-built session are
        // still safe to happen concurrently across the worker pool.
        return synchronized(initializationLock) {
            if (session != null) return@synchronized true
            try {
                val modelBytes = context.assets.open("$ASSET_DIR/model_int8.onnx").use { it.readBytes() }

                // Plain CPU EP only. XNNPACK was tried first but caused a real on-device SIGSEGV
                // inside libonnxruntime.so during inference on this int8-quantized model
                // (reproduced twice, same crash signature) - likely an XNNPACK int8-kernel
                // incompatibility on this SoC. CPU EP's QLinear kernels are mature/well-tested for
                // quantized models and are the safe default; no NNAPI/GPU either, per the GPU
                // delegate's device-wide memory-pressure crash history with the previous model.
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(INTRA_OP_THREADS)
                }
                session = ortEnvironment.createSession(modelBytes, sessionOptions)

                val vocabFile = copyAssetToCache("$ASSET_DIR/vocab.txt", "arctic_vocab.txt")
                val vocab = DefaultVocabulary.builder()
                    .addFromTextFile(vocabFile.toPath())
                    .optUnknownToken("[UNK]")
                    .build()
                vocabulary = vocab
                // do_lower_case=true per the repo's tokenizer_config.json - verified, not assumed.
                tokenizer = BertFullTokenizer(vocab, true)

                Log.d(TAG, "ArcticEmbedder initialized (intraOpThreads=$INTRA_OP_THREADS)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                session?.close()
                session = null
                false
            }
        }
    }

    private fun copyAssetToCache(assetPath: String, cacheName: String): File {
        val outFile = File(context.cacheDir, cacheName)
        if (!outFile.exists() || outFile.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile
    }

    override suspend fun embed(text: String): FloatArray = embed(text, isQuery = false)

    // arctic-embed-s is asymmetric: queries need "Represent this sentence for searching relevant
    // passages: " prepended, documents/chunks get no prefix. Confirmed against the model card's
    // documented usage, not assumed - omitting this collapsed relevant/irrelevant separation to
    // near zero in calibration.
    override suspend fun embed(text: String, isQuery: Boolean): FloatArray {
        val input = if (isQuery) QUERY_PREFIX + text else text
        return embedBatchInternal(listOf(input)).firstOrNull() ?: FloatArray(EMBEDDING_DIM)
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = embedBatchInternal(texts)

    override fun tokenizeToIds(text: String): List<Int> {
        if (!ensureInitialized()) return emptyList()
        return buildInputIds(text).map { it.toInt() }
    }

    private fun buildInputIds(text: String): LongArray {
        val bertTokenizer = tokenizer ?: return LongArray(0)
        val vocab = vocabulary ?: return LongArray(0)
        val wordpieces = bertTokenizer.tokenize(text)
        val truncated = if (wordpieces.size > MAX_SEQ_LENGTH - 2) {
            wordpieces.subList(0, MAX_SEQ_LENGTH - 2)
        } else {
            wordpieces
        }
        val withSpecialTokens = listOf("[CLS]") + truncated + listOf("[SEP]")
        return LongArray(withSpecialTokens.size) { i -> vocab.getIndex(withSpecialTokens[i]) }
    }

    private suspend fun embedBatchInternal(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (!ensureInitialized()) {
            throw IllegalStateException("ArcticEmbedder failed to initialize - model/tokenizer assets missing or corrupt.")
        }
        val ortSession = session ?: throw IllegalStateException("ONNX session is null after initialization.")

        val tokenIdLists = texts.map { text ->
            val ids = buildInputIds(text)
            Log.d(TAG, "chunk token count (incl [CLS]/[SEP]): ${ids.size}")
            ids
        }

        val batchSize = texts.size
        val maxLen = tokenIdLists.maxOf { it.size }.coerceAtLeast(1)

        val inputIdsArr = LongArray(batchSize * maxLen)
        val attnMaskArr = LongArray(batchSize * maxLen)
        val tokenTypeArr = LongArray(batchSize * maxLen) // single-segment input: all zeros

        for (i in tokenIdLists.indices) {
            val ids = tokenIdLists[i]
            val base = i * maxLen
            for (j in ids.indices) {
                inputIdsArr[base + j] = ids[j]
                attnMaskArr[base + j] = 1L
            }
            // Remaining positions stay 0, matching pad_token_id=0 from config.json.
        }

        val shape = longArrayOf(batchSize.toLong(), maxLen.toLong())
        OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(inputIdsArr), shape).use { inputIdsTensor ->
            OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(attnMaskArr), shape).use { attnMaskTensor ->
                OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(tokenTypeArr), shape).use { tokenTypeTensor ->
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attnMaskTensor,
                        "token_type_ids" to tokenTypeTensor
                    )
                    ortSession.run(inputs).use { result ->
                        // Model has a single output, last_hidden_state: [batch, seq_len, 384].
                        val outputTensor = result.get(0) as OnnxTensor
                        val floatBuffer = outputTensor.floatBuffer
                        (0 until batchSize).map { b ->
                            // CLS pooling per 1_Pooling/config.json: take position 0 of the sequence dim.
                            val clsBase = b * maxLen * EMBEDDING_DIM
                            val vector = FloatArray(EMBEDDING_DIM) { d -> floatBuffer.get(clsBase + d) }
                            l2Normalize(vector)
                            vector
                        }
                    }
                }
            }
        }
    }

    private fun l2Normalize(vector: FloatArray) {
        var sumSquares = 0.0f
        for (value in vector) sumSquares += value * value
        val magnitude = kotlin.math.sqrt(sumSquares)
        if (magnitude > 1e-6f) {
            for (i in vector.indices) vector[i] /= magnitude
        }
    }

    companion object {
        private const val TAG = "ArcticEmbedder"
        private val initializationLock = Any()
        const val EMBEDDING_DIM = 384 // hidden_size from config.json, verified not assumed
        const val MAX_SEQ_LENGTH = 512 // max_seq_length from sentence_bert_config.json, verified not assumed
        private const val INTRA_OP_THREADS = 4
        private const val ASSET_DIR = "arctic_embed"
        private const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
    }
}
