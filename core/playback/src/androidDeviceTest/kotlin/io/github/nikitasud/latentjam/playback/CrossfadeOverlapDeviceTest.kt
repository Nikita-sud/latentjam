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
            // Halfway through the overlap, the landing the session player uses for Next (from any
            // controller) is the point of the next track already heard.
            var landing: CrossfadeCoordinator.Landing? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (landing == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                instrumentation.runOnMainSync {
                    if (player.currentMediaItemIndex == 0 && player.currentPosition >= 5_000) {
                        landing = coordinator.nextFromOverlap()
                    }
                }
            }
            assertTrue("Playback timed out", end.await(30, TimeUnit.SECONDS))
            assertTrue("Playback failed: $failure", failure == null)
            Log.i("LJCrossfadeTest", "next lands at $landing")
            assertEquals(1, landing?.index)
            assertTrue("heard ${landing?.positionMs}", (landing?.positionMs ?: 0L) in 500L..2_000L)

            // A third of the shorter track at most: 1666 ms of the 5 s incoming one.
            val overlapMs = overlapLengthMs(OVERLAP_MS, OUTGOING_FRAMES * 1000L / RATE, INCOMING_FRAMES * 1000L / RATE)
            val heard = (overlapMs * RATE / 1000).toInt()
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
            assertTrue("position at the join was $incomingPositionAtStart", incomingPositionAtStart >= overlapMs - 300)
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            outgoing.delete()
            incoming.delete()
        }
    }

    /**
     * Next pressed mid-overlap through the coordinator's own seek, as the session player does: a
     * real seek, with the renderer disable and flushes it brings. The incoming track — a constant
     * level, so its gain reads straight off the output — must carry on at the overlap curve's level
     * for the point heard, not restart a linear fade there.
     */
    @Test fun nextDuringAnOverlapKeepsTheIncomingLevelThroughARealSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outgoing = File(context.cacheDir, "crossfade-seek-a.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val incoming = File(context.cacheDir, "crossfade-seek-b.wav").also { writeWav(it, INCOMING_FRAMES, { LEVEL }) }
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS }
        val capture = Capture()
        var landing: CrossfadeCoordinator.Landing? = null
        var capturedAtSeek = 0
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                runBlocking { AndroidEqualizerController.get(context).setEnabled(false) }
                val factory = object : EqualizerRenderersFactory(context, settings) {
                    override fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> =
                        super.buildAudioProcessors(context, restart) + capture
                }
                player = ExoPlayer.Builder(context, factory).build()
                player.volume = 0f
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
            val deadline = System.currentTimeMillis() + 20_000
            while (landing == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                instrumentation.runOnMainSync {
                    if (player.currentMediaItemIndex == 0 && player.currentPosition >= 5_000) {
                        landing = coordinator.nextFromOverlap()?.also {
                            capturedAtSeek = capture.samples().size
                            coordinator.seekTo(it)
                        }
                    }
                }
            }
            val seek = checkNotNull(landing) { "no overlap to leave" }
            Thread.sleep(1_000)
            val samples = capture.samples()
            // The incoming track after the seek is the first long run without the outgoing tone's
            // swing: a constant level times a slowly rising gain.
            val start = (capturedAtSeek - RATE until samples.size - RATE / 10).first { from ->
                (from until from + RATE / 20).all { abs(samples[it + 1] - samples[it]) < 40 } && samples[from] > 100
            }
            // Past the equalizer's 5 ms restart rise: average 10 ms.
            val window = samples.subList(start + RATE / 100, start + RATE / 50)
            val gain = window.average().toFloat() / LEVEL
            val curve = overlapInGain(seek.positionMs.toFloat() * 1000 / seek.riseUs)
            val linear = seek.positionMs.toFloat() / OVERLAP_MS
            Log.i("LJCrossfadeTest", "seek to ${seek.positionMs} ms of ${seek.riseUs} us: gain=$gain curve=$curve linear=$linear")
            assertTrue("gain $gain should follow the curve $curve, not the fade $linear", abs(gain - curve) < 0.06f)
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            outgoing.delete()
            incoming.delete()
        }
    }

    /** Dragging the slider while an overlap sounds leaves that overlap alone. */
    @Test fun movingTheSliderDuringAnOverlapLetsItFinish() = dragLetsTheOverlapFinish(dragAtMs = 5_000)

    /** The same between the moment the mix is settled (the 2 s window) and its first sound. */
    @Test fun movingTheSliderBetweenTheSettledMixAndItsSoundLetsItFinish() = dragLetsTheOverlapFinish(dragAtMs = 4_000)

    private fun dragLetsTheOverlapFinish(dragAtMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outgoing = File(context.cacheDir, "crossfade-drag-a.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val incoming = File(context.cacheDir, "crossfade-drag-b.wav").also { writeWav(it, INCOMING_FRAMES, ::saw) }
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS }
        val capture = Capture()
        val end = CountDownLatch(1)
        var moved = false
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                runBlocking { AndroidEqualizerController.get(context).setEnabled(false) }
                val factory = object : EqualizerRenderersFactory(context, settings) {
                    override fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> =
                        super.buildAudioProcessors(context, restart) + capture
                }
                player = ExoPlayer.Builder(context, factory).build()
                player.volume = 0f
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) end.countDown()
                    }
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
            val deadline = System.currentTimeMillis() + 30_000
            while (end.count > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                instrumentation.runOnMainSync {
                    if (!moved && player.currentMediaItemIndex == 0 && player.currentPosition >= dragAtMs && settings.mixing != null) {
                        // A drag: several steps, each a new setting, within the overlap.
                        settings.fadeMs = 3_000
                        coordinator.update()
                        settings.fadeMs = 5_000
                        coordinator.update()
                        moved = true
                    }
                }
            }
            val samples = capture.samples()
            val overlapMs = overlapLengthMs(OVERLAP_MS, OUTGOING_FRAMES * 1000L / RATE, INCOMING_FRAMES * 1000L / RATE)
            val heard = (overlapMs * RATE / 1000).toInt()
            Log.i("LJCrossfadeTest", "slider moved=$moved frames=${samples.size} expected=${OUTGOING_FRAMES + INCOMING_FRAMES - heard}")
            assertTrue(moved)
            // The overlap played out: the opening heard under A is dropped once, as without a drag.
            assertEquals(OUTGOING_FRAMES + INCOMING_FRAMES - heard, samples.size)
            for (offset in 0 until 200) {
                assertEquals(saw(heard + offset), samples[OUTGOING_FRAMES + offset])
            }
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            outgoing.delete()
            incoming.delete()
        }
    }

    /** A next track shorter than the opening asked for is captured whole and still overlaps. */
    @Test fun aNextTrackShorterThanTheRequestedOpeningStillOverlaps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val shortFrames = RATE * 3
        val outgoing = File(context.cacheDir, "crossfade-short-a.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val incoming = File(context.cacheDir, "crossfade-short-b.wav").also { writeWav(it, shortFrames, ::saw) }
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS }
        val capture = Capture()
        val end = CountDownLatch(1)
        var planFrames = 0
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                runBlocking { AndroidEqualizerController.get(context).setEnabled(false) }
                val factory = object : EqualizerRenderersFactory(context, settings) {
                    override fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> =
                        super.buildAudioProcessors(context, restart) + capture
                }
                player = ExoPlayer.Builder(context, factory).build()
                player.volume = 0f
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) end.countDown()
                    }
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
            val deadline = System.currentTimeMillis() + 30_000
            while (end.count > 0 && System.currentTimeMillis() < deadline) {
                settings.plan?.let { planFrames = it.frames }
                Thread.sleep(20)
            }
            val samples = capture.samples()
            // 3 s is a third of itself at most: a 1 s overlap, the whole track captured.
            val overlapFrames = overlapLengthMs(OVERLAP_MS, OUTGOING_FRAMES * 1000L / RATE, 3_000L).toInt() * RATE / 1000
            Log.i("LJCrossfadeTest", "short next: plan frames=$planFrames frames=${samples.size} overlap=$overlapFrames")
            assertEquals(shortFrames, planFrames)
            assertEquals(OUTGOING_FRAMES + shortFrames - overlapFrames, samples.size)
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            outgoing.delete()
            incoming.delete()
        }
    }

    @Test fun aOneRowQueueOnRepeatAllLoopsFromItsStartWithoutOverlappingItself() =
        loopsWithoutOverlap(Player.REPEAT_MODE_ALL)

    @Test fun repeatOneLoopsFromItsStartWithoutOverlappingItself() = loopsWithoutOverlap(Player.REPEAT_MODE_ONE)

    @Test fun theDecodedOpeningStaysOffTheJavaHeap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 16 s at 96 kHz stereo: the 14 s an overlap of 12 s decodes is 5.4 MB of PCM16.
        val rate = 96_000
        val track = File(context.cacheDir, "crossfade-hires.wav").also { writeWav(it, rate * 16, { i -> (i % 4000) - 2000 }, rate) }
        val done = CountDownLatch(1)
        var intro: PcmIntro? = null
        fun usedHeap(): Long {
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100) }
            return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        }
        try {
            val before = usedHeap()
            instrumentation.runOnMainSync {
                CrossfadeIntroCapture(
                    context, MediaItem.fromUri(track.toURI().toString()), 14_000L,
                    IntroScratch.get(context), slot = 0, outputRate = 0,
                ) {
                    intro = it
                    done.countDown()
                }.start()
            }
            assertTrue("capture timed out", done.await(20, TimeUnit.SECONDS))
            val held = checkNotNull(intro)
            val after = usedHeap()
            val pcmBytes = held.frames.toLong() * held.channelCount * 2
            Log.i("LJCrossfadeTest", "intro frames=${held.frames} pcm=$pcmBytes heap delta=${after - before}")
            assertEquals(14 * rate, held.frames)
            assertEquals(-2000, held.pcm.getShort(0).toInt())
            assertTrue("heap grew ${after - before} for $pcmBytes of PCM", after - before < pcmBytes / 4)
        } finally {
            track.delete()
        }
    }

    private fun loopsWithoutOverlap(repeatMode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val track = File(context.cacheDir, "crossfade-loop.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS }
        val repeated = CountDownLatch(1)
        var positionAtRepeat = C.TIME_UNSET
        var planned = false
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context, EqualizerRenderersFactory(context, settings)).build()
                player.volume = 0f
                player.repeatMode = repeatMode
                player.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        ) {
                            positionAtRepeat = player.currentPosition
                            repeated.countDown()
                        }
                    }
                })
                player.setMediaItem(MediaItem.Builder().setMediaId("loop").setUri(track.toURI().toString()).build())
                coordinator = CrossfadeCoordinator(context, player, settings)
                coordinator.start()
                player.prepare()
                player.play()
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (repeated.count > 0 && System.currentTimeMillis() < deadline) {
                if (settings.plan != null) planned = true
                Thread.sleep(50)
            }
            Log.i("LJCrossfadeTest", "repeat mode $repeatMode position at repeat=$positionAtRepeat planned=$planned")
            assertEquals(0L, repeated.count)
            assertTrue("no overlap with itself", !planned)
            assertTrue("each repeat starts at the start: $positionAtRepeat", positionAtRepeat in 0L..500L)
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
            track.delete()
        }
    }

    @Test fun finishingTheTrackPlaysItOutAloneWithoutTheNextOpening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val outgoing = File(context.cacheDir, "crossfade-last-a.wav").also { writeWav(it, OUTGOING_FRAMES, ::tone) }
        val incoming = File(context.cacheDir, "crossfade-last-b.wav").also { writeWav(it, INCOMING_FRAMES, ::saw) }
        // The sleep timer's "end of track" is armed.
        val settings = CrossfadeSettings().apply { fadeMs = OVERLAP_MS; finishingTrack = true }
        val capture = Capture()
        val end = CountDownLatch(1)
        var planned = false
        lateinit var player: ExoPlayer
        lateinit var coordinator: CrossfadeCoordinator
        try {
            instrumentation.runOnMainSync {
                runBlocking { AndroidEqualizerController.get(context).setEnabled(false) }
                val factory = object : EqualizerRenderersFactory(context, settings) {
                    override fun buildAudioProcessors(context: Context, restart: AtomicBoolean): Array<AudioProcessor> =
                        super.buildAudioProcessors(context, restart) + capture
                }
                player = ExoPlayer.Builder(context, factory).build()
                player.volume = 0f
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) end.countDown()
                    }
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
            val deadline = System.currentTimeMillis() + 30_000
            while (end.count > 0 && System.currentTimeMillis() < deadline) {
                if (settings.plan != null) planned = true
                Thread.sleep(50)
            }
            val samples = capture.samples()
            Log.i("LJCrossfadeTest", "finishing frames=${samples.size} planned=$planned")
            assertTrue("no overlap was prepared", !planned)
            // Every frame of both tracks, one after the other: nothing of B under A's end.
            assertEquals(OUTGOING_FRAMES + INCOMING_FRAMES, samples.size)
            // A faded out on its own: its last frames are silent, B's first frame starts from zero.
            assertTrue((OUTGOING_FRAMES - 100 until OUTGOING_FRAMES).all { abs(samples[it]) <= 20 })
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

    private fun writeWav(file: File, frames: Int, sample: (Int) -> Int, rate: Int = RATE) {
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
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
        const val LEVEL = 10_000
        const val OUTGOING_FRAMES = RATE * 6
        const val INCOMING_FRAMES = RATE * 5

        fun tone(index: Int): Int = (6000 * sin(2 * PI * 440 * index / RATE)).toInt()

        /** A different value on every frame, so one frame too early or late is visible. */
        fun saw(index: Int): Int = (index * 7) % 12000 - 6000
    }
}
