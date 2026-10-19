/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * One durable vector snapshot plus the descriptor identity each vector was derived from.
 *
 * [identities] may be empty when reading the legacy vector-only format. The engine deliberately
 * treats such rows as cache misses on its first complete-library synchronization, preventing an
 * old vector from being trusted merely because its opaque [TrackId] still exists.
 *
 * [failedIdentities] remembers only track-local [EngineError.InvalidAudio] failures. The
 * value is the same content identity used for vectors, so replacing or editing the media makes
 * the marker stale and the engine tries that track again. Error details are deliberately not
 * persisted: paths and codec diagnostics belong in local logs, not in the cache.
 */
public data class StoredIndexSnapshot(
    public val entries: Map<TrackId, FloatArray>,
    public val identities: Map<TrackId, String> = emptyMap(),
    public val failedIdentities: Map<TrackId, String> = emptyMap(),
)

/**
 * Persistence port for the vector index, so embeddings survive process death
 * (re-embedding a whole library costs minutes of compute; loading a snapshot
 * costs milliseconds).
 *
 * Snapshots are keyed by the embedding model's version string: a snapshot
 * written by a different model version MUST NOT load (embeddings from
 * different models are not comparable). Implementations return `null` from
 * [load] on any mismatch, corruption, or absence — the engine then simply
 * starts empty and re-indexes.
 *
 * Called only from the engine's confined dispatcher; implementations need no
 * additional synchronization.
 *
 * Neither direction copies a library of vectors on the engine's behalf: a snapshot passed to
 * [saveSnapshot] may share the engine's live arrays, so a store serializes it before returning and
 * never keeps or modifies it; and the arrays a load returns belong to the caller, which adopts them
 * into its index ([VectorIndex.adopt]), so a store hands out fresh ones every time.
 */
public interface IndexStore {
    // Load contract: null = no usable snapshot (none, another version, damaged data: start over). A snapshot that could
    // not be READ now (out of memory, an I/O error) throws [IndexRestoreFailure] instead: the file may be fine, and the
    // audio index's caller neither starts over from empty nor saves over it (the metadata index, a quick deterministic
    // re-encode, is encoded again).

    /** The snapshot for [modelVersion], or `null` if none is usable. */
    public suspend fun load(modelVersion: String): Map<TrackId, FloatArray>?

    /** Identity-aware load. Existing stores default to a conservative legacy snapshot. */
    public suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? =
        load(modelVersion)?.let(::StoredIndexSnapshot)

    /**
     * Streams the snapshot for [modelVersion] one row at a time: [row] gets each id, a fresh array the receiver may
     * keep, and its identity. Returns the failed identities, or null when there is no usable snapshot — in which
     * case rows may already have been delivered (a store that streams finds damage late), and the receiver must
     * discard them. Restoring this way never holds the whole library's vectors as one map.
     */
    public suspend fun loadRows(
        modelVersion: String,
        row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
    ): Map<TrackId, String>? {
        val snapshot = loadSnapshot(modelVersion) ?: return null
        for ((id, vector) in snapshot.entries) row(id, vector, snapshot.identities[id])
        return snapshot.failedIdentities
    }

    /** Atomically replaces the snapshot for [modelVersion]. */
    public suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>)

    /**
     * Atomically replaces vectors and their identities. Existing custom stores may ignore the
     * metadata; doing so only costs re-indexing after restart, never stale-vector reuse.
     */
    public suspend fun saveSnapshot(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
    ): Unit = save(modelVersion, snapshot.entries)

    /** Deletes any snapshot. */
    public suspend fun clear()
}

/**
 * Default no-op store: nothing persists. Bound by the common Koin module and
 * overridden by platforms that have a real store (Android's file-backed one).
 */
public class NoopIndexStore : IndexStore {
    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? = null
    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit = Unit
    override suspend fun clear(): Unit = Unit
}

/**
 * A stored snapshot that could not be read now for a reason other than its content (out of memory, an I/O error).
 * Distinct from "no snapshot" (a load's null): for the audio index the file is kept, nothing is saved over it, and
 * the read is retried; the metadata index is encoded again.
 */
public class IndexRestoreFailure(cause: Throwable) : Exception("the stored index could not be read now", cause)
