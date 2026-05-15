/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.audio

import android.content.Context
import android.net.Uri
import timber.log.Timber

/**
 * JNI bridge to the native multi-format decoder in `native_audio_decoder.cpp`.
 *
 * The native path:
 *   1. `mmap()`s the file via the FD we hand it from `ContentResolver.openAssetFileDescriptor`.
 *   2. Sniffs magic bytes (and synchsafe-skips ID3v2) to pick a decoder:
 *        - mp3        → `minimp3_ex`
 *        - Ogg/Opus   → `libopusfile`
 *        - Ogg/Vorbis → `stb_vorbis`
 *        - FLAC       → `dr_flac` (SEEKTABLE-aware)
 *        - WAV        → `dr_wav`
 *        - M4A / mp4  → `minimp4` demux + `fdk-aac` decode
 *   3. Seeks + decodes exactly `durationUs` of audio starting at `startUs`. Output is
 *      downmixed to mono float32 PCM at the source sample rate.
 *
 * Why we bother: `android.media.MediaExtractor.seekTo` charges 3-15 s/track for random
 * offsets across all of the formats above. The native path is 30-150 ms regardless of
 * file size or offset.
 *
 * Anything we can't identify, plus failures, fall through to `MediaExtractor` in
 * `AudioDecoder`. The lib is loaded lazily; if the .so isn't on the device (unusual ABI,
 * stripped from APK, etc.) [tryDecode] returns null and everything still works.
 */
internal object NativeAudioDecoder {

    private val available: Boolean = run {
        try {
            // Same .so as NativeLinearResampler — see CMakeLists OUTPUT_NAME.
            System.loadLibrary("linear_resampler")
            true
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "NativeAudioDecoder: native lib not available")
            false
        }
    }

    /**
     * Try to decode a mono float32 slice of [uri] starting at [startUs] for [durationUs].
     * Returns null if the file isn't an mp3/opus, the native lib isn't available, the
     * AssetFileDescriptor has a nonzero start offset (we don't handle multi-file bundles
     * yet), or the native decoder hits any error. The caller is expected to fall back to
     * the platform `MediaExtractor` path in that case.
     */
    fun tryDecode(context: Context, uri: Uri, startUs: Long, durationUs: Long): NativeDecodeResult? {
        if (!available) return null
        // Open via ContentResolver — for SAF documents this typically returns an AFD
        // wrapping a real file descriptor with startOffset=0.
        val afd = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Timber.v(e, "NativeAudioDecoder: openAssetFileDescriptor failed for %s", uri)
            null
        } ?: return null
        return afd.use {
            // mmap() with a non-zero file offset has to be page-aligned, and our native
            // side currently maps the whole FD from byte 0. Bail to the slow path if the
            // AFD covers only a sub-region of the FD (e.g. an asset bundle).
            if (it.startOffset != 0L) {
                Timber.v("NativeAudioDecoder: skipping nonzero AFD startOffset=%d", it.startOffset)
                return@use null
            }
            val len = if (it.declaredLength > 0) it.declaredLength else it.length
            if (len <= 0L) return@use null
            val fd = try {
                it.parcelFileDescriptor.fd
            } catch (e: Exception) {
                Timber.v(e, "NativeAudioDecoder: failed to get fd")
                return@use null
            }
            val outSr = IntArray(1)
            val samples = try {
                nativeDecode(fd, len, startUs, durationUs, outSr)
            } catch (e: Throwable) {
                Timber.w(e, "NativeAudioDecoder: nativeDecode threw")
                null
            } ?: return@use null
            NativeDecodeResult(samples = samples, sourceSampleRate = outSr[0])
        }
    }

    /**
     * Cheap sniff of the first few bytes to predict whether the native path will succeed.
     * Returns true for files we'd route to native (mp3 or Ogg). Useful when callers want
     * to avoid even opening the AFD for known-bad formats. Currently unused (we let the
     * native side sniff inside [tryDecode]), but kept for callers that want to gate by
     * format before paying for the AFD open.
     */
    fun isHandledFormat(header: ByteArray): Boolean {
        if (!available || header.size < 4) return false
        return nativeSniff(header) != 0
    }

    @JvmStatic
    private external fun nativeDecode(
        fd: Int,
        fileLen: Long,
        startUs: Long,
        durationUs: Long,
        outSr: IntArray,
    ): FloatArray?

    @JvmStatic
    private external fun nativeSniff(header: ByteArray): Int

    data class NativeDecodeResult(
        /** Mono float32 PCM, length = `(durationUs * sourceSampleRate / 1e6)` (approx). */
        val samples: FloatArray,
        /** Source sample rate. For opus this is always 48000; mp3 varies (typically 44100). */
        val sourceSampleRate: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            other is NativeDecodeResult && samples.contentEquals(other.samples) &&
                sourceSampleRate == other.sourceSampleRate

        override fun hashCode(): Int =
            samples.contentHashCode() * 31 + sourceSampleRate
    }
}
