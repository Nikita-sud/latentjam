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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives [SeamResamplingAudioProcessor] the way DefaultAudioSink and [PcmOnlyAudioSink] do. */
internal class SeamResamplingAudioProcessorTest {
    private val settings = CrossfadeSettings().apply { fadeMs = 6_000 }
    private val streams = PcmStreams()
    private val main = SeamResamplingAudioProcessor(settings, streams)

    private fun format(rate: Int) = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /** Configures [processor] for a stream at [rate] and applies it; returns its output rate. */
    private fun start(processor: SeamResamplingAudioProcessor, rate: Int): Int {
        val out = processor.configure(format(rate))
        processor.flush(StreamMetadata.DEFAULT)
        return if (out == AudioProcessor.AudioFormat.NOT_SET) rate else out.sampleRate
    }

    /** Some PCM reached the sink. */
    private fun played() {
        streams.repositionsAtData = streams.repositions
    }

    private fun tone(frames: Int, rate: Int) = ShortArray(frames * 2) { i ->
        (12_000 * sin(2 * PI * 440.0 * (i / 2) / rate)).toInt().toShort()
    }

    private fun run(processor: SeamResamplingAudioProcessor, pcm: ShortArray, chunkFrames: Int): ShortArray {
        val out = ArrayList<Short>()
        fun collect() {
            val buffer = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.hasRemaining()) out += buffer.short
        }
        var frame = 0
        while (frame < pcm.size / 2) {
            val count = minOf(chunkFrames, pcm.size / 2 - frame)
            val input = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            input.asShortBuffer().put(pcm, frame * 2, count * 2)
            processor.queueInput(input)
            collect()
            frame += count
        }
        processor.queueEndOfStream()
        collect()
        return out.toShortArray()
    }

    @Test
    fun aStartPlaysAtTheTracksOwnRateUntouched() {
        assertEquals(44_100, start(main, 44_100))
        assertFalse(main.isActive)
        assertEquals(44_100, settings.outputRate)
    }

    @Test
    fun aTrackThePlayerMovesOnToKeepsTheOutputsRate() {
        start(main, 44_100)
        played()
        // The gapless join into a 48 kHz track: same output, so the sink keeps its AudioTrack.
        assertEquals(44_100, start(main, 48_000))
        assertTrue(main.isActive)
        assertEquals(44_100, settings.outputRate)
        // And the next one at 48 kHz too, until a reposition.
        played()
        assertEquals(44_100, start(main, 48_000))
    }

    @Test
    fun aRepositionOrTransitionsOffGoBackToTheTracksOwnRate() {
        start(main, 44_100)
        played()
        streams.repositions++
        assertEquals(48_000, start(main, 48_000))
        assertFalse(main.isActive)
        played()
        settings.fadeMs = 0
        assertEquals(44_100, start(main, 44_100))
    }

    @Test
    fun theCapturedOpeningIsTheFramesThePlayerProducesAtTheJoin() {
        val opening = tone(30_000, 48_000)
        // The main player at the join, buffers of one size...
        start(main, 44_100)
        played()
        start(main, 48_000)
        val atJoin = run(main, opening, chunkFrames = 960)
        // ...and the capture at the main player's rate, buffers of another.
        val capture = SeamResamplingAudioProcessor(settings, targetRate = settings.outputRate)
        assertEquals(44_100, start(capture, 48_000))
        assertContentEquals(atJoin, run(capture, opening, chunkFrames = 4_096))
        assertEquals(2 * 27_563, atJoin.size)
    }

    @Test
    fun aCaptureAtTheTracksOwnRatePassesThrough() {
        val capture = SeamResamplingAudioProcessor(settings, targetRate = 48_000)
        start(capture, 48_000)
        assertFalse(capture.isActive)
    }
}
