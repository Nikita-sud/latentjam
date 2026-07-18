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
actual fun rememberTrackDeleter(onDeleted: () -> Unit): (TrackDescriptor) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnDeleted by rememberUpdatedState(onDeleted)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) currentOnDeleted()
    }

    return { track ->
        val uri = track.audioUri?.let(Uri::parse)
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                            .getOrDefault(0) > 0
                    }
                    if (deleted) currentOnDeleted()
                }
            }
        }
    }
}
