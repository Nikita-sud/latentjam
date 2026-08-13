/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ChannelPowerDownmixTest {

    @Test
    fun oppositePhaseStereoRetainsItsAudiblePower() {
        val channels = floatArrayOf(1f, -1f)

        assertEquals(
            1f,
            channelPowerDownmix(channels.size, channels::get),
            absoluteTolerance = 1e-6f,
        )
    }

    @Test
    fun hardPannedStereoUsesRmsAcrossBothChannels() {
        val channels = floatArrayOf(1f, 0f)

        assertEquals(
            (1.0 / sqrt(2.0)).toFloat(),
            channelPowerDownmix(channels.size, channels::get),
            absoluteTolerance = 1e-6f,
        )
    }

    @Test
    fun malformedFloatSamplesCannotPoisonTheMeasurement() {
        val channels = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -0.5f)

        assertEquals(
            (-0.5 / sqrt(3.0)).toFloat(),
            channelPowerDownmix(channels.size, channels::get),
            absoluteTolerance = 1e-6f,
        )
    }
}
