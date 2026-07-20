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
        require(vector.size == dim) {
            "Vector for ${id.value} has dimension ${vector.size}, expected $dim"
        }
        vectors[id] = l2Normalized(vector)
    }

    override fun vector(id: TrackId): FloatArray? = vectors[id]?.copyOf()

    override fun contains(id: TrackId): Boolean = id in vectors

    override fun entries(): Map<TrackId, FloatArray> =
        vectors.entries.associate { (id, vector) -> id to vector.copyOf() }

    override fun remove(id: TrackId): Boolean = vectors.remove(id) != null

    override fun nearest(query: FloatArray, k: Int, exclude: Set<TrackId>): List<ScoredTrack> {
        require(query.size == dim) {
            "Query vector has dimension ${query.size}, expected $dim"
        }
        if (k <= 0 || vectors.isEmpty()) return emptyList()
        val normalizedQuery = l2Normalized(query)
        return vectors.asSequence()
            .filter { (id, _) -> id !in exclude }
            .map { (id, stored) -> ScoredTrack(trackId = id, score = dot(normalizedQuery, stored)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
    }

    override fun clear() {
        vectors.clear()
    }

    /** Returns a fresh unit-length copy of [vector]; zero vectors pass through unchanged. */
    private fun l2Normalized(vector: FloatArray): FloatArray {
        var sumOfSquares = 0f
        for (component in vector) sumOfSquares += component * component
        val norm = sqrt(sumOfSquares)
        val copy = vector.copyOf()
        if (norm == 0f) return copy
        for (i in copy.indices) copy[i] /= norm
        return copy
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var accumulator = 0f
        for (i in a.indices) accumulator += a[i] * b[i]
        return accumulator
    }
}
