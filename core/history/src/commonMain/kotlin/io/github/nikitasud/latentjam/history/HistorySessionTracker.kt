/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Pure state machine turning a stream of now-playing snapshots into
 * [ListenEvent]s. Feed it every observed snapshot; it emits an event exactly
 * when playback moves off a track. No coroutines, no platform types —
 * fully unit-testable.
 *
 * Classification: completed = furthest position ≥ [completionThreshold] of
 * duration; skipped = not completed and abandoned before [skipThresholdMs].
 */
public class HistorySessionTracker(
    private val completionThreshold: Double = 0.85,
    private val skipThresholdMs: Long = 30_000,
) {

    private var currentTrackId: TrackId? = null
    private var startedAtMs: Long = 0
    private var maxPositionMs: Long = 0
    private var durationMs: Long? = null
    private var shuffleMode: String? = null

    /**
     * Observes one snapshot. Returns the finished session's event when the
     * track changed (including to nothing), else `null`.
     */
    public fun onSnapshot(
        trackId: TrackId?,
        positionMs: Long,
        trackDurationMs: Long,
        currentShuffleMode: String?,
        nowMs: Long,
        isPlaying: Boolean = true,
    ): ListenEvent? {
        if (trackId == currentTrackId) {
            // Repeat-one and adjacent duplicate queue entries do not necessarily emit a different
            // TrackId. A near-end -> near-zero wrap is nevertheless a new playback instance and
            // must close the prior listen before opening another one. Treating every backward seek
            // as a restart would inflate history, so require both substantial prior progress and a
            // return to the opening seconds.
            if (trackId != null && isPlaybackRestart(positionMs)) {
                val finished = finishCurrent()
                if (isPlaying) {
                    startSession(trackId, positionMs, trackDurationMs, currentShuffleMode, nowMs)
                } else {
                    currentTrackId = null
                }
                return finished
            }
            if (positionMs > maxPositionMs) maxPositionMs = positionMs
            if (trackDurationMs > 0) durationMs = trackDurationMs
            return null
        }
        val finished = finishCurrent()
        // A parked track — restored into the player at launch, never actually started — must not
        // open a session: abandoning it later would be recorded as a skip the user never made.
        // Only playback opens a session; pausing mid-track hits the same-track branch above and
        // keeps the session it already has. `startedAtMs` is therefore the moment it PLAYED.
        if (trackId != null && !isPlaying) {
            currentTrackId = null
            return finished
        }
        if (trackId != null) {
            startSession(trackId, positionMs, trackDurationMs, currentShuffleMode, nowMs)
        }
        return finished
    }

    /** Flushes the in-progress session (call on shutdown). */
    public fun flush(): ListenEvent? {
        val finished = finishCurrent()
        currentTrackId = null
        return finished
    }

    private fun finishCurrent(): ListenEvent? {
        val trackId = currentTrackId ?: return null
        val duration = durationMs
        val completed = duration != null && duration > 0 &&
            maxPositionMs >= (duration * completionThreshold).toLong()
        val skipped = !completed && maxPositionMs < skipThresholdMs
        return ListenEvent(
            trackId = trackId,
            startedAtMs = startedAtMs,
            playedMs = maxPositionMs,
            trackDurationMs = duration,
            completed = completed,
            skipped = skipped,
            shuffleMode = shuffleMode,
        )
    }

    private fun isPlaybackRestart(positionMs: Long): Boolean {
        val duration = durationMs ?: return false
        val openingPosition = positionMs.coerceAtLeast(0)
        return openingPosition <= RESTART_OPENING_WINDOW_MS &&
            maxPositionMs >= (duration * RESTART_MIN_PROGRESS).toLong() &&
            maxPositionMs - openingPosition >= RESTART_MIN_REWIND_MS
    }

    private fun startSession(
        trackId: TrackId,
        positionMs: Long,
        trackDurationMs: Long,
        currentShuffleMode: String?,
        nowMs: Long,
    ) {
        currentTrackId = trackId
        startedAtMs = nowMs
        maxPositionMs = positionMs.coerceAtLeast(0)
        durationMs = trackDurationMs.takeIf { it > 0 }
        shuffleMode = currentShuffleMode
    }

    private companion object {
        const val RESTART_OPENING_WINDOW_MS: Long = 5_000
        const val RESTART_MIN_REWIND_MS: Long = 30_000
        const val RESTART_MIN_PROGRESS: Double = 0.75
    }
}
