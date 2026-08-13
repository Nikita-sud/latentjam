/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

/** Arithmetic mono is for embeddings; loudness must retain the power in every output channel. */
internal enum class AudioDownmixMode {
    AVERAGE,
    PRESERVE_CHANNEL_POWER,
}

/**
 * Collapses one interleaved frame without allowing opposite-phase channels to cancel its power.
 *
 * The dominant channel supplies only the sign; the magnitude is the RMS across channels. Keeping a
 * sign carrier avoids turning every waveform into a rectified envelope before resampling.
 */
internal inline fun channelPowerDownmix(
    channelCount: Int,
    sampleAt: (Int) -> Float,
): Float {
    if (channelCount <= 0) return 0f
    var sumSquares = 0.0
    var dominant = 0f
    repeat(channelCount) { channel ->
        val raw = sampleAt(channel)
        val sample = if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
        sumSquares += sample.toDouble() * sample
        if (abs(sample) > abs(dominant)) dominant = sample
    }
    val magnitude = sqrt(sumSquares / channelCount).toFloat()
    return if (dominant < 0f) -magnitude else magnitude
}

internal enum class DecodeLoopGuardResult {
    CONTINUE,
    CANCELLED,
    WALL_TIMEOUT,
    IDLE_TIMEOUT,
}

/**
 * One window decode outcome, kept typed until the embedding backend has considered every crop.
 *
 * [InvalidAudio] is reserved for deterministic facts about the current media bytes: there is no
 * audio stream, Android has no decoder for the declared stream, or a decoder reaches end of stream
 * without producing valid PCM. [Unavailable] covers failures that may disappear on a later run:
 * permission/storage access, codec allocation/configuration, liveness timeouts, and other platform
 * exceptions. This distinction is load-bearing because only deterministic track-local failures are
 * persisted by the engine.
 */
internal sealed interface AudioDecodeResult {
    data class Success(val waveform: FloatArray, val validSamples: Int) : AudioDecodeResult
    data class InvalidAudio(val detail: String) : AudioDecodeResult
    data class Unavailable(val detail: String) : AudioDecodeResult
}

/** The operation that failed, used to keep parser rejection separate from transient access/codec. */
internal enum class AudioDecodeFailureStage {
    SOURCE_OPEN,
    SOURCE_PARSE,
    CODEC,
}

/** Pure classification seam covered by host tests; only a readable source's parser failure sticks. */
internal fun classifyAudioDecodeException(
    stage: AudioDecodeFailureStage,
    error: Throwable,
    startMs: Long,
): AudioDecodeResult {
    val detail = buildString {
        append(error.javaClass.simpleName.ifBlank { "Audio decode failure" })
        error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
        append(" at ").append(startMs).append("ms")
    }
    return if (
        stage == AudioDecodeFailureStage.SOURCE_PARSE &&
        !error.isSourceAccessFailure()
    ) {
        AudioDecodeResult.InvalidAudio(detail)
    } else {
        AudioDecodeResult.Unavailable(detail)
    }
}

/** Permission and disappearance stay retryable even if the descriptor became invalid after open. */
private fun Throwable.isSourceAccessFailure(): Boolean {
    var cursor: Throwable? = this
    while (cursor != null) {
        if (cursor is SecurityException || cursor is java.io.FileNotFoundException) return true
        val next = cursor.cause
        if (next === cursor) return false
        cursor = next
    }
    return false
}

/**
 * Monotonic liveness guard for the synchronous MediaCodec polling loop.
 *
 * The wall budget is absolute. The idle budget is reset only after input or
 * output was successfully handed across the codec boundary, so repeated
 * `INFO_TRY_AGAIN_LATER` results cannot keep a broken codec alive forever.
 */
