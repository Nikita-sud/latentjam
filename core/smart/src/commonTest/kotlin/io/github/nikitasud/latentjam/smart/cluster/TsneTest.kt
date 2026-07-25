/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TsneTest {

    // A mismatched rows array used to fail as an opaque ArrayIndexOutOfBoundsException deep inside
    // affinities(). It should fail loudly and specifically at the API boundary instead.
    @Test
    fun `embed rejects a rows array that does not match n times dim`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Tsne.embed(rows = FloatArray(10), n = 4, dim = 4, seed = 1)
        }
        assertTrue(
            exception.message?.contains("10") == true && exception.message?.contains("16") == true,
            "expected the message to name both the actual (10) and expected (16) sizes, " +
                "was: ${exception.message}",
        )
    }

    // Three well-separated blobs in 8-d must come out as three separated blobs in 2-d. This is the
    // only behavioural claim the map makes: things that belong together land together.
    @Test
    fun `embed separates three planted clusters`() {
        val perCluster = 30
        val dim = 8
        val n = perCluster * 3
        val rows = FloatArray(n * dim)
        var state = 99L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
        }
        for (c in 0 until 3) {
            for (i in 0 until perCluster) {
                val row = c * perCluster + i
                for (d in 0 until dim) {
                    rows[row * dim + d] = (if (d == c) 6f else 0f) + noise() * 0.4f
                }
            }
        }

        val out = Tsne.embed(rows, n, dim, seed = 5)
        assertEquals(n * 2, out.size)

        val within = meanDistance(out) { a, b -> a / perCluster == b / perCluster }
        val between = meanDistance(out) { a, b -> a / perCluster != b / perCluster }
        assertTrue(
            between > within * 2f,
            "clusters did not separate: within=$within between=$between",
        )
    }

    // Regression test for a bug in the perplexity binary search: `repeat(STEPS) { return@repeat }`
    // returns from a single lambda invocation, not the search, so it behaves as `continue` rather
    // than `break`. Point 0 below is placed at the origin with the other n-1 = PERPLEXITY points on
    // orthogonal axes at an equal distance, so point 0 is exactly equidistant from every other
    // point. That makes its conditional entropy independent of beta (see report for the algebra),
    // so the search "converges" on step 0 -- the very first iteration a buggy `return@repeat` would
    // discard. Because entropy never depends on beta here, every later step re-converges too, so
    // under the bug the write is skipped for the entire 60-step search and point 0's affinity row
    // stays all-zero forever, never getting the "self" contribution to its own conditional
    // probabilities that every other (non-degenerate) point gets.
    //
    // The affinity matrix itself is private to Tsne, so this asserts on the embedding, which is the
    // only observable surface: point 0's distance from the rest of the group's centroid, relative to
    // the group's own mean radius. A zeroed row 0 does not fully vanish after symmetrization (the
    // other points still record their own conditional probability of point 0), but it does mean
    // point 0 is only ever pulled toward the group by half of the usual affinity mass, so under the
    // bug it tends to settle farther from the group than the group's own mean radius, driven outward
    // by unopposed repulsion.
    //
    // A *single* seed's ratio is not safe to threshold: the embedding's optimization dynamics
    // (random initial layout, adaptive gains, momentum) make the ratio noisy per draw, and per-seed
    // the fixed and buggy distributions overlap -- one seed even flips the ordering entirely (fixed
    // 1.43 > buggy 0.35 at seed 19; see task-2-report.md for the full 16-seed table). Averaged over
    // many seeds the noise cancels: the fixed code's mean ratio measures ~0.90 and the buggy code's
    // ~1.75 across the seeds below, a gap wide enough to give real margin on both sides of the 1.3
    // threshold, in a way no single draw could.
    @Test
    fun `embed does not strand a point whose perplexity search converges on step 0`() {
        val dim = 20
        val n = dim + 1 // n - 1 == PERPLEXITY (20), so point 0's entropy already matches the
        // target on the search's first step, regardless of beta.
        val scale = 3f
        val rows = FloatArray(n * dim)
        // Point 0 stays at the origin (all zeros). Points 1..n-1 are `scale` out along their own
        // axis: every one of them is exactly `scale` from point 0, but `scale * sqrt(2)` from each
        // other, so only point 0 -- not the rest -- has the degenerate equidistant search.
        for (i in 1 until n) rows[i * dim + (i - 1)] = scale

        // Odd seeds only: Tsne's internal PRNG state is seeded with `seed or 1`, so an even seed
        // collapses onto the odd seed directly below it rather than giving an independent draw.
        val seeds = intArrayOf(1, 3, 5, 7, 9, 11, 13, 17, 19, 23, 29, 31, 101, 2025, 31337, 99991)
        var ratioSum = 0f
        for (seed in seeds) ratioSum += strandedPointRatio(rows, n, dim, seed)
        val meanRatio = ratioSum / seeds.size

        assertTrue(
            meanRatio < 1.3f,
            "the equidistant point drifted to the edge of the map instead of joining the group, " +
                "averaged over ${seeds.size} seeds: meanRatio=$meanRatio (bug leaves its affinity " +
                "row zeroed, so it is only ever pulled toward the others by half the usual affinity " +
                "mass)",
        )
    }

    /**
     * Distance of point 0 from the centroid of points `1 until n`, relative to those points' own
     * mean distance to that centroid -- i.e. how far outside the group's typical radius point 0
     * lands, as a multiple of that radius.
     */
    private fun strandedPointRatio(rows: FloatArray, n: Int, dim: Int, seed: Int): Float {
        val out = Tsne.embed(rows, n, dim, seed = seed)

        var centroidX = 0f
        var centroidY = 0f
        for (i in 1 until n) {
            centroidX += out[i * 2]
            centroidY += out[i * 2 + 1]
        }
        centroidX /= (n - 1)
        centroidY /= (n - 1)

        var meanRadius = 0f
        for (i in 1 until n) {
            val dx = out[i * 2] - centroidX
            val dy = out[i * 2 + 1] - centroidY
            meanRadius += sqrt(dx * dx + dy * dy)
        }
        meanRadius /= (n - 1)

        val dx0 = out[0] - centroidX
        val dy0 = out[1] - centroidY
        val distanceFromGroup = sqrt(dx0 * dx0 + dy0 * dy0)
        return distanceFromGroup / meanRadius
    }

    @Test
    fun `embed is deterministic for a fixed seed`() {
        val n = 40
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 17) % 13).toFloat() - 6f }
        val a = Tsne.embed(rows, n, dim, seed = 2)
        val b = Tsne.embed(rows, n, dim, seed = 2)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    // A warm start must be honoured, not ignored: Task 4's anti-churn machinery depends on it.
    @Test
    fun `embed started from a layout stays nearer it than a cold run`() {
        val n = 45
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 29) % 19).toFloat() - 9f }
        val reference = Tsne.embed(rows, n, dim, seed = 1)
        val warm = Tsne.embed(rows, n, dim, seed = 8, initial = reference)
        val cold = Tsne.embed(rows, n, dim, seed = 8)
        assertTrue(
            drift(warm, reference) < drift(cold, reference),
            "warm start drifted further than a cold run",
        )
    }

    private fun meanDistance(out: FloatArray, pair: (Int, Int) -> Boolean): Float {
        var sum = 0f
        var count = 0
        val n = out.size / 2
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (!pair(i, j)) continue
                val dx = out[i * 2] - out[j * 2]
                val dy = out[i * 2 + 1] - out[j * 2 + 1]
                sum += sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun drift(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum)
    }
}
