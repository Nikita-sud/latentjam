/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.Loudness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LoudnessNormalizationTest {

    @Test
    fun loudnessMapRoundTripsThroughItsPayloadIncludingHostileIds() {
        val original = mapOf(
            "42" to -8.5f,
            "path|with|pipes\nand lines 🎧" to -17.25f,
        )
        val decoded = decodeTrackLoudness(encodeTrackLoudness(original))
        assertEquals(original.keys, decoded.keys)
        original.forEach { (id, db) ->
            assertTrue(abs(decoded.getValue(id) - db) < 0.011f, "$id: ${decoded[id]}")
        }
    }

    @Test
    fun corruptPayloadLinesDropIndividually() {
        val good = "42".encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val decoded = decodeTrackLoudness(
            "nonsense\nzz|100\n$good|-850\n$good|notanumber\n",
        )
        assertEquals(mapOf("42" to -8.5f), decoded)
    }

    @Test
    fun volumesAreEmptyWhenTheFeatureIsOff() {
        val loudness = mapOf("a" to -8f, "b" to -20f)
        assertEquals(emptyMap(), normalizationVolumes(loudness, enabled = false))
        val enabled = normalizationVolumes(loudness, enabled = true)
        assertEquals(Loudness.normalizationVolume(-8f), enabled.getValue("a"))
        assertEquals(1f, enabled.getValue("b"))
    }
}
