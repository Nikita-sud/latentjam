/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * AudioDecoder.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Streaming audio decoder: `MediaExtractor` + `MediaCodec` → mono → resample to target SR →
 * sliding-window emission. Peak memory is one window (≈ 320 KB for 5 s @ 16 kHz).
 *
 * **A note on extractor experiments**: we tried two alternative paths (ExoPlayer's
 * `MediaExtractorCompat` with its `OggExtractor` binary-search seeker, and a custom
 * `ByteArray`-backed `MediaDataSource` to bypass SAF I/O). Both regressed: the platform
 * `MediaExtractor` already maps the URI through the kernel buffer cache and is the fastest path we
 * measured on Snapdragon 8 Gen 3. The 1-15 s per-track variance you'll see in `EmbeddingExtractor`
 * logs is dominated by **random seek offset position** (deeper into the file → more bytes for the
 * extractor to walk on VBR mp3 / Ogg) plus thermal throttling on sustained ML workloads, not by I/O
 * choice.
 *
 * Why streaming: the original "decode-all-then-embed" path held the full PCM buffer in RAM, which
 * capped us at ~90 s of audio per track to avoid OOM. That cap caused dramatic mis-clusters for
 * songs with dynamic structure (Free Bird's acoustic intro fooled it into "Billy Joel"). The
 * streaming window-accumulator keeps PCM memory bounded regardless of track length.
 */
@Singleton
class AudioDecoder @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Stream fixed-length, fixed-overlap windows of mono float PCM at [targetSampleRate].
     *
     * Optionally seek to [startUs] and stop after [maxDecodeUs] microseconds of audio (both default
     * to "whole track from the beginning"). When `maxDecodeUs > 0` we cap the decode so callers can
     * sample only a chunk of the track without paying for the full decode pass.
     *
     * Calls [onWindow] once per emitted window with a freshly-allocated `FloatArray` of exactly
     * [windowSamples] entries. Returns `true` on a clean decode (incl. emitting any partial final
     * window), `false` if MediaExtractor/MediaCodec couldn't open the input.
     */
    fun streamMonoWindows(
        uri: Uri,
        windowSamples: Int,
        hopSamples: Int,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
        startUs: Long = 0L,
        maxDecodeUs: Long = 0L,
        onWindow: (FloatArray) -> Unit,
    ): Boolean {
        // Fast path: mp3 / opus go through our native mmap+minimp3/libopusfile decoder.
        // It bypasses MediaExtractor + MediaCodec entirely, eliminating the 5-15 s SAF
        // seek scan that dominated everything else.
        if (maxDecodeUs > 0L && isNativeFastPathCandidate(uri)) {
            if (
                tryStreamNative(
                    uri,
                    windowSamples,
                    hopSamples,
                    targetSampleRate,
                    startUs,
                    maxDecodeUs,
                    onWindow,
                )
            ) {
                return true
            }
            Timber.v("native fast path declined for %s, falling back to MediaExtractor", uri)
        }
        val demuxer = openDemuxer(uri) ?: return false
        return try {
            val trackIndex = demuxer.selectAudioTrack() ?: return false
            demuxer.selectTrack(trackIndex)
            val format = demuxer.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val sourceSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            if (startUs > 0L) demuxer.seekTo(startUs)

            val resampler = StreamingLinearResampler(sourceSr, targetSampleRate)
            try {
                val window = WindowAccumulator(windowSamples, hopSamples, onWindow)
                val resampleScratch = FloatArray(RESAMPLE_SCRATCH_LEN)
                val monoScratch = FloatArray(MONO_SCRATCH_LEN)
                val maxFrames =
                    if (maxDecodeUs > 0L) {
                        (sourceSr.toLong() * maxDecodeUs / 1_000_000L).toInt()
                    } else {
                        Int.MAX_VALUE
                    }
                var framesConsumed = 0L

                decodeStreaming(demuxer, format, mime) { shortBuf, shortLen ->
                    if (framesConsumed >= maxFrames) return@decodeStreaming
                    val framesAvailable = shortLen / sourceCh.coerceAtLeast(1)
                    val framesBudget =
                        (maxFrames - framesConsumed)
                            .toInt()
                            .coerceAtLeast(0)
                            .coerceAtMost(framesAvailable)
                    if (framesBudget <= 0) return@decodeStreaming
                    var processed = 0
                    while (processed < framesBudget) {
                        val take = (framesBudget - processed).coerceAtMost(monoScratch.size)
                        downmixIntoFloat(
                            shortBuf,
                            processed * sourceCh,
                            sourceCh,
                            monoScratch,
                            0,
                            take,
                        )
                        var resampledPos = 0
                        while (resampledPos < take) {
                            val (consumed, produced) =
                                resampler.feed(
                                    monoScratch,
                                    resampledPos,
                                    take - resampledPos,
                                    resampleScratch,
                                    0,
                                    resampleScratch.size,
                                )
                            if (produced > 0) window.feed(resampleScratch, produced)
                            resampledPos += consumed
                            if (consumed == 0 && produced == 0) break
                        }
                        processed += take
                    }
                    framesConsumed += processed.toLong()
                }
                val tail = FloatArray(RESAMPLE_SCRATCH_LEN)
                val tailLen = resampler.flush(tail)
                if (tailLen > 0) window.feed(tail, tailLen)
                window.flush()
            } finally {
                // Release the native handle (no-op for the Kotlin-only fallback path).
                resampler.close()
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "streamMonoWindows failed for $uri")
            false
        } finally {
            demuxer.close()
        }
    }

    /** Read the track's total duration in microseconds, or 0 if unavailable. */
    fun durationUs(uri: Uri): Long {
        val demuxer = openDemuxer(uri) ?: return 0L
        return try {
            val trackIndex = demuxer.selectAudioTrack() ?: return 0L
            val format = demuxer.getTrackFormat(trackIndex)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
        } catch (e: Exception) {
            Timber.v(e, "durationUs failed for $uri")
            0L
        } finally {
            demuxer.close()
        }
    }

    /** Open the platform `MediaExtractor` on the URI. Returns null on I/O failure. */
    private fun openDemuxer(uri: Uri): Demuxer? {
        return try {
            PlatformDemuxer.openFromUri(context, uri)
        } catch (e: Exception) {
            Timber.w(e, "openDemuxer failed for %s", uri)
            null
        }
    }

    /**
     * Cheap check: is [uri]'s file extension one we accelerate natively? The native decoder covers
     * mp3, opus, vorbis, flac, wav, and AAC-in-M4A. We use extension rather than mime sniffing so
     * we don't pay an extra `ContentResolver` round- trip just to decide. False positives
     * (mis-named file) are harmless — the native sniff inside [NativeAudioDecoder.tryDecode]
     * discards them and returns null, after which we fall back to MediaExtractor.
     */
    private fun isNativeFastPathCandidate(uri: Uri): Boolean {
        val seg = uri.lastPathSegment ?: return false
        val ext = seg.substringAfterLast('.', "").lowercase()
        return ext in NATIVE_FAST_PATH_EXTENSIONS
    }

    /**
     * Native-decoder fast path. Mmaps the file, decodes [maxDecodeUs] of mono PCM at the source SR
     * starting at [startUs], pipes the result through our existing [StreamingLinearResampler] to
     * convert to [targetSampleRate], and feeds windows into [onWindow]. Returns false if the native
     * decoder declined (lib missing, unsupported format, error during decode) so the caller falls
     * through to the MediaExtractor path.
     */
    private fun tryStreamNative(
        uri: Uri,
        windowSamples: Int,
        hopSamples: Int,
        targetSampleRate: Int,
        startUs: Long,
        maxDecodeUs: Long,
        onWindow: (FloatArray) -> Unit,
    ): Boolean {
        val result =
            NativeAudioDecoder.tryDecode(context, uri, startUs, maxDecodeUs) ?: return false
        val pcm = result.samples
        if (pcm.isEmpty()) return false
        // DIAGNOSTIC: scan FULL native PCM right after JNI return — should be in [-1, 1].
        run {
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            for (v in pcm) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            Timber.tag("AudioDecoderDiag")
                .i(
                    "native pcm (full): ext=%s sr=%d n=%d range=[%.4f,%.4f]",
                    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(),
                    result.sourceSampleRate,
                    pcm.size,
                    mn,
                    mx,
                )
        }
        var producedTotal = 0
        val resampler = StreamingLinearResampler(result.sourceSampleRate, targetSampleRate)
        try {
            val window = WindowAccumulator(windowSamples, hopSamples, onWindow)
            val scratch = FloatArray(RESAMPLE_SCRATCH_LEN)
            // Feed the resampler in MONO_SCRATCH_LEN-sized chunks. The resampler's API
            // contract is that srcLen must fit inside the destination buffer's capacity
            // (the inner loop returns srcLen as "consumed" assuming the whole chunk was
            // digested in one call). Passing pcm.size at once would silently truncate
            // output to scratch.size — a 30× short-read in our case.
            val chunk = MONO_SCRATCH_LEN
            var off = 0
            // DIAG: track max abs value seen across ALL resampler output chunks.
            var maxAbsOut = 0f
            while (off < pcm.size) {
                val n = (pcm.size - off).coerceAtMost(chunk)
                val (consumed, produced) = resampler.feed(pcm, off, n, scratch, 0, scratch.size)
                if (produced > 0) {
                    for (i in 0 until produced) {
                        val a = kotlin.math.abs(scratch[i])
                        if (a > maxAbsOut) maxAbsOut = a
                    }
                    window.feed(scratch, produced)
                    producedTotal += produced
                }
                off += consumed
                if (consumed == 0 && produced == 0) break
            }
            Timber.tag("AudioDecoderDiag")
                .i("resampler-pass: max|out|=%.3f over %d produced", maxAbsOut, producedTotal)
            val tail = FloatArray(RESAMPLE_SCRATCH_LEN)
            val tailLen = resampler.flush(tail)
            if (tailLen > 0) {
                window.feed(tail, tailLen)
                producedTotal += tailLen
            }
            window.flush()
        } finally {
            resampler.close()
        }
        Timber.d(
            "native: pcm=%d sr=%d → resampled=%d",
            pcm.size,
            result.sourceSampleRate,
            producedTotal,
        )
        return true
    }

    /**
     * Drive MediaCodec, calling [onChunk] once per output buffer with the decoded PCM as
     * interleaved 16-bit shorts at the source sample rate + channel count.
     */
    private fun decodeStreaming(
        demuxer: Demuxer,
        format: MediaFormat,
        mime: String,
        onChunk: (ShortArray, Int) -> Unit,
    ) {
        val codec = MediaCodec.createDecoderByType(mime)
        val shortScratch = ShortArray(SHORT_SCRATCH_LEN)
        try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 10_000L
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val buf = codec.getInputBuffer(inputIndex) ?: continue
                        val sz = demuxer.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sz, demuxer.getSampleTime(), 0)
                            demuxer.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val nShorts = info.size / 2
                        var copied = 0
                        while (copied < nShorts) {
                            val take = (nShorts - copied).coerceAtMost(shortScratch.size)
                            outBuf.position(info.offset + copied * 2)
                            outBuf.limit(info.offset + (copied + take) * 2)
                            outBuf
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(shortScratch, 0, take)
                            onChunk(shortScratch, take)
                            copied += take
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
        }
    }

    /**
     * Downmix [channels] interleaved shorts to mono floats in [-1, 1]. Reads frames starting at
     * [srcFrameOff*channels] in [src]; writes [count] mono samples into [dst] starting at [dstOff].
     */
    private fun downmixIntoFloat(
        src: ShortArray,
        srcOffShorts: Int,
        channels: Int,
        dst: FloatArray,
        dstOff: Int,
        count: Int,
    ) {
        val ch = channels.coerceAtLeast(1)
        if (ch == 1) {
            for (i in 0 until count) {
                dst[dstOff + i] = src[srcOffShorts + i] / 32768f
            }
            return
        }
        var s = srcOffShorts
        var d = dstOff
        for (i in 0 until count) {
            var acc = 0
            for (c in 0 until ch) acc += src[s + c].toInt()
            dst[d++] = (acc / ch) / 32768f
            s += ch
        }
    }

    companion object {
        // 48 kHz mono float32 — matches CLAP HTSAT's expected input.
        const val TARGET_SAMPLE_RATE = 48_000
        private const val SHORT_SCRATCH_LEN = 8 * 1024
        private const val MONO_SCRATCH_LEN = 8 * 1024
        private const val RESAMPLE_SCRATCH_LEN = 8 * 1024
        private val NATIVE_FAST_PATH_EXTENSIONS =
            setOf(
                "mp3", // minimp3
                "opus",
                "ogg",
                "oga", // libopusfile / stb_vorbis (Ogg-wrapped)
                "flac", // dr_flac
                "wav",
                "wave", // dr_wav
                "m4a",
                "m4b",
                "mp4",
                "aac",
                "f4a", // minimp4 + fdk-aac
            )
    }
}

