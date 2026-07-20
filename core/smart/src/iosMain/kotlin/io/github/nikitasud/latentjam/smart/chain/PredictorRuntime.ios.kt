/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IosInferenceRegistry
import io.github.nikitasud.latentjam.smart.SmartEngineException
import org.koin.core.module.Module
import org.koin.dsl.module

/** iOS predictor backed by the ONNX Runtime provider installed by the Swift host. */
internal class IosPredictorRuntime : PredictorRuntime {

    override suspend fun load(): Result<Unit> {
        val provider = IosInferenceRegistry.current()
            ?: return Result.failure(SmartEngineException(EngineError.ModelUnavailable))
        val error = provider.loadPredictor()
        return if (error == null) Result.success(Unit) else failure(error)
    }

    override fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray = IosInferenceRegistry.current()?.encodeState(
        historySmall, historyMedium, historyLarge, timeFeatures, sessionFeatures,
    )?.takeIf { it.size == PredictorRuntime.STATE_DIM && it.all(Float::isFinite) }
        ?: throw SmartEngineException(EngineError.ModelUnavailable)

    override fun score(
        state: FloatArray,
        candidates: FloatArray,
        textState: FloatArray,
        textCandidates: FloatArray,
        textMask: FloatArray,
    ): FloatArray = IosInferenceRegistry.current()?.score(
        state, candidates, textState, textCandidates, textMask,
    )?.takeIf { it.size == PredictorRuntime.POOL_SIZE && it.all(Float::isFinite) }
        ?: throw SmartEngineException(EngineError.ModelUnavailable)

    override fun close(): Unit = Unit

    private fun <T> failure(message: String): Result<T> =
        Result.failure(SmartEngineException(EngineError.BackendFailure(message)))
}

public actual fun smartPredictorModule(): Module = module {
    single<PredictorRuntime> { IosPredictorRuntime() }
}
