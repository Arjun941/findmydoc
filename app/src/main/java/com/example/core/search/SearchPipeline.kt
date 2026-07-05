package com.example.core.search

import com.example.data.database.DocDao
import com.example.data.database.DocumentEntity
import com.example.core.embeddings.EmbeddingEngine
import java.util.Locale
import kotlin.math.sqrt

data class SearchResult(
    val document: DocumentEntity,
    val score: Float,
    val snippet: String,
    val matchedQueryTerms: List<String>,
    val isSemanticMatch: Boolean,
    val isFilenameMatch: Boolean,
    val isOcrMatch: Boolean,
    val openCount: Int = 0,
    val lastOpened: Long = 0
)

class SearchPipeline(
    private val docDao: DocDao,
    private val embeddingEngine: EmbeddingEngine
) {

    suspend fun search(
        query: String,
        enableSemantic: Boolean = true,
        enableOcr: Boolean = true
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val queryTerms = normalizedQuery.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }

        // 1. Generate query embedding
        val queryVector = if (enableSemantic) embeddingEngine.embed(query, isQuery = true) else null

        // 2. Fetch all required data from DB
        val allDocuments = docDao.getAllDocumentsSync()
        val allChunksWithEmbeddings = if (enableSemantic) docDao.getAllChunksWithEmbeddings() else emptyList()

        // Fetch usage analytics
        val allUsages = mutableMapOf<Long, Pair<Int, Long>>() // docId -> (openCount, lastOpened)
        // Since we can query database, let's fetch usages or build custom map
        for (doc in allDocuments) {
            val usage = docDao.getDocumentUsage(doc.id)
            if (usage != null) {
                allUsages[doc.id] = Pair(usage.openCount, usage.lastOpened)
            }
        }

        // Fetch OCR caches if needed
        val allOcrTexts = mutableMapOf<Long, String>() // docId -> combinedOcrText
        if (enableOcr) {
            for (doc in allDocuments) {
                val ocrList = docDao.getOcrForDocument(doc.id)
                if (ocrList.isNotEmpty()) {
                    allOcrTexts[doc.id] = ocrList.joinToString(" ") { it.text }
                }
            }
        }

        val results = mutableListOf<SearchResult>()

        for (doc in allDocuments) {
            val fileNameLower = doc.fileName.lowercase(Locale.ROOT)
            val isFilenameMatch = fileNameLower.contains(normalizedQuery)
            var filenameScore = 0.0f

            if (isFilenameMatch) {
                // Exact phrase matches in the filename should be extremely prominent
                filenameScore = if (fileNameLower == normalizedQuery) 400.0f else 250.0f
            } else {
                // Check if individual query terms appear in the filename
                var matchedTermsInFilename = 0
                for (term in queryTerms) {
                    if (fileNameLower.contains(term)) {
                        matchedTermsInFilename++
                    }
                }
                if (matchedTermsInFilename > 0) {
                    filenameScore = matchedTermsInFilename * 50.0f
                }
            }

            // Keyword text match score (matching text in chunks)
            val docChunks = if (enableSemantic) {
                allChunksWithEmbeddings.filter { it.documentId == doc.id }
            } else {
                docDao.getChunksForDocument(doc.id).map { chunk ->
                    com.example.data.database.ChunkWithEmbedding(
                        chunkId = chunk.id,
                        documentId = chunk.documentId,
                        text = chunk.text,
                        order = chunk.order,
                        vector = FloatArray(0)
                    )
                }
            }

            var bestSemanticScore = 0.0f
            var bestChunkText = ""
            
            // To prevent density bias where long documents gather high scores,
            // we look for the single best matching chunk (highest density of terms)
            var bestChunkKeywordScore = 0.0f
            val matchedUniqueTermsInDoc = mutableSetOf<String>()
            var hasExactPhraseMatchInChunk = false

            for (chunk in docChunks) {
                // Compute semantic similarity if query vector is available
                if (queryVector != null && chunk.vector.isNotEmpty()) {
                    val sim = cosineSimilarity(queryVector, chunk.vector)
                    if (sim > bestSemanticScore) {
                        bestSemanticScore = sim
                        bestChunkText = chunk.text
                    }
                }

                val chunkTextLower = chunk.text.lowercase(Locale.ROOT)
                
                // Track exact phrase match in a chunk
                if (chunkTextLower.contains(normalizedQuery)) {
                    hasExactPhraseMatchInChunk = true
                }

                // Keyword hits in chunk
                var hits = 0
                for (term in queryTerms) {
                    if (chunkTextLower.contains(term)) {
                        hits++
                        matchedUniqueTermsInDoc.add(term)
                    }
                }
                
                if (hits > 0) {
                    val chunkKeywordScore = hits * 35.0f
                    if (chunkKeywordScore > bestChunkKeywordScore) {
                        bestChunkKeywordScore = chunkKeywordScore
                        if (bestChunkText.isEmpty()) {
                            bestChunkText = chunk.text
                        }
                    }
                }
            }

            // Keyword score consists of:
            // 1. Score from the single best matching chunk
            // 2. Exact phrase bonus
            // 3. Document-wide unique term matching bonus (rewards finding more search terms)
            var keywordScore = bestChunkKeywordScore
            if (hasExactPhraseMatchInChunk) {
                keywordScore += 150.0f
            }
            keywordScore += matchedUniqueTermsInDoc.size * 20.0f

            // OCR matches
            val ocrText = allOcrTexts[doc.id] ?: ""
            val ocrTextLower = ocrText.lowercase(Locale.ROOT)
            val isOcrMatch = ocrTextLower.contains(normalizedQuery)
            var ocrScore = 0.0f
            
            if (isOcrMatch) {
                ocrScore = 120.0f // exact phrase in OCR
            } else {
                var ocrHits = 0
                for (term in queryTerms) {
                    if (ocrTextLower.contains(term)) {
                        ocrHits++
                    }
                }
                ocrScore = minOf(ocrHits * 20.0f, 80.0f)
            }

            // Popularity & Recency score (Should act as subtle tie-breakers, NOT override accurate keyword/semantic matches)
            val (openCount, lastOpened) = allUsages[doc.id] ?: Pair(0, 0L)
            val popularityScore = minOf(openCount * 4.0f, 30.0f)

            // Recency score (days since modified)
            val ageMs = System.currentTimeMillis() - doc.modifiedAt
            val ageDays = (ageMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
            val recencyScore = if (ageDays < 7) 15.0f else if (ageDays < 30) 7.0f else 0.0f

            // Combined Score Calculation
            // Scale semantic score (usually between 0.3 and 0.8)
            var semanticWeight = 0.0f
            if (bestSemanticScore > 0.35f) {
                semanticWeight = bestSemanticScore * 180.0f
                // Add confidence-graded neural boosts for highly accurate conceptual matches
                if (bestSemanticScore > 0.50f) {
                    semanticWeight += 80.0f
                }
                if (bestSemanticScore > 0.65f) {
                    semanticWeight += 120.0f
                }
            }

            val finalScore = filenameScore + keywordScore + ocrScore + semanticWeight + popularityScore + recencyScore

            // Generate Match Snippet
            val snippet = generateSnippet(bestChunkText.ifEmpty { ocrText.ifEmpty { doc.fileName } }, queryTerms)

            // Only include in search results if there is clear relevance:
            // Either a keyword match, a filename match, an OCR match, or a reasonable semantic similarity
            val hasDirectMatch = isFilenameMatch || matchedUniqueTermsInDoc.isNotEmpty() || isOcrMatch || ocrScore > 0.0f
            val hasHighSemanticSimilarity = bestSemanticScore > 0.38f

            if (hasDirectMatch || hasHighSemanticSimilarity) {
                results.add(
                    SearchResult(
                        document = doc,
                        score = finalScore,
                        snippet = snippet,
                        matchedQueryTerms = queryTerms,
                        isSemanticMatch = bestSemanticScore > 0.42f,
                        isFilenameMatch = isFilenameMatch,
                        isOcrMatch = isOcrMatch || ocrScore > 0.0f,
                        openCount = openCount,
                        lastOpened = lastOpened
                    )
                )
            }
        }

        // Sort by final score descending
        return results.sortedByDescending { it.score }
    }

    private fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vecA.indices) {
            dotProduct += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-6) (dotProduct / denom).getOrElseAndFallback(0.0f) else 0.0f
    }

    private fun Float.getOrElseAndFallback(fallback: Float): Float {
        return if (this.isNaN() || this.isInfinite()) fallback else this
    }

    private fun generateSnippet(text: String, queryTerms: List<String>): String {
        if (text.isBlank()) return ""
        val lowercaseText = text.lowercase(Locale.ROOT)

        // Find the index of the first matching query term
        var matchIndex = -1
        for (term in queryTerms) {
            val idx = lowercaseText.indexOf(term)
            if (idx != -1) {
                matchIndex = idx
                break
            }
        }

        if (matchIndex == -1) {
            // Return first 120 characters
            return if (text.length > 120) text.substring(0, 117) + "..." else text
        }

        // Frame a window around the match
        val start = (matchIndex - 50).coerceAtLeast(0)
        val end = (matchIndex + 100).coerceAtMost(text.length)

        var snippet = text.substring(start, end)
        if (start > 0) {
            snippet = "..." + snippet
        }
        if (end < text.length) {
            snippet = snippet + "..."
        }
        return snippet
    }
}
