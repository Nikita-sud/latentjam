/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * What an analysis window decoded short of its 10 s becomes (external review 2026-11-02, AUD-02).
 *
 * The encoder reads a fixed 320,000-sample window. A file shorter than that, or one whose audio ends before its
 * metadata says, used to reach it zero-padded, and padded windows embed alike whatever the music: on public FMA clips
 * a 2–9 s window's nearest other row was another padded clip 59–60 times in 60 (chance about 0.5). Repeating the audio
 * instead embeds like an unpadded window (23/60), and closer to the whole track.
 *
 * A window short by less than [FULL_SLACK_SAMPLES] counts as full and is left exactly as decoded, so every full-length
 * track keeps its bytes. The iOS host (IosOnnxInferenceProvider.swift) applies the same rule to its own decode.
 */
internal object WindowCoverage {
    /** 100 ms at 32 kHz: a resampler's flush, not missing music. */
    const val FULL_SLACK_SAMPLES: Int = 3_200

    /** Below this share of its samples, a window a longer track should fill is dropped rather than repeated. */
    const val MIN_PARTIAL_COVERAGE: Double = 0.5

    enum class Use { AS_DECODED, REPEATED, DROPPED }

    /**
     * How a window with [valid] of [size] samples is used. [mayBeShort]: the window is the track's only one (its
     * duration is unknown or at most one window) or the head retry, so it holds all the audio the file has.
     */
    fun use(valid: Int, size: Int, mayBeShort: Boolean): Use = when {
        valid >= size - FULL_SLACK_SAMPLES -> Use.AS_DECODED
        valid <= 0 -> Use.DROPPED
        mayBeShort || valid >= size * MIN_PARTIAL_COVERAGE -> Use.REPEATED
        else -> Use.DROPPED
    }

    /** Fills [samples] past [valid] with the audio before it, repeated from its start: `samples[i] = samples[i % valid]`. */
    fun repeat(samples: FloatArray, valid: Int) {
        require(valid in 1..samples.size) { "valid samples $valid of ${samples.size}" }
        var at = valid
        while (at < samples.size) {
            val count = minOf(valid, samples.size - at)
            samples.copyInto(samples, destinationOffset = at, startIndex = 0, endIndex = count)
            at += count
        }
    }

    /** Whether both hosts analyse a track of this duration in one window: unknown, or at most [WINDOW_MS]. */
    fun isSingleWindow(durationMs: Long?): Boolean = durationMs == null || durationMs <= WINDOW_MS

    /** One analysis window, 320,000 samples at 32 kHz. */
    const val WINDOW_MS: Long = 10_000L
}
