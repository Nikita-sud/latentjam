/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/** The queue one pick from outside the app becomes: [rows], playing from row [startIndex], the pick. */
internal class ExpandedPick<T>(val rows: List<T>, val startIndex: Int)

/** The list a controller outside the app was last shown; a pick that follows is taken to come from it. */
internal sealed interface ListedRows {
    /** The children of a browse node that lists tracks: every track, or one playlist. */
    data class Parent(val id: String) : ListedRows

    /** The results of a search. */
    data class Search(val query: String) : ListedRows
}

/**
 * The queue around a single track picked from outside the app, or null where the request plays as it came.
 *
 * Android Auto and voice assistants ask for one track at a time: a row tapped in a list arrives as that row's id
 * alone, a spoken request as its words alone. Played as it came, the track sounded and the music stopped after it.
 * Inside the app a tapped row plays its whole list from that row, so the same is done here: the list the controller
 * was last shown ([listedRows]) when it holds the track, else every track ([allRows]); for a spoken request, every
 * match of [search], best first.
 *
 * Null, so nothing changes:
 * - for the app's own controller ([fromApp]), whose requests already carry the queue it wants;
 * - for a request of several rows, or of a row that carries its own audio;
 * - in SMART, where a tapped track plays alone and SMART builds the queue from it, as it does in the app;
 * - when no list holds the track, or the list is the track alone.
 */
internal fun <T : Any> expandedPick(
    fromApp: Boolean,
    requested: List<RequestedRow>,
    mode: ShuffleMode,
    listedRows: List<T>?,
    allRows: List<T>,
    idOf: (T) -> String,
    search: (String) -> List<T>,
): ExpandedPick<T>? {
    if (fromApp || mode == ShuffleMode.SMART) return null
    val pick = requested.singleOrNull()?.takeIf { !it.carriesAudio } ?: return null
    if (pick.mediaId.isNotBlank()) {
        val list = listOfNotNull(listedRows, allRows).firstOrNull { rows -> rows.any { idOf(it) == pick.mediaId } }
        if (list != null) {
            return ExpandedPick(list, list.indexOfFirst { idOf(it) == pick.mediaId }).takeIf { list.size > 1 }
        }
    }
    val words = pick.searchQuery?.takeIf { it.isNotBlank() } ?: return null
    return search(words).takeIf { it.size > 1 }?.let { ExpandedPick(it, 0) }
}
