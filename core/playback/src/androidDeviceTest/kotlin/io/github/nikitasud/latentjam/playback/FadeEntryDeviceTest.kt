/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.net.Uri
import android.os.Debug
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
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fade entries on a device: what measuring a track's ending costs the loudness scan, and a seam
 * through the production Media3 chain — measured by the real scan, entered under the outgoing
 * track's own fade, longer than the slider — with its PCM checked frame by frame.
 */
class FadeEntryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /**
     * The ending tracker's share of a full scan: the scan itself on a 4-minute stereo WAV (decode,
     * R128, sound span and ending together), then the two trackers alone on the same PCM, CPU time
     * on this thread, median of three. Logged for the report; asserts only that the ending costs
     * less than the scan.
     */
    @Test fun theEndingCostsLittleNextToTheScanItRidesOn(): Unit = runBlocking {
        val rate = 44_100
        val seconds = 240
        val file = File(context.cacheDir, "fade-cost.wav")
        try {
            writeWav(file, rate * seconds, rate) { (6000 * sin(2 * PI * 220 * it / rate)).toInt() }
            val meter = AndroidIntegratedLoudnessMeter(context)
            val scanMs = (0 until 3).map {
                val started = System.nanoTime()
                assertNotNull(withTimeout(120_000) { meter.measure(Uri.fromFile(file).toString(), seconds * 1000L) })
                (System.nanoTime() - started) / 1_000_000
            }.sorted()[1]
            val chunk = FloatArray(LoudnessScanLimits.CHUNK_FRAMES * 2) { i -> (0.2 * sin(2 * PI * 220 * (i / 2) / rate)).toFloat() }
            val chunks = rate * seconds / LoudnessScanLimits.CHUNK_FRAMES
            fun cpuMs(feed: () -> Unit): Long = (0 until 3).map {
                val started = Debug.threadCpuTimeNanos()
                feed()
                (Debug.threadCpuTimeNanos() - started) / 1_000_000
            }.sorted()[1]
            val spanMs = cpuMs {
                val t = SoundSpanTracker(rate, 2)
                repeat(chunks) { t.add(chunk, LoudnessScanLimits.CHUNK_FRAMES) }
                t.finish()
            }
            val endMs = cpuMs {
                val t = FadeEndTracker(rate, 2)
                repeat(chunks) { t.add(chunk, LoudnessScanLimits.CHUNK_FRAMES) }
                t.finish()
            }
            // The tracker's own memory: its block ring, measured as the heap it adds while alive.
            System.gc(); Thread.sleep(200)
            val before = usedHeap()
            val held = List(50) { FadeEndTracker(rate, 2) }
            val perTrackerBytes = (usedHeap() - before) / held.size
            Log.i(TAG, "cost: scan(4 min, wall)=$scanMs ms; soundSpan(cpu)=$spanMs ms; fadeEnd(cpu)=$endMs ms; " +
                "fadeEnd share of scan=${"%.1f".format(100.0 * endMs / scanMs)} %; tracker heap≈$perTrackerBytes B; " +
                "maxHeap=${Runtime.getRuntime().maxMemory() / 1048576} MB")
            assertTrue(endMs < scanMs)
        } finally { file.delete() }
    }

    /** What 1k and 10k measured endings hold on the heap and add to the stored cache. */
    @Test fun endingsForALargeLibraryStaySmall() {
        for (count in listOf(1_000, 10_000)) {
            // The app already holds the ids (the library, the loudness map): only the endings are new.
            val ids = List(count) { "track-%08d".format(it) }
            System.gc(); Thread.sleep(200)
            val before = usedHeap()
            val ends = HashMap<String, FadeEnd>(count * 2)
            ids.forEachIndexed { i, id -> ends[id] = FadeEnd(200_000L + i, 12_000L + i % 7, (8_000L + i % 5).takeIf { i % 3 != 0 }) }
            val heap = usedHeap() - before
            // Each stored row grows by "|last|knee|visible".
            val rowBytes = ends.values.sumOf { "|${it.lastSoundMs}|${it.kneeLeadMs ?: -1}|${it.visibleLeadMs ?: -1}".length.toLong() }
            Log.i(TAG, "library $count: endings map≈${heap / 1024} KB heap (ids shared); cache rows +${rowBytes / 1024} KB")
            assertEquals(count, ends.size)
        }
    }

    /**
     * A track that fades out on its own, measured by the real scan, then played into the next one
     * with the slider at 2 s: the next track rises under the whole measured fade (longer than the
     * slider), the outgoing track keeps its own level until it starts, and every frame of the next
     * track sounds exactly once.
     */
    @Test fun aMeasuredFadeOutBringsTheNextTrackInUnderIt() {
        val outFrames = RATE * OUT_S
        val inFrames = RATE * IN_S
        val outgoing = File(context.cacheDir, "fade-entry-a.wav").also { writeWav(it, outFrames, RATE, ::fadingTone) }
        val incoming = File(context.cacheDir, "fade-entry-b.wav").also { writeWav(it, inFrames, RATE, ::saw) }
        try {
            val measured = runBlocking {
                val meter = AndroidIntegratedLoudnessMeter(context)
                listOf(outgoing, incoming).map { withTimeout(60_000) { meter.measure(Uri.fromFile(it).toString(), null) } }
            }
            val fade = checkNotNull(measured[0]?.fade)
            Log.i(TAG, "seam: measured $fade, spans ${measured[0]?.sound} / ${measured[1]?.sound}")
            val settings = CrossfadeSettings().apply {
                fadeMs = SLIDER_MS
                soundSpans = mapOf("a" to measured[0]!!.sound!!, "b" to measured[1]!!.sound!!)
                fadeEnds = mapOf("a" to fade)
                fadeEntryRule = FadeEntryRule.KNEE
            }
            val run = play(outgoing, incoming, settings)
            val anchors = seamAnchors(outFrames * 1000L / RATE, measured[0]!!.sound, measured[1]!!.sound)
            val overlapMs = checkNotNull(fadeEntryOverlapMs(fade, FadeEntryRule.KNEE, anchors.outgoingEndMs, inFrames * 1000L / RATE))
            Log.i(TAG, "seam: overlap $overlapMs ms (slider $SLIDER_MS), frames=${run.size}, anchors=$anchors")
            assertTrue("the entry is longer than the slider", overlapMs > SLIDER_MS)
            val heard = (overlapMs * RATE / 1000).toInt()
            val lead = (anchors.incomingStartMs * RATE / 1000).toInt()
            val endFrame = (anchors.outgoingEndMs * RATE / 1000).toInt()
            val mixStart = endFrame - heard
            // Every frame of B once: A to its end, then B from where the overlap left it.
            assertEquals(outFrames + inFrames - (heard + lead + (outFrames - endFrame)), run.size)
            // Before the entry A plays untouched; inside it A stays at its own (fading) level.
            for (frame in mixStart - 2_000 until mixStart) assertEquals("frame $frame", fadingTone(frame), run[frame])
            for (frame in mixStart + heard / 2 until mixStart + heard / 2 + 200) {
                val progress = (frame - mixStart).toFloat() / heard
                val bPart = run[frame] - fadingTone(frame)
                assertTrue("entry frame $frame", abs(bPart - saw(lead + frame - mixStart) * overlapInGain(progress)) <= 3f)
            }
        } finally {
            outgoing.delete()
            incoming.delete()
        }
    }

    /** A cold end measures no fade: the same seam keeps the slider's crossfade. */
    @Test fun aColdEndKeepsTheCrossfade() {
        val outFrames = RATE * 20
        val inFrames = RATE * 12
        val outgoing = File(context.cacheDir, "fade-entry-cold.wav").also { writeWav(it, outFrames, RATE, ::tone) }
        val incoming = File(context.cacheDir, "fade-entry-b2.wav").also { writeWav(it, inFrames, RATE, ::saw) }
        try {
            val measured = runBlocking {
                withTimeout(60_000) { AndroidIntegratedLoudnessMeter(context).measure(Uri.fromFile(outgoing).toString(), null) }
            }
            val fade = checkNotNull(measured?.fade)
            assertNull(fade.kneeLeadMs)
            val settings = CrossfadeSettings().apply {
                fadeMs = SLIDER_MS
                fadeEnds = mapOf("a" to fade)
                fadeEntryRule = FadeEntryRule.KNEE
            }
            val run = play(outgoing, incoming, settings)
            val heard = (overlapLengthMs(SLIDER_MS, outFrames * 1000L / RATE, inFrames * 1000L / RATE) * RATE / 1000).toInt()
            Log.i(TAG, "cold: frames=${run.size}, crossfade $heard frames")
            assertEquals(outFrames + inFrames - heard, run.size)
            // The crossfade's out-curve, not a hold: halfway through, A is at cos(π/4).
            val mid = outFrames - heard / 2
            assertTrue(abs((run[mid] - saw(heard / 2) * overlapInGain(0.5f)) - tone(mid) * overlapOutGain(0.5f)) <= 3f)
        } finally {
            outgoing.delete()
            incoming.delete()
        }
    }

    private fun play(outgoing: File, incoming: File, settings: CrossfadeSettings): List<Int> {
        val capture = Capture()
        val end = CountDownLatch(1)
        var failure: PlaybackException? = null
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
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) end.countDown() }
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
            assertTrue("Playback timed out", end.await(180, TimeUnit.SECONDS))
            assertTrue("Playback failed: $failure", failure == null)
            return capture.samples()
        } finally {
            instrumentation.runOnMainSync {
                coordinator.release()
                player.release()
            }
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

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun writeWav(file: File, frames: Int, rate: Int, sample: (Int) -> Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        file.outputStream().buffered().use { out ->
            out.write(header.array())
            val block = ByteBuffer.allocate(4800 * 4).order(ByteOrder.LITTLE_ENDIAN)
            var start = 0
            while (start < frames) {
                block.clear()
                repeat(minOf(4800, frames - start)) { i ->
                    val value = sample(start + i).toShort()
                    block.putShort(value).putShort(value)
                }
                out.write(block.array(), 0, block.position()); start += 4800
            }
        }
    }

    private companion object {
        const val TAG = "LJFadeEntryTest"
        const val RATE = 48_000
        const val SLIDER_MS = 2_000L
        /** 20 s at full level, a 15 s dB-linear fade of 60 dB, 3 s of silence. */
        const val OUT_S = 38
        const val IN_S = 50

        fun tone(index: Int): Int = (8000 * sin(2 * PI * 440 * index / RATE)).toInt()

        fun fadingTone(index: Int): Int {
            val t = index.toDouble() / RATE
            val gainDb = when {
                t < 20.0 -> 0.0
                t < 35.0 -> -60.0 * (t - 20.0) / 15.0
                else -> return 0
            }
            return (8000 * 10.0.pow(gainDb / 20.0) * sin(2 * PI * 440 * index / RATE)).toInt()
        }

        /** A different value on every frame, so one frame too early or late is visible. */
        fun saw(index: Int): Int = (index * 7) % 12000 - 6000
    }
}
