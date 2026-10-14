/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.IosInferenceProvider
import io.github.nikitasud.latentjam.smart.IosInferenceRegistry

/** Exports the SMART native-inference protocol and installer through the ComposeApp framework. */
public fun installIosInferenceProvider(provider: IosInferenceProvider): Unit =
    IosInferenceRegistry.install(provider)

public fun installIosMapEmbeddingProvider(
    provider: io.github.nikitasud.latentjam.smart.cluster.IosMapEmbeddingProvider,
): Unit {
    io.github.nikitasud.latentjam.smart.cluster.IosMapEmbeddingRegistry.provider = provider
}

/** Opt-in simulator/device verification, invoked by LATENTJAM_BATCH_DOTS_BENCHMARK in the Swift shell. */
public fun runIosBatchDotsDiagnostics(): String =
    io.github.nikitasud.latentjam.smart.runIosBatchDotsDiagnostics()
