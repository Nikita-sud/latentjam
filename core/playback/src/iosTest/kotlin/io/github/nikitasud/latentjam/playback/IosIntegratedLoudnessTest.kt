/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.Foundation.*
import kotlin.math.*
import kotlin.test.*

internal class IosIntegratedLoudnessTest {
    @Test fun fileDecoderMeasuresFullTrackWithoutCancellingOppositeChannels() = runBlocking {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "lj-loudness-" + NSUUID().UUIDString + ".caf")
        try {
            val format = AVAudioFormat(standardFormatWithSampleRate = 48000.0, channels = 2u)
            autoreleasepool {
                val file = AVAudioFile(forWriting = url, settings = format.settings, error = null)
                val block = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = 4800u)
                repeat(120) { index ->
                    block.frameLength = 4800u
                    val data = checkNotNull(block.floatChannelData)
                    for (i in 0 until 4800) {
                        val amplitude = if (index < 80) 0.01 else 0.1
                        val sample = (amplitude * sin(2 * PI * 1000 * (index * 4800 + i) / 48000)).toFloat()
                        data[0]!![i] = sample; data[1]!![i] = -sample
                    }
                    assertTrue(file.writeFromBuffer(block, error = null))
                }
            }
            val meter = IosIntegratedLoudnessMeter()
            val result = assertNotNull(meter.measure(checkNotNull(url.absoluteString), 12000))
            assertEquals(-20f, result.lufs, 0.3f)
            assertEquals(-20f, result.truePeakDbtp, 0.15f)
            assertNull(meter.measure("ipod-library://item/item.mp3?id=123", 12000))
            assertNull(meter.measure(checkNotNull(url.absoluteString), 180000))
        } finally { NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
    }
}
