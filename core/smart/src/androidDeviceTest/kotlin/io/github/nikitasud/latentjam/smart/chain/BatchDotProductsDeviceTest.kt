/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.smart.chain

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BatchDotProductsDeviceTest {
    @Test
    fun nativeDotsKeepEveryFloatAndSkippedRow() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val random = Random(71236)
        for (dim in listOf(1, 7, 16, 17, 33, 384, 768, 960)) {
            val n = 103
            val matrix = FloatArray(n * dim) { random.nextFloat() - 0.5f }
            val query = FloatArray(dim + 7) { random.nextFloat() - 0.5f }
            val rows = (0 until n).shuffled(random).toIntArray()
            for (count in listOf(0, 1, 7, 8, 9, 15, 16, 17, 101, 103)) {
                for (alias in listOf(false, true)) {
                    val q = if (alias) matrix else query
                    val offset = if (alias) dim else 7
                    val expected = FloatArray(n) { -1234.5f }
                    val actual = expected.copyOf()
                    batchDots(matrix, dim, q, offset, rows, count, expected)
                    assertTrue(AndroidBatchDotProducts.compute(matrix, dim, q, offset, rows, count, actual))
                    assertArrayEquals(
                        "dim=$dim count=$count alias=$alias",
                        expected.map(Float::toRawBits).toIntArray(), actual.map(Float::toRawBits).toIntArray(),
                    )
                }
            }
        }
    }

    /** Opt-in: -e batchDotsBenchmark 1. Includes JNI pinning and the platform array validation. */
    @Test
    fun benchmarkNativeDotsAgainstPortable() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        assumeTrue(InstrumentationRegistry.getArguments().getString("batchDotsBenchmark") == "1")
        val random = Random(71236)
        for (n in listOf(3_000, 10_000)) {
            val dim = 960
            val matrix = FloatArray(n * dim) { random.nextFloat() - 0.5f }
            val query = FloatArray(dim) { random.nextFloat() - 0.5f }
            val rows = IntArray(n) { it }
            val expected = FloatArray(n)
            batchDots(matrix, dim, query, 0, rows, n, expected)
            for (native in listOf(false, true)) {
                val output = FloatArray(n)
                val times = DoubleArray(31)
                var first = 0.0
                var checksum = 0
                repeat(36) { run ->
                    val start = System.nanoTime()
                    if (native) assertTrue(AndroidBatchDotProducts.compute(matrix, dim, query, 0, rows, n, output))
                    else batchDots(matrix, dim, query, 0, rows, n, output)
                    val ms = (System.nanoTime() - start) / 1e6
                    if (run == 0) first = ms
                    if (run >= 5) times[run - 5] = ms
                    checksum = checksum xor output[run % n].toRawBits()
                }
                assertArrayEquals(expected.map(Float::toRawBits).toIntArray(), output.map(Float::toRawBits).toIntArray())
                times.sort()
                println("LJ_DOTS rows=$n native=$native first_ms=$first median_ms=${times[times.size / 2]} checksum=$checksum")
            }
        }
    }
}
