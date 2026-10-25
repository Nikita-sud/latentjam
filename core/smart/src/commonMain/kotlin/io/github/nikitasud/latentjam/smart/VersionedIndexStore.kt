/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * One snapshot file per model version (`<base>.<version>.bin`) beside the single legacy file (`<base>.bin`) that
 * released builds read and write. Builds with different audio encoders on one phone then keep each other's vectors:
 * installing one never makes the other re-analyse the library. A version's first load adopts the legacy file when
 * it holds that version (the stores check the version on load). The first successful save of a version deletes the
 * legacy file: by then the versioned file holds everything it could have offered, and an upgrade whose encoder
 * changed would otherwise keep the previous release's vectors on disk forever (up to 64 MiB).
 */
public class VersionedIndexStore(
    private val baseName: String,
    private val store: (fileName: String) -> IndexStore,
) : IndexStore {
    private val stores = HashMap<String, IndexStore>()
    private val legacy: IndexStore by lazy { store("$baseName.bin") }

    private fun forVersion(modelVersion: String): IndexStore =
        stores.getOrPut(modelVersion) { store(fileNameFor(baseName, modelVersion)) }

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        loadSnapshot(modelVersion)?.entries

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? =
        forVersion(modelVersion).loadSnapshot(modelVersion) ?: legacy.loadSnapshot(modelVersion)

    /**
     * Streams like the stores it wraps (never one map of the whole library: the default would load the snapshot
     * whole). The legacy file is read only when the version's own file delivered nothing: rows from a damaged
     * version file are never mixed with the legacy file's (the receiver discards them on null, as for any store).
     */
    override suspend fun loadRows(
        modelVersion: String,
        row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
    ): Map<TrackId, String>? {
        var delivered = 0
        val own = forVersion(modelVersion).loadRows(modelVersion) { id, vector, identity ->
            delivered++
            row(id, vector, identity)
        }
        if (own != null || delivered > 0) return own
        return legacy.loadRows(modelVersion, row)
    }

    private var legacyRetired = false

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
        forVersion(modelVersion).save(modelVersion, entries)
        retireLegacy()
    }

    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
        forVersion(modelVersion).saveSnapshot(modelVersion, snapshot)
        retireLegacy()
    }

    private suspend fun retireLegacy() {
        if (legacyRetired) return
        legacyRetired = true
        runCatching { legacy.clear() }
    }

    /** Deletes the snapshots of the versions this store has used, and the legacy file. */
    override suspend fun clear() {
        for (s in stores.values) s.clear()
        legacy.clear()
    }

    public companion object {
        /** `<base>.<version>.bin`, the version reduced to file-name characters. */
        public fun fileNameFor(baseName: String, modelVersion: String): String =
            "$baseName.${modelVersion.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")}.bin"
    }
}
