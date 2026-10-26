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
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Piece 2 of docs/map-refresh-checks.md on a device: synthetic 10,000 tracks (seed 61). Untimed setup: a full build
 * through LibraryLayoutRefresh.build on 99 % (or on the pre-change vectors for change1), its state saved with
 * FileMapRefreshStateStore and loaded back mapped. Measured: `-e mapMode today` (LibraryLayout.compute, streamed,
 * warm) or `refresh` (LibraryLayoutRefresh.refresh from the mapped state). Logs time, sampled Java heap peak delta and
 * the mapped file size to files/map-refresh-heap.log.
 */
class MapRefreshHeapDeviceTest {
    @Test
    fun refreshHeap() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapRefreshHeap") == "1")
        val mode = args.getString("mapMode") ?: "refresh"
        val scenario = args.getString("mapScenario") ?: "add1"
        val priority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try { runBlocking { run(mode, scenario) } } finally { Process.setThreadPriority(priority) }
    }

    private fun rowsOf(ids: List<TrackId>, m: FloatArray) = LibraryVectorRows(ids, DIM, LibraryVectorSource.AUDIO, m.contentHashCode().toLong(),
        readChunk = { start, count, out -> m.copyInto(out, 0, start * DIM, (start + count) * DIM) }, verify = { true })

    private suspend fun run(mode: String, scenario: String) {
        val n = 10_000
        val (ids, rows) = fixture(n, 61)
        val old = if (scenario == "change1") (0 until n).toList() else (0 until n).filter { it % 100 != 0 }
        val oldRows = FloatArray(old.size * DIM)
        old.forEachIndexed { slot, r -> rows.copyInto(oldRows, slot * DIM, r * DIM, (r + 1) * DIM) }
        if (scenario == "change1") for (i in 0 until n step 100) {
            val other = (i * 7919 + 13) % n
            var norm = 0.0
            for (d in 0 until DIM) { val v = 0.5f * rows[i * DIM + d] + 0.5f * rows[other * DIM + d]; oldRows[i * DIM + d] = v; norm += v.toDouble() * v }
            val inv = (1.0 / sqrt(norm)).toFloat(); for (d in 0 until DIM) oldRows[i * DIM + d] *= inv
        }
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val store = FileMapRefreshStateStore(dir, "map_refresh_state_check.bin")
        val built = LibraryLayoutRefresh.build(rowsOf(old.map(ids::get), oldRows), emptyMap(), "check")!!
        store.save(built.snapshot!!)
        val previous = built.points.associate { it.trackId to floatArrayOf(it.x, it.y) }
        @Suppress("UNUSED_VALUE") var heldOld: FloatArray? = oldRows
        heldOld = null
        val mappedBytes = File(dir, "map_refresh_state_check.bin").length()

        val runtime = Runtime.getRuntime()
        System.gc(); System.runFinalization(); System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (sampling.get()) { peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; Thread.sleep(10) } }
        var loadMs = 0.0
        val start = System.nanoTime()
        val outcome = if (mode == "today") {
            LibraryLayout.compute(rowsOf(ids, rows), previous)!!.size.let { "completed points=$it" }
        } else {
            // Loading (parse, checksum, validation) is part of the app's refresh path, so it is inside
            // the timed and sampled window (piece 2 review round 1, issue 1).
            val loadStart = System.nanoTime()
            val state = store.load()!!
            loadMs = (System.nanoTime() - loadStart) / 1e6
            val r = try { LibraryLayoutRefresh.refresh(rowsOf(ids, rows), previous, state, "check") } finally { state.close() }
            if (r == null) "declined" else "refreshed stale=${r.stale} changed=${r.changedSinceFull}"
        }
        val ms = (System.nanoTime() - start) / 1e6
        sampling.set(false); sampler.join()
        val info = "LJ_MAP_REFRESH_HEAP mode=$mode scenario=$scenario outcome=$outcome total_ms=$ms load_ms=$loadMs load_in_window=1 heap_peak_delta_bytes=${peak.get() - usedBefore} " +
            "mapped_state_bytes=$mappedBytes heap_limit_bytes=${runtime.maxMemory()}"
        Log.i("LJMapRefreshHeap", info)
        File(dir, "map-refresh-heap.log").appendText(info + "\n")
        store.clear()
    }

    /** Identical generator to the host MapScaleReplay fixture. */
    private fun fixture(n: Int, seed: Int): Pair<List<TrackId>, FloatArray> {
        val random = Random(seed)
        fun gaussian(): Float { var u = random.nextDouble(); while (u <= 1e-12) u = random.nextDouble(); return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * random.nextDouble())).toFloat() }
        fun unit(v: FloatArray): FloatArray { val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat(); for (i in v.indices) v[i] /= norm; return v }
        val families = List(8) { unit(FloatArray(DIM) { gaussian() }) }
        val styles = List(48) { style -> val family = families[style / 6]; unit(FloatArray(DIM) { family[it] + 0.9f * gaussian() / sqrt(DIM.toFloat()) * 2f }) }
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
