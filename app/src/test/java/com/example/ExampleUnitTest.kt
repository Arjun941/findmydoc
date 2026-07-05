package com.example

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

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
            if (b == -1) throw java.io.EOFException()
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
        if ((b0 or b1 or b2 or b3) < 0) throw java.io.EOFException()
        val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return Float.fromBits(bits)
    }

    private fun skipField(stream: InputStream, wireType: Int) {
        when (wireType) {
            0 -> { readVarint(stream) } // varint
            1 -> { stream.skip(8) }     // 64-bit
            2 -> {                      // length-delimited
                val len = readVarint(stream)
                stream.skip(len)
            }
            5 -> { stream.skip(4) }     // 32-bit
            else -> throw IllegalArgumentException("Unknown wire type: $wireType")
        }
    }

    private fun parseSentencePiece(bytes: ByteArray): SentencePieceEntry {
        var piece = ""
        var score = 0f
        var type = 1
        
        val stream = java.io.ByteArrayInputStream(bytes)
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            
            when (fieldNumber) {
                1 -> { // piece: string
                    val len = readVarint(stream).toInt()
                    val strBytes = ByteArray(len)
                    var readLen = 0
                    while (readLen < len) {
                        val r = stream.read(strBytes, readLen, len - readLen)
                        if (r == -1) throw java.io.EOFException()
                        readLen += r
                    }
                    piece = String(strBytes, Charsets.UTF_8)
                }
                2 -> { // score: float
                    score = readFloat(stream)
                }
                3 -> { // type: enum
                    type = readVarint(stream).toInt()
                }
                else -> {
                    skipField(stream, wireType)
                }
            }
        }
        return SentencePieceEntry(piece, score, type)
    }

    fun parseSentencePieceModel(inputStream: InputStream): List<SentencePieceEntry> {
        val pieces = mutableListOf<SentencePieceEntry>()
        val buffered = inputStream.buffered()
        
        while (true) {
            val tag = try {
                readVarint(buffered)
            } catch (e: java.io.EOFException) {
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
                    if (read == -1) throw java.io.EOFException()
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

    @Test
    fun testParseSentencePieceModel() {
        val file = File("src/test/resources/sentencepiece.model")
        assertTrue("Model file should exist at ${file.absolutePath}", file.exists())
        
        FileInputStream(file).use { fis ->
            val pieces = parseSentencePieceModel(fis)
            println("Total parsed pieces: ${pieces.size}")
            assertTrue("Should parse thousands of pieces", pieces.size > 200000)
            
            println("First 20 parsed tokens:")
            for (i in 0 until minOf(20, pieces.size)) {
                val p = pieces[i]
                println("ID $i: piece='${p.piece}', score=${p.score}, type=${p.type}")
            }

            // Create tokenizer
            val tokenizer = SentencePieceTokenizer(pieces)
            val sampleText = "Hello, world! This is FindMyDoc offline search."
            val tokens = tokenizer.tokenize(sampleText)
            println("Sample text: \"$sampleText\"")
            println("Token IDs: $tokens")
            println("Token pieces: ${tokens.map { pieces[it].piece }}")
            assertTrue("Tokens should be non-empty", tokens.isNotEmpty())
        }
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

                // Merge the best pair
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
                    // Fallback to byte fallback or unk
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




