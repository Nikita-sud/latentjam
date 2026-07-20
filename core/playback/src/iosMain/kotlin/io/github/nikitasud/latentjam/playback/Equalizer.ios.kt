/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun equalizerModule(): Module = module {
    single { IosAudioEngine() }
    single<EqualizerController> { get<IosAudioEngine>() }
}
