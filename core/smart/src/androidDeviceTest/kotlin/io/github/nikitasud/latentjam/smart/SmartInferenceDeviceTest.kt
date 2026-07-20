/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.chain.OnnxPredictorRuntime
import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.text.OnnxTextEncoder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the exact production assets on an Android device/emulator. */
class SmartInferenceDeviceTest {

    @Test
    fun allProductionGraphsLoadAndRun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.targetContext
        val appContext = testContext.createPackageContext(APP_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val context = object : ContextWrapper(testContext) {
            override fun getAssets() = appContext.assets
        }

        val started = System.nanoTime()
        val audio = OnnxEmbeddingBackend(
            context,
            SmartEngineConfig(
                embeddingDim = 960,
                modelLocator = "ml/mnv4_audio.onnx",
                modelVersion = "device-smoke",
            ),
        )
        val audioLoadStarted = System.nanoTime()
        audio.loadModel().getOrThrow()
        val audioLoadMs = elapsedMs(audioLoadStarted)
        val wave = writeSineWave(File(testContext.cacheDir, "smart-smoke.wav"))
        val audioEmbedStarted = System.nanoTime()
        val embedding = audio.embed(
            TrackDescriptor(
                id = TrackId("smoke"),
                durationMs = 10_000,
                audioUri = Uri.fromFile(wave).toString(),
            ),
        ).getOrThrow()
        val audioEmbedMs = elapsedMs(audioEmbedStarted)
        assertEquals(960, embedding.size)
        assertTrue(embedding.all(Float::isFinite))
        assertTrue(norm(embedding) in 0.99f..1.01f)

        val text = OnnxTextEncoder(context)
        val textLoadStarted = System.nanoTime()
        text.load().getOrThrow()
        val textLoadMs = elapsedMs(textLoadStarted)
        val textEmbedStarted = System.nanoTime()
        val textEmbedding = requireNotNull(text.encode("Soul; Example Artist; 1972"))
        val textEmbedMs = elapsedMs(textEmbedStarted)
        assertEquals(384, textEmbedding.size)
        assertTrue(textEmbedding.all(Float::isFinite))
        assertTrue(norm(textEmbedding) in 0.99f..1.01f)

        val predictor = OnnxPredictorRuntime(context)
        val predictorLoadStarted = System.nanoTime()
        predictor.load().getOrThrow()
        val predictorLoadMs = elapsedMs(predictorLoadStarted)
        val stateScoreStarted = System.nanoTime()
        val state = predictor.encodeState(
            historySmall = FloatArray(4 * 961),
            historyMedium = FloatArray(960),
            historyLarge = FloatArray(960),
            timeFeatures = FloatArray(5),
            sessionFeatures = FloatArray(5),
        )
        assertEquals(960, state.size)
        assertTrue(state.all(Float::isFinite))

        val missing = predictor.score(
            state = state,
            candidates = FloatArray(100 * 960),
            textState = FloatArray(384),
            textCandidates = FloatArray(100 * 384),
            textMask = FloatArray(100),
        )
        val stateScoreMs = elapsedMs(stateScoreStarted)
        val maskedNoise = predictor.score(
            state = state,
            candidates = FloatArray(100 * 960),
            textState = FloatArray(384) { 0.25f },
            textCandidates = FloatArray(100 * 384) { -0.5f },
            textMask = FloatArray(100),
        )
        assertEquals(100, missing.size)
        assertTrue(missing.all(Float::isFinite))
        assertArrayEquals("masked text must be an exact audio fallback", missing, maskedNoise, 0f)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val warmStarted = System.nanoTime()
        repeat(WARM_STATE_SCORE_REPEATS) {
            val warmState = predictor.encodeState(
                historySmall = FloatArray(4 * 961),
                historyMedium = FloatArray(960),
                historyLarge = FloatArray(960),
                timeFeatures = FloatArray(5),
                sessionFeatures = FloatArray(5),
            )
            val warmScores = predictor.score(
                state = warmState,
                candidates = FloatArray(100 * 960),
                textState = FloatArray(384),
                textCandidates = FloatArray(100 * 384),
                textMask = FloatArray(100),
            )
            assertTrue(warmScores.all(Float::isFinite))
        }
        val warmStateScoreUs = (System.nanoTime() - warmStarted) /
            WARM_STATE_SCORE_REPEATS / 1_000
        println(
            "SMART_DEVICE_SMOKE ok audio=960 text=384 state=960 scores=100 " +
                "missing_text_exact=true elapsed_ms=$elapsedMs",
        )
        println(
            "SMART_DEVICE_TIMING audio_load_ms=$audioLoadMs audio_embed_ms=$audioEmbedMs " +
                "text_load_ms=$textLoadMs text_embed_ms=$textEmbedMs " +
                "predictor_load_ms=$predictorLoadMs first_state_score_ms=$stateScoreMs " +
                "warm_state_score_us=$warmStateScoreUs repeats=$WARM_STATE_SCORE_REPEATS",
        )
        predictor.close()
        text.close()
        audio.close()
    }

    private fun writeSineWave(file: File): File {
        val sampleRate = 32_000
        val samples = sampleRate * 10
        DataOutputStream(FileOutputStream(file)).use { output ->
            output.writeBytes("RIFF")
            output.writeLeInt(36 + samples * 2)
            output.writeBytes("WAVEfmt ")
            output.writeLeInt(16)
            output.writeLeShort(1)
            output.writeLeShort(1)
            output.writeLeInt(sampleRate)
            output.writeLeInt(sampleRate * 2)
            output.writeLeShort(2)
            output.writeLeShort(16)
            output.writeBytes("data")
            output.writeLeInt(samples * 2)
            repeat(samples) { index ->
                val value = (sin(2 * PI * 440 * index / sampleRate) * Short.MAX_VALUE * 0.1).toInt()
                output.writeLeShort(value)
            }
        }
        return file
    }

    private fun DataOutputStream.writeLeInt(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
        writeByte(value ushr 16)
        writeByte(value ushr 24)
    }

    private fun DataOutputStream.writeLeShort(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
    }

    private fun norm(values: FloatArray): Float =
        sqrt(values.sumOf { it.toDouble() * it }).toFloat()

    private fun elapsedMs(started: Long): Long =
        (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val APP_PACKAGE = "io.github.nikitasud.latentjam.kmp"
        const val WARM_STATE_SCORE_REPEATS = 5
    }
}
