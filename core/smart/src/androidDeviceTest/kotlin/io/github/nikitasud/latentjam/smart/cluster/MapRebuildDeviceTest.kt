/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import android.os.Process
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Map refresh check, piece 3, on a device: the deferred rebuild's work at 10,000 synthetic tracks (seed 61)
 * on a background-priority thread, as MapRebuild runs it: region discovery on the path the app's budget formula
 * picks, identity rediscovery, the full layout build, the state write. `-e mapRebuild 1 [-e mapRebuildCancels 10]`.
 * Logs to files/map-rebuild.log: path, peak heap against the budget, total time, the longest gap between two
 * cancellation polls (and the stage it fell in), and for each random deadline the latency until the run stopped.
 */
class MapRebuildDeviceTest {
    @Test
    fun rebuild() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapRebuild") == "1")
        val cancels = args.getString("mapRebuildCancels")?.toInt() ?: 10
        // Supplementary set: deadlines in [0, mapRebuildDeadlineMaxMs).
        deadlineMaxMs = args.getString("mapRebuildDeadlineMaxMs")?.toDouble()
        val priority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try { runBlocking { run(cancels) } } finally { Process.setThreadPriority(priority) }
    }

    private var deadlineMaxMs: Double? = null

    private val dir get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private fun log(line: String) { Log.i("LJMapRebuild", line); File(dir, "map-rebuild.log").appendText(line + "\n") }

    private class Polls {
        var stage = "start"
        var last = SystemClock.elapsedRealtimeNanos()
        var maxGapNs = 0L
        var maxGapStage = ""
        var count = 0L
        var deadlineNs = Long.MAX_VALUE
        var stoppedAtNs = 0L
        fun poll(): Boolean {
            val now = SystemClock.elapsedRealtimeNanos()
            val gap = now - last
            if (gap > maxGapNs) { maxGapNs = gap; maxGapStage = stage }
            last = now
            count++
            if (now >= deadlineNs) { stoppedAtNs = now; return false }
            return true
        }
        fun enter(name: String) { poll(); stage = name }
    }

    /** Longest single chunk read: in the app each one holds the engine lock, so this stands in for SMART's longest wait. */
    private val maxChunkNs = AtomicLong(0)

    private fun rowsOf(ids: List<TrackId>, m: FloatArray) = LibraryVectorRows(ids, DIM, LibraryVectorSource.AUDIO, m.contentHashCode().toLong(),
        readChunk = { start, count, out ->
            val t = SystemClock.elapsedRealtimeNanos()
            m.copyInto(out, 0, start * DIM, (start + count) * DIM)
            maxChunkNs.updateAndGet { maxOf(it, SystemClock.elapsedRealtimeNanos() - t) }
        }, verify = { true })

    /** The app's availableMapWorkBytes (composeApp androidMain) for the Kotlin paths. */
    private fun availableWork(): Long {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 8)
        return (runtime.maxMemory() - used - reserve).coerceAtLeast(0)
    }

    private suspend fun run(cancels: Int) {
        File(dir, "map-rebuild.log").delete()
        val n = 10_000
        val (ids, rows) = fixture(n, 61)
        val descriptors = ids.map { TrackDescriptor(it) }
        // Untimed setup, in its own frame so the 99 % matrix is garbage before the measured runs.
        val (previous, oldRegions) = setup(ids, rows, descriptors)
        val settled = ids.filterIndexedTo(HashSet()) { i, _ -> i % 100 != 0 }
        val store = FileMapRefreshStateStore(dir, "map_rebuild_state_check.bin")

        // One full run, then [cancels] runs stopped at random deadlines inside it.
        maxChunkNs.set(0)
        val full = once(ids, rows, descriptors, previous, oldRegions, settled, store, Polls())
        log("LJ_MAP_REBUILD run=full outcome=${full.outcome} path=${full.path} total_ms=${full.ms} budget_bytes=${full.budget} " +
            "heap_peak_delta_bytes=${full.peak} polls=${full.polls} max_poll_gap_ms=${full.maxGapMs} max_gap_stage=${full.maxGapStage} " +
            "stages_ms=${full.stages} max_chunk_read_ms=${maxChunkNs.get() / 1e6} heap_limit_bytes=${Runtime.getRuntime().maxMemory()}")
        val random = Random(6161)
        repeat(cancels) { i ->
            val polls = Polls()
            val at = (random.nextDouble() * (deadlineMaxMs ?: full.ms) * 1e6).toLong()
            polls.deadlineNs = SystemClock.elapsedRealtimeNanos() + at
            val stateFile = File(dir, "map_rebuild_state_check.bin")
            val stamp = stateFile.lastModified() to stateFile.length()
            val r = once(ids, rows, descriptors, previous, oldRegions, settled, store, polls)
            val untouched = (stateFile.lastModified() to stateFile.length()) == stamp
            val latency = if (polls.stoppedAtNs == 0L) -1.0 else (r.endNs - polls.deadlineNs) / 1e6
            log("LJ_MAP_REBUILD run=cancel$i deadline_ms=${at / 1e6} outcome=${r.outcome} stage=${polls.stage} cancel_latency_ms=$latency state_untouched=$untouched")
        }
        store.clear()
    }

    /** The map and regions as they were before 1 % was added (and refreshed in). */
    private suspend fun setup(ids: List<TrackId>, rows: FloatArray, descriptors: List<TrackDescriptor>): Pair<Map<TrackId, FloatArray>, List<IdentityRegion>> {
        val before = (0 until ids.size).filter { it % 100 != 0 }
        val beforeIds = before.map(ids::get)
        val beforeRows = FloatArray(before.size * DIM).also { out -> before.forEachIndexed { s, r -> rows.copyInto(out, s * DIM, r * DIM, (r + 1) * DIM) } }
        val previous = LibraryLayoutRefresh.build(rowsOf(beforeIds, beforeRows), emptyMap(), "check")!!.points.associate { it.trackId to floatArrayOf(it.x, it.y) }
        val oldWorlds = LibraryWorlds.discover(before.map(descriptors::get), LibraryVectorSpace(beforeIds, beforeRows.copyOf(), DIM, LibraryVectorSource.AUDIO), emptyMap(), isActive = { true })
        return previous to RegionIdentity.initial(oldWorlds, rowsOf(beforeIds, beforeRows))
    }

    private class Result(val outcome: String, val path: String, val ms: Double, val budget: Long, val peak: Long, val polls: Long,
                         val maxGapMs: Double, val maxGapStage: String, val stages: String, val endNs: Long)

    private suspend fun once(
        ids: List<TrackId>, rows: FloatArray, descriptors: List<TrackDescriptor>, previous: Map<TrackId, FloatArray>,
        oldRegions: List<IdentityRegion>, settled: Set<TrackId>, store: FileMapRefreshStateStore, polls: Polls,
    ): Result {
        val runtime = Runtime.getRuntime()
        System.gc(); System.runFinalization(); System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (sampling.get()) { peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; Thread.sleep(10) } }
        val stages = StringBuilder()
        var mark = SystemClock.elapsedRealtimeNanos()
        fun stage(name: String) { val now = SystemClock.elapsedRealtimeNanos(); stages.append("${polls.stage}:${(now - mark) / 1_000_000},"); mark = now; polls.enter(name) }
        val start = SystemClock.elapsedRealtimeNanos()
        polls.last = start
        // The app measures its budget once before discovery (after one collection) and once before the layout.
        val budget = availableWork()
        val path = if (MapMemoryBudget.regionWorkBytes(ids.size, DIM) <= budget) "average-linkage" else "k-means"
        val outcome = try {
            stage("regions")
            val worlds = LibraryWorlds.discover(descriptors, LibraryVectorSpace(ids, rows.copyOf(), DIM, LibraryVectorSource.AUDIO), emptyMap(),
                isActive = polls::poll, workBudgetBytes = budget)
            stage("identity")
            RegionIdentity.rediscover(oldRegions, worlds, settled, rowsOf(ids, rows), RegionIdentity.DELTA, isActive = polls::poll)
            stage("layout")
            val result = LibraryLayoutRefresh.build(rowsOf(ids, rows), previous, "check", polls::poll, availableWork())
                ?: error("layout refused")
            stage("state-write")
            store.save(result.snapshot!!)
            stage("end")
            "completed"
        } catch (cancelled: CancellationException) {
            "cancelled"
        }
        val end = SystemClock.elapsedRealtimeNanos()
        stages.append("${polls.stage}:${(end - mark) / 1_000_000}")
        sampling.set(false); sampler.join()
        return Result(outcome, path, (end - start) / 1e6, budget, peak.get() - usedBefore, polls.count, polls.maxGapNs / 1e6, polls.maxGapStage,
            stages.toString(), end)
    }

    /** Identical generator to MapRefreshHeapDeviceTest and the host MapScaleReplay fixture. */
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
