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

    /**
     * Fused scorer: [state] is `[SCORER_INPUT_DIM]` (`960 audio ⊕ 384 text-centroid`) and
     * [candidates] is `[POOL_SIZE × SCORER_INPUT_DIM]` flattened (see `ScorerPacking`).
     */
    public fun score(
        state: FloatArray,
        candidates: FloatArray,
    ): FloatArray?

    public fun close()
}

/** Process-local handoff installed by the Swift app before Compose starts. */
public object IosInferenceRegistry {
    private var installed: IosInferenceProvider? = null
    private var generation: Long = 0L
    private var activeLeases: Int = 0

    public fun install(provider: IosInferenceProvider): Unit {
        installed?.close()
        generation++
        activeLeases = 0
        installed = provider
    }

    public fun current(): IosInferenceProvider? = installed

    /**
     * Acquires one engine-subsystem lease on the installed provider.
     *
     * The registry retains the app-owned provider itself after the final lease is released. Only
     * the provider's native sessions are closed, so a later engine initialization can lazily load
     * them again without requiring Swift to reinstall its provider object.
     */
    internal fun acquire(): IosInferenceLease? {
        val provider = installed ?: return null
        activeLeases++
        return IosInferenceLease(provider = provider, generation = generation)
    }

    internal fun providerFor(lease: IosInferenceLease): IosInferenceProvider? =
        installed?.takeIf { provider ->
            provider === lease.provider && lease.generation == generation
        }

    internal fun release(lease: IosInferenceLease): Unit {
        if (providerFor(lease) == null) return
        check(activeLeases > 0) { "iOS inference lease count underflow" }
        activeLeases--
        if (activeLeases == 0) installed?.close()
    }

    internal fun resetForTests(): Unit {
        installed?.close()
        installed = null
        generation++
        activeLeases = 0
    }
}

/** Idempotent ownership token for one iOS SMART subsystem. */
internal class IosInferenceLease internal constructor(
    internal val provider: IosInferenceProvider,
    internal val generation: Long,
) {
    private var released: Boolean = false

    internal fun currentProvider(): IosInferenceProvider? =
        if (released) null else IosInferenceRegistry.providerFor(this)

    internal fun release(): Unit {
        if (released) return
        released = true
        IosInferenceRegistry.release(this)
    }
}
