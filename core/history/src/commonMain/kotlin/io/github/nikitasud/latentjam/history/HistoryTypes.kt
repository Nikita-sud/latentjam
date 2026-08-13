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
 * @property playedMs Furthest playhead position observed. Retained for completion, resume, and
 *   compatibility with v1/v2 history.
 * @property listenedMs Elapsed time actually heard, accumulated from playing position deltas and
 *   bounded by wall time. Null only for legacy events, where [playedMs] is the best approximation.
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
    public val listenedMs: Long? = null,
) {
    /** Honest elapsed duration when recorded by v3, with a legacy playhead approximation. */
    public val effectiveListenedMs: Long get() = listenedMs ?: playedMs

    /** Versioned line format. Arbitrary identifiers are hex-escaped before delimiters are added. */
    public fun serialize(): String = listOf(
        FORMAT_V3,
        trackId.value.encodeHex(),
        startedAtMs.toString(),
        playedMs.toString(),
        trackDurationMs?.toString() ?: "",
        if (completed) "1" else "0",
        if (skipped) "1" else "0",
        shuffleMode?.encodeHex() ?: "",
        listenedMs?.toString() ?: "",
    ).joinToString("|")

    public companion object {
        private const val FORMAT_V1 = "v1"
        private const val FORMAT_V2 = "v2"
        private const val FORMAT_V3 = "v3"

        /** Returns `null` for corrupt or unknown-version lines (they are skipped). */
        public fun parse(line: String): ListenEvent? {
            val parts = line.split("|")
            if (parts.size !in 8..9) return null
            val version = parts[0]
            if (version == FORMAT_V3 && parts.size != 9) return null
            if (version != FORMAT_V3 && parts.size != 8) return null
            val id = when (parts[0]) {
                FORMAT_V1 -> parts[1]
                FORMAT_V2, FORMAT_V3 -> parts[1].decodeHex() ?: return null
                else -> return null
            }
            val mode = parts[7].takeIf(String::isNotEmpty)?.let { value ->
                if (parts[0] != FORMAT_V1) value.decodeHex() ?: return null else value
            }
            val startedAtMs = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val playedMs = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val durationMs = parts[4].takeIf { it.isNotEmpty() }?.let {
                it.toLongOrNull()?.takeIf { duration -> duration >= 0 } ?: return null
            }
            if (parts[5] !in setOf("0", "1") || parts[6] !in setOf("0", "1")) return null
            val listenedMs = parts.getOrNull(8)?.takeIf(String::isNotEmpty)?.let {
                it.toLongOrNull()?.takeIf { duration -> duration >= 0 } ?: return null
            }
            return ListenEvent(
                trackId = TrackId(id),
                startedAtMs = startedAtMs,
                playedMs = playedMs,
                trackDurationMs = durationMs,
                completed = parts[5] == "1",
                skipped = parts[6] == "1",
                shuffleMode = mode,
                listenedMs = listenedMs,
            )
        }

        private fun String.encodeHex(): String = encodeToByteArray().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

        private fun String.decodeHex(): String? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { index ->
                    substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }
}

/** Adds non-negative listening durations without wrapping a corrupt/extreme total below zero. */
internal fun saturatingDurationAdd(left: Long, right: Long): Long =
    if (right >= Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

/** Aggregate per-track listening statistics. */
public data class TrackStats(
    public val plays: Int,
    public val completions: Int,
    public val skips: Int,
    public val totalPlayedMs: Long,
    public val lastPlayedAtMs: Long,
)
