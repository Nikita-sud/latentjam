/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackSemanticsTest {

    @Test
    fun `the model contract is exactly twenty seven stable outputs`() {
        assertEquals(27, TrackSemantics.OUTPUT_SIZE)
        assertEquals(27, SemanticLabel.entries.size)
        assertEquals((0 until 27).toList(), SemanticLabel.entries.map { it.modelIndex })

        val output = FloatArray(TrackSemantics.OUTPUT_SIZE) { index ->
            index.toFloat() / (TrackSemantics.OUTPUT_SIZE - 1)
        }
        val semantics = assertNotNull(TrackSemantics.fromModelOutput(output))

        SemanticLabel.entries.forEach { label ->
            assertEquals(output[label.modelIndex], semantics.probability(label))
        }
        assertContentEquals(output, semantics.copyScores())
    }

    @Test
    fun `wrong shaped non finite and out of range rows are rejected`() {
        assertNull(TrackSemantics.fromModelOutput(FloatArray(26)))
        assertNull(TrackSemantics.fromModelOutput(FloatArray(28)))

        fun invalid(index: Int, value: Float): FloatArray =
            FloatArray(TrackSemantics.OUTPUT_SIZE) { 0.5f }.also { it[index] = value }

        assertNull(TrackSemantics.fromModelOutput(invalid(0, Float.NaN)))
        assertNull(TrackSemantics.fromModelOutput(invalid(1, Float.POSITIVE_INFINITY)))
        assertNull(TrackSemantics.fromModelOutput(invalid(2, -0.0001f)))
        assertNull(TrackSemantics.fromModelOutput(invalid(3, 1.0001f)))
    }

    @Test
    fun `accepted rows are copied on input and output`() {
        val output = FloatArray(TrackSemantics.OUTPUT_SIZE) { 0.25f }
        val semantics = assertNotNull(TrackSemantics.fromModelOutput(output))

        output[SemanticLabel.MUSIC.modelIndex] = 1f
        val copied = semantics.copyScores()
        copied[SemanticLabel.SPEECH.modelIndex] = 1f

        assertEquals(0.25f, semantics.probability(SemanticLabel.MUSIC))
        assertEquals(0.25f, semantics.probability(SemanticLabel.SPEECH))
    }
}
