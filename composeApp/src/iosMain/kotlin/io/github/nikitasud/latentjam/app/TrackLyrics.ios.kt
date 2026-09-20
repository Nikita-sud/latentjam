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
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.posix.memcpy

private const val MAX_TAG_BYTES = 8 * 1024 * 1024

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

/** Reads only the bounded ID3 prefix; audio files themselves can be gigabytes. */
@OptIn(ExperimentalForeignApi::class)
private fun readEmbeddedLyrics(track: TrackDescriptor): Lyrics? {
    val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString) ?: return null
    if (!url.isFileURL()) return null
    val path = url.path ?: return null
    val handle = checkNotNull(NSFileHandle.fileHandleForReadingAtPath(path)) { "Cannot open lyrics source" }
    return try {
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus.
        EmbeddedLyrics.read(FileHandleByteSource(handle))
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}
