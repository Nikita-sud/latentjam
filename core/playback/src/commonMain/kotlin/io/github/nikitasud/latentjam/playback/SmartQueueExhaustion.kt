/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * How many unplayed [NextTrackChooser.continuation] rows a SMART queue may hold at once.
 *
 * A continuation is not a recommendation, so it fills the gap rather than the lookahead. The limit
 * is on the queue, not on one top-up pass: a pass-local cap still lets consecutive passes stack
 * continuations until the whole lookahead is made of them, and the listener would then have to sit
 * through twenty of them after the engine had recovered. Three keeps the music going while leaving
 * the recommender first in line the moment it can answer.
 */
internal const val SMART_CONTINUATION_BATCH: Int = 3

/**
 * Whether the queue has room for another continuation row.
 *
 * Only what is still ahead of the playhead counts: continuations already heard are history, and
 * holding them against the budget would eventually silence a session the longer it went on.
 */
internal fun smartMayContinue(
    queueSize: Int,
    currentIndex: Int,
    isContinuation: (Int) -> Boolean,
): Boolean {
    if (queueSize <= 0) return true
    val firstUnplayed = (currentIndex + 1).coerceIn(0, queueSize)
    var unplayedContinuations = 0
    for (index in firstUnplayed until queueSize) {
        if (isContinuation(index) && ++unplayedContinuations >= SMART_CONTINUATION_BATCH) {
            return false
        }
    }
    return true
}

/**
 * Where playback resumes after a SMART queue that had already run dry gained rows.
 *
 * SMART tops up on queue transitions, so a moment where the recommender cannot answer ends the
 * queue — and nothing transitions afterwards, which used to make that transient abstention
 * permanent for the whole session. Retrying once playback has ENDED is what reopens the walk, but
 * the transport is then parked past the last row: appending alone plays nothing, and `play()` after
 * the final item is a no-op. The new first row has to be selected explicitly.
 *
 * Returns the index to seek to, or `null` when nothing should move: playback that is still running
 * (the ordinary top-up), a pass that appended nothing, and — deliberately — a transport with no
 * playback intent, so launch restore parking a finished session at its last row cannot start audio
 * the listener never asked for.
 */
internal fun smartResumeIndexAfterExhaustion(
    queueSizeBefore: Int,
    queueSizeAfter: Int,
    playbackEnded: Boolean,
    playWhenReady: Boolean,
): Int? = when {
    !playbackEnded || !playWhenReady -> null
    queueSizeBefore <= 0 -> null
    queueSizeAfter <= queueSizeBefore -> null
    else -> queueSizeBefore
}
