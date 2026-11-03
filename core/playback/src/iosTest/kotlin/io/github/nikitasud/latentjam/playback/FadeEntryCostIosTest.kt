/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package io.github.nikitasud.latentjam.playback

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.Foundation.*
import kotlin.math.*
import kotlin.test.*
import kotlin.time.TimeSource

/**
 * What measuring a track's ending costs the iOS loudness scan: the scan of a 4-minute stereo file,
 * then the sound-span and ending trackers alone on the same PCM (median of three). Printed for the
 * report; asserts only that the ending costs less than the scan and that the scan finds the fade.
 */
internal class FadeEntryCostIosTest {
    @Test fun theEndingCostsLittleNextToTheScanItRidesOn(): Unit = runBlocking {
        val rate = 44_100
        val seconds = 240
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "lj-fade-cost-" + NSUUID().UUIDString + ".caf")
        try {
            val format = AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 2u)
            autoreleasepool {
                val file = AVAudioFile(forWriting = url, settings = format.settings, error = null)
                val block = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = 4410u)
                repeat(seconds * 10) { index ->
                    block.frameLength = 4410u
                    val data = checkNotNull(block.floatChannelData)
                    for (i in 0 until 4410) {
                        val t = (index * 4410 + i).toDouble() / rate
                        // Full level, then a 20 s dB-linear fade of 60 dB at the end.
                        val db = if (t < seconds - 20) 0.0 else -3.0 * (t - (seconds - 20))
                        val sample = (0.2 * 10.0.pow(db / 20) * sin(2 * PI * 220 * t)).toFloat()
                        data[0]!![i] = sample; data[1]!![i] = sample
                    }
                    assertTrue(file.writeFromBuffer(block, error = null))
                }
            }
            // Off by default: the plain scan returns no ending at all.
            assertNull(assertNotNull(IosIntegratedLoudnessMeter().measure(checkNotNull(url.absoluteString), seconds * 1000L)).fade)
            val meter = IosIntegratedLoudnessMeter(measuresFadeEnds = true)
            var fade: FadeEnd? = null
            val scanMs = (0 until 3).map {
                val mark = TimeSource.Monotonic.markNow()
                fade = assertNotNull(meter.measure(checkNotNull(url.absoluteString), seconds * 1000L)).fade
                mark.elapsedNow().inWholeMilliseconds
            }.sorted()[1]
            val chunk = FloatArray(LoudnessScanLimits.CHUNK_FRAMES * 2) { i -> (0.2 * sin(2 * PI * 220 * (i / 2) / rate)).toFloat() }
            val chunks = rate * seconds / LoudnessScanLimits.CHUNK_FRAMES
            fun ms(feed: () -> Unit): Long = (0 until 3).map {
                val mark = TimeSource.Monotonic.markNow()
                feed()
                mark.elapsedNow().inWholeMilliseconds
            }.sorted()[1]
            val spanMs = ms { SoundSpanTracker(rate, 2).also { t -> repeat(chunks) { t.add(chunk, LoudnessScanLimits.CHUNK_FRAMES) } }.finish() }
            val endMs = ms { FadeEndTracker(rate, 2).also { t -> repeat(chunks) { t.add(chunk, LoudnessScanLimits.CHUNK_FRAMES) } }.finish() }
            println("LJFadeEntryCost ios: scan(4 min)=$scanMs ms; soundSpan=$spanMs ms; fadeEnd=$endMs ms; share=${(1000 * endMs / maxOf(scanMs, 1)) / 10.0} %; fade=$fade")
            assertTrue(endMs < scanMs)
            assertNotNull(fade?.kneeLeadMs)
        } finally { NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
    }
}
