/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Everything the app knows about one track, and a way to correct it.
 *
 * Values are shown as they actually are: an absent tag reads as "Not set" rather than being
 * silently filled in with a guess, since a guess is exactly what a user opening this wants removed.
 *
 * ### Why there is no edit button
 * Editing was built and then removed, because it does not work. Audio metadata columns in
 * MediaStore (TITLE, ARTIST, ALBUM, YEAR) are DERIVED by the media provider from the file's own
 * tags; writes to them are accepted and then ignored. Verified on API 36 — the write-consent dialog
 * appears, consent is granted, `ContentResolver.update` reports success, and the value is unchanged.
 * A direct `content update` from the shell, with full permissions, fails the same way.
 *
 * Correcting tags therefore means writing the audio FILE, which needs a tag writer per container
 * format. That is a real commitment and a licensing decision (the obvious library is LGPL), so it
 * is left as a deliberate choice rather than smuggled in behind a button that silently does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackInfoSheet(
    track: TrackDescriptor,
    onDismiss: () -> Unit,
) {

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

                InfoRow("Title", track.title)
                InfoRow("Artist", track.artist)
                InfoRow("Album", track.album)
                InfoRow("Genre", track.genre)
                InfoRow("Year", track.year?.toString())
                InfoRow("Duration", track.durationMs?.let(::formatDuration))
                InfoRow("Location", track.audioUri)
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
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

