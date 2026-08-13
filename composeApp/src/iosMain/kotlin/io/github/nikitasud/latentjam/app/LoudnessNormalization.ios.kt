/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual fun loudnessMeterModule(): Module = module {
    // iOS playback has no app-level gain stage yet; without application, measurement is waste.
    single<TrackLoudnessMeter> { NoopTrackLoudnessMeter }
}

private object NoopTrackLoudnessMeter : TrackLoudnessMeter {
    override suspend fun measureDb(track: TrackDescriptor): Float? = null
}
