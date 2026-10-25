/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.ranker.RankerModel
import kotlin.random.Random
import kotlin.test.Test

/**
 * Perf bench (LJ_PERF=1 only): the personal layer's build cost as history grows — personalHistoryOf (listens, vc21
 * pairs, LW2's credits and pair negatives, the transition maps) over a 10,000-track library, r12a (no listenWeight)
 * vs LW2, at 5,000 and 50,000 plays: median build time of 7 runs and the heap the result keeps (after GC).
 */
class PersonalLayerCostBench {
    @Test
    fun personalLayerCost() {
        if (System.getenv("LJ_PERF") != "1") return
        val library = (0 until 10_000).mapTo(HashSet()) { TrackId("t$it") }
        val lw2 = RankerModel.ListenWeight(negWeight = 1.5, pairMode = "gate2", picksOnly = true)
        for (n in listOf(5_000, 50_000)) {
            val rnd = Random(n)
            var t = 0L
            val events = (0 until n).map {
                val heard = if (rnd.nextDouble() < .25) rnd.nextLong(3_000, 29_000) else rnd.nextLong(60_000, 200_000)
                val start = if (rnd.nextDouble() < .55) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE
                t += heard + 2_000 + (if (rnd.nextDouble() < .05) 3_600_000L else 0L)
                ListenEvent(TrackId("t${rnd.nextInt(10_000)}"), t, heard, 200_000, completed = heard >= 170_000,
                    skipped = heard < 30_000 && heard < 170_000, listenedMs = heard, origin = ListenOrigin(start))
            }
            for ((name, lw) in listOf("r12a" to null, "LW2" to lw2)) {
                val times = ArrayList<Long>()
                var keep: Any? = null
                repeat(7) {
                    val t0 = System.nanoTime()
                    keep = personalHistoryOf(events, library, t + 60_000, emptyMap(), skipPairs = true, unskippedOnly = false, lw = lw) { "" }
                    times += (System.nanoTime() - t0) / 1_000_000
                }
                keep = null
                val rt = Runtime.getRuntime()
                fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
                val before = used()
                keep = personalHistoryOf(events, library, t + 60_000, emptyMap(), skipPairs = true, unskippedOnly = false, lw = lw) { "" }
                val after = used()
                println("PERF personal n=$n arm=$name build_ms_median=${times.sorted()[3]} retained_kb=${(after - before) / 1024} keep=${keep != null}")
            }
        }
    }
}
