/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * One snapshot file per model version (`<base>.<version>.bin`) beside the single legacy file (`<base>.bin`) that
 * released builds read and write. Builds with different audio encoders on one phone then keep each other's vectors:
 * installing one never makes the other re-analyse the library. A version's first load adopts the legacy file when
 * it holds that version (the stores check the version on load); saves never touch the legacy file.
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

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit =
        forVersion(modelVersion).save(modelVersion, entries)

    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot): Unit =
        forVersion(modelVersion).saveSnapshot(modelVersion, snapshot)

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
