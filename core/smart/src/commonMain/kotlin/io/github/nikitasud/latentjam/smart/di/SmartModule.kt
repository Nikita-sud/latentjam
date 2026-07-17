/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.di

import io.github.nikitasud.latentjam.smart.DefaultSimilarityEngine
import io.github.nikitasud.latentjam.smart.EmbeddingBackend
import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartEngineConfig
import io.github.nikitasud.latentjam.smart.VectorIndex
import io.github.nikitasud.latentjam.smart.createEmbeddingBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * - [EmbeddingBackend] — the platform stub/implementation. Tests override
 *   this binding with a fake instead of touching platform code.
 * - [SimilarityEngine] — THE engine singleton.
 *
 * ### Threading guarantee
 * The engine singleton is created lazily (Koin `single` default) — resolving
 * it is cheap and allocation-only, safe even on the main thread. All heavy
 * work happens inside its suspend functions, which the engine confines to
 * `Dispatchers.Default.limitedParallelism(1)`:
 * - background: never the UI thread, so the Compose UI cannot be blocked;
 * - parallelism 1: serializes native tensor operations, which keeps the
 *   future ONNX/Core ML session usage trivially safe.
 * Note `limitedParallelism` guarantees SERIALIZATION, not thread affinity —
 * consecutive tasks may hop between Default-pool threads. If a future backend
 * requires same-thread access (some native runtimes do), swap this binding to
 * a `newSingleThreadContext("smart-engine")`.
 *
 * ### Lifecycle
 * The singleton lives as long as the Koin application. The graph owner is
 * responsible for calling [SimilarityEngine.release] on teardown (Koin's
 * `onClose` hook cannot suspend, so release is deliberately explicit).
 */
public val smartEngineModule: Module = module {

    single { SmartEngineConfig() }

    single<CoroutineDispatcher>(smartEngineDispatcherQualifier) {
        Dispatchers.Default.limitedParallelism(1, "smart-engine")
    }

    single<VectorIndex> {
        InMemoryVectorIndex(dim = get<SmartEngineConfig>().embeddingDim)
    }

    single<EmbeddingBackend> {
        createEmbeddingBackend(config = get())
    }

    single<SimilarityEngine> {
        DefaultSimilarityEngine(
            backend = get(),
            index = get(),
            config = get(),
            dispatcher = get(smartEngineDispatcherQualifier),
        )
    }
}
