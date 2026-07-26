/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LayoutAnchorTest {

    // A layout that is the reference rotated by 40 degrees must come back essentially on top of it.
    @Test
    fun `align undoes a rotation`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 13) % 17).toFloat() - 8f }
        val angle = 0.698f
        val rotated = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = reference[i * 2]
            val y = reference[i * 2 + 1]
            rotated[i * 2] = x * cos(angle) - y * sin(angle)
            rotated[i * 2 + 1] = x * sin(angle) + y * cos(angle)
        }
        val aligned = LayoutAnchor.align(rotated, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    // Mirroring is the failure that matters most: it leaves every learned location wrong while
    // every fidelity metric stays identical.
    @Test
    fun `align undoes a reflection`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f }
        val mirrored = FloatArray(n * 2)
        for (i in 0 until n) {
            mirrored[i * 2] = -reference[i * 2]
            mirrored[i * 2 + 1] = reference[i * 2 + 1]
        }
        val aligned = LayoutAnchor.align(mirrored, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    // The reflection test above has zero rotation composed on top (s == 0 throughout the
    // implementation's mirrored branch), so it cannot catch a bug in how the mirrored branch's
    // (c, s) interact with the `ax` flip in the output loop. Compose a genuine rotation with the
    // mirror so `mirrored == true` and `s != 0` at the same time.
    @Test
    fun `align undoes a reflection composed with a rotation`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 13) % 17).toFloat() - 8f }
        val angle = 0.4f
        val mirroredRotated = FloatArray(n * 2)
        for (i in 0 until n) {
            val mx = -reference[i * 2]
            val y = reference[i * 2 + 1]
            mirroredRotated[i * 2] = mx * cos(angle) - y * sin(angle)
            mirroredRotated[i * 2 + 1] = mx * sin(angle) + y * cos(angle)
        }
        val aligned = LayoutAnchor.align(mirroredRotated, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    // The three tests above all build `candidate` as an exact rigid transform of `reference` about
    // the coordinate origin, so the correct answer happens to be bit-close to `reference` itself --
    // a stub `return reference.copyOf()` would pass every one of them. Guard against that here.
    // `align` composes a translation with a rotation/reflection, both isometries, so its output must
    // reproduce *candidate*'s own pairwise distances exactly, no matter what `reference` looks like.
    // Make `candidate` a non-rigid deformation of `reference` (fixed per-point jitter, not a
    // rotation or reflection), so its shape differs from reference's and a `reference.copyOf()`
    // stub is caught: it would carry reference's distances, not candidate's.
    @Test
    fun `align preserves candidate's own shape so it cannot be a copy of reference`() {
        val n = 12
        val reference = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f }
        // Fixed, hand-written per-point jitter -- deterministic, and not a rigid transform of
        // `reference` (each point moves by a different amount in a different direction).
        val jitter = floatArrayOf(
            0.3f, -0.2f, -0.5f, 0.4f, 0.1f, 0.6f, -0.3f, -0.1f, 0.2f, -0.4f,
            0.5f, 0.0f, -0.2f, 0.3f, 0.4f, -0.5f, -0.1f, 0.2f, 0.3f, -0.3f,
            -0.4f, 0.1f, 0.2f, -0.2f,
        )
        val candidate = FloatArray(n * 2) { reference[it] + jitter[it] }

        val aligned = LayoutAnchor.align(candidate, reference, n)

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val expected = distance(candidate, i, j)
                val actual = distance(aligned, i, j)
                assertTrue(
                    abs(actual - expected) < 1e-3f,
                    "pairwise distance ($i,$j) changed under alignment: candidate=$expected aligned=$actual",
                )
            }
        }

        // Sanity check that this test is actually discriminating: candidate's shape must differ
        // from reference's, otherwise the check above wouldn't catch a `reference.copyOf()` stub.
        var sawDifference = false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (abs(distance(candidate, i, j) - distance(reference, i, j)) > 1e-3f) {
                    sawDifference = true
                }
            }
        }
        assertTrue(sawDifference, "candidate's shape must differ from reference's for this test to be meaningful")
    }

    // n < 2: there is nothing to align against, so `align` must hand back a defensive copy of
    // `candidate` untouched.
    @Test
    fun `align returns a defensive copy of candidate when n is less than 2`() {
        val oneCandidate = floatArrayOf(3f, 4f)
        val oneReference = floatArrayOf(9f, -2f)
        val oneAligned = LayoutAnchor.align(oneCandidate, oneReference, 1)
        assertContentEquals(oneCandidate, oneAligned)
        assertNotSame(oneCandidate, oneAligned, "must be a copy, not the same array instance")
        assertNoNaN(oneAligned)

        val zeroCandidate = FloatArray(0)
        val zeroReference = FloatArray(0)
        val zeroAligned = LayoutAnchor.align(zeroCandidate, zeroReference, 0)
        assertContentEquals(zeroCandidate, zeroAligned)
        assertNotSame(zeroCandidate, zeroAligned, "must be a copy, not the same array instance")
    }

    // candidateNorm < 1e-12f: every candidate point coincides with the candidate centroid (the
    // candidate cloud is collapsed to a single point), so there is no shape to rotate onto the
    // reference.
    @Test
    fun `align returns a copy of candidate when the candidate cloud is collapsed to a point`() {
        val n = 5
        val candidate = FloatArray(n * 2) { if (it % 2 == 0) 2f else 3f } // every point is (2, 3)
        val reference = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f } // ordinary spread-out layout

        val aligned = LayoutAnchor.align(candidate, reference, n)

        assertContentEquals(candidate, aligned)
        assertNotSame(candidate, aligned, "must be a copy, not the same array instance")
        assertNoNaN(aligned)
    }

    // scale < 1e-12f: the candidate has real spread, but the reference cloud is collapsed to a
    // single point, so the cross-covariance is exactly zero and neither the rotation branch nor the
    // mirrored branch has any achievable alignment score.
    @Test
    fun `align returns a copy of candidate when the reference cloud is collapsed to a point`() {
        val n = 5
        val candidate = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f } // ordinary spread-out layout
        val reference = FloatArray(n * 2) { if (it % 2 == 0) -1f else 4f } // every point is (-1, 4)

        val aligned = LayoutAnchor.align(candidate, reference, n)

        assertContentEquals(candidate, aligned)
        assertNotSame(candidate, aligned, "must be a copy, not the same array instance")
        assertNoNaN(aligned)
    }

    private fun rmse(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum / a.size)
    }

    private fun distance(points: FloatArray, i: Int, j: Int): Float {
        val dx = points[i * 2] - points[j * 2]
        val dy = points[i * 2 + 1] - points[j * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun assertNoNaN(points: FloatArray) {
        for (v in points) {
            assertFalse(v.isNaN(), "output contains NaN")
        }
    }
}
