/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [PlaybackController] — currently a STUB so the shared UI compiles and
 * behaves sanely (shuffle mode cycles, nothing audible happens).
 *
 * ### Where the real implementation goes (TODO)
 * `AVQueuePlayer` + `MPNowPlayingInfoCenter`/`MPRemoteCommandCenter`:
 * `play` maps to a rebuilt `AVPlayerItem` queue from the descriptors'
 * `audioUri` file URLs; SMART mirrors the Android strategy (stay one item
 * ahead via the injected [NextTrackChooser], observing
 * `AVPlayerItemDidPlayToEndTime`); audio session category `.playback` for
 * background audio.
 */
internal class StubPlaybackController : PlaybackController {

    private val mutableState = MutableStateFlow(NowPlaying())
    override val state: StateFlow<NowPlaying> = mutableState.asStateFlow()

    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int) {
        // Reflect the selection in state so the shared UI is exercisable.
        mutableState.update { it.copy(track = tracks.getOrNull(startIndex), isPlaying = false) }
    }

    override suspend fun togglePlayPause() = Unit

    override suspend fun next() = Unit

    override suspend fun previous() = Unit

    override suspend fun cycleShuffleMode(): ShuffleMode {
        val next = when (mutableState.value.shuffleMode) {
            ShuffleMode.OFF -> ShuffleMode.ON
            ShuffleMode.ON -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }
        mutableState.update { it.copy(shuffleMode = next) }
        return next
    }
}

public actual fun playbackModule(): Module = module {
    single<PlaybackController> { StubPlaybackController() }
}
