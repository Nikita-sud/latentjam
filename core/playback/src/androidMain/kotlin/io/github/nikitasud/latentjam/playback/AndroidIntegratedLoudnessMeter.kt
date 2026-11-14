/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full-track streaming analysis using platform decoders and the shared libebur128 core. With
 * [measuresFadeEnds] the same pass also measures how the track ends ([FadeEnd]); off, nothing of it
 * is allocated or returned.
 */
public class AndroidIntegratedLoudnessMeter(context: Context, private val measuresFadeEnds: Boolean = false) {
    private val context = context.applicationContext

    public suspend fun measure(audioUri: String, durationMs: Long?): IntegratedLoudness? =
        withContext(Dispatchers.IO) {
            scanMutex.withLock {
                val coroutine = currentCoroutineContext()
                val started = SystemClock.elapsedRealtime()
                var progressAt = started
                fun check(progress: Boolean = false) {
                    coroutine.ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    require(now - started <= LoudnessScanLimits.WALL_BUDGET_MS) { "Loudness scan timed out" }
                    if (progress) progressAt = now
                    require(now - progressAt <= LoudnessScanLimits.IDLE_BUDGET_MS) { "Loudness decoder stalled" }
                }
                var extractor: MediaExtractor? = null
                var codec: MediaCodec? = null
                var meter: NativeLoudnessMeter? = null
                var sound: SoundSpanTracker? = null
                var fade: FadeEndTracker? = null
                try {
                    if (durationMs != null && durationMs > LoudnessScanLimits.MAX_DURATION_MS) return@withLock null
                    val uri = Uri.parse(audioUri)
                    // Do not turn the offline scanner into an HTTP client.
                    if (uri.scheme !in listOf("content", "file")) return@withLock null
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { source ->
                        val reader = MediaExtractor().also { extractor = it }
                        if (source.declaredLength >= 0) reader.setDataSource(source.fileDescriptor, source.startOffset, source.declaredLength)
                        else reader.setDataSource(source.fileDescriptor)
                        check(true)
                        val track = (0 until reader.trackCount).firstOrNull {
                            reader.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        } ?: return@withLock null
                        reader.selectTrack(track)
                        val input = reader.getTrackFormat(track)
                        val expectedDuration = if (input.containsKey(MediaFormat.KEY_DURATION))
                            input.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1000) ?: durationMs else durationMs
                        if (expectedDuration != null && expectedDuration > LoudnessScanLimits.MAX_DURATION_MS) return@withLock null
                        // Android has no ALAC codec: Apple Lossless is measured through the app's own decoder (issue #34).
                        if (input.getString(MediaFormat.KEY_MIME) == MimeTypes.AUDIO_ALAC) {
                            val cookie = input.getByteBuffer("csd-0")?.let { csd -> ByteArray(csd.remaining()).also { csd.duplicate().get(it) } }
                                ?: return@withLock null
                            val alac = AlacPacketDecoder.open(cookie) ?: return@withLock null
                            alac.use {
                                require(alac.channels in 1..2 && alac.sampleRate in 8000..192000) { "Unsupported loudness PCM layout" }
                                meter = NativeLoudnessMeter(alac.sampleRate, alac.channels)
                                sound = SoundSpanTracker(alac.sampleRate, alac.channels)
                                if (measuresFadeEnds) fade = FadeEndTracker(alac.sampleRate, alac.channels)
                                val bytesPerSample = alac.maxOutputBytes / (alac.frameLength * alac.channels)
                                val samples = FloatArray(LoudnessScanLimits.CHUNK_FRAMES * 2)
                                // Room for the largest packet this stream can hold: a frame stored uncompressed, plus
                                // its header. The file's own claims size nothing (and MediaExtractor.getSampleSize needs
                                // API 28); a packet that does not fit is not ALAC of this configuration.
                                val packet = java.nio.ByteBuffer.allocate(alac.maxOutputBytes + ALAC_PACKET_HEADROOM)
                                val bytes = ByteArray(packet.capacity())
                                var decodedFrames = 0L
                                while (true) {
                                    check()
                                    packet.clear()
                                    val read = reader.readSampleData(packet, 0)
                                    if (read < 0) break
                                    packet.limit(read); packet.position(0)
                                    packet.get(bytes, 0, read)
                                    val pcm = alac.decode(bytes, 0, read) ?: return@withLock null
                                    val frameBytes = alac.channels * bytesPerSample
                                    var at = 0
                                    while (at < pcm.length) {
                                        val frames = minOf(LoudnessScanLimits.CHUNK_FRAMES, (pcm.length - at) / frameBytes)
                                        if (frames == 0) break
                                        repeat(frames * alac.channels) { i -> samples[i] = pcmSample(pcm.bytes, at + i * bytesPerSample, bytesPerSample) }
                                        if (meter?.add(samples, frames) != true) return@withLock null
                                        sound?.add(samples, frames)
                                        fade?.add(samples, frames)
                                        decodedFrames += frames
                                        at += frames * frameBytes
                                    }
                                    reader.advance()
                                    check(true)
                                }
                                if (!LoudnessScanLimits.complete(decodedFrames, alac.sampleRate, expectedDuration, sourceEnded = true)) {
                                    return@withLock null
                                }
                                return@withLock meter?.finish()?.copy(sound = sound?.finish(), fade = fade?.finish())
                                    .also { coroutine.ensureActive() }
                            }
                        }
                        input.setInteger(MediaFormat.KEY_PRIORITY, 1) // background, non-realtime codec work
                        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(input)
                            ?: return@withLock null
                        val decoder = MediaCodec.createByCodecName(name).also { codec = it }
                        decoder.configure(input, null, null, 0)
                        decoder.start()
                        check(true)
                        var rate = 0
                        var channels = 0
                        var encoding = AudioFormat.ENCODING_PCM_16BIT
                        var bytesPerSample = 2
                        var decodedFrames = 0L
                        val samples = FloatArray(LoudnessScanLimits.CHUNK_FRAMES * 2)
                        val info = MediaCodec.BufferInfo()
                        var inputDone = false
                        var outputDone = false
                        fun readFormat() {
                            val output = decoder.outputFormat
                            val newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val newEncoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(newRate in 8000..192000 && newChannels in 1..2) { "Unsupported loudness PCM layout" }
                            // A changed format mid-programme cannot be spliced into one libebur128 state.
                            require(rate == 0 || (rate == newRate && channels == newChannels && encoding == newEncoding))
                            rate = newRate; channels = newChannels; encoding = newEncoding
                            bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> error("Unsupported loudness PCM encoding")
                            }
                            if (meter == null) {
                                meter = NativeLoudnessMeter(rate, channels)
                                sound = SoundSpanTracker(rate, channels)
                                if (measuresFadeEnds) fade = FadeEndTracker(rate, channels)
                            }
                        }
                        while (!outputDone) {
                            check()
                            // Bound each input burst so decoding output and cancellation are serviced.
                            for (attempt in 0 until 16) {
                                if (inputDone) break
                                val index = decoder.dequeueInputBuffer(0)
                                if (index < 0) break
                                val buffer = checkNotNull(decoder.getInputBuffer(index))
                                val size = reader.readSampleData(buffer, 0)
                                check()
                                if (size < 0) {
                                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    require(reader.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                                    decoder.queueInputBuffer(index, 0, size, reader.sampleTime, 0)
                                    reader.advance()
                                }
                            }
                            val index = decoder.dequeueOutputBuffer(info, 10_000)
                            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { readFormat(); check(true) }
                            if (index >= 0) {
                                try {
                                    if (rate == 0) readFormat()
                                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                        val buffer = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                        require(info.offset >= 0 && info.size <= buffer.capacity() - info.offset)
                                        require(info.size % (channels * bytesPerSample) == 0)
                                        buffer.limit(info.offset + info.size); buffer.position(info.offset)
                                        while (buffer.hasRemaining()) {
                                            check()
                                            val frames = minOf(LoudnessScanLimits.CHUNK_FRAMES, buffer.remaining() / (channels * bytesPerSample))
                                            repeat(frames * channels) { i ->
                                                samples[i] = when (encoding) {
                                                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                                                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                                                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                                                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                                                    else -> {
                                                        val packed = (buffer.get().toInt() and 255) or
                                                            ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                                                        packed / 8388608f
                                                    }
                                                }
                                            }
                                            if (meter?.add(samples, frames) != true) return@withLock null
                                            sound?.add(samples, frames)
                                            fade?.add(samples, frames)
                                            decodedFrames += frames
                                            check(true)
                                        }
                                    }
                                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                } finally { decoder.releaseOutputBuffer(index, false) }
                            }
                        }
                        check()
                        // inputDone: the extractor itself reported the end of the file.
                        if (!inputDone || !LoudnessScanLimits.complete(decodedFrames, rate, expectedDuration, sourceEnded = true)) {
                            return@withLock null
                        }
                        meter?.finish()?.copy(sound = sound?.finish(), fade = fade?.finish()).also { coroutine.ensureActive() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Unsupported/corrupt files and unavailable platform codecs leave playback unchanged.
                    null
                } finally {
                    meter?.close()
                    codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
                    extractor?.let { runCatching { it.release() } }
                }
            }
        }

    private companion object { val scanMutex = Mutex() }
}

/** Bytes an ALAC packet may take beyond its frame stored uncompressed: the element header and the end tag. */
private const val ALAC_PACKET_HEADROOM = 1024

/** One little-endian signed PCM sample of [bytesPerSample] bytes at [at], scaled to -1..1. */
internal fun pcmSample(bytes: ByteArray, at: Int, bytesPerSample: Int): Float = when (bytesPerSample) {
    2 -> ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
    3 -> ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() shl 16)) / 8388608f
    else -> ((bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
        ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)) / 2147483648f
}
