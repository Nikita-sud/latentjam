/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.tab_artists
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.library.ArtistGroup
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Choosing a credit navigates to that artist's full collection without changing playback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistChooserSheet(
    artists: List<ArtistGroup>,
    onSelect: (ArtistGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    var dismissing by remember { mutableStateOf(false) }

    fun dismissThen(action: () -> Unit = {}) {
        if (dismissing) return
        dismissing = true
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

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = { dismissThen() },
        sheetGesturesEnabled = !dismissing,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.tab_artists),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { dismissThen() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.action_close))
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(artists, key = { it.name.orEmpty() }) { artist ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !dismissing, role = Role.Button) {
                            dismissThen { onSelect(artist) }
                        },
                        headlineContent = {
                            Text(
                                text = artist.name ?: stringResource(Res.string.track_unknown_artist),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(pluralStringResource(Res.plurals.count_tracks, artist.tracks.size, artist.tracks.size))
                        },
                        leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
