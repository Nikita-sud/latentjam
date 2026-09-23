/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_add_favorite
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_favorite
import io.github.nikitasud.latentjam.app.generated.resources.action_show_on_map
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_queue
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_delete_from_device
import io.github.nikitasud.latentjam.app.generated.resources.action_go_to_album
import io.github.nikitasud.latentjam.app.generated.resources.action_go_to_artist
import io.github.nikitasud.latentjam.app.generated.resources.action_information
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_active_end_of_track
import io.github.nikitasud.latentjam.app.generated.resources.sleep_timer_active_minutes
import io.github.nikitasud.latentjam.app.generated.resources.action_exclude_artist_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_exclude_track_from_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_include_artist_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_include_track_in_smart
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_play_next
import io.github.nikitasud.latentjam.app.generated.resources.action_share
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_remove_from_latentjam
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_message
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_message_generic
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_track_title
import io.github.nikitasud.latentjam.app.generated.resources.dialog_delete_tracks_message
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.playback.SleepTimerState
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch
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
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    /** Non-null only when the sheet was raised from inside a user playlist's own track list. */
    onRemoveFromPlaylist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
    onGoToArtist: (() -> Unit)?,
    /** Non-null only when the Map has actually drawn this track's dot. */
    onShowOnMap: (() -> Unit)? = null,
    onInfo: () -> Unit,
    /** Present only from the full player, which is where a listener reaches for it. */
    onSleepTimer: (() -> Unit)? = null,
    sleepTimerState: SleepTimerState? = null,
    isTrackExcludedFromSmart: Boolean,
    isArtistExcludedFromSmart: Boolean,
    onToggleTrackSmartExclusion: () -> Unit,
    onToggleArtistSmartExclusion: (() -> Unit)?,
    onHide: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Null only when this track cannot be shared by the platform. */
    onShare: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }

    fun dismissThen(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        if (reduceMotion) {
            onDismiss()
            action()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
                action()
            }
        }
    }

    fun dismissWhile(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        // Transport and local-state actions should acknowledge the tap immediately; the sheet can
        // leave in parallel. Navigation/modal handoffs continue to use dismissThen above.
        action()
        if (reduceMotion) {
            onDismiss()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissThen {} },
        // Keep top clearance outside the measured sheet: position-dependent content insets
        // can resize a tall menu and continuously move its expanded anchor while it settles.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        },
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissalInFlight,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(modifier = Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
            }
        },
    ) {
        // The complete action set is taller than compact phones (and grows further with large
        // accessibility text). Let the sheet and this content negotiate nested scrolling so the
        // non-destructive visibility action and delete confirmation can never be stranded below
        // the viewport.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 56.dp, cornerRadius = 12.dp)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = track.title ?: stringResource(Res.string.track_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            track.artist ?: stringResource(Res.string.track_unknown_artist),
                            track.album?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { dismissWhile(onToggleFavorite) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(
                            if (isFavorite) Res.string.action_remove_favorite else Res.string.action_add_favorite,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { dismissWhile(onPlay) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(Res.string.action_play))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetPrimaryAction(stringResource(Res.string.action_play_next), Modifier.weight(1f)) {
                    dismissWhile(onPlayNext)
                }
                SheetPrimaryAction(stringResource(Res.string.action_add_to_queue), Modifier.weight(1f)) {
                    dismissWhile(onAddToQueue)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            onShare?.let { share ->
                SheetAction(
                    Icons.Outlined.Share,
                    stringResource(Res.string.action_share),
                ) { dismissThen(share) }
            }
            SheetAction(
                Icons.AutoMirrored.Outlined.PlaylistAdd,
                stringResource(Res.string.action_add_to_playlist),
            ) { dismissThen(onAddToPlaylist) }
            onRemoveFromPlaylist?.let { removeFromPlaylist ->
                SheetAction(
                    Icons.Outlined.PlaylistRemove,
                    stringResource(Res.string.action_remove_from_playlist),
                ) { dismissWhile(removeFromPlaylist) }
            }
            onGoToAlbum?.let { goToAlbum ->
                SheetAction(
                    Icons.Outlined.Album,
                    stringResource(Res.string.action_go_to_album),
                ) { dismissThen(goToAlbum) }
            }
            onGoToArtist?.let { goToArtist ->
                SheetAction(
                    Icons.Outlined.PersonOutline,
                    stringResource(Res.string.action_go_to_artist),
                ) { dismissThen(goToArtist) }
            }
            onShowOnMap?.let { showOnMap ->
                SheetAction(
                    Icons.Outlined.Map,
                    stringResource(Res.string.action_show_on_map),
                ) { dismissThen(showOnMap) }
            }
            SheetAction(
                Icons.Outlined.Info,
                stringResource(Res.string.action_information),
            ) { dismissThen(onInfo) }
            onSleepTimer?.let { sleepTimer ->
                SheetAction(
                    icon = Icons.Outlined.Bedtime,
                    label = stringResource(Res.string.sleep_timer),
                    supporting = when (val timer = sleepTimerState) {
                        is SleepTimerState.Countdown -> stringResource(
                            Res.string.sleep_timer_active_minutes,
                            timer.remainingMinutes,
                        )
                        SleepTimerState.EndOfTrack ->
                            stringResource(Res.string.sleep_timer_active_end_of_track)
                        else -> null
                    },
                ) { dismissThen(sleepTimer) }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            // An artist-level exclusion already covers every one of their tracks. Surface its
            // restore action first; a dormant track-specific rule appears after the artist returns.
            if (!isArtistExcludedFromSmart) {
                SheetAction(
                    Icons.Outlined.Block,
                    stringResource(
                        if (isTrackExcludedFromSmart) {
                            Res.string.action_include_track_in_smart
                        } else {
                            Res.string.action_exclude_track_from_smart
                        },
                    ),
                ) { dismissWhile(onToggleTrackSmartExclusion) }
            }
            onToggleArtistSmartExclusion?.let { toggleArtist ->
                SheetAction(
                    Icons.Outlined.PersonOff,
                    stringResource(
                        if (isArtistExcludedFromSmart) {
                            Res.string.action_include_artist_in_smart
                        } else {
                            Res.string.action_exclude_artist_from_smart
                        },
                    ),
                ) { dismissWhile(toggleArtist) }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            SheetAction(
                Icons.Outlined.VisibilityOff,
                stringResource(Res.string.action_remove_from_latentjam),
            ) { dismissWhile(onHide) }
            if (canDelete) {
                SheetAction(
                    icon = Icons.Outlined.DeleteOutline,
                    label = stringResource(Res.string.action_delete_from_device),
                    tint = MaterialTheme.colorScheme.error,
                ) { dismissThen(onDelete) }
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
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissalInFlight by remember { mutableStateOf(false) }

    fun dismissThen(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        if (reduceMotion) {
            onDismiss()
            action()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
                action()
            }
        }
    }

    fun dismissWhile(action: () -> Unit) {
        if (dismissalInFlight) return
        dismissalInFlight = true
        action()
        if (reduceMotion) {
            onDismiss()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismissThen {} },
        sheetState = sheetState,
        sheetGesturesEnabled = !dismissalInFlight,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = pluralStringResource(Res.plurals.count_tracks, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            SheetAction(
                icon = Icons.Outlined.VisibilityOff,
                label = stringResource(Res.string.action_remove_from_latentjam),
            ) { dismissWhile(onHide) }
            if (canDeleteFromDevice) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SheetAction(
                    icon = Icons.Outlined.DeleteOutline,
                    label = stringResource(Res.string.action_delete_from_device),
                    tint = MaterialTheme.colorScheme.error,
                ) { dismissThen(onDeleteFromDevice) }
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
private fun SheetPrimaryAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    /** A second, quieter line under the label: a state the action currently has. */
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tint == Color.Unspecified) Color.Unspecified else tint,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
