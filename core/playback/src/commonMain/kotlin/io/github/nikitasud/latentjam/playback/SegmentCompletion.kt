/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/*
 * What it means when the iOS file player's node says a scheduled segment is finished. The node says so when the
 * segment has played out, and also when it is emptied without having played: stopped here (told apart by a token), or
 * by the system stopping the engine. And the end of a track can come while nothing can move on: paused, or with the
 * output stopped. Pure, so the host tests hold every row; the engine and the controller only gather the facts.
 */

/** What the node's notice says about the segment itself. */
internal enum class SegmentEnd {
    /** A segment that was replaced here (a seek, a load): nothing. */
    STALE,

    /** The segment played to its end. */
    PLAYED_OUT,

    /** The system emptied the node mid-track: nothing is scheduled any more, and the track has not ended. */
    FLUSHED,
}

/**
 * [tokenCurrent]: the notice is for the segment now cued. A running engine only reports segments it played, also for a
 * file whose audio stops short of its stated length. On a stopped engine the notice is a real end only when the last
 * frame known to have sounded ([renderedFrame]) is within [toleranceFrames] of the segment's end ([segmentEndFrame]):
 * the stop then came with the end, and at most that much of the tail is lost.
 */
internal fun segmentEnd(
    tokenCurrent: Boolean,
    engineRunning: Boolean,
    renderedFrame: Long,
    segmentEndFrame: Long,
    toleranceFrames: Long,
): SegmentEnd = when {
    !tokenCurrent -> SegmentEnd.STALE
    engineRunning -> SegmentEnd.PLAYED_OUT
    renderedFrame >= segmentEndFrame - toleranceFrames -> SegmentEnd.PLAYED_OUT
    else -> SegmentEnd.FLUSHED
}

/** What the controller does with a row that played out. */
internal enum class ItemEnd {
    /** Not the row at the playhead any more: nothing. */
    IGNORE,

    /** Move on now: the next track, a repeat, or the overlap's track taking over. */
    ADVANCE,

    /** Nothing can move on now; the row is remembered as ended, and the next Play moves on instead of replaying it. */
    REMEMBER,
}

/**
 * [playing] is what the app means; [pauseFading]: a pause is still fading out, with the graph rendering;
 * [outputRunning]: the engine renders (always true for the Music-library player); [overlapSounding]: the next track
 * is already sounding under this one's end, and takes over even inside a pause's fade.
 */
internal fun itemEnd(
    currentItem: Boolean,
    playing: Boolean,
    pauseFading: Boolean,
    outputRunning: Boolean,
    overlapSounding: Boolean,
): ItemEnd = when {
    !currentItem -> ItemEnd.IGNORE
    playing && outputRunning -> ItemEnd.ADVANCE
    pauseFading && outputRunning && overlapSounding -> ItemEnd.ADVANCE
    else -> ItemEnd.REMEMBER
}

/** The whole table: what a node's notice comes to. */
internal enum class CompletionMeaning { IGNORE, CUE_AGAIN, ADVANCE, REMEMBER_ENDED }

internal fun completionMeaning(
    tokenCurrent: Boolean,
    engineRunning: Boolean,
    renderedFrame: Long,
    segmentEndFrame: Long,
    toleranceFrames: Long,
    currentItem: Boolean,
    playing: Boolean,
    pauseFading: Boolean,
    overlapSounding: Boolean,
): CompletionMeaning = when (segmentEnd(tokenCurrent, engineRunning, renderedFrame, segmentEndFrame, toleranceFrames)) {
    SegmentEnd.STALE -> CompletionMeaning.IGNORE
    SegmentEnd.FLUSHED -> CompletionMeaning.CUE_AGAIN
    SegmentEnd.PLAYED_OUT -> when (itemEnd(currentItem, playing, pauseFading, engineRunning, overlapSounding)) {
        ItemEnd.IGNORE -> CompletionMeaning.IGNORE
        ItemEnd.ADVANCE -> CompletionMeaning.ADVANCE
        ItemEnd.REMEMBER -> CompletionMeaning.REMEMBER_ENDED
    }
}

/**
 * Whether a Play on a row remembered as ended ([endRemembered]) plays out its last frame, so that its end is reported
 * again and playback moves on, instead of the finished track starting over. Only when there is something to move on
 * to: at the end of the queue a Play starts the last track again, as it always did.
 */
internal fun playsOutRememberedEnd(endRemembered: Boolean, repeatOne: Boolean, smart: Boolean, rowsAhead: Int): Boolean =
    endRemembered && (repeatOne || smart || rowsAhead > 0)
