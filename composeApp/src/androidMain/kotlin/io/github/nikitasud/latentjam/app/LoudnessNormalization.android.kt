/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import io.github.nikitasud.latentjam.playback.AndroidIntegratedLoudnessMeter
import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual fun loudnessMeterModule(): Module = module {
    single<TrackLoudnessMeter> { AndroidTrackLoudnessMeter(context = get()) }
}

internal actual val playbackGainControlsAvailable: Boolean = true

private class AndroidTrackLoudnessMeter(context: Context) : TrackLoudnessMeter {

    private val meter = AndroidIntegratedLoudnessMeter(context)

    override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? {
        val uri = track.audioUri?.takeIf { it.isNotBlank() } ?: return null
        return meter.measure(audioUri = uri, durationMs = track.durationMs)
    }
}

internal actual val boundaryFadeControlsAvailable: Boolean = true
