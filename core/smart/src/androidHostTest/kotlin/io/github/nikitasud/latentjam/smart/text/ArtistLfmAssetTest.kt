/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shipped LightFM table reads back the research's lfm_artist.npz (tools/research/ranker/pack_lfm.py), in its int8 form
 * (PLAN_SIZE, shrink_tables.py): each value within half a step of the scale (≈ 0.002), the cosine within 0.001.
 */
class ArtistLfmAssetTest {
    private val table by lazy {
        assertNotNull(ArtistVectors.parse(File("../../androidApp/src/main/assets/ml/artist_lfm.bin").readBytes()), "the shipped table must parse")
    }

    @Test
    fun `vectors match the research npz`() {
        assertEquals(32, table.dimension)
        val a = assertNotNull(table.vector(106))
        val b = assertNotNull(table.vector(349906))
        for ((x, y) in a.take(3).zip(listOf(0.08409583568572998, -0.0719277411699295, -0.10523860156536102))) {
            assertTrue(abs(x - y) < 0.002, "$x vs $y")
        }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        assertTrue(abs(dot / norm(a) / norm(b) - 0.5141675472259521) < 0.001, "cosine $dot")
        assertFalse(table.has(28))
    }

    private fun norm(v: FloatArray): Double = kotlin.math.sqrt(v.sumOf { it.toDouble() * it })
}
