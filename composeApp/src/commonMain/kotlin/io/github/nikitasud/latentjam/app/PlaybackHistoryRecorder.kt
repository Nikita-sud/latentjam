/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.HistorySessionTracker
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
): Job = launch {
    val tracker = HistorySessionTracker()
    playback.state.collect { now ->
        tracker.onSnapshot(
            trackId = now.track?.id,
            positionMs = now.positionMs,
            trackDurationMs = now.durationMs,
            currentShuffleMode = now.shuffleMode.name,
            nowMs = epochMillis(),
        )?.let { finished -> history.record(finished) }
    }
}
