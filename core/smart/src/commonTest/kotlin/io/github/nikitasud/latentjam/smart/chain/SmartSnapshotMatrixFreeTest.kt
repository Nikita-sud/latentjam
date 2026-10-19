/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The snapshot stopped holding its centred matrices (CentredRows): every float it reads must still be the float those
 * matrices held. The reference below is the dense build as it was, kept verbatim; the library is synthetic, with
 * songs without text, songs sharing one descriptor and songs without one, and more rows than the platform-kernel
 * threshold so the blocked scans run.
 */
class SmartSnapshotMatrixFreeTest {

    private val n = 2_100
    private val random = Random(20261020)

    private fun vector(dim: Int) = FloatArray(dim) { (random.nextFloat() - 0.5f) * (1f + random.nextFloat()) }

    private val tracks: List<SmartTrack> = run {
        val artistDescriptors = List(40) { vector(SmartSnapshot.TEXT_DIM) }
        List(n) { i ->
            SmartTrack(
                id = TrackId("t$i"),
                audio = vector(SmartSnapshot.AUDIO_DIM),
                text = if (i % 7 == 3) null else vector(SmartSnapshot.TEXT_DIM),
                descriptor = when {
                    i % 11 == 5 -> null
                    i % 3 == 0 -> artistDescriptors[i % artistDescriptors.size]
                    else -> vector(SmartSnapshot.TEXT_DIM)
                },
                meta = TrackMeta(null, null, null, null, null),
            )
        }
    }

    @Test
    fun everyCentredFloatHubPenaltyAndScanMatchesTheDenseBuild() {
        val snapshot = assertNotNull(SmartSnapshot.build(tracks))
        val reference = DenseReference(tracks)
        assertEquals(n, snapshot.size)

        fun sameRows(label: String, rows: CentredRows?, matrix: FloatArray?, dim: Int) {
            if (matrix == null) {
                assertEquals(null, rows, label)
                return
            }
            val actual = assertNotNull(rows, label)
            val row = FloatArray(dim)
            var mismatches = 0
            for (r in 0 until n) {
                actual.copyRow(r, row)
                for (d in 0 until dim) if (row[d].toRawBits() != matrix[r * dim + d].toRawBits()) mismatches++
            }
            assertEquals(0, mismatches, "$label: centred floats differ from the dense build")
        }
        sameRows("audio", snapshot.centeredAudio, reference.audio, SmartSnapshot.AUDIO_DIM)
        sameRows("text", snapshot.centeredText, reference.text, SmartSnapshot.TEXT_DIM)
        sameRows("descriptor", snapshot.centeredDescriptor, reference.descriptor, snapshot.descriptorDim)
        assertEquals(reference.hasText?.toList(), snapshot.hasText?.toList())
        assertEquals(reference.hasDescriptor?.toList(), snapshot.hasDescriptor?.toList())
        assertEquals(
            reference.hubPenalty.map(Float::toRawBits),
            snapshot.hubPenalty.map(Float::toRawBits),
            "hub penalty",
        )

        for (ref in listOf(0, 3, 5, 1_000, n - 1)) {
            val expectedAudio = FloatArray(n)
            batchDots(reference.audio, SmartSnapshot.AUDIO_DIM, reference.audio, ref * SmartSnapshot.AUDIO_DIM, IntArray(n) { it }, n, expectedAudio)
            assertEquals(expectedAudio.map(Float::toRawBits), snapshot.centeredCosines(ref).map(Float::toRawBits), "audio scan from $ref")
            val actualDescriptor = snapshot.descriptorCosines(ref)
            val has = reference.hasDescriptor!!
            if (!has[ref]) {
                assertEquals(null, actualDescriptor)
            } else {
                val rows = (0 until n).filter { has[it] }.toIntArray()
                val expected = FloatArray(n)
                batchDots(reference.descriptor!!, snapshot.descriptorDim, reference.descriptor, ref * snapshot.descriptorDim, rows, rows.size, expected)
                assertEquals(expected.map(Float::toRawBits), assertNotNull(actualDescriptor).map(Float::toRawBits), "descriptor scan from $ref")
            }
            for (other in listOf(1, 2, 7, 10, 999, n - 2)) {
                assertEquals(dense(reference.audio, SmartSnapshot.AUDIO_DIM, ref, other).toRawBits(), snapshot.centeredCosine(ref, other).toRawBits())
                val textExpected = if (reference.hasText!![ref] && reference.hasText[other]) dense(reference.text!!, SmartSnapshot.TEXT_DIM, ref, other) else null
                assertEquals(textExpected?.toRawBits(), snapshot.textCosine(ref, other)?.toRawBits())
                val query = FloatArray(SmartSnapshot.AUDIO_DIM) { random.nextFloat() }
                var expectedWith = 0f
                for (d in 0 until SmartSnapshot.AUDIO_DIM) expectedWith += query[d] * reference.audio[other * SmartSnapshot.AUDIO_DIM + d]
                assertEquals(expectedWith.toRawBits(), snapshot.centeredAudio.dotWith(query, other).toRawBits())
            }
        }
    }

