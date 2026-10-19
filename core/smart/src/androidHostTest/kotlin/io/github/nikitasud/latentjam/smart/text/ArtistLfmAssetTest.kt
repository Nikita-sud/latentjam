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

/** The shipped LightFM table reads back the research's lfm_artist.npz (tools/research/ranker/pack_lfm.py). */
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
            assertTrue(abs(x - y) < 1e-7, "$x vs $y")
        }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        assertTrue(abs(dot - 0.5141675472259521) < 1e-6, "cosine $dot")
        assertFalse(table.has(28))
    }
}