/**
 * Tiny demux abstraction over `android.media.MediaExtractor` and ExoPlayer's
 * `MediaExtractorCompat`. Both expose the same conceptual API but as unrelated Java types, so we
 * wrap each in a thin Kotlin facade with the methods our decoder actually uses.
 */
private interface Demuxer : Closeable {
    val trackCount: Int

    fun getTrackFormat(index: Int): MediaFormat

    fun selectTrack(index: Int)

    fun seekTo(timeUs: Long)

    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun advance(): Boolean

    fun getSampleTime(): Long

    fun selectAudioTrack(): Int? {
        for (i in 0 until trackCount) {
            val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}

/** Wrapper around the platform `android.media.MediaExtractor`. */
private class PlatformDemuxer private constructor(private val extractor: MediaExtractor) : Demuxer {
    companion object {
        fun openFromUri(context: Context, uri: Uri): PlatformDemuxer {
            val ex = MediaExtractor()
            ex.setDataSource(context, uri, null)
            return PlatformDemuxer(ex)
        }
    }

    override val trackCount: Int
        get() = extractor.trackCount

    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)

    override fun selectTrack(index: Int) = extractor.selectTrack(index)

    override fun seekTo(timeUs: Long) =
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int =
        extractor.readSampleData(buffer, offset)

    override fun advance(): Boolean = extractor.advance()

    override fun getSampleTime(): Long = extractor.sampleTime

    override fun close() {
        try {
            extractor.release()
        } catch (_: Throwable) {}
    }
}

/**
 * Common interface implemented by the native NEON-optimized resampler and the pure-Kotlin fallback.
 * Same algorithm (linear interpolation, O(1) memory, cross-chunk-aware), same call shape — only the
 * language / SIMD backend differs.
 */
internal interface LinearResampler : AutoCloseable {
    /** Returns (input-consumed, output-produced). */
    fun feed(
        src: FloatArray,
        srcOff: Int,
        srcLen: Int,
        dst: FloatArray,
        dstOff: Int,
        dstCap: Int,
    ): Pair<Int, Int>

