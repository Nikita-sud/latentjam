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
