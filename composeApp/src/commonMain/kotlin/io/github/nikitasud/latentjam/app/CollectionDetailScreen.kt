/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_back
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.action_deselect_all
import io.github.nikitasud.latentjam.app.generated.resources.action_play
import io.github.nikitasud.latentjam.app.generated.resources.action_select_all
import io.github.nikitasud.latentjam.app.generated.resources.action_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.selection_count
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.jetbrains.compose.resources.stringResource

/**
 * What the user drilled into from a browse tab; playing from here scopes the
 * queue to exactly these tracks.
 */
data class CollectionSelection(
    val title: String,
    val subtitle: String?,
    val artworkUri: String?,
    val tracks: List<TrackDescriptor>,
    /**
     * Whether long-pressing a track starts checkbox multi-selection. True for every drill-in
     * today — playlists, albums, artists, genres, folders — so the same gesture means the same
     * thing on every track list. Kept as a flag for future read-only surfaces.
     */
    val allowsTrackSelection: Boolean = false,
    /**
     * Set only when this collection IS a user playlist. Albums, artists, genres, folders and
     * auto playlists leave it null — membership there is derived from tags or listening, so
     * "remove from this playlist" would be a lie the UI cannot honour.
     */
    val playlistId: String? = null,
)

/**
 * Full-screen detail for one album / artist / genre: header with artwork and
 * play-all, then the track list. One screen serves all three collection
 * kinds — they differ only in header data.
 */
@Composable
fun CollectionDetailScreen(
    selection: CollectionSelection,
    currentTrackId: TrackId?,
    /** Whether the player is audibly running; animates the current row's badge. */
    currentTrackPlaying: Boolean = false,
    selectedTrackIds: Set<TrackId> = emptySet(),
    onToggleSelection: (TrackDescriptor) -> Unit = {},
    onStartSelection: (TrackDescriptor) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onToggleAllSelection: () -> Unit = {},
    onPlayTrack: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackMenu: (TrackDescriptor) -> Unit,
    onClose: () -> Unit,
    /** Room at the foot of the list for the mini-player floating over this screen. */
    bottomInset: Dp = 0.dp,
) {
    val selectionMode = selection.allowsTrackSelection && selectedTrackIds.isNotEmpty()
    PlatformBackHandler(enabled = true) {
        if (selectionMode) onClearSelection() else onClose()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // Title and count sit in the bar itself rather than under a large cover: this screen
            // is a list you came to play, and a hero image would push the first track off-screen.
            if (selectionMode) {
                SelectionTopAppBar(
                    count = selectedTrackIds.size,
                    allSelected = selection.tracks.all { it.id in selectedTrackIds },
                    onClose = onClearSelection,
                    onToggleAll = onToggleAllSelection,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            text = selection.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selection.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            LazyColumn(contentPadding = PaddingValues(bottom = bottomInset)) {
                item(key = "actions") {
                    // Shuffle and play live on their own rounded surface, mirroring the Tracks tab
                    // so the same two controls sit in the same place wherever a list is shown.
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledTonalIconButton(
                                onClick = onShuffle,
                                enabled = !selectionMode,
                            ) {
                                Icon(
                                    Icons.Rounded.Shuffle,
                                    contentDescription = stringResource(Res.string.action_shuffle),
                                )
                            }
                            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                            FilledIconButton(
                                onClick = { onPlayTrack(0) },
                                enabled = !selectionMode,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(
                    selection.tracks,
                    key = { _, track -> track.id.value },
                ) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        isPlaying = currentTrackPlaying,
                        onClick = {
                            if (selectionMode) onToggleSelection(track) else onPlayTrack(index)
                        },
                        onLongClick = if (selection.allowsTrackSelection) {
                            {
                                if (selectionMode) {
                                    onToggleSelection(track)
                                } else {
                                    onStartSelection(track)
                                }
                            }
                        } else {
                            { onTrackMenu(track) }
                        },
                        selectionState = if (selectionMode) {
                            track.id in selectedTrackIds
                        } else {
                            null
                        },
                        onMenu = if (selectionMode) null else ({ onTrackMenu(track) }),
                    )
                    if (index < selection.tracks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Contextual app bar shared by the Tracks page and user-playlist multi-selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopAppBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleAll: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(Res.string.selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_close),
                )
            }
        },
        actions = {
            val label = stringResource(
                if (allSelected) Res.string.action_deselect_all else Res.string.action_select_all,
            )
            IconButton(onClick = onToggleAll) {
                Icon(
                    imageVector = if (allSelected) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = label,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
