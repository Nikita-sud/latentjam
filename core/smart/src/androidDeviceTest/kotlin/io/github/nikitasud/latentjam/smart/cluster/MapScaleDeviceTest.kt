/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import android.os.Debug
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
 * Opt-in: the production large-library Map paths on a synthetic library, with no music-app
 * access. `-e mapScale 1 -e mapRows N -e mapStage layout|regions [-e mapCancelAfterMs T]
 * [-e mapBallastMb B]`. Logs to `LJMapScale` and appends to `files/map-scale.log`.
 *
 * The test APK's manifest sets largeHeap, so the heap limit is whatever the device gives a
 * large-heap app: run it where `dalvik.vm.heapsize` equals the app's growth limit (see
 * docs/map-scaling-10k-2026-10-15.md) and check the logged `heap_limit_bytes`.
 */
class MapScaleDeviceTest {
    @Test
    fun profileLargeLibraryMap() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapScale") == "1")
        val n = args.getString("mapRows")?.toInt() ?: 3000
        val stage = args.getString("mapStage") ?: "layout"
        val cancelAfterMs = args.getString("mapCancelAfterMs")?.toLong()
        val ballastMb = args.getString("mapBallastMb")?.toInt() ?: 0
        require(n in 3..MAX_ROWS && stage in STAGES)
        require(n <= LibraryLayout.MAX_TRACKS || stage.endsWith("-stream"))
        val ballast = List(ballastMb) { ByteArray(1024 * 1024) { 1 } }
        val originalPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            run(n, stage, cancelAfterMs, ballastMb)
        } finally {
            Process.setThreadPriority(originalPriority)
        }
        check(ballast.size == ballastMb)
    }

    private fun run(n: Int, stage: String, cancelAfterMs: Long?, ballastMb: Int) {
        val streamed = stage.endsWith("-stream")
        // A streamed run never allocates the n x dim matrix: rows are regenerated on each read.
        val fixture = if (streamed) null else hierarchicalFixture(n, seed = if (stage == "regions") 11 else 7)
        val stream = if (streamed) StreamedFixture(n, seed = 7) else null
        val ids = fixture?.ids ?: List(n) { TrackId("scale-$it") }
        val space = fixture?.let { LibraryVectorSpace(it.ids, it.rows, DIM, LibraryVectorSource.AUDIO) }
        val runtime = Runtime.getRuntime()
        System.gc()
        System.runFinalization()
        System.gc()
        // The fixture's matrix is now owned by the space; it is part of the measured working set.
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val budget = availableWorkBytes()
        val estimate = if (stage.startsWith("layout-sampled") || stage == "layout-stream") {
            0L
        } else if (stage != "regions") {
            MapMemoryBudget.projectionBytes(n, DIM, false)
        } else {
            MapMemoryBudget.regionBytes(n, DIM)
        }
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true, name = "map-heap-sampler") {
            while (sampling.get()) {
                peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                Thread.sleep(10)
            }
        }
        val active = AtomicBoolean(true)
        val polls = AtomicLong(0)
        val lastPoll = AtomicLong(0)
        val longestGap = AtomicLong(0)
        val cancelledAt = AtomicLong(0)
        val stamps = LongArray(4 * n + 4096)
        val isActive = {
            val now = System.nanoTime()
            val index = polls.get().toInt()
            if (index < stamps.size) stamps[index] = now
            val previous = lastPoll.getAndSet(now)
            if (previous != 0L) longestGap.accumulateAndGet(now - previous, ::maxOf)
            polls.incrementAndGet()
            active.get()
        }
        val phaseMarks = mutableListOf<String>()
        val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")
        val start = System.nanoTime()
        val canceller = cancelAfterMs?.let { delay ->
            thread(isDaemon = true, name = "map-canceller") {
                Thread.sleep(delay)
                cancelledAt.set(System.nanoTime())
                active.set(false)
            }
        }
        var outcome: String
        try {
            val source = stream ?: LayoutRowSource { row, out ->
                fixture!!.rows.copyInto(out, 0, row * DIM, (row + 1) * DIM)
            }
            outcome = if (stage == "layout-stream") {
                val points = LibraryLayout.computeStreamed(
                    ids, source, DIM, isActive = isActive,
                    onPhase = { phase -> phaseMarks += "${phase}_at_ms=${(System.nanoTime() - start) / 1e6}" },
                )
                check(points.size == n && points.all { it.x.isFinite() && it.y.isFinite() })
                "points=${points.size} backend=streamed-${if (n > LibraryLayout.EXACT_MAX_TRACKS) "barnes-hut" else "exact"}"
            } else if (stage.startsWith("layout-sampled")) {
                val points = LibraryLayout.computeSampled(
                    ids, source, DIM, isActive = isActive,
                    refineIterations = InstrumentationRegistry.getArguments().getString("mapRefine")?.toInt() ?: SampledTsne.REFINE_ITERATIONS,
                    fullIterations = InstrumentationRegistry.getArguments().getString("mapFull")?.toInt() ?: 0,
                    sampleIterations = InstrumentationRegistry.getArguments().getString("mapSampleIter")?.toInt() ?: Tsne.ITERATIONS,
                    onPhase = { phase -> phaseMarks += "${phase}_at_ms=${(System.nanoTime() - start) / 1e6}" },
                )
                check(points.size == n && points.all { it.x.isFinite() && it.y.isFinite() })
                "points=${points.size} backend=sampled sample=${minOf(n, SampledTsne.SAMPLE_SIZE)}"
            } else if (stage == "layout") {
                val points = LibraryLayout.compute(space!!, isActive = isActive)
                check(points.size == n && points.all { it.x.isFinite() && it.y.isFinite() })
                "points=${points.size} backend=${if (n > LibraryLayout.EXACT_MAX_TRACKS) "barnes-hut" else "exact"}"
            } else if (stage == "layout-bh") {
                // Same reduction as compute(), then Barnes-Hut regardless of the size switch.
                val reduced = LibraryLayout.reduceForEmbedding(space!!.takeRows(), n, DIM, isActive)
                val result = ApproximateMapEmbedding.embed(reduced, n, reduced.size / n, null, isActive)
                check(result is MapEmbeddingResult.Complete)
                "points=$n backend=barnes-hut-forced"
            } else {
                val clusters = AverageLinkageClustering.cluster(space!!, isActive = isActive)
                "regions=${clusters.size} assigned=${clusters.sumOf { it.members.size }} " +
                    "path=${if (n > AverageLinkageClustering.MAX_TRACKS) "sampled" else "exact"}"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            outcome = "cancelled"
        }
        val end = System.nanoTime()
        canceller?.join()
        sampling.set(false)
        sampler.join()
        // Layout polls: PCA passes, then two per row (neighbours or distances, then bandwidth),
        // then one per optimizer iteration. Only meaningful for an uncancelled layout.
        val count = polls.get().toInt()
        val phases = if (stage in setOf("layout", "layout-bh") && cancelAfterMs == null && count <= stamps.size && count > 2 * n + Tsne.ITERATIONS) {
            val optimizer = stamps[count - Tsne.ITERATIONS]
            val affinities = stamps[count - Tsne.ITERATIONS - 2 * n]
            "pca_ms=${(affinities - start) / 1e6} affinity_ms=${(optimizer - affinities) / 1e6} " +
                "optimizer_ms=${(end - optimizer) / 1e6} "
        } else phaseMarks.joinToString(" ", postfix = if (phaseMarks.isEmpty()) "" else " ")
        val cancelLatency = cancelledAt.get().takeIf { it != 0L }?.let { (end - it) / 1e6 }
        val info = "LJ_MAP_SCALE stage=$stage n=$n $outcome total_ms=${(end - start) / 1e6} $phases" +
            "cancel_after_ms=${cancelAfterMs ?: "none"} cancel_latency_ms=${cancelLatency ?: "n/a"} " +
            "max_poll_gap_ms=${longestGap.get() / 1e6} polls=${polls.get()} " +
            "heap_before_bytes=$usedBefore heap_peak_sampled_bytes=${peak.get()} " +
            "heap_peak_delta_bytes=${peak.get() - usedBefore} heap_limit_bytes=${runtime.maxMemory()} " +
            "ballast_mb=$ballastMb app_formula_budget_bytes=$budget estimate_bytes=$estimate " +
            "native_heap_bytes=${Debug.getNativeHeapAllocatedSize()} " +
            "gc_before=$gcBefore gc_after=${Debug.getRuntimeStat("art.gc.gc-count")}"
        Log.i("LJMapScale", info)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "map-scale.log")
            .appendText(info + "\n")
    }

    /** Same formula as the app's availableMapWorkBytes on Android. */
    private fun availableWorkBytes(): Long {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 8)
        return (runtime.maxMemory() - javaUsed - Debug.getNativeHeapAllocatedSize() - reserve).coerceAtLeast(0)
    }

    /**
     * The same family/style structure as [hierarchicalFixture], but row `i` is drawn from its own
     * seeded generator, so any row can be regenerated without holding the matrix. Row draws differ
     * from the held fixture's; use it for time and memory, not for quality.
     */
    private class StreamedFixture(n: Int, seed: Int) : LayoutRowSource {
        private val seed = seed
        private val styles: List<FloatArray>
        private val weights = DoubleArray(48) { 1.0 / (1 + it % 12) }
        private val total = weights.sum()

        init {
            val random = Random(seed)
            val families = List(8) { unit(FloatArray(DIM) { gaussian(random) }) }
            styles = List(48) { style ->
                val family = families[style / 6]
                unit(FloatArray(DIM) { family[it] + 0.9f * gaussian(random) / sqrt(DIM.toFloat()) * 2f })
            }
            check(n > 0)
        }

        override fun read(row: Int, out: FloatArray) {
            val random = Random(seed * 1_000_003 + row)
            val scattered = random.nextDouble() < 0.15
            var pick = random.nextDouble() * total
            var style = 0
            while (pick > weights[style]) { pick -= weights[style]; style++ }
            val spread = if (scattered) 1f else 0.55f + 0.3f * (style % 4) / 3f
            for (d in 0 until DIM) {
                val base = if (scattered) 0f else styles[style][d]
                out[d] = base + spread * gaussian(random) / sqrt(DIM.toFloat()) * 2f
            }
            unit(out)
        }

        private fun gaussian(random: Random): Float {
            var u = random.nextDouble()
            while (u <= 1e-12) u = random.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * random.nextDouble())).toFloat()
        }

        private fun unit(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            for (i in vector.indices) vector[i] /= norm
            return vector
        }
    }

    private class Fixture(val ids: List<TrackId>, val rows: FloatArray, val labels: IntArray)

    /** Identical generator to the host MapScaleReplay fixture. */
    private fun hierarchicalFixture(n: Int, seed: Int, dim: Int = DIM): Fixture {
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
        val families = List(8) { unit(FloatArray(dim) { gaussian() }) }
        val styles = List(48) { style ->
            val family = families[style / 6]
            unit(FloatArray(dim) { family[it] + 0.9f * gaussian() / sqrt(dim.toFloat()) * 2f })
        }
        val weights = DoubleArray(48) { 1.0 / (1 + it % 12) }
        val total = weights.sum()
        val rows = FloatArray(n * dim)
        val labels = IntArray(n)
        for (i in 0 until n) {
            val scattered = random.nextDouble() < 0.15
            var pick = random.nextDouble() * total
            var style = 0
            while (pick > weights[style]) { pick -= weights[style]; style++ }
            labels[i] = if (scattered) 48 + i else style
            val spread = if (scattered) 0f else 0.55f + 0.3f * (style % 4) / 3f
            val base = if (scattered) FloatArray(dim) else styles[style]
            val row = FloatArray(dim) { base[it] + (if (scattered) 1f else spread) * gaussian() / sqrt(dim.toFloat()) * 2f }
            unit(row).copyInto(rows, i * dim)
        }
        return Fixture(List(n) { TrackId("scale-$it") }, rows, labels)
    }

    private companion object {
        const val DIM = 1344
        const val MAX_ROWS = 20_000
        val STAGES = setOf("layout", "layout-bh", "layout-sampled", "layout-sampled-stream", "layout-stream", "regions")
    }
}
