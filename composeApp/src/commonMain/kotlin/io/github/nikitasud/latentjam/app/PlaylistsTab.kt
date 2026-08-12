/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_cancel
import io.github.nikitasud.latentjam.app.generated.resources.action_add_to_playlist
import io.github.nikitasud.latentjam.app.generated.resources.action_delete
import io.github.nikitasud.latentjam.app.generated.resources.action_rename
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_most_played
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_recently_added
import io.github.nikitasud.latentjam.app.generated.resources.auto_playlist_recently_played
import io.github.nikitasud.latentjam.app.generated.resources.cd_playlist_options
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.playlist_add_to_title
import io.github.nikitasud.latentjam.app.generated.resources.playlist_add_to_title_generic
import io.github.nikitasud.latentjam.app.generated.resources.playlist_name_placeholder
import io.github.nikitasud.latentjam.app.generated.resources.playlist_new
import io.github.nikitasud.latentjam.app.generated.resources.playlists_empty
import io.github.nikitasud.latentjam.library.AutoPlaylist
import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Playlists tab: the playlists the app derives for you across the top,
 * then the ones you made. Auto playlists lead because they are useful on day
 * one, before anything has been curated.
 */
@Composable
internal fun PlaylistsTabContent(
    autoPlaylists: List<AutoPlaylist>,
    playlists: List<Playlist>,
    tracksOf: (Playlist) -> List<TrackDescriptor>,
    contentPadding: PaddingValues,
    onOpenAuto: (AutoPlaylist) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    /** Whether the pager is currently settled on this tab; drives the entry scroll reset. */
    settledOnTab: Boolean = true,
) {
    // Entering the tab always starts at the top — with the auto playlists (the most useful
    // row, and the whole reason they lead) visible. Nothing tied to this page's lifecycle can
    // express "on entry": measured on a device, the page composition survives leaving the tab
    // (state, effects and offset all stay live in the pager), so a LaunchedEffect(Unit) here
    // fires once per process and never again. The pager's settled position is what actually
    // changes, so the reset keys on settling here. Scrolling within a visit still holds; only
    // re-entry resets.
    val listState = remember { LazyListState() }
    LaunchedEffect(settledOnTab) {
        if (settledOnTab) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (autoPlaylists.isNotEmpty()) {
            item(key = "auto") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(autoPlaylists, key = { it.kind.name }) { auto ->
                        AutoPlaylistCard(auto) { onOpenAuto(auto) }
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.playlists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                tracks = tracksOf(playlist),
                onClick = { onOpenPlaylist(playlist) },
                onRename = { onRename(playlist) },
                onDelete = { onDelete(playlist) },
            )
        }
    }
}

/**
 * The builder emits a playlist KIND; the title is written here.
 *
 * Same split as the For You sections: the derivation stays a pure, unit-tested
 * function in core, and the words it implies arrive in the reader's language.
 *
 * The mapping lives on its own so both readers can share it — cards resolve it
 * in composition, while opening a collection resolves it inside a coroutine.
 */
internal fun AutoPlaylistKind.titleRes(): StringResource = when (this) {
    AutoPlaylistKind.RECENTLY_ADDED -> Res.string.auto_playlist_recently_added
    AutoPlaylistKind.MOST_PLAYED -> Res.string.auto_playlist_most_played
    AutoPlaylistKind.RECENTLY_PLAYED -> Res.string.auto_playlist_recently_played
}

@Composable
private fun AutoPlaylistKind.title(): String = stringResource(titleRes())

@Composable
private fun AutoPlaylistCard(auto: AutoPlaylist, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(150.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            auto.tracks.firstNotNullOfOrNull { it.artworkUri }?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = auto.kind.title(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = pluralStringResource(Res.plurals.count_tracks, auto.tracks.size, auto.tracks.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    tracks: List<TrackDescriptor>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(
            uri = tracks.firstNotNullOfOrNull { it.artworkUri },
            size = 48.dp,
            cornerRadius = 12.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Resolved tracks, not stored ids: a playlist keeps the id of a track deleted
                // from the device (so it self-heals if the file returns), but the number shown
                // must count what tapping the row will actually show.
                text = pluralStringResource(Res.plurals.count_tracks, tracks.size, tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(Res.string.cd_playlist_options, playlist.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_rename)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Create or rename — the same one-field question either way. */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.playlist_name_placeholder)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** Picks a destination playlist for one or more tracks, or makes a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    tracks: List<TrackDescriptor>,
    playlists: List<Playlist>,
    onAddTo: (Playlist) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Two whole sentences rather than a fragment plus an interpolated
            // fallback noun: "track" would have had to decline with the verb in
            // half the languages this ships in.
            val trackTitle = tracks.singleOrNull()?.title
            Text(
                text = if (tracks.size > 1) {
                    stringResource(Res.string.action_add_to_playlist) + " · " +
                        pluralStringResource(Res.plurals.count_tracks, tracks.size, tracks.size)
                } else if (trackTitle != null) {
                    stringResource(Res.string.playlist_add_to_title, trackTitle)
                } else {
                    stringResource(Res.string.playlist_add_to_title_generic)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onCreateNew()
                    }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.playlist_new),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onAddTo(playlist)
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = pluralStringResource(Res.plurals.count_tracks, playlist.trackIds.size, playlist.trackIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
