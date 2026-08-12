/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IosInferenceLease
import io.github.nikitasud.latentjam.smart.IosInferenceRegistry
import io.github.nikitasud.latentjam.smart.SmartEngineException
import org.koin.core.module.Module
import org.koin.dsl.module

/** iOS predictor backed by the ONNX Runtime provider installed by the Swift host. */
internal class IosPredictorRuntime : PredictorRuntime {

    private var lease: IosInferenceLease? = null

    override suspend fun load(): Result<Unit> {
        val existingLease = lease?.takeIf { it.currentProvider() != null }
        if (existingLease == null) {
            lease?.release()
            lease = null
        }
        val activeLease = existingLease ?: IosInferenceRegistry.acquire()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val provider = activeLease.currentProvider() ?: run {
            if (existingLease == null) activeLease.release()
            return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        }
        val error = provider.loadPredictor()
        return if (error == null) {
            lease = activeLease
            Result.success(Unit)
        } else {
            if (existingLease == null) activeLease.release()
            failure(error)
        }
    }

    override fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray = lease?.currentProvider()?.encodeState(
        historySmall, historyMedium, historyLarge, timeFeatures, sessionFeatures,
    ).let { validatedPredictorOutput(it, PredictorRuntime.EMBEDDING_DIM) }
        ?: throw SmartEngineException(EngineError.ModelUnavailable)

    override fun score(
        state: FloatArray,
        candidates: FloatArray,
    ): FloatArray = lease?.currentProvider()?.score(state, candidates)
        .let { validatedPredictorOutput(it, PredictorRuntime.POOL_SIZE) }
        ?: throw SmartEngineException(EngineError.ModelUnavailable)

    override fun close(): Unit {
        lease?.release()
        lease = null
    }

    private fun <T> failure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))
}

public actual fun smartPredictorModule(): Module = module {
    single<PredictorRuntime> { IosPredictorRuntime() }
}
