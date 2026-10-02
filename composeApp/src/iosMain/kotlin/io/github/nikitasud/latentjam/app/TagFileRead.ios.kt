/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.IosTagFiles
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun readTagFile(track: TrackDescriptor): TagFileRead = withContext(Dispatchers.IO) {
    // Apple lets no app write the Music library's files; say so rather than "could not read".
    if (IosTagFiles.isMusicLibraryTrack(track.id.value)) return@withContext TagFileRead.NotEditable(TagProblem.MUSIC_LIBRARY)
    try {
        val url = track.audioUri?.takeIf(String::isNotBlank)?.let(NSURL::URLWithString)
        val path = url?.takeIf { it.isFileURL() }?.path ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        val handle = NSFileHandle.fileHandleForReadingAtPath(path)
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        try {
            tagFileReadOf(TagCodecs.read(FileHandleSource(handle)))
        } finally {
            handle.closeFile()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TagFileRead.NotEditable(TagProblem.UNREADABLE)
    }
}
