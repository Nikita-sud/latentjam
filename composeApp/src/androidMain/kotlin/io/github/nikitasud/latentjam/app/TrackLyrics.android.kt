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
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLyricsReader(reportReadFailures: Boolean): suspend (TrackDescriptor) -> Lyrics? {
    val context = LocalContext.current.applicationContext
    return remember(context, reportReadFailures) {
        { track ->
            withContext(Dispatchers.IO) {
                try {
                    readEmbeddedLyrics(context, track)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (reportReadFailures) throw failure
                    null
                }
            }
        }
    }
}

private fun readEmbeddedLyrics(context: Context, track: TrackDescriptor): Lyrics? {
    val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
    val pfd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "Cannot open lyrics source" }
    return pfd.use {
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus, the ©lyr
        // atom for M4A — reading only ID3 silently answered "no lyrics" for every correctly
        // tagged Opus, and random access lets the MP4 path read just the `moov` atom.
        // Not closed here: closing any stream over this descriptor closes the descriptor
        // itself, which is the ParcelFileDescriptor's job.
        val channel = FileInputStream(it.fileDescriptor).channel
        EmbeddedLyrics.read(FileChannelSource(channel))
    }
}
