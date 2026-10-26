/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Keeps the output's sample rate across a seam between tracks of different rates — a 44.1 kHz FLAC
 * into a 48 kHz Opus — while smooth transitions are on. Without it, the overlap cannot mix (the
 * two tracks reach [CrossfadeAudioProcessor] at different rates) and the sink replaces its
 * AudioTrack at the join, which pauses the sound.
 *
 * In front of DefaultAudioSink, not inside its chain: the sink times every buffer it was given
 * since its last flush at the current track's input rate, so a track of another rate behind a
 * reused AudioTrack threw its clock tens of seconds off (an "unexpected discontinuity", a jump of
 * the position and a drain in the middle of the next track). Here the sink only ever sees the
 * output's rate.
 *
 * A track the player moved on to by itself plays at the rate the output already has, resampled
 * ([PcmResampler]) when its own differs; after a reposition — a first start, a seek, a skip, where
 * the sink restarts anyway — a track plays at its own rate again. A track at the output's rate
 * passes through untouched, bit for bit. The opening an overlap mixes in is captured through one
 * of these at the same rate ([CrossfadeIntroCapture]), so its frames are the ones the player
 * produces at the join.
 *
 * With [streams] (the main player) that rule applies and the rate chosen is published as
 * [CrossfadeSettings.outputRate]; without (the capture) every PCM16 stream is brought to
 * [targetRate], 0 for its own. Playback thread only.
 */
internal class SeamResamplingAudioSink(
    sink: AudioSink,
    private val settings: CrossfadeSettings,
    private val streams: PcmStreams? = null,
    private val targetRate: Int = 0,
) : ForwardingAudioSink(sink) {
    private var resampler: PcmResampler? = null
    private var inputRate = 0
    private var outputRate = 0
    private var channels = 0
    private var samples = ShortArray(0)
    private var converted = ShortArray(0)
    private var pending: ByteBuffer? = null
    private var pendingFor: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pcm16 = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT &&
            inputFormat.sampleRate > 0 && inputFormat.channelCount > 0
        val own = inputFormat.sampleRate
        val rate = when {
            !pcm16 -> own
            streams == null -> targetRate.takeIf { it > 0 } ?: own
            else -> {
                // No reposition since the last PCM reached the sink: the player moved on by itself.
                val joined = outputRate > 0 && streams.repositions == streams.repositionsAtData
                if (joined && settings.fadeMs > 0L) outputRate else own
            }
        }
        if (streams != null && own > 0) {
            outputRate = rate
            settings.outputRate = rate
        }
        dropPending()
        if (!pcm16 || rate == own) {
            resampler = null
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        inputRate = own
        channels = inputFormat.channelCount
        resampler = PcmResampler(own, rate, channels)
        val scaled = inputFormat.buildUpon()
            .setSampleRate(rate)
            // Gapless trimming counts frames of the rate the sink is told.
            .setEncoderDelay(scaleFrames(inputFormat.encoderDelay, own, rate))
            .setEncoderPadding(scaleFrames(inputFormat.encoderPadding, own, rate))
            .build()
        super.configure(scaled, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val resampler = resampler ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // The renderer offers the same buffer again until the sink takes it: convert it once.
        val out = pending?.takeIf { pendingFor === buffer } ?: convert(resampler, buffer).also {
            pending = it
            pendingFor = buffer
        }
        if (!super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)) return false
        buffer.position(buffer.limit())
        dropPending()
        return true
    }

    override fun flush() {
        dropPending()
        resampler = resampler?.let { PcmResampler(inputRate, it.outputRate, channels) }
        super.flush()
    }

    override fun reset() {
        dropPending()
        resampler = null
        outputRate = 0
        super.reset()
    }

    private fun convert(resampler: PcmResampler, buffer: ByteBuffer): ByteBuffer {
        val count = buffer.remaining() / 2
        if (samples.size < count) samples = ShortArray(count)
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, count)
        resampler.push(samples, 0, count / channels)
        val frames = resampler.available()
        if (converted.size < frames * channels) converted = ShortArray(frames * channels)
        val made = resampler.pull(converted, frames)
        val bytes = made * channels * 2
        val out = (pending?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(maxOf(bytes, 4096)))
            .order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        out.asShortBuffer().put(converted, 0, made * channels)
        out.limit(bytes)
        return out
    }

    private fun dropPending() {
        pendingFor = null
        // The buffer stays for reuse; only its claim on an input goes.
        pending?.let { it.clear(); it.limit(0) }
    }

    private companion object {
        fun scaleFrames(frames: Int, from: Int, to: Int): Int =
            if (frames <= 0) 0 else ((frames.toLong() * to + from / 2) / from).toInt()
    }
}
