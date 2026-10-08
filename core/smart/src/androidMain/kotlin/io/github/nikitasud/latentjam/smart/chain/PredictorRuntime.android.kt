/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.SmartEngineException
import io.github.nikitasud.latentjam.smart.createOrtSession
import java.nio.FloatBuffer
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [PredictorRuntime]: a state encoder and a single semantics-aware fused scorer
 * (`scoring-semtext-v1`). The scorer takes `960 audio ⊕ 384 text` on both its state and candidate
 * rows (see [ScorerPacking]); an all-zero text half is a trained text-dropout path, so a library
 * with no metadata-text degrades gracefully to the acoustic-only behaviour.
 *
 * The scorer graph has the pool size baked in (`n100`), so [PredictorRuntime.POOL_SIZE] is a model
 * property here, not a tuning knob — a short pool is zero-padded rather than resized.
 */
internal class OnnxPredictorRuntime(
    private val context: Context,
    private val stateAsset: String = STATE_ASSET,
    private val scorerAsset: String = SCORER_ASSET,
) : PredictorRuntime {

    private var stateSession: OrtSession? = null
    private var scorerSession: OrtSession? = null

    override suspend fun load(): Result<Unit> {
        if (stateSession != null && scorerSession != null) {
            return Result.success(Unit)
        }
        return try {
            stateSession = createOrtSession(context.assets.open(stateAsset).use { it.readBytes() })
            scorerSession = createOrtSession(context.assets.open(scorerAsset).use { it.readBytes() })
            Result.success(Unit)
        } catch (t: Throwable) {
            close()
            Result.failure(
                SmartEngineException(
                    EngineError.BackendFailure("Failed to load SMART predictor: ${t.message}", t),
                ),
            )
        }
    }

    override fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray {
        val session = stateSession ?: throw SmartEngineException(EngineError.ModelUnavailable)
        val environment = OrtEnvironment.getEnvironment()
        val k = PredictorRuntime.CONTEXT_K.toLong()
        val token = PredictorRuntime.TOKEN_DIM.toLong()
        val dim = PredictorRuntime.EMBEDDING_DIM.toLong()

        // Every tensor is created inside the try: a failure half-way through the list must not leave the
        // ones already created to the garbage collector's mercy.
        val created = ArrayList<OnnxTensor>(5)
        return try {
            created += tensor(environment, historySmall, 1, k, token)
            created += tensor(environment, historyMedium, 1, dim)
            created += tensor(environment, historyLarge, 1, dim)
            created += tensor(environment, timeFeatures, 1, timeFeatures.size.toLong())
            created += tensor(environment, sessionFeatures, 1, sessionFeatures.size.toLong())
            session.run(
                mapOf(
                    "history_small" to created[0],
                    "history_medium" to created[1],
                    "history_large" to created[2],
                    "time_features" to created[3],
                    "session_features" to created[4],
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result[0].value as Array<FloatArray>)[0].copyOf()
                validatedPredictorOutput(output, PredictorRuntime.EMBEDDING_DIM)
                    ?: throw SmartEngineException(EngineError.ModelUnavailable)
            }
        } finally {
            for (tensor in created) tensor.close()
        }
    }

    override fun score(
        state: FloatArray,
        candidates: FloatArray,
    ): FloatArray {
        val scorer = scorerSession ?: throw SmartEngineException(EngineError.ModelUnavailable)
        val environment = OrtEnvironment.getEnvironment()
        val created = ArrayList<OnnxTensor>(2)
        return try {
            created += tensor(environment, state, 1, PredictorRuntime.SCORER_INPUT_DIM.toLong())
            created += tensor(
                environment, candidates,
                1, PredictorRuntime.POOL_SIZE.toLong(), PredictorRuntime.SCORER_INPUT_DIM.toLong(),
            )
            scorer.run(
                mapOf(
                    "state" to created[0],
                    "candidates" to created[1],
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result[0].value as Array<FloatArray>)[0].copyOf()
                validatedPredictorOutput(output, PredictorRuntime.POOL_SIZE)
                    ?: throw SmartEngineException(EngineError.ModelUnavailable)
            }
        } finally {
            for (tensor in created) tensor.close()
        }
    }

    override fun close() {
        runCatching { stateSession?.close() }
        runCatching { scorerSession?.close() }
        stateSession = null
        scorerSession = null
    }

    private fun tensor(
        environment: OrtEnvironment,
        data: FloatArray,
        vararg shape: Long,
    ): OnnxTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)

    companion object {
        const val STATE_ASSET = "ml/predictor_state.onnx"
        const val SCORER_ASSET = "ml/predictor_scorer_n100.onnx"
    }
}

public actual fun smartPredictorModule(): Module = module {
    single<PredictorRuntime> { OnnxPredictorRuntime(get()) }
}
