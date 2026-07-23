/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_queue
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_delete_from_device
import io.github.nikitasud.latentjam.app.generated.resources.action_go_to_album
import io.github.nikitasud.latentjam.app.generated.resources.action_go_to_artist
import io.github.nikitasud.latentjam.app.generated.resources.action_information
import io.github.nikitasud.latentjam.app.generated.resources.action_exclude_artist_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_exclude_track_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_include_artist_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_include_track_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_play_next
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_message
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_message_generic
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_title
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_tracks_message
import io.github.nikitasud.latentjam.app.generated.resources.label_track
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/**
 * Track actions, raised from the bottom rather than dropped from the row —
 * the sheet has room for a header (artwork, title, artist) that confirms
 * which track the actions apply to, and its targets sit in the thumb zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsSheet(
    track: TrackDescriptor,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToAlbum: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
    onInfo: () -> Unit,
    isTrackExcludedFromSmart: Boolean,
    isArtistExcludedFromSmart: Boolean,
    onToggleTrackSmartExclusion: () -> Unit,
    onToggleArtistSmartExclusion: (() -> Unit)?,
    onHide: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // The complete action set is taller than compact phones (and grows further with large
        // accessibility text). Let the sheet and this content negotiate nested scrolling so the
        // non-destructive visibility action and delete confirmation can never be stranded below
        // the viewport.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 56.dp, cornerRadius = 16.dp)
                Column {
                    Text(
                        text = stringResource(Res.string.label_track),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = track.title ?: stringResource(Res.string.track_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetAction(
                Icons.Rounded.PlayArrow,
                stringResource(Res.string.action_play),
            ) { onDismiss(); onPlay() }
            SheetAction(
                Icons.AutoMirrored.Rounded.PlaylistAdd,
                stringResource(Res.string.action_play_next),
            ) { onDismiss(); onPlayNext() }
            SheetAction(
                Icons.AutoMirrored.Rounded.QueueMusic,
                stringResource(Res.string.action_add_to_queue),
            ) { onDismiss(); onAddToQueue() }
            SheetAction(
                Icons.Rounded.LibraryAdd,
                stringResource(Res.string.action_add_to_playlist),
            ) { onDismiss(); onAddToPlaylist() }
            onGoToAlbum?.let { goToAlbum ->
                SheetAction(
                    Icons.Rounded.Album,
                    stringResource(Res.string.action_go_to_album),
                ) { onDismiss(); goToAlbum() }
            }
            onGoToArtist?.let { goToArtist ->
                SheetAction(
                    Icons.Rounded.Person,
                    stringResource(Res.string.action_go_to_artist),
                ) { onDismiss(); goToArtist() }
            }
            SheetAction(
                Icons.Rounded.Info,
                stringResource(Res.string.action_information),
            ) { onDismiss(); onInfo() }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            // An artist-level exclusion already covers every one of their tracks. Surface its
            // restore action first; a dormant track-specific rule appears after the artist returns.
            if (!isArtistExcludedFromSmart) {
                SheetAction(
                    if (isTrackExcludedFromSmart) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbDown,
                    stringResource(
                        if (isTrackExcludedFromSmart) {
                            Res.string.action_include_track_in_smart
                        } else {
                            Res.string.action_exclude_track_from_smart
                        },
                    ),
                ) { onDismiss(); onToggleTrackSmartExclusion() }
            }
            onToggleArtistSmartExclusion?.let { toggleArtist ->
                SheetAction(
                    if (isArtistExcludedFromSmart) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbDown,
                    stringResource(
                        if (isArtistExcludedFromSmart) {
                            Res.string.action_include_artist_in_smart
                        } else {
                            Res.string.action_exclude_artist_from_smart
                        },
                    ),
                ) { onDismiss(); toggleArtist() }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetAction(
                Icons.Rounded.VisibilityOff,
                stringResource(Res.string.action_remove_from_latentjam),
            ) { onDismiss(); onHide() }
            if (canDelete) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction(
                    icon = Icons.Rounded.DeleteOutline,
                    label = stringResource(Res.string.action_delete_from_device),
                    tint = MaterialTheme.colorScheme.error,
                ) { onDismiss(); onDelete() }
            }
        }
    }
}

/** Confirmation for the one action here that cannot be undone. */
@Composable
internal fun DeleteTrackDialog(
    track: TrackDescriptor,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_delete_track_title)) },
        text = {
            val title = track.title
            Text(
                if (title != null) {
                    stringResource(Res.string.dialog_delete_track_message, title)
                } else {
                    stringResource(Res.string.dialog_delete_track_message_generic)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * A trash tap in multi-selection mode first distinguishes the reversible app-only action from
 * physical file deletion. This preserves the choice the single-track sheet already exposes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionRemovalSheet(
    count: Int,
    canDeleteFromDevice: Boolean,
    onHide: () -> Unit,
    onDeleteFromDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = pluralStringResource(Res.plurals.count_tracks, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            SheetAction(
                icon = Icons.Rounded.VisibilityOff,
                label = stringResource(Res.string.action_remove_from_latentjam),
            ) {
                onDismiss()
                onHide()
            }
            if (canDeleteFromDevice) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction(
                    icon = Icons.Rounded.DeleteOutline,
                    label = stringResource(Res.string.action_delete_from_device),
                    tint = MaterialTheme.colorScheme.error,
                ) {
                    onDismiss()
                    onDeleteFromDevice()
                }
            }
        }
    }
}

/** Confirmation for deleting multiple physical files in one platform-owned operation. */
@Composable
internal fun DeleteTracksDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_delete_track_title)) },
        text = {
            Text(stringResource(Res.string.dialog_delete_tracks_message, count))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val resolvedTint = if (tint == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        tint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = resolvedTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tint == Color.Unspecified) Color.Unspecified else tint,
        )
    }
}
