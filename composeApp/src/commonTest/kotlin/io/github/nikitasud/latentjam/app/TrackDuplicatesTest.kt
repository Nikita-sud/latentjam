/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TrackDuplicatesTest {

    private fun unit(x: Float, y: Float): FloatArray {
        val norm = kotlin.math.sqrt(x * x + y * y)
        return floatArrayOf(x / norm, y / norm)
    }

    @Test
    fun nearIdenticalVectorsGroupTogether() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("a-copy") to unit(1f, 0.01f),
                TrackId("far") to unit(0f, 1f),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(TrackId("a"), TrackId("a-copy")), groups.single().toSet())
    }

    @Test
    fun transitiveNeighborsFormOneGroupNotTwoPairs() {
        // b sits between a and c: one cluster of three, not overlapping pairs.
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("b") to unit(1f, 0.008f),
                TrackId("c") to unit(1f, 0.016f),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().size)
    }

    @Test
    fun distinctTracksProduceNoGroups() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("a") to unit(1f, 0f),
                TrackId("b") to unit(0.5f, 1f),
                TrackId("c") to unit(0f, 1f),
            ),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun biggestGroupComesFirst() {
        val groups = audioDuplicateGroups(
            vectors = mapOf(
                TrackId("p1") to unit(0f, 1f),
                TrackId("p2") to unit(0.005f, 1f),
                TrackId("t1") to unit(1f, 0f),
                TrackId("t2") to unit(1f, 0.005f),
                TrackId("t3") to unit(1f, 0.01f),
            ),
        )
        assertEquals(listOf(3, 2), groups.map { it.size })
    }
}
