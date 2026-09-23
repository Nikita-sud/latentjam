/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.BufferedReader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLocalBackupFileExchange(
    exportMimeType: String,
    importMimeTypes: List<String>,
    onExportResult: (LocalBackupFileResult<Unit>) -> Unit,
    onImportResult: (LocalBackupFileResult<String>) -> Unit,
): LocalBackupFileExchange {
    val activity = LocalActivity.current as? ComponentActivity
        ?: return LocalBackupFileExchange(
            export = { _, _ -> onExportResult(LocalBackupFileResult.Failure("Document picker unavailable")) },
            import = { onImportResult(LocalBackupFileResult.Failure("Document picker unavailable")) },
        )
    // Backup and playlist exchange can coexist. Only this small owner key enters saved state;
    // potentially large document text stays in the retained ViewModel, never in a Bundle.
    val ownerKey = rememberSaveable { "document-exchange-${UUID.randomUUID()}" }
    val model = remember(activity, ownerKey) {
        val resolver = activity.applicationContext.contentResolver
        val factory = viewModelFactory {
            initializer {
                LocalBackupExchangeModel(
                    handle = createSavedStateHandle(),
                    writeDocument = { encoded, destination ->
                        writeDocument(resolver, Uri.parse(destination), encoded)
                    },
                    readDocument = { source -> readDocument(resolver, Uri.parse(source)) },
                )
            }
        }
        ViewModelProvider(activity, factory)[ownerKey, LocalBackupExchangeModel::class.java]
    }
    val currentExportResult by rememberUpdatedState(onExportResult)
    val currentImportResult by rememberUpdatedState(onImportResult)
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(exportMimeType),
    ) { uri -> model.exportDestination(uri?.toString()) }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.importSource(uri?.toString())
    }
    var resumed by remember(activity) {
        mutableStateOf(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(activity, model) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            // A screen dismissal releases its payload; rotation only replaces the callback.
            if (!activity.isChangingConfigurations) model.abandon()
        }
    }
    val completed by model.completed.collectAsState()
    val inProgress by model.inProgress.collectAsState()
    LaunchedEffect(completed, resumed) {
        val result = completed
        if (result != null && resumed) {
            model.acknowledge()
            when (result) {
                is LocalBackupExchangeResult.Export -> currentExportResult(result.result)
                is LocalBackupExchangeResult.Import -> currentImportResult(result.result)
            }
        }
    }
    return remember(model, createDocument, openDocument, importMimeTypes, inProgress) {
        LocalBackupFileExchange(
            export = { encoded, suggestedName ->
                if (model.beginExport(encoded)) {
                    try {
                        createDocument.launch(normalizedBackupFileName(suggestedName))
                    } catch (failure: Exception) {
                        model.failedToLaunch(failure)
                    }
                }
            },
            import = {
                if (model.beginImport()) {
                    try {
                        openDocument.launch(importMimeTypes.toTypedArray())
                    } catch (failure: Exception) {
                        model.failedToLaunch(failure)
                    }
                }
            },
            inProgress = inProgress,
        )
    }
}

internal sealed interface LocalBackupExchangeResult {
    data class Export(val result: LocalBackupFileResult<Unit>) : LocalBackupExchangeResult
    data class Import(val result: LocalBackupFileResult<String>) : LocalBackupExchangeResult
}

