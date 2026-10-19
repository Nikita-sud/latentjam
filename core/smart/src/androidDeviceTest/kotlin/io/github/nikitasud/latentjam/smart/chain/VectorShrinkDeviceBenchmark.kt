/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.smart.chain

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Research benchmark (opt-in: -e vshrinkBenchmark 1): one full centred-cosine scan of 10,000 × 960 on ART, the way a
 * SMART press scans the library, three ways. (a) today: the centred matrix on the heap, native kernel. (b) matrix-free:
 * raw rows plus mean and row scale, centred a block of 256 rows at a time into a reused buffer, same kernel. (c) as (b)
 * with the raw rows memory-mapped from a file. (b) and (c) must give (a)'s floats bit for bit.
 */
class VectorShrinkDeviceBenchmark {
    @Test
    fun centredScanThreeWays() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        assumeTrue(InstrumentationRegistry.getArguments().getString("vshrinkBenchmark") == "1")
        val n = 10_000
        val dim = 960
        val random = Random(1019)
        // Raw unit rows, as the index stores them.
        val raw = Array(n) {
            val v = FloatArray(dim) { random.nextFloat() - 0.5f }
            var s = 0.0; for (x in v) s += x.toDouble() * x
            val k = 1f / sqrt(s).toFloat(); for (d in 0 until dim) v[d] *= k
            v
        }
        val mean = FloatArray(dim)
        for (v in raw) for (d in 0 until dim) mean[d] += v[d]
        for (d in 0 until dim) mean[d] /= n
        val rowScale = FloatArray(n) { i ->
            var s = 0.0; for (d in 0 until dim) { val c = raw[i][d] - mean[d]; s += c.toDouble() * c }
            1f / sqrt(s).toFloat().coerceAtLeast(1e-12f)
        }
        val centred = FloatArray(n * dim)
        for (i in 0 until n) for (d in 0 until dim) centred[i * dim + d] = (raw[i][d] - mean[d]) * rowScale[i]
        val query = centred.copyOfRange(0, dim)
        val all = IntArray(n) { it }
        val expected = FloatArray(n)
        val block = 256
        val buffer = FloatArray(block * dim)
        val rowsIndex = IntArray(block) { it }
        val out = FloatArray(n)
        val blockOut = FloatArray(block)
        fun matrixFree(rowOf: (Int, FloatArray, Int) -> Unit) {
            var start = 0
            while (start < n) {
                val count = minOf(block, n - start)
                for (j in 0 until count) {
                    val i = start + j
                    rowOf(i, buffer, j * dim)
                    val s = rowScale[i]
                    for (d in 0 until dim) buffer[j * dim + d] = (buffer[j * dim + d] - mean[d]) * s
                }
                check(AndroidBatchDotProducts.compute(buffer, dim, query, 0, rowsIndex, count, blockOut))
                blockOut.copyInto(out, start, 0, count)
                start += count
            }
        }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "vshrink.f32")
        RandomAccessFile(file, "rw").channel.use { channel ->
            val bytes = ByteBuffer.allocate(n * dim * 4).order(ByteOrder.nativeOrder())
            for (v in raw) for (x in v) bytes.putFloat(x)
            bytes.flip(); channel.write(bytes, 0)
        }
        val mapped = RandomAccessFile(file, "r").channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        fun time(label: String, body: () -> Unit) {
            val t = LongArray(11)
            repeat(14) { r -> val s = System.nanoTime(); body(); if (r >= 3) t[r - 3] = System.nanoTime() - s }
            t.sort()
            Log.i("VSHRINK", "$label median ${t[5] / 1_000_000.0} ms (n=$n dim=$dim)")
        }
        time("a heap matrix") { check(AndroidBatchDotProducts.compute(centred, dim, query, 0, all, n, expected)) }
        time("b matrix-free heap rows") { matrixFree { i, b, o -> raw[i].copyInto(b, o) } }
        assertEquals(expected.map(Float::toRawBits), out.map(Float::toRawBits))
        out.fill(0f)
        time("c matrix-free mapped rows") { matrixFree { i, b, o -> mapped.position(i * dim); mapped.get(b, o, dim) } }
        assertEquals(expected.map(Float::toRawBits), out.map(Float::toRawBits))
        Log.i("VSHRINK", "bit-identical: matrix-free heap and mapped scans equal the matrix scan for all $n rows")
        file.delete()
    }
}
