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
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album art lives inside the same tag, so a real-world tag prefix can be megabytes. */
private const val MAX_TAG_BYTES = 8 * 1024 * 1024

@Composable
internal actual fun rememberLyricsReader(): suspend (TrackDescriptor) -> String? {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { track ->
            withContext(Dispatchers.IO) {
                runCatching { readEmbeddedLyrics(context, track) }.getOrNull()
            }
        }
    }
}

private fun readEmbeddedLyrics(context: Context, track: TrackDescriptor): String? {
    val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
    return context.contentResolver.openInputStream(uri)?.use { input ->
        val header = ByteArray(Id3Tags.HEADER_SIZE)
        if (!readFully(input, header, header.size)) return null
        val tagLength = Id3Tags.tagLength(header) ?: return null
        if (tagLength <= header.size) return null
        val prefix = ByteArray(tagLength.coerceAtMost(MAX_TAG_BYTES))
        header.copyInto(prefix)
        // A short read means a truncated tag; the parser refuses it rather than misreading.
        readFully(input, prefix, prefix.size - header.size, offset = header.size)
        Id3Tags.lyrics(prefix)
    }
}

private fun readFully(
    input: java.io.InputStream,
    target: ByteArray,
    count: Int,
    offset: Int = 0,
): Boolean {
    var done = 0
    while (done < count) {
        val read = input.read(target, offset + done, count - done)
        if (read <= 0) return false
        done += read
    }
    return true
}
