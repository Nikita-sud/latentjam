/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * In-memory [IndexStore] for tests: remembers the last [save] per model
 * version and serves it back from [load], with call counters for interaction
 * asserts.
 */
internal class FakeIndexStore : IndexStore {

    val snapshots = mutableMapOf<String, Map<TrackId, FloatArray>>()
    var loadCalls: Int = 0
    var saveCalls: Int = 0

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? {
        loadCalls++
        return snapshots[modelVersion]
    }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
        saveCalls++
        snapshots[modelVersion] = entries
    }

    override suspend fun clear() {
        snapshots.clear()
    }
}
