/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import io.github.nikitasud.latentjam.app.generated.resources.details_file
import io.github.nikitasud.latentjam.app.generated.resources.details_format
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
    val lyricsSource = track.lyricsSourceIdentity()
    val reduceMotion = rememberReduceMotion()
    var editing by rememberSaveable(track.id.value) { mutableStateOf(false) }
    var title by rememberSaveable(track.id.value) { mutableStateOf(track.title.orEmpty()) }
    var artist by rememberSaveable(track.id.value) { mutableStateOf(track.artist.orEmpty()) }
    var album by rememberSaveable(track.id.value) { mutableStateOf(track.album.orEmpty()) }
    var genre by rememberSaveable(track.id.value) { mutableStateOf(track.genre.orEmpty()) }
    var year by rememberSaveable(track.id.value) { mutableStateOf(track.year?.toString().orEmpty()) }
    var saving by remember(track.id) { mutableStateOf(false) }
    var failure by remember(track.id) { mutableStateOf<TagWriteOutcome?>(null) }
    var lyrics by remember(lyricsSource) { mutableStateOf<String?>(null) }
    val readLyrics = rememberLyricsReader()
    LaunchedEffect(lyricsSource) {
        lyrics = null
        lyrics = readLyrics(track)?.text
    }

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

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val edits = TagEdits(
        title = title.trim().takeIf { it != track.title.orEmpty() },
        artist = artist.trim().takeIf { it != track.artist.orEmpty() },
        album = album.trim().takeIf { it != track.album.orEmpty() },
        genre = genre.trim().takeIf { it != track.genre.orEmpty() },
        year = year.trim().takeIf { it != track.year?.toString().orEmpty() },
    )
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !saving || it != SheetValue.Hidden },
    )

    val fileLabel = remember(track.folderPath, track.fileName, track.audioUri) {
        track.fileName?.let { name ->
            track.folderPath?.takeIf(String::isNotBlank)?.let { folder ->
                "${folder.trimEnd('/')}/$name"
            } ?: name
        } ?: track.audioUri
    }
    val formatLabel = remember(track.fileName, track.sizeBytes, track.durationMs) {
        fileFormatLabel(track.fileName, track.sizeBytes, track.durationMs)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(width = 32.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(uri = track.artworkUri, size = 56.dp)
                Column(modifier = Modifier.weight(1f)) {
                    if (editing) Text(
                        stringResource(Res.string.info_edit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = track.title ?: stringResource(Res.string.track_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
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
                if (!editing) {
                    FilledTonalIconButton(
                        onClick = { editing = true },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(Res.string.info_edit),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = editing,
                transitionSpec = { motionFadeThrough(reduceMotion) },
                label = "track-info-mode",
            ) { isEditing ->
                if (isEditing) {
                    Column(
                        modifier = Modifier.inactiveForMotion(isEditing != editing)
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    MetadataField(stringResource(Res.string.info_title), title, enabled = !saving) { title = it }
                    MetadataField(stringResource(Res.string.info_artist), artist, enabled = !saving) { artist = it }
                    MetadataField(stringResource(Res.string.info_album), album, enabled = !saving) { album = it }
                    MetadataField(stringResource(Res.string.info_genre), genre, enabled = !saving) { genre = it }
                    MetadataField(stringResource(Res.string.info_year), year, enabled = !saving, last = true) { input ->
                        // Filtered at entry rather than validated on save: a year is digits, and
                        // rejecting the field afterwards would lose the rest of the edit.
                        year = input.filter(Char::isDigit).take(4)
                    }

                    AnimatedContent(
                        targetState = failure,
                        transitionSpec = { motionFadeThrough(reduceMotion) },
                        label = "track-info-failure",
                    ) { outcome ->
                        outcome?.let {
                            Text(
                                text = failureMessage(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }

                    Text(
                        text = stringResource(Res.string.info_edit_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    }
                } else {
                    Column(
                        modifier = Modifier.inactiveForMotion(isEditing != editing),
                    ) {
                    InfoRow(stringResource(Res.string.info_album), track.album)
                    InfoRow(stringResource(Res.string.info_genre), track.genre)
                    InfoRow(stringResource(Res.string.info_year), track.year?.toString())
                    InfoRow(
                        stringResource(Res.string.info_duration),
                        track.durationMs?.let(::formatDuration),
                    )
                    InfoRow(stringResource(Res.string.details_format), formatLabel)
                    InfoRow(stringResource(Res.string.details_file), fileLabel, showDivider = false)
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
                    }
                }
            }
            Spacer(modifier = Modifier.padding(bottom = 8.dp))
            }
            if (editing) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                EditorActions(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    cancelLabel = stringResource(Res.string.info_cancel),
                    confirmLabel = stringResource(if (saving) Res.string.info_saving else Res.string.info_save),
                    confirmEnabled = !edits.isEmpty,
                    busy = saving,
                    onCancel = {
                        focus.clearFocus()
                        keyboard?.hide()
                        title = track.title.orEmpty()
                        artist = track.artist.orEmpty()
                        album = track.album.orEmpty()
                        genre = track.genre.orEmpty()
                        year = track.year?.toString().orEmpty()
                        failure = null
                        editing = false
                    },
                    onConfirm = {
                        if (!saving && !edits.isEmpty) {
                            focus.clearFocus()
                            keyboard?.hide()
                            failure = null
                            saving = true
                            // Null leaves an untouched frame intact; an empty string removes it.
                            saveTags(track, edits)
                        }
                    },
                )
            }
        }
    }
}

/** Changes whenever the bytes/location capable of supplying embedded lyrics may have changed. */
internal fun TrackDescriptor.lyricsSourceIdentity(): List<String?> =
    listOf(id.value, audioUri, sourceRevision)

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
private fun InfoRow(label: String, value: String?, showDivider: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(92.dp).alignByBaseline(),
            )
            Text(
                text = value?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.info_not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = if (value.isNullOrBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
        }
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    enabled: Boolean,
    last: Boolean = false,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    EditorTextField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (last) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Down) },
            onDone = { focus.clearFocus(); keyboard?.hide() },
        ),
    )
}
