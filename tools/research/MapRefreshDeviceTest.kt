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
 * Research stage for docs/map-cost-research-2026-10-25.md (approach 1 gate, device rows). Synthetic
 * 10,000-track library (seed 13); the old map is laid out cold on 99 % (untimed), then the full
 * library is updated either as today (`-e mapMode today`: warm Barnes-Hut, 1,000 iterations) or by
 * the refresh (`refresh`: 5-NN placement of the 1 % newcomers + 150 iterations without
 * exaggeration from the raw-scale start). Times the update and samples used Java heap every 10 ms.
 * `-e mapCancelAfterMs T` cancels the update after T ms. Appends to files/map-refresh.log.
 * Copy into core/smart/src/androidDeviceTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/ to run; the
 * novelty router is not part of this stage (it would reuse the 60-NN lists in production).
 */
class MapRefreshDeviceTest {
    @Test
    fun refreshAgainstToday() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapRefresh") == "1")
        val n = args.getString("mapRows")?.toInt() ?: 10_000
        val mode = args.getString("mapMode") ?: "refresh"
        val cancelAfterMs = args.getString("mapCancelAfterMs")?.toLong()
        val priority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try { run(n, mode, cancelAfterMs) } finally { Process.setThreadPriority(priority) }
    }

    private fun run(n: Int, mode: String, cancelAfterMs: Long?) {
        val (ids, rows) = fixture(n, 13)
        val old = (0 until n).filter { it % 100 != 0 }
        val oldRows = FloatArray(old.size * DIM)
        old.forEachIndexed { slot, row -> rows.copyInto(oldRows, slot * DIM, row * DIM, (row + 1) * DIM) }
        var rawSpread = 0f
        val recording = MapEmbeddingBackend { r, rn, rd, initial, active ->
            ApproximateMapEmbedding.embed(r, rn, rd, initial, active).also { if (it is MapEmbeddingResult.Complete) rawSpread = spread(it.coordinates) }
        }
        val before = LibraryLayout.computeWith(
            LibraryVectorSpace(old.map(ids::get), oldRows, DIM, LibraryVectorSource.AUDIO), emptyMap(), { true }, Long.MAX_VALUE, recording,
        )
        val previous = before.associate { it.trackId to floatArrayOf(it.x, it.y) }
        val flagged = BooleanArray(n) { it % 100 == 0 }
        val space = LibraryVectorSpace(ids, rows, DIM, LibraryVectorSource.AUDIO)

        val runtime = Runtime.getRuntime()
        System.gc(); System.runFinalization(); System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true, name = "map-heap-sampler") {
            while (sampling.get()) { peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; Thread.sleep(10) }
        }
        val active = AtomicBoolean(true)
        val cancelledAt = AtomicLong(0)
        val lastPoll = AtomicLong(0)
        val longestGap = AtomicLong(0)
        val isActive = {
            val now = System.nanoTime()
            val previousPoll = lastPoll.getAndSet(now)
            if (previousPoll != 0L) longestGap.accumulateAndGet(now - previousPoll, ::maxOf)
            active.get()
        }
        val start = System.nanoTime()
        val canceller = cancelAfterMs?.let { delay ->
            thread(isDaemon = true) { Thread.sleep(delay); cancelledAt.set(System.nanoTime()); active.set(false) }
        }
        val placeMs = AtomicLong(0)
        val backend = if (mode == "today") ApproximateMapEmbedding else MapEmbeddingBackend { reduced, rn, rd, initial, activeNow ->
            val y = rescaled(initial!!, rawSpread)
            val placeStart = System.nanoTime()
            place(reduced, rn, rd, flagged, y, activeNow)
            placeMs.set((System.nanoTime() - placeStart) / 1_000_000)
            MapEmbeddingResult.Complete(BarnesHutTsne.embed(
                reduced, rn, rd, 0x1A7E27, y, activeNow,
                iterations = 150, exaggerationIterations = 0, scheduleOffset = Tsne.EXAGGERATION_ITERATIONS,
            ))
        }
        val outcome = try {
            val points = LibraryLayout.computeWith(space, previous, isActive, Long.MAX_VALUE, backend)
            "points=${points.size}"
        } catch (stopped: Throwable) { "stopped=${stopped::class.simpleName}" }
        val end = System.nanoTime()
        canceller?.join()
        sampling.set(false)
        sampler.join()
        val cancelLatency = cancelledAt.get().takeIf { it != 0L }?.let { (end - it) / 1e6 }
        val info = "LJ_MAP_REFRESH mode=$mode n=$n $outcome total_ms=${(end - start) / 1e6} place_ms=${placeMs.get()} " +
            "cancel_after_ms=${cancelAfterMs ?: "none"} cancel_latency_ms=${cancelLatency ?: "n/a"} max_poll_gap_ms=${longestGap.get() / 1e6} " +
            "heap_before_bytes=$usedBefore heap_peak_sampled_bytes=${peak.get()} heap_peak_delta_bytes=${peak.get() - usedBefore} " +
            "heap_limit_bytes=${runtime.maxMemory()}"
        Log.i("LJMapRefresh", info)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "map-refresh.log").appendText(info + "\n")
    }

    private fun place(reduced: FloatArray, n: Int, dim: Int, flagged: BooleanArray, y: FloatArray, isActive: () -> Boolean) {
        val start = y.copyOf()
        val packed = LongArray(n)
        for (i in 0 until n) {
            if (!flagged[i]) continue
            if (!isActive()) return
            for (j in 0 until n) {
                var sum = 0f
                if (j != i) for (d in 0 until dim) { val delta = reduced[i * dim + d] - reduced[j * dim + d]; sum += delta * delta }
                packed[j] = ((if (j == i) -1L else sum.toRawBits().toLong()) shl 32) or j.toLong()
            }
            packed.sort()
            var x = 0f
            var z = 0f
            var taken = 0
            for (position in 1 until n) {
                val j = (packed[position] and 0xFFFFFFFFL).toInt()
                if (flagged[j]) continue
                x += start[j * 2]; z += start[j * 2 + 1]
                if (++taken == 5) break
            }
            y[i * 2] = x / taken; y[i * 2 + 1] = z / taken
        }
    }

    private fun spread(y: FloatArray): Float {
        val n = y.size / 2
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) { cx += y[i * 2]; cy += y[i * 2 + 1] }
        cx /= n; cy /= n
        var sum = 0.0
        for (i in 0 until n) { val dx = y[i * 2] - cx; val dy = y[i * 2 + 1] - cy; sum += dx * dx + dy * dy }
        return sqrt(sum / n).toFloat()
    }

    private fun rescaled(y: FloatArray, target: Float): FloatArray {
        val factor = target / spread(y)
        return FloatArray(y.size) { y[it] * factor }
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

    private companion object {
        const val DIM = 1344
    }
}
