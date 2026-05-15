/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.encoder

import android.net.Uri
import io.github.nikitasud.latentjam.ml.audio.AudioDecoder
import io.github.nikitasud.latentjam.ml.features.AudioFeatures
import io.github.nikitasud.latentjam.ml.features.FeatureExtractor
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.random.Random
import timber.log.Timber

/**
 * Embed a track by mean-pooling [N_WINDOWS] contiguous 10-second windows starting at
 * one randomly-chosen offset.
 *
 * Why one offset + contiguous windows instead of N independent seeks: on Android the
 * dominant cost for VBR MP3 (and a lot of legacy AAC) is `MediaExtractor.seekTo` — for
 * files without a seek table the extractor falls back to scanning bytes from BOF, which
 * empirically costs 5-25 s/seek on a long track on a Snapdragon 8 Gen 3.
 *
 * Encoder: CLAP HTSAT, raw 48 kHz mono PCM → 512-d L2-normalized.
 */
@Singleton
class EmbeddingExtractor
@Inject
constructor(
    private val encoder: EncoderRuntime,
    private val audioDecoder: AudioDecoder,
) {

    /**
     * Embed the audio at [uri]. Picks ONE random offset, decodes [N_WINDOWS] contiguous 5-s
     * windows starting there, runs the batch through the encoder, mean-pools + L2-normalizes.
     *
     * Per-stage timing is logged at DEBUG level so a quick `adb logcat -s EmbeddingExtractor`
     * gives us decode-vs-encode breakdown live on-device. This is how we caught both (a) the
     * silent fp16/XNNPACK fallback that made encode dominate at 5+ s/track and (b) the
     * 3×-seek decode pathology that made decode dominate at 25 s/seek for long VBR MP3s.
     */
    /**
     * Single-shot result of one embed pass: the 512-d L2-normalized embedding plus
     * the lightweight handcrafted features ([AudioFeatures.bpm], [AudioFeatures.energy])
     * computed from the same PCM. Returned together so the embedding worker writes
     * one row to the DB instead of running two decode passes.
     */
    data class EmbedResult(val embedding: FloatArray, val features: AudioFeatures) {
        override fun equals(other: Any?): Boolean =
            other is EmbedResult &&
                embedding.contentEquals(other.embedding) &&
                features == other.features
        override fun hashCode(): Int =
            embedding.contentHashCode() * 31 + features.hashCode()
    }

    /** Backwards-compatible shorthand that drops the features. */
    fun embed(uri: Uri): FloatArray = embedWithFeatures(uri).embedding

    fun embedWithFeatures(uri: Uri): EmbedResult {
        val tStart = System.nanoTime()
        val durationUs = audioDecoder.durationUs(uri)
        val sliceUs = N_WINDOWS * WINDOW_DURATION_US
        // Full-range random offset. We previously capped this at 60 s to dodge the slow
        // linear page scan that `android.media.MediaExtractor` does for Ogg containers,
        // but since switching to ExoPlayer's MediaExtractorCompat (which does binary-search
        // seek via Ogg granule positions) the seek cost is O(log n) regardless of offset —
        // so we can pick from anywhere in the track without paying for it.
        val maxStartUs = (durationUs - sliceUs).coerceAtLeast(0L)
        val startUs = if (maxStartUs > 0L) Random.Default.nextLong(0L, maxStartUs + 1L) else 0L

        // Up to N windows × WINDOW_SAMPLES samples. Filled in arrival order from the
        // single contiguous decode pass; trailing slots stay zero for short tracks and we
        // only run the encoder on rows we actually captured (see `batchCount`).
        val batchBuf = FloatBuffer.allocate(N_WINDOWS * EncoderRuntime.WINDOW_SAMPLES)
        var batchCount = 0

        val tDecodeStart = System.nanoTime()
        val ok = audioDecoder.streamMonoWindows(
            uri = uri,
            windowSamples = EncoderRuntime.WINDOW_SAMPLES,
            hopSamples = EncoderRuntime.WINDOW_SAMPLES, // back-to-back, no overlap
            targetSampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
            startUs = startUs,
            maxDecodeUs = sliceUs + DECODE_BUFFER_US,
        ) { window ->
            if (batchCount < N_WINDOWS) {
                batchBuf.put(window)
                batchCount += 1
            }
        }
        val decodeMs = (System.nanoTime() - tDecodeStart) / 1_000_000.0

        if (!ok || batchCount == 0) {
            Timber.d("embed: %s → no decode (ok=%b), decode=%.1fms", uri, ok, decodeMs)
            return EmbedResult(zeroEmbedding(), AudioFeatures(bpm = null, energy = 0f))
        }

        batchBuf.rewind()
        val tEncodeStart = System.nanoTime()
        val rows = encoder.embed(batchBuf, batchCount)
        val encodeMs = (System.nanoTime() - tEncodeStart) / 1_000_000.0

        // Compute the handcrafted features on the same PCM we just fed the encoder.
        // The decoded windows live contiguously in `batchBuf` (back-to-back, no overlap),
        // so a single copy gives us up to N_WINDOWS × WINDOW_SAMPLES samples to feed
        // the BPM estimator + RMS. Tempo wants a continuous run; ~15 s is the sweet
        // spot for the autocorrelation estimator.
        batchBuf.rewind()
        val pcmLen = batchCount * EncoderRuntime.WINDOW_SAMPLES
        val featurePcm = FloatArray(pcmLen)
        batchBuf.get(featurePcm, 0, pcmLen)
        // DIAGNOSTIC: log sample range to figure out which decoders produce
        // out-of-[-1,1] PCM (78 % of tracks land at energy=1.0 / bpm=117 which
        // strongly suggests the audio buffer scale is wrong for those files).
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var sumSq = 0.0
        for (v in featurePcm) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sumSq += v.toDouble() * v
        }
        val rmsRaw = kotlin.math.sqrt(sumSq / featurePcm.size).toFloat()
        Timber.d(
            "audio-range %s: min=%.3f max=%.3f rms=%.3f", uri, minV, maxV, rmsRaw,
        )
        val tFeatStart = System.nanoTime()
        val features = FeatureExtractor.extract(featurePcm, AudioDecoder.TARGET_SAMPLE_RATE)
        val featuresMs = (System.nanoTime() - tFeatStart) / 1_000_000.0

        val pooled = FloatArray(EncoderRuntime.EMBEDDING_DIM)
        for (row in rows) {
            for (k in pooled.indices) pooled[k] += row[k]
        }
        val inv = 1.0f / batchCount
        for (k in pooled.indices) pooled[k] *= inv
        val out = l2Normalize(pooled)
        val totalMs = (System.nanoTime() - tStart) / 1_000_000.0
        Timber.d(
            "embed: %s n=%d total=%.1fms decode=%.1fms encode=%.1fms feat=%.1fms bpm=%s energy=%.3f",
            uri, batchCount, totalMs, decodeMs, encodeMs, featuresMs,
            features.bpm?.let { "%.1f".format(it) } ?: "?",
            features.energy,
        )
        return EmbedResult(out, features)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x.toDouble() * x.toDouble()
        if (sumSq <= 0.0) return v
        val inv = (1.0 / sqrt(sumSq)).toFloat()
        for (k in v.indices) v[k] *= inv
        return v
    }

    private fun zeroEmbedding(): FloatArray = FloatArray(EncoderRuntime.EMBEDDING_DIM)

    companion object {
        /** Number of contiguous 10-s windows to mean-pool per track. */
        const val N_WINDOWS = 3
        /** Slice duration in microseconds (10 s — one CLAP window). */
        const val WINDOW_DURATION_US = 10_000_000L
        /** Extra microseconds beyond the window for mp3 frame alignment + resampler latency. */
        const val DECODE_BUFFER_US = 500_000L
    }
}
