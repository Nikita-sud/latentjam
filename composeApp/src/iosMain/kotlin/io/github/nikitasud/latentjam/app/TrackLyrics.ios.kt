/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.nikitasud.latentjam.library.tags.Id3Tags
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
internal actual fun rememberLyricsReader(): suspend (TrackDescriptor) -> String? = remember {
    { track ->
        withContext(Dispatchers.Default) {
            try {
                readEmbeddedLyrics(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Reads only the bounded ID3 prefix; audio files themselves can be gigabytes. */
@OptIn(ExperimentalForeignApi::class)
private fun readEmbeddedLyrics(track: TrackDescriptor): String? {
    val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString) ?: return null
    if (!url.isFileURL()) return null
    val path = url.path ?: return null
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    return try {
        val header = handle.readDataOfLength(Id3Tags.HEADER_SIZE.toULong()).toByteArray()
        if (header.size != Id3Tags.HEADER_SIZE) return null
        val tagLength = Id3Tags.tagLength(header) ?: return null
        if (tagLength <= Id3Tags.HEADER_SIZE || tagLength > MAX_TAG_BYTES) return null
        handle.seekToFileOffset(0uL)
        val prefix = handle.readDataOfLength(tagLength.toULong()).toByteArray()
        if (prefix.size != tagLength) return null
        Id3Tags.lyrics(prefix)
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
