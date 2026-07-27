/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LayoutRoundTripTest {

    private class MemoryStore : IndexStore {
        private var version: String? = null
        private var entries: Map<TrackId, FloatArray> = emptyMap()
        override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
            if (modelVersion == version) entries else null
        override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
            version = modelVersion
            this.entries = entries
        }
        override suspend fun clear() {
            version = null
            entries = emptyMap()
        }
    }

    private val ids = listOf(TrackId("a"), TrackId("b"), TrackId("c"))
    private val points = listOf(
        LayoutPoint(TrackId("a"), 0.1f, 0.2f),
        LayoutPoint(TrackId("b"), 0.3f, 0.4f),
        LayoutPoint(TrackId("c"), 0.5f, 0.6f),
    )

    @Test
    fun `a saved layout round-trips`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points)
        val loaded = store.loadLayout()
        assertEquals(3, loaded.size)
        assertEquals(0.3f, loaded.getValue(TrackId("b"))[0])
        assertEquals(0.4f, loaded.getValue(TrackId("b"))[1])
        assertTrue(LibraryLayout.covers(loaded, ids))
    }

    // A changed library must still READ — the stale layout is the warm start — but must report
    // itself stale so the caller recomputes instead of drawing a map missing its newest tracks.
    @Test
    fun `a changed library still loads but does not cover`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points)
        val loaded = store.loadLayout()
        assertEquals(3, loaded.size)
        assertFalse(LibraryLayout.covers(loaded, ids + TrackId("d")))
    }

    @Test
    fun `an empty store loads empty rather than failing`() = runTest {
        assertEquals(emptyMap(), MemoryStore().loadLayout())
    }
}
