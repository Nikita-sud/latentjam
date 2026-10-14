/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.playback.nativeeq.*
import kotlinx.cinterop.*
import platform.AVFAudio.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class IosSharedAudioUnitTest {
    @Test fun audioUnitMatchesTheCoreIncludingTheDelayedEndOfTheInput() {
        for (rate in listOf(44100, 48000)) for (channels in listOf(1, 2)) for (boosted in listOf(false, true)) {
            val engine = AVAudioEngine()
            val player = AVAudioPlayerNode()
            val unit = checkNotNull(lj_eq_ios_create())
            val format = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = channels.toUInt())
            assertTrue(engine.enableManualRenderingMode(AVAudioEngineManualRenderingModeOffline, format, 512u, null))
            engine.attachNode(player); engine.attachNode(unit)
            engine.connect(player, unit, format); engine.connect(unit, engine.outputNode, format)
            val settings = SoftwareEqualizerSettings(enabled = boosted, levels = SoftwareEqualizerSettings.PRESETS[1], bass = if (boosted) 1000 else 0)
            settings.levels.toIntArray().usePinned { lj_eq_ios_set_settings(unit, if (boosted) 1 else 0, it.addressOf(0), settings.bass) }
            val reference = NativeEqualizer(rate, channels, settings)
            val frames = rate / 5
            val total = frames + reference.latencyFrames
            val expected = FloatArray(total * channels)
            val input = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt())
            input.frameLength = frames.toUInt()
            val source = checkNotNull(input.floatChannelData)
            for (i in 0 until frames) for (c in 0 until channels) {
                val sample = ((0.6 * sin(2 * PI * 40 * i / rate) + 0.15 * sin(2 * PI * 1000 * i / rate)) * if (c % 2 == 0) 1.0 else -0.25).toFloat()
                source[c]!![i] = sample
                expected[i * channels + c] = sample
            }
            reference.process(expected, total)
            try {
                player.scheduleBuffer(input, completionHandler = null)
                assertTrue(engine.startAndReturnError(null))
                player.play()
                val output = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = 512u)
                var offset = 0
                while (offset < total) {
                    val count = minOf(512, total - offset)
                    assertEquals(AVAudioEngineManualRenderingStatusSuccess, engine.renderOffline(count.toUInt(), output, null))
                    val result = checkNotNull(output.floatChannelData)
                    for (i in 0 until count) for (c in 0 until channels) {
                        assertTrue(abs(result[c]!![i] - expected[(offset + i) * channels + c]) < 0.00002f,
                            "$rate Hz, $channels channels, boosted=$boosted, sample=${offset + i}")
                    }
                    offset += count
                }
            } finally { player.stop(); engine.stop(); reference.close() }
        }
    }
}
