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
 *   the output of the similarity model (960 for the production MNv4 encoder;
 *   the app graph configures this). The engine validates every
 *   backend-produced vector against this value.
 * @property modelLocator Platform-interpreted hint for where the model lives
 *   (an Android asset path, an iOS bundle resource name, an absolute file
 *   path, …). `null` lets the platform backend fall back to its built-in
 *   default location. The common layer never interprets this string.
 * @property modelVersion Version string of the embedding model, used to key
 *   persisted index snapshots ([IndexStore]) — embeddings from different
 *   model versions must never mix. Keep in sync with the shipped model asset.
 * @property typicalityWeight EXPERIMENT FLAG, off at `0f`. How strongly the
 *   chain prefers tracks that are typical of the library, in standard
 *   deviations of that library's own spread. The chain measures every cosine
 *   in a mean-centered space, which discards how central a track is; measured
 *   against 717 real listening transitions that discarded axis predicts
 *   whether a track is kept at AUC 0.633, with keep rate climbing 0.393 →
 *   0.550 → 0.644 across terciles. Offline sweeps show no niche collapse up to
 *   `0.8f` (genre-family retention, language retention and cross-seed overlap
 *   all flat), but no offline judge can confirm it *helps* — the only
 *   validated predictor is typicality itself, so scoring it with typicality
 *   would be circular. It ships behind this flag to be settled on device.
 */
public data class SmartEngineConfig(
    public val embeddingDim: Int = 512,
    public val modelLocator: String? = null,
    public val modelVersion: String = "unversioned",
    public val typicalityWeight: Float = 0f,
)
