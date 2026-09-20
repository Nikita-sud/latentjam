/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_show_on_map
import io.github.nikitasud.latentjam.app.generated.resources.cd_details_close
import io.github.nikitasud.latentjam.app.generated.resources.details_file
import io.github.nikitasud.latentjam.app.generated.resources.details_format
import io.github.nikitasud.latentjam.app.generated.resources.details_language
import io.github.nikitasud.latentjam.app.generated.resources.details_original_year
import io.github.nikitasud.latentjam.app.generated.resources.details_plays
import io.github.nikitasud.latentjam.app.generated.resources.details_plays_none
import io.github.nikitasud.latentjam.app.generated.resources.info_album
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_duration
import io.github.nikitasud.latentjam.app.generated.resources.info_edit
import io.github.nikitasud.latentjam.app.generated.resources.info_genre
import io.github.nikitasud.latentjam.app.generated.resources.info_not_set
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import org.jetbrains.compose.resources.stringResource

/**
 * The back of the cover: the facts about the track, as the file states them.
 *
 * Nothing here is guessed. A missing tag reads as "Not set", the format comes from the file
 * name, the bitrate is the average the file size and duration imply, and the play count is the
 * listening history's. "Edit tags" hands over to the full information sheet, which is where a
 * correction is actually written into the file.
 */
@Composable
internal fun TrackDetailsFace(
    track: TrackDescriptor,
    stats: TrackStats?,
    onEditTags: () -> Unit,
    onShowOnMap: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val notSet = stringResource(Res.string.info_not_set)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track.title ?: stringResource(Res.string.track_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.cd_details_close),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            DetailRow(stringResource(Res.string.info_artist), track.artist ?: notSet)
            DetailRow(stringResource(Res.string.info_album), track.album ?: notSet)
            DetailRow(stringResource(Res.string.info_genre), track.genre ?: notSet)
            DetailRow(stringResource(Res.string.info_year), track.year?.toString() ?: notSet)
            track.originalYear?.takeIf { it != track.year }?.let { original ->
                DetailRow(stringResource(Res.string.details_original_year), original.toString())
            }
            DetailRow(stringResource(Res.string.details_language), track.language ?: notSet)
            DetailRow(
                stringResource(Res.string.info_duration),
                track.durationMs?.let(::formatDuration) ?: notSet,
            )
            DetailRow(
                stringResource(Res.string.details_format),
                fileFormatLabel(track.fileName, track.sizeBytes, track.durationMs) ?: notSet,
            )
            DetailRow(stringResource(Res.string.details_file), track.fileName ?: track.audioUri ?: notSet)
            DetailRow(
                stringResource(Res.string.details_plays),
                stats?.plays?.takeIf { it > 0 }?.toString()
                    ?: stringResource(Res.string.details_plays_none),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onEditTags, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(Res.string.info_edit), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            onShowOnMap?.let { showOnMap ->
                OutlinedButton(onClick = showOnMap, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(Res.string.action_show_on_map),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
