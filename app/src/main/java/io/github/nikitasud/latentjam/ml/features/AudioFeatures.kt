/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.features

import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-track audio features computed offline, alongside the MusicNN embedding, from
 * the same 15-second mono-float32 PCM slice the encoder consumes. Cheap to compute,
 * stored next to the embedding so RecommendationEngine can filter on tempo and
 * penalize on energy distance without paying a second decode.
 *
 * @property bpm Estimated tempo in beats-per-minute. Null when the estimator can't
 *   commit (low autocorrelation peak relative to baseline noise — typically ambient,
 *   orchestral, or otherwise non-percussive material). Treat as "unknown" in filters.
 * @property energy RMS loudness in [0, 1]. Deterministic, always present.
 */
data class AudioFeatures(
    val bpm: Float?,
    val energy: Float,
)

/**
 * Detect BPM and compute RMS loudness from a mono float32 PCM buffer at [sampleRate].
 *
 * The BPM path mirrors `librosa.feature.rhythm.tempo` (rhythm.py lines 491–656):
 *   1. Onset envelope: frame-to-frame positive log-energy differences (cheap
 *      spectral-flux approximation, no FFT).
 *   2. Autocorrelation over the lag range corresponding to [MIN_BPM, MAX_BPM].
 *   3. **Log2-Gaussian prior** centered at [START_BPM] with σ = [STD_BPM] octaves
 *      added to `log1p(1e6 * acf)` (librosa rhythm.py line 315).
 *   4. argmax of (log-acf + log-prior).
 *
 * Why the prior matters: raw autocorrelation peaks at sub-multiples of the true tempo
 * just as strongly as at the tempo itself, so naive peak-picking gives octave-down
 * errors on most percussive material (the previous detector estimated 35 % of this
 * library at <80 BPM — completely wrong distribution). The prior makes 60 BPM cost
 * `exp(-0.5)` ≈ 0.6× relative to 120 BPM, and 30 BPM cost `exp(-2)` ≈ 0.14×, so
 * harmonic doubles only win when their autocorrelation peak is meaningfully larger.
 *
 * Designed for ~30 s of audio at the encoder's native sample rate (currently 48 kHz
 * for CLAP). Frame/hop are derived from seconds, not raw sample counts, so the
 * envelope frame rate stays at the librosa-default 31.25 Hz regardless of input SR
 * — the lag-to-BPM math depends on that constant. FFT-free; the autocorrelation
 * inner loop dominates at ~10 ms on a Snapdragon 8 Gen 3. No allocations beyond
 * one frame buffer + envelope buffer + lag arrays.
 */
object FeatureExtractor {

    /** Frame size for the onset envelope, in seconds. 64 ms — matches librosa default. */
    private const val FRAME_SECONDS = 0.064
    /** Hop, in seconds. 32 ms → 31.25 Hz envelope frame rate regardless of input SR. */
    private const val HOP_SECONDS = 0.032

    /**
     * Search bounds for the tempo. librosa's defaults are 30..320. We trim to 50..240
     * because (a) anything below 50 BPM is sub-bass material we don't have, (b) anything
     * above 240 BPM is "drum-and-bass at 2× detection", and the prior won't always
     * push it down on its own.
     */
    private const val MIN_BPM = 50f
    private const val MAX_BPM = 240f

    /**
     * Center of the log2-Gaussian prior, in BPM. librosa default is 120 — fits a mixed
     * library that leans dance/pop/rock. Setting this lower (e.g. 90) makes ambient /
     * orchestral tracks resolve more sensibly but pushes the dance cluster off-center.
     */
    private const val START_BPM = 120.0
    /**
     * Standard deviation of the prior, in octaves (log2 units). librosa default is 1.0,
     * which means BPMs exactly one octave away from [START_BPM] (so 60 or 240) get a
     * `exp(-0.5)` ≈ 0.6× weight. Tighter (0.5) over-commits to 120; looser (2.0) gives
     * the prior almost no effect and reintroduces octave errors.
     */
    private const val STD_BPM = 1.0

    /**
     * Minimum onset-envelope variance before we commit to a BPM. Tracks below this
     * threshold are essentially silent / continuous tones (drones, ambient pads) and
     * the autocorrelation peak is noise; we'd rather return null than fake a tempo.
     */
    private const val MIN_ENV_VARIANCE = 1e-6

    private val LOG2 = ln(2.0)

    /**
     * Extract features from a mono PCM buffer. The encoder already produces this
     * exact shape upstream, so we tap into the same buffer instead of decoding again.
     */
    fun extract(pcm: FloatArray, sampleRate: Int): AudioFeatures {
        return AudioFeatures(
            bpm = estimateBpm(pcm, sampleRate),
            energy = perceptualEnergy(pcm),
        )
    }

