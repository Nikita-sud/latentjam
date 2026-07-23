/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Native inference supplied by the thin Swift host.
 *
 * Keeping the bridge in the app target lets iOS use ONNX Runtime's supported Swift/Objective-C
 * distribution while the SMART engine remains ordinary Kotlin Multiplatform code. Every method is
 * synchronous because the engine already serializes model work on a background dispatcher.
 */
public interface IosInferenceProvider {

    /** Returns null on success, otherwise a human-readable local error. */
    public fun loadAudio(): String?

    /** Three-window pooled audio embedding for a local file URL/path. */
    public fun embedAudio(uri: String, durationMs: Long, outputDim: Int): FloatArray?

    /** Returns null on success, otherwise a human-readable local error. */
    public fun loadSemantic(): String?

    /** Batched `[batch, 960] -> [batch, 27]` universal semantic-head inference. */
    public fun classifySemantics(
        embeddings: FloatArray,
        batchSize: Int,
        inputDim: Int,
        outputDim: Int,
    ): FloatArray?

    /** Returns null on success, otherwise a human-readable local error. */
    public fun loadText(): String?

    /** MiniLM token embeddings, mean-pooled and L2-normalized by the native host. */
    public fun encodeText(inputIds: LongArray): FloatArray?

    /** Returns null on success, otherwise a human-readable local error. */
    public fun loadPredictor(): String?

    public fun encodeState(
        historySmall: FloatArray,
        historyMedium: FloatArray,
        historyLarge: FloatArray,
        timeFeatures: FloatArray,
        sessionFeatures: FloatArray,
    ): FloatArray?

    public fun score(
        state: FloatArray,
        candidates: FloatArray,
        textState: FloatArray,
        textCandidates: FloatArray,
        textMask: FloatArray,
    ): FloatArray?

    public fun close()
}

/** Process-local handoff installed by the Swift app before Compose starts. */
public object IosInferenceRegistry {
    private var installed: IosInferenceProvider? = null

    public fun install(provider: IosInferenceProvider): Unit {
        installed?.close()
        installed = provider
    }

    public fun current(): IosInferenceProvider? = installed
}
