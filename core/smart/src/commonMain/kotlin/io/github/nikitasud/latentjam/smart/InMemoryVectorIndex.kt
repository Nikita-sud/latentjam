/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.sqrt

/**
 * Brute-force, in-memory [VectorIndex].
 *
 * Vectors are L2-normalized once on [upsert], so a nearest-neighbor query is
 * a single dot product per stored vector. Exact and dependency-free — the
 * right baseline for libraries up to a few tens of thousands of tracks; swap
 * in an ANN- or disk-backed implementation behind [VectorIndex] when profiling
 * says so, without touching the engine.
 *
 * Zero vectors (a defensive edge case — a real encoder never emits one) are
 * stored as-is and simply score `0` against everything rather than crashing
 * on division by zero.
 *
 * A stored array is never written again: [upsert] stores a fresh copy and [adopt] normalises the
 * handed-over array before storing it, so the arrays [storedEntries] hands out stay as they are.
 *
 * Not thread-safe by design; see [VectorIndex] for the serialization contract.
 *
 * @param dim Required dimensionality of every stored and queried vector.
 */
public class InMemoryVectorIndex(
    private val dim: Int,
) : VectorIndex {

    init {
        require(dim > 0) { "Embedding dimension must be positive, got $dim" }
    }

    /** LinkedHashMap keeps iteration deterministic (insertion order). */
    private val vectors = LinkedHashMap<TrackId, FloatArray>()

    override val size: Int
        get() = vectors.size

    override fun upsert(id: TrackId, vector: FloatArray) {
        validate(id, vector)
        vectors[id] = l2Normalized(vector)
    }

    /** Normalizes the handed-over array in place, with exactly [upsert]'s arithmetic, and keeps it. */
    override fun adopt(id: TrackId, vector: FloatArray) {
        validate(id, vector)
        vectors[id] = normalizeInPlace(vector)
    }

    override fun vector(id: TrackId): FloatArray? = vectors[id]?.copyOf()

    override fun contains(id: TrackId): Boolean = id in vectors

    override fun entries(): Map<TrackId, FloatArray> =
        vectors.entries.associate { (id, vector) -> id to vector.copyOf() }

    override fun ids(): Set<TrackId> = vectors.keys.toSet()

    override fun storedEntries(): Map<TrackId, FloatArray> = vectors

    override fun remove(id: TrackId): Boolean = vectors.remove(id) != null

    override fun nearest(query: FloatArray, k: Int, exclude: Set<TrackId>): List<ScoredTrack> {
        require(query.size == dim) {
            "Query vector has dimension ${query.size}, expected $dim"
        }
        require(query.all(Float::isFinite)) { "Query contains a non-finite component" }
        if (k <= 0 || vectors.isEmpty()) return emptyList()
        val normalizedQuery = l2Normalized(query)
        // A worst-first heap keeps only k candidates. The ordinal makes ties agree with the
        // old stable sort, including upserts (which retain their LinkedHashMap position).
        val limit = minOf(k, vectors.size)
        val heap = ArrayList<RankedTrack>(limit)
        for ((ordinal, entry) in vectors.entries.withIndex()) {
            if (entry.key in exclude) continue
            val score = dot(normalizedQuery, entry.value)
            if (heap.size == limit && score.compareTo(heap[0].track.score) <= 0) continue
            val candidate = RankedTrack(ScoredTrack(entry.key, score), ordinal)
            if (heap.size < limit) {
                heap.add(candidate)
                var child = heap.lastIndex
                while (child > 0) {
                    val parent = (child - 1) / 2
                    if (rank.compare(heap[child], heap[parent]) <= 0) break
                    val swap = heap[parent]
                    heap[parent] = heap[child]
                    heap[child] = swap
                    child = parent
                }
            } else {
                heap[0] = candidate
                var parent = 0
                while (parent * 2 + 1 < heap.size) {
                    var worst = parent * 2 + 1
                    if (worst + 1 < heap.size && rank.compare(heap[worst + 1], heap[worst]) > 0) worst++
                    if (rank.compare(heap[worst], heap[parent]) <= 0) break
                    val swap = heap[parent]
                    heap[parent] = heap[worst]
                    heap[worst] = swap
                    parent = worst
                }
            }
        }
        return heap.sortedWith(rank).map { it.track }
    }

    private data class RankedTrack(val track: ScoredTrack, val ordinal: Int)

    private val rank = compareByDescending<RankedTrack> { it.track.score }.thenBy { it.ordinal }

    override fun clear() {
        vectors.clear()
    }

    private fun validate(id: TrackId, vector: FloatArray) {
        require(vector.size == dim) {
            "Vector for ${id.value} has dimension ${vector.size}, expected $dim"
        }
        require(vector.all(Float::isFinite)) {
            "Vector for ${id.value} contains a non-finite component"
        }
    }

    /** Returns a fresh unit-length copy of [vector]; zero vectors pass through unchanged. */
    private fun l2Normalized(vector: FloatArray): FloatArray = normalizeInPlace(vector.copyOf())

    /** Scales [vector] itself to unit length and returns it; a zero vector stays as it is. */
    private fun normalizeInPlace(vector: FloatArray): FloatArray {
        var sumOfSquares = 0f
        for (component in vector) sumOfSquares += component * component
        val norm = sqrt(sumOfSquares)
        if (norm == 0f) return vector
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var accumulator = 0f
        for (i in a.indices) accumulator += a[i] * b[i]
        return accumulator
    }
}
