/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shipped music encoder through every kernel path of LatentJam's 4-bit operator (libljq4): the CPU's
 * own (i8mm where present), dotprod (weights repacked at first use) and the portable loop. All three
 * accumulate the same integers, so their embeddings must agree exactly; the portable loop is the plain
 * reference. Reads the installed app's assets, like [SmartInferenceDeviceTest].
 */
class OrtOperatorsDeviceTest {

    @Test
    fun everyKernelPathEncodesTheSameEmbedding() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val appContext = testContext.createPackageContext(APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val model = appContext.assets.open("ml/mnv4_audio.onnx").use { it.readBytes() }
        val wave = FloatArray(WINDOW) {
            (0.1 * sin(2 * PI * 440 * it / RATE) + 0.05 * sin(2 * PI * 1_234 * it / RATE)).toFloat()
        }
        val embeddings = PATHS.associateWith { path ->
            // The operator picks its path when a session creates its kernels.
            if (path == null) Os.unsetenv(PATH_VARIABLE) else Os.setenv(PATH_VARIABLE, path, true)
            try {
                embed(model, wave)
            } finally {
                Os.unsetenv(PATH_VARIABLE)
            }
        }
        val detected = embeddings.getValue(null)
        assertEquals(960, detected.size)
        assertTrue(detected.all(Float::isFinite))
        assertEquals(1.0, sqrt(detected.sumOf { it.toDouble() * it }), 1e-3)
        for ((path, embedding) in embeddings) {
            val cosine = detected.indices.sumOf { detected[it].toDouble() * embedding[it] }
            println("LJQ4_PATHS path=${path ?: "detected"} cosine_to_detected=$cosine")
            // Parity is exact, not approximate: one requantization step moves a component by roughly
            // 0.004, which a cosine threshold cannot see. ljq4_test.cc pins the same byte-exact
            // agreement between the paths on the host (CheckPathsAgree).
            val mismatch = detected.indices.firstOrNull {
                detected[it].toRawBits() != embedding[it].toRawBits()
            }
            if (mismatch != null) {
                fail(
                    "path $path: component $mismatch is ${embedding[mismatch]} against " +
                        "${detected[mismatch]} (cosine $cosine)",
                )
            }
        }
    }

    private fun embed(model: ByteArray, wave: FloatArray): FloatArray {
        val environment = OrtEnvironment.getEnvironment()
        return createOrtSession(model, operators = true).use { session ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(wave), longArrayOf(1, WINDOW.toLong())).use { input ->
                session.run(mapOf("waveform" to input)).use { result ->
                    (result.get(0) as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }
                }
            }
        }
    }

    private companion object {
        const val APP_PACKAGE = "io.github.nikitasud.latentjam.kmp"
        const val PATH_VARIABLE = "LJ_Q4_PATH"
        const val WINDOW = 320_000
        const val RATE = 32_000.0
        val PATHS = listOf(null, "dotprod", "portable")
    }
}
