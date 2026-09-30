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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_edit_album
import io.github.nikitasud.latentjam.app.generated.resources.action_edit_artist
import io.github.nikitasud.latentjam.app.generated.resources.bulk_different
import io.github.nikitasud.latentjam.app.generated.resources.bulk_done
import io.github.nikitasud.latentjam.app.generated.resources.bulk_file_reason
import io.github.nikitasud.latentjam.app.generated.resources.bulk_keep_field
import io.github.nikitasud.latentjam.app.generated.resources.bulk_more_files
import io.github.nikitasud.latentjam.app.generated.resources.bulk_not_changed
import io.github.nikitasud.latentjam.app.generated.resources.bulk_not_editable
import io.github.nikitasud.latentjam.app.generated.resources.bulk_nothing_editable
import io.github.nikitasud.latentjam.app.generated.resources.bulk_reading
import io.github.nikitasud.latentjam.app.generated.resources.bulk_remove_field
import io.github.nikitasud.latentjam.app.generated.resources.bulk_removed
import io.github.nikitasud.latentjam.app.generated.resources.bulk_result
import io.github.nikitasud.latentjam.app.generated.resources.bulk_save
import io.github.nikitasud.latentjam.app.generated.resources.bulk_saving
import io.github.nikitasud.latentjam.app.generated.resources.bulk_stop
import io.github.nikitasud.latentjam.app.generated.resources.count_fields
import io.github.nikitasud.latentjam.app.generated.resources.count_files
import io.github.nikitasud.latentjam.app.generated.resources.info_album
import io.github.nikitasud.latentjam.app.generated.resources.info_album_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_artist
import io.github.nikitasud.latentjam.app.generated.resources.info_cancel
import io.github.nikitasud.latentjam.app.generated.resources.info_disc_number
import io.github.nikitasud.latentjam.app.generated.resources.info_disc_total
import io.github.nikitasud.latentjam.app.generated.resources.info_edit
import io.github.nikitasud.latentjam.app.generated.resources.info_genre
import io.github.nikitasud.latentjam.app.generated.resources.info_saving
import io.github.nikitasud.latentjam.app.generated.resources.info_track_total
import io.github.nikitasud.latentjam.app.generated.resources.info_year
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Where an N-track edit was opened from: it names the sheet and puts that field first. */
internal enum class BulkEditScope { ALBUM, ARTIST, SELECTION }

private const val READ_CONCURRENCY = 4
private const val LISTED_FILES = 20

private val BulkFormSaver = Saver<BulkTagForm, List<String>>(
    save = { it.toSaveable() },
    restore = { BulkTagForm.fromSaveable(it) },
)

private val BulkResultSaver = Saver<TagSaveResult?, List<String>>(
    save = { it?.toSaveable() ?: emptyList() },
    restore = { saved -> tagSaveResultOf(saved)?.takeIf { it.entries.isNotEmpty() } },
)