    /** Drain remaining outputs that don't need future input. Returns produced. */
    fun flush(dst: FloatArray): Int

    /** Default close is a no-op; native impl frees its handle. */
    override fun close() {}
}

/**
 * Thin facade picking the fastest available [LinearResampler] backend at construction. If
 * `linear_resampler.so` loads successfully on this device/ABI we use the C++ NEON implementation
 * (~5-15× faster). Otherwise we fall back to [KotlinLinearResampler] — same algorithm, just runs in
 * the JVM.
 */
internal class StreamingLinearResampler(fromSr: Int, toSr: Int) : LinearResampler {
    private val impl: LinearResampler =
        NativeLinearResampler.tryCreate(fromSr, toSr) ?: KotlinLinearResampler(fromSr, toSr)

    override fun feed(
        src: FloatArray,
        srcOff: Int,
        srcLen: Int,
        dst: FloatArray,
        dstOff: Int,
        dstCap: Int,
    ) = impl.feed(src, srcOff, srcLen, dst, dstOff, dstCap)

    override fun flush(dst: FloatArray) = impl.flush(dst)

    override fun close() {
        impl.close()
    }
}

/**
 * Linear-interpolation streaming resampler. Memory: O(1). Maintains a "previous sample" for
 * interpolation across chunk boundaries; emits as many output samples as fit into the provided
 * destination buffer, returns (input-consumed, output-produced).
 *
 * Pure Kotlin. Kept as the cross-platform fallback used when [NativeLinearResampler] fails to load
 * (e.g. unusual ABI, library stripped from APK, security policy).
 */
internal class KotlinLinearResampler(fromSr: Int, toSr: Int) : LinearResampler {
    private val ratio: Double = fromSr.toDouble() / toSr.toDouble()
    // Global position in the source stream where the next output sample should be sampled.
    private var srcCursor: Double = 0.0
    // Index of the first sample of the current `feed()` chunk in the global stream.
    private var chunkBase: Long = 0L
    private var prevSample: Float = 0f
    private var hasPrev: Boolean = false

