/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.ScoredTrack
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the semantic tier's confidence gate: cos_top1 >= 0.45 AND (top1 − mean(cos[rank50:150])) >=
 * 0.22, with at least [SemanticGate.BG_HI] scored rows.
 */
class SemanticGateTest {

    private fun scores(values: List<Float>): List<ScoredTrack> =
        values.mapIndexed { i, v -> ScoredTrack(TrackId("t$i"), v) }.sortedByDescending { it.score }

    /** Tight cluster far above a low background → fires, best row first, top-K only. */
    @Test
    fun tightCluster_fires() {
        val values = List(160) { i ->
            when {
                i == 7 -> 0.80f // planted off index 0 to prove ordering
                i < 50 -> 0.50f
                i < 150 -> 0.10f // background band → mean 0.10, margin 0.70
                else -> 0.0f
            }
        }
        val result = SemanticGate.gate(scores(values))
        assertEquals(SemanticGate.TOP_K, result.size)
        assertEquals(0.80f, result[0].score)
    }

    /** Diffuse blob: clears 0.45 but the background mean is right below it → margin rejects. */
    @Test
    fun diffuseBlob_gatedByMargin() {
        val values = List(160) { i -> if (i == 0) 0.60f else 0.50f }
        assertTrue(SemanticGate.gate(scores(values)).isEmpty())
    }

    /** Weak top-1 below 0.45 → rejected even with a huge margin. */
    @Test
    fun weakTop1_gatedByThreshold() {
        val values = List(160) { i -> if (i == 0) 0.40f else 0.02f }
        assertTrue(SemanticGate.gate(scores(values)).isEmpty())
    }

    /** Fewer than the background band's upper bound → can't gate, declines. */
    @Test
    fun tooFewRows_declines() {
        val values = List(100) { i -> if (i == 0) 0.90f else 0.01f }
        assertTrue(SemanticGate.gate(scores(values)).isEmpty())
    }
}
