/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.text.ArtistAdapter
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledgePack
import io.github.nikitasud.latentjam.smart.text.MusicEntityIndex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.zip.CRC32
import kotlin.math.sin
import kotlin.random.Random

/**
 * Times and weighs model bundles side by side on a device, without touching the installed app.
 *
 * Each bundle is a folder of this test package's files, `files/bench/<name>/`, holding the files the
 * app ships under `assets/ml` (pushed there with `adb … | run-as <test package> sh -c 'cat > …'`). Every
 * graph runs with the app's session options (sequential, one intra-op and one inter-op thread) on
 * inputs of production shapes. Skipped when no bundle is present, so ordinary device runs are unaffected.
 *
 * Output, one logcat line per graph or table: `LJ_BENCH bundle=… asset=… load_ms=… first_ms=… warm_us=…
 * native_kb=…`, warm time the median of [REPEATS] runs, native_kb the native heap the session holds.
 *
 * Instrumentation arguments `arena=false` and `pattern=false` turn off ONNX Runtime's CPU memory arena
 * and memory-pattern planning, to weigh what an idle session keeps between runs.
 */
class ModelBenchmarkDeviceTest {

    @Test
    fun benchmarkBundles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "bench")
        val bundles = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no bundles under $root", bundles.isNotEmpty())
        val environment = OrtEnvironment.getEnvironment()
        val arguments = InstrumentationRegistry.getArguments()
        arena = arguments.getString("arena") != "false"
        pattern = arguments.getString("pattern") != "false"
        repeat(2) { round -> // round 0 warms the process (JIT, allocator); round 1 is reported
            for (bundle in bundles) {
                for ((asset, feeds) in graphs()) {
                    val file = File(bundle, asset)
                    if (!file.isFile) continue
                    val result = measureGraph(environment, file.readBytes(), feeds)
                    if (round == 1) report(bundle.name, asset, result)
                }
                if (round == 1) measureTables(bundle)
            }
        }
    }

    private var arena = true
    private var pattern = true

    private class Measured(val loadMs: Double, val firstMs: Double, val warmUs: Long, val nativeKb: Long, val checksum: Long)

    private fun measureGraph(environment: OrtEnvironment, bytes: ByteArray, feeds: (OrtEnvironment) -> Map<String, OnnxTensor>): Measured {
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize()
        val loadStarted = System.nanoTime()
        val session = OrtSession.SessionOptions().use { options ->
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(arena)
            options.setMemoryPatternOptimization(pattern)
            environment.createSession(bytes, options)
        }
        val loadMs = (System.nanoTime() - loadStarted) / 1e6
        val inputs = feeds(environment)
        val firstStarted = System.nanoTime()
        val checksum = session.run(inputs).use(::checksum)
        val firstMs = (System.nanoTime() - firstStarted) / 1e6
        val times = LongArray(REPEATS) {
            val started = System.nanoTime()
            session.run(inputs).close()
            (System.nanoTime() - started) / 1000
        }
        val nativeKb = (Debug.getNativeHeapAllocatedSize() - before) / 1024
        inputs.values.forEach(OnnxTensor::close)
        session.close()
        times.sort()
        return Measured(loadMs, firstMs, times[REPEATS / 2], nativeKb, checksum)
    }

    /** CRC-32 over the raw bits of every float output, to show that a setting leaves results unchanged. */
    private fun checksum(result: OrtSession.Result): Long {
        val crc = CRC32()
        for ((_, value) in result) {
            val floats = (value as? OnnxTensor)?.floatBuffer ?: continue
            while (floats.hasRemaining()) {
                val bits = floats.get().toRawBits()
                for (shift in 0 until 32 step 8) crc.update(bits ushr shift and 0xff)
            }
        }
        return crc.value
    }

    private fun report(bundle: String, asset: String, m: Measured) {
        println(
            "LJ_BENCH bundle=$bundle asset=$asset arena=$arena pattern=$pattern load_ms=${"%.1f".format(m.loadMs)} " +
                "first_ms=${"%.2f".format(m.firstMs)} warm_us=${m.warmUs} native_kb=${m.nativeKb} " +
                "out_crc=${m.checksum.toString(16)}",
        )
    }

    private fun measureTables(bundle: File) {
        fun time(asset: String, parse: (ByteArray) -> Any?) {
            val file = File(bundle, asset)
            if (!file.isFile) return
            val bytes = file.readBytes()
            val runs = DoubleArray(TABLE_REPEATS) {
                val started = System.nanoTime()
                checkNotNull(parse(bytes)) { "$asset did not parse" }
                (System.nanoTime() - started) / 1e6
            }
            val first = runs[0]
            runs.sort()
            println(
                "LJ_BENCH bundle=${bundle.name} asset=$asset first_parse_ms=${"%.1f".format(first)} " +
                    "median_parse_ms=${"%.1f".format(runs[TABLE_REPEATS / 2])} bytes=${bytes.size}",
            )
        }
        time("music_entities_250k.bin") { MusicEntityIndex.parse(it) }
        time("artist_knowledge.bin") { ArtistKnowledgePack.parse(it) }
        time("artist_adapter.bin") { ArtistAdapter.parse(it) }
    }

    private fun graphs(): List<Pair<String, (OrtEnvironment) -> Map<String, OnnxTensor>>> {
        val random = Random(7)
        fun unit(size: Int): FloatArray {
            val v = FloatArray(size) { random.nextFloat() - 0.5f }
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(size) { v[it] / norm }
        }
        fun tensor(env: OrtEnvironment, values: FloatArray, vararg shape: Long) =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)
        return listOf(
            "mnv4_audio.onnx" to { env ->
                val wave = FloatArray(320_000) { (0.1 * sin(2 * Math.PI * 440 * it / 32_000)).toFloat() }
                mapOf("waveform" to tensor(env, wave, 1, 320_000))
            },
            "universal_semantic_head.onnx" to { env ->
                mapOf("embedding" to tensor(env, unit(960) + unit(960), 2, 960))
            },
            "text_encoder.onnx" to { env ->
                val ids = longArrayOf(2, 1500, 3000, 47, 900, 12, 44, 1201, 77, 3)
                val shape = longArrayOf(1, ids.size.toLong())
                mapOf(
                    "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape),
                    "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape),
                    "token_type_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape),
                )
            },
            "predictor_state.onnx" to { env ->
                val small = FloatArray(4 * 961)
                for (slot in 0 until 4) { unit(960).copyInto(small, slot * 961); small[slot * 961 + 960] = 1f }
                mapOf(
                    "history_small" to tensor(env, small, 1, 4, 961),
                    "history_medium" to tensor(env, unit(960), 1, 960),
                    "history_large" to tensor(env, unit(960), 1, 960),
                    "time_features" to tensor(env, floatArrayOf(0.5f, 0.8f, -0.3f, 0.9f, 0f), 1, 5),
                    "session_features" to tensor(env, floatArrayOf(1f, 0f, 2f, 0.9f, 0.9f), 1, 5),
                )
            },
            "predictor_scorer_n100.onnx" to { env ->
                val candidates = FloatArray(100 * 1344)
                for (row in 0 until 100) {
                    unit(960).copyInto(candidates, row * 1344)
                    unit(384).copyInto(candidates, row * 1344 + 960)
                }
                mapOf(
                    "state" to tensor(env, unit(960) + unit(384), 1, 1344),
                    "candidates" to tensor(env, candidates, 1, 100, 1344),
                )
            },
        )
    }

    private companion object {
        const val REPEATS = 21
        const val TABLE_REPEATS = 7
    }
}
