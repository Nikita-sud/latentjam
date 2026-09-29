/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.nikitasud.latentjam.library.tags.EmbeddedLyrics
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.library.tags.SidecarLyrics
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
            val embedded = try {
                readEmbeddedLyrics(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reportReadFailures) throw failure
                null
            }
            SidecarLyrics.choose(embedded, readSidecarLyrics(track))
        }
    }
}

/** Random access lets the reader parse only what each container needs — the `moov` atom for
 *  M4A, the ID3 prefix for MP3 — never the audio itself, which can run to gigabytes. */
@OptIn(ExperimentalForeignApi::class)
private fun readEmbeddedLyrics(track: TrackDescriptor): Lyrics? {
    val path = filePathOf(track) ?: return null
    val handle = checkNotNull(NSFileHandle.fileHandleForReadingAtPath(path)) { "Cannot open lyrics source" }
    return try {
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus, the ©lyr
        // atom for M4A.
        EmbeddedLyrics.read(FileHandleSource(handle))
    } finally {
        handle.closeFile()
    }
}

/**
 * The `.lrc` beside an imported file in the app's Documents, or null — never a failure: a missing,
 * oversized or undecodable sidecar leaves the embedded lyrics in charge. The app owns that folder,
 * so no permission is involved. Music library items have no file path and never have one.
 */
private fun readSidecarLyrics(track: TrackDescriptor): Lyrics? = try {
    filePathOf(track)?.let(::readSiblingFile)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

@OptIn(ExperimentalForeignApi::class)
private fun readSiblingFile(audioPath: String): Lyrics? {
    val slash = audioPath.lastIndexOf('/')
    if (slash < 0) return null
    val folder = audioPath.substring(0, slash + 1)
    for (name in SidecarLyrics.candidateNames(audioPath.substring(slash + 1))) {
        // A null handle is the file not being there.
        val handle = NSFileHandle.fileHandleForReadingAtPath(folder + name) ?: continue
        val lyrics = try {
            val source = FileHandleSource(handle)
            if (source.length > SidecarLyrics.MAX_BYTES) {
                null
            } else {
                source.read(0, source.length.toInt())?.let(SidecarLyrics::decode)
            }
        } finally {
            handle.closeFile()
        }
        if (lyrics != null) return lyrics
    }
    return null
}

private fun filePathOf(track: TrackDescriptor): String? {
    val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString) ?: return null
    return if (url.isFileURL()) url.path else null
}
