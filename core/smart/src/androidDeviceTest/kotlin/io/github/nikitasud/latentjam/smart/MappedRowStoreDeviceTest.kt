/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapped store on ART: same floats as the heap index across segments, compaction and clear; no file left. */
class MappedRowStoreDeviceTest {

    @Test
    fun mappedIndexMatchesTheHeapIndexBitForBit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dim = 960
        val store = MappedRowStore.forIndex(context, dim, "devicetest")
        assertTrue("64-bit test process maps", store is MappedRowStore)
        val mapped = InMemoryVectorIndex(dim, store)
        val heap = InMemoryVectorIndex(dim)
        val random = Random(7)
        val n = MappedRowStore.SEGMENT_ROWS * 2 + 300
        for (i in 0 until n) {
            val v = FloatArray(dim) { random.nextFloat() - 0.5f }
            heap.adopt(TrackId("$i"), v.copyOf())
            mapped.adopt(TrackId("$i"), v.copyOf())
        }
        val held = mapped.storedRow(TrackId("17"))!!
        val heldFloats = held.toFloatArray()
        for (i in 0 until n step 2) {
            heap.remove(TrackId("$i"))
            mapped.remove(TrackId("$i"))
        }
        for (i in 1 until 200 step 2) {
            val v = FloatArray(dim) { random.nextFloat() }
            heap.upsert(TrackId("$i"), v)
            mapped.upsert(TrackId("$i"), v)
        }
        assertEquals(heap.storedEntries().keys.toList(), mapped.storedEntries().keys.toList())
        for ((id, v) in heap.storedEntries()) {
            assertArrayEquals(v.map(Float::toRawBits).toIntArray(), mapped.storedEntries()[id]!!.map(Float::toRawBits).toIntArray())
        }
        val query = FloatArray(dim) { random.nextFloat() - 0.5f }
        assertEquals(heap.nearest(query, 50), mapped.nearest(query, 50))
        assertArrayEquals(heldFloats.map(Float::toRawBits).toIntArray(), held.toFloatArray().map(Float::toRawBits).toIntArray())
        mapped.clear()
        assertArrayEquals(heldFloats.map(Float::toRawBits).toIntArray(), held.toFloatArray().map(Float::toRawBits).toIntArray())
        assertEquals(emptyList<String>(), File(context.noBackupFilesDir, "smart_rows").list()?.toList().orEmpty())
    }
}
