/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.di

import io.github.nikitasud.latentjam.smart.DefaultSimilarityEngine
import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.NoopIndexStore
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartClock
import io.github.nikitasud.latentjam.smart.SmartEngineConfig
import io.github.nikitasud.latentjam.smart.VectorIndex
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.StringQualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Qualifier of the engine's confined background [CoroutineDispatcher] in the
 * Koin graph. Public so advanced consumers (and tests) can resolve or replace
 * the exact dispatcher the engine runs on.
 */
public val smartEngineDispatcherQualifier: StringQualifier = named("smart-engine-dispatcher")

/**
 * Qualifier of the chain's metadata-text vector index and its store. Separate from the audio
 * bindings because the two spaces differ in dimensionality and are persisted independently.
 */
public val smartTextIndexQualifier: StringQualifier = named("smart-text-index")

/**
 * Qualifier of the Map page's 2-D layout store. A third store rather than a field on an existing
 * one: the layout has its own version, its own invalidation trigger, and must survive an embedding
 * re-index untouched.
 */
public val smartLayoutQualifier: StringQualifier = named("smart-layout-index")

/**
 * Koin module providing the SMART similarity engine.
 *
 * 100% common code — the only platform seam is the
 * [createEmbeddingBackend] expect/actual factory resolved inside. Install it
 * in your `startKoin { modules(smartEngineModule, ...) }` on both platforms;
 * no platform-specific companion module is required.
 *
 * ### What is bound
 * - [SmartEngineConfig] — defaults; override by registering your own
 *   `single { SmartEngineConfig(...) }` in a module listed AFTER this one
 *   (Koin's last-definition-wins override, the default since Koin 3.2).
 * - A confined background dispatcher (see below), qualified by
 *   [smartEngineDispatcherQualifier].
 * - [VectorIndex] — the in-memory baseline implementation.
 * - [SimilarityEngine] — THE engine singleton.
 *
 * The platform's `EmbeddingBackend` binding is NOT here — install
 * [io.github.nikitasud.latentjam.smart.smartEngineBackendModule] alongside
 * this module (tests override that binding with a fake).
 *
 * ### Threading guarantee
 * The engine singleton is created lazily (Koin `single` default) — resolving
 * it is cheap and allocation-only, safe even on the main thread. All heavy
 * work happens inside its suspend functions, which the engine confines to a
 * platform-owned single-parallelism background dispatcher:
 * - background: never the UI thread, so the Compose UI cannot be blocked;
 * - parallelism 1: serializes native tensor operations, which keeps the
 *   future ONNX/Core ML session usage trivially safe.
 * Android gives that worker OS background priority, so a library scan cannot
 * compete equally with rendering merely because both are native work.
 *
 * ### Lifecycle
 * The singleton lives as long as the Koin application. The graph owner is
 * responsible for calling [SimilarityEngine.release] on teardown (Koin's
 * `onClose` hook cannot suspend, so release is deliberately explicit).
 */
public val smartEngineModule: Module = module {

    single { SmartEngineConfig() }

    single<CoroutineDispatcher>(smartEngineDispatcherQualifier) {
        createPlatformSmartEngineDispatcher()
    }

    single<VectorIndex> {
        InMemoryVectorIndex(dim = get<SmartEngineConfig>().embeddingDim)
    }

    // The chain's metadata-text vectors: same structure as the audio index, different
    // dimensionality, so it gets its own binding and its own persisted file.
    single<VectorIndex>(smartTextIndexQualifier) {
        InMemoryVectorIndex(dim = TextEncoder.TEXT_DIM)
    }

    // Overridden by platforms with a real store (Android's file-backed one,
    // bound in smartEngineBackendModule, listed after this module).
    single<IndexStore> { NoopIndexStore() }
    single<IndexStore>(smartTextIndexQualifier) { NoopIndexStore() }
    single<IndexStore>(smartLayoutQualifier) { NoopIndexStore() }

    single<SimilarityEngine> {
        DefaultSimilarityEngine(
            backend = get(),
            index = get(),
            store = get(),
            config = get(),
            dispatcher = get(smartEngineDispatcherQualifier),
            predictor = getOrNull<PredictorRuntime>(),
            textEncoder = getOrNull<TextEncoder>(),
            textIndex = get<VectorIndex>(smartTextIndexQualifier),
            textStore = get<IndexStore>(smartTextIndexQualifier),
            clock = getOrNull<SmartClock>() ?: SmartClock,
        )
    }
}

/** Platform scheduling policy for long-running local inference. */
internal expect fun createPlatformSmartEngineDispatcher(): CoroutineDispatcher
