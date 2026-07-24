/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticZTest {

    // directionZ mean-centers and unit-scales the pool projections: a symmetric spread lands
    // symmetric around zero with std = sqrt(variance), so the pool mean of the z-scores is zero.
    @Test
    fun `directionZ standardizes the pool projections`() {
        val matrix = floatArrayOf(-2f, -1f, 0f, 1f, 2f)
        val z = SemanticZ.directionZ(
            matrix = matrix,
            dim = 1,
            direction = floatArrayOf(1f),
            poolRows = intArrayOf(0, 1, 2, 3, 4),
        )
        val std = kotlin.math.sqrt(2f)
        assertEquals(-2f / std, z[0], 1e-5f)
        assertEquals(0f, z[2], 1e-6f)
        assertEquals(2f / std, z[4], 1e-5f)
        var mean = 0f
        for (v in z) mean += v
        assertEquals(0f, mean / z.size, 1e-6f)
    }

    // A lone outlier that would score above Z_CLIP is clamped, so one spectacular projection can't
    // dominate the whole chain score. Nine zeros + one 1 gives std = 0.3 and z = 3.0 exactly.
    @Test
    fun `directionZ clips extreme projections`() {
        val matrix = FloatArray(10).also { it[9] = 1f }
        val z = SemanticZ.directionZ(
            matrix = matrix,
            dim = 1,
            direction = floatArrayOf(1f),
            poolRows = IntArray(10) { it },
            clip = SemanticZ.Z_CLIP,
        )
        assertEquals(SemanticZ.Z_CLIP, z[9], 1e-5f)
        for (k in 0 until 9) assertTrue(z[k] in -SemanticZ.Z_CLIP..SemanticZ.Z_CLIP)
    }

    @Test
    fun `metadata text is the complete production semantic signal`() {
        val text = floatArrayOf(-1.5f, 0f, 2.25f)

        assertContentEquals(text, SemanticZ.combine(null, text, text.size))
    }

    @Test
    fun `missing metadata candidate contributes a neutral score`() {
        val text = floatArrayOf(1f, Float.NaN, -1f)

        assertContentEquals(floatArrayOf(1f, 0f, -1f), SemanticZ.combine(null, text, text.size))
    }
}
