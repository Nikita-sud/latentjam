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
    val identitySnapshots = mutableMapOf<String, Map<TrackId, String>>()
    var loadCalls: Int = 0
    var saveCalls: Int = 0
    var clearCalls: Int = 0
    var saveFailuresRemaining: Int = 0
    var clearFailuresRemaining: Int = 0
    var failClearAfterDeletion: Boolean = false

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? {
        loadCalls++
        return snapshots[modelVersion]
    }

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? {
        loadCalls++
        return snapshots[modelVersion]?.let { entries ->
            StoredIndexSnapshot(entries, identitySnapshots[modelVersion].orEmpty())
        }
    }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
        saveCalls++
        failNextSaveIfConfigured()
        snapshots[modelVersion] = entries
        identitySnapshots.remove(modelVersion)
    }

    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
        saveCalls++
        failNextSaveIfConfigured()
        snapshots[modelVersion] = snapshot.entries
        identitySnapshots[modelVersion] = snapshot.identities
    }

    override suspend fun clear() {
        clearCalls++
        if (clearFailuresRemaining > 0 && !failClearAfterDeletion) {
            clearFailuresRemaining--
            error("configured clear failure")
        }
        snapshots.clear()
        identitySnapshots.clear()
        if (clearFailuresRemaining > 0) {
            clearFailuresRemaining--
            error("configured clear failure after deletion")
        }
    }

    private fun failNextSaveIfConfigured() {
        if (saveFailuresRemaining > 0) {
            saveFailuresRemaining--
            error("configured save failure")
        }
    }
}
