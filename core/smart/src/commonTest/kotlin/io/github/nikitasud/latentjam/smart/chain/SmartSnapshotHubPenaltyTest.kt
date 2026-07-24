/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the sampled-anchor hub penalty in [SmartSnapshot.computeHubPenalty]. Libraries at or below
 * [SmartSnapshot.HUB_ANCHOR_SAMPLE] tracks must get the historical exact all-pairs result; larger
 * libraries switch to a seeded anchor sample that must stay deterministic and must preserve the
 * hub-vs-isolated ordering the CSLS correction exists for.
 */
class SmartSnapshotHubPenaltyTest {

    private val dim = SmartSnapshot.AUDIO_DIM

    // 1. At or below the anchor limit the sampled path is the exact path: identical to a
    //    brute-force all-pairs reference.
    @Test
    fun exactPathMatchesBruteForceReference() {
        val n = 60
        val flat = randomUnitRows(n, seed = 7)
        val got = SmartSnapshot.computeHubPenalty(flat, n, anchorLimit = n)
        val reference = bruteForceReference(flat, n, k = 10)
        for (i in 0 until n) assertEquals(reference[i], got[i], 1e-5f)
    }

    // 2. Above the limit: deterministic across calls, zero-mean, and rows inside a dense cluster
    //    score strictly hubbier than isolated rows.
    @Test
    fun sampledPathIsDeterministicZeroMeanAndOrdersHubsAboveIsolates() {
        val nCluster = 200
        val nIsolated = 40
        val n = nCluster + nIsolated
        val rng = Random(11)
        val flat = FloatArray(n * dim)
        // Dense cluster: a shared direction plus small noise — every member is near every other.
        for (i in 0 until nCluster) {
            val base = i * dim
            flat[base] = 1f
            for (d in 1 until dim) flat[base + d] = (rng.nextFloat() - 0.5f) * 0.05f
            normalizeRow(flat, base)
        }
        // Isolated rows: disjoint one-hots — near nothing (cluster lives on axis 0).
        for (i in 0 until nIsolated) {
            val base = (nCluster + i) * dim
            flat[base + 1 + i] = 1f
        }

        val a = SmartSnapshot.computeHubPenalty(flat, n, anchorLimit = 64)
        val b = SmartSnapshot.computeHubPenalty(flat, n, anchorLimit = 64)

        for (i in 0 until n) assertEquals(a[i], b[i], 0f, "seeded sample must be deterministic")
        var mean = 0f
        for (x in a) mean += x
        assertEquals(0f, mean / n, 1e-4f, "zero-mean is part of the contract")
        val worstCluster = (0 until nCluster).minOf { a[it] }
        val bestIsolated = (nCluster until n).maxOf { a[it] }
        assertTrue(
            worstCluster > bestIsolated,
            "every cluster row ($worstCluster) must out-hub every isolated row ($bestIsolated)",
        )
    }

    // 3. Degenerate sizes produce no penalty rather than dividing by zero.
    @Test
    fun singleTrackLibraryGetsZeroPenalty() {
        val flat = randomUnitRows(1, seed = 3)
        val got = SmartSnapshot.computeHubPenalty(flat, 1)
        assertEquals(0f, got[0], 0f)
    }

    private fun randomUnitRows(n: Int, seed: Int): FloatArray {
        val rng = Random(seed)
        val flat = FloatArray(n * dim)
        for (i in 0 until n) {
            val base = i * dim
            for (d in 0 until dim) flat[base + d] = rng.nextFloat() - 0.5f
            normalizeRow(flat, base)
        }
        return flat
    }

    private fun normalizeRow(flat: FloatArray, base: Int) {
        var sumSq = 0.0
        for (d in 0 until dim) sumSq += flat[base + d].toDouble() * flat[base + d]
        val inv = (1.0 / sqrt(sumSq)).toFloat()
        for (d in 0 until dim) flat[base + d] *= inv
    }

    /** The historical exact all-pairs CSLS score, reimplemented independently. */
    private fun bruteForceReference(flat: FloatArray, n: Int, k: Int): FloatArray {
        val r = FloatArray(n)
        for (i in 0 until n) {
            val cos = ArrayList<Float>(n - 1)
            for (j in 0 until n) {
                if (j == i) continue
                var dot = 0f
                for (d in 0 until dim) dot += flat[i * dim + d] * flat[j * dim + d]
                cos.add(dot)
            }
            cos.sortDescending()
            r[i] = cos.take(k).sum() / k
        }
        val mean = r.sum() / n
        for (i in 0 until n) r[i] -= mean
        return r
    }
}
