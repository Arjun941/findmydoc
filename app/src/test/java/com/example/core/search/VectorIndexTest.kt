package com.example.core.search

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIndexTest {

    private fun randomUnitVector(random: Random, dim: Int): FloatArray {
        val v = FloatArray(dim) { random.nextGaussian().toFloat() }
        var mag = 0f
        for (x in v) mag += x * x
        mag = sqrt(mag)
        if (mag > 1e-6f) for (i in v.indices) v[i] /= mag
        return v
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun bruteForceTopK(vectors: List<FloatArray>, query: FloatArray, k: Int): List<Int> =
        vectors.indices.sortedByDescending { dot(query, vectors[it]) }.take(k)

    @Test
    fun exactSearch_matchesBruteForce_belowThreshold() {
        val random = Random(7)
        val dim = 32
        val count = 100 // below the default exactSearchThreshold (500) -> exact-scan path
        val vectors = List(count) { randomUnitVector(random, dim) }

        val index = VectorIndex()
        index.rebuild(vectors.mapIndexed { i, v -> Triple(i.toLong(), i.toLong(), v) })

        val query = randomUnitVector(random, dim)
        val expected = bruteForceTopK(vectors, query, 10).toSet()
        val actual = index.findNearest(query, 10).map { it.chunkId.toInt() }.toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun hnswSearch_hasHighRecallAgainstBruteForce_aboveThreshold() {
        val random = Random(42)
        val dim = 64
        val count = 2000 // above the default exactSearchThreshold -> HNSW graph path
        val vectors = List(count) { randomUnitVector(random, dim) }

        val index = VectorIndex()
        index.rebuild(vectors.mapIndexed { i, v -> Triple(i.toLong(), i.toLong(), v) })

        val numQueries = 25
        val k = 10
        var totalRecall = 0.0
        repeat(numQueries) {
            val query = randomUnitVector(random, dim)
            val expected = bruteForceTopK(vectors, query, k).toSet()
            val actual = index.findNearest(query, k).map { it.chunkId.toInt() }.toSet()
            totalRecall += expected.intersect(actual).size.toDouble() / k
        }
        val avgRecall = totalRecall / numQueries

        assertTrue("Expected recall@$k >= 0.8 but was $avgRecall", avgRecall >= 0.8)
    }

    @Test
    fun removeByDocumentIds_excludesDeletedFromResults() {
        val random = Random(1)
        val dim = 16
        val vectors = List(50) { randomUnitVector(random, dim) }

        val index = VectorIndex()
        index.rebuild(vectors.mapIndexed { i, v -> Triple(i.toLong(), i.toLong(), v) })

        val query = vectors[0]
        val before = index.findNearest(query, 5)
        assertTrue(before.any { it.documentId == 0L })

        index.removeByDocumentIds(listOf(0L))
        val after = index.findNearest(query, 5)
        assertTrue(after.none { it.documentId == 0L })
    }
}
