/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.SmartEngineException
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [PredictorRuntime] — not yet wired to a native inference engine.
 *
 * [load] fails with [EngineError.ModelUnavailable], which the chain treats as "no learned scorer":
 * it still builds a queue from the geometric terms (centered cosine, seed gravity, semantic
 * z-scores, metadata multipliers), just without the model's vote. Degraded, not broken.
 *
 * To finish this: link ONNX Runtime's C API via cinterop (or convert both graphs to Core ML) and
 * fill in the same tensor contract documented on [PredictorRuntime]. The chain itself is common
 * code and needs no iOS-side changes.
 */
internal class UnavailablePredictorRuntime : PredictorRuntime {

    override suspend fun load(): Result<Unit> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray = throw SmartEngineException(EngineError.ModelUnavailable)

    override fun score(state: FloatArray, candidates: FloatArray): FloatArray =
        throw SmartEngineException(EngineError.ModelUnavailable)

    override fun close() = Unit
}

public actual fun smartPredictorModule(): Module = module {
    single<PredictorRuntime> { UnavailablePredictorRuntime() }
}
