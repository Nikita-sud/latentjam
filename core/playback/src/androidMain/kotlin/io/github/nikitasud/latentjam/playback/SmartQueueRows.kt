/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

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
