/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The PCM an app-owned decoder (Apple Lossless) hands the analysis, as the mono floats the models take. */
class AppPcmBytesTest {
    private fun le(value: Int, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

    @Test
    fun sixteenBitStereoAveragesItsChannels() {
        val pcm = le(16384, 2) + le(-16384, 2) + le(32767, 2) + le(32767, 2) + le(-32768, 2) + le(-32768, 2)
        val mono = monoFromPcmBytes(pcm, pcm.size, channels = 2, bytesPerSample = 2, AudioDownmixMode.AVERAGE)
        assertEquals(3, mono.size)
        assertEquals(0f, mono[0], 1e-6f)
        assertEquals(32767 / 32768f, mono[1], 1e-6f)
        assertEquals(-1f, mono[2], 1e-6f)
    }

    @Test
    fun twentyFourAndThirtyTwoBitKeepTheirSignAndScale() {
        val p24 = le(0x400000, 3) + le(-0x400000, 3) + le(-0x800000, 3)
        val m24 = monoFromPcmBytes(p24, p24.size, channels = 1, bytesPerSample = 3, AudioDownmixMode.AVERAGE)
        assertEquals(listOf(0.5f, -0.5f, -1f), m24.toList())
        val p32 = le(0x40000000, 4) + le(-0x40000000, 4) + le(Int.MIN_VALUE, 4)
        val m32 = monoFromPcmBytes(p32, p32.size, channels = 1, bytesPerSample = 4, AudioDownmixMode.AVERAGE)
        assertEquals(listOf(0.5f, -0.5f, -1f), m32.toList())
    }

    @Test
    fun onlyWholeFramesOfTheGivenLengthAreRead() {
        val pcm = le(100, 2) + le(100, 2) + le(200, 2) + le(200, 2) + le(300, 2)
        // Six of the ten bytes: one whole stereo frame and half of another.
        assertEquals(1, monoFromPcmBytes(pcm, 6, channels = 2, bytesPerSample = 2, AudioDownmixMode.AVERAGE).size)
        assertEquals(0, monoFromPcmBytes(pcm, 0, channels = 2, bytesPerSample = 2, AudioDownmixMode.AVERAGE).size)
    }

    @Test
    fun thePowerPreservingDownmixKeepsAHardPannedSignalAudible() {
        val pcm = le(16384, 2) + le(0, 2)
        val mono = monoFromPcmBytes(pcm, pcm.size, channels = 2, bytesPerSample = 2, AudioDownmixMode.PRESERVE_CHANNEL_POWER)
        // RMS across the channels, signed by the louder one: 0.5 / sqrt(2), where the average would give 0.25.
        assertEquals(0.35355f, mono.single(), 1e-4f)
    }

    @Test
    fun aLayoutNoDecoderProducesIsRefused() {
        assertFailsWith<IllegalArgumentException> { monoFromPcmBytes(ByteArray(4), 4, channels = 0, bytesPerSample = 2, AudioDownmixMode.AVERAGE) }
        assertFailsWith<IllegalArgumentException> { monoFromPcmBytes(ByteArray(4), 4, channels = 1, bytesPerSample = 1, AudioDownmixMode.AVERAGE) }
    }
}
