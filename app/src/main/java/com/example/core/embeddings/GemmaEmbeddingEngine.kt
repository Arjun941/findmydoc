package com.example.core.embeddings

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.EOFException
import java.io.ByteArrayInputStream
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class GemmaEmbeddingEngine(
    private val context: Context
) : EmbeddingEngine {

    val modelDir = File(context.filesDir, "models/gemma-300m")
    val modelFile = File(modelDir, "embeddinggemma-300m.tflite")
    val vocabFile = File(modelDir, "sentencepiece.model")

    override val modelDirName: String get() = "gemma-300m"

    private var interpreter: Interpreter? = null
    private var tokenizer: SentencePieceTokenizer? = null
    private val initializationLock = Any()

    init {
        if (isModelDownloaded()) {
            ensureInitialized()
        }
    }

    override suspend fun embed(text: String): FloatArray {
        return embed(text, isQuery = false)
    }

    override suspend fun embed(text: String, isQuery: Boolean): FloatArray {
        // Gemma embeddinggemma-300m does not require a prefix instruction, we pass the text directly.
        val results = embedBatch(listOf(text))
        return results.firstOrNull() ?: FloatArray(144)
    }

    override fun isModelDownloaded(): Boolean {
        return modelFile.exists() && modelFile.length() > 0 && vocabFile.exists() && vocabFile.length() > 0
    }

    override suspend fun downloadModelAndVocab(onProgress: (String, Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"

            // 1. Download sentencepiece.model first if it doesn't exist.
            if (!vocabFile.exists() || vocabFile.length() == 0L) {
                onProgress("Downloading Tokenizer...", 0f)
                val vocabUrl = "https://huggingface.co/Xenova/gemma-tokenizer/resolve/main/tokenizer.model"
                val request = Request.Builder()
                    .url(vocabUrl)
                    .header("User-Agent", browserUserAgent)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "Failed to download vocabulary: HTTP ${response.code} ${response.message} (URL: $vocabUrl)"
                        Log.e("GemmaEmbeddingEngine", errMsg)
                        throw IllegalStateException(errMsg)
                    }
                    val body = response.body ?: throw IllegalStateException("Response body is null for vocabulary download from $vocabUrl")
                    val contentLength = body.contentLength()
                    body.byteStream().use { input ->
                        vocabFile.outputStream().use { output ->
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (contentLength > 0) {
                                    val downloadedMB = totalBytesRead.toDouble() / (1024 * 1024)
                                    val totalMB = contentLength.toDouble() / (1024 * 1024)
                                    val progress = (totalBytesRead.toFloat() / contentLength) * 100f
                                    val text = String.format(java.util.Locale.US, "Downloading Tokenizer (%.2f MB / %.2f MB - %.1f%%)", downloadedMB, totalMB, progress)
                                    onProgress(text, progress)
                                } else {
                                    val downloadedMB = totalBytesRead.toDouble() / (1024 * 1024)
                                    val text = String.format(java.util.Locale.US, "Downloading Tokenizer (%.2f MB)", downloadedMB)
                                    onProgress(text, -1f)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Download Gemma Model TFLite if it doesn't exist
            if (!modelFile.exists() || modelFile.length() == 0L) {
                onProgress("Downloading Gemma Model...", 0f)
                val modelUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/embeddinggemma-300m.tflite"
                val request = Request.Builder()
                    .url(modelUrl)
                    .header("User-Agent", browserUserAgent)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = "Failed to download Gemma model: HTTP ${response.code} ${response.message} (URL: $modelUrl)"
                        Log.e("GemmaEmbeddingEngine", errMsg)
                        throw IllegalStateException(errMsg)
                    }
                    val body = response.body ?: throw IllegalStateException("Response body is null for Gemma model download from $modelUrl")
                    val contentLength = body.contentLength()
                    body.byteStream().use { input ->
                        modelFile.outputStream().use { output ->
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (contentLength > 0) {
                                    val downloadedMB = totalBytesRead.toDouble() / (1024 * 1024)
                                    val totalMB = contentLength.toDouble() / (1024 * 1024)
                                    val progress = (totalBytesRead.toFloat() / contentLength) * 100f
                                    val text = String.format(java.util.Locale.US, "Downloading Gemma Model (%.2f MB / %.2f MB - %.1f%%)", downloadedMB, totalMB, progress)
                                    onProgress(text, progress)
                                } else {
                                    val downloadedMB = totalBytesRead.toDouble() / (1024 * 1024)
                                    val text = String.format(java.util.Locale.US, "Downloading Gemma Model (%.2f MB)", downloadedMB)
                                    onProgress(text, -1f)
                                }
                            }
                        }
                    }
                }
            }

            val success = isModelDownloaded()
            if (success) {
                ensureInitialized()
            } else {
                throw IllegalStateException("Downloaded model files are missing or incomplete after download finished.")
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e("GemmaEmbeddingEngine", "Download failed", e)
            if (modelFile.exists()) modelFile.delete()
            if (vocabFile.exists()) vocabFile.delete()
            throw e
        }
    }

    override fun ensureInitialized(): Boolean {
        synchronized(initializationLock) {
            if (interpreter != null && tokenizer != null) return true

            try {
                if (!isModelDownloaded()) {
                    return false
                }

                // 1. Initialize custom SentencePieceTokenizer
                FileInputStream(vocabFile).use { fis ->
                    val pieces = parseSentencePieceModel(fis)
                    tokenizer = SentencePieceTokenizer(pieces)
                }

                // 2. Initialize TFLite Interpreter with 4 threads and try NNAPI for NPU acceleration
                val options = Interpreter.Options()
                options.setNumThreads(4)
                try {
                    val cacheDir = File(context.cacheDir, "nnapi_cache")
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val nnapiOptions = org.tensorflow.lite.nnapi.NnApiDelegate.Options().apply {
                        setCacheDir(cacheDir.absolutePath)
                        setModelToken("gemma_300m_v1_aot")
                        setExecutionPreference(org.tensorflow.lite.nnapi.NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                    }
                    val nnapiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate(nnapiOptions)
                    options.addDelegate(nnapiDelegate)
                    Log.d("GemmaEmbeddingEngine", "NNAPI Delegate added with AOT cache successfully.")
                } catch (e: Throwable) {
                    Log.w("GemmaEmbeddingEngine", "NNAPI delegate not supported or failed to load. Falling back to CPU.", e)
                }

                interpreter = Interpreter(modelFile, options)
                Log.d("GemmaEmbeddingEngine", "Gemma TFLite Interpreter initialized successfully")
                return true
            } catch (e: Exception) {
                Log.e("GemmaEmbeddingEngine", "Initialization failed", e)
                return false
            }
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()

        if (!ensureInitialized()) {
            throw IllegalStateException("Gemma Embedding Engine failed to initialize. Model or vocabulary files are missing, incomplete, or corrupted.")
        }

        val tflite = interpreter ?: throw IllegalStateException("Gemma TFLite Interpreter is null. Model initialization failed.")
        val tok = tokenizer ?: throw IllegalStateException("SentencePieceTokenizer is null. Vocabulary initialization failed.")

        try {
            val results = mutableListOf<FloatArray>()
            for (text in texts) {
                val tokenIds = tok.tokenize(text).take(512)
                val seqLen = tokenIds.size
                if (seqLen == 0) {
                    results.add(FloatArray(144))
                    continue
                }

                // Input is [1, seqLen] Ints
                val inputVal = arrayOf(tokenIds.toIntArray())

                // Output: Gemma embeddinggemma-300m.tflite outputs shape [1, 144]
                val outputVal = Array(1) { FloatArray(144) }
                val outputs = mapOf(0 to outputVal)

                tflite.runForMultipleInputsOutputs(arrayOf(inputVal), outputs)

                val embedding = outputVal[0].clone()
                l2Normalize(embedding)
                results.add(embedding)
            }
            return@withContext results
        } catch (e: Exception) {
            Log.e("GemmaEmbeddingEngine", "Gemma TFLite Inference error", e)
            throw IllegalStateException("Gemma TFLite Inference failed: ${e.localizedMessage}", e)
        }
    }

    override fun tokenizeToIds(text: String): List<Int> {
        return tokenizer?.tokenize(text) ?: emptyList()
    }

    private fun l2Normalize(vector: FloatArray) {
        var sum = 0.0f
        for (v in vector) {
            sum += v * v
        }
        val magnitude = sqrt(sum)
        if (magnitude > 1e-6f) {
            for (i in vector.indices) {
                vector[i] /= magnitude
            }
        }
    }

    // Binary Protobuf SentencePiece Parser implementation
    class SentencePieceEntry(
        val piece: String,
        val score: Float,
        val type: Int = 1
    )

    private fun readVarint(inputStream: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = inputStream.read()
            if (b == -1) throw EOFException()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                break
            }
            shift += 7
        }
        return result
    }

    private fun readFloat(inputStream: InputStream): Float {
        val b0 = inputStream.read()
        val b1 = inputStream.read()
        val b2 = inputStream.read()
        val b3 = inputStream.read()
        if ((b0 or b1 or b2 or b3) < 0) throw EOFException()
        val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return Float.fromBits(bits)
    }

    private fun skipField(stream: InputStream, wireType: Int) {
        when (wireType) {
            0 -> { readVarint(stream) }
            1 -> { stream.skip(8) }
            2 -> {
                val len = readVarint(stream)
                stream.skip(len)
            }
            5 -> { stream.skip(4) }
            else -> throw IllegalArgumentException("Unknown wire type: $wireType")
        }
    }

    private fun parseSentencePiece(bytes: ByteArray): SentencePieceEntry {
        var piece = ""
        var score = 0f
        var type = 1
        
        val stream = ByteArrayInputStream(bytes)
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            
            when (fieldNumber) {
                1 -> {
                    val len = readVarint(stream).toInt()
                    val strBytes = ByteArray(len)
                    var readLen = 0
                    while (readLen < len) {
                        val r = stream.read(strBytes, readLen, len - readLen)
                        if (r == -1) throw EOFException()
                        readLen += r
                    }
                    piece = String(strBytes, Charsets.UTF_8)
                }
                2 -> {
                    score = readFloat(stream)
                }
                3 -> {
                    type = readVarint(stream).toInt()
                }
                else -> {
                    skipField(stream, wireType)
                }
            }
        }
        return SentencePieceEntry(piece, score, type)
    }

    private fun parseSentencePieceModel(inputStream: InputStream): List<SentencePieceEntry> {
        val pieces = mutableListOf<SentencePieceEntry>()
        val buffered = inputStream.buffered()
        
        while (true) {
            val tag = try {
                readVarint(buffered)
            } catch (e: EOFException) {
                break
            }
            
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            
            if (fieldNumber == 1 && wireType == 2) {
                val length = readVarint(buffered).toInt()
                val bytes = ByteArray(length)
                var readTotal = 0
                while (readTotal < length) {
                    val read = buffered.read(bytes, readTotal, length - readTotal)
                    if (read == -1) throw EOFException()
                    readTotal += read
                }
                val pieceEntry = parseSentencePiece(bytes)
                pieces.add(pieceEntry)
            } else {
                skipField(buffered, wireType)
            }
        }
        return pieces
    }

    class SentencePieceTokenizer(private val pieces: List<SentencePieceEntry>) {
        private val pieceToId = mutableMapOf<String, Int>()
        init {
            for (i in pieces.indices) {
                pieceToId[pieces[i].piece] = i
            }
        }

        fun tokenize(text: String): List<Int> {
            if (text.isEmpty()) return emptyList()

            // 1. Replace spaces with U+2581 and prepend U+2581
            val normalized = "\u2581" + text.replace(" ", "\u2581")

            // 2. Initialize symbols as individual characters/unicode code points
            val symbols = mutableListOf<String>()
            var i = 0
            while (i < normalized.length) {
                val codePoint = normalized.codePointAt(i)
                val charCount = Character.charCount(codePoint)
                val charStr = normalized.substring(i, i + charCount)
                symbols.add(charStr)
                i += charCount
            }

            // 3. BPE merge loop
            while (true) {
                var bestPairIdx = -1
                var bestScore = -Float.MAX_VALUE
                var bestMergedPiece = ""

                for (j in 0 until symbols.size - 1) {
                    val merged = symbols[j] + symbols[j + 1]
                    val entryId = pieceToId[merged]
                    if (entryId != null) {
                        val score = pieces[entryId].score
                        if (score > bestScore) {
                            bestScore = score
                            bestPairIdx = j
                            bestMergedPiece = merged
                        }
                    }
                }

                if (bestPairIdx == -1) {
                    break
                }

                symbols[bestPairIdx] = bestMergedPiece
                symbols.removeAt(bestPairIdx + 1)
            }

            // 4. Convert symbols to token IDs
            val tokenIds = mutableListOf<Int>()
            for (symbol in symbols) {
                val id = pieceToId[symbol]
                if (id != null) {
                    tokenIds.add(id)
                } else {
                    for (byte in symbol.toByteArray(Charsets.UTF_8)) {
                        val hex = "%02X".format(byte)
                        val byteToken = "<0x$hex>"
                        val byteId = pieceToId[byteToken]
                        if (byteId != null) {
                            tokenIds.add(byteId)
                        } else {
                            tokenIds.add(3) // <unk>
                        }
                    }
                }
            }
            return tokenIds
        }
    }


}
