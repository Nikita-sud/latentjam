/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * Static configuration of the similarity engine.
 *
 * Provided once through dependency injection; treated as immutable for the
 * lifetime of the engine singleton. To customize, register your own instance
 * AFTER [io.github.nikitasud.latentjam.smart.di.smartEngineModule] in your
 * Koin application — Koin's last-definition-wins override replaces the default.
 *
 * @property embeddingDim Dimensionality of the track embeddings. Must match
 *   the output of the similarity model (512 for the production CNN encoder).
 *   The engine validates every backend-produced vector against this value.
 * @property modelLocator Platform-interpreted hint for where the model lives
 *   (an Android asset path, an iOS bundle resource name, an absolute file
 *   path, …). `null` lets the platform backend fall back to its built-in
 *   default location. The common layer never interprets this string.
 */
public data class SmartEngineConfig(
    public val embeddingDim: Int = 512,
    public val modelLocator: String? = null,
)
