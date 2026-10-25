/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps the output's sample rate across a seam between tracks of different rates — a 44.1 kHz FLAC
 * into a 48 kHz Opus — while smooth transitions are on. Without it, the overlap cannot mix (the
 * two tracks reach [CrossfadeAudioProcessor] at different rates) and the sink replaces its
 * AudioTrack at the join, which pauses the sound.
 *
 * First in the chain. A track the player moved on to by itself plays at the rate the output already
 * has, resampled ([PcmResampler]) when its own differs; after a reposition — a first start, a seek,
 * a skip, where the sink restarts anyway — a track plays at its own rate again. A track at the
 * output's rate passes through untouched, bit for bit. The opening an overlap mixes in is captured
 * through one of these at the same [targetRate] ([CrossfadeIntroCapture]), so its frames are the
 * ones the player produces at the join.
 *
 * With [streams] (the main player) the rule above applies and the rate chosen is published as
 * [CrossfadeSettings.outputRate]; without (the capture) every stream is brought to [targetRate],
 * 0 for its own.
 */
internal class SeamResamplingAudioProcessor(
    private val settings: CrossfadeSettings,
    private val streams: PcmStreams? = null,
    private val targetRate: Int = 0,
) : BaseAudioProcessor() {
    private var resampler: PcmResampler? = null
    private var outputRate = 0
    private var input = ShortArray(0)
    private var output = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0 ||
            inputAudioFormat.sampleRate <= 0
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        val own = inputAudioFormat.sampleRate
        val rate = if (streams == null) {
            targetRate.takeIf { it > 0 } ?: own
        } else {
            // No reposition since the last PCM reached the sink: the player moved on by itself.
            val joined = outputRate > 0 && streams.repositions == streams.repositionsAtData
            (if (joined && settings.fadeMs > 0L) outputRate else own).also {
                outputRate = it
                settings.outputRate = it
            }
        }
        return if (rate == own) AudioProcessor.AudioFormat.NOT_SET
        else AudioProcessor.AudioFormat(rate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        resampler = if (isActive) {
            PcmResampler(inputAudioFormat.sampleRate, outputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        } else {
            null
        }
    }

    override fun onReset() {
        resampler = null
        outputRate = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val resampler = checkNotNull(resampler)
        val channels = inputAudioFormat.channelCount
        val samples = inputBuffer.remaining() / 2
        if (input.size < samples) input = ShortArray(samples)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(input, 0, samples)
        inputBuffer.position(inputBuffer.limit())
        resampler.push(input, 0, samples / channels)
        emit(resampler, prefix = null)
    }

    override fun onQueueEndOfStream() {
        val resampler = resampler ?: return
        resampler.end()
        // The output not read yet goes first: the end can arrive before it is.
        val previous = getOutput()
        val prefix = ByteArray(previous.remaining()).also { previous.get(it) }
        emit(resampler, prefix)
    }

    private fun emit(resampler: PcmResampler, prefix: ByteArray?) {
        val channels = inputAudioFormat.channelCount
        val frames = resampler.available()
        if (frames == 0 && prefix == null) return
        if (output.size < frames * channels) output = ShortArray(frames * channels)
        val made = resampler.pull(output, frames)
        val buffer = replaceOutputBuffer((prefix?.size ?: 0) + made * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        prefix?.let(buffer::put)
        buffer.asShortBuffer().put(output, 0, made * channels)
        buffer.position(buffer.position() + made * channels * 2)
        buffer.flip()
    }
}
