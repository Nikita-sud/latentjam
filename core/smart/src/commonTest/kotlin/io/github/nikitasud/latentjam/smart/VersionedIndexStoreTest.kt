/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionedIndexStoreTest {
    /** Files by name, each holding one version's snapshot (a load of another version reads nothing, as on disk). */
    private val files = HashMap<String, Pair<String, Map<TrackId, FloatArray>>>()

    private fun file(name: String) = object : IndexStore {
        override suspend fun load(modelVersion: String) = files[name]?.takeIf { it.first == modelVersion }?.second
        override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) { files[name] = modelVersion to entries }
        override suspend fun clear() { files.remove(name) }
    }

    private fun row(x: Float) = mapOf(TrackId("t") to floatArrayOf(x))

    @Test
    fun twoEncodersKeepTheirOwnVectorsAndTheLegacyFileIsAdoptedThenRetired() = runTest {
        // An earlier release's single file holds encoder A; this build runs encoder B.
        files["smart_index.bin"] = "A" to row(1f)
        val store = VersionedIndexStore("smart_index", ::file)
        assertNull(store.load("B"), "B has nothing yet")
        assertEquals(1f, store.load("A")!!.getValue(TrackId("t"))[0], "A's first load adopts the legacy file")
        store.save("A", row(1f))
        store.save("B", row(2f))
        // The first save retires the legacy file: an upgrade must not keep the old vectors forever.
        assertEquals(setOf("smart_index.A.bin", "smart_index.B.bin"), files.keys)
        assertEquals(2f, store.load("B")!!.getValue(TrackId("t"))[0])
        assertEquals(1f, store.load("A")!!.getValue(TrackId("t"))[0])
        assertNull(files["smart_index.bin"])
        assertEquals("smart_index.mnv4-960-clean-a0-fma-v1-p50q4aq.bin", VersionedIndexStore.fileNameFor("smart_index", "mnv4-960-clean-a0-fma-v1-p50q4aq"))
        assertEquals("smart_energy.semantic-energy-v1-x_y.bin", VersionedIndexStore.fileNameFor("smart_energy", "semantic-energy-v1-x/y"))
    }
}
