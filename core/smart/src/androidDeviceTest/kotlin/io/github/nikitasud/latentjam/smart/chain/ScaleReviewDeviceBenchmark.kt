/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * External review 2026-11-02 SCALE-01/02: what one snapshot build's hub penalty and one full classification pass cost.
 * Opt-in (`-e scaleReview 1`); the semantic head is read from `files/bench/head/universal_semantic_head.onnx` when
 * pushed there. Output: `LJ_SCALE …` lines.
 */
class ScaleReviewDeviceBenchmark {
    @Test
    fun hubAndClassifyCost() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("scaleReview") == "1")
        val sizes = args.getString("sizes")?.split(',')?.map(String::toInt) ?: listOf(5_000, 10_000, 20_000, 34_000)
        for (n in sizes) {
            val centered = FloatArray(n * SmartSnapshot.AUDIO_DIM)
            fill(centered, n, SmartSnapshot.AUDIO_DIM)
            val rows = MatrixCentredRows(centered, SmartSnapshot.AUDIO_DIM)
            val times = DoubleArray(3)
            var check = 0f
            for (r in times.indices) {
                val started = System.nanoTime()
                val p = SmartSnapshot.computeHubPenalty(rows, kernel = AndroidBatchDotProducts)
                times[r] = (System.nanoTime() - started) / 1e6
                check += p[n / 2]
            }
            times.sort()
            println("LJ_SCALE hub rows=$n median_ms=${times[1]} min_ms=${times[0]} max_ms=${times[2]} check=$check")
        }
        for (n in args.getString("buildSizes")?.split(',')?.map(String::toInt) ?: listOf(5_000, 20_000)) {
            val tracks = library(n)
            val times = DoubleArray(2)
            for (r in times.indices) {
                val started = System.nanoTime()
                val snapshot = requireNotNull(SmartSnapshot.build(tracks, batchDotProducts = AndroidBatchDotProducts))
                times[r] = (System.nanoTime() - started) / 1e6
                check(snapshot.size == n)
            }
            println("LJ_SCALE build rows=$n first_ms=${times[0]} second_ms=${times[1]}")
            // As a press builds it with SmartEngineConfig.backgroundHub: the hub is handed in, never computed.
            val supplied = DoubleArray(2)
            for (r in supplied.indices) {
                val started = System.nanoTime()
                val snapshot = requireNotNull(
                    SmartSnapshot.build(tracks, batchDotProducts = AndroidBatchDotProducts, hubPenaltyOf = { FloatArray(it.size) }),
                )
                supplied[r] = (System.nanoTime() - started) / 1e6
                check(snapshot.size == n)
            }
            println("LJ_SCALE build_hub_supplied rows=$n first_ms=${supplied[0]} second_ms=${supplied[1]}")
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val head = File(context.filesDir, "bench/head/universal_semantic_head.onnx")
        if (!head.isFile) {
            println("LJ_SCALE classify skipped: no $head")
            return
        }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        env.createSession(head.readBytes(), options).use { session ->
            val input = session.inputNames.first()
            val batch = FloatArray(BATCH * SmartSnapshot.AUDIO_DIM)
            fill(batch, BATCH, SmartSnapshot.AUDIO_DIM)
            fun once() = OnnxTensor.createTensor(env, FloatBuffer.wrap(batch), longArrayOf(BATCH.toLong(), 960)).use { t ->
                session.run(mapOf(input to t)).use { }
            }
            repeat(5) { once() }
            for (n in listOf(5_000, 20_000)) {
                val batches = (n + BATCH - 1) / BATCH
                val started = System.nanoTime()
                repeat(batches) { once() }
                println("LJ_SCALE classify rows=$n batches=$batches ms=${(System.nanoTime() - started) / 1e6}")
            }
        }
    }

    private fun fill(target: FloatArray, n: Int, dim: Int) {
        var state = 0x5eed_9876L
        for (row in 0 until n) {
            val style = (row / 8) % 40
            var sum = 0.0
            for (d in 0 until dim) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val v = ((state ushr 33).toFloat() / (1L shl 31).toFloat() - 0.5f) * 0.08f + if (d == style) 1f else 0f
                target[row * dim + d] = v
                sum += v * v
            }
            val inv = (1.0 / sqrt(sum)).toFloat()
            for (d in 0 until dim) target[row * dim + d] *= inv
        }
    }

    /** The clustered generator of ChainPlanningDeviceTest, with all three spaces. */
    private fun library(n: Int): List<SmartTrack> {
        var state = 0x5eed_1234L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 33).toFloat() / (1L shl 31).toFloat() - 0.5f
        }
        fun vector(dim: Int, style: Int, artist: Int, spread: Float): FloatArray {
            val values = FloatArray(dim) { noise() * spread }
            values[style % dim] += 1f
            values[(artist * 7 + 3) % dim] += 0.6f
            val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
            for (i in values.indices) values[i] /= norm
            return values
        }
        return (0 until n).map { row ->
            val artist = row / 8
            val style = artist % 40
            SmartTrack(
                TrackId(row.toString()), vector(960, style, artist, 0.08f),
                text = vector(384, style, artist, 0.1f), descriptor = vector(384, style, artist, 0.05f),
                energy = 0.5f + noise() * 0.4f,
                meta = TrackMeta("Title $row", "Artist $artist", "Album ${row / 12}", "Style $style", 1990 + style % 30),
            )
        }
    }

    private companion object {
        const val BATCH = 128
    }
}
