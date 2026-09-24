/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DescriptorWidthTest {

    @Test
    fun `a 384-wide descriptor space is measured at its own width`() {
        val snapshot = assertNotNull(
            SmartSnapshot.build(
                listOf(
                    track("a", 0, FloatArray(384) { if (it == 0) 1f else 0f }),
                    track("b", 1, FloatArray(384) { if (it < 2) 1f else 0f }),
                    track("c", 2, FloatArray(384) { if (it == 5) 1f else 0f }),
                ),
            ),
        )

        assertEquals(384, snapshot.descriptorDim)
        assertNotNull(snapshot.descriptorCosine(0, 1))
    }

    @Test
    fun `rows of another width count as absent rather than truncated`() {
        val snapshot = assertNotNull(
            SmartSnapshot.build(
                listOf(
                    track("a", 0, FloatArray(384) { if (it == 0) 1f else 0f }),
                    track("b", 1, FloatArray(384) { if (it == 1) 1f else 0f }),
                    track("c", 2, FloatArray(768) { 1f }),
                ),
            ),
        )

        assertEquals(384, snapshot.descriptorDim)
        assertNull(snapshot.descriptorCosine(0, 2))
    }

    @Test
    fun `without descriptors the width stays the fixtures' default`() {
        val snapshot = assertNotNull(SmartSnapshot.build(listOf(track("a", 0, null), track("b", 1, null))))

        assertEquals(SmartSnapshot.DESCRIPTOR_DIM, snapshot.descriptorDim)
        assertNull(snapshot.descriptorCosine(0, 1))
    }

    private fun track(id: String, audioAxis: Int, descriptor: FloatArray?) = SmartTrack(
        id = TrackId(id),
        audio = FloatArray(SmartSnapshot.AUDIO_DIM).also { it[audioAxis] = 1f },
        descriptor = descriptor,
        meta = TrackMeta(null, null, null, null, null),
    )
}
