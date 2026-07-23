/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSpace

/**
 * Stable output contract of the compact universal semantic head.
 *
 * The head runs over the same 960-dimensional normalized audio fingerprint already stored for
 * SMART, so classification does not decode a track twice. The first 14 values come from selected
 * and aggregated AudioSet concepts; genre values combine the calibrated FMA branch with selected
 * AudioSet families. All values are multi-label scores in `[0, 1]`, not exclusive classes.
 */
public enum class SemanticLabel(public val modelIndex: Int) {
    MUSIC(0),
    SPEECH(1),
    SOUND_EFFECTS(2),
    INSTRUMENTAL(3),
    NOVELTY_PROXY(4),
    ENERGY_LOW(5),
    ENERGY_HIGH(6),
    MOOD_HAPPY(7),
    MOOD_FUNNY(8),
    MOOD_SAD(9),
    MOOD_TENDER(10),
    MOOD_EXCITING(11),
    MOOD_ANGRY(12),
    MOOD_SCARY(13),
    GENRE_INTERNATIONAL(14),
    GENRE_POP(15),
    GENRE_ROCK(16),
    GENRE_ELECTRONIC(17),
    GENRE_FOLK(18),
    GENRE_HIP_HOP(19),
    GENRE_EXPERIMENTAL(20),
    GENRE_METAL(21),
    GENRE_JAZZ_BLUES(22),
    GENRE_CLASSICAL(23),
    GENRE_REGGAE(24),
    GENRE_COUNTRY(25),
    GENRE_AMBIENT_SOUNDTRACK(26),
}

/**
 * One track's immutable multi-label semantic prediction.
 *
 * Construction is strict: a partially corrupt model batch must not silently route tracks into the
 * wrong mix. Rejected rows simply retain the history-independent clustering fallback.
 */
public class TrackSemantics private constructor(
    private val scores: FloatArray,
) {
    public fun probability(label: SemanticLabel): Float = scores[label.modelIndex]

    public fun copyScores(): FloatArray = scores.copyOf()

    public companion object {
        public const val OUTPUT_SIZE: Int = 27

        public fun fromModelOutput(output: FloatArray): TrackSemantics? {
            if (output.size != OUTPUT_SIZE) return null
            if (output.any { !it.isFinite() || it !in 0f..1f }) return null
            return TrackSemantics(output.copyOf())
        }
    }
}

/** One coherent engine snapshot used to discover and semantically route local library mixes. */
public data class LibraryMixFeatures(
    public val vectorSpace: LibraryVectorSpace,
    public val semantics: Map<TrackId, TrackSemantics>,
)
