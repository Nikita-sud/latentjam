/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

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
 */
public interface IndexStore {

    /** The snapshot for [modelVersion], or `null` if none is usable. */
    public suspend fun load(modelVersion: String): Map<TrackId, FloatArray>?

    /** Atomically replaces the snapshot for [modelVersion]. */
    public suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>)

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
