/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SemanticZTest {

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
