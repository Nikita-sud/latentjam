/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.test.*

class RegionVectorWeightTest {
    @Test
    fun `region reweighting agrees with direct fusion including missing modalities`() {
        val ids = (0..9).map { TrackId(it.toString()) }
        val audio = ids.dropLast(1).associateWith { floatArrayOf(3f, 4f) }
        val text = ids.drop(1).associateWith { floatArrayOf(0f, 2f, 1f) }
        val original = assertNotNull(LibraryVectorFusion.build(ids, audio, text, 2, 3, metadataWeight = 0.75f))
        val expected = assertNotNull(LibraryVectorFusion.build(ids, audio, text, 2, 3, metadataWeight = 0.25f))
        val before = original.peekRows().copyOf()
        val fingerprint = original.fingerprint
        // Layout and journeys see the original vectors until the clustering handoff.
        assertTrue(abs(before[5] - 0.3f) < 1e-6f)
        val adjusted = original.takeRowsForRegions(0.25f)
        val direct = expected.peekRows()
        for (i in adjusted.indices) assertTrue(abs(adjusted[i] - direct[i]) < 1e-6f, "component $i")
        assertContentEquals(before.copyOfRange(0,5), adjusted.copyOfRange(0,5))
        assertContentEquals(before.copyOfRange(45,50), adjusted.copyOfRange(45,50))
        assertEquals(fingerprint, original.fingerprint)
        assertFailsWith<IllegalStateException> { original.takeRowsForRegions(0.25f) }
    }

    @Test
    fun `single modality and explicitly weighted legacy matrices retain their directions`() {
        for (source in LibraryVectorSource.entries) {
            val values = floatArrayOf(0.6f, 0.8f)
            val space = LibraryVectorSpace(listOf(TrackId("x")), values.copyOf(), 2, source)
            assertContentEquals(values, space.takeRowsForRegions(0.25f))
        }
    }
}
