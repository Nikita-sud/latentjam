/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Drives [SeamResamplingAudioSink] the way the renderer and [PcmOnlyAudioSink] do. */
internal class SeamResamplingAudioSinkTest {
    private val settings = CrossfadeSettings().apply { fadeMs = 6_000 }
    private val streams = PcmStreams()

    /** What DefaultAudioSink was told: the formats configured, the PCM handed over. */
    private class Delegate {
        val formats = mutableListOf<Format>()
        val written = ArrayList<Short>()
        val offered = mutableListOf<ByteBuffer>()
        var refuseNext = 0
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> { formats += args[0] as Format; null }
                "handleBuffer" -> {
                    val buffer = args[0] as ByteBuffer
                    offered += buffer
                    if (refuseNext > 0) {
                        refuseNext--
                        false
                    } else {
                        val shorts = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shorts.hasRemaining()) written += shorts.get()
                        buffer.position(buffer.limit())
                        true
                    }
                }
                "flush", "reset" -> null
                else -> error("Unexpected delegation to ${method.name}")
            }
        } as AudioSink
    }

    private fun format(rate: Int, delay: Int = 0) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelCount(2).setEncoderDelay(delay).build()

    private fun tone(frames: Int, rate: Int) = ShortArray(frames * 2) { i ->
        (12_000 * sin(2 * PI * 440.0 * (i / 2) / rate)).toInt().toShort()
    }

    private fun feed(sink: AudioSink, pcm: ShortArray, chunkFrames: Int) {
        var frame = 0
        while (frame < pcm.size / 2) {
            val count = minOf(chunkFrames, pcm.size / 2 - frame)
            val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(pcm, frame * 2, count * 2)
            while (!sink.handleBuffer(buffer, frame * 1_000_000L / 48_000, 1)) Unit
            assertEquals(0, buffer.remaining(), "the buffer is taken once the sink took its PCM")
            frame += count
        }
    }

    /** Some PCM reached the sink ([PcmOnlyAudioSink] records it). */
    private fun played() {
        streams.repositionsAtData = streams.repositions
    }

    @Test
    fun aStartPlaysAtTheTracksOwnRateUntouched() {
        val delegate = Delegate()
        val sink = SeamResamplingAudioSink(delegate.sink, settings, streams)
        sink.configure(format(44_100), 0, null)
        assertEquals(44_100, delegate.formats.last().sampleRate)
        assertEquals(44_100, settings.outputRate)
        val buffer = ByteBuffer.allocateDirect(400).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertSame(buffer, delegate.offered.single(), "passed through as it is")
    }

    @Test
    fun aTrackThePlayerMovesOnToReachesTheSinkAtTheOutputsRate() {
        val delegate = Delegate()
        val sink = SeamResamplingAudioSink(delegate.sink, settings, streams)
        sink.configure(format(44_100), 0, null)
        played()
        // The gapless join into a 48 kHz Opus track: the sink is told 44.1 kHz, so it keeps its
        // AudioTrack and times what it was given at one rate.
        sink.configure(format(48_000, delay = 312), 0, null)
        assertEquals(44_100, delegate.formats.last().sampleRate)
        assertEquals(287, delegate.formats.last().encoderDelay)
        feed(sink, tone(48_000, 48_000), chunkFrames = 960)
        // One second in, one second out, give or take the filter's tail.
        assertTrue(delegate.written.size / 2 in 44_050..44_100, "frames out: ${delegate.written.size / 2}")
    }

    @Test
    fun aBufferTheSinkCannotTakeYetIsOfferedAgainAsTheSameConversion() {
        val delegate = Delegate()
        val sink = SeamResamplingAudioSink(delegate.sink, settings, targetRate = 44_100)
        sink.configure(format(48_000), 0, null)
        val buffer = ByteBuffer.allocateDirect(960 * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(tone(960, 48_000))
        delegate.refuseNext = 2
        assertTrue(!sink.handleBuffer(buffer, 0, 1))
        assertTrue(!sink.handleBuffer(buffer, 0, 1))
        assertEquals(buffer.capacity(), buffer.remaining(), "not taken while refused")
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertSame(delegate.offered[0], delegate.offered[2])
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun aRepositionOrTransitionsOffGoBackToTheTracksOwnRate() {
        val delegate = Delegate()
        val sink = SeamResamplingAudioSink(delegate.sink, settings, streams)
        sink.configure(format(44_100), 0, null)
        played()
        streams.repositions++
        sink.configure(format(48_000), 0, null)
        assertEquals(48_000, delegate.formats.last().sampleRate)
        played()
        settings.fadeMs = 0
        sink.configure(format(44_100), 0, null)
        assertEquals(44_100, delegate.formats.last().sampleRate)
    }

    @Test
    fun theCapturedOpeningIsThePcmThePlayerHandsTheSinkAtTheJoin() {
        val opening = tone(30_000, 48_000)
        val main = Delegate()
        SeamResamplingAudioSink(main.sink, settings, streams).apply {
            configure(format(44_100), 0, null)
            played()
            configure(format(48_000), 0, null)
            feed(this, opening, chunkFrames = 960)
        }
        val capture = Delegate()
        SeamResamplingAudioSink(capture.sink, settings, targetRate = settings.outputRate).apply {
            configure(format(48_000), 0, null)
            feed(this, opening, chunkFrames = 4_096)
        }
        val frames = minOf(main.written.size, capture.written.size)
        assertTrue(frames > 2 * 27_000)
        assertContentEquals(capture.written.take(frames), main.written.take(frames))
    }
}