internal class DecodeLoopGuard(
    private val wallBudgetNanos: Long,
    private val idleBudgetNanos: Long,
    private val nowNanos: () -> Long,
) {
    private val startedAtNanos = nowNanos()
    private var lastProgressAtNanos = startedAtNanos

    init {
        require(wallBudgetNanos > 0L) { "wallBudgetNanos must be positive" }
        require(idleBudgetNanos > 0L) { "idleBudgetNanos must be positive" }
    }

    fun observe(cancelled: Boolean, madeProgress: Boolean = false): DecodeLoopGuardResult {
        if (cancelled) return DecodeLoopGuardResult.CANCELLED
        val now = nowNanos()
        if (elapsedNanos(startedAtNanos, now) >= wallBudgetNanos) {
            return DecodeLoopGuardResult.WALL_TIMEOUT
        }
        if (madeProgress) lastProgressAtNanos = now
        if (elapsedNanos(lastProgressAtNanos, now) >= idleBudgetNanos) {
            return DecodeLoopGuardResult.IDLE_TIMEOUT
        }
        return DecodeLoopGuardResult.CONTINUE
    }

    private fun elapsedNanos(since: Long, now: Long): Long = (now - since).coerceAtLeast(0L)
}

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

    private data class DecodedWindow(
        val waveform: FloatArray,
        val validSamples: Int,
    )

    /**
     * Returns exactly [targetSamples] mono float samples in `[-1, 1]` at [targetSampleRate],
     * starting near [startMs], or a typed track-local/platform failure.
     */
    fun decodeWindowMono(
        uri: Uri,
        startMs: Long,
        targetSampleRate: Int,
        targetSamples: Int,
        downmixMode: AudioDownmixMode = AudioDownmixMode.AVERAGE,
        isCancelled: () -> Boolean,
    ): AudioDecodeResult {
        if (isCancelled()) throw CancellationException("Audio decoding cancelled")
        val source = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return classifyAudioDecodeException(
                AudioDecodeFailureStage.SOURCE_OPEN,
                error,
                startMs,
            )
        } ?: return AudioDecodeResult.Unavailable(
            "Audio source is not locally readable at ${startMs}ms",
        )

        val extractor = try {
            MediaExtractor()
        } catch (cancellation: CancellationException) {
            runCatching { source.close() }
            throw cancellation
        } catch (error: Exception) {
            runCatching { source.close() }
            return classifyAudioDecodeException(
                AudioDecodeFailureStage.CODEC,
                error,
                startMs,
            )
        }
        var failureStage = AudioDecodeFailureStage.SOURCE_PARSE
        return try {
            source.let { descriptor ->
                // Opening the descriptor is the readability preflight. Once local bytes are open,
                // MediaExtractor rejecting their container is deterministic for this unchanged
                // source; permission/missing/unmounted failures returned before this point do not
                // poison the durable track identity.
                if (!descriptor.fileDescriptor.valid()) {
                    return AudioDecodeResult.Unavailable(
                        "Audio source returned an invalid descriptor at ${startMs}ms",
                    )
                }
                if (descriptor.declaredLength >= 0L) {
                    extractor.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                } else {
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                if (isCancelled()) throw CancellationException("Audio decoding cancelled")
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return AudioDecodeResult.InvalidAudio("No audio stream")
                extractor.selectTrack(trackIndex)
                val inputFormat = extractor.getTrackFormat(trackIndex)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: return AudioDecodeResult.InvalidAudio("Missing audio MIME")
                if (startMs > 0) {
                    extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
                failureStage = AudioDecodeFailureStage.CODEC
                // Resolve support separately from allocation. A null result is a stable property of
                // this device + stream format and may be remembered for unchanged bytes; failures
                // while creating/starting the chosen codec remain retryable availability failures.
                val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(inputFormat)
                    ?: return AudioDecodeResult.InvalidAudio("No decoder for $mime")
                val codec = MediaCodec.createByCodecName(decoderName)
                try {
                    codec.configure(inputFormat, null, null, 0)
                    codec.start()
                    val decoded = decodeLoop(
                        codec,
                        extractor,
                        targetSampleRate,
                        targetSamples,
                        downmixMode,
                        isCancelled,
                    ) ?: return AudioDecodeResult.InvalidAudio(
                        "Decoder produced no valid PCM at ${startMs}ms ($mime)",
                    )
                    AudioDecodeResult.Success(decoded.waveform, decoded.validSamples)
                } finally {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (parseFailure: ReadableSourceParseException) {
            classifyAudioDecodeException(
                AudioDecodeFailureStage.SOURCE_PARSE,
                parseFailure.cause ?: parseFailure,
                startMs,
            )
        } catch (error: Exception) {
            classifyAudioDecodeException(failureStage, error, startMs)
        } finally {
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }

    private fun decodeLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        targetSampleRate: Int,
        targetSamples: Int,
        downmixMode: AudioDownmixMode,
        isCancelled: () -> Boolean,
    ): DecodedWindow? {
        var sourceRate = 0
        var sourceChannels = 0
        var pcmFloat = false
        var sourceNeeded = Int.MAX_VALUE
        val chunks = ArrayList<FloatArray>()
        var collected = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        val guard = DecodeLoopGuard(
            wallBudgetNanos = DECODE_WALL_BUDGET_NANOS,
            idleBudgetNanos = DECODE_IDLE_BUDGET_NANOS,
            nowNanos = { System.nanoTime() },
        )

        fun checkLiveness(madeProgress: Boolean = false) {
            when (guard.observe(cancelled = isCancelled(), madeProgress = madeProgress)) {
                DecodeLoopGuardResult.CONTINUE -> Unit
                DecodeLoopGuardResult.CANCELLED -> {
                    throw CancellationException("Audio decoding cancelled")
                }
                DecodeLoopGuardResult.WALL_TIMEOUT -> {
                    throw DecodeLoopTimeoutException("Decoder exceeded the 30 second wall budget")
                }
                DecodeLoopGuardResult.IDLE_TIMEOUT -> {
                    throw DecodeLoopTimeoutException("Decoder made no progress for 5 seconds")
                }
            }
        }

        fun readOutputFormat() {
            val format = codec.outputFormat
            if (
                !format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ||
                !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ) {
                error("Decoder reported incomplete PCM format")
            }
            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (sourceRate <= 0 || sourceChannels <= 0) {
                error(
                    "Decoder reported invalid PCM format: rate=$sourceRate channels=$sourceChannels",
                )
            }
            pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            // +100 ms margin so interpolation never reads past the end.
            sourceNeeded = (targetSamples.toLong() * sourceRate / targetSampleRate).toInt() +
                sourceRate / 10
        }

        fun <T> readSource(operation: () -> T): T = try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // The source descriptor was already opened and MediaExtractor accepted its container.
            // A later extractor read/advance rejection is still a deterministic property of these
            // bytes; keep it distinct from MediaCodec allocation/configuration/runtime failures.
            throw ReadableSourceParseException(error)
        }

        while (!outputDone) {
            checkLiveness()
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                checkLiveness()
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                        ?: error("Codec returned no input buffer for index $inputIndex")
                    val size = readSource { extractor.readSampleData(buffer, 0) }
                    checkLiveness()
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        val sampleTime = readSource { extractor.sampleTime }
                        codec.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                        readSource { extractor.advance() }
                    }
                    checkLiveness(madeProgress = true)
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            checkLiveness()
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    readOutputFormat()
                    checkLiveness(madeProgress = true)
                }
                outputIndex >= 0 -> {
                    if (sourceRate == 0) readOutputFormat()
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                            ?: error("Codec returned no output buffer for index $outputIndex")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val mono = if (pcmFloat) {
                            monoFromFloat(buffer, sourceChannels, downmixMode)
                        } else {
                            monoFromPcm16(buffer, sourceChannels, downmixMode)
                        }
                        chunks.add(mono)
                        collected += mono.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    checkLiveness(madeProgress = true)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                        collected >= sourceNeeded
                    ) {
                        outputDone = true
                    }
                }
            }
        }

        checkLiveness()
        if (sourceRate == 0 || collected == 0) return null
        val source = FloatArray(collected)
        var position = 0
        for (chunk in chunks) {
            chunk.copyInto(source, position)
            position += chunk.size
        }
        checkLiveness()
        return resampleLinear(source, sourceRate, targetSampleRate, targetSamples)
    }

    private fun monoFromPcm16(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        downmixMode: AudioDownmixMode,
    ): FloatArray {
        val shorts = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.remaining() / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            mono[frame] = when (downmixMode) {
                AudioDownmixMode.AVERAGE -> {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += shorts.get(frame * channels + channel) / 32768f
                    }
                    sum / channels
                }
                AudioDownmixMode.PRESERVE_CHANNEL_POWER -> channelPowerDownmix(channels) { channel ->
                    shorts.get(frame * channels + channel) / 32768f
                }
            }
        }
        return mono
    }

    private fun monoFromFloat(
        buffer: java.nio.ByteBuffer,
        channels: Int,
        downmixMode: AudioDownmixMode,
    ): FloatArray {
        val floats = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = floats.remaining() / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            mono[frame] = when (downmixMode) {
                AudioDownmixMode.AVERAGE -> {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += floats.get(frame * channels + channel)
                    }
                    // Float decoders may legally overshoot full scale. The embedding graph's
                    // trained contract is strictly finite [-1, 1].
                    val sample = sum / channels
                    if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                }
                AudioDownmixMode.PRESERVE_CHANNEL_POWER -> channelPowerDownmix(channels) { channel ->
                    floats.get(frame * channels + channel)
                }
            }
        }
        return mono
    }

    private fun resampleLinear(
        source: FloatArray,
        sourceRate: Int,
        targetRate: Int,
        targetSamples: Int,
    ): DecodedWindow {
        val output = FloatArray(targetSamples) // zero-padded past the source end
        if (sourceRate == targetRate) {
            val validSamples = minOf(source.size, targetSamples)
            source.copyInto(output, 0, 0, validSamples)
            return DecodedWindow(output, validSamples)
        }
        val ratio = sourceRate.toDouble() / targetRate
        var validSamples = 0
        for (i in 0 until targetSamples) {
            val sourcePosition = i * ratio
            val index = sourcePosition.toInt()
            if (index >= source.size - 1) break
            val fraction = (sourcePosition - index).toFloat()
            output[i] = source[index] * (1f - fraction) + source[index + 1] * fraction
            validSamples = i + 1
        }
        return DecodedWindow(output, validSamples)
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DECODE_WALL_BUDGET_NANOS = 30_000_000_000L
        const val DECODE_IDLE_BUDGET_NANOS = 5_000_000_000L
    }
}

private class DecodeLoopTimeoutException(message: String) : Exception(message)

/** Wraps extractor rejection after a descriptor has already proven locally readable. */
private class ReadableSourceParseException(cause: Throwable) : Exception(cause)
