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
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.details_file
import io.github.nikitasud.latentjam.app.generated.resources.details_format
import io.github.nikitasud.latentjam.app.generated.resources.info_album
import io.github.nikitasud.latentjam.app.generated.resources.info_album_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_cancel
import io.github.nikitasud.latentjam.app.generated.resources.info_disc_number
import io.github.nikitasud.latentjam.app.generated.resources.info_disc_total
import io.github.nikitasud.latentjam.app.generated.resources.info_duration
import io.github.nikitasud.latentjam.app.generated.resources.info_edit
import io.github.nikitasud.latentjam.app.generated.resources.info_lyrics
import io.github.nikitasud.latentjam.app.generated.resources.info_edit_note
import io.github.nikitasud.latentjam.app.generated.resources.info_genre
import io.github.nikitasud.latentjam.app.generated.resources.info_not_set
import io.github.nikitasud.latentjam.app.generated.resources.info_reading_file
import io.github.nikitasud.latentjam.app.generated.resources.info_save
import io.github.nikitasud.latentjam.app.generated.resources.info_saving
import io.github.nikitasud.latentjam.app.generated.resources.info_title
import io.github.nikitasud.latentjam.app.generated.resources.info_track_number
import io.github.nikitasud.latentjam.app.generated.resources.info_track_total
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_finish
import io.github.nikitasud.latentjam.app.generated.resources.track_unknown_artist
import io.github.nikitasud.latentjam.app.generated.resources.track_untitled
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.launch
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
 * It rewrites the file's own tags, in whichever of the four formats the file is (ID3v2 in an MP3,
 * Vorbis comments in FLAC and Ogg, the metadata atoms in an MP4), through the durable writer, then
 * has the media index re-read it — see [FileWriteStatus], which also records why the obvious
 * shortcut of writing MediaStore's columns does not work. A correction therefore travels with the
 * file and is visible to every other app. For the same reason the edit fields are filled from the
 * file itself rather than from the media index: what the user corrects is what the file holds.
 *
 * A save can legitimately fail: the writer refuses any file whose existing tag it cannot reproduce
 * byte for byte, because a partial rewrite is indistinguishable from deleting the frames it did not
 * understand. That refusal is shown here in words. The one thing this screen will never do is
 * report success it did not get — the version of this UI that did was removed for it.
 *
 * @param onSaved runs with the save's result after the file and the media index both hold the new
 *   tags, so the caller can refresh its library snapshot and see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackInfoSheet(
    track: TrackDescriptor,
    onSaved: (TagSaveResult) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val lyricsSource = track.lyricsSourceIdentity()
    val reduceMotion = rememberReduceMotion()
    var editing by rememberSaveable(track.id.value) { mutableStateOf(false) }
    var lyrics by remember(lyricsSource) { mutableStateOf<String?>(null) }
    val readLyrics = rememberLyricsReader()
    val lyricsSources = rememberLyricsSourcesRevision()
    LaunchedEffect(lyricsSource, lyricsSources) {
        lyrics = null
        lyrics = readLyrics(track)?.text
    }

    val scope = rememberCoroutineScope()
    val access = rememberTagWriteAccess()
    // Read only once editing starts: the info view never opens the file for tags it does not show.
    var read by remember(track.id, track.sourceRevision) { mutableStateOf<TagFileRead?>(null) }
    LaunchedEffect(editing, track.id, track.sourceRevision) {
        if (editing && read == null) read = readTagFile(track)
    }
    val snapshot = (read as? TagFileRead.Ready)?.snapshot
    val fileForm = remember(snapshot) { snapshot?.let(TagEditorForm::of) }
    val formSaver = Saver<TagEditorForm?, List<String>>(
        save = { it?.toSaveable() ?: emptyList() },
        restore = { TagEditorForm.fromSaveable(it) },
    )
    var form by rememberSaveable(track.id.value, stateSaver = formSaver) { mutableStateOf(null) }
    // The file as it was when the form was built from it. Edits are measured against this, never
    // against a newer reading, so a field the user did not touch is never written.
    var baseline by rememberSaveable(track.id.value, stateSaver = formSaver) { mutableStateOf(null) }
    // A restored form keeps the user's typing; a fresh one starts from what the file holds. When
    // the file changed since (a rescan, another app, a finished recovery), untouched fields follow it.
    LaunchedEffect(fileForm) {
        val now = fileForm ?: return@LaunchedEffect
        val current = form
        val from = baseline
        form = if (current == null || from == null) now else current.rebased(from = from, to = now)
        baseline = now
    }
    var failure by remember(track.id) { mutableStateOf<TagProblem?>(null) }
    val pending = access?.coordinator?.pendingRecovery?.collectAsState()?.value.orEmpty()
    val interrupted = access?.keyOf(track)?.let { key -> pending.any { it.target == key } } == true

    // On the app's scope: the sheet's own is cancelled as it leaves, which would keep the file.
    fun forgetPickedCover() {
        (form?.cover as? CoverChoice.Replace)?.let { AppGraph.appScope.launch { deleteTagCover(it.reference) } }
    }

    val saver = rememberTagSaver { result ->
        val entry = result.entries.singleOrNull()
        when {
            entry == null -> failure = TagProblem.FAILED
            entry.saved -> {
                forgetPickedCover()
                onSaved(result)
                onDismiss()
            }
            // The user closed the system's permission dialog. They know they did.
            result.cancelled -> Unit
            else -> failure = entry.problem
        }
    }
    val saving = saver?.busy == true
    val pickCover = rememberTagCoverPicker { pick ->
        when (pick) {
            is TagCoverPick.Picked -> {
                forgetPickedCover()
                form = form?.copy(cover = CoverChoice.Replace(pick.reference))
            }
            TagCoverPick.Failed -> failure = TagProblem.BAD_IMAGE
            TagCoverPick.Cancelled -> Unit
        }
    }
    // True from the Save tap until the saver owns the request: reading a picked cover suspends.
    var starting by remember(track.id) { mutableStateOf(false) }
    val base = baseline
    val edits = base?.let { form?.edits(it) }
    val canSave = !saving && !starting && !interrupted && fileForm != null && base != null &&
        form?.hasChanges(base) == true && edits?.numbersAreValid == true

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
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
        onDismissRequest = {
            if (!saving) {
                forgetPickedCover()
                onDismiss()
            }
        },
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
                    when {
                        interrupted -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tagProblemText(TagProblem.INTERRUPTED), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { access.coordinator.enqueueRecovery() }) {
                                Text(stringResource(Res.string.tag_recovery_finish))
                            }
                        }
                        read == null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.info_reading_file), style = MaterialTheme.typography.bodyMedium)
                        }
                        read is TagFileRead.NotEditable -> Text(
                            text = tagProblemText((read as TagFileRead.NotEditable).problem),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> form?.let { current ->
                            val enabled = !saving
                            CoverEditRow(
                                choice = current.cover,
                                currentUri = track.artworkUri,
                                canRemove = snapshot?.cover != null,
                                otherPictures = snapshot?.otherPictures ?: 0,
                                enabled = enabled,
                                onReplace = pickCover,
                                onRemove = { form = current.copy(cover = CoverChoice.Remove) },
                                onKeep = {
                                    forgetPickedCover()
                                    form = current.copy(cover = CoverChoice.Keep)
                                },
                            )
                            TextRow(stringResource(Res.string.info_title), current.title, enabled) { form = current.copy(title = it) }
                            TextRow(stringResource(Res.string.info_artist), current.artist, enabled) { form = current.copy(artist = it) }
                            TextRow(stringResource(Res.string.info_album), current.album, enabled) { form = current.copy(album = it) }
                            TextRow(stringResource(Res.string.info_album_artist), current.albumArtist, enabled) {
                                form = current.copy(albumArtist = it)
                            }
                            TextRow(stringResource(Res.string.info_genre), current.genre, enabled) { form = current.copy(genre = it) }
                            TextRow(stringResource(Res.string.info_year), current.year, enabled, KeyboardType.Number) {
                                form = current.copy(year = yearInput(it))
                            }
                            NumberPairField(
                                numberLabel = stringResource(Res.string.info_track_number),
                                totalLabel = stringResource(Res.string.info_track_total),
                                number = current.trackNumber,
                                total = current.trackTotal,
                                enabled = enabled,
                                onNumber = { form = current.copy(trackNumber = it) },
                                onTotal = { form = current.copy(trackTotal = it) },
                            )
                            NumberPairField(
                                numberLabel = stringResource(Res.string.info_disc_number),
                                totalLabel = stringResource(Res.string.info_disc_total),
                                number = current.discNumber,
                                total = current.discTotal,
                                enabled = enabled,
                                onNumber = { form = current.copy(discNumber = it) },
                                onTotal = { form = current.copy(discTotal = it) },
                            )
                            EditorTextField(
                                label = stringResource(Res.string.info_lyrics),
                                value = current.lyrics,
                                onValueChange = { form = current.copy(lyrics = it) },
                                enabled = enabled,
                                singleLine = false,
                                minLines = 3,
                                maxLines = 12,
                            )
                            if (edits?.numbersAreValid == false) {
                                Text(
                                    text = tagProblemText(TagProblem.BAD_NUMBER),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = failure,
                        transitionSpec = { motionFadeThrough(reduceMotion) },
                        label = "track-info-failure",
                    ) { problem ->
                        problem?.let {
                            Text(
                                text = tagProblemText(it),
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
                    InfoRow(stringResource(Res.string.info_album_artist), track.albumArtist)
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
                    confirmEnabled = canSave,
                    busy = saving,
                    onCancel = {
                        focus.clearFocus()
                        keyboard?.hide()
                        forgetPickedCover()
                        form = fileForm
                        baseline = fileForm
                        failure = null
                        editing = false
                    },
                    onConfirm = {
                        val current = form
                        // canSave holds only with a baseline, so base is known non-null here.
                        if (canSave && current != null) {
                            focus.clearFocus()
                            keyboard?.hide()
                            failure = null
                            starting = true
                            scope.launch {
                                try {
                                    val cover = when (val choice = current.cover) {
                                        CoverChoice.Keep -> CoverEdit.Keep
                                        CoverChoice.Remove -> CoverEdit.Remove
                                        is CoverChoice.Replace -> readTagCover(choice.reference)
                                            ?.let { CoverEdit.Replace(it, tagCoverMime(choice.reference)) }
                                    }
                                    when {
                                        cover == null -> failure = TagProblem.BAD_IMAGE
                                        saver == null -> failure = TagProblem.FAILED
                                        // Null leaves an untouched field intact; an empty string removes it.
                                        else -> saver.start(listOf(track), current.edits(base, cover))
                                    }
                                } finally {
                                    starting = false
                                }
                            }
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
private fun TextRow(label: String, value: String, enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    EditorTextField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
    )
}
