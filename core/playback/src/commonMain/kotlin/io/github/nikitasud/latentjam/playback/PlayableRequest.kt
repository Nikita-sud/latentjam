/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * What a controller's request comes to: [items] in the order asked, starting on row [startIndex] of
 * them at [startPositionMs]. A null row leaves the choice to the player; a null position starts the
 * track from its beginning.
 */
internal data class PlayableRequest<T>(
    val items: List<T>,
    val startIndex: Int?,
    val startPositionMs: Long?,
)

/**
 * The part of a controller's request that can play. [rows] are the items it asked for, in order,
 * each resolved to something playable, or null where nothing in the library matched: an unknown id,
 * a search with no hit, no library yet.
 *
 * Rows that cannot play are left out, and the start follows the row the request named, [startIndex]
 * (null leaves it to the player). When that row cannot play, the next row that can takes its place,
 * or the last one before it when none follows, from the track's beginning: the requested position
 * was a point in another track. A start past the end of the request lands on its last row the same
 * way. Media3 fills in the playing queue's row number for a controller that keeps its position, and
 * ExoPlayer rejects a row past the end only midway through replacing its queue, which leaves the
 * player stuck.
 *
 * Null when the request asked for rows and none of them can play. Installed as asked, that empty
 * list would stop playback and wipe the queue that was playing, so the request is refused instead.
 * A request for no rows at all passes as it came, as does one that can play in full.
 */
internal fun <T : Any> playableRequest(
    rows: List<T?>,
    startIndex: Int? = null,
    startPositionMs: Long? = null,
): PlayableRequest<T>? {
    val items = rows.filterNotNull()
    if (items.isEmpty() && rows.isNotEmpty()) return null
    if (startIndex == null || items.isEmpty()) return PlayableRequest(items, startIndex, startPositionMs)
    val playableBefore = rows.subList(0, startIndex.coerceIn(0, rows.size)).count { it != null }
    return PlayableRequest(
        items,
        startIndex = playableBefore.coerceAtMost(items.lastIndex),
        startPositionMs = startPositionMs.takeIf { rows.getOrNull(startIndex) != null },
    )
}
