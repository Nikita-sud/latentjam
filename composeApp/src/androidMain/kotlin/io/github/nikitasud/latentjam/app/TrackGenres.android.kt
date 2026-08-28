/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.net.Uri
import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.library.tags.TagFacts
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun readEmbeddedFacts(track: TrackDescriptor): EmbeddedTagFacts? =
    withContext(Dispatchers.IO) {
        try {
            val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                ?: return@withContext null
            AndroidAppContext.value.contentResolver.openInputStream(uri)?.use { input ->
                TagFacts.embedded(InputStreamByteSource(input))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

/** Adapts a one-pass stream to the parser's pull interface; no rewinding, no buffering. */
internal class InputStreamByteSource(private val input: InputStream) : GenreTags.ByteSource {

    override fun read(count: Int): ByteArray? {
        val target = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = input.read(target, done, count - done)
            if (read <= 0) return null
            done += read
        }
        return target
    }

    override fun readUpTo(count: Int): ByteArray {
        val target = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = input.read(target, done, count - done)
            if (read <= 0) break
            done += read
        }
        return if (done == count) target else target.copyOf(done)
    }

    override fun skip(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip() may lawfully return 0 on a stream that can still read; fall back.
            if (input.read() < 0) return false
            remaining -= 1
        }
        return true
    }
}
