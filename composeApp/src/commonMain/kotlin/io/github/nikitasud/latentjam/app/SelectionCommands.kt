/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.StringResource

/**
 * The one destructive action a selection offers. Only a user playlist's own page can take tracks
 * out of something without touching the files; everywhere else the action deletes them.
 */
internal enum class SelectionDestructiveAction { DELETE, REMOVE_FROM_PLAYLIST }

/** [playlistId] is the open page's user playlist ([CollectionSelection.playlistId]), null elsewhere. */
internal fun selectionDestructiveAction(playlistId: String?): SelectionDestructiveAction =
    if (playlistId != null) {
        SelectionDestructiveAction.REMOVE_FROM_PLAYLIST
    } else {
        SelectionDestructiveAction.DELETE
    }

internal val SelectionDestructiveAction.label: StringResource
    get() = when (this) {
        SelectionDestructiveAction.DELETE -> Res.string.action_delete
        SelectionDestructiveAction.REMOVE_FROM_PLAYLIST -> Res.string.action_remove_from_playlist
    }

internal val SelectionDestructiveAction.icon: ImageVector
    get() = when (this) {
        SelectionDestructiveAction.DELETE -> Icons.Rounded.DeleteOutline
        SelectionDestructiveAction.REMOVE_FROM_PLAYLIST -> Icons.Rounded.PlaylistRemove
    }

/** Sharing hands over the files themselves, so every selected track needs one. */
internal fun canShareSelection(selected: List<TrackDescriptor>): Boolean =
    selected.isNotEmpty() && selected.all { it.audioUri != null }

/**
 * What a multi-selection can do. App builds one per composition and hands the same object to the
 * bottom action bar and to every contextual top bar (the browse tabs, Search, a collection's page),
 * so they can never disagree about what is enabled or which destructive action is on offer.
 *
 * A data class of flags and Compose-remembered lambdas: an unchanged selection compares equal
 * across recompositions, so the screens it is passed to can still skip.
 */
internal data class SelectionCommands(
    /** False while the bars leave, and when every selected row has left the library meanwhile. */
    val canAct: Boolean,
    val canShare: Boolean,
    val destructive: SelectionDestructiveAction,
    val onPlay: () -> Unit,
    val onAdd: () -> Unit,
    val onFavorite: (SelectionFavoriteAction) -> Unit,
    val onShare: () -> Unit,
    val onEditTags: () -> Unit,
    /** Deletes (after its confirmation) or removes from the open playlist, as [destructive] says. */
    val onRemove: () -> Unit,
) {
    constructor(
        /** The selected tracks that are still in the library, in the order they would play. */
        selected: List<TrackDescriptor>,
        /** The open page's user playlist, if any: there the destructive action removes from it. */
        playlistId: String?,
        onPlay: () -> Unit,
        onAdd: () -> Unit,
        onFavorite: (SelectionFavoriteAction) -> Unit,
        onShare: () -> Unit,
        onEditTags: () -> Unit,
        onRemove: () -> Unit,
    ) : this(
        canAct = selected.isNotEmpty(),
        canShare = canShareSelection(selected),
        destructive = selectionDestructiveAction(playlistId),
        onPlay = onPlay,
        onAdd = onAdd,
        onFavorite = onFavorite,
        onShare = onShare,
        onEditTags = onEditTags,
        onRemove = onRemove,
    )
}
