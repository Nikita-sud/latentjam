/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Kotlin/Native's shared pool, confined so ONNX work remains strictly serial. */
internal actual fun createPlatformSmartEngineDispatcher(): CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1, "smart-engine")

/** A separate serial lane prevents layout work from blocking engine calls. */
internal actual fun createPlatformMapLayoutDispatcher(): CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(1, "smart-map-layout")
