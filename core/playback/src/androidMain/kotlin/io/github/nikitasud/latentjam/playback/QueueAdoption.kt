/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * The rows of a queue the app's controller did not build, in the player's order: each track as the app already knows
 * it ([named], by media id), or else as the player's own item describes it ([provisional], by row). A row neither can
 * name is left out.
 *
 * A queue from Android Auto, a voice request or another app reaches the player through the service alone. Until the
 * controller takes its rows as its own source, the queue it built before still stands in for them: the saved session
 * names the old list, and leaving shuffle or SMART puts the old list back around the playing track.
 */
internal fun <T : Any> adoptedQueueRows(
    ids: List<String>,
    named: (String) -> T?,
    provisional: (Int) -> T?,
): List<T> = ids.indices.mapNotNull { row -> named(ids[row]) ?: provisional(row) }

/**
 * A move between two rows of the visible queue as a move in the player: [mediaIndices] holds the player's index of
 * each visible row. The two differ whenever the player holds a row the queue cannot show (one nothing names), so a
 * visible row number handed to the player as it is would move another row. Null when either row is not there or
 * nothing moves.
 */
internal fun visibleMoveInPlayer(mediaIndices: List<Int>, from: Int, to: Int): Pair<Int, Int>? {
    if (from == to) return null
    val fromMedia = mediaIndices.getOrNull(from) ?: return null
    val toMedia = mediaIndices.getOrNull(to) ?: return null
    return fromMedia to toMedia
}

/**
 * The rows of [ids] to take out of a queue so that only the kept ones ([keep]) remain, as runs of neighbouring rows
 * from the last run down: each run is one command to the player, and taking a later run out first leaves the
 * earlier ones where they were.
 *
 * One command per row made every removal rebuild the player's whole timeline and everything that listens to it. A
 * queue of ten thousand rows that lost several hundred files at once (a folder deleted outside the app) held the main
 * thread for seconds.
 */
internal fun queueRemovalRanges(ids: List<String>, keep: (String) -> Boolean): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    var end = -1
    for (index in ids.lastIndex downTo 0) {
        if (!keep(ids[index])) {
            if (end < 0) end = index
        } else if (end >= 0) {
            ranges += (index + 1)..end
            end = -1
        }
    }
    if (end >= 0) ranges += 0..end
    return ranges
}
