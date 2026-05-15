/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.oxycblt.musikr.Music

/**
 * One row per finalized track listen. Schema mirrors the synthetic listening-event format in
 * latentjam-research/scripts/synthesize_listening.py so the on-device retrain pass can rebuild
 * `TrainPair` tuples without re-encoding audio.
 *
 * `completed` is true iff `playedMs >= 0.9 * trackDurationMs` (matches the research recipe).
 * `skipped` is `!completed`. The `ctxUid0..3` fields persist the last 4 same-session
 * predecessors so `history_small` reconstruction is a single row read.
 */
@Entity(indices = [Index("sessionId"), Index("startedAtMs"), Index("songUid")])
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songUid: Music.UID,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val playedMs: Long,
    val trackDurationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val sessionId: String,
    val sessionPos: Int,
    val parentUid: Music.UID?,
    val ctxUid0: Music.UID?,
    val ctxUid1: Music.UID?,
    val ctxUid2: Music.UID?,
    val ctxUid3: Music.UID?,
    /**
     * True iff the track was queued by `RecommendationEngine` (SMART shuffle), false
     * if the user manually picked or it came from a non-SMART queue. Phase 1 training
     * cares about the difference: only `was_smart_pick = true` rows tell us anything
     * about model quality.
     */
    val wasSmartPick: Boolean = false,
    /**
     * Why this listen ended. Distinguishes "track played out naturally" from "user
     * actively moved off" — `completed`/`skipped` alone conflate these (a Next-tap at
     * 99 % of the track is `completed=true, skipped=false` today, but it's a soft
     * rejection, not full acceptance). Values: `TRACK_ENDED`, `USER_SKIPPED`,
     * `SESSION_END`, `NEW_PLAYBACK`.
     */
    val finalizeReason: String = "UNKNOWN",
    /** `OFF` / `SHUFFLE` / `SMART` — captured at finalize time. */
    val shuffleMode: String = "OFF",
    /** `NONE` / `TRACK` / `ALL`. Useful for filtering queue-driven repeats. */
    val repeatMode: String = "NONE",
    /**
     * True iff this track started because the user triggered `onNewPlayback` (tapped
     * a song / opened a parent / SMART injected a new context). False if it played
     * because the queue advanced. Captures user agency — manually-started tracks are
     * a stronger positive signal than queue-advance acceptance.
     */
    val wasUserStarted: Boolean = false,
    /**
     * True iff the song was in the user's `LikedSongEntity` table at finalize time.
     * Explicit favorite signal — the strongest positive label the predictor has.
     * Stamped on each event so retraining can use point-in-time liked state.
     */
    val liked: Boolean = false,
)
