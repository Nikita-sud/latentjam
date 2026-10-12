/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** Move a visible occurrence in the full stored list, retaining every unavailable entry. */
internal fun playlistOrderAfterMove(
    stored: List<String>, visible: List<TrackId>, from: Int, to: Int,
): List<TrackId>? {
    if (from !in visible.indices || to !in visible.indices || from == to) return null
    val visibleIds = visible.mapTo(HashSet()) { it.value }
    val positions = stored.indices.filter { stored[it] in visibleIds }
    // Reject stale or sorted presentations, including a missing duplicate occurrence.
    if (positions.map { stored[it] } != visible.map { it.value }) return null
    return stored.toMutableList().apply { add(positions[to], removeAt(positions[from])) }.map(::TrackId)
}

/** Merge is idempotent and keeps the greater occurrence count, without reordering existing rows. */
internal fun mergePlaylistOccurrences(existing: List<String>, imported: List<String>): List<String> {
    val remaining = existing.groupingBy { it }.eachCount().toMutableMap()
    return existing + imported.filter { id ->
        val count = remaining[id] ?: 0
        if (count > 0) { remaining[id] = count - 1; false } else true
    }
}

internal data class PlaylistTrackReorderActions(
    val busy: Boolean,
    val snackbar: SnackbarHostState,
    val move: (Int, Int) -> Unit,
)

/** Owns the write outside App's large composition lambda; the page changes after durable commit. */
@Composable
internal fun rememberPlaylistTrackReorder(
    playlist: Playlist?,
    selection: CollectionSelection,
    onSaved: suspend () -> Unit,
): PlaylistTrackReorderActions {
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latestSaved by rememberUpdatedState(onSaved)
    return PlaylistTrackReorderActions(busy, snackbar) { from, to ->
        if (!busy && playlist != null && playlist.id == selection.playlistId &&
            selection.trackSort?.sort == PlaylistSort.CUSTOM
        ) {
            val replacement = playlistOrderAfterMove(playlist.trackIds, selection.tracks.map { it.id }, from, to)
            if (replacement != null) {
                busy = true
                scope.launch {
                    try {
                        val saved = withContext(NonCancellable) {
                            val changed = AppGraph.playlists.replaceTracksIfUnchanged(
                                playlist.id, playlist.trackIds.map(::TrackId), replacement,
                            )
                            // A stale drop refreshes the page too. Closing the page during a save
                            // must not leave the app's playlist cache behind the durable store.
                            latestSaved()
                            changed
                        }
                        if (!saved) snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
