/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in profiling: synthetic input, or an explicitly copied f32 replay; no music-app access. */
class MapLayoutProfileDeviceTest {
    @Test
    fun profileExactLayout() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mapProfile") == "1")
        val n = args.getString("mapRows")?.toInt() ?: 1107
        val repeats = args.getString("mapRepeats")?.toInt() ?: 3
        require(n in 3..5000 && repeats in 1..5)
        val inputFile = if (args.getString("mapInput") == "1") {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "map-input.f32")
        } else null
        val originalPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            repeat(3) { run(180, -1, persist = false) }
            repeat(repeats) { run(n, it, persist = it == 0, inputFile = inputFile) }
        } finally {
            Process.setThreadPriority(originalPriority)
        }
    }

    private fun run(n: Int, round: Int, persist: Boolean, inputFile: File? = null) {
        // Identical deterministic clustered fixture to LayoutPerformanceReplay. No media or tags.
        val rows = if (inputFile == null) fixture(n) else {
            require(inputFile.length() == n.toLong() * DIM * 4) { "Unexpected replay matrix size" }
            val buffer = ByteBuffer.wrap(inputFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(n * DIM) { buffer.float }.also { values -> require(values.all { it.isFinite() }) }
        }
        val runtime = Runtime.getRuntime()
        System.gc()
        val usedBefore = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(usedBefore)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true, name = "map-heap-sampler") {
            while (sampling.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.updateAndGet { maxOf(it, used) }
                Thread.sleep(20)
            }
        }
        val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")
        val start = System.nanoTime()
        var checks = 0
        var optimizerStart = 0L
        var longestIterationNs = 0L
        var previousCheck = 0L
        try {
            val reduced = LibraryLayout.reduceForEmbedding(rows, n, DIM)
            val reducedAt = System.nanoTime()
            val embedding = Tsne.embed(reduced, n, reduced.size / n, SEED, isActive = {
                checks++
                // affinities checks each of its n distance rows and n probability rows.
                if (checks > 2 * n) {
                    val now = System.nanoTime()
                    if (optimizerStart == 0L) optimizerStart = now
                    if (previousCheck != 0L) longestIterationNs = maxOf(longestIterationNs, now - previousCheck)
                    previousCheck = now
                }
                true
            })
            val end = System.nanoTime()
            longestIterationNs = maxOf(longestIterationNs, end - previousCheck)
            assertTrue(embedding.all { it.isFinite() })
            assertTrue(checks == 2 * n + Tsne.ITERATIONS)
            if (round >= 0) {
                val info = "LJ_MAP_EXACT n=$n round=$round dim=$DIM " +
                    "fixture=${if (inputFile == null) "synthetic" else "replay"} " +
                    "pca_ms=${(reducedAt - start) / 1e6} " +
                    "affinity_ms=${(optimizerStart - reducedAt) / 1e6} " +
                    "optimizer_ms=${(end - optimizerStart) / 1e6} total_ms=${(end - start) / 1e6} " +
                    "max_iteration_ms=${longestIterationNs / 1e6} " +
                    "heap_before_bytes=$usedBefore heap_peak_sampled_bytes=${peak.get()} " +
                    "heap_limit_bytes=${runtime.maxMemory()} gc_before=$gcBefore " +
                    "gc_after=${Debug.getRuntimeStat("art.gc.gc-count")} iterations=${Tsne.ITERATIONS}"
                println(info)
                Log.i("LJMapProfile", info)
                File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    "map-profile-$n.log").appendText(info + "\n")
            }
            if (persist) {
                val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                writeFloats(File(dir, "map-reduced-$n.f32"), reduced)
                writeFloats(File(dir, "map-exact-$n.f32"), embedding)
            }
        } finally {
            sampling.set(false)
            sampler.join()
        }
    }

    private fun fixture(n: Int): FloatArray {
        var state = 99871L
        val rows = FloatArray(n * DIM)
        for (i in 0 until n) {
            var norm = 0f
            for (d in 0 until DIM) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val noise = ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
                val value = noise + if (d % 12 == i % 12) 0.25f else 0f
                rows[i * DIM + d] = value
                norm += value * value
            }
            norm = sqrt(norm)
            for (d in 0 until DIM) rows[i * DIM + d] /= norm
        }
        return rows
    }

    private fun writeFloats(file: File, values: FloatArray) {
        val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(file).use { output ->
            for (value in values) {
                if (buffer.remaining() < 4) {
                    output.write(buffer.array(), 0, buffer.position())
                    buffer.clear()
                }
                buffer.putFloat(value)
            }
            output.write(buffer.array(), 0, buffer.position())
        }
    }

    private companion object {
        const val DIM = 1344
        const val SEED = 0x1A7E27
    }
}
