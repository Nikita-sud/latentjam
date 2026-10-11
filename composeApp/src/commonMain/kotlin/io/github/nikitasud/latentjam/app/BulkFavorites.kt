/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_favorites_added
import io.github.nikitasud.latentjam.app.generated.resources.snack_favorites_removed
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesChange
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** What the selection bar's heart does: hearts the rest of a selection, or un-hearts all of it. */
internal enum class SelectionFavoriteAction { ADD, REMOVE }

/** Whether any of [tracks] is not a favourite yet: whether "add to favourites" would change anything. */
internal fun anyNotFavorite(tracks: List<TrackDescriptor>, favorites: List<TrackId>): Boolean {
    if (tracks.isEmpty()) return false
    val hearted = favorites.toHashSet()
    return tracks.any { it.id !in hearted }
}

/**
 * Remove only when every selected track already is a favourite, the one selection adding would
 * leave unchanged. A mixed selection hearts the ones still missing, like the playlist menu's
 * "Add all to favorites": a heart on a selection never takes a heart away by surprise.
 */
internal fun selectionFavoriteAction(
    selected: List<TrackDescriptor>,
    favorites: List<TrackId>,
): SelectionFavoriteAction =
    if (selected.isNotEmpty() && !anyNotFavorite(selected, favorites)) {
        SelectionFavoriteAction.REMOVE
    } else {
        SelectionFavoriteAction.ADD
    }

/** One bulk heart edit, one durable write; [ids] in the order the listener saw them. */
internal suspend fun Favorites.applyBulk(
    action: SelectionFavoriteAction,
    ids: List<TrackId>,
): FavoritesChange = when (action) {
    SelectionFavoriteAction.ADD -> addAll(ids)
    SelectionFavoriteAction.REMOVE -> removeAll(ids)
}

/**
 * Puts the list from before [change] back exactly. Refused (false) once anything newer changed the
 * hearts: restoring then would silently undo that newer heart as well.
 */
internal suspend fun Favorites.undoBulk(change: FavoritesChange): Boolean =
    change.changed && replaceIfUnchanged(expected = change.after, replacement = change.before)

/**
 * A bulk heart edit with its feedback: the write, a snackbar saying how many tracks it changed,
 * and Undo for an edit that changed anything. [onPublished] gets every list the store settles on —
 * the edit's, then the restored one — so hearts, Favorites and the auto lists follow at once, the
 * same way a single heart's reload makes them.
 *
 * @return false when the write failed; the hearts are then exactly as they were.
 */
internal suspend fun runBulkFavoriteEdit(
    favorites: Favorites,
    action: SelectionFavoriteAction,
    ids: List<TrackId>,
    snackbar: SnackbarHostState,
    onPublished: (List<TrackId>) -> Unit,
): Boolean {
    val change = try {
        favorites.applyBulk(action, ids)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("Favorites: could not edit in bulk: $failure")
        snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
        return false
    }
    onPublished(change.after)
    val message = when (action) {
        SelectionFavoriteAction.ADD ->
            getPluralString(Res.plurals.snack_favorites_added, change.addedCount, change.addedCount)
        SelectionFavoriteAction.REMOVE ->
            getPluralString(Res.plurals.snack_favorites_removed, change.removedCount, change.removedCount)
    }
    val result = snackbar.showSnackbar(
        message = message,
        actionLabel = if (change.changed) getString(Res.string.action_undo) else null,
        withDismissAction = change.changed,
    )
    if (result != SnackbarResult.ActionPerformed) return true
    val restored = try {
        favorites.undoBulk(change)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("Favorites: could not undo a bulk edit: $failure")
        false
    }
    if (restored) {
        onPublished(change.before)
    } else {
        snackbar.showSnackbar(getString(Res.string.settings_library_manage_failed))
    }
    return true
}

/** The route the Favorites auto playlist opens under (see PlaylistsTab's cards). */
internal val FAVORITES_ROUTE_ID: String = "auto:${AutoPlaylistKind.FAVORITES.name}"

/**
 * The open Favorites page once the hearts changed, or null when it needs nothing: another page is
 * open, or it already shows [hearted]. The page lists the hearts, so a track un-hearted while it is
 * open leaves it and a newly hearted one joins at the top — as a playlist's page follows an edit —
 * instead of showing a list the hearts no longer match until it is opened again.
 */
internal fun CollectionSelection.followingFavorites(hearted: List<TrackDescriptor>): CollectionSelection? {
    if (routeId != FAVORITES_ROUTE_ID || tracks == hearted) return null
    return copy(
        tracks = hearted,
        artworkUri = hearted.firstNotNullOfOrNull { it.artworkUri },
    )
}
