/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Round 2 device rows (docs/map-cost-research-2026-10-25.md): synthetic 10,000-track library (seed 23); the old state
 * is built on 99 % (untimed), then the full library is updated as today (`-e mapMode today`) or by
 * MapRefreshEngine.update with `-e mapRouter R` (`refresh`), router included. Times the update, samples used Java
 * heap every 10 ms; `-e mapCancelAfterMs T` cancels at T ms and logs latency from the deadline. Appends to
 * files/map-round2.log. Copy MapRefreshEngine.kt next to this file to build it.
 */
class MapRound2DeviceTest {
    @Test
    fun round2() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapRound2") == "1")
        val n = args.getString("mapRows")?.toInt() ?: 10_000
        val mode = args.getString("mapMode") ?: "refresh"
        val router = MapRefreshEngine.Router.valueOf(args.getString("mapRouter") ?: "UNION")
        val cancelAfterMs = args.getString("mapCancelAfterMs")?.toLong()
        val scenario = args.getString("mapScenario") ?: "add1"
        val priority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try { run(n, mode, router, cancelAfterMs, scenario) } finally { Process.setThreadPriority(priority) }
    }

    private fun run(n: Int, mode: String, router: MapRefreshEngine.Router, cancelAfterMs: Long?, scenario: String) {
        val (ids, rows) = fixture(n, 23)
        // add1: every 100th track is new. change1: every 100th track's old vector was half its own, half another's.
        val old = if (scenario == "change1") (0 until n).toList() else (0 until n).filter { it % 100 != 0 }
        val oldRows = FloatArray(old.size * DIM)
        old.forEachIndexed { slot, row -> rows.copyInto(oldRows, slot * DIM, row * DIM, (row + 1) * DIM) }
        if (scenario == "change1") for (i in 0 until n step 100) {
            val other = (i * 7919 + 13) % n
            var norm = 0.0
            for (d in 0 until DIM) { val v = 0.5f * rows[i * DIM + d] + 0.5f * rows[other * DIM + d]; oldRows[i * DIM + d] = v; norm += v.toDouble() * v }
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (d in 0 until DIM) oldRows[i * DIM + d] *= inv
        }
        val state = MapRefreshEngine.fullBuild(old.map(ids::get), oldRows, DIM, null).state
        val runtime = Runtime.getRuntime()
        System.gc(); System.runFinalization(); System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (sampling.get()) { peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; Thread.sleep(10) } }
        val start = System.nanoTime()
        val due = cancelAfterMs?.let { start + it * 1_000_000 } ?: Long.MAX_VALUE
        val lastPoll = AtomicLong(start)
        val longestGap = AtomicLong(0)
        val isActive = {
            val now = System.nanoTime()
            val previous = lastPoll.getAndSet(now)
            if (previous != 0L) longestGap.accumulateAndGet(now - previous, ::maxOf)
            now < due
        }
        var outcome: String
        var detail = ""
        try {
            if (mode == "today") {
                // Production's work only: no research state by-product, the fixture matrix consumed in place.
                MapRefreshEngine.fullBuild(ids, rows, DIM, state, isActive, withState = false)
                outcome = "completed"
            } else {
                val r = MapRefreshEngine.update(ids, rows, DIM, state, MapRefreshEngine.Config(router, buildState = false), isActive)
                outcome = if (r.refreshed) "refreshed" else "full:${r.reason}"
                detail = "phases=" + r.phases.entries.joinToString(",") { "${it.key}:${"%.1f".format(it.value)}" }
            }
        } catch (_: MapRefreshEngine.Cancelled) { outcome = "cancelled" } catch (e: Throwable) { outcome = "stopped:${e::class.simpleName}" }
        val end = System.nanoTime()
        sampling.set(false); sampler.join()
        val latency = if (due != Long.MAX_VALUE) "%.1f".format((end - due) / 1e6) else "n/a"
        val stored = if (mode == "today") 0L else state.productionBytes()
        val info = "LJ_MAP_R2 mode=$mode scenario=$scenario router=$router n=$n outcome=$outcome stored_state_bytes=$stored total_ms=${(end - start) / 1e6} cancel_after_ms=${cancelAfterMs ?: "none"} " +
            "latency_from_deadline_ms=$latency max_poll_gap_ms=${longestGap.get() / 1e6} heap_peak_delta_bytes=${peak.get() - usedBefore} " +
            "heap_limit_bytes=${runtime.maxMemory()} $detail"
        Log.i("LJMapR2", info)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "map-round2.log").appendText(info + "\n")
    }

    /** Identical generator to the host MapScaleReplay fixture. */
    private fun fixture(n: Int, seed: Int): Pair<List<TrackId>, FloatArray> {
        val random = Random(seed)
        fun gaussian(): Float {
            var u = random.nextDouble()
            while (u <= 1e-12) u = random.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * random.nextDouble())).toFloat()
        }
        fun unit(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            for (i in vector.indices) vector[i] /= norm
            return vector
        }
        val families = List(8) { unit(FloatArray(DIM) { gaussian() }) }
        val styles = List(48) { style ->
            val family = families[style / 6]
            unit(FloatArray(DIM) { family[it] + 0.9f * gaussian() / sqrt(DIM.toFloat()) * 2f })
        }
        val weights = DoubleArray(48) { 1.0 / (1 + it % 12) }
        val total = weights.sum()
        val rows = FloatArray(n * DIM)
        for (i in 0 until n) {
            val scattered = random.nextDouble() < 0.15
            var pick = random.nextDouble() * total
            var style = 0
            while (pick > weights[style]) { pick -= weights[style]; style++ }
            val spread = if (scattered) 0f else 0.55f + 0.3f * (style % 4) / 3f
            val base = if (scattered) FloatArray(DIM) else styles[style]
            val row = FloatArray(DIM) { base[it] + (if (scattered) 1f else spread) * gaussian() / sqrt(DIM.toFloat()) * 2f }
            unit(row).copyInto(rows, i * DIM)
        }
        return List(n) { TrackId("scale-$it") } to rows
    }

    private companion object { const val DIM = 1344 }
}
