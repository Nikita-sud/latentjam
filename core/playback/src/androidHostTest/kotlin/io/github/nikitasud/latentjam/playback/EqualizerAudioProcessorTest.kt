/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class EqualizerAudioProcessorTest {
    private fun buffer(samples: List<Int>): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2)
        .order(ByteOrder.nativeOrder()).apply { samples.forEach { putShort(it.toShort()) }; flip() }

    private fun read(buffer: ByteBuffer): List<Int> = buildList { while (buffer.hasRemaining()) add(buffer.short.toInt()) }

    @Test fun disabledProcessorPreservesEveryPcm16ValueAndRemainsInTheChain() {
        val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings() }
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        assertTrue(processor.isActive)
        processor.flush(StreamMetadata.DEFAULT)
        val input = (-32768..32767).toList()
        processor.queueInput(buffer(input))
        val result = read(processor.output)
        processor.queueEndOfStream()
        assertEquals(input, result + read(processor.output))
        assertTrue(processor.isEnded)
    }

    @Test fun emptyDrainBuffersDoNotCopyTheSharedSingletonOrDiscardPendingOutput() {
        val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings() }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertTrue(!processor.output.hasRemaining())
        processor.queueInput(buffer(listOf(123, -456)))
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertTrue(!processor.output.hasRemaining())
        processor.queueEndOfStream()
        assertEquals(listOf(123, -456), read(processor.output))
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertTrue(processor.isEnded)
    }

    @Test fun realPcmChangesLiveAndSettingsSurviveAFlush() {
        var settings = SoftwareEqualizerSettings()
        val processor = EqualizerAudioProcessor { settings }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        val tone = (0 until 4800).flatMap { i -> listOf((10000 * sin(2 * PI * i / 48)).toInt(), 0) }
        processor.queueInput(buffer(tone))
        val initial = read(processor.output)
        assertEquals(tone.take(initial.size), initial)
        settings = SoftwareEqualizerSettings(true, List(10) { if (it == 5) -1200 else 0 })
        processor.queueInput(buffer(tone))
        val changed = read(processor.output)
        assertTrue(changed.drop(4000).filterIndexed { i, _ -> i % 2 == 0 }.maxOf(::abs) < 2700)
        assertTrue(changed.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 0 })
        processor.flush(StreamMetadata.DEFAULT)
        processor.queueInput(buffer(tone))
        assertTrue(read(processor.output).drop(4000).maxOf(::abs) < 2700)
    }

    @Test fun formatChangeResetsChannelHistoriesAndHandlesMono() {
        val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings().withPreset(1) }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        processor.queueInput(buffer(List(400) { 10000 }))
        read(processor.output)
        processor.configure(AudioProcessor.AudioFormat(16000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        processor.queueInput(buffer(List(100) { 0 }))
        val result = read(processor.output)
        processor.queueEndOfStream()
        assertEquals(List(100) { 0 }, result + read(processor.output))
    }

    @Test fun lookaheadPreservesShortStreamsAndPendingOutputAtEndOfStream() {
        for (length in listOf(1, 239, 240, 241, 1200)) {
            val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings() }
            processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
            processor.flush(StreamMetadata.DEFAULT)
            val samples = List(length) { it % 100 - 50 }
            processor.queueInput(buffer(samples))
            // EOS before getOutput must append the delayed tail instead of replacing pending PCM.
            processor.queueEndOfStream()
            assertEquals(samples, read(processor.output), "Length=$length")
            assertTrue(processor.isEnded)
            processor.queueEndOfStream()
            assertTrue(!processor.output.hasRemaining())
        }
    }

    @Test fun delayedOutputIsIndependentOfInputChunkBoundaries() {
        val samples = List(4000) { (10000 * sin(2 * PI * 40 * it / 48000)).toInt() }
        fun render(chunkSize: Int): List<Int> {
            val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings().withPreset(1).copy(bass = 1000) }
            processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
            processor.flush(StreamMetadata.DEFAULT)
            return buildList {
                for (chunk in samples.chunked(chunkSize)) {
                    processor.queueInput(buffer(chunk))
                    addAll(read(processor.output))
                }
                processor.queueEndOfStream()
                addAll(read(processor.output))
                assertTrue(processor.isEnded)
            }
        }
        val oneBuffer = render(samples.size)
        assertEquals(samples.size, oneBuffer.size)
        assertEquals(oneBuffer, render(137))
        assertEquals(oneBuffer, render(1))
    }

    @Test fun pcm16ShippingPathLimitsBoostedPeaksBeforeIntegerQuantization() {
        val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings(true, List(10) { 1500 }, 1000) }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        val samples = (0 until 48000).flatMap { frame ->
            val sample = (30000 * sin(2 * PI * 40 * frame / 48000)).toInt()
            listOf(sample, -sample / 4)
        }
        val actual = buildList {
            for (chunk in samples.chunked(1024)) {
                processor.queueInput(buffer(chunk))
                addAll(read(processor.output))
            }
            processor.queueEndOfStream()
            addAll(read(processor.output))
        }
        assertEquals(samples.size, actual.size)
        val peak = actual.maxOf(::abs)
        assertTrue(peak in 30000..30936, "Peak must approach the -0.5 dB ceiling without clipping: $peak")
        assertTrue(actual.chunked(2).all { abs(it[0] / 4.0 + it[1]) < 4 }, "Stereo gain must remain linked")
        processor.reset()
    }

    @Test fun jniRejectsMismatchedLayoutsAndInvalidResponseRatesBeforeAccessingBuffers() {
        assertFailsWith<IllegalArgumentException> { NativeEqualizerJni.create(0, 2) }
        val handle = NativeEqualizerJni.create(48000, 2)
        try {
            assertFailsWith<IllegalArgumentException> { NativeEqualizerJni.processFloat(handle, floatArrayOf(0f), 1, 1) }
            val input = buffer(listOf(1))
            val output = buffer(listOf(0))
            assertFailsWith<IllegalArgumentException> { NativeEqualizerJni.processPcm16(handle, input, 0, output, 0, 1, 1) }
            assertFailsWith<IllegalArgumentException> { NativeEqualizerJni.drain(handle, output, 0, 1, 1) }
            val gains = floatArrayOf(123f)
            assertFailsWith<IllegalArgumentException> { NativeEqualizerJni.response(0, true, IntArray(10), 0, floatArrayOf(1000f), gains) }
            assertEquals(123f, gains[0])
        } finally { NativeEqualizerJni.destroy(handle) }
    }

    @Test fun unsupportedInputIsRejectedInsteadOfSilentlyBypassingEffects() {
        val processor = EqualizerAudioProcessor { SoftwareEqualizerSettings() }
        assertFailsWith<AudioProcessor.UnhandledAudioFormatException> {
            processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        }
    }

    @Test fun compressedPassthroughAndOffloadCannotBypassTheProcessor() {
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else -> error("Unexpected delegation to ${method.name}")
            }
        } as AudioSink
        val sink = PcmOnlyAudioSink(delegate)
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG)) {
            val format = Format.Builder().setSampleMimeType(mime).build()
            assertTrue(!sink.supportsFormat(format))
            assertTrue(!sink.getFormatOffloadSupport(format).isFormatSupported)
        }
        assertTrue(sink.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build()))
    }
}
