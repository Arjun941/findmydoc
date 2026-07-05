package com.example.core.indexing

data class DocumentChunk(
    val text: String,
    val order: Int
)

object Chunker {

    /**
     * Splits document text into chunks of 300 to 400 tokens (estimated or counted),
     * with overlap, aligning with paragraphs and sentences first to avoid mid-sentence cuts.
     */
    fun chunk(text: String, maxTokens: Int = 350, overlapTokens: Int = 45): List<DocumentChunk> {
        if (text.isBlank()) return emptyList()

        val separators = listOf("\n\n", "\n", ". ", "? ", "! ", " ")
        
        fun countTokens(str: String): Int {
            // Words * 1.3 is a reliable heuristic for token count
            val words = str.split(Regex("\\s+")).count { it.isNotEmpty() }
            return (words * 1.3).toInt()
        }

        fun splitBy(piece: String, separator: String, limit: Int): List<String> {
            val parts = piece.split(separator)
            val result = mutableListOf<String>()
            var current = ""
            for (part in parts) {
                val partWithSep = if (current.isEmpty()) part else current + separator + part
                if (countTokens(partWithSep) <= limit) {
                    current = partWithSep
                } else {
                    if (current.isNotEmpty()) {
                        result.add(current)
                    }
                    current = part
                }
            }
            if (current.isNotEmpty()) {
                result.add(current)
            }
            return result
        }

        fun recursiveChunk(input: String): List<String> {
            var chunks = listOf(input)
            for (sep in separators) {
                val newChunks = mutableListOf<String>()
                var changed = false
                for (c in chunks) {
                    if (countTokens(c) > maxTokens) {
                        val splits = splitBy(c, sep, maxTokens)
                        newChunks.addAll(splits)
                        changed = true
                    } else {
                        newChunks.add(c)
                    }
                }
                chunks = newChunks
                if (!changed) break
            }
            return chunks
        }

        val splitChunks = recursiveChunk(text)

        // Post-processing: Add overlap (10-15% overlap)
        val overlappedChunks = mutableListOf<String>()
        for (i in splitChunks.indices) {
            val currentChunk = splitChunks[i]
            if (i == 0) {
                overlappedChunks.add(currentChunk)
            } else {
                val prevChunk = splitChunks[i - 1]
                val prevWords = prevChunk.split(Regex("\\s+")).filter { it.isNotEmpty() }
                
                // Determine how many words to overlap (10-15% of chunk size)
                val overlapWordCount = (overlapTokens / 1.3).toInt().coerceAtMost(prevWords.size / 4).coerceAtLeast(5)
                
                if (overlapWordCount > 0 && prevWords.size >= overlapWordCount) {
                    val overlapText = prevWords.subList(prevWords.size - overlapWordCount, prevWords.size).joinToString(" ")
                    overlappedChunks.add("$overlapText\n$currentChunk")
                } else {
                    overlappedChunks.add(currentChunk)
                }
            }
        }

        // Filter out extremely short chunks (merge with neighbors if too small)
        val finalChunks = mutableListOf<String>()
        for (chunk in overlappedChunks) {
            val tokenCount = countTokens(chunk)
            if (tokenCount < 30 && finalChunks.isNotEmpty()) {
                val lastIdx = finalChunks.size - 1
                finalChunks[lastIdx] = finalChunks[lastIdx] + "\n" + chunk
            } else {
                finalChunks.add(chunk)
            }
        }

        return finalChunks.mapIndexed { idx, t -> DocumentChunk(t, idx) }
    }
}
