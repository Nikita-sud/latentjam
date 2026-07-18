/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.library.tags.Id3Refusal
import io.github.nikitasud.latentjam.library.tags.Id3Tags
import io.github.nikitasud.latentjam.library.tags.Id3v1
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.resume

/**
 * Asks the system for write consent, rewrites the file's ID3v2 tag, then makes
 * the media index re-read it.
 *
 * The consent step is [MediaStore.createWriteRequest], the same
 * system-owns-the-decision pattern the delete path uses. It only exists from
 * API 30; below that a write to media the app does not own needs
 * `WRITE_EXTERNAL_STORAGE`, which this app deliberately does not request, so
 * editing reports itself [TagWriteOutcome.Unavailable] there rather than
 * failing at the last moment.
 */
@Composable
actual fun rememberTagWriter(
    onOutcome: (TagWriteOutcome) -> Unit,
): (TrackDescriptor, TagEdits) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnOutcome by rememberUpdatedState(onOutcome)
    // Consent arrives asynchronously, so the edit has to survive the round trip
    // to the system dialog and back. Held here rather than passed through the
    // Intent, which cannot carry it.
    val pending = remember { arrayOfNulls<Pair<Uri, TagEdits>>(1) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val (uri, edits) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        if (result.resultCode != Activity.RESULT_OK) {
            currentOnOutcome(TagWriteOutcome.Cancelled)
        } else {
            scope.launch { currentOnOutcome(saveTags(context, uri, edits)) }
        }
    }

    return { track, edits ->
        val uri = track.audioUri?.let(Uri::parse)
        when {
            uri == null -> currentOnOutcome(TagWriteOutcome.Unavailable)
            edits.isEmpty -> currentOnOutcome(TagWriteOutcome.Saved)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                currentOnOutcome(TagWriteOutcome.Unavailable)
            }
            else -> {
                // Ask first: on scoped storage the open throws without consent,
                // and catching a RecoverableSecurityException after the fact is
                // the messier of the two paths.
                pending[0] = uri to edits
                val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
    }
}

private suspend fun saveTags(context: Context, uri: Uri, edits: TagEdits): TagWriteOutcome {
    val outcome = withContext(Dispatchers.IO) { rewriteFile(context, uri, edits) }
    // Only after the bytes are on disk, and before the caller is told, so a
    // library refresh triggered by [TagWriteOutcome.Saved] reads the new values
    // instead of racing the scanner for them.
    if (outcome == TagWriteOutcome.Saved) rescan(context, uri)
    return outcome
}

/**
 * Everything about the rewrite that is decided before the file is touched.
 *
 * @property tag the complete replacement ID3v2 tag.
 * @property replacedLength bytes at the head of the file [tag] stands in for.
 * @property trailerLength bytes of ID3v1 at the end to drop.
 * @property size the file's current length.
 */
private class Rewrite(
    val tag: ByteArray,
    val replacedLength: Long,
    val trailerLength: Long,
    val size: Long,
) {
    /**
     * True when the new tag occupies exactly the old one's footprint, so the
     * audio does not move.
     *
     * This is the case worth separating out: the write becomes a few kilobytes
     * at the head of the file plus, at most, a truncation of the trailer. The
     * audio is never opened for writing at all, so nothing that goes wrong
     * mid-write can reach it.
     */
    val audioStaysPut: Boolean get() = tag.size.toLong() == replacedLength

    val newSize: Long get() = tag.size + (size - replacedLength - trailerLength)
}

private sealed interface Plan {
    class Ready(val rewrite: Rewrite) : Plan
    class Refused(val reason: Id3Refusal?) : Plan
}

/**
 * Rewrites the tag, leaving the file untouched unless the whole new content is
 * known to be good.
 *
 * There is no atomic path here to reach for: an atomic replace means writing a
 * sibling file and renaming over the original, and a `content://` URI grants
 * access to one file, not to the directory it lives in. What is possible is to
 * make the destructive step as small and as late as it can be, which is what
 * the two branches below do.
 */
private fun rewriteFile(context: Context, uri: Uri, edits: TagEdits): TagWriteOutcome {
    val resolver = context.contentResolver
    var staged: File? = null
    return try {
        val source = resolver.openFileDescriptor(uri, "r") ?: return TagWriteOutcome.Failed
        val rewrite = source.use { pfd ->
            // Not closed here: closing any stream over this descriptor closes the
            // descriptor itself, which is the ParcelFileDescriptor's job.
            val channel = FileInputStream(pfd.fileDescriptor).channel
            when (val plan = planRewrite(channel, edits)) {
                is Plan.Refused -> return TagWriteOutcome.Refused(plan.reason)
                is Plan.Ready -> plan.rewrite.also {
                    // When the audio has to move there is no small write to be
                    // had, so the entire new file is built in the cache first.
                    // Nothing of the original is touched until a complete,
                    // fsynced copy of its replacement exists.
                    if (!it.audioStaysPut) {
                        staged = stage(context, channel, it) ?: return TagWriteOutcome.Failed
                    }
                }
            }
        }

        val written = when (val file = staged) {
            null -> patchHead(resolver, uri, rewrite)
            else -> replaceContents(resolver, uri, file)
        }
        if (written) TagWriteOutcome.Saved else TagWriteOutcome.Failed
    } catch (e: Exception) {
        TagWriteOutcome.Failed
    } finally {
        staged?.delete()
    }
}

