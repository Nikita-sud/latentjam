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

/**
 * One row of a controller's request as it arrived. A row that [carriesAudio] is complete and plays
 * as it came. Any other names a library track by [mediaId], empty for none, or searches for the
 * words of [searchQuery], null when it does not search.
 */
internal class RequestedRow(
    val mediaId: String,
    val searchQuery: String?,
    val carriesAudio: Boolean = false,
)

/**
 * Whether a controller's request leaves the music to the app: it asks for rows, and every one of
 * them is a search with no words that names no track. A voice assistant sends "play music on
 * LatentJam" that way, and Android's media apps take it to mean "play anything", usually what
 * played last. Read as a search, no words find nothing, and the request would be refused like one
 * that cannot play.
 */
internal fun asksForAnything(rows: List<RequestedRow>): Boolean =
    rows.isNotEmpty() && rows.all { row ->
        !row.carriesAudio && row.mediaId.isBlank() && row.searchQuery?.isBlank() == true
    }
