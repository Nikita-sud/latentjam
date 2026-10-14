/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records PCM immediately AFTER the production EQ, before AudioTrack; output volume is zero. */
class EqualizerPlaybackDeviceTest {
    @Test fun presetsChangeDecodedAudioThroughTheProductionMedia3Chain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "equalizer-tone.wav")
        writeTone(input)
        val amplitudes = mutableMapOf<String, List<Double>>()
        try {
            for (mode in listOf("off", "bass", "treble", "boost", "stacked")) {
                val capture = Capture()
                val end = CountDownLatch(1)
                var failure: PlaybackException? = null
                lateinit var player: ExoPlayer
                instrumentation.runOnMainSync {
                    val controller = AndroidEqualizerController.get(context)
                    runBlocking {
                        controller.reset()
                        controller.setEnabled(mode != "off")
                        when (mode) {
                            "bass" -> controller.applyPreset(1)
                            "treble" -> controller.applyPreset(2)
                            "boost" -> controller.setBassBoost(1000)
                            "stacked" -> { controller.applyPreset(1); controller.setBassBoost(1000) }
                        }
                    }
                    val factory = object : EqualizerRenderersFactory(context) {
                        override fun buildAudioProcessors(context: Context): Array<AudioProcessor> =
                            super.buildAudioProcessors(context) + capture
                    }
                    player = ExoPlayer.Builder(context, factory).build()
                    player.volume = 0f
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) end.countDown()
                        }
                        override fun onPlayerError(error: PlaybackException) { failure = error; end.countDown() }
                    })
                    player.setMediaItem(MediaItem.fromUri(input.toURI().toString()))
                    player.prepare()
                    player.play()
                }
                try {
                    assertTrue("Playback timed out: $mode", end.await(15, TimeUnit.SECONDS))
                    assertTrue("Playback failed: $failure", failure == null)
                    assertTrue("Lookahead must preserve every frame, including the tail", capture.count == 48000)
                    amplitudes[mode] = capture.amplitudes()
                    Log.i("LJEqualizerTest", "$mode: amplitudes=${capture.amplitudes()} frames=${capture.count}")
                } finally {
                    instrumentation.runOnMainSync { player.release() }
                }
            }
            fun relative(mode: String, a: Int, b: Int): Double {
                val changed = amplitudes.getValue(mode)
                val flat = amplitudes.getValue("off")
                return 20 * log10((changed[a] / changed[b]) / (flat[a] / flat[b]))
            }
            assertTrue("Bass preset must change the waveform", relative("bass", 0, 1) > 5)
            assertTrue("Treble preset must change the waveform", relative("treble", 2, 1) > 4)
            assertTrue("Bass boost must change the waveform", relative("boost", 0, 1) > 7)
            for (mode in listOf("boost", "stacked")) {
                val midDb = 20 * log10(amplitudes.getValue(mode)[1] / amplitudes.getValue("off")[1])
                val bassDb = 20 * log10(amplitudes.getValue(mode)[0] / amplitudes.getValue("off")[0])
                assertTrue("$mode must preserve midrange level: $midDb dB", midDb > -2.1)
                assertTrue("$mode must raise bass absolutely: $bassDb dB", bassDb > 6.5)
                Log.i("LJEqualizerTest", "$mode absolute bass=$bassDb dB mid=$midDb dB")
            }
            Log.i("LJEqualizerTest", "PASS bass=${relative("bass", 0, 1)} dB treble=${relative("treble", 2, 1)} dB boost=${relative("boost", 0, 1)} dB")
        } finally { input.delete() }
    }

    @Test fun limiterMeterReportsReductionFromTheProductionMedia3Chain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "equalizer-meter.wav")
        writeTone(input, level = 25000, frequencies = listOf(40))
        val controller = AndroidEqualizerController.get(context)
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                runBlocking {
                    controller.reset()
                    controller.setEnabled(true)
                    controller.setBassBoost(1000)
                }
                player = ExoPlayer.Builder(context, EqualizerRenderersFactory(context)).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(input.toURI().toString()))
                    prepare()
                    play()
                }
            }
            val reading = runBlocking {
                withTimeout(5000) {
                    controller.processing.first { it.sampleRateHz == 48000 && (it.limiterReductionDb ?: 0f) > 3f }
                }
            }
            Log.i("LJEqualizerTest", "Production meter: ${reading.sampleRateHz} Hz, ${reading.limiterReductionDb} dB peak reduction")
            assertTrue(reading.limiterReductionDb!! < 12f)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            input.delete()
        }
    }

    @Test fun processingIsFasterThanRealtimeOnTheDevice() {
        val processor = SoftwareEqualizer(48000, 2, SoftwareEqualizerSettings(true, List(10) { 600 }, 1000))
        val frame = floatArrayOf(0.1f, -0.1f)
        repeat(48000) { frame[0] = 0.1f; frame[1] = -0.1f; processor.process(frame) }
        val start = System.nanoTime()
        repeat(480000) { index ->
            frame[0] = if (index % 2 == 0) 0.2f else -0.2f
            frame[1] = -frame[0]
            processor.process(frame)
        }
        val elapsedMs = (System.nanoTime() - start) / 1e6
        Log.i("LJEqualizerTest", "10 seconds stereo, all bands + bass: $elapsedMs ms elapsed")
        assertTrue("Processing must keep up with audio", elapsedMs < 5000)
    }

    private class Capture : BaseAudioProcessor() {
        var count = 0
        private val re = DoubleArray(3)
        private val im = DoubleArray(3)
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            check(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            while (inputBuffer.hasRemaining()) {
                val left = inputBuffer.short
                output.putShort(left)
                for (channel in 1 until inputAudioFormat.channelCount) output.putShort(inputBuffer.short)
                if (count in 12000 until 48000) {
                    FREQUENCIES.forEachIndexed { i, frequency ->
                        val phase = 2 * PI * frequency * count / inputAudioFormat.sampleRate
                        re[i] += left * cos(phase)
                        im[i] += left * sin(phase)
                    }
                }
                count++
            }
            output.flip()
        }
        fun amplitudes(): List<Double> = re.indices.map { kotlin.math.sqrt(re[it] * re[it] + im[it] * im[it]) / 18000 }
    }

    private fun writeTone(file: File, level: Int = 2000, frequencies: List<Int> = FREQUENCIES) {
        val frames = 48000
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { index ->
            val sample = frequencies.sumOf { frequency -> level * sin(2 * PI * frequency * index / 48000) }.toInt().toShort()
            bytes.putShort(sample).putShort(sample)
        }
        file.writeBytes(bytes.array())
    }

    private companion object { val FREQUENCIES = listOf(40, 1000, 8000) }
}
