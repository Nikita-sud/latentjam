/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On Android 11+ this hands the delete to [MediaStore.createDeleteRequest],
 * so the system shows its own confirmation and owns the destructive step —
 * the app never deletes media behind the user's back. Older releases fall
 * back to a direct content-resolver delete, which the caller gates behind
 * its own confirmation dialog.
 */
@Composable
actual fun rememberTrackDeleter(onDeleted: () -> Unit): (List<TrackDescriptor>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnDeleted by rememberUpdatedState(onDeleted)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) currentOnDeleted()
    }

    return { tracks ->
        val uris = tracks.mapNotNull { it.audioUri?.let(Uri::parse) }.distinct()
        if (uris.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                scope.launch {
                    val deletedAny = withContext(Dispatchers.IO) {
                        var removed = false
                        uris.forEach { uri ->
                            if (runCatching { context.contentResolver.delete(uri, null, null) }
                                    .getOrDefault(0) > 0
                            ) {
                                removed = true
                            }
                        }
                        removed
                    }
                    if (deletedAny) currentOnDeleted()
                }
            }
        }
    }
}
