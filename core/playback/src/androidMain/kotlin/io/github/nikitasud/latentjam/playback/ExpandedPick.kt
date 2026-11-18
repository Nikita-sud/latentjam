/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * The list one pick from outside the app belongs to: [rows], with the pick at row [startIndex]. It is the queue to
 * play, from the pick, unless [sourceOnly]: then the pick plays alone and the list is only the source its queue goes
 * back to (see [expandedPick]).
 */
internal class ExpandedPick<T>(val rows: List<T>, val startIndex: Int, val sourceOnly: Boolean = false)

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
 * In SMART a tapped track plays alone and SMART builds the queue from it, as it does in the app. The list is still
 * found, as the source ([ExpandedPick.sourceOnly]): in the app the tapped list stays the queue's source under SMART,
 * and without one, leaving SMART would leave a queue of the pick alone and the music would stop after it.
 *
 * Null, so nothing changes:
 * - for the app's own controller ([fromApp]), whose requests already carry the queue it wants;
 * - for a request of several rows, or of a row that carries its own audio;
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
    if (fromApp) return null
    val sourceOnly = mode == ShuffleMode.SMART
    val pick = requested.singleOrNull()?.takeIf { !it.carriesAudio } ?: return null
    if (pick.mediaId.isNotBlank()) {
        val list = listOfNotNull(listedRows, allRows).firstOrNull { rows -> rows.any { idOf(it) == pick.mediaId } }
        if (list != null) {
            return ExpandedPick(list, list.indexOfFirst { idOf(it) == pick.mediaId }, sourceOnly).takeIf { list.size > 1 }
        }
    }
    val words = pick.searchQuery?.takeIf { it.isNotBlank() } ?: return null
    return search(words).takeIf { it.size > 1 }?.let { ExpandedPick(it, 0, sourceOnly) }
}

/**
 * The shuffle mode a pick from outside the app is played in when it is the first thing a process plays: the mode
 * [saved] with the last track, or null where the mode already in force stands.
 *
 * The mode in force is the app's controller's to set, and it sets it when it connects. A car or a voice request can
 * start the service in a process where it has not connected yet ([appControllerConnected] false, the player still
 * empty): the mode is then still the default, in order, whatever the listener left it at. The controller would take
 * the saved mode on connecting to an empty player; it connects only after this pick is installed, so the same rule is
 * applied here, before the pick is turned into a queue.
 */
internal fun coldPickMode(
    appControllerConnected: Boolean,
    playerEmpty: Boolean,
    saved: PlaybackWidgetSnapshot,
): ShuffleMode? = saved.shuffleMode.takeIf { !appControllerConnected && playerEmpty && saved.mediaId.isNotBlank() }
