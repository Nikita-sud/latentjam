/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.IntegratedLoudness
import io.github.nikitasud.latentjam.playback.IosIntegratedLoudnessMeter
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual fun loudnessMeterModule(): Module = module {
    single<TrackLoudnessMeter> { IosTrackLoudnessMeter() }
}
internal actual val playbackGainControlsAvailable: Boolean = true
// The file player overlaps and fades its own tracks (IosPlaybackController); Music library items
// keep plain switches there.
internal actual val boundaryFadeControlsAvailable: Boolean = true

private class IosTrackLoudnessMeter : TrackLoudnessMeter {
    private val meter = IosIntegratedLoudnessMeter()
    override suspend fun measure(track: TrackDescriptor): IntegratedLoudness? {
        val uri = track.audioUri ?: return null
        return meter.measure(uri, track.durationMs)
    }
}
