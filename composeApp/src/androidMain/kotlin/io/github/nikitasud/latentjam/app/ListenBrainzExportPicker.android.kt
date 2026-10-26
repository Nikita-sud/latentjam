/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream

@Composable
internal actual fun rememberListenBrainzExportPicker(
    onResult: (LocalBackupFileResult<PickedExport>) -> Unit,
): () -> Unit {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        current(
            if (uri == null) {
                LocalBackupFileResult.Cancelled
            } else {
                LocalBackupFileResult.Success(PickedExport(opener = { openExport(resolver, uri) }))
            },
        )
    }
    return remember(launcher) {
        {
            try {
                launcher.launch(LISTENBRAINZ_EXPORT_MIME_TYPES)
            } catch (failure: Exception) {
                current(LocalBackupFileResult.Failure(failure.message ?: "Could not open document picker"))
            }
        }
    }
}

private fun openExport(resolver: ContentResolver, uri: Uri): ExportByteSource {
    val stream: InputStream = resolver.openInputStream(uri) ?: error("The selected document cannot be opened")
    return object : ExportByteSource {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)
        override fun close() = stream.close()
    }
}
