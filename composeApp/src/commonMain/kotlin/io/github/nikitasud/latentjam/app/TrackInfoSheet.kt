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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.info_album
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_cancel
import io.github.nikitasud.latentjam.app.generated.resources.info_duration
import io.github.nikitasud.latentjam.app.generated.resources.info_edit
import io.github.nikitasud.latentjam.app.generated.resources.info_lyrics
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_failed
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_note
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_refused
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_unavailable
import io.github.nikitasud.latentjam.app.generated.resources.info_genre
import io.github.nikitasud.latentjam.app.generated.resources.info_location
import io.github.nikitasud.latentjam.app.generated.resources.info_not_set
import io.github.nikitasud.latentjam.app.generated.resources.info_save
import io.github.nikitasud.latentjam.app.generated.resources.info_saving
import io.github.nikitasud.latentjam.app.generated.resources.info_title
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the app knows about one track, and a way to correct it.
 *
 * Reading and editing are the same screen rather than two, because the reason to open this is
 * usually "that looks wrong" — the fields are the answer and the fix at once. Values are shown as
 * they actually are: an absent tag reads as "Not set" rather than being silently filled in with a
 * guess, since a guess is exactly what the user came here to remove.
 *
 * ### What a save actually does
 * It rewrites the ID3v2 tag inside the audio FILE, then has the media index re-read it — see
 * [rememberTagWriter], which also records why the obvious shortcut of writing MediaStore's columns
 * does not work. A correction therefore travels with the file and is visible to every other app.
 *
 * A save can legitimately fail: the writer refuses any file whose existing tag it cannot reproduce
 * byte for byte, because a partial rewrite is indistinguishable from deleting the frames it did not
 * understand. That refusal is shown here in words. The one thing this screen will never do is
 * report success it did not get — the version of this UI that did was removed for it.
 *
 * @param onSaved runs after the file and the media index both hold the new tags, so the caller can
 *   refresh its library snapshot and see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackInfoSheet(
    track: TrackDescriptor,
    onSaved: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var editing by remember(track.id) { mutableStateOf(false) }
    var title by remember(track.id) { mutableStateOf(track.title.orEmpty()) }
    var artist by remember(track.id) { mutableStateOf(track.artist.orEmpty()) }
    var album by remember(track.id) { mutableStateOf(track.album.orEmpty()) }
    var genre by remember(track.id) { mutableStateOf(track.genre.orEmpty()) }
    var year by remember(track.id) { mutableStateOf(track.year?.toString().orEmpty()) }
    var saving by remember(track.id) { mutableStateOf(false) }
    var failure by remember(track.id) { mutableStateOf<TagWriteOutcome?>(null) }
    var lyrics by remember(track.id) { mutableStateOf<String?>(null) }
    val readLyrics = rememberLyricsReader()
    LaunchedEffect(track.id) { lyrics = readLyrics(track) }

    val saveTags = rememberTagWriter { outcome ->
        saving = false
        when (outcome) {
            TagWriteOutcome.Saved -> {
                onSaved()
                onDismiss()
            }
            // The user closed the system's permission dialog. They know they did.
            TagWriteOutcome.Cancelled -> Unit
            else -> failure = outcome
        }
    }

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
                        text = track.title ?: stringResource(Res.string.track_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist ?: stringResource(Res.string.track_unknown_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (editing) {
                MetadataField(stringResource(Res.string.info_title), title) { title = it }
                MetadataField(stringResource(Res.string.info_artist), artist) { artist = it }
                MetadataField(stringResource(Res.string.info_album), album) { album = it }
                MetadataField(stringResource(Res.string.info_genre), genre) { genre = it }
                MetadataField(stringResource(Res.string.info_year), year) { input ->
                    // Filtered at entry rather than validated on save: a year is digits, and
                    // rejecting the field afterwards would lose the rest of the edit.
                    year = input.filter(Char::isDigit).take(4)
                }

                failure?.let { outcome ->
                    Text(
                        text = failureMessage(outcome),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                Text(
                    text = stringResource(Res.string.info_edit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = !saving,
                        onClick = {
                            // An untouched field sends null, which leaves that frame exactly as it
                            // is. A field the user emptied sends "", which removes it — the two
                            // have to stay distinguishable all the way down to the writer.
                            val edits = TagEdits(
                                title = title.trim().takeIf { it != track.title.orEmpty() },
                                artist = artist.trim().takeIf { it != track.artist.orEmpty() },
                                album = album.trim().takeIf { it != track.album.orEmpty() },
                                genre = genre.trim().takeIf { it != track.genre.orEmpty() },
                                year = year.trim().takeIf { it != track.year?.toString().orEmpty() },
                            )
                            if (edits.isEmpty) {
                                onDismiss()
                            } else {
                                failure = null
                                saving = true
                                saveTags(track, edits)
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (saving) Res.string.info_saving else Res.string.info_save,
                            ),
                        )
                    }
                    TextButton(
                        enabled = !saving,
                        onClick = { editing = false },
                    ) { Text(stringResource(Res.string.info_cancel)) }
                }
            } else {
                InfoRow(stringResource(Res.string.info_title), track.title)
                InfoRow(stringResource(Res.string.info_artist), track.artist)
                InfoRow(stringResource(Res.string.info_album), track.album)
                InfoRow(stringResource(Res.string.info_genre), track.genre)
                InfoRow(stringResource(Res.string.info_year), track.year?.toString())
                InfoRow(
                    stringResource(Res.string.info_duration),
                    track.durationMs?.let(::formatDuration),
                )
                InfoRow(stringResource(Res.string.info_location), track.audioUri)
                lyrics?.let { text ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = stringResource(Res.string.info_lyrics),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp)) {
                    Button(onClick = { editing = true }) {
                        Text(stringResource(Res.string.info_edit))
                    }
                }
            }
            Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/**
 * Says what went wrong in the user's terms.
 *
 * A refusal is deliberately worded as a property of the file rather than as a malfunction, because
 * that is what it is — the writer declining to risk the file's other frames.
 */
@Composable
private fun failureMessage(outcome: TagWriteOutcome): String = when (outcome) {
    is TagWriteOutcome.Refused -> stringResource(Res.string.info_edit_refused)
    TagWriteOutcome.Unavailable -> stringResource(Res.string.info_edit_unavailable)
    else -> stringResource(Res.string.info_edit_failed)
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
            text = value?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.info_not_set),
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