    @Test
    fun aSnapshotHoldsOneDescriptorRowPerDistinctDescriptor() {
        val snapshot = assertNotNull(SmartSnapshot.build(tracks))
        val rows = assertNotNull(snapshot.centeredDescriptor) as IndexedCentredRows
        val distinct = tracks.mapNotNull { it.descriptor?.toList() }.toSet().size
        assertTrue(distinct < n, "the fixture shares descriptors")
        assertEquals(distinct, rows.storedRows)
    }

    private fun dense(matrix: FloatArray, dim: Int, a: Int, b: Int): Float {
        var dot = 0f
        for (d in 0 until dim) dot += matrix[a * dim + d] * matrix[b * dim + d]
        return dot
    }

    /** SmartSnapshot.build's centred matrices and hub penalty as they were built before rows were read through. */
    private class DenseReference(tracks: List<SmartTrack>) {
        val audio: FloatArray
        val hubPenalty: FloatArray
        val text: FloatArray?
        val hasText: BooleanArray?
        val descriptor: FloatArray?
        val hasDescriptor: BooleanArray?

        init {
            val n = tracks.size
            val dim = SmartSnapshot.AUDIO_DIM
            val raw = ScaledRows(Array(n) { tracks[it].audio }, FloatArray(n) { 1f / norm(tracks[it].audio).coerceAtLeast(1e-12f) }, dim)
            val row = FloatArray(dim)
            val mean = FloatArray(dim)
            for (i in 0 until n) {
                raw.copyInto(i, row)
                for (d in 0 until dim) mean[d] += row[d]
            }
            for (d in 0 until dim) mean[d] /= n
            val centered = FloatArray(n * dim)
            for (i in 0 until n) {
                raw.copyInto(i, row)
                val base = i * dim
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = row[d] - mean[d]
                    centered[base + d] = v
                    sumSq += v.toDouble() * v
                }
                val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until dim) centered[base + d] *= scale
            }
            audio = centered
            hubPenalty = hub(centered, n)
            centeredMasked(tracks, SmartSnapshot.TEXT_DIM) { it.text }.let { (m, h) -> text = m; hasText = h }
            centeredMasked(tracks, SmartSnapshot.TEXT_DIM) { it.descriptor }.let { (m, h) -> descriptor = m; hasDescriptor = h }
        }

        private fun hub(centered: FloatArray, n: Int): FloatArray {
            val dim = SmartSnapshot.AUDIO_DIM
            val penalty = FloatArray(n)
            val limit = SmartSnapshot.HUB_ANCHOR_SAMPLE
            val anchors: IntArray? = if (n > limit) {
                val perm = IntArray(n) { it }
                val rng = Random(0x1A7E47L)
                for (i in n - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
                }
                perm.copyOf(limit)
            } else null
            val candidates = anchors?.size ?: n
            val k = SmartSnapshot.HUB_TOPK.coerceAtMost(candidates - 1)
            val top = FloatArray(k)
            for (i in 0 until n) {
                top.fill(Float.NEGATIVE_INFINITY)
                for (c in 0 until candidates) {
                    val j = anchors?.get(c) ?: c
                    if (j == i) continue
                    var dot = 0f
                    for (d in 0 until dim) dot += centered[i * dim + d] * centered[j * dim + d]
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

        private fun centeredMasked(tracks: List<SmartTrack>, dim: Int, select: (SmartTrack) -> FloatArray?): Pair<FloatArray?, BooleanArray?> {
            val n = tracks.size
            val has = BooleanArray(n)
            var present = 0
            var allocated: FloatArray? = null
            for (i in 0 until n) {
                val v = select(tracks[i])
                if (v == null || v.size != dim) continue
                val length = norm(v)
                if (!(length > 1e-9f)) continue
                val rows = allocated ?: FloatArray(n * dim).also { allocated = it }
                has[i] = true
                present++
                val scale = 1f / length.coerceAtLeast(1e-12f)
                for (d in 0 until dim) rows[i * dim + d] = v[d] * scale
            }
            val matrix = allocated ?: return null to null
            val mean = FloatArray(dim)
            for (i in 0 until n) if (has[i]) for (d in 0 until dim) mean[d] += matrix[i * dim + d]
            for (d in 0 until dim) mean[d] /= present
            for (i in 0 until n) {
                if (!has[i]) continue
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = matrix[i * dim + d] - mean[d]
                    matrix[i * dim + d] = v
                    sumSq += v.toDouble() * v
                }
                val scale = 1f / sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until dim) matrix[i * dim + d] *= scale
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
