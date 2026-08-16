/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TagFacts
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
import platform.posix.memcpy

internal actual suspend fun readEmbeddedFacts(track: TrackDescriptor): EmbeddedTagFacts? =
    withContext(Dispatchers.Default) {
        try {
            readGenresFromFile(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun readGenresFromFile(track: TrackDescriptor): EmbeddedTagFacts? {
    val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString) ?: return null
    if (!url.isFileURL()) return null
    val path = url.path ?: return null
    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    return try {
        TagFacts.embedded(FileHandleByteSource(handle))
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FileHandleByteSource(
    private val handle: NSFileHandle,
) : GenreTags.ByteSource {

    override fun read(count: Int): ByteArray? {
        val bytes = handle.readDataOfLength(count.toULong()).toByteArray()
        return bytes.takeIf { it.size == count }
    }

    override fun readUpTo(count: Int): ByteArray =
        handle.readDataOfLength(count.toULong()).toByteArray()

    override fun skip(count: Long): Boolean {
        // NSFileHandle reads are cheap enough for metadata-scale skips, and readDataOfLength
        // moves the offset for us — no seek bookkeeping to get wrong.
        var remaining = count
        while (remaining > 0) {
            val step = minOf(remaining, SKIP_CHUNK)
            val got = handle.readDataOfLength(step.toULong()).length.toLong()
            if (got <= 0L) return false
            remaining -= got
        }
        return true
    }

    private companion object {
        const val SKIP_CHUNK = 1L shl 20
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
