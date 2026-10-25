/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class IosMapRefreshStateStoreTest {
    @Test
    fun mappedStoreRoundTripsAndRejectsGarbage() = runBlocking {
        val random = Random(7)
        val n = 50; val dim = 10; val k = 4; val kk = 6
        val snapshot = MapRefreshSnapshot(
            "v", LibraryVectorSource.AUDIO, 42L, List(n) { TrackId("t$it") }, dim, k, kk, 12.5f,
            FloatArray(dim) { random.nextFloat() }, FloatArray(dim * k) { random.nextFloat() },
            FloatArray(n * k) { random.nextFloat() }, IntArray(n * kk) { random.nextInt(n) }, LongArray(n) { random.nextLong() },
        )
        val store = IosMapRefreshStateStore("map_refresh_state_test.bin")
        store.clear()
        assertNull(store.load())
        store.save(snapshot)
        val state = assertNotNull(store.load())
        assertEquals(snapshot.ids, state.ids)
        assertContentEquals(snapshot.basis, state.basis)
        val row = FloatArray(k)
        for (i in 0 until n) {
            state.reducedRow(i, row)
            assertContentEquals(snapshot.reduced.copyOfRange(i * k, (i + 1) * k), row)
            assertEquals(snapshot.hashes[i], state.hash(i))
            for (s in 0 until kk) assertEquals(snapshot.neighbourIds[i * kk + s], state.neighbour(i, s))
        }
        state.close()
        store.clear()
        assertNull(store.load())
    }
}
