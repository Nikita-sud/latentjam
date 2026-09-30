/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

internal actual suspend fun readTagFile(track: TrackDescriptor): TagFileRead = withContext(Dispatchers.IO) {
    try {
        val uri = track.audioUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        val descriptor = AndroidAppContext.value.contentResolver.openFileDescriptor(uri, "r")
            ?: return@withContext TagFileRead.NotEditable(TagProblem.UNREADABLE)
        descriptor.use {
            // Not closed: closing a stream over this descriptor closes the descriptor itself, which is
            // the ParcelFileDescriptor's job.
            val channel = FileInputStream(it.fileDescriptor).channel
            tagFileReadOf(TagCodecs.read(FileChannelSource(channel)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TagFileRead.NotEditable(TagProblem.UNREADABLE)
    }
}
