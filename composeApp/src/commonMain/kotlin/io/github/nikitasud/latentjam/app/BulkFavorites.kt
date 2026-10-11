/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_add_favorite
import io.github.nikitasud.latentjam.app.generated.resources.action_undo
import io.github.nikitasud.latentjam.app.generated.resources.m3u_import_found
import io.github.nikitasud.latentjam.app.generated.resources.playlist_imported_name
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_favorites_added
import io.github.nikitasud.latentjam.app.generated.resources.snack_favorites_removed
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesChange
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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

/**
 * A playlist file, read and matched against the library, waiting for the listener to say where it
 * goes: a new playlist, or Favorites. [matched] keeps the file's order, each track once.
 */
internal class M3uImportOffer(
    /** The file read and matched against the library (see [importM3u]). */
    val import: M3uImport,
) {
    /** The file's own `#PLAYLIST` name; null names it with the localized default. */
    val name: String? get() = import.name

    /** Track rows in the file, matched or not. */
    val entryCount: Int get() = import.entryCount

    /** The rows that found their track. */
    val matchedEntryCount: Int get() = import.matchedEntryCount

    /** The found tracks in the file's order, each once. */
    val matched: List<TrackDescriptor> get() = import.tracks

    /** What Favorites receives: the matched tracks in the file's order, which heads the list. */
    val favoriteIds: List<TrackId> get() = matched.map { it.id }
}

/**
 * Where an imported playlist file goes. Raised after the file is read, so it can say how much of it
 * was found on this device before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun M3uImportTargetSheet(
    offer: M3uImportOffer,
    onNewPlaylist: () -> Unit,
    onFavorites: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }

    // The choice runs at once and the sheet leaves after it: an interrupted hide must not lose it.
    fun dismissWhile(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        action()
        if (reduceMotion) {
            onDismiss()
        } else {
            scope.hideSheetThen(sheetState::hide) { onDismiss() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!dismissalInFlight) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissalInFlight,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                text = offer.name ?: stringResource(Res.string.playlist_imported_name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = pluralStringResource(
                    Res.plurals.m3u_import_found,
                    offer.entryCount,
                    offer.matchedEntryCount,
                    offer.entryCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            ImportTargetRow(
                icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                label = stringResource(Res.string.playlist_new),
            ) { dismissWhile(onNewPlaylist) }
            ImportTargetRow(
                icon = Icons.Outlined.FavoriteBorder,
                label = stringResource(Res.string.action_add_favorite),
            ) { dismissWhile(onFavorites) }
        }
    }
}

@Composable
private fun ImportTargetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
