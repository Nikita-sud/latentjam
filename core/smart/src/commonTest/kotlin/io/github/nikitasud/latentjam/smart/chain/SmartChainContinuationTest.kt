/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SmartChainContinuationTest {
    @Test
    fun `all eligible close tracks are consumed before leaving the initial neighborhood`() {
        val snapshot = library(near = 9, far = 12)
        val near = (1 until snapshot.size).filter {
            Reanchor.isCloseContinuation(snapshot.centeredCosine(0, it), snapshot.descriptorCosine(0, it))
        }.toSet()
        assertEquals(9, near.size)
        val queue = build(snapshot, near.size + 3)
        assertEquals(near, queue.rows.take(near.size).toSet())
        assertEquals(near.size + 3, queue.rows.size)
        assertEquals(queue.rows.size, queue.rows.toSet().size)
        assertTrue(queue.rows.all { it != 0 && it in queue.pool })
    }

    @Test
    fun `pool refills expose suitable tracks beyond the first hundred candidates`() {
        val snapshot = library(near = 125, far = 25)
        val queue = build(snapshot, 120)
        assertEquals(120, queue.rows.size)
        assertEquals(120, queue.rows.toSet().size)
        assertTrue(queue.rows.all { it in 1..125 })
        assertTrue(queue.pool.size > PredictorRuntime.POOL_SIZE)
    }

    @Test
    fun `marked groups retain their existing quota behavior`() {
        val snapshot = library(near = 9, far = 12)
        val groups = listOf(setOf(TrackId("0"), TrackId("15"), TrackId("16")))
        val current = SmartChain(snapshot, null, companionGroups = groups).build(TrackId("0"), 12, FloatArray(5))
        val experimental = SmartChain(
            snapshot, null, companionGroups = groups, tuning = ChainTuning(continueAfterExhaustion = true),
        ).build(TrackId("0"), 12, FloatArray(5))
        assertEquals(current, experimental)
    }

    @Test
    fun `artist semantics without acoustic support do not hold the queue in a neighborhood`() {
        assertFalse(Reanchor.isCloseContinuation(0.1f, 1f))
        assertFalse(Reanchor.isCloseContinuation(0.39f, null))
        assertTrue(Reanchor.isCloseContinuation(0.5f, null))
        assertTrue(Reanchor.isCloseContinuation(0.3f, 0.7f))
    }

    private fun build(snapshot: SmartSnapshot, length: Int): ChainResult = SmartChain(
        snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true),
    ).build(TrackId("0"), length, FloatArray(5))

    private fun library(near: Int, far: Int): SmartSnapshot = requireNotNull(SmartSnapshot.build(
        (0..near + far).map { row ->
            SmartTrack(
                TrackId(row.toString()),
                audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also {
                    it[0] = if (row <= near) 1f else -1f
                    it[row + 1] = 0.04f
                },
                meta = TrackMeta("Title $row", "Artist $row", null, null, null),
            )
        },
    ))
}
