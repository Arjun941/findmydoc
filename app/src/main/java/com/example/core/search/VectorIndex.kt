package com.example.core.search

import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.random.Random

data class VectorMatch(val chunkId: Long, val documentId: Long, val score: Float)

/**
 * In-process approximate-nearest-neighbor index over unit-length (L2-normalized) embedding
 * vectors, using a compact HNSW (Hierarchical Navigable Small World) graph. Room remains the
 * durable source of truth for vectors; this index is a rebuildable in-memory accelerator built
 * from Room on first use and maintained incrementally as documents are indexed or removed.
 *
 * Below [exactSearchThreshold] chunks, search falls back to an exact brute-force scan — ANN error
 * is pointless at that scale, and exact search also serves as the recall oracle for testing.
 */
class VectorIndex(
    private val exactSearchThreshold: Int = 500,
    private val maxConnections: Int = 16,
    private val efConstruction: Int = 200,
    private val efSearchDefault: Int = 64
) {
    private class Node(val vector: FloatArray, val chunkId: Long, val documentId: Long)

    private val nodes = mutableListOf<Node>()
    private val deleted = HashSet<Int>()
    private val levels = mutableListOf<Int>()

    // neighbors[nodeId][layer] = neighbor node ids at that layer. A node id only ever appears at
    // layer L if that node's own level >= L (enforced at insertion time).
    private val neighbors = mutableListOf<MutableList<MutableList<Int>>>()

    private var entryPoint = -1
    private var maxLevel = -1
    private val levelMultiplier = 1.0 / ln(maxConnections.toDouble())
    private val random = Random(42)

    val size: Int get() = nodes.size - deleted.size

    @Synchronized
    fun clear() {
        nodes.clear()
        deleted.clear()
        levels.clear()
        neighbors.clear()
        entryPoint = -1
        maxLevel = -1
    }

    @Synchronized
    fun rebuild(vectors: List<Triple<Long, Long, FloatArray>>) {
        clear()
        for ((chunkId, documentId, vector) in vectors) {
            insertInternal(vector, chunkId, documentId)
        }
    }

    @Synchronized
    fun add(chunkId: Long, documentId: Long, vector: FloatArray) {
        insertInternal(vector, chunkId, documentId)
    }

    @Synchronized
    fun removeByChunkIds(chunkIds: Collection<Long>) {
        if (chunkIds.isEmpty()) return
        val idSet = chunkIds.toHashSet()
        for (i in nodes.indices) {
            if (nodes[i].chunkId in idSet) deleted.add(i)
        }
    }

    @Synchronized
    fun removeByDocumentIds(documentIds: Collection<Long>) {
        if (documentIds.isEmpty()) return
        val idSet = documentIds.toHashSet()
        for (i in nodes.indices) {
            if (nodes[i].documentId in idSet) deleted.add(i)
        }
    }

    @Synchronized
    fun findNearest(query: FloatArray, k: Int): List<VectorMatch> {
        if (nodes.isEmpty() || k <= 0) return emptyList()
        return if (size <= exactSearchThreshold) exactSearch(query, k) else hnswSearch(query, k)
    }

    private fun exactSearch(query: FloatArray, k: Int): List<VectorMatch> {
        val scored = ArrayList<Pair<Int, Float>>(nodes.size)
        for (i in nodes.indices) {
            if (i in deleted) continue
            scored.add(i to dot(query, nodes[i].vector))
        }
        return scored.sortedByDescending { it.second }.take(k).map { (id, score) ->
            VectorMatch(nodes[id].chunkId, nodes[id].documentId, score)
        }
    }

    private fun insertInternal(vector: FloatArray, chunkId: Long, documentId: Long): Int {
        val id = nodes.size
        nodes.add(Node(vector, chunkId, documentId))
        val level = randomLevel()
        levels.add(level)
        neighbors.add(MutableList(level + 1) { mutableListOf() })

        if (entryPoint == -1) {
            entryPoint = id
            maxLevel = level
            return id
        }

        var curr = entryPoint
        for (layer in maxLevel downTo level + 1) {
            curr = greedyDescend(vector, curr, layer)
        }

        for (layer in minOf(level, maxLevel) downTo 0) {
            val candidates = searchLayer(vector, curr, efConstruction, layer)
            val maxConn = if (layer == 0) maxConnections * 2 else maxConnections
            val selected = candidates.sortedBy { it.second }.take(maxConn).map { it.first }
            neighbors[id][layer].addAll(selected)

            for (neighborId in selected) {
                val neighborLayers = neighbors[neighborId]
                if (layer >= neighborLayers.size) continue
                val neighborList = neighborLayers[layer]
                neighborList.add(id)
                if (neighborList.size > maxConn) {
                    val neighborVector = nodes[neighborId].vector
                    val kept = neighborList
                        .map { it to distance(neighborVector, nodes[it].vector) }
                        .sortedBy { it.second }
                        .take(maxConn)
                        .map { it.first }
                    neighborList.clear()
                    neighborList.addAll(kept)
                }
            }

            curr = candidates.minByOrNull { it.second }?.first ?: curr
        }

        if (level > maxLevel) {
            maxLevel = level
            entryPoint = id
        }
        return id
    }

    private fun greedyDescend(query: FloatArray, start: Int, layer: Int): Int {
        var curr = start
        var currDist = distance(query, nodes[curr].vector)
        var improved = true
        while (improved) {
            improved = false
            val layerNeighbors = neighbors[curr].getOrNull(layer) ?: emptyList()
            for (candidate in layerNeighbors) {
                if (candidate in deleted) continue
                val d = distance(query, nodes[candidate].vector)
                if (d < currDist) {
                    currDist = d
                    curr = candidate
                    improved = true
                }
            }
        }
        return curr
    }

    /** Returns up to [ef] nodes closest to [query] reachable from [entryPoint] at [layer], as (nodeId, distance). */
    private fun searchLayer(query: FloatArray, entryPoint: Int, ef: Int, layer: Int): List<Pair<Int, Float>> {
        val visited = HashSet<Int>()
        visited.add(entryPoint)

        val candidateHeap = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
        val resultHeap = PriorityQueue<Pair<Int, Float>>(compareByDescending { it.second })

        val entryDist = distance(query, nodes[entryPoint].vector)
        candidateHeap.add(entryPoint to entryDist)
        if (entryPoint !in deleted) resultHeap.add(entryPoint to entryDist)

        while (candidateHeap.isNotEmpty()) {
            val (currId, currDist) = candidateHeap.poll()!!
            val worst = resultHeap.peek()
            if (worst != null && resultHeap.size >= ef && currDist > worst.second) break

            val layerNeighbors = neighbors[currId].getOrNull(layer) ?: emptyList()
            for (neighborId in layerNeighbors) {
                if (!visited.add(neighborId)) continue
                val d = distance(query, nodes[neighborId].vector)
                val w = resultHeap.peek()
                if (resultHeap.size < ef || w == null || d < w.second) {
                    candidateHeap.add(neighborId to d)
                    if (neighborId !in deleted) {
                        resultHeap.add(neighborId to d)
                        if (resultHeap.size > ef) resultHeap.poll()
                    }
                }
            }
        }
        return resultHeap.toList()
    }

    private fun hnswSearch(query: FloatArray, k: Int): List<VectorMatch> {
        var curr = entryPoint
        for (layer in maxLevel downTo 1) {
            curr = greedyDescend(query, curr, layer)
        }
        val ef = maxOf(efSearchDefault, k)
        val candidates = searchLayer(query, curr, ef, 0)
        return candidates.sortedBy { it.second }.take(k).map { (id, dist) ->
            VectorMatch(nodes[id].chunkId, nodes[id].documentId, 1f - dist)
        }
    }

    private fun randomLevel(): Int {
        return (-ln(random.nextDouble().coerceAtLeast(1e-12)) * levelMultiplier).toInt()
    }

    private fun distance(a: FloatArray, b: FloatArray): Float = 1f - dot(a, b)

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }
}