/** Retains picker input, IO and undelivered results across configuration changes. */
internal class LocalBackupExchangeModel(
    private val handle: SavedStateHandle,
    private val writeDocument: suspend (encoded: String, destination: String) -> Unit,
    private val readDocument: suspend (source: String) -> String,
) : ViewModel() {
    private var operation: String? = handle["operation"]
    private var picking = false
    private var pendingExport: String? = null
    private var work: Job? = null
    private var generation = 0L
    // Process death cannot retain the payload or a running IO job. Cancel once and allow retry;
    // a late picker result must never write a missing payload or resurrect an abandoned request.
    val completed = MutableStateFlow<LocalBackupExchangeResult?>(
        when (operation) {
            "export" -> LocalBackupExchangeResult.Export(LocalBackupFileResult.Cancelled)
            "import" -> LocalBackupExchangeResult.Import(LocalBackupFileResult.Cancelled)
            else -> null
        },
    )
    val inProgress = MutableStateFlow(operation != null)

    fun beginExport(encoded: String): Boolean {
        if (!begin("export")) return false
        pendingExport = encoded
        return true
    }

    fun beginImport(): Boolean = begin("import")

    private fun begin(kind: String): Boolean {
        if (inProgress.value) return false
        generation++
        operation = kind
        handle["operation"] = kind
        picking = true
        inProgress.value = true
        return true
    }

    fun failedToLaunch(failure: Exception) {
        if (!picking) return
        val result = LocalBackupFileResult.Failure(failure.message ?: "Could not open document picker")
        complete(
            when (operation) {
                "export" -> LocalBackupExchangeResult.Export(result)
                "import" -> LocalBackupExchangeResult.Import(result)
                else -> return
            },
        )
    }

    fun exportDestination(destination: String?) {
        if (!picking || operation != "export") return
        picking = false
        val encoded = pendingExport
        pendingExport = null
        if (destination == null || encoded == null) {
            complete(LocalBackupExchangeResult.Export(LocalBackupFileResult.Cancelled))
            return
        }
        val request = generation
        work = viewModelScope.launch {
            val result = try {
                writeDocument(encoded, destination)
                LocalBackupFileResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LocalBackupFileResult.Failure(failure.message ?: "Could not export backup")
            } catch (_: OutOfMemoryError) {
                LocalBackupFileResult.Failure("Backup exceeds available memory")
            }
            if (request == generation) complete(LocalBackupExchangeResult.Export(result))
        }
    }

    fun importSource(source: String?) {
        if (!picking || operation != "import") return
        picking = false
        if (source == null) {
            complete(LocalBackupExchangeResult.Import(LocalBackupFileResult.Cancelled))
            return
        }
        val request = generation
        work = viewModelScope.launch {
            val result = try {
                LocalBackupFileResult.Success(readDocument(source))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LocalBackupFileResult.Failure(failure.message ?: "Could not import backup")
            } catch (_: OutOfMemoryError) {
                LocalBackupFileResult.Failure("Backup exceeds available memory")
            }
            if (request == generation) complete(LocalBackupExchangeResult.Import(result))
        }
    }

    private fun complete(result: LocalBackupExchangeResult) {
        picking = false
        pendingExport = null
        work = null
        completed.value = result
    }

    fun acknowledge() {
        completed.value = null
        operation = null
        handle.remove<String>("operation")
        inProgress.value = false
    }

    fun abandon() {
        generation++
        picking = false
        pendingExport = null
        work?.cancel()
        work = null
        acknowledge()
    }
}

private suspend fun writeDocument(resolver: ContentResolver, uri: Uri, encoded: String) =
    withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri, "wt")
            ?: error("The selected document cannot be opened")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            var offset = 0
            while (offset < encoded.length) {
                currentCoroutineContext().ensureActive()
                val count = minOf(8 * 1024, encoded.length - offset)
                writer.write(encoded, offset, count)
                offset += count
            }
        }
    }

private suspend fun readDocument(resolver: ContentResolver, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: error("The selected document cannot be opened")
        input.bufferedReader(Charsets.UTF_8).use { it.readBounded() }
    }

private suspend fun BufferedReader.readBounded(): String {
    val result = StringBuilder()
    val buffer = CharArray(8 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) break
        check(result.length <= MAX_LOCAL_BACKUP_DOCUMENT_CHARS - count) {
            "Backup exceeds the supported size"
        }
        result.append(buffer, 0, count)
    }
    return result.toString()
}
