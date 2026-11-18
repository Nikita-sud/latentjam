/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class IntegratedLoudnessDeviceTest {
    @Test fun fullTrackDecoderPreservesStereoAndMeasuresBeyondTheOldThreeWindows(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "loudness-full-stereo.wav")
        try {
            writeTone(file, seconds = 12)
            val started = System.nanoTime()
            val result = withTimeout(30_000) { AndroidIntegratedLoudnessMeter(context).measure(Uri.fromFile(file).toString(), 12000) }
            assertNotNull("System decoder must produce a complete measurement", result)
            // First 8 seconds are quiet, final 4 are 20 dB louder: relative gating excludes the
            // quiet prefix. Old sample positions (10%, 40%, 70%) don't cover this in full.
            assertEquals(-20f, result!!.lufs, 0.3f)
            assertEquals(-20f, result.truePeakDbtp, 0.15f)
            Log.i("LJLoudnessTest", "LUFS=${result.lufs}, dBTP=${result.truePeakDbtp}, wallMs=${(System.nanoTime()-started)/1000000}")
        } finally { file.delete() }
    }

    @Test fun aOneChannelFileMeasuresAsLoudAsTheSameToneInTwoChannels(): Unit = runBlocking {
        // It plays from both speakers at the file's level. Counted once (until 0.9.6) it read 3 LU lower, and
        // normalization left it 3 dB louder than its two-channel twin.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mono = File(context.cacheDir, "loudness-mono.wav")
        val twin = File(context.cacheDir, "loudness-mono-twin.wav")
        try {
            writeTone(mono, seconds = 12, channels = 1)
            writeTone(twin, seconds = 12, channels = 2, invertRight = false)
            val meter = AndroidIntegratedLoudnessMeter(context)
            val one = withTimeout(30_000) { meter.measure(Uri.fromFile(mono).toString(), 12000) }
            val two = withTimeout(30_000) { meter.measure(Uri.fromFile(twin).toString(), 12000) }
            assertNotNull(one); assertNotNull(two)
            Log.i("LJLoudnessTest", "mono LUFS=${one!!.lufs}, twin LUFS=${two!!.lufs}")
            assertEquals(-20f, two.lufs, 0.3f)
            assertEquals(two.lufs, one.lufs, 0.15f)
            assertEquals(two.truePeakDbtp, one.truePeakDbtp, 0.05f)
        } finally { mono.delete(); twin.delete() }
    }

    @Test fun cancellationAndRejectedSourcesReleaseDecoderForTheNextScan(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "loudness-cancel.wav")
        try {
            writeTone(file, seconds = 120)
            val meter = AndroidIntegratedLoudnessMeter(context)
            var published = false
            val job = launch(Dispatchers.Default) { meter.measure(Uri.fromFile(file).toString(), 120000); published = true }
            delay(25)
            job.cancelAndJoin()
            assertFalse("A cancelled scan cannot return a result", published)
            assertNull(meter.measure("https://example.invalid/audio", null))
            assertNull(meter.measure(Uri.fromFile(file).toString(), 7L * 3600000))
            writeTone(file, seconds = 12)
            assertNotNull(withTimeout(30_000) { meter.measure(Uri.fromFile(file).toString(), 12000) })
        } finally { file.delete() }
    }

    @Test fun compressedMusicFormatsReachFullEosWithConsistentLoudness(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val meter = AndroidIntegratedLoudnessMeter(context)
        for (name in listOf("tone.flac", "tone.mp3", "tone.m4a")) {
            val file = File(context.cacheDir, "loudness-" + name)
            try {
                instrumentation.context.assets.open("loudness/" + name).use { input -> file.outputStream().use(input::copyTo) }
                val result = withTimeout(30_000) { meter.measure(Uri.fromFile(file).toString(), 3000) }
                assertNotNull(name, result)
                assertEquals(name, -20f, result!!.lufs, 0.65f)
                assertTrue(name, result.truePeakDbtp in -22f..-17f)
                Log.i("LJLoudnessTest", "$name: LUFS=${result.lufs}, dBTP=${result.truePeakDbtp}")
            } finally { file.delete() }
        }
    }

    /** A 16-bit WAV of [channels] channels; with two, the right one is the left one, turned over when [invertRight]. */
    private fun writeTone(file: File, seconds: Int, channels: Int = 2, invertRight: Boolean = true) {
        val rate = 48000
        val frames = rate * seconds
        val frameBytes = channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + frames * frameBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
            .put("data".toByteArray()).putInt(frames * frameBytes)
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            val block = ByteBuffer.allocate(4800 * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
            var start = 0
            while (start < frames) {
                block.clear()
                repeat(minOf(4800, frames - start)) { i ->
                    val amplitude = if (start + i < rate * (seconds - 4)) 0.01 else 0.1
                    val value = (32767 * amplitude * sin(2 * PI * 1000 * (start + i) / rate)).toInt().toShort()
                    block.putShort(value)
                    if (channels == 2) block.putShort(if (invertRight) (-value).toShort() else value)
                }
                out.write(block.array(), 0, block.position()); start += 4800
            }
        }
    }
}
