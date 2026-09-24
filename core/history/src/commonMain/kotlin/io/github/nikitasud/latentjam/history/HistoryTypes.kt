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
 * @property origin Who chose the track and from where. Null only for legacy events, recorded
 *   before origins were logged; see [ListenOrigin] for what each null field inside means.
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
    public val origin: ListenOrigin? = null,
) {
    /** Honest elapsed duration when recorded by v3, with a legacy playhead approximation. */
    public val effectiveListenedMs: Long get() = listenedMs ?: playedMs

    /**
     * Versioned line format. Arbitrary identifiers are hex-escaped before delimiters are added.
     *
     * An event with an [origin] is written as v4; one without stays v3, so a legacy event rewritten
     * by a restore or merge keeps saying that its origin is unknown rather than acquiring an empty
     * record that would claim it was observed.
     */
    public fun serialize(): String = buildList {
        add(if (origin == null) FORMAT_V3 else FORMAT_V4)
        add(trackId.value.encodeHex())
        add(startedAtMs.toString())
        add(playedMs.toString())
        add(trackDurationMs?.toString() ?: "")
        add(if (completed) "1" else "0")
        add(if (skipped) "1" else "0")
        add(shuffleMode?.encodeHex() ?: "")
        add(listenedMs?.toString() ?: "")
        origin?.let { origin ->
            add(origin.start?.name ?: "")
            add(origin.smartPlanPosition?.toString() ?: "")
            add(origin.parentId?.encodeHex() ?: "")
        }
    }.joinToString("|")

    public companion object {
        private const val FORMAT_V1 = "v1"
        private const val FORMAT_V2 = "v2"
        private const val FORMAT_V3 = "v3"
        private const val FORMAT_V4 = "v4"
        private const val V4_FIELDS = 12

        private fun isLaterVersion(version: String): Boolean =
            version.startsWith('v') && (version.drop(1).toIntOrNull() ?: 0) > 4

        /**
         * Returns `null` for corrupt or unknown-version lines (they are skipped).
         *
         * A line from a version newer than v4 is read as its v4 prefix, because later versions only
         * append fields. After a downgrade, a listen written by the newer build then keeps the fields
         * this build knows instead of disappearing the next time the log is rewritten.
         */
        public fun parse(line: String): ListenEvent? {
            val raw = line.split("|")
            val parts = if (isLaterVersion(raw[0]) && raw.size >= V4_FIELDS) {
                listOf(FORMAT_V4) + raw.subList(1, V4_FIELDS)
            } else {
                raw
            }
            val version = parts[0]
            val expectedFields = when (version) {
                FORMAT_V1, FORMAT_V2 -> 8
                FORMAT_V3 -> 9
                FORMAT_V4 -> V4_FIELDS
                else -> return null
            }
            if (parts.size != expectedFields) return null
            val id = when (version) {
                FORMAT_V1 -> parts[1]
                else -> parts[1].decodeHex() ?: return null
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
            val origin = if (version != FORMAT_V4) null else ListenOrigin(
                // A start this build does not know was written by a newer one: unknown, not corrupt.
                start = parts[9].takeIf(String::isNotEmpty)?.let { name ->
                    ListenStart.entries.firstOrNull { it.name == name }
                },
                smartPlanPosition = parts[10].takeIf(String::isNotEmpty)?.let {
                    it.toIntOrNull()?.takeIf { position -> position >= 1 } ?: return null
                },
                parentId = parts[11].takeIf(String::isNotEmpty)?.let { it.decodeHex() ?: return null },
            )
            return ListenEvent(
                trackId = TrackId(id),
                startedAtMs = startedAtMs,
                playedMs = playedMs,
                trackDurationMs = durationMs,
                completed = parts[5] == "1",
                skipped = parts[6] == "1",
                shuffleMode = mode,
                listenedMs = listenedMs,
                origin = origin,
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

/**
 * How a listen came about, as observed when the track started. Without it, a track the listener
 * tapped and one SMART queued look identical, and SMART-quality evaluation cannot separate new
 * intents from continuations.
 *
 * @property start How playback reached the track; null when the player did not report it (for
 *   example a queue restored after a restart, or a change made by another app).
 * @property smartPlanPosition Where the track sat in the SMART plan it was served from — 1 is the
 *   first track after the plan's seed. Null when SMART did not recommend it: the listener's own
 *   pick, a source-queue row, or a labelled continuation played while SMART abstained.
 * @property parentId Stable id of the collection the queue was started from, such as
 *   `playlist:<id>` or `album:<key>`; null when that source has no stable id. In SMART mode it stays
 *   that collection while SMART draws later tracks from the whole library; [smartPlanPosition] tells
 *   those tracks apart.
 */
public data class ListenOrigin(
    public val start: ListenStart? = null,
    public val smartPlanPosition: Int? = null,
    public val parentId: String? = null,
)

/** How playback reached a track. Persisted by name; a name this build does not know reads as null. */
public enum class ListenStart {
    /**
     * The listener started playback here: tapped this track in a list, search, For You, a menu or
     * the queue, or started a collection (Play, Shuffle) that opens with it.
     */
    USER_PICK,

    /** The previous track ended and playback moved on by itself. */
    AUTO_ADVANCE,

    /** The listener skipped forward and the queue supplied this track. */
    SKIP_NEXT,

    /** The listener went back to this track. */
    SKIP_PREVIOUS,

    /** Repeat-one played the same track again. */
    REPEAT,
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
