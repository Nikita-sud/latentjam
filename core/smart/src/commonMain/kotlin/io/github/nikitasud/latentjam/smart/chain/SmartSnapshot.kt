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
 * A snapshot row: the [SmartTrack] without its vectors, which the snapshot's matrices hold. A snapshot
 * lives as long as the engine's cache, so keeping the input arrays as well would hold the library's
 * vectors once more for that long.
 */
internal class SnapshotTrack(
    val id: TrackId,
    val energy: Float,
    val meta: TrackMeta,
) {
    override fun equals(other: Any?): Boolean = other is SnapshotTrack && other.id == id
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
    val tracks: List<SnapshotTrack>,
    /** Raw audio rows, N × [AUDIO_DIM], as the scorer consumes them. */
    val rawAudio: ScaledRows,
    /** Centered, renormalised audio rows — the space all chain cosines are measured in (recomputed per read). */
    val centeredAudio: CentredRows,
    /** Per-row CSLS hub penalty, zero-meaned across the corpus. */
    val hubPenalty: FloatArray,
    /**
     * Per-row projection onto the corpus mean direction, z-scored — how much of this library
     * sounds like the track. This is precisely the axis [centeredAudio] removes, kept because
     * the removal is not free: measured against real listening it predicts whether a track is
     * kept better than the centered cosine does. See TypicalityTest for the numbers.
     */
    val typicality: FloatArray,
    val centeredText: CentredRows?,
    val hasText: BooleanArray?,
    val centeredDescriptor: CentredRows?,
    val hasDescriptor: BooleanArray?,
    /**
     * Width of the descriptor rows, taken from the data. The recorded research fixtures carry 768,
     * and the artist knowledge pack decodes to 384.
     */
    val descriptorDim: Int,
    /**
     * Unit-normalised but UNCENTERED text rows. Candidate generation ranks this space separately;
     * the learned scorer receives it as optional conditioning. Rows without text read as zeros.
     */
    val rawText: ScaledRows?,
    private val batchDotProducts: BatchDotProducts,
) {
    val size: Int get() = tracks.size

    /** Optional exact-order acceleration for large batches; no matrix copies or retained scratch. */
    fun batchDots(
        matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
        rows: IntArray, count: Int, out: FloatArray,
    ) {
        if (count >= 2_048 && dim >= 16 &&
            batchDotProducts.compute(matrix, dim, query, offset, rows, count, out)
        ) return
        BatchDotProducts.Portable.compute(matrix, dim, query, offset, rows, count, out)
    }

    private val rowById: Map<TrackId, Int> =
        tracks.withIndex().associate { (index, track) -> track.id to index }

    fun rowOf(id: TrackId): Int = rowById[id] ?: -1

    /** Centered-space cosine between two rows. Both rows are unit-norm, so this is a dot product. */
    fun centeredCosine(rowA: Int, rowB: Int): Float {
        if (rowA < 0 || rowB < 0) return 0f
        return centeredAudio.dot(rowA, rowB)
    }

    /**
     * Centered-descriptor cosine between two rows, or `null` when the descriptor space is absent or
     * either row lacks a descriptor — callers then fall back to audio-only (see [Reanchor.fusedCos]).
     */
    fun descriptorCosine(rowA: Int, rowB: Int): Float? {
        val dc = centeredDescriptor ?: return null
        val has = hasDescriptor ?: return null
        if (rowA < 0 || rowB < 0 || !has[rowA] || !has[rowB]) return null
        return dc.dot(rowA, rowB)
    }

    /** [centeredCosine] of [reference] with every row: the same floats, a whole library at a time. */
    fun centeredCosines(reference: Int): FloatArray {
        val out = FloatArray(size)
        if (reference < 0) return out
        centeredAudio.dots(reference, IntArray(size) { it }, size, out, batchDotProducts)
        return out
    }

    /**
     * [descriptorCosine] of [reference] with every row, read where [hasDescriptor] is set (elsewhere it
     * is null); null when it is null for every row.
     */
    fun descriptorCosines(reference: Int): FloatArray? {
        val dc = centeredDescriptor ?: return null
        val has = hasDescriptor ?: return null
        if (reference < 0 || !has[reference]) return null
        val rows = IntArray(size)
        var count = 0
        for (row in 0 until size) if (has[row]) rows[count++] = row
        val out = FloatArray(size)
        dc.dots(reference, rows, count, out, batchDotProducts)
        return out
    }

    /** Centered-text cosine between two rows, or `null` when either row has no text vector. */
    fun textCosine(rowA: Int, rowB: Int): Float? {
        val tc = centeredText ?: return null
        val has = hasText ?: return null
        if (rowA < 0 || rowB < 0 || !has[rowA] || !has[rowB]) return null
        return tc.dot(rowA, rowB)
    }

    companion object {
        const val AUDIO_DIM = 960
        const val TEXT_DIM = 384
        /** Descriptor width when no track carries one; the recorded research fixtures' width. */
        const val DESCRIPTOR_DIM = 768

        /**
         * Rows whose embedding is essentially zero are dropped outright: they sit at the origin of
         * the centered space and therefore look equally similar to everything, which lets them
         * surface as neighbours of any seed.
         */
        const val DEGENERATE_NORM_EPS = 1e-3f

        /** CSLS neighbourhood size for the hub penalty. */
        const val HUB_TOPK = 10

        /**
         * [descriptorOf] reads a row's descriptor; the engine decodes it there, so that one row's decoded
         * descriptor is alive at a time instead of the whole library's. It is called at most twice per
         * row and must return the same values each time.
         */
        fun build(
            tracks: List<SmartTrack>,
            batchDotProducts: BatchDotProducts = BatchDotProducts.Portable,
            descriptorOf: (SmartTrack) -> FloatArray? = SmartTrack::descriptor,
        ): SmartSnapshot? {
            val usable = tracks.filter { it.audio.size == AUDIO_DIM && norm(it.audio) >= DEGENERATE_NORM_EPS }
            val n = usable.size
            if (n == 0) return null

            // Renormalised on the way in. The encoder emits unit vectors already, but persisted
            // and migrated rows drift, and both the scorer and the raw retrieval cosines assume
            // unit norm — a single long row would otherwise rank first against every query. The
            // rows keep the input arrays and apply their scale where they are read (see ScaledRows).
            val raw = ScaledRows(
                Array(n) { usable[it].audio },
                FloatArray(n) { 1f / norm(usable[it].audio).coerceAtLeast(1e-12f) },
                AUDIO_DIM,
            )

            // Centered + renormalised rows, read through the raw rows: the corpus mean and one scale per row.
            val row = FloatArray(AUDIO_DIM)
            val mean = FloatArray(AUDIO_DIM)
            for (i in 0 until n) {
                raw.copyInto(i, row)
                for (d in 0 until AUDIO_DIM) mean[d] += row[d]
            }
            for (d in 0 until AUDIO_DIM) mean[d] /= n
            val centeredScales = FloatArray(n)
            for (i in 0 until n) {
                raw.copyInto(i, row)
                var sumSq = 0.0
                for (d in 0 until AUDIO_DIM) {
                    val v = row[d] - mean[d]
                    sumSq += v.toDouble() * v
                }
                centeredScales[i] = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
            }
            val centered = RecomputedCentredRows(raw, mean, centeredScales, has = null)

            // Text: unit rows kept as the inputs (rawText), centred through them like audio.
            val textHas = BooleanArray(n)
            var anyText = false
            val textVectors = Array(n) { i ->
                val v = usable[i].text
                if (v != null && v.size == TEXT_DIM && norm(v) > 1e-9f) {
                    textHas[i] = true
                    anyText = true
                    v
                } else {
                    null
                }
            }
            val hasText = if (anyText) textHas else null
            val rawText = if (hasText == null) null else ScaledRows(
                textVectors,
                FloatArray(n) { if (textHas[it]) 1f / norm(textVectors[it]!!).coerceAtLeast(1e-12f) else 0f },
                TEXT_DIM,
            )
            val text = rawText?.let { centredThrough(it, textHas) }
            // One width per snapshot: the first usable descriptor sets it, and rows of another width
            // count as absent rather than being truncated.
            val descriptorDim = usable.firstNotNullOfOrNull { track ->
                descriptorOf(track)?.takeIf { it.isNotEmpty() && norm(it) > 1e-9f }?.size
            } ?: DESCRIPTOR_DIM
            val descriptor = centredDistinct(usable, descriptorDim, descriptorOf)

            return SmartSnapshot(
                tracks = usable.map { SnapshotTrack(it.id, it.energy, it.meta) },
                batchDotProducts = batchDotProducts,
                rawAudio = raw,
                centeredAudio = centered,
                hubPenalty = computeHubPenalty(centered),
                typicality = computeTypicality(raw, mean, n),
                centeredText = text,
                hasText = hasText,
                centeredDescriptor = descriptor,
                hasDescriptor = descriptor?.has,
                descriptorDim = descriptorDim,
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
        private fun computeTypicality(raw: ScaledRows, mean: FloatArray, n: Int): FloatArray {
            val out = FloatArray(n)
            val meanNorm = norm(mean)
            if (meanNorm < DEGENERATE_NORM_EPS) return out
            val scale = 1f / meanNorm
            val row = FloatArray(AUDIO_DIM)
            var sum = 0.0
            for (i in 0 until n) {
                raw.copyInto(i, row)
                var dot = 0f
                for (d in 0 until AUDIO_DIM) dot += row[d] * mean[d] * scale
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
            require(centered.size == n * AUDIO_DIM)
            return computeHubPenalty(MatrixCentredRows(centered, AUDIO_DIM), anchorLimit)
        }

        /**
         * [computeHubPenalty] over rows read one at a time: the anchors (at most [HUB_ANCHOR_SAMPLE] rows, 3.9 MB) are
         * centred once into a block and each row against them, the same products in the same order as the matrix.
         */
        internal fun computeHubPenalty(
            centered: CentredRows,
            anchorLimit: Int = HUB_ANCHOR_SAMPLE,
        ): FloatArray {
            val n = centered.size
            val dim = centered.dim
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
            val anchorRows = FloatArray(candidates * dim)
            for (c in 0 until candidates) centered.copyRow(anchors?.get(c) ?: c, anchorRows, c * dim)
            val rowI = FloatArray(dim)
            val top = FloatArray(k)
            for (i in 0 until n) {
                top.fill(Float.NEGATIVE_INFINITY)
                centered.copyRow(i, rowI)
                for (c in 0 until candidates) {
                    val j = anchors?.get(c) ?: c
                    if (j == i) continue
                    var dot = 0f
                    val baseJ = c * dim
                    for (d in 0 until dim) dot += rowI[d] * anchorRows[baseJ + d]
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
         * Centred, renormalised rows of an optional space read through its unit rows [raw]: the mean over the rows
         * that HAVE a vector (so absent rows don't drag it), summed in row order, and one scale per row. Element d
         * of row r is `(raw[r][d] − mean[d]) × scale[r]`, the float the centred matrix held.
         */
        private fun centredThrough(raw: ScaledRows, has: BooleanArray): RecomputedCentredRows {
            val n = raw.size
            val dim = raw.dim
            val row = FloatArray(dim)
            val mean = FloatArray(dim)
            var present = 0
            for (i in 0 until n) if (has[i]) {
                present++
                raw.copyInto(i, row)
                for (d in 0 until dim) mean[d] += row[d]
            }
            for (d in 0 until dim) mean[d] /= present
            val scales = FloatArray(n)
            for (i in 0 until n) {
                if (!has[i]) continue
                raw.copyInto(i, row)
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = row[d] - mean[d]
                    sumSq += v.toDouble() * v
                }
                scales[i] = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
            }
            return RecomputedCentredRows(raw, mean, scales, has)
        }

        /**
         * Centred, renormalised rows of a space whose vectors are not kept anywhere else (descriptors are decoded per
         * row), stored once per distinct unit vector: equal vectors centre to equal rows, so one row serves every song
         * of an artist. The mean still sums every present row in row order, so every value matches the full matrix.
         * Each row is read once.
         */
        private inline fun centredDistinct(
            tracks: List<SmartTrack>,
            dim: Int,
            select: (SmartTrack) -> FloatArray?,
        ): IndexedCentredRows? {
            val n = tracks.size
            val has = BooleanArray(n)
            val index = IntArray(n) { -1 }
            var present = 0
            val distinct = HashMap<UnitRowKey, Int>()
            var unique = FloatArray(0)
            var uniqueCount = 0
            for (i in 0 until n) {
                val v = select(tracks[i])
                if (v == null || v.size != dim) continue
                val length = norm(v)
                if (!(length > 1e-9f)) continue
                has[i] = true
                present++
                val scale = 1f / length.coerceAtLeast(1e-12f)
                val unit = FloatArray(dim) { v[it] * scale }
                val key = UnitRowKey(unit)
                index[i] = distinct.getOrPut(key) {
                    if ((uniqueCount + 1) * dim > unique.size) unique = unique.copyOf(maxOf(dim * 64, unique.size * 2))
                    unit.copyInto(unique, uniqueCount * dim)
                    uniqueCount++
                }
            }
            if (present == 0) return null
            unique = unique.copyOf(uniqueCount * dim)

            val mean = FloatArray(dim)
            for (i in 0 until n) if (has[i]) {
                val base = index[i] * dim
                for (d in 0 until dim) mean[d] += unique[base + d]
            }
            for (d in 0 until dim) mean[d] /= present
            for (u in 0 until uniqueCount) {
                val base = u * dim
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = unique[base + d] - mean[d]
                    unique[base + d] = v
                    sumSq += v.toDouble() * v
                }
                val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until dim) unique[base + d] *= scale
            }
            return IndexedCentredRows(unique, dim, index, has)
        }

        /** A unit row compared by its exact floats (bit patterns), for [centredDistinct]. */
        private class UnitRowKey(val values: FloatArray) {
            private val hash = values.fold(17) { h, x -> 31 * h + x.toRawBits() }
            override fun hashCode(): Int = hash
            override fun equals(other: Any?): Boolean {
                if (other !is UnitRowKey || other.hash != hash || other.values.size != values.size) return false
                for (i in values.indices) if (values[i].toRawBits() != other.values[i].toRawBits()) return false
                return true
            }
        }

        private fun norm(v: FloatArray): Float {
            var sumSq = 0.0
            for (x in v) sumSq += x.toDouble() * x
            return sqrt(sumSq).toFloat()
        }
    }
}

/**
 * Unit-normalised rows kept as the arrays they were built from, each with the scale that normalises
 * it: element `d` of row `r` is `vectors[r][d] * scales[r]`, the very float a matrix of its own would
 * store. Every read repeats that one multiplication, so every value, copy and sum below is the same
 * float as it would be read from such a matrix, without holding the library's vectors a second time
 * beside the index that owns them. A null row reads as zeros.
 *
 * The arrays must not change while these rows are read; the engine builds them from the index's own
 * arrays (see [io.github.nikitasud.latentjam.smart.VectorIndex.storedEntries]).
 */
internal class ScaledRows(
    private val vectors: Array<out FloatArray?>,
    private val scales: FloatArray,
    val dim: Int,
) {
    val size: Int get() = vectors.size

    operator fun get(row: Int, d: Int): Float {
        val vector = vectors[row] ?: return 0f
        return vector[d] * scales[row]
    }

    /** Writes [row] into [out] from [offset]. */
    fun copyInto(row: Int, out: FloatArray, offset: Int = 0) {
        val vector = vectors[row]
        if (vector == null) {
            out.fill(0f, offset, offset + dim)
            return
        }
        val scale = scales[row]
        for (d in 0 until dim) out[offset + d] = vector[d] * scale
    }

    fun row(row: Int): FloatArray = FloatArray(dim).also { copyInto(row, it) }

    /** `Σ row[d] · query[offset + d]`, d ascending. */
    fun dot(row: Int, query: FloatArray, offset: Int = 0): Float {
        val vector = vectors[row] ?: zeros
        val scale = scales[row]
        var dot = 0f
        for (d in 0 until dim) dot += vector[d] * scale * query[offset + d]
        return dot
    }

    /** [batchDots] over these rows: the same order, four rows side by side, so the same floats. */
    fun dots(query: FloatArray, offset: Int, rows: IntArray, count: Int, out: FloatArray) {
        var index = 0
        while (index + 4 <= count) {
            val row0 = rows[index]
            val row1 = rows[index + 1]
            val row2 = rows[index + 2]
            val row3 = rows[index + 3]
            val v0 = vectors[row0] ?: zeros
            val v1 = vectors[row1] ?: zeros
            val v2 = vectors[row2] ?: zeros
            val v3 = vectors[row3] ?: zeros
            val s0 = scales[row0]
            val s1 = scales[row1]
            val s2 = scales[row2]
            val s3 = scales[row3]
            var dot0 = 0f
            var dot1 = 0f
            var dot2 = 0f
            var dot3 = 0f
            for (d in 0 until dim) {
                val q = query[offset + d]
                dot0 += v0[d] * s0 * q
                dot1 += v1[d] * s1 * q
                dot2 += v2[d] * s2 * q
                dot3 += v3[d] * s3 * q
            }
            out[row0] = dot0
            out[row1] = dot1
            out[row2] = dot2
            out[row3] = dot3
            index += 4
        }
        while (index < count) {
            out[rows[index]] = dot(rows[index], query, offset)
            index++
        }
    }

    private val zeros by lazy { FloatArray(dim) }
}

/**
 * `out[row] = Σ matrix[row · dim + d] · query[offset + d]`, d ascending, for the first [count] of [rows].
 * Each sum runs in the same order as a loop over one row, so it is the same float; four rows go at a time
 * so that their sums, independent of each other, run side by side instead of each addition waiting for
 * the one before.
 */
internal fun batchDots(
    matrix: FloatArray,
    dim: Int,
    query: FloatArray,
    offset: Int,
    rows: IntArray,
    count: Int,
    out: FloatArray,
) {
    var index = 0
    while (index + 4 <= count) {
        val base0 = rows[index] * dim
        val base1 = rows[index + 1] * dim
        val base2 = rows[index + 2] * dim
        val base3 = rows[index + 3] * dim
        var dot0 = 0f
        var dot1 = 0f
        var dot2 = 0f
        var dot3 = 0f
        for (d in 0 until dim) {
            val q = query[offset + d]
            dot0 += matrix[base0 + d] * q
            dot1 += matrix[base1 + d] * q
            dot2 += matrix[base2 + d] * q
            dot3 += matrix[base3 + d] * q
        }
        out[rows[index]] = dot0
        out[rows[index + 1]] = dot1
        out[rows[index + 2]] = dot2
        out[rows[index + 3]] = dot3
        index += 4
    }
    while (index < count) {
        val base = rows[index] * dim
        var dot = 0f
        for (d in 0 until dim) dot += matrix[base + d] * query[offset + d]
        out[rows[index]] = dot
        index++
    }
}

/** Optional platform kernel. False leaves [out] untouched and requests the portable implementation. */
internal fun interface BatchDotProducts {
    fun compute(
        matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
        rows: IntArray, count: Int, out: FloatArray,
    ): Boolean

    companion object {
        val Portable = BatchDotProducts { matrix, dim, query, offset, rows, count, out ->
            batchDots(matrix, dim, query, offset, rows, count, out)
            true
        }
    }
}
