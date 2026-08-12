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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LayoutRoundTripTest {

    private class MemoryStore : IndexStore {
        private var version: String? = null
        private var entries: Map<TrackId, FloatArray> = emptyMap()
        override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
            if (modelVersion == version) entries.filterValues { row -> row.all(Float::isFinite) }
            else null
        override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
            version = modelVersion
            this.entries = entries
        }
        override suspend fun clear() {
            version = null
            entries = emptyMap()
        }

        fun rawEntries(): Map<TrackId, FloatArray> = entries
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
        val fingerprint = 0x1234_5678_9abc_defL
        store.saveLayout(points, fingerprint)
        val loaded = store.loadStoredLayout()
        assertEquals(3, loaded.positions.size)
        assertEquals(0.3f, loaded.positions.getValue(TrackId("b"))[0])
        assertEquals(0.4f, loaded.positions.getValue(TrackId("b"))[1])
        assertEquals(fingerprint, loaded.fingerprint)
        assertTrue(LibraryLayout.covers(loaded, ids, fingerprint))
    }

    // A changed library must still READ — the stale layout is the warm start — but must report
    // itself stale so the caller recomputes instead of drawing a map missing its newest tracks.
    @Test
    fun `a changed library still loads but does not cover`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points, fingerprint = 17L)
        val loaded = store.loadStoredLayout()
        assertEquals(3, loaded.positions.size)
        assertFalse(LibraryLayout.covers(loaded, ids + TrackId("d"), fingerprint = 17L))
    }

    @Test
    fun `a stale fingerprint is a cache miss but preserves warm-start positions`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points, fingerprint = 17L)

        val loaded = store.loadStoredLayout()

        assertFalse(LibraryLayout.covers(loaded, ids, fingerprint = 18L))
        assertEquals(ids.toSet(), loaded.positions.keys)
        assertEquals(0.5f, loaded.positions.getValue(TrackId("c"))[0])
    }

    @Test
    fun `reserved-looking ids and the largest fingerprint round-trip through finite pairs`() =
        runTest {
            val trickyPoints = listOf(
                LayoutPoint(TrackId("\u0000lj-layout-meta:fingerprint"), 0.1f, 0.2f),
                LayoutPoint(TrackId("12:a:b:c|d"), 0.3f, 0.4f),
                LayoutPoint(TrackId(""), 0.5f, 0.6f),
            )
            val store = MemoryStore()

            store.saveLayout(trickyPoints, Long.MAX_VALUE)
            val loaded = store.loadStoredLayout()

            assertEquals(trickyPoints.map { it.trackId }.toSet(), loaded.positions.keys)
            assertEquals(Long.MAX_VALUE, loaded.fingerprint)
            assertTrue(store.rawEntries().values.all { row ->
                row.size == 2 && row.all(Float::isFinite)
            })
        }

    @Test
    fun `legacy save has positions but no cache fingerprint`() = runTest {
        val store = MemoryStore()
        store.saveLayout(points)

        val loaded = store.loadStoredLayout()

        assertEquals(ids.toSet(), loaded.positions.keys)
        assertNull(loaded.fingerprint)
        assertFalse(LibraryLayout.covers(loaded, ids, fingerprint = 1L))
    }

    @Test
    fun `an empty store loads empty rather than failing`() = runTest {
        assertEquals(emptyMap(), MemoryStore().loadLayout())
        assertNull(MemoryStore().loadStoredLayout().fingerprint)
    }
}
