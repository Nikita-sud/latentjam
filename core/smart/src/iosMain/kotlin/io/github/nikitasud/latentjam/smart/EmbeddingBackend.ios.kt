/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [EmbeddingBackend] — currently a STUB that reports
 * [EngineError.ModelUnavailable] for every operation, so the app degrades
 * gracefully (smart shuffle falls back to random) until the real runtime lands.
 *
 * ### Where the real implementation goes (TODO)
 * Mirror of the Android backend's fixed model contract
 * (`waveform [1, 320000]` = 10 s mono 32 kHz → `embedding [1, 960]`,
 * L2-normalized in-graph):
 * 1. **ONNX Runtime (C/Objective-C API via cinterop)** — same `.onnx` asset as
 *    Android; Core ML execution provider for ANE offload, CPU fallback.
 * 2. Audio decode via `AVAudioFile`/`AVAssetReader` at 32 kHz mono; the same
 *    three deterministic windows (20 % / 50 % / 80 %), sum + L2-normalize.
 * Every native error maps to
 * `Result.failure(SmartEngineException(EngineError.BackendFailure(...)))`.
 */
internal class IosEmbeddingBackend(
    @Suppress("unused") // Consumed by the real implementation (model location, dim checks).
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    override suspend fun loadModel(): Result<Unit> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override fun close() {
        // Nothing to release in the stub. Real implementation: release the ORT session / MLModel.
    }
}

public actual fun smartEngineBackendModule(): Module = module {
    single<EmbeddingBackend> { IosEmbeddingBackend(config = get()) }
}
