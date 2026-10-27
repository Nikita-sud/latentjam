/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.TrackDescriptor
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
 * Round 3 device rows (docs/map-cost-research-2026-10-25.md, "Round 3"): synthetic 10,000-track library (seed 23);
 * the old state is built on 99 % (untimed). `-e mapMode today`: today's warm layout (production work only).
 * `refresh`: MapRefreshEngine.update with no router plus the geometry stale hint over the patched lists.
 * `rebuild`: the deferred full rebuild, today's layout plus LibraryWorlds.discover over the full library.
 * Times the run, samples used Java heap every 10 ms; `-e mapCancelAfterMs T` cancels at T ms and logs latency from
 * the deadline. Appends to files/map-round3.log. Copy MapRefreshEngine.kt next to this file to build it.
 */
class MapRound3DeviceTest {
    @Test
    fun round3() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapRound3") == "1")
        val n = args.getString("mapRows")?.toInt() ?: 10_000
        val mode = args.getString("mapMode") ?: "refresh"
        val router = MapRefreshEngine.Router.NONE
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
        // Untimed setup, memory-lean (post-freeze harness fix): the state from a consumed copy, then the layout
        // consumes the old matrix itself.
        val oldIds = old.map(ids::get)
        var raw = 0f
        val stateRows = oldRows.copyOf()
        val recording = MapEmbeddingBackend { r, rn, rd, initial, active ->
            ApproximateMapEmbedding.embed(r, rn, rd, initial, active).also { if (it is MapEmbeddingResult.Complete) raw = MapRefreshEngine.spread(it.coordinates) }
        }
        val points = LibraryLayout.computeWith(LibraryVectorSpace(oldIds, oldRows, DIM, LibraryVectorSource.AUDIO), emptyMap(), { true }, Long.MAX_VALUE, recording)
        val state = MapRefreshEngine.stateOfLean(oldIds, stateRows, DIM, points, raw)
        val runtime = Runtime.getRuntime()
        System.gc(); System.runFinalization(); System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (sampling.get()) { peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; Thread.sleep(10) } }
        val budgetBefore = availableWorkBytes()
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
            when (mode) {
                "today" -> {
                    // Production's work only: no research state by-product, the fixture matrix consumed in place.
                    MapRefreshEngine.fullBuild(ids, rows, DIM, state, isActive, withState = false)
                    outcome = "completed"
                }
                "rebuild" -> {
                    // Deferred full rebuild: regions (under the app's work budget, measured before), then the layout,
                    // then the refresh state the next refreshes need (an upper bound: a second PCA and 60-NN).
                    // Warm from the 99 % state, a timing proxy for "warm from the waiting map".
                    val regionStart = System.nanoTime()
                    val worlds = LibraryWorlds.discover(ids.map { TrackDescriptor(it) }, LibraryVectorSpace(ids, rows.copyOf(), DIM, LibraryVectorSource.AUDIO), emptyMap(), isActive, budgetBefore)
                    val regionMs = (System.nanoTime() - regionStart) / 1e6
                    val leanStart = System.nanoTime()
                    MapRefreshEngine.stateOfLean(ids, rows.copyOf(), DIM, emptyList(), 1f, isActive)
                    val stateMs = (System.nanoTime() - leanStart) / 1e6
                    val built = MapRefreshEngine.fullBuild(ids, rows, DIM, state, isActive, withState = false)
                    val regionPath = if (MapMemoryBudget.regionWorkBytes(n, DIM) <= budgetBefore) "average-linkage" else "k-means-fallback"
                    outcome = "completed regions=${worlds.size} region_path=$regionPath"
                    detail = "regions_ms=$regionMs state_ms=$stateMs layout_ms=${built.ms} " +
                        "budget_projection_streamed_bytes=${MapMemoryBudget.projectionStreamedBytes(n, DIM, false)} budget_region_work_bytes=${MapMemoryBudget.regionWorkBytes(n, DIM)} available_work_bytes_before=$budgetBefore"
                }
                else -> {
                    val r = MapRefreshEngine.update(ids, rows, DIM, state, MapRefreshEngine.Config(router, sizeLimit = 1.0, placedShareLimit = 1.0, buildState = false), isActive)
                    val nb = r.neighbours!!
                    val flagged = BooleanArray(n) { state.hashes[ids[it]] == null || state.hashes[ids[it]] != MapRefreshEngine.rowHash(rows, it, DIM) }
                    val hintStart = System.nanoTime()
                    val novel = MapRefreshEngine.groupNovel(nb, n, nb.index.size / n, flagged, MapRefreshEngine.Config(MapRefreshEngine.Router.UNION)) { if (!isActive()) throw MapRefreshEngine.Cancelled() }
                    val hintMs = (System.nanoTime() - hintStart) / 1e6
                    outcome = if (r.refreshed) "refreshed hintNovel=$novel" else "full:${r.reason}"
                    detail = "hint_ms=$hintMs phases=" + r.phases.entries.joinToString(",") { "${it.key}:${"%.1f".format(it.value)}" }
                }
            }
        } catch (_: MapRefreshEngine.Cancelled) { outcome = "cancelled" } catch (_: kotlinx.coroutines.CancellationException) { outcome = "cancelled" } catch (e: Throwable) { outcome = "stopped:${e::class.simpleName}" }
        val end = System.nanoTime()
        sampling.set(false); sampler.join()
        val latency = if (due != Long.MAX_VALUE) "%.1f".format((end - due) / 1e6) else "n/a"
        val stored = if (mode == "refresh") state.productionBytes() + REGION_STATE_BYTES else 0L
        val info = "LJ_MAP_R3 mode=$mode scenario=$scenario router=$router n=$n outcome=$outcome stored_state_bytes=$stored total_ms=${(end - start) / 1e6} cancel_after_ms=${cancelAfterMs ?: "none"} " +
            "latency_from_deadline_ms=$latency max_poll_gap_ms=${longestGap.get() / 1e6} heap_peak_delta_bytes=${peak.get() - usedBefore} " +
            "heap_limit_bytes=${runtime.maxMemory()} peak_vs_available_before=${peak.get() - usedBefore}/$budgetBefore $detail"
        Log.i("LJMapR3", info)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "map-round3.log").appendText(info + "\n")
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

    /** Same formula as the app's availableMapWorkBytes on Android. */
    private fun availableWorkBytes(): Long {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 8)
        return (runtime.maxMemory() - javaUsed - android.os.Debug.getNativeHeapAllocatedSize() - reserve).coerceAtLeast(0)
    }

    private companion object {
        const val DIM = 1344
        /** Region identity kept between rebuilds: up to 200 regions x (1,344-d centroid + floor) + 10,000 track entries (~52 B each as a map on ART). */
        const val REGION_STATE_BYTES = 200L * (4 * DIM + 4) + 52L * 10_000
    }
}
