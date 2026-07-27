/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.random.Random

/** One track's inputs to the chain. Only [audio] is required; the rest sharpen the result. */
internal data class SmartTrack(
    val id: TrackId,
    val audio: FloatArray,
    val text: FloatArray? = null,
    /** Optional second semantic space for experiments and recorded parity fixtures. */
    val descriptor: FloatArray? = null,
    val energy: Float = Float.NaN,
    val meta: TrackMeta,
) {
    override fun equals(other: Any?): Boolean = other is SmartTrack && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * The library prepared for chain building: centered spaces, hub penalties and availability masks,
 * all computed once per SMART activation.
 *
 * Centering matters more than it looks. Raw embedding cosines are dominated by a shared mean
 * direction, which makes everything look similar to everything; subtracting the corpus mean and
 * renormalising is what turns cosine into a usable ranking signal.
 */
internal class SmartSnapshot private constructor(
    val tracks: List<SmartTrack>,
    /** Raw audio rows, N × [AUDIO_DIM], as the scorer consumes them. */
    val rawAudio: FloatArray,
    /** Centered, renormalised audio rows — the space all chain cosines are measured in. */
    val centeredAudio: FloatArray,
    /** Per-row CSLS hub penalty, zero-meaned across the corpus. */
    val hubPenalty: FloatArray,
    /**
     * Per-row projection onto the corpus mean direction, z-scored — how much of this library
     * sounds like the track. This is precisely the axis [centeredAudio] removes, kept because
     * the removal is not free: measured against real listening it predicts whether a track is
     * kept better than the centered cosine does. See TypicalityTest for the numbers.
     */
    val typicality: FloatArray,
    val centeredText: FloatArray?,
    val hasText: BooleanArray?,
    val centeredDescriptor: FloatArray?,
    val hasDescriptor: BooleanArray?,
    /**
     * Unit-normalised but UNCENTERED text rows. Candidate generation ranks this space separately;
     * the learned scorer receives it as optional conditioning.
     */
    val rawText: FloatArray?,
) {
    val size: Int get() = tracks.size

    private val rowById: Map<TrackId, Int> =
        tracks.withIndex().associate { (index, track) -> track.id to index }

    fun rowOf(id: TrackId): Int = rowById[id] ?: -1

    /** Centered-space cosine between two rows. Both rows are unit-norm, so this is a dot product. */
    fun centeredCosine(rowA: Int, rowB: Int): Float {
        if (rowA < 0 || rowB < 0) return 0f
        var dot = 0f
        val baseA = rowA * AUDIO_DIM
        val baseB = rowB * AUDIO_DIM
        for (d in 0 until AUDIO_DIM) dot += centeredAudio[baseA + d] * centeredAudio[baseB + d]
        return dot
    }

    /**
     * Centered-descriptor cosine between two rows, or `null` when the descriptor space is absent or
     * either row lacks a descriptor — callers then fall back to audio-only (see [Reanchor.fusedCos]).
     */
    fun descriptorCosine(rowA: Int, rowB: Int): Float? {
        val dc = centeredDescriptor ?: return null
        val has = hasDescriptor ?: return null
        if (rowA < 0 || rowB < 0 || !has[rowA] || !has[rowB]) return null
        var dot = 0f
        val baseA = rowA * DESCRIPTOR_DIM
        val baseB = rowB * DESCRIPTOR_DIM
        for (d in 0 until DESCRIPTOR_DIM) dot += dc[baseA + d] * dc[baseB + d]
        return dot
    }

    companion object {
        const val AUDIO_DIM = 960
        const val TEXT_DIM = 384
        const val DESCRIPTOR_DIM = 768

        /**
         * Rows whose embedding is essentially zero are dropped outright: they sit at the origin of
         * the centered space and therefore look equally similar to everything, which lets them
         * surface as neighbours of any seed.
         */
        const val DEGENERATE_NORM_EPS = 1e-3f

        /** CSLS neighbourhood size for the hub penalty. */
        const val HUB_TOPK = 10

        fun build(tracks: List<SmartTrack>): SmartSnapshot? {
            val usable = tracks.filter { it.audio.size == AUDIO_DIM && norm(it.audio) >= DEGENERATE_NORM_EPS }
            val n = usable.size
            if (n == 0) return null

            // Renormalised on the way in. The encoder emits unit vectors already, but persisted
            // and migrated rows drift, and both the scorer and the raw retrieval cosines assume
            // unit norm — a single long row would otherwise rank first against every query.
            val raw = FloatArray(n * AUDIO_DIM)
            for (i in 0 until n) {
                val v = usable[i].audio
                val scale = 1f / norm(v).coerceAtLeast(1e-12f)
                val base = i * AUDIO_DIM
                for (d in 0 until AUDIO_DIM) raw[base + d] = v[d] * scale
            }

            // Centered + renormalised copy.
            val mean = FloatArray(AUDIO_DIM)
            for (i in 0 until n) {
                val base = i * AUDIO_DIM
                for (d in 0 until AUDIO_DIM) mean[d] += raw[base + d]
            }
            for (d in 0 until AUDIO_DIM) mean[d] /= n
            val centered = FloatArray(n * AUDIO_DIM)
            for (i in 0 until n) {
                val base = i * AUDIO_DIM
                var sumSq = 0.0
                for (d in 0 until AUDIO_DIM) {
                    val v = raw[base + d] - mean[d]
                    centered[base + d] = v
                    sumSq += v.toDouble() * v
                }
                val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until AUDIO_DIM) centered[base + d] *= scale
            }

            val (text, hasText) = centeredMasked(usable, TEXT_DIM) { it.text }
            val (descriptor, hasDescriptor) = centeredMasked(usable, DESCRIPTOR_DIM) { it.descriptor }
            val rawText = if (hasText == null) null else FloatArray(n * TEXT_DIM).also { out ->
                for (i in 0 until n) if (hasText[i]) {
                    val v = usable[i].text!!
                    val scale = 1f / norm(v).coerceAtLeast(1e-12f)
                    for (d in 0 until TEXT_DIM) out[i * TEXT_DIM + d] = v[d] * scale
                }
            }

            return SmartSnapshot(
                tracks = usable,
                rawAudio = raw,
                centeredAudio = centered,
                hubPenalty = computeHubPenalty(centered, n),
                typicality = computeTypicality(raw, mean, n),
                centeredText = text,
                hasText = hasText,
                centeredDescriptor = descriptor,
                hasDescriptor = hasDescriptor,
                rawText = rawText,
            )
        }

        /**
         * Projection of every row onto the corpus mean direction, z-scored.
         *
         * The z-score is what makes the chain's weight portable: the raw projection's scale
         * depends on how tightly a particular library clusters, so an absolute weight tuned on
         * one collection would mean something else on another. After z-scoring, a weight is
         * expressed in standard deviations of *this* library and behaves the same everywhere.
         *
         * Returns all zeros — a term that cannot fire — when the mean direction is degenerate or
         * every row projects identically, which is the correct neutral for a library too small or
         * too uniform to have a centre.
         */
        private fun computeTypicality(raw: FloatArray, mean: FloatArray, n: Int): FloatArray {
            val out = FloatArray(n)
            val meanNorm = norm(mean)
            if (meanNorm < DEGENERATE_NORM_EPS) return out
            val scale = 1f / meanNorm
            var sum = 0.0
            for (i in 0 until n) {
                val base = i * AUDIO_DIM
                var dot = 0f
                for (d in 0 until AUDIO_DIM) dot += raw[base + d] * mean[d] * scale
                out[i] = dot
                sum += dot
            }
            val average = (sum / n).toFloat()
            var sumSq = 0.0
            for (i in 0 until n) {
                val delta = out[i] - average
                sumSq += delta.toDouble() * delta
            }
            val deviation = sqrt(sumSq / n).toFloat()
            if (deviation < DEGENERATE_NORM_EPS) return FloatArray(n)
            for (i in 0 until n) out[i] = (out[i] - average) / deviation
            return out
        }

        /**
         * CSLS hub correction: a track's mean centered cosine to its [HUB_TOPK] nearest
         * neighbours, zero-meaned across the corpus. Dense clusters (cinematic/game/anime) score
         * high here and get pushed down in retrieval, which stops them acting as everyone's
         * neighbour.
         *
         * Exact all-pairs is O(N²·D) — fine to ~1k tracks (~0.5–1 s for 810×960) but quadratic
         * beyond. At or below [HUB_ANCHOR_SAMPLE] the reference set is the whole corpus, so the
         * result is byte-identical to the historical exact behaviour and to the parity fixtures.
         * Above it, each row is scored against a fixed seeded sample of anchors instead —
         * O(N·[HUB_ANCHOR_SAMPLE]·D). Hubs are near *everything*, so they are near a random sample
         * too, which preserves the relative hubness ordering; the zero-mean keeps the score scale
         * intact so downstream floors and temperature calibration are unaffected. The anchor
         * permutation is seeded so repeated snapshot builds rank identically.
         *
         * The outer loop is intentionally serial. The reference (`Retrieval.computeHubPenalty`,
         * commit 9e978bb) parallelizes it across `Dispatchers.Default` workers, but that requires a
         * suspend seam and `SmartSnapshot.build` is a synchronous factory reached from several
         * non-suspend call sites; making it suspend would ripple through the engine and every test.
         * Each row's penalty is independent and written to its own slot, so the values here are
         * identical to the parallel path — only the wall-clock cost differs.
         */
        internal fun computeHubPenalty(
            centered: FloatArray,
            n: Int,
            anchorLimit: Int = HUB_ANCHOR_SAMPLE,
        ): FloatArray {
            val penalty = FloatArray(n)
            val anchors: IntArray? =
                if (n > anchorLimit) {
                    val perm = IntArray(n) { it }
                    val rng = Random(HUB_ANCHOR_SEED)
                    for (i in n - 1 downTo 1) {
                        val j = rng.nextInt(i + 1)
                        val tmp = perm[i]
                        perm[i] = perm[j]
                        perm[j] = tmp
                    }
                    perm.copyOf(anchorLimit)
                } else {
                    null
                }
            val candidates = anchors?.size ?: n
            val k = HUB_TOPK.coerceAtMost(candidates - 1)
            if (k <= 0) return penalty
            val top = FloatArray(k)
            for (i in 0 until n) {
                top.fill(Float.NEGATIVE_INFINITY)
                val baseI = i * AUDIO_DIM
                for (c in 0 until candidates) {
                    val j = anchors?.get(c) ?: c
                    if (j == i) continue
                    var dot = 0f
                    val baseJ = j * AUDIO_DIM
                    for (d in 0 until AUDIO_DIM) dot += centered[baseI + d] * centered[baseJ + d]
                    var minIdx = 0
                    for (t in 1 until k) if (top[t] < top[minIdx]) minIdx = t
                    if (dot > top[minIdx]) top[minIdx] = dot
                }
                var sum = 0f
                for (t in 0 until k) sum += top[t]
                penalty[i] = sum / k
            }
            var mean = 0f
            for (v in penalty) mean += v
            mean /= n
            for (i in 0 until n) penalty[i] -= mean
            return penalty
        }

        /**
         * Libraries at or under this size take the exact all-pairs path (byte-identical to the
         * historical behaviour and to recorded parity fixtures); larger ones score each row against
         * a seeded anchor sample of this size. Mirrors `Retrieval.HUB_ANCHOR_SAMPLE`.
         */
        internal const val HUB_ANCHOR_SAMPLE = 1024

        /** Seeds the anchor permutation so repeated snapshot builds rank identically. */
        private const val HUB_ANCHOR_SEED = 0x1A7E47L

        /**
         * Centered + renormalised copy of an optional space, computed over the rows that HAVE a
         * vector (so absent rows don't drag the mean) and zeroed elsewhere.
         */
        private inline fun centeredMasked(
            tracks: List<SmartTrack>,
            dim: Int,
            select: (SmartTrack) -> FloatArray?,
        ): Pair<FloatArray?, BooleanArray?> {
            val n = tracks.size
            val has = BooleanArray(n)
            var present = 0
            for (i in 0 until n) {
                val v = select(tracks[i])
                if (v != null && v.size == dim && norm(v) > 1e-9f) {
                    has[i] = true
                    present++
                }
            }
            if (present == 0) return null to null

            val matrix = FloatArray(n * dim)
            for (i in 0 until n) if (has[i]) {
                val v = select(tracks[i])!!
                val scale = 1f / norm(v).coerceAtLeast(1e-12f)
                val base = i * dim
                for (d in 0 until dim) matrix[base + d] = v[d] * scale
            }
            val mean = FloatArray(dim)
            for (i in 0 until n) if (has[i]) {
                val base = i * dim
                for (d in 0 until dim) mean[d] += matrix[base + d]
            }
            for (d in 0 until dim) mean[d] /= present
            for (i in 0 until n) {
                val base = i * dim
                if (!has[i]) {
                    for (d in 0 until dim) matrix[base + d] = 0f
                    continue
                }
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = matrix[base + d] - mean[d]
                    matrix[base + d] = v
                    sumSq += v.toDouble() * v
                }
                val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until dim) matrix[base + d] *= scale
            }
            return matrix to has
        }

        private fun norm(v: FloatArray): Float {
            var sumSq = 0.0
            for (x in v) sumSq += x.toDouble() * x
            return sqrt(sumSq).toFloat()
        }
    }
}
