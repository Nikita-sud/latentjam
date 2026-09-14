/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun duplicateCopiesToRemove(
    merges: List<Pair<DuplicateGroup, DuplicateCopy>>,
): List<TrackDescriptor> = merges.flatMap { (group, survivor) ->
    group.copies.filterNot { it.track.id == survivor.track.id }.map { it.track }
}.distinctBy { it.id }

internal fun canDeleteDuplicateFiles(merges: List<Pair<DuplicateGroup, DuplicateCopy>>): Boolean =
    duplicateCopiesToRemove(merges).let { copies -> copies.isNotEmpty() && copies.all(::canDeleteTrack) }

/** Once hiding starts, publish its library snapshot even if the settings screen closes. */
internal suspend fun hideTracksAndRefresh(
    hide: suspend () -> Unit,
    refresh: suspend () -> Unit,
) {
    currentCoroutineContext().ensureActive()
    withContext(NonCancellable) {
        hide()
        refresh()
    }
}

/**
 * Rewrite references before removing copies, and republish partial durable changes even on failure.
 * A rejected native delete request is propagated to the caller instead of escaping its coroutine.
 */
internal suspend fun performDuplicateMerge(
    merges: List<Pair<DuplicateGroup, DuplicateCopy>>,
    deleteFiles: Boolean,
    playlists: Playlists,
    favorites: Favorites,
    onHideTracks: suspend (List<TrackDescriptor>) -> Unit,
    onDeleteTracks: (List<TrackDescriptor>) -> Unit,
    onDataChanged: suspend () -> Unit,
) {
    require(!deleteFiles || canDeleteDuplicateFiles(merges)) {
        "Some duplicate copies cannot be deleted from this source"
    }
    val losers = mutableListOf<TrackDescriptor>()
    var failure: Throwable? = null
    try {
        for ((group, survivor) in merges) {
            mergeDuplicateGroup(
                group = group.copies.map { it.track },
                survivor = survivor.track,
                playlists = playlists,
                favorites = favorites,
                onHideTrack = { losers += it },
            )
        }
        if (!deleteFiles && losers.isNotEmpty()) onHideTracks(losers.distinctBy { it.id })
    } catch (problem: Throwable) {
        failure = problem
    }
    try {
        withContext(NonCancellable) { onDataChanged() }
    } catch (problem: Throwable) {
        if (failure == null) failure = problem
    }
    failure?.let { throw it }
    currentCoroutineContext().ensureActive()
    if (deleteFiles && losers.isNotEmpty()) onDeleteTracks(losers.distinctBy { it.id })
}
