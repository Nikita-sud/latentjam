/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryLayoutTest {

    @Test
    fun `compute returns one normalized point per track`() {
        val points = LibraryLayout.compute(space(60, 24))
        assertEquals(60, points.size)
        assertEquals(60, points.map { it.trackId }.toSet().size)
        for (point in points) {
            assertTrue(point.x in 0f..1f, "x out of range: ${point.x}")
            assertTrue(point.y in 0f..1f, "y out of range: ${point.y}")
        }
    }

    @Test
    fun `compute is deterministic`() {
        val a = LibraryLayout.compute(space(50, 16))
        val b = LibraryLayout.compute(space(50, 16))
        assertEquals(a, b)
    }

    // Freshness is set equality, not order: the library list is rebuilt on every scan and its
    // order is not stable, so an order-sensitive check would recompute the map constantly.
    @Test
    fun `covers is true only for exactly the stored id set`() {
        val stored = mapOf(
            TrackId("a") to floatArrayOf(0f, 0f),
            TrackId("b") to floatArrayOf(1f, 1f),
        )
        assertTrue(LibraryLayout.covers(stored, listOf(TrackId("b"), TrackId("a"))))
        assertFalse(LibraryLayout.covers(stored, listOf(TrackId("a"), TrackId("b"), TrackId("c"))))
        assertFalse(LibraryLayout.covers(stored, listOf(TrackId("a"))))
    }

    @Test
    fun `compute tolerates a previous layout that covers only some tracks`() {
        val previous = LibraryLayout.compute(space(40, 16))
            .take(20)
            .associate { it.trackId to floatArrayOf(it.x, it.y) }
        val points = LibraryLayout.compute(space(40, 16), previous = previous)
        assertEquals(40, points.size)
    }

    // LayoutAnchor.align is documented to reflect as well as rotate. A single complex
    // scale-rotation (what the brief's draft of applyTransform fit) cannot represent a reflection --
    // it is a holomorphic model and reflection is anti-holomorphic -- so fitting it against reflected
    // data recovers a transform that does not reproduce what align actually computed. Forcing that
    // branch through the public compute() API would depend on where t-SNE's optimizer happens to
    // converge, which a test cannot pin down, so this drives applyTransform directly with a
    // hand-built candidate/reference pair that can only be reconciled by a mirror.
    @Test
    fun `applyTransform reproduces a mirrored alignment instead of guessing`() {
        // An L-shape and its exact x-mirror: LayoutAnchor.align has no rotation-only way to match
        // these, so it must take the mirrored branch.
        val candidate = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 2f, 3f)
        val reference = floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, -2f, 3f)
        val overlap = 4
        val embedded = candidate.copyOf()

        val transformed = LibraryLayout.applyTransform(embedded, candidate, reference, overlap, overlap)
        val alignedOverlap = LayoutAnchor.align(candidate, reference, overlap)

        for (i in transformed.indices) {
            assertEquals(alignedOverlap[i], transformed[i], 1e-3f, "index $i")
        }
    }

    // Tsne.embed's own doc says its caller must unit-normalize rows so squared euclidean distance
    // is monotone in cosine distance. Unit-normalizing before Pca.reduce -- what the brief's draft
    // did -- does not establish that: Pca.reduce is a partial orthogonal projection, and a row's
    // norm generally does not survive a projection, so the *reduced* rows Tsne.embed actually
    // receives would not be unit length. Assert on that array directly rather than trusting that a
    // pre-reduction normalize step was enough.
    @Test
    fun `reduceForEmbedding hands Tsne unit-normalized rows`() {
        val vectorSpace = space(20, 12)
        val rows = vectorSpace.takeRows()
        val reduced = LibraryLayout.reduceForEmbedding(rows, vectorSpace.size, vectorSpace.dim)
        val reducedDim = reduced.size / vectorSpace.size
        for (i in 0 until vectorSpace.size) {
            var normSquared = 0f
            for (d in 0 until reducedDim) {
                val value = reduced[i * reducedDim + d]
                normSquared += value * value
            }
            assertEquals(1f, sqrt(normSquared), 1e-4f, "row $i norm")
        }
    }

    // Finding 1: overlap.size == 2 is the exact tie-break degeneracy -- both the direct and
    // x-mirrored similarity fits reconstruct a 2-point overlap with residual 0, so alignToPrevious
    // must skip alignment entirely rather than let float noise pick one. This pins that boundary
    // directly, since forcing it through compute() would depend on where t-SNE happens to converge.
    @Test
    fun `alignToPrevious leaves the embedding untouched at overlap two`() {
        val ids = listOf(TrackId("a"), TrackId("b"), TrackId("c"))
        val embedded = floatArrayOf(0f, 0f, 1f, 0f, 5f, 5f)
        // Reference points chosen so a naive 2-point fit would recover a real (nonzero) rotation
        // and scale, not a no-op -- if alignment ran here, the output would visibly differ.
        val previous = mapOf(
            TrackId("a") to floatArrayOf(10f, 10f),
            TrackId("b") to floatArrayOf(10f, 20f),
        )
        val result = LibraryLayout.alignToPrevious(ids, embedded, previous, ids.size)
        assertContentEquals(embedded, result)
    }

    @Test
    fun `alignToPrevious aligns once overlap reaches three`() {
        val ids = listOf(TrackId("a"), TrackId("b"), TrackId("c"))
        // The candidate L-shape rotated 90 degrees CCW: align has exactly one non-degenerate way
        // to reconcile these, so three points is enough to break the tie applyTransform needs.
        val embedded = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val reference = floatArrayOf(0f, 0f, 0f, 1f, -1f, 0f)
        val previous = mapOf(
            TrackId("a") to floatArrayOf(reference[0], reference[1]),
            TrackId("b") to floatArrayOf(reference[2], reference[3]),
            TrackId("c") to floatArrayOf(reference[4], reference[5]),
        )
        val result = LibraryLayout.alignToPrevious(ids, embedded, previous, ids.size)
        val expected = LayoutAnchor.align(embedded, reference, ids.size)
        for (i in expected.indices) {
            assertEquals(expected[i], result[i], 1e-3f, "index $i")
        }
        assertNotEquals(embedded.toList(), result.toList())
    }

    // Finding 5: n = 0, 1, 2 each have documented special-cased behaviour that the existing tests
    // (which all use n >= 40) never exercise.
    @Test
    fun `compute at n=0 returns an empty list`() {
        val empty = LibraryVectorSpace(emptyList(), FloatArray(0), dim = 1, source = LibraryVectorSource.AUDIO)
        assertEquals(emptyList(), LibraryLayout.compute(empty))
    }

    @Test
    fun `compute at n=1 pins the single point at the center`() {
        val points = LibraryLayout.compute(space(1, 8))
        assertEquals(1, points.size)
        assertEquals(0.5f, points[0].x)
        assertEquals(0.5f, points[0].y)
    }

    @Test
    fun `compute at n=2 pins both points at the center`() {
        val points = LibraryLayout.compute(space(2, 8))
        assertEquals(2, points.size)
        for (point in points) {
            assertEquals(0.5f, point.x)
            assertEquals(0.5f, point.y)
        }
    }

    // Finding 2: the previous round's fix (unitize after Pca.reduce, to satisfy Tsne.embed's
    // precondition) was correct but incomplete -- it silently dropped the *pre*-PCA unitize that
    // keeps Pca.reduce looking at directions instead of per-track magnitude. This asserts on the
    // array Pca.reduce itself receives, not just on reduceForEmbedding's final output, so a
    // regression that re-drops this step fails here even if the post-PCA unitize still holds.
    @Test
    fun `prepareForPca hands Pca unit-normalized directions`() {
        val vectorSpace = space(20, 12)
        val rows = vectorSpace.takeRows()
        val n = vectorSpace.size
        val dim = vectorSpace.dim
        LibraryLayout.prepareForPca(rows, n, dim)
        for (i in 0 until n) {
            var normSquared = 0f
            for (d in 0 until dim) {
                val value = rows[i * dim + d]
                normSquared += value * value
            }
            assertEquals(1f, sqrt(normSquared), 1e-4f, "row $i norm")
        }
    }

    // Finding 3: an independent per-axis min-max scale is not rotation-invariant, so it can undo
    // the very orientation stability Procrustes alignment exists to provide. A uniform scale must
    // preserve the embedding's aspect ratio instead of stretching it to fill a square.
    @Test
    fun `normalize preserves aspect ratio instead of stretching to fill the box`() {
        val ids = listOf(TrackId("a"), TrackId("b"), TrackId("c"), TrackId("d"))
        // A rectangle 10x wider than it is tall.
        val embedded = floatArrayOf(
            0f, 0f,
            100f, 0f,
            0f, 10f,
            100f, 10f,
        )
        val points = LibraryLayout.normalize(ids, embedded, ids.size)
        for (point in points) {
            assertTrue(point.x in 0f..1f, "x out of range: ${point.x}")
            assertTrue(point.y in 0f..1f, "y out of range: ${point.y}")
        }
        val outSpanX = points.maxOf { it.x } - points.minOf { it.x }
        val outSpanY = points.maxOf { it.y } - points.minOf { it.y }
        // Old (anisotropic) behaviour would make this ratio exactly 1; the input's ratio is 10.
        assertEquals(10f, outSpanX / outSpanY, 0.05f)
        assertEquals(1f, outSpanX, 1e-4f, "the longer axis should fill the box")
    }

    // Finding 4: the existing partial-overlap test only asserts points.size == 40, so it would pass
    // even if compute() ignored `previous` entirely. This proves the warm start actually pulls the
    // recomputed layout closer to the original than an unanchored (cold) recompute of the same
    // slightly-changed library -- discriminating evidence captured in the task report by stubbing
    // compute() to ignore `previous` and watching this fail (RED), then restoring it (GREEN).
    @Test
    fun `compute pulls a warm-started recompute closer to the original than a cold one`() {
        val original = LibraryLayout.compute(space(40, 16))
            .associate { it.trackId to floatArrayOf(it.x, it.y) }

        // A slightly changed library: the same 40 tracks (identical features, since space()'s
        // vectors depend only on the track index, not on n) plus 4 new ones.
        val warmStarted = LibraryLayout.compute(space(44, 16), previous = original)
        val cold = LibraryLayout.compute(space(44, 16))

        val warmDistance = averageDisplacement(original, warmStarted)
        val coldDistance = averageDisplacement(original, cold)

        assertTrue(
            warmDistance < coldDistance * 0.75f,
            "expected the warm start ($warmDistance) to sit measurably closer to the original " +
                "than a cold recompute ($coldDistance)",
        )
    }

    private fun averageDisplacement(
        original: Map<TrackId, FloatArray>,
        recomputed: List<LayoutPoint>,
    ): Float {
        var total = 0f
        var count = 0
        for (point in recomputed) {
            val stored = original[point.trackId] ?: continue
            val dx = point.x - stored[0]
            val dy = point.y - stored[1]
            total += sqrt(dx * dx + dy * dy)
            count++
        }
        check(count > 0) { "no overlap between original and recomputed layouts" }
        return total / count
    }

    private fun space(n: Int, dim: Int): LibraryVectorSpace {
        val ids = (0 until n).map { TrackId("t$it") }
        val audio = HashMap<TrackId, FloatArray>(n)
        val metadata = HashMap<TrackId, FloatArray>(n)
        for (i in 0 until n) {
            val cluster = i % 4
            audio[ids[i]] = FloatArray(dim) { d ->
                (if (d == cluster) 5f else 0f) + ((i * 7 + d * 3) % 5).toFloat() * 0.1f
            }
            metadata[ids[i]] = FloatArray(dim) { d ->
                (if (d == cluster) 4f else 0f) + ((i * 11 + d) % 3).toFloat() * 0.1f
            }
        }
        return requireNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = dim, metadataDim = dim),
        )
    }
}
