/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.library.tags.EmbeddedLyrics
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.library.tags.SidecarLyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberLyricsReader(reportReadFailures: Boolean): suspend (TrackDescriptor) -> Lyrics? {
    val context = LocalContext.current.applicationContext
    return remember(context, reportReadFailures) {
        { track ->
            withContext(Dispatchers.IO) {
                val embedded = try {
                    readEmbeddedLyrics(context, track)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (reportReadFailures) throw failure
                    null
                }
                SidecarLyrics.choose(embedded, readSidecarLyrics(context, track))
            }
        }
    }
}

private fun readEmbeddedLyrics(context: Context, track: TrackDescriptor): Lyrics? {
    val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
    val pfd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "Cannot open lyrics source" }
    return pfd.use {
        // Container-agnostic: ID3 USLT for mp3, Vorbis comments for FLAC and Ogg/Opus, the ©lyr
        // atom for M4A — reading only ID3 silently answered "no lyrics" for every correctly
        // tagged Opus, and random access lets the MP4 path read just the `moov` atom.
        // Not closed here: closing any stream over this descriptor closes the descriptor
        // itself, which is the ParcelFileDescriptor's job.
        val channel = FileInputStream(it.fileDescriptor).channel
        EmbeddedLyrics.read(FileChannelSource(channel))
    }
}

/**
 * The `.lrc` beside the song, or null — never a failure: a missing, unreadable or undecodable
 * sidecar leaves the embedded lyrics in charge. Android 9 and older read it directly with the
 * storage permission playback already holds; Android 10+ hides non-media files from the media
 * permission, so the file is reached only through a folder the listener granted in Settings.
 */
private fun readSidecarLyrics(context: Context, track: TrackDescriptor): Lyrics? = try {
    val uri = track.audioUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    when {
        uri == null -> null
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> filePathOf(context, uri)?.let(::readSiblingFile)
        else -> {
            val store = LyricsFolderStore.get(context)
            // No grants, no MediaStore query: the common case costs nothing per track.
            if (store.trees.value.isEmpty()) {
                null
            } else {
                filePathOf(context, uri)?.let { readFromGrantedFolders(context, store, it) }
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private fun readSiblingFile(audioPath: String): Lyrics? {
    val audio = File(audioPath)
    for (name in SidecarLyrics.candidateNames(audio.name)) {
        val sidecar = File(audio.parentFile, name)
        if (!sidecar.isFile) continue
        if (sidecar.length() > SidecarLyrics.MAX_BYTES) return null
        return sidecar.inputStream().use(::decodeCapped)
    }
    return null
}

private fun readFromGrantedFolders(context: Context, store: LyricsFolderStore, audioPath: String): Lyrics? {
    val audioDocumentId = externalStorageDocumentId(audioPath) ?: return null
    for (tree in store.trees.value) {
        val treeUri = Uri.parse(tree)
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) continue
        // One malformed stored entry must not end the lookup for every other folder.
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
        for (documentId in sidecarDocumentIds(treeDocumentId, audioDocumentId)) {
            val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            try {
                context.contentResolver.openInputStream(document)?.use { return decodeCapped(it) }
            } catch (_: SecurityException) {
                // A grant revoked outside the app can never answer again, so it leaves the list
                // instead of costing a binder call per song. A refusal while the grant is still
                // held (the provider judging this file outside the tree) only skips this lookup:
                // forgetting it would leave a live grant the listener can no longer see or remove.
                val stillHeld = context.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission
                }
                if (!stillHeld) store.forget(tree)
                break
            } catch (_: Exception) {
                // Not there. The provider says so with FileNotFoundException, or — when its tree
                // check cannot resolve a missing child or a deleted folder — IllegalArgumentException.
            }
        }
    }
    return null
}

/** Reads one byte past the cap at most, so an oversized file is declined without reading it all. */
private fun decodeCapped(input: InputStream): Lyrics? =
    SidecarLyrics.decode(input.readAtMost(SidecarLyrics.MAX_BYTES + 1))

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (buffer.size() < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
        if (read < 0) break
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
