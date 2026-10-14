/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class InMemoryVectorIndexTest {

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

    @Test
    fun heapMatchesStableFullSortAcrossTiesExclusionsAndUpdates() {
        val index = InMemoryVectorIndex(dim = 7)
        for (i in 0 until 257) {
            index.upsert(TrackId("$i"), FloatArray(7) { d -> ((i % 29 + d * 13) % 11 - 5).toFloat() })
        }
        index.upsert(TrackId("5"), FloatArray(7))
        index.remove(TrackId("4"))
        index.upsert(TrackId("4"), FloatArray(7))
        for (query in listOf(FloatArray(7), FloatArray(7) { (it - 3).toFloat() })) {
            val norm = kotlin.math.sqrt(query.fold(0f) { sum, v -> sum + v * v })
            val normalized = query.map { if (norm == 0f) it else it / norm }
            for (exclude in listOf(emptySet(), setOf(TrackId("0"), TrackId("5")), index.ids())) {
                val expected = index.entries().filterKeys { it !in exclude }.map { (id, vector) ->
                    var score = 0f
                    for (d in vector.indices) score += normalized[d] * vector[d]
                    ScoredTrack(id, score)
                }.sortedByDescending { it.score }
                for (k in listOf(0, 1, 2, 7, 128, 257, Int.MAX_VALUE)) {
                    assertEquals(expected.take(k), index.nearest(query, k, exclude), "k=$k")
                }
            }
        }
    }

    private fun indexWithTriangle(): InMemoryVectorIndex {
        // Against query (1,0,0): a ≈ 0.994, b ≈ 0.707, c = 0.
        return InMemoryVectorIndex(dim = 3).apply {
            upsert(a, floatArrayOf(0.9f, 0.1f, 0f))
            upsert(b, floatArrayOf(0.5f, 0.5f, 0f))
            upsert(c, floatArrayOf(0f, 1f, 0f))
        }
    }

    @Test
    fun ranksByCosineSimilarityBestFirst() {
        val hits = indexWithTriangle().nearest(query = floatArrayOf(1f, 0f, 0f), k = 3)
        assertContentEquals(listOf(a, b, c), hits.map { it.trackId })
        assertTrue(hits[0].score > 0.99f)
        assertTrue(hits[1].score in 0.70f..0.72f)
        assertTrue(hits[2].score in -0.01f..0.01f)
    }

    @Test
    fun normalizesTheQueryItself() {
        // Same direction, wildly different magnitude — identical ranking and scores.
        val unit = indexWithTriangle().nearest(floatArrayOf(1f, 0f, 0f), k = 1).single()
        val scaled = indexWithTriangle().nearest(floatArrayOf(1000f, 0f, 0f), k = 1).single()
        assertEquals(unit.trackId, scaled.trackId)
        assertEquals(unit.score, scaled.score, absoluteTolerance = 1e-5f)
    }

    @Test
    fun honorsK() {
        val index = indexWithTriangle()
        assertEquals(listOf(a), index.nearest(floatArrayOf(1f, 0f, 0f), k = 1).map { it.trackId })
        assertTrue(index.nearest(floatArrayOf(1f, 0f, 0f), k = 0).isEmpty())
        assertEquals(3, index.nearest(floatArrayOf(1f, 0f, 0f), k = 99).size)
    }

    @Test
    fun skipsExcludedIds() {
        val hits = indexWithTriangle().nearest(floatArrayOf(1f, 0f, 0f), k = 3, exclude = setOf(a))
        assertContentEquals(listOf(b, c), hits.map { it.trackId })
    }

    @Test
    fun upsertReplacesExistingVector() {
        val index = InMemoryVectorIndex(dim = 3)
        index.upsert(a, floatArrayOf(1f, 0f, 0f))
        index.upsert(a, floatArrayOf(0f, 1f, 0f))
        assertEquals(1, index.size)
        val hit = index.nearest(floatArrayOf(0f, 1f, 0f), k = 1).single()
        assertEquals(a, hit.trackId)
        assertTrue(hit.score > 0.99f)
    }

    @Test
    fun rejectsWrongDimensions() {
        val index = InMemoryVectorIndex(dim = 3)
        assertFailsWith<IllegalArgumentException> { index.upsert(a, floatArrayOf(1f, 0f)) }
        assertFailsWith<IllegalArgumentException> { index.nearest(floatArrayOf(1f, 0f), k = 1) }
        assertFailsWith<IllegalArgumentException> { InMemoryVectorIndex(dim = 0) }
    }

    @Test
    fun rejectsNonFiniteStoredAndQueryVectors() {
        val index = InMemoryVectorIndex(dim = 3)
        assertFailsWith<IllegalArgumentException> {
            index.upsert(a, floatArrayOf(1f, Float.NaN, 0f))
        }
        index.upsert(a, floatArrayOf(1f, 0f, 0f))
        assertFailsWith<IllegalArgumentException> {
            index.nearest(floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f), k = 1)
        }
    }

    @Test
    fun vectorReturnsDefensiveNormalizedCopy() {
        val index = InMemoryVectorIndex(dim = 3)
        index.upsert(a, floatArrayOf(2f, 0f, 0f))
        val stored = assertNotNull(index.vector(a))
        assertContentEquals(floatArrayOf(1f, 0f, 0f), stored) // normalized on upsert
        stored.fill(0f) // mutating the copy must not corrupt the index
        assertContentEquals(floatArrayOf(1f, 0f, 0f), assertNotNull(index.vector(a)))
        assertNull(index.vector(b))
    }

    @Test
    fun zeroVectorsScoreZeroInsteadOfCrashing() {
        val index = InMemoryVectorIndex(dim = 3)
        index.upsert(a, floatArrayOf(0f, 0f, 0f))
        index.upsert(b, floatArrayOf(1f, 0f, 0f))
        val hits = index.nearest(floatArrayOf(0f, 0f, 0f), k = 2)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.score == 0f })
    }

    @Test
    fun clearEmptiesTheIndex() {
        val index = indexWithTriangle()
        index.clear()
        assertEquals(0, index.size)
        assertTrue(index.nearest(floatArrayOf(1f, 0f, 0f), k = 3).isEmpty())
    }

    @Test
    fun idsListTheStoredTracksAndOutliveLaterRemovals() {
        val index = indexWithTriangle()
        val ids = index.ids()
        assertEquals(listOf(a, b, c), ids.toList())
        index.remove(b)
        assertEquals(listOf(a, b, c), ids.toList())
        assertEquals(listOf(a, c), index.ids().toList())
    }

    @Test
    fun storedEntriesShareTheStoredVectorsInsteadOfCopyingThem() {
        val index = indexWithTriangle()
        val stored = index.storedEntries()
        assertEquals(index.entries().keys, stored.keys)
        for ((id, vector) in index.entries()) assertContentEquals(vector, stored[id])
        // The whole point: a checkpoint reads the same arrays every time instead of a second index.
        assertSame(stored[a], index.storedEntries()[a])
        assertNotSame(index.entries()[a], index.entries()[a])
    }

    @Test
    fun adoptStoresExactlyWhatUpsertStoresWithoutACopy() {
        val rows = listOf(
            floatArrayOf(0.9f, 0.1f, 0f),
            floatArrayOf(0.3f, -0.7f, 0.2f),
            floatArrayOf(0.57735026f, 0.57735026f, 0.57735026f), // already unit length, as restored rows are
            floatArrayOf(0f, 0f, 0f),
        )
        for (row in rows) {
            val upserted = InMemoryVectorIndex(dim = 3).apply { upsert(a, row.copyOf()) }
            val handedOver = row.copyOf()
            val adopted = InMemoryVectorIndex(dim = 3).apply { adopt(a, handedOver) }
            val expected = assertNotNull(upserted.vector(a))
            val actual = assertNotNull(adopted.vector(a))
            for (i in expected.indices) assertEquals(expected[i].toRawBits(), actual[i].toRawBits())
            assertSame(handedOver, adopted.storedEntries()[a])
        }
    }

    @Test
    fun adoptRejectsWhatUpsertRejects() {
        val index = InMemoryVectorIndex(dim = 3)
        assertFailsWith<IllegalArgumentException> { index.adopt(a, floatArrayOf(1f, 0f)) }
        assertFailsWith<IllegalArgumentException> { index.adopt(a, floatArrayOf(1f, Float.NaN, 0f)) }
        assertEquals(0, index.size)
    }
}