    override fun feed(
        src: FloatArray,
        srcOff: Int,
        srcLen: Int,
        dst: FloatArray,
        dstOff: Int,
        dstCap: Int,
    ): Pair<Int, Int> {
        if (srcLen == 0) return 0 to 0
        var produced = 0
        // Local frame index relative to the chunk start: localF in [-1, srcLen-1].
        // -1 means "use prevSample"; srcLen means we ran out and need next chunk.
        while (produced < dstCap) {
            val localF = srcCursor - chunkBase.toDouble()
            // We need samples at floor(localF) and floor(localF)+1. We can only
            // straddle the previous-chunk boundary by ONE sample (using prevSample
            // as s0). If `localF` is further back than -1, the caller must rewind
            // — produce nothing, return early so the caller can re-feed.
            if (localF < -1.0) break
            val i = if (localF >= 0.0) localF.toInt() else -1
            if (i + 1 >= srcLen) break // need next chunk for the upper sample
            val frac = (localF - i.toDouble()).toFloat()
            val s0 = if (i < 0) prevSample else src[srcOff + i]
            val s1 = src[srcOff + i + 1]
            dst[dstOff + produced] = s0 * (1f - frac) + s1 * frac
            produced += 1
            srcCursor += ratio
        }
        // Compute the number of input samples we actually crossed. `srcCursor` now
        // points to the position of the next output to produce; samples up to (but not
        // including) `floor(srcCursor)` have been crossed and are no longer needed.
        // We also keep one "prev" sample (the one just before floor(srcCursor)), so we
        // need to retain input from index `floor(srcCursor) - chunkBase`. Anything
        // before that the caller can discard.
        //
        // Previous bug: this method always reported `srcLen` consumed and advanced
        // `chunkBase` by `srcLen`. When the loop exited early (e.g. dstCap reached
        // before all input was crossed), the lag accumulated chunk over chunk and
        // produced wildly extrapolated outputs (up to ±35000) from in-range inputs.
        val keepFloat = kotlin.math.floor(srcCursor) - chunkBase
        // We keep one "prev" sample so consumed = max(0, keepFloat - 0). Actually keep
        // index `floor(srcCursor) - 1` and onward; consumed = floor(srcCursor) - chunkBase - 1.
        // But on the very first call we may have no prev yet, so clamp to [0, srcLen].
        val crossed =
            (kotlin.math.floor(srcCursor).toLong() - chunkBase)
                .coerceIn(0L, srcLen.toLong())
                .toInt()
        // After this call, the caller will advance `off` by `crossed`; we update
        // chunkBase to match (so next call's `src[0]` is at global position chunkBase + crossed).
        if (crossed > 0) {
            prevSample = src[srcOff + crossed - 1]
            hasPrev = true
        }
        chunkBase += crossed.toLong()
        return crossed to produced
    }

