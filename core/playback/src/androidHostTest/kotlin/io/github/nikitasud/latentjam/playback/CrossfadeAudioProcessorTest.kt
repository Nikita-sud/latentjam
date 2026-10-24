/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [CrossfadeAudioProcessor] the way DefaultAudioSink and the stream-tagging renderer do: a
 * reposition flush before the first track, then per gapless join a checkpoint
 * ([CrossfadeAudioProcessor.prepareSegment]), a drain and a flush. Mono at 1 kHz keeps frames and
 * milliseconds the same numbers.
 */
internal class CrossfadeAudioProcessorTest {
    private val settings = CrossfadeSettings()
    private val streams = PcmStreams()
    private val processor = CrossfadeAudioProcessor(settings, streams).apply {
        configure(AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT))
    }
    private var nextKey = 1L

    private fun stream(id: String, durationMs: Long, row: Int = -1) =
        PcmStream(nextKey++, id, duration = { durationMs * 1000L }, windowIndex = { row })

    /** A seek, a skip or the first start: the renderer flushes the sink. */
    private fun reposition(stream: PcmStream, atMs: Long = 0L) {
        streams.current = stream
        streams.repositions++
        streams.bufferMediaUs = atMs * 1000L
        processor.flush(StreamMetadata.DEFAULT)
    }

    /** The player moved on by itself; returns the position offset the chain reports. */
    private fun join(stream: PcmStream): Long {
        streams.current = stream
        val skippedUs = processor.prepareSegment()
        processor.queueEndOfStream()
        processor.flush(StreamMetadata.DEFAULT)
        streams.bufferMediaUs = 0L
        return skippedUs
    }

    /** Feeds [samples] starting at [fromMs] of the track in 100-frame buffers; returns the output. */
    private fun feed(samples: (Int) -> Int, fromMs: Int, toMs: Int): List<Int> = buildList {
        var frame = fromMs
        while (frame < toMs) {
            val count = minOf(100, toMs - frame)
            streams.bufferMediaUs = frame * 1000L
            val input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { input.putShort(samples(frame + it).toShort()) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            while (output.hasRemaining()) add(output.short.toInt())
            frame += count
        }
    }

    private fun plan(outgoing: String, incoming: String, overlapMs: Long, frames: Int, samples: (Int) -> Int) =
        OverlapPlan(
            outgoingId = outgoing,
            incomingId = incoming,
            overlapUs = overlapMs * 1000L,
            sampleRate = RATE,
            channelCount = 1,
            pcm = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames) { putShort(samples(it).toShort()) }
                flip()
            },
            frames = frames,
        )

    private val loud = { _: Int -> 1000 }
    private val ramp = { frame: Int -> frame }

    @Test
    fun theNextTracksOpeningSoundsUnderTheEndAndIsNotPlayedTwice() {
        settings.fadeMs = 2_000
        val a = stream("a", 10_000)
        val b = stream("b", 20_000)
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000, samples = ramp)
        reposition(a, atMs = 5_000)
        val outgoing = feed(loud, 5_000, 10_000)

        // Untouched until the overlap; then both tracks at once on equal-power curves.
        assertTrue(outgoing.take(3_000).all { it == 1000 })
        val middle = outgoing[4_000]
        val expectedMiddle = 1000 * overlapOutGain(0.5f) + 1000 * overlapInGain(0.5f)
        assertTrue(abs(middle - expectedMiddle) <= 1f, "middle of the overlap was $middle")
        assertTrue(outgoing.last() in 1999..2000, "end of the overlap was ${outgoing.last()}")
        assertEquals(2000L, settings.progress?.heardMs)

        // The join drops exactly the 2000 frames already heard and reports them as played, also
        // when the sink checkpoints a second time before the first frame.
        assertEquals(2_000_000L, join(b))
        assertEquals(2_000_000L, processor.prepareSegment())
        processor.flush(StreamMetadata.DEFAULT)
        val incoming = feed(ramp, 0, 3_000)
        assertEquals((2_000 until 3_000).toList(), incoming)
        assertEquals(null, settings.progress)
    }

    @Test
    fun aTrackThatEndsEarlierThanItsHeaderSaysStillJoinsWhereTheOverlapReached() {
        settings.fadeMs = 2_000
        val a = stream("a", 10_000)
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000, samples = ramp)
        reposition(a, atMs = 5_000)
        // The real stream ends 500 ms before its declared duration.
        feed(loud, 5_000, 9_500)
        assertEquals(1_500_000L, join(stream("b", 20_000)))
        val incoming = feed(ramp, 0, 3_000)
        // The rest of the overlap's rise continues on the live track, then full level.
        val expectedFirst = 1500 * overlapInGain(0.75f)
        assertTrue(abs(incoming.first() - expectedFirst) <= 1f, "rise carried over: ${incoming.first()}")
        assertEquals((2_500 until 3_000).toList(), incoming.takeLast(500))
    }

    @Test
    fun aWithdrawnPlanLetsTheOpeningGoAndTheNextTrackStartsFromItsBeginning() {
        settings.fadeMs = 2_000
        val a = stream("a", 10_000)
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000) { 3000 }
        reposition(a, atMs = 5_000)
        feed(loud, 5_000, 8_500)
        settings.plan = null
        val rest = feed({ 0 }, 8_500, 10_000)
        // Within the cancel ramp the opening is gone.
        assertTrue(rest.drop(CROSSFADE_CANCEL_MS.toInt() + 100).all { it == 0 })
        assertEquals(0L, join(stream("c", 20_000)))
        assertEquals(0, feed(ramp, 0, 10).first())
    }

    @Test
    fun anAlbumSeamPlayedInOrderNeitherOverlapsNorFades() {
        settings.fadeMs = 2_000
        settings.edges = mapOf(0 to FadeEdges(fadeIn = true, fadeOut = false), 1 to FadeEdges(false, true))
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000, samples = ramp)
        reposition(stream("a", 10_000, row = 0), atMs = 5_000)
        assertTrue(feed(loud, 5_000, 10_000).all { it == 1000 })
        assertEquals(0L, join(stream("b", 10_000, row = 1)))
        assertTrue(feed(loud, 0, 3_000).all { it == 1000 })
    }

    @Test
    fun withoutAPlanTheSeamFallsBackToFadingOutAndIn() {
        settings.fadeMs = 2_000
        reposition(stream("a", 10_000), atMs = 7_000)
        val outgoing = feed(loud, 7_000, 10_000)
        assertEquals(1000, outgoing[0])
        assertEquals(500, outgoing[2_000])
        join(stream("b", 10_000))
        val incoming = feed(loud, 0, 3_000)
        assertEquals(0, incoming[0])
        assertEquals(500, incoming[1_000])
        assertEquals(1000, incoming[2_500])
    }

    @Test
    fun aPlanForAnotherSampleRateIsNotMixed() {
        settings.fadeMs = 2_000
        settings.plan = plan("a", "b", 2_000, 4_000, ramp).let {
            OverlapPlan(it.outgoingId, it.incomingId, it.overlapUs, RATE * 2, 1, it.pcm, it.frames)
        }
        reposition(stream("a", 10_000), atMs = 7_000)
        val outgoing = feed(loud, 7_000, 10_000)
        assertEquals(500, outgoing[2_000])
        assertEquals(0L, join(stream("b", 10_000)))
    }

    @Test
    fun eachTrackPlaysAtItsOwnLoudnessAndALaterMeasurementRamps() {
        settings.trackGains = mapOf("a" to 0.5f, "b" to 0.25f)
        reposition(stream("a", 100_000))
        assertEquals(500, feed(loud, 0, 10).first())
        join(stream("b", 100_000))
        // A new track's gain is installed before its first sample.
        assertEquals(250, feed(loud, 0, 10).first())
        settings.trackGains = mapOf("b" to 1f)
        val ramped = feed(loud, 10, 1_000)
        assertTrue(ramped.first() < 300)
        assertEquals(1000, ramped.last())
    }

    @Test
    fun aSeekBackOutOfTheOverlapReturnsToTheOutgoingTrackAlone() {
        settings.fadeMs = 2_000
        val a = stream("a", 10_000)
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000, samples = ramp)
        reposition(a)
        feed(loud, 0, 9_000)
        reposition(a, atMs = 3_000)
        assertTrue(feed(loud, 3_000, 4_000).all { it == 1000 })
        assertEquals(null, settings.progress)
    }

    @Test
    fun aMidTrackReconfigurationKeepsTheOverlapGoing() {
        settings.fadeMs = 2_000
        val a = stream("a", 10_000)
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000, samples = ramp)
        reposition(a)
        feed(loud, 0, 9_000)
        // Same stream: a speed change or similar re-checkpoints without a join.
        assertEquals(0L, processor.prepareSegment())
        processor.flush(StreamMetadata.DEFAULT)
        val rest = feed(loud, 9_000, 10_000)
        assertTrue(rest.last() in 1999..2000)
        assertEquals(2_000_000L, join(stream("b", 20_000)))
    }

    @Test
    fun theSameTrackQueuedAgainElsewhereStillFadesAtItsOwnRow() {
        settings.fadeMs = 2_000
        // Row 0 continues its album; row 3, the same track queued again, does not.
        settings.edges = mapOf(0 to FadeEdges(fadeIn = false, fadeOut = false))
        reposition(stream("a", 10_000, row = 3), atMs = 7_000)
        assertEquals(500, feed(loud, 7_000, 10_000)[2_000])
    }

    @Test
    fun finishingTheTrackLetsAnOverlapInProgressGo() {
        settings.fadeMs = 2_000
        settings.plan = plan("a", "b", overlapMs = 2_000, frames = 4_000) { 3000 }
        reposition(stream("a", 10_000), atMs = 5_000)
        feed(loud, 5_000, 8_500)
        // The sleep timer arms "end of track": the coordinator withdraws the plan.
        settings.finishingTrack = true
        settings.plan = null
        val rest = feed({ 0 }, 8_500, 10_000)
        assertTrue(rest.drop(CROSSFADE_CANCEL_MS.toInt() + 100).all { it == 0 })
        assertEquals(0L, join(stream("b", 20_000)))
    }

    private companion object {
        const val RATE = 1000
    }
}
