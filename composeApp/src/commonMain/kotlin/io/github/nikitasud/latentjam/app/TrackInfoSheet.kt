/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Everything the app knows about one track, and a way to correct it.
 *
 * Reading and editing are the same screen rather than two, because the reason to open this is
 * usually "that looks wrong" — the fields are the answer and the fix at once. Values are shown as
 * they actually are: an absent tag reads as "Not set" rather than being silently filled in with a
 * guess, since a guess is exactly what the user came here to remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackInfoSheet(
    track: TrackDescriptor,
    onSave: (TrackEdits) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember(track.id) { mutableStateOf(false) }
    var title by remember(track.id) { mutableStateOf(track.title.orEmpty()) }
    var artist by remember(track.id) { mutableStateOf(track.artist.orEmpty()) }
    var album by remember(track.id) { mutableStateOf(track.album.orEmpty()) }
    var genre by remember(track.id) { mutableStateOf(track.genre.orEmpty()) }
    var year by remember(track.id) { mutableStateOf(track.year?.toString().orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 64.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title ?: "Untitled",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (editing) {
                MetadataField("Title", title) { title = it }
                MetadataField("Artist", artist) { artist = it }
                MetadataField("Album", album) { album = it }
                MetadataField("Genre", genre) { genre = it }
                MetadataField("Year", year) { input ->
                    // Filtered at entry rather than validated on save: a year is digits, and
                    // rejecting the field afterwards would lose the rest of the edit.
                    year = input.filter(Char::isDigit).take(4)
                }
                Text(
                    text = "Corrections are saved to the system media library. They stick for " +
                        "everyday use, but the file's own tags remain the original — editing the " +
                        "file elsewhere will bring them back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            onSave(
                                TrackEdits(
                                    title = title.trim().takeIf { it != track.title.orEmpty() },
                                    artist = artist.trim().takeIf { it != track.artist.orEmpty() },
                                    album = album.trim().takeIf { it != track.album.orEmpty() },
                                    genre = genre.trim().takeIf { it != track.genre.orEmpty() },
                                    year = year.toIntOrNull().takeIf { it != track.year },
                                ),
                            )
                        },
                    ) { Text("Save") }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else {
                InfoRow("Title", track.title)
                InfoRow("Artist", track.artist)
                InfoRow("Album", track.album)
                InfoRow("Genre", track.genre)
                InfoRow("Year", track.year?.toString())
                InfoRow("Duration", track.durationMs?.let(::formatDuration))
                InfoRow("Location", track.audioUri)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                ) {
                    Button(onClick = { editing = true }) { Text("Edit tags") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "Not set",
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun MetadataField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
