/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Main-thread-owned record of how the playback instance at the playhead began.
 *
 * A transport command knows why the track it selects will play, but on Android the player reports
 * the resulting transition later, next to transitions no in-app command caused (a track ending, a
 * headset button). Commands therefore [announce] their intent, and whatever observes the player —
 * the playback service on Android, the controller itself on iOS — calls [begin] when a new instance
 * starts, passing its own reading of the transition for the rest.
 */
internal class PlaybackStartLedger {
    private var sequence = 0L
    private var announced: Announcement? = null

    /** The start of the instance at the playhead; null until the first one begins. */
    var current: PlaybackStart? = null
        private set

    /** The command about to run will make [target] current because of [cause]. */
    fun announce(cause: StartCause, target: TrackId) {
        announced = Announcement(cause, target)
    }

    /**
     * A new playback instance of [trackId] began. An announcement naming this track wins over
     * [observed]; either way the announcement is spent, so one that never came true — Next pressed
     * just as the track ended on its own — cannot label a later transition.
     */
    fun begin(trackId: TrackId, observed: StartCause?): PlaybackStart {
        val cause = announced?.takeIf { it.target == trackId }?.cause ?: observed
        announced = null
        return PlaybackStart(++sequence, trackId, cause).also { current = it }
    }

    /**
     * The playlist changed and [trackId] is now current. The same track still at the playhead with no
     * command announcing a new start is the playing instance re-described — a queue edit around it,
     * a generated cover — so it keeps its start; anything else began a new play.
     */
    fun playlistChanged(trackId: TrackId) {
        if (current?.trackId == trackId && announced?.target != trackId) return
        begin(trackId, observed = null)
    }

    private data class Announcement(val cause: StartCause, val target: TrackId)
}

/**
 * Reads a jump between queue rows that no in-app command announced — a headset, notification or car
 * button. Landing where the transport's Next or Previous would have gone is that skip; anywhere
 * else was chosen directly.
 */
internal fun seekStartCause(toIndex: Int, nextIndex: Int?, previousIndex: Int?): StartCause = when (toIndex) {
    nextIndex -> StartCause.SKIP_NEXT
    previousIndex -> StartCause.SKIP_PREVIOUS
    else -> StartCause.USER_PICK
}

/**
 * Books the row SMART just appended for [trackId]: [choice]'s plan position when SMART recommended
 * it. A continuation ([choice] null) is no recommendation, so it clears any position the same track
 * left behind in an earlier row.
 */
internal fun recordSmartPlanPosition(
    positions: MutableMap<TrackId, Int>,
    trackId: TrackId,
    choice: SmartChoice?,
) {
    val position = choice?.planPosition
    if (position != null) positions[trackId] = position else positions.remove(trackId)
}

/**
 * Published SMART plan positions, rebuilt only after structural queue edits — the same contract as
 * [PlaybackContinuationSnapshot]. A row that left the queue takes its position with it, so a track
 * the listener later queues by hand is not mistaken for SMART's pick.
 */
internal class PlaybackPlanPositionSnapshot {
    private var lastQueue: List<TrackDescriptor>? = null
    private var lastPositions: Map<TrackId, Int> = emptyMap()

    fun get(queue: List<TrackDescriptor>, positions: MutableMap<TrackId, Int>): Map<TrackId, Int> {
        if (queue === lastQueue && positions == lastPositions) return lastPositions
        if (positions.isNotEmpty()) {
            positions.keys.retainAll(queue.mapTo(HashSet()) { it.id })
        }
        lastQueue = queue
        lastPositions = positions.toMap()
        return lastPositions
    }
}
