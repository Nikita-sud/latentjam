/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * One nearest-neighbor hit.
 *
 * @property trackId The matched track.
 * @property score Cosine similarity to the query vector, in `[-1, 1]`.
 */
public data class ScoredTrack(
    public val trackId: TrackId,
    public val score: Float,
)

/**
 * Storage + nearest-neighbor lookup for track embeddings.
 *
 * Port interface so the storage strategy can evolve (in-memory today, a
 * persisted / quantized / ANN-accelerated index later) without touching the
 * engine. Implementations are NOT required to be thread-safe — the engine
 * serializes all access on its background dispatcher.
 */
public interface VectorIndex {

    /** Number of vectors currently stored. */
    public val size: Int

    /**
     * Inserts or replaces the vector for [id]. Implementations may normalize
     * or copy the input; the caller must not rely on aliasing.
     *
     * @throws IllegalArgumentException if the vector has the wrong dimension.
     */
    public fun upsert(id: TrackId, vector: FloatArray)

    /**
     * [upsert] for a [vector] the caller hands over and never touches again — a row just decoded
     * from a snapshot — so an implementation may keep that array instead of a copy. What is stored
     * is exactly what [upsert] would store. Restoring a library this way holds one copy of its
     * vectors rather than two at the peak.
     *
     * @throws IllegalArgumentException if the vector has the wrong dimension.
     */
    public fun adopt(id: TrackId, vector: FloatArray): Unit = upsert(id, vector)

    /**
     * Returns a copy of the stored vector for [id], or `null` if absent.
     * Note: implementations may store vectors normalized; the returned array
     * is whatever [nearest] would compare against.
     */
    public fun vector(id: TrackId): FloatArray?

    /** Whether a vector is stored for [id] (no copying, unlike [vector]). */
    public operator fun contains(id: TrackId): Boolean

    /**
     * Snapshot of all stored entries (defensive copies) — a second copy of the whole index.
     * Vectors are in stored (normalized) form.
     */
    public fun entries(): Map<TrackId, FloatArray>

    /** The ids of every stored vector, copying none of the vectors. */
    public fun ids(): Set<TrackId> = entries().keys

    /**
     * Every stored entry in stored form, for a caller that only reads it before the index next
     * changes — writing a checkpoint or building a SMART snapshot under the engine's lock. An
     * implementation may hand out its own arrays and map instead of [entries]' copies: never modify
     * them, or keep them.
     */
    public fun storedEntries(): Map<TrackId, FloatArray> = entries()

    /** Removes [id], returning true only when a vector was present. */
    public fun remove(id: TrackId): Boolean

    /**
     * The `k` most cosine-similar stored vectors to [query], best first,
     * skipping every id in [exclude]. The query does not need to be
     * normalized. `k <= 0` returns an empty list.
     *
     * @throws IllegalArgumentException if the query has the wrong dimension.
     */
    public fun nearest(query: FloatArray, k: Int, exclude: Set<TrackId> = emptySet()): List<ScoredTrack>

    /** Removes all stored vectors. */
    public fun clear()
}
