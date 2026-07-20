/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Decodes one fixed-length mono window of a track for the embedding model,
 * using the platform decoders (MediaExtractor + MediaCodec) — every format
 * Android can play, no bundled codecs.
 *
 * Behavior notes:
 * - Seeks are sync-frame fuzzy ([MediaExtractor.SEEK_TO_PREVIOUS_SYNC]); the
 *   encoder is trained multi-window, so ± a frame of start drift is fine.
 * - Output is resampled to the model rate by linear interpolation. Good
 *   enough per the embedding-equivalence gate; swap for windowed-sinc if that
 *   gate ever degrades.
 * - Short tracks are zero-padded to the window length, matching the training
 *   pipeline's padding.
 */
internal class AndroidAudioDecoder(private val context: Context) {

    /** Diagnostic for the most recent null result; embedding calls are serialized by the engine. */
    var lastFailure: String? = null
        private set

    /**
     * Returns exactly [targetSamples] mono float samples in `[-1, 1]` at
     * [targetSampleRate], starting near [startMs]; `null` if the track can't
     * be decoded at all.
     */
    fun decodeWindowMono(
        uri: Uri,
        startMs: Long,
        targetSampleRate: Int,
        targetSamples: Int,
    ): FloatArray? {
        lastFailure = null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return failed("No audio stream")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return failed("Missing audio MIME")
            if (startMs > 0) {
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                decodeLoop(codec, extractor, targetSampleRate, targetSamples)
                    ?: failed("Decoder produced no PCM at ${startMs}ms ($mime)")
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } catch (error: Exception) {
            failed(
                buildString {
                    append(error.javaClass.simpleName)
                    error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                    append(" at ").append(startMs).append("ms")
                },
            )
        } finally {
            extractor.release()
        }
    }

    private fun failed(reason: String): FloatArray? {
        lastFailure = reason
        return null
    }

    private fun decodeLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        targetSampleRate: Int,
        targetSamples: Int,
    ): FloatArray? {
        var sourceRate = 0
        var sourceChannels = 0
        var pcmFloat = false
        var sourceNeeded = Int.MAX_VALUE
        val chunks = ArrayList<FloatArray>()
        var collected = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()

        fun readOutputFormat() {
            val format = codec.outputFormat
            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            // +100 ms margin so interpolation never reads past the end.
            sourceNeeded = (targetSamples.toLong() * sourceRate / targetSampleRate).toInt() +
                sourceRate / 10
        }

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: return null
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat()
                outputIndex >= 0 -> {
                    if (sourceRate == 0) readOutputFormat()
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex) ?: return null
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val mono = if (pcmFloat) {
                            monoFromFloat(buffer, sourceChannels)
                        } else {
                            monoFromPcm16(buffer, sourceChannels)
                        }
                        chunks.add(mono)
                        collected += mono.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                        collected >= sourceNeeded
                    ) {
                        outputDone = true
                    }
                }
            }
        }

        if (sourceRate == 0 || collected == 0) return null
        val source = FloatArray(collected)
        var position = 0
        for (chunk in chunks) {
            chunk.copyInto(source, position)
            position += chunk.size
        }
        return resampleLinear(source, sourceRate, targetSampleRate, targetSamples)
    }

    private fun monoFromPcm16(buffer: java.nio.ByteBuffer, channels: Int): FloatArray {
        val shorts = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += shorts.get(frame * channels + channel) / 32768f
            }
            mono[frame] = sum / channels
        }
        return mono
    }

    private fun monoFromFloat(buffer: java.nio.ByteBuffer, channels: Int): FloatArray {
        val floats = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = floats.remaining() / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += floats.get(frame * channels + channel)
            }
            // Float decoders may legally overshoot full scale. The graph's trained contract is
            // strictly finite [-1, 1]; feeding hot samples into FP16 weights can overflow the
            // whole embedding to NaN/zero (observed on slowed/ultra-slowed files on Samsung).
            val sample = sum / channels
            mono[frame] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
        }
        return mono
    }

    private fun resampleLinear(
        source: FloatArray,
        sourceRate: Int,
        targetRate: Int,
        targetSamples: Int,
    ): FloatArray {
        val output = FloatArray(targetSamples) // zero-padded past the source end
        if (sourceRate == targetRate) {
            source.copyInto(output, 0, 0, minOf(source.size, targetSamples))
            return output
        }
        val ratio = sourceRate.toDouble() / targetRate
        for (i in 0 until targetSamples) {
            val sourcePosition = i * ratio
            val index = sourcePosition.toInt()
            if (index >= source.size - 1) break
            val fraction = (sourcePosition - index).toFloat()
            output[i] = source[index] * (1f - fraction) + source[index + 1] * fraction
        }
        return output
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
