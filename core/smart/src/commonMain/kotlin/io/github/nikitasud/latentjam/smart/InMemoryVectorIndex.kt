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
    /**
     * Where the rows live. The heap store keeps the arrays themselves, as this index always did; Android passes a
     * memory-mapped one so the library's vectors stay off the Java heap. The floats stored are the same either way.
     */
    private val store: RowStore = HeapRowStore(dim),
) : VectorIndex {

    init {
        require(dim > 0) { "Embedding dimension must be positive, got $dim" }
        require(store.dim == dim) { "Row store dimension ${store.dim} does not match $dim" }
    }

    /** Row of each id in [store]. LinkedHashMap keeps iteration deterministic (insertion order). */
    private val vectors = LinkedHashMap<TrackId, Int>()

    /** Rows of [store] no id refers to any more (removed or replaced ids); reclaimed by [compactIfSparse]. */
    private var deadRows = 0

    override val size: Int
        get() = vectors.size

    override fun upsert(id: TrackId, vector: FloatArray) {
        validate(id, vector)
        put(id, l2Normalized(vector))
    }

    /** Normalizes the handed-over array in place, with exactly [upsert]'s arithmetic, and keeps it. */
    override fun adopt(id: TrackId, vector: FloatArray) {
        validate(id, vector)
        put(id, normalizeInPlace(vector))
    }

    private fun put(id: TrackId, normalized: FloatArray) {
        // A replaced id gets a new row (stored rows are never rewritten); it keeps its place in iteration order.
        val old = vectors.put(id, store.append(normalized))
        if (old != null) {
            store.release(old)
            deadRows++
            compactIfSparse()
        }
    }

    override fun vector(id: TrackId): FloatArray? = vectors[id]?.let { store.row(it).toFloatArray() }

    override fun contains(id: TrackId): Boolean = id in vectors

    override fun entries(): Map<TrackId, FloatArray> =
        vectors.entries.associate { (id, row) -> id to store.row(row).toFloatArray() }

    override fun ids(): Set<TrackId> = vectors.keys.toSet()

    /**
     * A read-only view in iteration order. Over the heap store its values are the stored arrays themselves; over
     * an off-heap store each value read is a fresh copy of one row, so iterating it (a checkpoint) never holds more
     * than the row being read.
     */
    override fun storedEntries(): Map<TrackId, FloatArray> = storedView

    private val storedView: Map<TrackId, FloatArray> = object : AbstractMap<TrackId, FloatArray>() {
        override val size: Int get() = vectors.size
        override fun containsKey(key: TrackId): Boolean = key in vectors
        override fun get(key: TrackId): FloatArray? = vectors[key]?.let(::stored)
        override val entries: Set<Map.Entry<TrackId, FloatArray>>
            get() = object : AbstractSet<Map.Entry<TrackId, FloatArray>>() {
                override val size: Int get() = vectors.size
                override fun iterator(): Iterator<Map.Entry<TrackId, FloatArray>> {
                    val source = vectors.entries.iterator()
                    return object : Iterator<Map.Entry<TrackId, FloatArray>> {
                        override fun hasNext(): Boolean = source.hasNext()
                        override fun next(): Map.Entry<TrackId, FloatArray> {
                            val (id, row) = source.next()
                            return Entry(id, stored(row))
                        }
                    }
                }
            }
    }

    private class Entry(override val key: TrackId, override val value: FloatArray) : Map.Entry<TrackId, FloatArray>

    private fun stored(row: Int): FloatArray = store.heapArray(row) ?: store.row(row).toFloatArray()

    override fun storedRow(id: TrackId): StoredVectorRow? = vectors[id]?.let(store::row)

    override fun remove(id: TrackId): Boolean {
        val row = vectors.remove(id) ?: return false
        store.release(row)
        deadRows++
        compactIfSparse()
        return true
    }

    /** Rewrites the store without the rows of removed ids once they outnumber the live ones (and 1,024). */
    private fun compactIfSparse() {
        if (deadRows < 1_024 || deadRows < vectors.size) return
        val live = IntArray(vectors.size)
        var i = 0
        for (entry in vectors.entries) {
            live[i] = entry.value
            entry.setValue(i)
            i++
        }
        store.compact(live)
        deadRows = 0
    }

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
        val scratch = FloatArray(dim)
        for ((ordinal, entry) in vectors.entries.withIndex()) {
            if (entry.key in exclude) continue
            val values = store.heapArray(entry.value) ?: scratch.also { store.row(entry.value).copyInto(it) }
            val score = dot(normalizedQuery, values)
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
        store.clear()
        deadRows = 0
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
