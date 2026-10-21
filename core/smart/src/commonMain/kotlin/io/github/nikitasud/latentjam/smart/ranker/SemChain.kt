/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/**
 * The cold fix's semChain (reference.py SemChain): 0.9.1's chain terms as a decoder rule term. Per candidate i at a hop
 * with anchor a (the previous pick, the prefix's last for a top-up, the seed at hop 1):
 *
 *   audioPrev · ccos(a, i) + audioSeed · ccos(seed, i) + semSeed · sb(zSeed_i, ccos(seed, i)) + semPrev · sb(zPrev_i, ccos(a, i))
 *
 * ccos = cosine in the library-mean-centred audio space (0 when either side has no vector); z = pool z-scores of the
 * centred cosine in the pack-descriptor and metadata-text spaces (at least 10 valid, sd floor 0.05, clip ±3), the mean
 * of the two where both exist; sb damps a positive z by clamp((cos + 0.05) / 0.25, 0, 1). All in double precision.
 */
internal class SemChain(
    private val spec: RankerModel.SemChainSpec,
    private val seed: TrackId,
    private val pool: List<TrackId>,
    /** Centred unit vectors by track (only the seed, the anchors and the pool are needed). */
    private val audio: Map<TrackId, DoubleArray>,
    private val descriptor: Map<TrackId, DoubleArray>,
    private val text: Map<TrackId, DoubleArray>,
) {
    private val seedCos = DoubleArray(pool.size) { ccos(seed, pool[it]) }
    private val zSeed = zComb(seed)

    private fun ccos(a: TrackId, b: TrackId): Double {
        val va = audio[a] ?: return 0.0
        val vb = audio[b] ?: return 0.0
        return dot(va, vb)
    }

    private fun poolZ(space: Map<TrackId, DoubleArray>, ref: TrackId): Array<Double?>? {
        val vr = space[ref] ?: return null
        val sims = Array(pool.size) { i -> space[pool[i]]?.let { dot(vr, it) } }
        var n = 0
        var sum = 0.0
        for (x in sims) if (x != null) { sum += x; n++ }
        if (n < MIN_VALID) return null
        val mu = sum / n
        var sq = 0.0
        for (x in sims) if (x != null) sq += (x - mu) * (x - mu)
        val sd = maxOf(SD_FLOOR, sqrt(sq / n))
        return Array(pool.size) { i -> sims[i]?.let { ((it - mu) / sd).coerceIn(-CLIP, CLIP) } }
    }

    private fun zComb(ref: TrackId): DoubleArray {
        val zd = poolZ(descriptor, ref)
        val zt = poolZ(text, ref)
        return DoubleArray(pool.size) { i ->
            val a = zd?.get(i)
            val b = zt?.get(i)
            when {
                a != null && b != null -> (a + b) / 2
                a != null -> a
                b != null -> b
                else -> 0.0
            }
        }
    }

    /** The term per pool row for the hop whose anchor is [anchor] (null = the seed). */
    fun terms(anchor: TrackId?): DoubleArray {
        val aid = anchor ?: seed
        val zPrev = if (anchor == null) zSeed else zComb(aid)
        return DoubleArray(pool.size) { i ->
            val ac = ccos(aid, pool[i])
            val sc = seedCos[i]
            spec.audioPrev * ac + spec.audioSeed * sc + spec.semSeed * soundBacked(zSeed[i], sc) + spec.semPrev * soundBacked(zPrev[i], ac)
        }
    }

    companion object {
        const val MIN_VALID = 10
        const val SD_FLOOR = 0.05
        const val CLIP = 3.0

        fun soundBacked(z: Double, cos: Double): Double = if (z <= 0.0) z else z * ((cos + 0.05) / 0.25).coerceIn(0.0, 1.0)

        fun dot(a: DoubleArray, b: DoubleArray): Double {
            if (a.size != b.size) return 0.0
            var s = 0.0
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** Σ unit vectors / n over the library rows that have one (float32 unit vectors, accumulated in double). */
        class Mean(dim: Int) {
            val sum = DoubleArray(dim)
            var n = 0
            fun add(unit: FloatArray) {
                if (unit.size != sum.size) return
                for (i in unit.indices) sum[i] += unit[i].toDouble()
                n++
            }
            fun mean(): DoubleArray? = if (n == 0) null else DoubleArray(sum.size) { sum[it] / n }
        }

        /** v − mean, renormalised; null when it vanishes. */
        fun centred(unit: FloatArray, mean: DoubleArray): DoubleArray? {
            if (unit.size != mean.size) return null
            val c = DoubleArray(unit.size) { unit[it].toDouble() - mean[it] }
            var nn = 0.0
            for (x in c) nn += x * x
            nn = sqrt(nn)
            if (!(nn > 0.0)) return null
            for (i in c.indices) c[i] = c[i] / nn
            return c
        }
    }
}
