/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * The rows of a SMART queue that a change of SMART's eligible library removes, as indices of [ids] from the last one
 * down, so each stays valid while the ones after it go.
 *
 * Only rows after [keepThrough] (what already played, and the current row), only rows whose track is no longer
 * [eligible], and only rows SMART put there itself ([smartOwned]: a row with a plan position or a continuation mark).
 * A row the listener queued by hand, or one of a start they chose, is theirs: hiding a track from SMART says what
 * SMART may pick, not what the listener may play, so such a row stays and plays.
 */
internal fun smartRowsToDrop(
    ids: List<String>,
    keepThrough: Int,
    eligible: (String) -> Boolean,
    smartOwned: (String) -> Boolean,
): List<Int> = (ids.lastIndex downTo keepThrough + 1).filter { index ->
    val id = ids[index]
    !eligible(id) && smartOwned(id)
}

/**
 * What the player holds that SMART's new library does not: the descriptor, as [known] before the sync, of every track
 * in [queueIds] the library no longer has ([inLibrary]). The controller keeps them next to the new library, so a
 * queued row keeps its title and the playing track stays the playing track whatever the shuffle mode: a queue played
 * in order or shuffled can hold rows only SMART's library ever named (the rows SMART picked, a queue from the car).
 * Without them a sync that left out the playing track published "nothing is playing" while it played.
 */
internal fun <T : Any> descriptorsLeavingLibrary(
    queueIds: List<String>,
    inLibrary: (String) -> Boolean,
    known: (String) -> T?,
): List<T> = queueIds.distinct().filterNot(inLibrary).mapNotNull(known)

/**
 * The rows a queue plays when it goes from SMART straight to random shuffle (the mode menu; the button's cycle passes
 * through OFF): the [source] list the queue was started from, around the [current] track, which stays at the playhead
 * whether the source holds it or not. Null where there is nothing to exchange: any other transition, nothing playing,
 * or no source at all, which leaves the queue as it stands.
 *
 * SMART replaces the source's rows with its own, so shuffling the player as it is would shuffle only what SMART had
 * left in it, under SMART's marks, and end after those few rows. iOS builds the same queue: the current track, then
 * the source in random order.
 */
internal fun shuffleSourceLeavingSmart(
    previousMode: ShuffleMode,
    source: List<TrackDescriptor>,
    current: TrackDescriptor?,
): PlaybackQueueOrder? =
    if (previousMode == ShuffleMode.SMART && current != null && source.isNotEmpty()) {
        sourceOrderKeepingCurrent(source, current)
    } else {
        null
    }