/**
 * Edits the fields [tracks] can share, all at once (spec §6.2–§6.3).
 *
 * The sheet reads every file first, so which files cannot be edited, and why, is known before
 * anything is typed. Those files are set aside, never sent to the writer. The files are read once
 * per set of tracks: a rescan while the sheet is open does not read them again, so the fields never
 * vanish mid-edit. That is safe because a field the user left alone is never written, whatever the
 * files hold by the time of the save.
 *
 * [onSaved] runs after any save that wrote at least one file, with the tracks the save was for. A
 * save that wrote every file closes the sheet; any other result stays on screen, file by file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkTagEditorSheet(
    tracks: List<TrackDescriptor>,
    scope: BulkEditScope,
    onSaved: (List<TrackDescriptor>, TagSaveResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutines = rememberCoroutineScope()
    val access = rememberTagWriteAccess()
    val trackIds = remember(tracks) { tracks.map { it.id } }
    var targets by remember(trackIds) { mutableStateOf<BulkTargets?>(null) }
    var readCount by remember(trackIds) { mutableIntStateOf(0) }
    LaunchedEffect(trackIds) {
        val reading = tracks
        val permits = Semaphore(READ_CONCURRENCY)
        val reads = coroutineScope {
            reading.map { track ->
                async { permits.withPermit { readTagFile(track) }.also { readCount++ } }
            }.awaitAll()
        }
        targets = bulkTargets(reading, reads)
    }
    val baseline = remember(targets) { targets?.let { BulkBaseline.of(it.editable.map { (_, snapshot) -> snapshot }) } }
    val editable = remember(targets) { targets?.editable?.map { it.first }.orEmpty() }

    var form by rememberSaveable(trackIds, stateSaver = BulkFormSaver) { mutableStateOf(BulkTagForm()) }
    var result by rememberSaveable(trackIds, stateSaver = BulkResultSaver) { mutableStateOf(null) }
    var failure by remember(trackIds) { mutableStateOf<TagProblem?>(null) }

    // On the app's scope: the sheet's own is cancelled as it leaves, which would keep the file.
    fun forgetPickedCover() {
        (form.cover as? CoverChoice.Replace)?.let { AppGraph.appScope.launch { deleteTagCover(it.reference) } }
    }

    val saver = rememberTagSaver { finished ->
        // The user closed the system's permission dialog. They know they did.
        if (finished.cancelled) return@rememberTagSaver
        if (finished.savedCount > 0) {
            // From the result's own keys, so a report claimed after recreation, before the files
            // were read again, still names the right tracks.
            val keys = finished.entries.mapTo(HashSet()) { it.key }
            onSaved(tracks.filter { access?.keyOf(it) in keys }, finished)
        }
        if (finished.notChanged.isEmpty()) {
            forgetPickedCover()
            onDismiss()
        } else {
            result = finished
        }
    }
    val saving = saver?.busy == true
    val progress = saver?.progress()
    val pickCover = rememberTagCoverPicker { pick ->
        when (pick) {
            is TagCoverPick.Picked -> {
                forgetPickedCover()
                form = form.copy(cover = CoverChoice.Replace(pick.reference))
            }
            TagCoverPick.Failed -> failure = TagProblem.BAD_IMAGE
            TagCoverPick.Cancelled -> Unit
        }
    }
    // True from the Save tap until the saver owns the request: reading a picked cover suspends.
    var starting by remember { mutableStateOf(false) }
    val busy = saving || starting
    val busyNow by rememberUpdatedState(busy)

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !busyNow || it != SheetValue.Hidden },
    )
    val leave = {
        if (!busyNow) {
            forgetPickedCover()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = leave,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(
                            when (scope) {
                                BulkEditScope.ALBUM -> Res.string.action_edit_album
                                BulkEditScope.ARTIST -> Res.string.action_edit_artist
                                BulkEditScope.SELECTION -> Res.string.info_edit
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = pluralStringResource(Res.plurals.count_files, tracks.size, tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val shownResult = result
                val shownTargets = targets
                when {
                    shownResult != null -> ResultList(shownResult, tracks, access)
                    shownTargets == null || baseline == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(Res.string.bulk_reading, readCount, tracks.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        if (shownTargets.notEditable.isNotEmpty()) NotEditableList(shownTargets.notEditable)
                        if (editable.isEmpty()) {
                            Text(stringResource(Res.string.bulk_nothing_editable), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            CoverEditRow(
                                choice = form.cover,
                                currentUri = editable.firstNotNullOfOrNull { it.artworkUri },
                                canRemove = shownTargets.editable.any { (_, snapshot) -> snapshot.cover != null },
                                otherPictures = 0,
                                enabled = !busy,
                                onReplace = pickCover,
                                onRemove = { form = form.copy(cover = CoverChoice.Remove) },
                                onKeep = {
                                    forgetPickedCover()
                                    form = form.copy(cover = CoverChoice.Keep)
                                },
                            )
                            BulkFields(
                                scope = scope,
                                form = form,
                                baseline = baseline,
                                count = editable.size,
                                enabled = !busy,
                                onForm = { form = it },
                            )
                        }
                        failure?.let {
                            Text(
                                tagProblemText(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            val actions = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            when {
                result != null -> Button(
                    onClick = {
                        forgetPickedCover()
                        onDismiss()
                    },
                    modifier = actions.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(Res.string.bulk_done)) }
                // In the fixed footer, not below the fields: Stop must stay in reach without scrolling.
                saving -> SavingProgress(progress, onStop = { saver?.stop() }, modifier = actions)
                else -> {
                    val fieldCount = baseline?.let { form.changedFieldCount(it) } ?: 0
                    val fields = pluralStringResource(Res.plurals.count_fields, fieldCount, fieldCount)
                    val files = pluralStringResource(Res.plurals.count_files, editable.size, editable.size)
                    val numbersValid = baseline?.let { form.edits(it).numbersAreValid } == true
                    EditorActions(
                        modifier = actions,
                        cancelLabel = stringResource(Res.string.info_cancel),
                        confirmLabel = stringResource(Res.string.bulk_save, fields, files),
                        confirmEnabled = fieldCount > 0 && numbersValid && editable.isNotEmpty(),
                        busy = starting,
                        onCancel = leave,
                        onConfirm = {
                            // Read live, not from the last composition: a second tap within a frame
                            // must not enqueue the same save twice.
                            val base = baseline
                            val current = form
                            val to = editable
                            val ready = !busyNow && base != null && to.isNotEmpty() &&
                                current.changedFieldCount(base) > 0 && current.edits(base).numbersAreValid
                            if (ready) {
                                focus.clearFocus()
                                keyboard?.hide()
                                failure = null
                                starting = true
                                coroutines.launch {
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
                                            else -> saver.start(to, current.edits(base, cover))
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
}

/** The fields in the order the entry point suggests: an album leads with the album, an artist with the artist. */
@Composable
private fun BulkFields(
    scope: BulkEditScope,
    form: BulkTagForm,
    baseline: BulkBaseline,
    count: Int,
    enabled: Boolean,
    onForm: (BulkTagForm) -> Unit,
) {
    val ordered = when (scope) {
        BulkEditScope.ALBUM -> listOf(BulkField.ALBUM, BulkField.ALBUM_ARTIST, BulkField.ARTIST, BulkField.GENRE)
        else -> listOf(BulkField.ARTIST, BulkField.ALBUM, BulkField.ALBUM_ARTIST, BulkField.GENRE)
    }
    for (field in ordered) BulkTextField(field, form, baseline, count, enabled, onForm)
    BulkTextField(BulkField.YEAR, form, baseline, count, enabled, onForm, KeyboardType.Number, ::yearInput)
    BulkTextField(BulkField.TRACK_TOTAL, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BulkTextField(
            BulkField.DISC_NUMBER, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput,
            Modifier.weight(1f),
        )
        BulkTextField(
            BulkField.DISC_TOTAL, form, baseline, count, enabled, onForm, KeyboardType.Number, ::numberInput,
            Modifier.weight(1f),
        )
    }
    if (!form.edits(baseline).numbersAreValid) {
        Text(
            tagProblemText(TagProblem.BAD_NUMBER),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * One field in its three states. The clear control removes the field from every file, a visible,
 * deliberate choice; undo returns it to Keep. Typing into a removed field sets it instead. An
 * emptied box is Keep, so it shows what will be kept rather than looking removed. The clear
 * control is offered only when some file holds the field: there is nothing else to remove.
 */
@Composable
private fun BulkTextField(
    field: BulkField,
    form: BulkTagForm,
    baseline: BulkBaseline,
    count: Int,
    enabled: Boolean,
    onForm: (BulkTagForm) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val removed = form.removed(field)
    val shared = baseline.shared[field]
    EditorTextField(
        label = stringResource(field.label()),
        value = if (removed) "" else form.text(field, baseline),
        onValueChange = { onForm(form.typed(field, filter(it))) },
        enabled = enabled,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        placeholder = when {
            removed -> pluralStringResource(Res.plurals.bulk_removed, count, count)
            shared == SharedValue.Different -> stringResource(Res.string.bulk_different)
            else -> baseline.initialText(field).takeIf(String::isNotBlank)
        },
        trailingIcon = when {
            removed -> {
                {
                    IconButton(onClick = { onForm(form.keep(field)) }, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(Res.string.bulk_keep_field))
                    }
                }
            }
            shared != SharedValue.Same("") -> {
                {
                    IconButton(onClick = { onForm(form.remove(field)) }, enabled = enabled) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.bulk_remove_field))
                    }
                }
            }
            else -> null
        },
    )
}

