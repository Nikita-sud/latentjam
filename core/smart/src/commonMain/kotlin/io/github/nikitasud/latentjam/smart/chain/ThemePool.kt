/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A queue's theme as a candidate channel (2026-10-28): which library tracks belong in a queue started from a reference,
 * scored as a whole set rather than one hop at a time. The DeepSeek pool check (FINDINGS §51) found half of what a
 * queue teacher picks never enters the chain's pool, which is built from sound neighbours; a small linear model over
 * [FEATURES] seed-relative features, fitted to the teacher's queues (P(track in the queue | seed)), recovers them.
 * Its top rows take up to [slots] of the pool, outside the continuation mode's sound neighbourhood; the walk still
 * orders and filters them as every other candidate.
 *
 * @property weights one per feature, in [FEATURE_NAMES] order
 */
internal class ThemePool(
    val weights: FloatArray,
    val slots: Int = DEFAULT_SLOTS,
    /** Spreads of the hop's scores a candidate gains per unit of its theme z-score; 0 leaves the theme a pool channel only. */
    val bonus: Float = 0f,
) {
    init {
        require(weights.size == FEATURES) { "ThemePool needs $FEATURES weights, got ${weights.size}" }
    }

    /** The eligible rows other than [reference], best theme score first. */
    fun ranking(snapshot: SmartSnapshot, reference: Int, eligible: BooleanArray, features: FloatArray = features(snapshot, reference)): IntArray {
        val n = snapshot.size
        val scores = FloatArray(n)
        for (row in 0 until n) {
            var s = 0f
            for (k in 0 until FEATURES) s += weights[k] * features[row * FEATURES + k]
            scores[row] = s
        }
        return (0 until n).filter { it != reference && eligible[it] }.sortedByDescending { scores[it] }.toIntArray()
    }

    /** The theme scores of every row as z-scores over the library. */
    fun zScores(n: Int, features: FloatArray): FloatArray {
        val scores = FloatArray(n) { row -> var s = 0f; for (k in 0 until FEATURES) s += weights[k] * features[row * FEATURES + k]; s }
        return zScores(scores, null)
    }

    companion object {
        const val FEATURES: Int = 8
        const val DEFAULT_SLOTS: Int = 25
        val FEATURE_NAMES: List<String> = listOf(
            "audio_z", "descriptor_z", "family_jaccard", "family_any", "same_language", "same_era", "same_artist", "energy_gap",
        )

        /** Unknown era or energy: the neutral value a known pair averages to. */
        private const val UNKNOWN_ERA = 0.5f
        private const val UNKNOWN_ENERGY_GAP = 0.3f
        private const val ERA_YEARS = 10
        private const val POOL_NEIGHBOURS = 100

        /**
         * Row-major `n × FEATURES` features of every row against [reference]: audio and descriptor cosines as z-scores over
         * the library (rows without a descriptor 0), genre-family Jaccard and any-overlap, the same script language, the same
         * era (years within [ERA_YEARS]), a shared artist (the credit's artist set) that also sounds alike, and the energy gap.
         */
        fun features(snapshot: SmartSnapshot, reference: Int): FloatArray {
            val n = snapshot.size
            val out = FloatArray(n * FEATURES)
            val audio = zScores(snapshot.centeredCosines(reference), null)
            val descriptorCos = snapshot.descriptorCosines(reference)
            val has = snapshot.hasDescriptor
            val descriptor = if (descriptorCos != null && has != null && has[reference]) zScores(descriptorCos, has) else null
            val sameArtistFloor = audio.filterIndexed { i, _ -> i != reference }.sortedDescending()
                .take(POOL_NEIGHBOURS).let { top -> if (top.isEmpty()) Float.NEGATIVE_INFINITY else top[top.size / 2] }
            val ref = snapshot.tracks[reference]
            val refMeta = ref.meta
            for (row in 0 until n) {
                val track = snapshot.tracks[row]
                val meta = track.meta
                val o = row * FEATURES
                out[o] = audio[row]
                out[o + 1] = descriptor?.get(row) ?: 0f
                val a = refMeta.genreFamilies
                val b = meta.genreFamilies
                if (a.isNotEmpty() && b.isNotEmpty()) {
                    val shared = a.count { it in b }
                    out[o + 2] = shared.toFloat() / (a.size + b.size - shared)
                    out[o + 3] = if (shared > 0) 1f else 0f
                }
                out[o + 4] = if (meta.language == refMeta.language) 1f else 0f
                val ya = refMeta.eraYear
                val yb = meta.eraYear
                out[o + 5] = if (ya != null && yb != null) (if (abs(ya - yb) <= ERA_YEARS) 1f else 0f) else UNKNOWN_ERA
                // Similarity, not the artist (owner, §46): a shared artist counts only for a track that also sounds like
                // the reference, at or above the median of its POOL_NEIGHBOURS nearest by sound.
                out[o + 6] = if (refMeta.sharesArtistWith(meta) && audio[row] >= sameArtistFloor) 1f else 0f
                out[o + 7] = if (ref.energy.isNaN() || track.energy.isNaN()) UNKNOWN_ENERGY_GAP else abs(ref.energy - track.energy)
            }
            return out
        }

        /** z-scores of [values] over the rows [mask] keeps (all when null); other rows 0. */
        internal fun zScores(values: FloatArray, mask: BooleanArray?): FloatArray {
            var n = 0
            var sum = 0.0
            for (i in values.indices) if (mask == null || mask[i]) { sum += values[i]; n++ }
            if (n < 2) return FloatArray(values.size)
            val mean = sum / n
            var sq = 0.0
            for (i in values.indices) if (mask == null || mask[i]) { val d = values[i] - mean; sq += d * d }
            val sd = sqrt(sq / n).takeIf { it > 1e-9 } ?: return FloatArray(values.size)
            return FloatArray(values.size) { i -> if (mask == null || mask[i]) ((values[i] - mean) / sd).toFloat() else 0f }
        }
    }
}
