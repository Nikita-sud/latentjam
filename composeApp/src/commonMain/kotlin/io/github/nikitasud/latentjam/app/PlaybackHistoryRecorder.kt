/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.HistorySessionTracker
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bridges playback to the listening history — the single place the two meet
 * (mirror of [EngineNextTrackChooser] on the engine side). Collects the
 * now-playing snapshots, lets the pure [HistorySessionTracker] detect track
 * transitions, and records the finished sessions.
 *
 * The ticker inside the playback controller refreshes positions ~2×/s while
 * playing, which keeps the tracker's furthest-position measurement honest.
 */
fun CoroutineScope.launchPlaybackHistoryRecorder(
    playback: PlaybackController,
    history: ListeningHistory,
    enabled: StateFlow<Boolean>,
    onRecorded: () -> Unit = {},
): Job = launch {
    val gate = PlaybackHistoryGate(initiallyEnabled = enabled.value)
    playback.state.combine(enabled) { now, isEnabled -> now to isEnabled }.collect { (now, isEnabled) ->
        gate.onSnapshot(now, isEnabled, epochMillis())?.let { finished ->
            history.record(finished)
            onRecorded()
        }
    }
}

/**
 * Privacy boundary between the always-live player state and the durable listening log.
 *
 * Disabling history drops the in-progress session immediately. Re-enabling during a track waits for
 * the next track instead of treating the existing absolute playhead position as newly listened time.
 * This makes the switch honest even when it is changed with music already playing.
 */
internal class PlaybackHistoryGate(initiallyEnabled: Boolean) {

    private var recording = initiallyEnabled
    private var tracker = HistorySessionTracker()
    private var ignoredTrackAfterEnabling: TrackId? = null

    fun onSnapshot(now: NowPlaying, enabled: Boolean, nowMs: Long) = when {
        !enabled -> {
            if (recording) tracker = HistorySessionTracker()
            recording = false
            ignoredTrackAfterEnabling = null
            null
        }

        !recording -> {
            recording = true
            tracker = HistorySessionTracker()
            // A null current track arms the tracker immediately; otherwise begin with the next one.
            ignoredTrackAfterEnabling = now.track?.id
            null
        }

        ignoredTrackAfterEnabling == now.track?.id -> null

        else -> {
            ignoredTrackAfterEnabling = null
            tracker.onSnapshot(
                trackId = now.track?.id,
                positionMs = now.positionMs,
                trackDurationMs = now.durationMs,
                currentShuffleMode = now.shuffleMode.name,
                nowMs = nowMs,
                isPlaying = now.isPlaying,
            )
        }
    }
}
