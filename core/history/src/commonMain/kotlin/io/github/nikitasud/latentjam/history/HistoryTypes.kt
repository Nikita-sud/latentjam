/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * One completed listening session for one track (emitted when playback moves
 * OFF the track, not while it plays).
 *
 * @property playedMs Furthest playhead position observed, a lower bound on
 *   listening time (forward seeks inflate it slightly; acceptable for v1).
 * @property completed Reached ≥85 % of the track duration.
 * @property skipped Not completed and abandoned before 30 s.
 * @property shuffleMode Shuffle mode active when the track STARTED
 *   ("OFF"/"ON"/"SMART"), for future SMART-quality evaluation.
 */
public data class ListenEvent(
    public val trackId: TrackId,
    public val startedAtMs: Long,
    public val playedMs: Long,
    public val trackDurationMs: Long?,
    public val completed: Boolean,
    public val skipped: Boolean,
    public val shuffleMode: String? = null,
) {
    /** Pipe-delimited v1 line format; ids are numeric strings on Android. */
    public fun serialize(): String = listOf(
        FORMAT_VERSION,
        trackId.value,
        startedAtMs.toString(),
        playedMs.toString(),
        trackDurationMs?.toString() ?: "",
        if (completed) "1" else "0",
        if (skipped) "1" else "0",
        shuffleMode ?: "",
    ).joinToString("|")

    public companion object {
        private const val FORMAT_VERSION = "v1"

        /** Returns `null` for corrupt or unknown-version lines (they are skipped). */
        public fun parse(line: String): ListenEvent? {
            val parts = line.split("|")
            if (parts.size != 8 || parts[0] != FORMAT_VERSION) return null
            return ListenEvent(
                trackId = TrackId(parts[1]),
                startedAtMs = parts[2].toLongOrNull() ?: return null,
                playedMs = parts[3].toLongOrNull() ?: return null,
                trackDurationMs = parts[4].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null },
                completed = parts[5] == "1",
                skipped = parts[6] == "1",
                shuffleMode = parts[7].takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** Aggregate per-track listening statistics. */
public data class TrackStats(
    public val plays: Int,
    public val completions: Int,
    public val skips: Int,
    public val totalPlayedMs: Long,
    public val lastPlayedAtMs: Long,
)
