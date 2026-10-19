/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An index over a store that keeps its own copies and hands out no heap arrays (the shape of Android's mapped store)
 * must answer exactly as the heap store does, and a row handed out must keep reading its floats after the id is
 * replaced or removed and the store compacted (a snapshot holds such rows).
 */
internal class RowStoreIndexTest {

    /** Keeps copies; never exposes them as heap arrays. Compaction builds a new list and leaves old copies as they were. */
    private class CopyingRowStore(override val dim: Int) : RowStore {
        var rows = ArrayList<FloatArray?>()
        var compactions = 0
        override val rowCount: Int get() = rows.size
        override fun append(values: FloatArray): Int {
            rows += values.copyOf()
            return rows.lastIndex
        }
        override fun release(row: Int) {
            rows[row] = null
        }
        override fun row(row: Int): StoredVectorRow {
            val values = checkNotNull(rows[row])
            return object : StoredVectorRow {
                override val size: Int get() = values.size
                override fun get(d: Int): Float = values[d]
                override fun copyInto(out: FloatArray, offset: Int) {
                    values.copyInto(out, offset)
                }
            }
        }
        override fun compact(rows: IntArray) {
            compactions++
            this.rows = ArrayList(rows.map { this.rows[it] })
        }
        override fun clear() {
            rows = ArrayList()
        }
    }

    private val dim = 9

    @Test
    fun anOffHeapStoreAnswersAsTheHeapStoreThroughUpdatesRemovalsAndCompaction() {
        val heap = InMemoryVectorIndex(dim)
        val store = CopyingRowStore(dim)
        val copying = InMemoryVectorIndex(dim, store)
        val random = Random(20261020)
        val held = ArrayList<Pair<StoredVectorRow, FloatArray>>()
        repeat(6_000) { step ->
            val id = TrackId("t${random.nextInt(1_500)}")
            when (random.nextInt(10)) {
                in 0..5 -> {
                    val v = FloatArray(dim) { random.nextFloat() - 0.5f }
                    if (step % 2 == 0) {
                        heap.upsert(id, v)
                        copying.upsert(id, v)
                    } else {
                        heap.adopt(id, v.copyOf())
                        copying.adopt(id, v.copyOf())
                    }
                }
                else -> assertEquals(heap.remove(id), copying.remove(id))
            }
            if (step % 97 == 0) copying.storedRow(id)?.let { held += it to it.toFloatArray() }
        }
        assertTrue(store.compactions > 0, "the run reaches compaction")
        assertEquals(heap.size, copying.size)
        assertEquals(heap.storedEntries().keys.toList(), copying.storedEntries().keys.toList(), "iteration order")
        for ((id, vector) in heap.storedEntries()) {
            assertContentEquals(vector.map(Float::toRawBits), copying.storedEntries()[id]!!.map(Float::toRawBits))
            assertContentEquals(vector.map(Float::toRawBits), assertNotNull(copying.storedRow(id)).toFloatArray().map(Float::toRawBits))
        }
        assertEquals(heap.entries().keys, copying.entries().keys)
        repeat(20) {
            val query = FloatArray(dim) { random.nextFloat() - 0.5f }
            val exclude = setOf(TrackId("t${random.nextInt(1_500)}"))
            assertEquals(heap.nearest(query, 25, exclude), copying.nearest(query, 25, exclude))
        }
        for ((row, floats) in held) assertContentEquals(floats.map(Float::toRawBits), row.toFloatArray().map(Float::toRawBits))
        assertTrue(store.rowCount <= 2 * copying.size + 1_024, "dead rows are reclaimed (${store.rowCount} rows for ${copying.size})")

        copying.clear()
        assertEquals(0, copying.size)
        assertNull(copying.storedRow(TrackId("t1")))
        for ((row, floats) in held) assertContentEquals(floats.map(Float::toRawBits), row.toFloatArray().map(Float::toRawBits))
    }
}
