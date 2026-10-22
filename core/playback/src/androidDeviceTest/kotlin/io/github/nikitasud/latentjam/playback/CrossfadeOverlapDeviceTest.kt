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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two real files through the production Media3 chain with an overlap: the PCM after the equalizer
 * must hold the outgoing tone and the incoming saw at once, then the incoming track exactly once
 * — every frame of it, none twice — and the player must report the incoming track's own position.
 */
class CrossfadeOverlapDeviceTest {
    @Test fun theNextTrackOverlapsTheEndAndContinuesWithoutRepeatingItsOpening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outgoing = File(context.cacheDir, "crossfade-a.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val incoming = File(context.cacheDir, "crossfade-b.wav").also { writeWav(it, INCOMING_FRAMES, ::saw) }
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS }
        val capture = Capture()
        val end = CountDownLatch(1)
        var failure: PlaybackException? = null
        var incomingPositionAtStart = C.TIME_UNSET
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                runBlocking {
                    AndroidEqualizerController.get(context).reset()
                    AndroidEqualizerController.get(context).setEnabled(false)
                }
                val factory = object : EqualizerRenderersFactory(context, settings) {
                    override fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> =
                        super.buildAudioProcessors(context, restart) + capture
                }
                player = ExoPlayer.Builder(context, factory).build()
                player.volume = 0f
                player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (mediaItem?.mediaId == "b") incomingPositionAtStart = player.currentPosition
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) end.countDown()
                    }
                    override fun onPlayerError(error: PlaybackException) { failure = error; end.countDown() }
                })
                player.setMediaItems(
                    listOf(
                        MediaItem.Builder().setMediaId("a").setUri(outgoing.toURI().toString()).build(),
                        MediaItem.Builder().setMediaId("b").setUri(incoming.toURI().toString()).build(),
                    ),
                )
                coordinator = CrossfadeCoordinator(context, player, settings)
                coordinator.start()
                player.prepare()
                player.play()
            }
            assertTrue("Playback timed out", end.await(30, TimeUnit.SECONDS))
            assertTrue("Playback failed: $failure", failure == null)

            val heard = OVERLAP_MS.toInt() * RATE / 1000
            val samples = capture.samples()
            Log.i("LJCrossfadeTest", "frames=${samples.size} expected=${OUTGOING_FRAMES + INCOMING_FRAMES - heard} " +
                "positionAtStart=$incomingPositionAtStart")
            // Every frame of the incoming track exactly once: its opening under the outgoing end.
            assertEquals(OUTGOING_FRAMES + INCOMING_FRAMES - heard, samples.size)
            // Live playback of the incoming track resumes on the very frame after the overlap.
            for (offset in 0 until 1000) {
                assertEquals("frame ${heard + offset} of the incoming track", saw(heard + offset), samples[OUTGOING_FRAMES + offset])
            }
            // The last frame of the overlap is the incoming track's previous frame, the outgoing tone gone.
            assertTrue(abs(samples[OUTGOING_FRAMES - 1] - saw(heard - 1)) <= 2)
            // Halfway, both tracks sound: the saw's steps under the tone.
            val mixStart = OUTGOING_FRAMES - heard
            for (frame in mixStart + heard / 2 until mixStart + heard / 2 + 100) {
                val progress = (frame - mixStart).toFloat() / heard
                val incomingPart = samples[frame] - tone(frame) * overlapOutGain(progress)
                assertTrue(
                    "overlap frame $frame",
                    abs(incomingPart - saw(frame - mixStart) * overlapInGain(progress)) <= 2f,
                )
            }
            // The player's clock jumps with the sound: the incoming track starts at the heard point.
            assertTrue("position at the join was $incomingPositionAtStart", incomingPositionAtStart >= OVERLAP_MS - 300)
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            outgoing.delete()
            incoming.delete()
        }
    }

    private class Capture : BaseAudioProcessor() {
        private val left = ArrayList<Int>()
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            check(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            synchronized(left) {
                while (inputBuffer.hasRemaining()) {
                    val sample = inputBuffer.short
                    left += sample.toInt()
                    output.putShort(sample)
                    for (channel in 1 until inputAudioFormat.channelCount) output.putShort(inputBuffer.short)
                }
            }
            output.flip()
        }
        fun samples(): List<Int> = synchronized(left) { left.toList() }
    }

    private fun writeWav(file: File, frames: Int, sample: (Int) -> Int) {
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { index ->
            val value = sample(index).toShort()
            bytes.putShort(value).putShort(value)
        }
        file.writeBytes(bytes.array())
    }

    private companion object {
        const val RATE = 48000
        const val OVERLAP_MS = 2_000L
        const val OUTGOING_FRAMES = RATE * 6
        const val INCOMING_FRAMES = RATE * 5

        fun tone(index: Int): Int = (6000 * sin(2 * PI * 440 * index / RATE)).toInt()

        /** A different value on every frame, so one frame too early or late is visible. */
        fun saw(index: Int): Int = (index * 7) % 12000 - 6000
    }
}