private fun planRewrite(channel: FileChannel, edits: TagEdits): Plan {
    val size = channel.size()
    val header = readAt(channel, 0, minOf(size, Id3Tags.HEADER_SIZE.toLong()).toInt())
    // A null length means the header is unusable; buildUpdate is left to say so.
    val tagLength = Id3Tags.tagLength(header) ?: 0
    // Enough bytes either to hold the whole existing tag, or for an untagged
    // file to be recognised as a container that must not be given one.
    val prefixLength = minOf(size, maxOf(tagLength, CONTAINER_PROBE_BYTES).toLong()).toInt()
    val prefix = readAt(channel, 0, prefixLength)

    val update = Id3Tags.buildUpdate(prefix, edits)
        ?: return Plan.Refused(Id3Tags.refusalOf(prefix))

    val tailLength = minOf(size, Id3v1.MAX_TRAILER_SIZE.toLong()).toInt()
    val tail = readAt(channel, size - tailLength, tailLength)
    val trailer = Id3Tags.droppedTrailerLength(tail, edits).toLong()
        // A trailer reaching back into the tag just built is a coincidence, not
        // a trailer. Matches what the whole-file path does with the same case.
        .let { if (size - it >= update.replacedLength) it else 0L }

    return Plan.Ready(Rewrite(update.tag, update.replacedLength.toLong(), trailer, size))
}

/** Builds the complete new file in the cache directory. Null if it came out short. */
private fun stage(context: Context, source: FileChannel, rewrite: Rewrite): File? {
    val file = File.createTempFile("tagwrite", ".tmp", context.cacheDir)
    FileOutputStream(file).use { out ->
        val target = out.channel
        writeAt(target, 0, rewrite.tag)
        // transferTo appends at the target's position, so say where that is.
        target.position(rewrite.tag.size.toLong())
        var pos = rewrite.replacedLength
        val end = rewrite.size - rewrite.trailerLength
        while (pos < end) {
            val moved = source.transferTo(pos, end - pos, target)
            if (moved <= 0L) return null
            pos += moved
        }
        out.fd.sync()
    }
    return file.takeIf { it.length() == rewrite.newSize }
}

/**
 * Overwrites just the head of the file, and drops the ID3v1 trailer if there
 * was one. The audio is not read, written, or moved.
 */
private fun patchHead(resolver: ContentResolver, uri: Uri, rewrite: Rewrite): Boolean {
    val target = resolver.openFileDescriptor(uri, "rw") ?: return false
    target.use { pfd ->
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        writeAt(channel, 0, rewrite.tag)
        if (rewrite.trailerLength > 0L) channel.truncate(rewrite.newSize)
        pfd.fileDescriptor.sync()
    }
    return true
}

/**
 * Copies a staged file over the original.
 *
 * This is the one step that cannot be made atomic, so it is also the only step
 * that can leave a file half-written — and it is attempted twice, because a
 * failure part-way leaves the staged file still holding the complete, correct
 * bytes. That makes the retry a real second chance rather than a formality.
 */
private fun replaceContents(resolver: ContentResolver, uri: Uri, staged: File): Boolean {
    repeat(2) { if (copyOver(resolver, uri, staged)) return true }
    return false
}

private fun copyOver(resolver: ContentResolver, uri: Uri, staged: File): Boolean = try {
    val target = resolver.openFileDescriptor(uri, "rwt") ?: return false
    target.use { pfd ->
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        FileInputStream(staged).use { input ->
            val length = staged.length()
            var pos = 0L
            while (pos < length) {
                val moved = channel.transferFrom(input.channel, pos, length - pos)
                if (moved <= 0L) return false
                pos += moved
            }
            channel.truncate(length)
        }
        pfd.fileDescriptor.sync()
    }
    true
} catch (e: Exception) {
    false
}

/**
 * Makes the media index re-read the file.
 *
 * Without this the edit is invisible. MediaStore's metadata columns are a cache
 * of what the scanner last read out of the file, and writing through a
 * descriptor does not invalidate it — every screen in the app would keep
 * showing the old tags, which is indistinguishable from the write having failed.
 *
 * Suspends until the scan finishes, so whatever the caller does with
 * [TagWriteOutcome.Saved] sees the new values. The scan matches the file by
 * path, so the row keeps its `_id` and the track keeps its identity everywhere
 * the app has recorded it.
 */
private suspend fun rescan(context: Context, uri: Uri) {
    val path = filePathOf(context, uri) ?: return
    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** DATA is deprecated and still the only way to name a file to the scanner. */
@Suppress("DEPRECATION")
private fun filePathOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

private fun readAt(channel: FileChannel, position: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val buffer = ByteBuffer.allocate(length)
    var pos = position
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, pos)
        if (read < 0) break
        pos += read
    }
    val filled = buffer.position()
    return if (filled == length) buffer.array() else buffer.array().copyOf(filled)
}

private fun writeAt(channel: FileChannel, position: Long, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    var pos = position
    while (buffer.hasRemaining()) {
        pos += channel.write(buffer, pos)
    }
}

/** Enough of an untagged file for the writer to spot a FLAC, WAV or M4A. */
private const val CONTAINER_PROBE_BYTES = 64

private const val SCAN_TIMEOUT_MS = 10_000L
