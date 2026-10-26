/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.action_close
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_body
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_empty_library
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_failed
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_habits_hint
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_habits_on
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_invalid
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_progress
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_already_present
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_imported
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_not_in_library
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_over_limit
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_songs
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_title
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_truncated
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_result_unreadable
import io.github.nikitasud.latentjam.app.generated.resources.listenbrainz_import_section
import io.github.nikitasud.latentjam.library.MusicLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The running import and its last outcome, for the whole process: the import outlives the screen
 * that started it (like a backup restore), and a screen opened meanwhile shows its progress and,
 * once it ends, its summary.
 */
internal object ListenBrainzImportState {
    /** Rows read so far while an import runs; null when none does. */
    val progress = MutableStateFlow<Long?>(null)

    /** The finished import's report, until the listener closes the summary. */
    val report = MutableStateFlow<ListenBrainzImportReport?>(null)

    /** Why the last import failed, until a screen has shown it. */
    val failure = MutableStateFlow<StringResource?>(null)
}

/** Settings → Local backup: loading a ListenBrainz export into the listening history. */
@Composable
internal fun ListenBrainzImportSection(
    settings: AppSettings,
    library: MusicLibrary,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val progress by ListenBrainzImportState.progress.collectAsState()
    val report by ListenBrainzImportState.report.collectAsState()
    val failure by ListenBrainzImportState.failure.collectAsState()
    // Shown from process-wide state, like the report: the section may be gone when an import
    // fails (scrolled away, Settings closed), and its scope with it.
    LaunchedEffect(failure) {
        val reason = failure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(getString(reason))
        ListenBrainzImportState.failure.value = null
    }
    val learnFromHabits by settings.learnFromHabits.collectAsState()
    val smartRanker by settings.smartRanker.collectAsState()

    val pick = rememberListenBrainzExportPicker { result ->
        when (result) {
            LocalBackupFileResult.Cancelled -> Unit
            is LocalBackupFileResult.Failure -> scope.launch {
                snackbarHostState.showSnackbar(getString(Res.string.listenbrainz_import_failed))
            }
            is LocalBackupFileResult.Success -> {
                if (ListenBrainzImportState.progress.value != null) {
                    result.value.release()
                    return@rememberListenBrainzExportPicker
                }
                ListenBrainzImportState.progress.value = 0L
                scope.launch {
                    // One durable operation, like a restore: once started it runs to the end and
                    // reports, even when the screen closes, so the log is never left half-told.
                    withContext(NonCancellable) {
                        try {
                            val importer = ListenBrainzImporter(history = AppGraph.history, library = library)
                            val outcome = importer.import(
                                source = result.value::open,
                                onProgress = { ListenBrainzImportState.progress.value = it },
                            )
                            if (outcome.imported > 0) AppGraph.noteHistoryImported()
                            ListenBrainzImportState.report.value = outcome
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            ListenBrainzImportState.failure.value = when (failure) {
                                is ListenBrainzLibraryEmptyException -> Res.string.listenbrainz_import_empty_library
                                is IllegalArgumentException -> Res.string.listenbrainz_import_invalid
                                else -> Res.string.listenbrainz_import_failed
                            }
                        } finally {
                            result.value.release()
                            ListenBrainzImportState.progress.value = null
                        }
                    }
                }
            }
        }
    }

    SettingsSection(stringResource(Res.string.listenbrainz_import_section)) {
        SettingsActionRow(
            title = stringResource(Res.string.listenbrainz_import),
            subtitle = stringResource(Res.string.listenbrainz_import_body),
            enabled = progress == null,
            onClick = pick,
        )
        progress?.let { rows ->
            AppLinearProgress(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            SettingsBody(stringResource(Res.string.listenbrainz_import_progress, rows))
        }
    }

    report?.let { finished ->
        val offerHabits = finished.imported > 0 && !learnFromHabits
        val dismiss = { ListenBrainzImportState.report.value = null }
        AppAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(Res.string.listenbrainz_import_result_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(Res.string.listenbrainz_import_result_imported, finished.imported))
                    Text(stringResource(Res.string.listenbrainz_import_result_songs, finished.songs))
                    Text(stringResource(Res.string.listenbrainz_import_result_not_in_library, finished.notInLibrary))
                    Text(stringResource(Res.string.listenbrainz_import_result_already_present, finished.alreadyPresent))
                    if (finished.overLimit > 0) {
                        Text(stringResource(Res.string.listenbrainz_import_result_over_limit, finished.overLimit))
                    }
                    if (finished.unreadable > 0) {
                        Text(stringResource(Res.string.listenbrainz_import_result_unreadable, finished.unreadable))
                    }
                    if (finished.truncated) Text(stringResource(Res.string.listenbrainz_import_result_truncated))
                    if (offerHabits) {
                        Text(
                            text = stringResource(Res.string.listenbrainz_import_habits_hint),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (offerHabits && smartRanker) {
                    TextButton(
                        onClick = {
                            settings.setLearnFromHabits(true)
                            dismiss()
                        },
                    ) {
                        Text(stringResource(Res.string.listenbrainz_import_habits_on))
                    }
                } else {
                    TextButton(onClick = dismiss) { Text(stringResource(Res.string.action_close)) }
                }
            },
            dismissButton = if (offerHabits && smartRanker) {
                { TextButton(onClick = dismiss) { Text(stringResource(Res.string.action_close)) } }
            } else {
                null
            },
        )
    }
}
