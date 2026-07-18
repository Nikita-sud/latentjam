/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

/**
 * The three shuffle positions — LatentJam's signature control.
 *
 * [SMART] delegates next-track choice to a [NextTrackChooser] (backed by the
 * similarity engine in the app graph) and falls back to random when the
 * chooser abstains, so the mode is usable even before the model ships.
 */
public enum class ShuffleMode { OFF, ON, SMART }

/**
 * Snapshot of what is playing right now — the UI's single source of truth.
 * Position/progress tracking arrives with the full now-playing screen.
 */
public data class NowPlaying(
    public val track: TrackDescriptor? = null,
    public val isPlaying: Boolean = false,
    public val shuffleMode: ShuffleMode = ShuffleMode.OFF,
)

/**
 * Strategy for picking the next track in [ShuffleMode.SMART].
 *
 * Kept as a port so :core:playback never depends on the similarity engine —
 * the app graph adapts [io.github.nikitasud.latentjam.smart.SimilarityEngine]
 * into this shape. Return `null` to abstain; the controller then falls back
 * to a random candidate.
 */
public fun interface NextTrackChooser {
    public suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor?
}

/**
 * Playback port for the shared UI.
 *
 * Platform implementations own the real player (Media3/ExoPlayer behind a
 * media-session service on Android; AVQueuePlayer on iOS, later). All
 * functions are main-safe suspends; [state] is safe to collect anywhere.
 * Like every port in this codebase, no platform or player types appear here.
 */
public interface PlaybackController {

    /** Live now-playing snapshot. */
    public val state: StateFlow<NowPlaying>

    /**
     * Replaces the queue with [tracks] and starts playing the one at
     * [startIndex]. The list also becomes the candidate pool for
     * [ShuffleMode.SMART] selection.
     */
    public suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int)

    /** Pauses if playing, resumes if paused. No-op with an empty queue. */
    public suspend fun togglePlayPause()

    /** Skips to the next track (choosing one first in [ShuffleMode.SMART] if needed). */
    public suspend fun next()

    /** Returns to the previous track (or restarts the current one, player-standard). */
    public suspend fun previous()

    /** Advances OFF → ON → SMART → OFF and returns the new mode. */
    public suspend fun cycleShuffleMode(): ShuffleMode
}

/**
 * Koin bindings for [PlaybackController] on this platform. The Android actual
 * resolves an `android.content.Context` and a [NextTrackChooser] from the
 * graph; the app's modules must bind both.
 */
public expect fun playbackModule(): Module
