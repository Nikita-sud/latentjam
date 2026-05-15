/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * SessionTracker.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.session

import io.github.nikitasud.latentjam.ml.data.ListeningEventEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.musikr.Music

/**
 * Tracks the user's current listening session and exposes the session features the recommender's
 * state encoder consumes. Mirrors the recipe in
 * `latentjam-research/src/predictor/train_history.py`:
 *
 * session_features =
 * [ log(session_pos+1), was_last_skipped, log(time_since_session_start_min+1), recent_completion_rate, // NB: scoring_v1 was trained with session_feat_dim=4 (no mean_played_pct) ]
 *
 * The 5th field (`mean_played_pct`) is included in the public API so newer predictor checkpoints
 * can opt in without refactoring the recorder. The `PredictorRuntime` truncates to whatever
 * `session_feat_dim` the loaded ONNX expects.
 *
 * Sessions break on a 30-minute idle gap, mirroring `SESSION_GAP_MS` in train_history.py.
 */
@Singleton
class SessionTracker @Inject constructor() {
    private val lock = Any()

    @Volatile private var sessionId: String = newId()
    @Volatile private var sessionStartedAtMs: Long = 0L
    @Volatile private var sessionPos: Int = 0
    @Volatile private var lastEventEndedAtMs: Long = 0L
    @Volatile private var lastEventWasSkipped: Boolean = false

    private val recentResults: ArrayDeque<RecentResult> = ArrayDeque()

    /**
     * Newest-first ring buffer of the last K=4 completed plays in the current session, mirroring
     * what `history_small` needs. Populated by the recorder on every finalize regardless of the
     * privacy opt-in, so Smart shuffle can run without the user enabling persistent event logging.
     */
    private val recentCompleted: ArrayDeque<CompletedPlay> = ArrayDeque()

    /** Called when a new track index becomes the current one. */
    fun onTrackStart(nowMs: Long): TrackContext =
        synchronized(lock) {
            val sinceLast = if (lastEventEndedAtMs == 0L) 0L else nowMs - lastEventEndedAtMs
            if (sessionStartedAtMs == 0L || sinceLast >= SESSION_GAP_MS) {
                sessionId = newId()
                sessionStartedAtMs = nowMs
                sessionPos = 0
                lastEventWasSkipped = false
                recentResults.clear()
                recentCompleted.clear()
            }
            TrackContext(
                sessionId = sessionId,
                sessionPos = sessionPos,
                startedAtMs = nowMs,
                sessionStartedAtMs = sessionStartedAtMs,
            )
        }

    /** Called after a track is finalized so subsequent feature reads see the result. */
    fun onTrackFinalized(event: ListeningEventEntity): Unit =
        synchronized(lock) {
            sessionPos += 1
            lastEventEndedAtMs = event.endedAtMs
            lastEventWasSkipped = event.skipped
            val playedPct =
                event.trackDurationMs
                    .takeIf { it > 0 }
                    ?.let { event.playedMs.toFloat() / it.toFloat() }
                    ?.coerceIn(0f, 1f) ?: 0f
            recentResults.addLast(RecentResult(completed = event.completed, playedPct = playedPct))
            while (recentResults.size > RECENT_WINDOW) recentResults.removeFirst()
            if (event.completed) {
                recentCompleted.addFirst(CompletedPlay(uid = event.songUid, playedPct = playedPct))
                while (recentCompleted.size > CONTEXT_K) recentCompleted.removeLast()
            }
        }

    /** Last K=4 completed plays, newest first. Used by the recommender's history_small builder. */
    fun recentCompletedSnapshot(): List<CompletedPlay> =
        synchronized(lock) { recentCompleted.toList() }

    /** Called when playback stops entirely. Does NOT advance `sessionPos`. */
    fun onSessionEnded(): Unit =
        synchronized(lock) {
            // Keep the data so the next track-start within SESSION_GAP_MS can reuse the session;
            // the gap check in onTrackStart will produce a fresh id once that boundary is crossed.
        }

    /** Snapshot suitable for the predictor `session_features` tensor. */
    fun snapshot(nowMs: Long): SessionSnapshot =
        synchronized(lock) {
            val minutesSinceStart =
                if (sessionStartedAtMs == 0L) {
                    0.0
                } else {
                    max(0L, nowMs - sessionStartedAtMs) / 60_000.0
                }
            val completionRate =
                if (recentResults.isEmpty()) {
                    0f
                } else {
                    recentResults.count { it.completed }.toFloat() / recentResults.size
                }
            val meanPlayedPct =
                if (recentResults.isEmpty()) {
                    0f
                } else {
                    recentResults.map { it.playedPct }.average().toFloat()
                }
            SessionSnapshot(
                sessionId = sessionId,
                sessionPos = sessionPos,
                sessionStartedAtMs = sessionStartedAtMs,
                features =
                    floatArrayOf(
                        ln((sessionPos + 1).toFloat()),
                        if (lastEventWasSkipped) 1f else 0f,
                        ln((minutesSinceStart + 1.0).toFloat()),
                        completionRate,
                        meanPlayedPct,
                    ),
                completedThisSession =
                    recentResults
                        .count { it.completed }
                        .let {
                            // Account for completed plays that have already aged out of the recent
                            // window:
                            // session_pos is incremented for every finalized event, recent_results
                            // is
                            // capped at RECENT_WINDOW. Use sessionPos as a lower bound when the
                            // window
                            // has filled with completed-only events.
                            min(it, sessionPos).coerceAtLeast(0)
                        },
            )
        }

    private data class RecentResult(val completed: Boolean, val playedPct: Float)

    data class CompletedPlay(val uid: Music.UID, val playedPct: Float)

    data class TrackContext(
        val sessionId: String,
        val sessionPos: Int,
        val startedAtMs: Long,
        val sessionStartedAtMs: Long,
    )

    data class SessionSnapshot(
        val sessionId: String,
        val sessionPos: Int,
        val sessionStartedAtMs: Long,
        /** session_features (5,) — last entry is `mean_played_pct`, ignored by k4-d512 v1. */
        val features: FloatArray,
        /** Used by the recommendation cold-start gate. */
        val completedThisSession: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SessionSnapshot
            return sessionId == other.sessionId &&
                sessionPos == other.sessionPos &&
                sessionStartedAtMs == other.sessionStartedAtMs &&
                completedThisSession == other.completedThisSession &&
                features.contentEquals(other.features)
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + sessionPos
            result = 31 * result + sessionStartedAtMs.hashCode()
            result = 31 * result + completedThisSession
            result = 31 * result + features.contentHashCode()
            return result
        }
    }

    companion object {
        const val SESSION_GAP_MS: Long = 30L * 60L * 1000L
        const val RECENT_WINDOW = 8
        const val CONTEXT_K = 4

        private fun newId(): String = UUID.randomUUID().toString()
    }
}
