package com.example.core.search

import com.example.core.embeddings.EmbeddingEngine
import com.example.data.database.DocDao
import com.example.data.database.DocumentEntity
import java.util.Locale

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

    private val vectorIndex = VectorIndex()

    @Volatile
    private var indexedVectorCount = -1

    /** Rebuilds the in-memory ANN index from Room only when the stored embedding count changed. */
    private suspend fun ensureVectorIndex() {
        val currentCount = docDao.getEmbeddingCount()
        if (currentCount != indexedVectorCount) {
            val rows = docDao.getAllChunkVectorRows()
            vectorIndex.rebuild(rows.map { Triple(it.chunkId, it.documentId, it.vector) })
            indexedVectorCount = currentCount
        }
    }

    suspend fun search(
        query: String,
        enableSemantic: Boolean = true,
        enableOcr: Boolean = true
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val queryTerms = normalizedQuery.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }

        // 1. Semantic candidate retrieval: ANN top-K across the whole corpus (falls back to an
        // exact scan internally below VectorIndex's size threshold), reduced to a per-document best score.
        val semanticBestByDoc = mutableMapOf<Long, Float>()
        if (enableSemantic) {
            ensureVectorIndex()
            val queryVector = embeddingEngine.embed(query, isQuery = true)
            for (match in vectorIndex.findNearest(queryVector, VECTOR_CANDIDATE_K)) {
                val prevBest = semanticBestByDoc[match.documentId] ?: -1f
                if (match.score > prevBest) semanticBestByDoc[match.documentId] = match.score
            }
        }

        // 2. Bulk-fetch everything else needed for fusion scoring (no N+1 per-document queries).
        val allDocuments = docDao.getAllDocumentsSync()
        val chunksByDocument = docDao.getAllChunkTextRows().groupBy { it.documentId }
        val usageByDoc = docDao.getAllDocumentUsagesSync().associateBy { it.documentId }
        val ocrTextByDoc: Map<Long, String> = if (enableOcr) {
            docDao.getAllOcrSync()
                .groupBy { it.documentId }
                .mapValues { (_, ocrList) -> ocrList.joinToString(" ") { it.text } }
        } else emptyMap()

        val results = mutableListOf<SearchResult>()

        for (doc in allDocuments) {
            val fileNameLower = doc.fileName.lowercase(Locale.ROOT)
            val isFilenameMatch = fileNameLower.contains(normalizedQuery)
            var filenameScore = 0.0f

            if (isFilenameMatch) {
                // Exact phrase matches in the filename should be extremely prominent
                filenameScore = if (fileNameLower == normalizedQuery) 400.0f else 250.0f
            } else {
                var matchedTermsInFilename = 0
                for (term in queryTerms) {
                    if (fileNameLower.contains(term)) matchedTermsInFilename++
                }
                if (matchedTermsInFilename > 0) filenameScore = matchedTermsInFilename * 50.0f
            }

            val docChunks = chunksByDocument[doc.id] ?: emptyList()

            var bestChunkText = ""
            var bestChunkKeywordScore = 0.0f
            val matchedUniqueTermsInDoc = mutableSetOf<String>()
            var hasExactPhraseMatchInChunk = false

            for (chunk in docChunks) {
                val chunkTextLower = chunk.text.lowercase(Locale.ROOT)

                if (chunkTextLower.contains(normalizedQuery)) {
                    hasExactPhraseMatchInChunk = true
                }

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
                        bestChunkText = chunk.text
                    }
                }
            }

            val bestSemanticScore = semanticBestByDoc[doc.id] ?: 0.0f
            if (bestChunkText.isEmpty() && bestSemanticScore > 0f) {
                // No keyword-scoring chunk was found, but this doc surfaced via semantic search;
                // the ANN index doesn't track which chunk matched, so snippet from the first chunk.
                bestChunkText = docChunks.firstOrNull()?.text ?: ""
            }

            var keywordScore = bestChunkKeywordScore
            if (hasExactPhraseMatchInChunk) {
                keywordScore += 150.0f
            }
            keywordScore += matchedUniqueTermsInDoc.size * 20.0f

            val ocrText = ocrTextByDoc[doc.id] ?: ""
            val ocrTextLower = ocrText.lowercase(Locale.ROOT)
            val isOcrMatch = ocrTextLower.contains(normalizedQuery)
            val ocrScore = if (isOcrMatch) {
                120.0f
            } else {
                var ocrHits = 0
                for (term in queryTerms) {
                    if (ocrTextLower.contains(term)) ocrHits++
                }
                minOf(ocrHits * 20.0f, 80.0f)
            }

            val usage = usageByDoc[doc.id]
            val openCount = usage?.openCount ?: 0
            val lastOpened = usage?.lastOpened ?: 0L
            val popularityScore = minOf(openCount * 4.0f, 30.0f)

            val ageMs = System.currentTimeMillis() - doc.modifiedAt
            val ageDays = (ageMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0)
            val recencyScore = if (ageDays < 7) 15.0f else if (ageDays < 30) 7.0f else 0.0f

            // Combined Score Calculation.
            // arctic-embed-s cosines (query-prefixed vs. unprefixed chunks) empirically cluster
            // ~0.60-0.71 for relevant pairs and ~0.29-0.48 for unrelated ones - see the gate
            // constants below for the calibration this was derived from.
            var semanticWeight = 0.0f
            if (bestSemanticScore > SEMANTIC_WEIGHT_GATE) {
                semanticWeight = bestSemanticScore * 180.0f
                if (bestSemanticScore > SEMANTIC_HIGH_GATE) {
                    semanticWeight += 80.0f
                }
                if (bestSemanticScore > SEMANTIC_VERY_HIGH_GATE) {
                    semanticWeight += 120.0f
                }
            }

            val finalScore = filenameScore + keywordScore + ocrScore + semanticWeight + popularityScore + recencyScore

            val snippet = generateSnippet(bestChunkText.ifEmpty { ocrText.ifEmpty { doc.fileName } }, queryTerms)

            val hasDirectMatch = isFilenameMatch || matchedUniqueTermsInDoc.isNotEmpty() || isOcrMatch || ocrScore > 0.0f
            val hasHighSemanticSimilarity = bestSemanticScore > SEMANTIC_MATCH_GATE

            if (hasDirectMatch || hasHighSemanticSimilarity) {
                results.add(
                    SearchResult(
                        document = doc,
                        score = finalScore,
                        snippet = snippet,
                        matchedQueryTerms = queryTerms,
                        isSemanticMatch = bestSemanticScore > SEMANTIC_LABEL_GATE,
                        isFilenameMatch = isFilenameMatch,
                        isOcrMatch = isOcrMatch || ocrScore > 0.0f,
                        openCount = openCount,
                        lastOpened = lastOpened
                    )
                )
            }
        }

        return results.sortedByDescending { it.score }
    }

    private fun generateSnippet(text: String, queryTerms: List<String>): String {
        if (text.isBlank()) return ""
        val lowercaseText = text.lowercase(Locale.ROOT)

        var matchIndex = -1
        for (term in queryTerms) {
            val idx = lowercaseText.indexOf(term)
            if (idx != -1) {
                matchIndex = idx
                break
            }
        }

        if (matchIndex == -1) {
            return if (text.length > 120) text.substring(0, 117) + "..." else text
        }

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

    companion object {
        // ANN candidate pool: generously larger than expected chunk counts for a personal document
        // corpus, so a document's single best-matching chunk is very unlikely to be excluded.
        private const val VECTOR_CANDIDATE_K = 200

        // Recalibrated for arctic-embed-s (previous values were tuned for EmbeddingGemma's score
        // distribution, which doesn't transfer - different model, different embedding space).
        // Derived from a small labeled set (5 relevant / 6 irrelevant query-chunk pairs drawn from
        // this corpus's actual content) run through the real model+tokenizer on the JVM:
        // relevant scores ranged 0.60-0.71 (avg 0.65), irrelevant ranged 0.29-0.48 (avg 0.42) -
        // a clean +0.12 gap between the relevant floor and irrelevant ceiling. Gates below sit with
        // margin around that boundary rather than hugging it, since the calibration set is small.
        private const val SEMANTIC_WEIGHT_GATE = 0.48f
        private const val SEMANTIC_MATCH_GATE = 0.52f
        private const val SEMANTIC_LABEL_GATE = 0.52f
        private const val SEMANTIC_HIGH_GATE = 0.60f
        private const val SEMANTIC_VERY_HIGH_GATE = 0.66f
    }
}
