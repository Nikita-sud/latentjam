/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * In-memory [IndexStore] for tests: remembers the last [save] per model
 * version and serves it back from [load], with call counters for interaction
 * asserts.
 *
 * Like the file-backed stores it keeps its own copy of what is saved and hands out fresh arrays on
 * every load: the engine saves its live vectors and adopts what it loads (see [IndexStore]).
 */
internal class FakeIndexStore : IndexStore {

    val snapshots = mutableMapOf<String, Map<TrackId, FloatArray>>()
    val identitySnapshots = mutableMapOf<String, Map<TrackId, String>>()
    val failureSnapshots = mutableMapOf<String, Map<TrackId, String>>()
    var loadCalls: Int = 0
    var saveCalls: Int = 0
    var clearCalls: Int = 0
    var saveFailuresRemaining: Int = 0
    var clearFailuresRemaining: Int = 0
    var failClearAfterDeletion: Boolean = false
    /** Loads that fail as a read that could not happen now (out of memory), not as "no snapshot". */
    var loadFailuresRemaining: Int = 0

    private fun failNextLoadIfConfigured() {
        if (loadFailuresRemaining > 0) {
            loadFailuresRemaining--
            throw IndexRestoreFailure(OutOfMemoryError("simulated restore"))
        }
    }

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? {
        loadCalls++
        failNextLoadIfConfigured()
        return snapshots[modelVersion]?.deepCopy()
    }

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? {
        loadCalls++
        failNextLoadIfConfigured()
        return snapshots[modelVersion]?.let { entries ->
            StoredIndexSnapshot(
                entries = entries.deepCopy(),
                identities = identitySnapshots[modelVersion].orEmpty(),
                failedIdentities = failureSnapshots[modelVersion].orEmpty(),
            )
        }
    }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
        saveCalls++
        failNextSaveIfConfigured()
        snapshots[modelVersion] = entries.deepCopy()
        identitySnapshots.remove(modelVersion)
        failureSnapshots.remove(modelVersion)
    }

    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
        saveCalls++
        failNextSaveIfConfigured()
        snapshots[modelVersion] = snapshot.entries.deepCopy()
        identitySnapshots[modelVersion] = snapshot.identities.toMap()
        failureSnapshots[modelVersion] = snapshot.failedIdentities.toMap()
    }

    override suspend fun clear() {
        clearCalls++
        if (clearFailuresRemaining > 0 && !failClearAfterDeletion) {
            clearFailuresRemaining--
            error("configured clear failure")
        }
        snapshots.clear()
        identitySnapshots.clear()
        failureSnapshots.clear()
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

    private fun Map<TrackId, FloatArray>.deepCopy(): Map<TrackId, FloatArray> = mapValues { it.value.copyOf() }
}
