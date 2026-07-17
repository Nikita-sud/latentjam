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
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class InMemoryVectorIndexTest {

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

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
}
