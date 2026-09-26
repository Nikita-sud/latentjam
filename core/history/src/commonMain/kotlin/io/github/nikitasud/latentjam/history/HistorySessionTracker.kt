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
 *
 * Origin: the plan position and parent are those of the snapshot that opened the session, like the
 * shuffle mode. The start is bound from a [ListenStartSignal] instead, because a player can show a
 * new track a moment before it has reported how that track began — see [ListenStartSignal].
 */
public class HistorySessionTracker(
    private val completionThreshold: Double = 0.85,
    private val skipThresholdMs: Long = 30_000,
) {

    private var currentTrackId: TrackId? = null
    private var startedAtMs: Long = 0
    private var maxPositionMs: Long = 0
    private var lastPositionMs: Long = 0
    private var lastSnapshotAtMs: Long = 0
    private var lastSnapshotWasPlaying: Boolean = false
    private var lastSnapshotWasForwardSeek: Boolean = false
    private var listenedMs: Long = 0
    private var durationMs: Long? = null
    private var shuffleMode: String? = null
    private var start: ListenStart? = null
    private var startBound: Boolean = false
    private var lastBoundStartSequence: Long = Long.MIN_VALUE
    private var smartPlanPosition: Int? = null
    private var parentId: String? = null

    /** How the playback instance this session belongs to began, even when this session did not. */
    private var instanceStart: ListenStart? = null

    /** The flushed listen whose playback may still resume; see [flush]. */
    private var flushed: FlushedListen? = null

    /** A possible continuation of the flushed playback, confirmed when its old signal is bound. */
    private var resumedFrom: FlushedListen? = null

    private class FlushedListen(val trackId: TrackId, val positionMs: Long, val instanceStart: ListenStart?)

    /**
     * Observes one snapshot. Returns the finished session's event when the
     * track changed (including to nothing), else `null`.
     *
     * [start] is the player's latest report of how a playback instance began, [smartPlanPosition]
     * and [parentId] describe the track in this snapshot; see [ListenOrigin].
     */
    public fun onSnapshot(
        trackId: TrackId?,
        positionMs: Long,
        trackDurationMs: Long,
        currentShuffleMode: String?,
        nowMs: Long,
        isPlaying: Boolean = true,
        start: ListenStartSignal? = null,
        smartPlanPosition: Int? = null,
        parentId: String? = null,
    ): ListenEvent? {
        val finished = observe(
            trackId, positionMs, trackDurationMs, currentShuffleMode, nowMs, isPlaying,
            start, smartPlanPosition, parentId,
        )
        // After any session change, so a session opened by this snapshot can bind its signal.
        bindStart(start)
        return finished
    }

    private fun observe(
        trackId: TrackId?,
        positionMs: Long,
        trackDurationMs: Long,
        currentShuffleMode: String?,
        nowMs: Long,
        isPlaying: Boolean,
        startSignal: ListenStartSignal?,
        smartPlanPosition: Int?,
        parentId: String?,
    ): ListenEvent? {
        if (trackId == currentTrackId) {
            // An explicit same-track start can happen at any progress or with unknown duration.
            // Without a fresh signal, retain the near-end wrap heuristic for older transports.
            if (trackId != null && isPlaybackRestart(positionMs, startSignal)) {
                // As with a different-track transition, the position may already belong to the
                // new listen. Count this interval only as a tail, never also as forward progress.
                accumulateTransitionTail(nowMs)
                val finished = finishCurrent()
                if (isPlaying) {
                    startSession(
                        trackId, positionMs, trackDurationMs, currentShuffleMode, nowMs,
                        smartPlanPosition, parentId,
                    )
                } else {
                    currentTrackId = null
                }
                return finished
            }
            accumulateListening(positionMs, nowMs)
            if (positionMs > maxPositionMs) maxPositionMs = positionMs
            if (trackDurationMs > 0) durationMs = trackDurationMs
            lastPositionMs = positionMs.coerceAtLeast(0)
            lastSnapshotAtMs = nowMs
            lastSnapshotWasPlaying = isPlaying
            return null
        }
        // The new-track snapshot has no final old-track position. Account for at most one ticker
        // interval of sound after the last old snapshot; the cap prevents a clock jump or stalled
        // collector from inventing a long listen.
        accumulateTransitionTail(nowMs)
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
            startSession(
                trackId, positionMs, trackDurationMs, currentShuffleMode, nowMs,
                smartPlanPosition, parentId,
            )
        }
        return finished
    }

    /** Flushes the in-progress session (call on shutdown). */
    public fun flush(nowMs: Long? = null): ListenEvent? {
        // Leaving again while still paused has nothing to finish and must retain the last
        // playback's identity for when it actually resumes.
        val trackId = currentTrackId ?: return null
        if (nowMs != null) accumulateTransitionTail(nowMs)
        // The player may resume this same playback later without reporting a new start, since
        // nothing new began. Remember where it stopped, so that resumption is recognized.
        flushed = if (startBound) FlushedListen(trackId, lastPositionMs, instanceStart) else null
        val finished = finishCurrent()
        currentTrackId = null
        return finished
    }

    private fun finishCurrent(): ListenEvent? {
        val trackId = currentTrackId ?: return null
        // Finishing is a consuming operation. In particular, a queue-end `trackId == null`
        // snapshot can be followed by a new track later; leaving the old id here would emit the
        // same ended session a second time on that later transition.
        currentTrackId = null
        val duration = durationMs
        val completed = duration != null && duration > 0 &&
            maxPositionMs >= (duration * completionThreshold).toLong()
        val skipped = !completed && listenedMs < skipThresholdMs
        return ListenEvent(
            trackId = trackId,
            startedAtMs = startedAtMs,
            playedMs = maxPositionMs,
            trackDurationMs = duration,
            completed = completed,
            skipped = skipped,
            shuffleMode = shuffleMode,
            listenedMs = listenedMs,
            origin = ListenOrigin(start, smartPlanPosition, parentId),
        )
    }

    private fun isPlaybackRestart(positionMs: Long, signal: ListenStartSignal?): Boolean {
        val openingPosition = positionMs.coerceAtLeast(0)
        if (openingPosition > RESTART_OPENING_WINDOW_MS) return false
        // Android can publish the service's signal before the controller's playhead resets. Wait
        // for the opening position; otherwise the subsequent wrap would close a second session.
        // An unbound session was already opened by a track change or wrap, so a delayed signal
        // belongs to that session rather than starting yet another one. Unknown causes may just
        // re-describe a queue after a mode switch and do not prove a restart.
        if (startBound && signal != null && signal.trackId == currentTrackId && signal.start != null &&
            signal.sequence > lastBoundStartSequence
        ) return true
        val duration = durationMs ?: return false
        return maxPositionMs >= (duration * RESTART_MIN_PROGRESS).toLong() &&
            maxPositionMs - openingPosition >= RESTART_MIN_REWIND_MS
    }

    private fun startSession(
        trackId: TrackId,
        positionMs: Long,
        trackDurationMs: Long,
        currentShuffleMode: String?,
        nowMs: Long,
        smartPlanPosition: Int?,
        parentId: String?,
    ) {
        currentTrackId = trackId
        startedAtMs = nowMs
        maxPositionMs = positionMs.coerceAtLeast(0)
        lastPositionMs = maxPositionMs
        lastSnapshotAtMs = nowMs
        lastSnapshotWasPlaying = true
        lastSnapshotWasForwardSeek = false
        listenedMs = 0
        durationMs = trackDurationMs.takeIf { it > 0 }
        shuffleMode = currentShuffleMode
        start = null
        startBound = false
        instanceStart = null
        // A rewind may be a new selection whose start signal has not arrived yet. Even a small
        // rewind counts here: tolerating drift would mistake a restart after 500 ms for a resume.
        resumedFrom = flushed?.takeIf { it.trackId == trackId && lastPositionMs >= it.positionMs }
        flushed = null
        // The log rejects a line whose plan position or parent it could not have written, so a
        // value it could not reload is dropped here rather than costing the whole listen later.
        this.smartPlanPosition = smartPlanPosition?.takeIf { it >= 1 }
        this.parentId = parentId?.takeIf(String::isNotEmpty)
    }

    /**
     * Binds the open session to the first signal that describes its own track and that no earlier
     * session bound. A stale signal — the previous track's, or the previous play of this same track
     * after a repeat — therefore leaves the start unknown until the right one arrives.
     */
    private fun bindStart(signal: ListenStartSignal?) {
        val trackId = currentTrackId ?: return
        if (startBound || signal == null || signal.trackId != trackId) return
        if (signal.sequence <= lastBoundStartSequence) {
            // The flushed half already claimed this signal. The resumed half began no new playback:
            // its start stays unknown, yet it is bound, so the next explicit restart still counts.
            val resumed = resumedFrom ?: return
            if (signal.sequence != lastBoundStartSequence) return
            // At zero, a resume and a fresh selection look identical. Wait for either a new
            // signal or forward progress still carrying the old one before claiming that signal.
            if (lastPositionMs == 0L) return
            instanceStart = resumed.instanceStart
            if (instanceStart == ListenStart.USER_PICK) smartPlanPosition = null
            startBound = true
            return
        }
        start = signal.start
        instanceStart = signal.start
        // A direct choice of a planned queue row is the listener's intent, even if the queue still
        // carries the position SMART originally assigned it. The delayed signal can clarify this.
        if (start == ListenStart.USER_PICK) smartPlanPosition = null
        startBound = true
        lastBoundStartSequence = signal.sequence
    }

    /**
     * Counts only forward progress that occurred while the previous snapshot said playback was
     * active. A seek cannot add its jump because progress is capped by elapsed wall time; a pause
     * or backward seek cannot add time because there is no positive audio-position delta.
     */
    private fun accumulateListening(positionMs: Long, nowMs: Long) {
        if (!lastSnapshotWasPlaying) {
            lastSnapshotWasForwardSeek = false
            return
        }
        val elapsedMs = (nowMs - lastSnapshotAtMs).coerceAtLeast(0)
        val positionDeltaMs = (positionMs.coerceAtLeast(0) - lastPositionMs).coerceAtLeast(0)
        val heardMs = minOf(positionDeltaMs, elapsedMs)
        listenedMs = saturatingDurationAdd(listenedMs, heardMs)
        lastSnapshotWasForwardSeek = positionDeltaMs > elapsedMs + MAX_POSITION_DRIFT_MS
    }

    private fun accumulateTransitionTail(nowMs: Long) {
        // A large playhead jump is a seek, not proof that audio traversed that span. Until a later
        // same-track sample confirms ordinary forward progress, do not invent an unobserved tail.
        if (!lastSnapshotWasPlaying || lastSnapshotWasForwardSeek) return
        val elapsedMs = (nowMs - lastSnapshotAtMs).coerceIn(0, MAX_TRANSITION_TAIL_MS)
        listenedMs = saturatingDurationAdd(listenedMs, elapsedMs)
    }

    private companion object {
        const val RESTART_OPENING_WINDOW_MS: Long = 5_000
        const val RESTART_MIN_REWIND_MS: Long = 30_000
        const val RESTART_MIN_PROGRESS: Double = 0.75
        const val MAX_TRANSITION_TAIL_MS: Long = 1_000
        const val MAX_POSITION_DRIFT_MS: Long = 1_000
    }
}

/**
 * A player's report of how the playback instance of [trackId] began.
 *
 * The player may already show a new track while its latest signal still describes the previous
 * instance, which can be the same track again after a repeat. [sequence] increases with every
 * instance the player reports, so [HistorySessionTracker] binds each session only to a signal for
 * its own track that is newer than any signal an earlier session bound.
 *
 * @property start Null when the player saw the instance begin but could not tell how.
 */
public data class ListenStartSignal(
    public val sequence: Long,
    public val trackId: TrackId,
    public val start: ListenStart?,
)
