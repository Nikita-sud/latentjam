/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import org.koin.core.module.Module

/**
 * Platform seam for the similarity model: model loading and tensor operations
 * live behind this interface, implemented per platform (ONNX Runtime on
 * Android, Core ML / ONNX Runtime on iOS).
 *
 * ### Contract
 * - [embed] returns a vector of exactly [SmartEngineConfig.embeddingDim]
 *   floats (512 in production). The engine validates this and rejects
 *   misbehaving backends with [EngineError.BackendFailure].
 * - Failures are RETURNED, not thrown: wrap the typed reason as
 *   `Result.failure(SmartEngineException(error))`. Only cancellation is
 *   allowed to propagate as an exception.
 * - Implementations do not need to be thread-safe: the engine serializes all
 *   backend access on a single-parallelism background dispatcher.
 * - [close] must be idempotent and must release native resources (ORT
 *   sessions, mapped model files, …).
 */
public interface EmbeddingBackend {

    /**
     * Loads the model referenced by [SmartEngineConfig.modelLocator] into
     * memory, ready for [embed] calls. Called at most once per engine
     * initialization; heavy by design (tens of MB of weights).
     */
    public suspend fun loadModel(): Result<Unit>

    /**
     * Encodes one track into its embedding vector.
     *
     * Production backends decode audio from [TrackDescriptor.audioUri] and run
     * the CNN encoder; fallback strategies may embed from metadata fields.
     * The returned array is owned by the caller (no aliasing of internal
     * buffers).
     */
    public suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray>

    /**
     * Runs the optional compact semantic head over stored audio embeddings.
     *
     * The default keeps alternate/test backends source-compatible and means a missing head only
     * removes semantic routing; it never disables SMART audio similarity.
     */
    public suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    /** Releases all native resources held by the backend. Idempotent. */
    public fun close()
}

/**
 * Koin bindings for this platform's [EmbeddingBackend] — the module's single
 * platform seam (same idiom as the library and playback modules).
 *
 * Android's implementation resolves an `android.content.Context` from the
 * graph for asset access and audio decoding; the app's platform module must
 * bind one. The iOS actual has no extra requirements. Tests substitute a fake
 * by overriding the binding (Koin last-definition-wins) instead of touching
 * platform code.
 */
public expect fun smartEngineBackendModule(): Module
