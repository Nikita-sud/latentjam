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
import java.nio.FloatBuffer
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [PredictorRuntime]: two ONNX Runtime sessions over the exported state encoder and
 * candidate scorer.
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
        if (stateSession != null && scorerSession != null) return Result.success(Unit)
        return try {
            val environment = OrtEnvironment.getEnvironment()
            stateSession = environment.createSession(
                context.assets.open(stateAsset).use { it.readBytes() },
            )
            scorerSession = environment.createSession(
                context.assets.open(scorerAsset).use { it.readBytes() },
            )
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
        val dim = PredictorRuntime.STATE_DIM.toLong()

        val small = tensor(environment, historySmall, 1, k, token)
        val medium = tensor(environment, historyMedium, 1, dim)
        val large = tensor(environment, historyLarge, 1, dim)
        val time = tensor(environment, timeFeatures, 1, timeFeatures.size.toLong())
        val session5 = tensor(environment, sessionFeatures, 1, sessionFeatures.size.toLong())

        return try {
            session.run(
                mapOf(
                    "history_small" to small,
                    "history_medium" to medium,
                    "history_large" to large,
                    "time_features" to time,
                    "session_features" to session5,
                ),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0].copyOf()
            }
        } finally {
            small.close(); medium.close(); large.close(); time.close(); session5.close()
        }
    }

    override fun score(state: FloatArray, candidates: FloatArray): FloatArray {
        val session = scorerSession ?: throw SmartEngineException(EngineError.ModelUnavailable)
        val environment = OrtEnvironment.getEnvironment()
        val stateTensor = tensor(environment, state, 1, PredictorRuntime.STATE_DIM.toLong())
        val candidateTensor = tensor(
            environment, candidates,
            1, PredictorRuntime.POOL_SIZE.toLong(), PredictorRuntime.STATE_DIM.toLong(),
        )
        return try {
            session.run(mapOf("state" to stateTensor, "candidates" to candidateTensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0].copyOf()
            }
        } finally {
            stateTensor.close(); candidateTensor.close()
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
