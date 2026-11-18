/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/*
 * A seam without a gap on iOS: the next track of an album that runs on (a live record, a mix, the movements of one
 * work) is queued on the same player node right behind the current one, and the node plays the two sample after
 * sample (IosPlayerNodeFactsTest). Everything else keeps the plain seam, where the next track is loaded when the
 * current one has ended. Pure rules here; IosAudioEngine queues and IosPlaybackController adopts the row.
 */

/**
 * Whether the row after the current one may be queued behind it. Only with smooth transitions off (they overlap the
 * tracks instead), never for a track that follows itself or under repeat-one, never while the sleep timer holds the
 * player to the end of this track, and only for the next track of the same album: any other pair has silence at its
 * edges, and keeps the seam every other part of the app already knows.
 * The engine adds its own conditions (a local file of the same sample rate and channel count).
 */
internal fun gaplessJoinWanted(
    crossfadeMs: Long,
    repeatOne: Boolean,
    finishingTrack: Boolean,
    fileBackend: Boolean,
    nextIsLocalFile: Boolean,
    nextIsSameTrack: Boolean,
    continuesAlbum: Boolean,
): Boolean = crossfadeMs <= 0L && !repeatOne && !finishingTrack && fileBackend &&
    nextIsLocalFile && !nextIsSameTrack && continuesAlbum

/** Whether the current track is near enough to its end ([GAPLESS_LEAD_MS]) for the next one to be queued now. */
internal fun withinGaplessLead(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && durationMs - positionMs <= GAPLESS_LEAD_MS

/**
 * Where the queued track's segment "starts" on the node's clock: the clock counts on through the seam, so with the
 * previous segment begun at [previousSegmentStart] of a file [previousLength] frames long, frame f of the next file is
 * played at clock time f - (previousSegmentStart - previousLength).
 */
internal fun seamSegmentStart(previousSegmentStart: Long, previousLength: Long): Long = previousSegmentStart - previousLength

/** What the controller does when the engine says the queued track has begun. */
internal enum class SeamAdoption {
    /** The row that was queued is still the one that follows: it becomes the current row, already sounding. */
    ADOPT,

    /** The queue changed in the last moment, or the join is not known here: the row that should follow is loaded the plain way. */
    RELOAD,
}

internal fun seamAdoption(joinKnown: Boolean, queuedRowStillNext: Boolean, generationAsExpected: Boolean): SeamAdoption =
    if (joinKnown && queuedRowStillNext && generationAsExpected) SeamAdoption.ADOPT else SeamAdoption.RELOAD

/** How long before the end of a track its successor is queued: time to open the file, little time for the queue to change. */
internal const val GAPLESS_LEAD_MS: Long = 3_000L

/** How often the join is looked at near a seam, and at most how seldom far from one. */
internal const val GAPLESS_NEAR_TICK_MS: Long = 50L
internal const val GAPLESS_IDLE_TICK_MS: Long = 1_000L

/** When to look again: soon near the seam, else when the lead begins. */
internal fun gaplessTickMs(positionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L) return GAPLESS_IDLE_TICK_MS
    val untilLead = durationMs - GAPLESS_LEAD_MS - positionMs
    return untilLead.coerceIn(GAPLESS_NEAR_TICK_MS, GAPLESS_IDLE_TICK_MS)
}
