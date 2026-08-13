/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.log10
import kotlin.math.pow

/**
 * Track loudness measurement and playback normalization math.
 *
 * The measure is plain mean-square RMS over sampled decode windows, expressed in dBFS. It is not
 * BS.1770 LUFS — no K-weighting, no gating — but master-level differences between quiet and loud
 * files dominate both measures, and RMS needs nothing beyond the waveform the embedding decoder
 * already produces.
 */
public object Loudness {

    /** Decoded samples plus how many came from media rather than decoder zero-padding. */
    public data class Window(
        public val samples: FloatArray,
        public val validSamples: Int = samples.size,
    )

    /**
     * Reference level playback normalizes toward. Attenuation-only: tracks quieter than this play
     * untouched rather than being digitally boosted into clipping.
     */
    public const val TARGET_DB: Float = -14f

    /** Below this many real samples a window set is an artifact, not a measurement. */
    public const val MIN_MEASURED_SAMPLES: Int = 8_000

    /** The floor keeps a mismeasured track audible no matter what the store claims. */
    public const val MIN_VOLUME: Float = 0.05f

    /**
     * Mean-square loudness of [windows] in dBFS, or null for silence/too little signal. Explicit
     * valid lengths exclude decoder padding without mistaking genuine digital silence for padding.
     */
    public fun measureDb(windows: List<Window>): Float? {
        var energy = 0.0
        var samples = 0L
        for (window in windows) {
            val validSamples = window.validSamples.coerceIn(0, window.samples.size)
            for (index in 0 until validSamples) {
                val sample = window.samples[index]
                if (!sample.isFinite()) return null
                energy += sample.toDouble() * sample
            }
            samples += validSamples
        }
        if (samples < MIN_MEASURED_SAMPLES) return null
        val meanSquare = energy / samples
        if (!meanSquare.isFinite() || meanSquare <= 1e-10) return null
        return (10.0 * log10(meanSquare)).toFloat()
    }

    /** Playback volume for a track measured at [loudnessDb]; 1 for quiet tracks (never boosts). */
    public fun normalizationVolume(loudnessDb: Float, targetDb: Float = TARGET_DB): Float {
        if (!loudnessDb.isFinite()) return 1f
        val gainDb = targetDb - loudnessDb
        if (gainDb >= 0f) return 1f
        return 10f.pow(gainDb / 20f).coerceIn(MIN_VOLUME, 1f)
    }
}
