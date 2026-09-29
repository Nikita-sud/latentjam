/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.nikitasud.latentjam.library.tags.EmbeddedLyrics
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath

@Composable
internal actual fun rememberLyricsReader(reportReadFailures: Boolean): suspend (TrackDescriptor) -> Lyrics? = remember(reportReadFailures) {
    { track ->
        withContext(Dispatchers.Default) {
            try {
                readEmbeddedLyrics(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reportReadFailures) throw failure
                null
            }
        }
    }
}

/** Random access lets the reader parse only what each container needs — the `moov` atom for
 *  M4A, the ID3 prefix for MP3 — never the audio itself, which can run to gigabytes. */
@OptIn(ExperimentalForeignApi::class)
private fun readEmbeddedLyrics(track: TrackDescriptor): Lyrics? {
    val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString) ?: return null
    if (!url.isFileURL()) return null
    val path = url.path ?: return null
    val handle = checkNotNull(NSFileHandle.fileHandleForReadingAtPath(path)) { "Cannot open lyrics source" }
    return try {
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus, the ©lyr
        // atom for M4A.
        EmbeddedLyrics.read(FileHandleSource(handle))
    } finally {
        handle.closeFile()
    }
}