    /** Drain any remaining output samples that don't require future input. */
    override fun flush(dst: FloatArray): Int {
        // Emit until srcCursor passes the last sample (i.e. interpolation would need data
        // we don't have). With linear interpolation, the last valid output is at
        // srcCursor == chunkBase - 1 + ε.
        var produced = 0
        while (produced < dst.size) {
            val localF = srcCursor - chunkBase.toDouble()
            // Last sample we know is at local index -1 (prevSample). Without an i+1
            // partner we can only emit srcCursor that lies exactly at integer position -1
            // (= prevSample). Stop.
            if (localF >= 0.0 || !hasPrev) break
            dst[produced++] = prevSample
            srcCursor += ratio
        }
        return produced
    }
}

/**
 * Sliding-window accumulator. Feeds a stream of mono samples; calls [onWindow] each time
 * [windowSamples] have accumulated, then keeps the last (windowSamples - hopSamples) for overlap
 * with the next window.
 */
internal class WindowAccumulator(
    private val windowSamples: Int,
    private val hopSamples: Int,
    private val onWindow: (FloatArray) -> Unit,
) {
    private val buf = FloatArray(windowSamples)
    private var fill = 0
    private val keep = windowSamples - hopSamples // overlap retained after each emit

    fun feed(src: FloatArray, count: Int) {
        var srcOff = 0
        while (srcOff < count) {
            val take = (windowSamples - fill).coerceAtMost(count - srcOff)
            System.arraycopy(src, srcOff, buf, fill, take)
            fill += take
            srcOff += take
            if (fill == windowSamples) {
                onWindow(buf.copyOf())
                if (keep > 0) {
                    System.arraycopy(buf, hopSamples, buf, 0, keep)
                    fill = keep
                } else {
                    fill = 0
                }
            }
        }
    }

    /** Emit a final partial window if it contains at least 25 % of windowSamples. */
    fun flush() {
        if (fill > windowSamples / 4) {
            val w = FloatArray(windowSamples)
            System.arraycopy(buf, 0, w, 0, fill)
            onWindow(w)
        }
        fill = 0
    }
}
