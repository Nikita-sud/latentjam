/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.text.ArtistAdapter
import io.github.nikitasud.latentjam.smart.text.ArtistAdapterPackedTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/** Opt-in timing probe (PRESS_TIMING=1): the snapshot build's two heavy loops on the JVM. Not a test of anything. */
class PressTimingProbe {
    @Test
    fun time() {
        if (System.getenv("PRESS_TIMING") != "1") return
        val adapter = ArtistAdapter.parse(File("../../androidApp/src/main/assets/ml/artist_adapter.bin").readBytes())!!
        val random = Random(1)
        val x = FloatArray(adapter.width) { random.nextFloat() - 0.5f }
        repeat(200) { adapter.descriptor(x) }
        var t = System.nanoTime()
        repeat(2_000) { adapter.descriptor(x) }
        println("PRESS_TIMING adapter ${(System.nanoTime() - t) / 2_000 / 1000} us/call")
        val eager = ArtistAdapterPackedTest.EagerReference.parse(File("../../androidApp/src/main/assets/ml/artist_adapter.bin").readBytes())!!
        repeat(200) { eager.descriptor(x) }
        t = System.nanoTime()
        repeat(2_000) { eager.descriptor(x) }
        println("PRESS_TIMING eager adapter ${(System.nanoTime() - t) / 2_000 / 1000} us/call")

        val n = 10_000
        val dim = 960
        val m = FloatArray(n * dim) { random.nextFloat() - 0.5f }
        val rows = MatrixCentredRows(m, dim)
        SmartSnapshot.computeHubPenalty(MatrixCentredRows(m.copyOf(2_000 * dim), dim))
        t = System.nanoTime()
        SmartSnapshot.computeHubPenalty(rows)
        println("PRESS_TIMING hub ${(System.nanoTime() - t) / 1_000_000} ms")
        t = System.nanoTime()
        oldHub(m, n, dim)
        println("PRESS_TIMING old hub ${(System.nanoTime() - t) / 1_000_000} ms")
    }

    /** The hub loop before the anchors moved to a store: one row against a dense anchor matrix. */
    private fun oldHub(centered: FloatArray, n: Int, dim: Int): FloatArray {
        val perm = IntArray(n) { it }
        val rng = Random(0x1A7E47L)
        for (i in n - 1 downTo 1) { val j = rng.nextInt(i + 1); val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp }
        val anchors = perm.copyOf(1024)
        val k = 10
        val anchorRows = FloatArray(1024 * dim)
        for (c in 0 until 1024) centered.copyInto(anchorRows, c * dim, anchors[c] * dim, (anchors[c] + 1) * dim)
        val rowI = FloatArray(dim)
        val top = FloatArray(k)
        val penalty = FloatArray(n)
        for (i in 0 until n) {
            top.fill(Float.NEGATIVE_INFINITY)
            centered.copyInto(rowI, 0, i * dim, (i + 1) * dim)
            for (c in 0 until 1024) {
                if (anchors[c] == i) continue
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
        return penalty
    }
}