    /**
     * dBFS-normalized loudness in [0, 1]. Maps the typical music range linearly in
     * decibels: silence → 0, full-scale square wave → 1, with the boundary at -60 dBFS.
     *
     * Why not raw RMS: typical mastered pop sits at -10 to -6 dBFS RMS, which raw-RMS
     * would output as ~0.30-0.50. Modern brick-walled material crosses ~0.6 raw RMS
     * and saturates the `coerceIn(0, 1)` ceiling on the previous implementation.
     * Mapping through dB gives meaningful discrimination across the pop/classical
     * dynamic range instead of having 70% of the library cluster at energy=1.0.
     */
    private fun perceptualEnergy(pcm: FloatArray): Float {
        if (pcm.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in pcm) sumSq += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSq / pcm.size).toFloat()
        if (rms <= 1e-6f) return 0f
        val dbfs = (20.0 * log10(rms.toDouble())).toFloat()
        return ((dbfs + 60f) / 60f).coerceIn(0f, 1f)
    }

    /**
     * Onset envelope via banded spectral flux. We avoid a full FFT — instead we
     * approximate the envelope with the absolute first difference of frame-wise
     * log-energy, which captures the same "things-just-got-louder" signal a real
     * spectral-flux detector would, at a fraction of the cost. Good enough for
     * tempo, where we only care about periodicity, not phase.
     */
    private fun onsetEnvelope(pcm: FloatArray, sampleRate: Int): FloatArray {
        val frameSize = (FRAME_SECONDS * sampleRate).toInt()
        val hop = (HOP_SECONDS * sampleRate).toInt()
        if (frameSize <= 0 || hop <= 0 || pcm.size < frameSize) return FloatArray(0)
        val nFrames = (pcm.size - frameSize) / hop + 1
        val logEnergy = FloatArray(nFrames)
        for (i in 0 until nFrames) {
            val start = i * hop
            var sumSq = 0.0
            for (j in 0 until frameSize) {
                val x = pcm[start + j]
                sumSq += x.toDouble() * x.toDouble()
            }
            // log(1 + ...) so silent frames don't blow up to -infinity.
            logEnergy[i] = ln(1.0 + sumSq).toFloat()
        }
        // Half-wave-rectified first difference: only energy *increases* count as
        // onsets. Falling energy isn't a percussive event.
        val env = FloatArray((nFrames - 1).coerceAtLeast(0))
        for (i in 1 until nFrames) {
            val d = logEnergy[i] - logEnergy[i - 1]
            env[i - 1] = if (d > 0f) d else 0f
        }
        return env
    }

    /**
     * Tempo estimate via autocorrelation + log2-Gaussian prior over BPM. Mirrors
     * `librosa.feature.rhythm.tempo` — `score(lag) = log1p(1e6 * acf(lag)) + log_prior(lag)`
     * where `log_prior(lag) = -0.5 * ((log2(bpm_at(lag)) - log2(START_BPM)) / STD_BPM)^2`.
     *
     * Returns null when the onset envelope is essentially silent (variance below
     * [MIN_ENV_VARIANCE]) — typical for drones / ambient / continuous-tone tracks
     * where any tempo we picked would be a hallucination.
     */
    private fun estimateBpm(pcm: FloatArray, sampleRate: Int): Float? {
        val env = onsetEnvelope(pcm, sampleRate)
        if (env.size < 32) return null

        // Onset envelope frame rate (frames/sec). HOP_SECONDS is fixed at 32 ms so
        // frameRate is ~31.25 Hz regardless of input sample rate; lag (in frames) →
        // BPM via `bpm = 60 * frameRate / lag`.
        val frameRate = (1.0 / HOP_SECONDS).toFloat()
        val minLag = (frameRate * 60f / MAX_BPM).toInt().coerceAtLeast(1)
        val maxLag = (frameRate * 60f / MIN_BPM).toInt().coerceAtMost(env.size - 1)
        if (maxLag <= minLag) return null

        // Center the envelope so autocorrelation isn't dominated by the DC component.
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        val centered = FloatArray(env.size) { (env[it] - mean.toFloat()) }

        // Variance gate: silent / drone tracks have ~zero variance after centering and
        // produce noise in the autocorrelation. Decline to commit on those rather than
        // emit garbage.
        var variance = 0.0
        for (v in centered) variance += v.toDouble() * v
        variance /= centered.size
        if (variance < MIN_ENV_VARIANCE) return null

        // Unbiased autocorrelation + log-prior scoring in a single pass. The prior is
        // log2-Gaussian centered at START_BPM with std STD_BPM octaves; same form
        // librosa uses (rhythm.py:315).
        val log2Start = ln(START_BPM) / LOG2
        var bestScore = Double.NEGATIVE_INFINITY
        var bestLag = -1
        for (lag in minLag..maxLag) {
            var s = 0.0
            val n = env.size - lag
            for (i in 0 until n) s += centered[i] * centered[i + lag]
            val acf = s / n
            val bpm = 60.0 * frameRate / lag
            val z = (ln(bpm) / LOG2 - log2Start) / STD_BPM
            val logPrior = -0.5 * z * z
            // log1p of a non-negative quantity. Negative autocorrelation values mean
            // "the lag is anti-periodic" — we clamp to 0 so they only contribute the
            // prior (which is still informative).
            val logAcf = ln1p(1e6 * max(0.0, acf))
            val score = logAcf + logPrior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        return (60.0 * frameRate / bestLag).toFloat()
    }
}
