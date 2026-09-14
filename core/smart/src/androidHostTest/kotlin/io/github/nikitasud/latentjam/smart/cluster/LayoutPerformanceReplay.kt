/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test

/** Opt-in diagnostic: MAP_LAYOUT_BENCHMARK=1, then run this host test with --rerun-tasks. */
class LayoutPerformanceReplay {
    @Test
    fun `measure synthetic layout cold compute warm compute and cache validation`() {
        if (System.getenv("MAP_LAYOUT_BENCHMARK") != "1") return
        repeat(3) { LibraryLayout.compute(fixture(180)) }
        for (n in listOf(400, 873, 2400)) {
            val cold = DoubleArray(3)
            var points = emptyList<LayoutPoint>()
            repeat(3) { run ->
                val space = fixture(n)
                val start = System.nanoTime()
                points = LibraryLayout.compute(space)
                cold[run] = (System.nanoTime() - start) / 1e6
            }
            val previous = points.associate { it.trackId to floatArrayOf(it.x, it.y) }
            val warmSpace = fixture(n)
            var start = System.nanoTime()
            LibraryLayout.compute(warmSpace, previous)
            val warm = (System.nanoTime() - start) / 1e6
            val stored = StoredLibraryLayout(previous, warmSpace.fingerprint)
            start = System.nanoTime()
            repeat(1000) { check(LibraryLayout.covers(stored, warmSpace.trackIds, warmSpace.fingerprint)) }
            val cache = (System.nanoTime() - start) / 1e6 / 1000
            println("layout n=$n dim=1344 coldMedianMs=${cold.sorted()[1]} warmComputeMs=$warm cacheCheckMs=$cache")
        }
    }

    internal fun fixture(n: Int): LibraryVectorSpace {
        val dim = 1344
        var state = 99871L
        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            var norm = 0f
            for (d in 0 until dim) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val noise = ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
                val value = noise + if (d % 12 == i % 12) 0.25f else 0f
                rows[i * dim + d] = value
                norm += value * value
            }
            norm = sqrt(norm)
            for (d in 0 until dim) rows[i * dim + d] /= norm
        }
        return LibraryVectorSpace(
            List(n) { TrackId("synthetic-$it") }, rows, dim,
            LibraryVectorSource.AUDIO_AND_METADATA,
        )
    }
}