private fun BulkField.label(): StringResource = when (this) {
    BulkField.ARTIST -> Res.string.info_artist
    BulkField.ALBUM -> Res.string.info_album
    BulkField.ALBUM_ARTIST -> Res.string.info_album_artist
    BulkField.GENRE -> Res.string.info_genre
    BulkField.YEAR -> Res.string.info_year
    BulkField.TRACK_TOTAL -> Res.string.info_track_total
    BulkField.DISC_NUMBER -> Res.string.info_disc_number
    BulkField.DISC_TOTAL -> Res.string.info_disc_total
}

@Composable
private fun NotEditableList(files: List<Pair<TrackDescriptor, TagProblem>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(Res.plurals.bulk_not_editable, files.size, files.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        files.take(LISTED_FILES).forEach { (track, problem) ->
            FileReasonLine(trackLabel(track, track.id.value), tagProblemText(problem))
        }
        MoreFiles(files.size - LISTED_FILES)
    }
}

@Composable
private fun ResultList(result: TagSaveResult, tracks: List<TrackDescriptor>, access: TagWriteAccess?) {
    val byKey = remember(tracks, access) { tracks.associateBy { access?.keyOf(it) ?: it.id.value } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.bulk_result, result.savedCount, result.entries.size),
            style = MaterialTheme.typography.bodyLarge,
        )
        val notChanged = result.notChanged
        if (notChanged.isNotEmpty()) {
            Text(
                pluralStringResource(Res.plurals.bulk_not_changed, notChanged.size, notChanged.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            notChanged.take(LISTED_FILES).forEach { entry ->
                FileReasonLine(trackLabel(byKey[entry.key], entry.key), entry.problem?.let { tagProblemText(it) }.orEmpty())
            }
            MoreFiles(notChanged.size - LISTED_FILES)
        }
    }
}

@Composable
private fun FileReasonLine(name: String, reason: String) {
    Text(
        text = stringResource(Res.string.bulk_file_reason, name, reason),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoreFiles(count: Int) {
    if (count > 0) {
        Text(
            pluralStringResource(Res.plurals.bulk_more_files, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Saved 5 of 12" while this sheet's save runs, with Stop; before it starts, it waits its turn. */
@Composable
private fun SavingProgress(progress: TagWriteProgress?, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.info_saving), style = MaterialTheme.typography.bodyMedium)
        } else {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(Res.string.bulk_saving, progress.done, progress.total),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(Res.string.bulk_stop)) }
    }
}
