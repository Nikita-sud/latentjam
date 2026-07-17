/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Android [EmbeddingBackend] — currently a STUB that reports
 * [EngineError.ModelUnavailable] for every operation, so the app degrades
 * gracefully (smart shuffle disabled) until the real runtime lands.
 *
 * ### Where the real implementation goes (TODO)
 * This class is the single place Android tensor code will live. The intended
 * shape, kept out of the common layer on purpose:
 *
 * 1. **Runtime**: ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`).
 *    - [loadModel]: create one process-wide `OrtEnvironment`, then an
 *      `OrtSession` from the model bytes resolved via
 *      [SmartEngineConfig.modelLocator] (an `assets/` path or an absolute
 *      file path). Register the QNN execution provider for Hexagon-NPU
 *      offload on Snapdragon, falling back to CPU when unavailable.
 *    - Session options: a single intra-op thread is enough here — the engine
 *      already serializes calls on a dedicated dispatcher.
 * 2. **Input pipeline**: [embed] decodes a short window of audio from
 *    [TrackDescriptor.audioUri] (MediaCodec/MediaExtractor or a custom
 *    decoder), resamples to the encoder's expected rate, computes the
 *    mel-spectrogram, wraps it in an `OnnxTensor`, runs the CNN encoder, and
 *    returns the 512-dim embedding as a defensively copied [FloatArray].
 * 3. **Errors**: every ORT exception is mapped to
 *    `Result.failure(SmartEngineException(EngineError.BackendFailure(...)))` —
 *    never rethrown across the common boundary (see [EmbeddingBackend]).
 * 4. **[close]**: close the `OrtSession` (idempotently); keep the
 *    `OrtEnvironment` if shared, close it if owned.
 *
 * No ONNX/TFLite dependency exists in this module yet — adding the runtime is
 * a deliberate, separate change.
 */
internal class AndroidEmbeddingBackend(
    @Suppress("unused") // Consumed by the real implementation (model location, dim checks).
    private val config: SmartEngineConfig,
) : EmbeddingBackend {

    override suspend fun loadModel(): Result<Unit> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> =
        Result.failure(SmartEngineException(EngineError.ModelUnavailable))

    override fun close() {
        // Nothing to release in the stub. Real implementation: close the OrtSession.
    }
}

/**
 * Android `actual` of the platform seam declared in commonMain.
 * Resolved by the common Koin module — no Android-specific DI module exists.
 */
public actual fun createEmbeddingBackend(config: SmartEngineConfig): EmbeddingBackend =
    AndroidEmbeddingBackend(config)
