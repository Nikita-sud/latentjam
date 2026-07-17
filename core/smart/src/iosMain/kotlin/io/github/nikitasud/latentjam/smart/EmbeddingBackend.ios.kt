/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * iOS [EmbeddingBackend] — currently a STUB that reports
 * [EngineError.ModelUnavailable] for every operation, so the app degrades
 * gracefully (smart shuffle disabled) until the real runtime lands.
 *
 * ### Where the real implementation goes (TODO)
 * This class is the single place iOS tensor code will live. Two candidate
 * runtimes, decision deferred until the model format is frozen:
 *
 * 1. **ONNX Runtime (C/Objective-C API via cinterop)** — same `.onnx` asset
 *    as Android, one model artifact for both platforms. [loadModel] creates
 *    the ORT env + session from the bundle resource named by
 *    [SmartEngineConfig.modelLocator]; Core ML execution provider for ANE
 *    offload, CPU fallback.
 * 2. **Core ML** — convert the encoder to `.mlmodelc` at build time,
 *    [loadModel] instantiates `MLModel`, [embed] feeds an `MLMultiArray`.
 *    Best ANE utilization, at the cost of a second model artifact.
 *
 * Input pipeline for either: [embed] decodes audio from
 * [TrackDescriptor.audioUri] with `AVAudioFile`/`AVAssetReader`, resamples,
 * computes the mel-spectrogram (Accelerate/vDSP), runs the CNN encoder, and
 * returns the 512-dim embedding as a [FloatArray]. Every native error is
 * mapped to `Result.failure(SmartEngineException(EngineError.BackendFailure(...)))`
 * — never rethrown across the common boundary (see [EmbeddingBackend]).
 * [close] releases the session/model idempotently.
 *
 * One `iosMain` actual covers iosArm64 and iosSimulatorArm64 via the default
 * KMP hierarchy.
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

/**
 * iOS `actual` of the platform seam declared in commonMain.
 * Resolved by the common Koin module — no iOS-specific DI module exists.
 */
public actual fun createEmbeddingBackend(config: SmartEngineConfig): EmbeddingBackend =
    IosEmbeddingBackend(config)
