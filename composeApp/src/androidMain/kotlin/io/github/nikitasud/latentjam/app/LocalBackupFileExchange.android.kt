/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingExport(val encoded: String)

@Composable
internal actual fun rememberLocalBackupFileExchange(
    exportMimeType: String,
    importMimeTypes: List<String>,
    onExportResult: (LocalBackupFileResult<Unit>) -> Unit,
    onImportResult: (LocalBackupFileResult<String>) -> Unit,
): LocalBackupFileExchange {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val currentExportResult by rememberUpdatedState(onExportResult)
    val currentImportResult by rememberUpdatedState(onImportResult)
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(exportMimeType),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) {
            currentExportResult(LocalBackupFileResult.Cancelled)
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = resolver.openOutputStream(uri, "wt")
                            ?: error("The selected document cannot be opened")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(pending.encoded) }
                    }.fold(
                        onSuccess = { LocalBackupFileResult.Success(Unit) },
                        onFailure = { LocalBackupFileResult.Failure(it.message ?: "Could not export backup") },
                    )
                }
                currentExportResult(result)
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            currentImportResult(LocalBackupFileResult.Cancelled)
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) { readDocument(resolver = resolver, uri = uri) }
                currentImportResult(result)
            }
        }
    }

    return remember(createDocument, openDocument) {
        LocalBackupFileExchange(
            export = { encoded, suggestedName ->
                if (pendingExport == null) {
                    val fileName = normalizedBackupFileName(suggestedName)
                    pendingExport = PendingExport(encoded)
                    createDocument.launch(fileName)
                }
            },
            import = { openDocument.launch(importMimeTypes.toTypedArray()) },
        )
    }
}

private fun readDocument(
    resolver: android.content.ContentResolver,
    uri: Uri,
): LocalBackupFileResult<String> = runCatching {
    val input = resolver.openInputStream(uri) ?: error("The selected document cannot be opened")
    input.bufferedReader(Charsets.UTF_8).use(BufferedReader::readBounded)
}.fold(
    onSuccess = { LocalBackupFileResult.Success(it) },
    onFailure = { LocalBackupFileResult.Failure(it.message ?: "Could not import backup") },
)

private fun BufferedReader.readBounded(): String {
    val result = StringBuilder()
    val buffer = CharArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(result.length <= MAX_LOCAL_BACKUP_DOCUMENT_CHARS - count) {
            "Backup exceeds the supported size"
        }
        result.append(buffer, 0, count)
    }
    return result.toString()
}
