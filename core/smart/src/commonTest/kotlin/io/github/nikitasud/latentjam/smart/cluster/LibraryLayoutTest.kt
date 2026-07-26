/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
