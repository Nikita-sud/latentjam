/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SemanticLabel
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TrackSemantics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryEnergyTest {

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

    @Test
    fun `ranks span zero to one in score order`() {
        assertEquals(
            mapOf(b to 0f, a to 0.5f, c to 1f),
            libraryEnergyRanks(mapOf(a to 0.4f, b to -0.2f, c to 0.9f)),
        )
    }

    @Test
    fun `equal scores are ordered by track id rather than by map order`() {
        assertEquals(mapOf(a to 0f, b to 0.5f, c to 1f), libraryEnergyRanks(mapOf(c to 0.3f, b to 0.3f, a to 0.3f)))
    }

    @Test
    fun `unknown scores stay unknown and a lone track ranks at zero`() {
        assertEquals(emptyMap(), libraryEnergyRanks(emptyMap()))
        assertEquals(mapOf(a to 0f), libraryEnergyRanks(mapOf(a to 0.3f, b to Float.NaN)))
    }

    @Test
    fun `energy score is the high-energy score minus the low-energy score`() {
        val output = FloatArray(TrackSemantics.OUTPUT_SIZE)
        output[SemanticLabel.ENERGY_HIGH.modelIndex] = 0.8f
        output[SemanticLabel.ENERGY_LOW.modelIndex] = 0.3f
        assertEquals(0.5f, assertNotNull(TrackSemantics.fromModelOutput(output)).energyScore(), 1e-6f)
    }

    @Test
    fun `rows get their library percentile and keep a known energy but stay unknown without semantics`() {
        val rows = listOf(track(a), track(b), track(c, energy = 0.42f), track(TrackId("d")))
        val semantics = mapOf(a to semantics(high = 0.9f, low = 0.1f), b to semantics(high = 0.2f, low = 0.6f),
                              c to semantics(high = 0.5f, low = 0.5f))
        val out = applyLibraryEnergy(rows, semantics).associate { it.id to it.energy }

        assertEquals(1f, out.getValue(a))       // the most energetic of a, b, c
        assertEquals(0f, out.getValue(b))
        assertEquals(0.42f, out.getValue(c))    // an energy the caller supplied is never overwritten
        assertTrue(out.getValue(TrackId("d")).isNaN())
    }

    private fun track(id: TrackId, energy: Float = Float.NaN) =
        SmartTrack(id, FloatArray(4), energy = energy, meta = TrackMeta(null, null, null, null, null))

    private fun semantics(high: Float, low: Float): TrackSemantics {
        val output = FloatArray(TrackSemantics.OUTPUT_SIZE)
        output[SemanticLabel.ENERGY_HIGH.modelIndex] = high
        output[SemanticLabel.ENERGY_LOW.modelIndex] = low
        return assertNotNull(TrackSemantics.fromModelOutput(output))
    }
}
