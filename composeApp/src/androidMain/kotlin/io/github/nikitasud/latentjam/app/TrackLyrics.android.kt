/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.library.tags.EmbeddedLyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLyricsReader(): suspend (TrackDescriptor) -> String? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { track ->
            withContext(Dispatchers.IO) {
                try {
                    readEmbeddedLyrics(context, track)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}

private fun readEmbeddedLyrics(context: Context, track: TrackDescriptor): String? {
    val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
    return context.contentResolver.openInputStream(uri)?.use { input ->
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus —
        // reading only ID3 silently answered "no lyrics" for every correctly tagged Opus.
        EmbeddedLyrics.read(InputStreamByteSource(input))
    }
}
