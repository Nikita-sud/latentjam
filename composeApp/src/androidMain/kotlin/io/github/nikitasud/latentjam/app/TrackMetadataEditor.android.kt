/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberMetadataEditor(onSaved: () -> Unit): (TrackDescriptor, TrackEdits) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)
    // Consent arrives asynchronously, so the edit has to survive the round trip to the system
    // dialog and back. Held here rather than passed through the Intent, which cannot carry it.
    val pending = remember { arrayOfNulls<Pair<Uri, TrackEdits>>(1) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val (uri, edits) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        scope.launch {
            if (applyEdits(context, uri, edits)) currentOnSaved()
        }
    }

    return { track, edits ->
        val uri = track.audioUri?.let(Uri::parse)
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Ask first: on scoped storage the update throws without consent, and catching a
                // RecoverableSecurityException after the fact is the messier of the two paths.
                pending[0] = uri to edits
                val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else {
                scope.launch {
                    if (applyEdits(context, uri, edits)) currentOnSaved()
                }
            }
        }
    }
}

private suspend fun applyEdits(
    context: android.content.Context,
    uri: Uri,
    edits: TrackEdits,
): Boolean = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        edits.title?.let { put(MediaStore.Audio.Media.TITLE, it) }
        edits.artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
        edits.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
        edits.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
        // GENRE became writable on the audio table in API 30; below that it lives in a separate
        // relation this app does not manage, so the field is simply not offered there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            edits.genre?.let { put(MediaStore.Audio.Media.GENRE, it) }
        }
    }
    if (values.size() == 0) return@withContext false
    runCatching { context.contentResolver.update(uri, values, null, null) }.getOrDefault(0) > 0
}
