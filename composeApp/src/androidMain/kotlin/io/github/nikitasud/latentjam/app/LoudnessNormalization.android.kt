/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import io.github.nikitasud.latentjam.smart.AndroidAudioLoudnessMeter
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual fun loudnessMeterModule(): Module = module {
    single<TrackLoudnessMeter> { AndroidTrackLoudnessMeter(context = get()) }
}

private class AndroidTrackLoudnessMeter(context: Context) : TrackLoudnessMeter {

    private val meter = AndroidAudioLoudnessMeter(context)

    override suspend fun measureDb(track: TrackDescriptor): Float? {
        val uri = track.audioUri?.takeIf { it.isNotBlank() } ?: return null
        return meter.measureDb(audioUri = uri, durationMs = track.durationMs)
    }
}
