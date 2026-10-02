/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nikitasud.latentjam.library.tags.EmbeddedLyrics
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.library.tags.SidecarLyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        if (sidecar.length() > SidecarLyrics.MAX_BYTES) continue
        sidecar.inputStream().use(::decodeCapped)?.let { return it }
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
                context.contentResolver.openInputStream(document)?.use(::decodeCapped)?.let { return it }
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

@Composable
internal actual fun rememberSidecarFingerprints(): suspend (List<TrackDescriptor>) -> Map<TrackId, String> {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { tracks -> withContext(Dispatchers.IO) { sidecarFingerprints(context, tracks) } }
    }
}

/**
 * What one pass costs, by where [readSidecarLyrics] looks:
 *
 * - Android 10+ with no folder of the device's storage granted: nothing. No sidecar is
 *   reachable, every song is known to have none, and no query runs.
 * - Android 9 and older: one MediaStore query for every song's path, then two `stat`s per song
 *   ([siblingFingerprint]) — microseconds each, no file opened.
 * - Android 10+ with folders granted: the same MediaStore query, then one children query per
 *   folder that holds songs inside a granted tree — once per folder even when nested grants both
 *   cover it, and never per song or per candidate name, which for a few thousand songs would be
 *   thousands of binder calls.
 *
 * A song is left out of the answer — "unknown", which keeps its cached entry — when its folder
 * cannot be listed; the whole call throws when the paths cannot be queried. Neither may read as
 * "no sidecar", which would re-read every song that has one.
 */
private suspend fun sidecarFingerprints(context: Context, tracks: List<TrackDescriptor>): Map<TrackId, String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val paths = mediaStorePaths(context)
        val fingerprints = HashMap<TrackId, String>(tracks.size)
        for ((index, track) in tracks.withIndex()) {
            if (index % CANCELLATION_STRIDE == 0) currentCoroutineContext().ensureActive()
            // A song MediaStore has no path for has no sidecar the reader could find either.
            fingerprints[track.id] = track.audioUri?.let(paths::get)?.let(::siblingFingerprint).orEmpty()
        }
        return fingerprints
    }
    val store = LyricsFolderStore.get(context)
    val grants = store.trees.value.mapNotNull { tree ->
        val treeUri = Uri.parse(tree)
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return@mapNotNull null
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()?.let { Triple(tree, treeUri, it) }
    }
    if (grants.isEmpty()) return tracks.associate { it.id to "" }
    val paths = mediaStorePaths(context)
    // Folder → the tree it is listed through and the songs in it with their candidate ids. A song
    // lies in one folder, and nested grants name the same document ids, so the first covering
    // tree is enough.
    val folders = LinkedHashMap<String, Pair<Triple<String, Uri, String>, MutableList<Pair<TrackId, List<String>>>>>()
    val fingerprints = HashMap<TrackId, String>(tracks.size)
    for (track in tracks) {
        fingerprints[track.id] = ""
        val audioDocumentId = track.audioUri?.let(paths::get)?.let(::externalStorageDocumentId) ?: continue
        for (grant in grants) {
            val candidates = sidecarDocumentIds(grant.third, audioDocumentId)
            if (candidates.isEmpty()) continue
            folders.getOrPut(parentDocumentId(candidates.first())) { grant to mutableListOf() }.second +=
                track.id to candidates
            break
        }
    }
    for ((folder, entry) in folders) {
        currentCoroutineContext().ensureActive()
        val (grant, songs) = entry
        val listing = listSidecars(context, store, grant, folder)
        for ((id, candidates) in songs) {
            if (listing == null) fingerprints.remove(id) else fingerprints[id] = listingFingerprint(candidates, listing)
        }
    }
    return fingerprints
}

/** How many songs a per-song loop handles between cancellation checks. */
private const val CANCELLATION_STRIDE = 256

/**
 * Every song's file path by its content URI, from one query. DATA is deprecated, and it is what
 * [filePathOf] reads per song for the same purpose. Throws when the query cannot run: an empty
 * answer would say that no song has a path, and so no sidecar.
 */
@Suppress("DEPRECATION")
private fun mediaStorePaths(context: Context): Map<String, String> {
    val cursor = checkNotNull(
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        ),
    ) { "MediaStore did not answer" }
    val paths = HashMap<String, String>()
    cursor.use {
        while (it.moveToNext()) {
            val path = it.getString(1) ?: continue
            paths[ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0)).toString()] = path
        }
    }
    return paths
}

/**
 * The `.lrc` files in [folderDocumentId], from one children query through [grant]'s tree; null
 * when the folder cannot be listed. A grant revoked outside the app is forgotten here as the
 * reader forgets it ([readFromGrantedFolders]); that changes the lyrics sources, and the index
 * re-reads what the folder used to supply.
 */
private fun listSidecars(
    context: Context,
    store: LyricsFolderStore,
    grant: Triple<String, Uri, String>,
    folderDocumentId: String,
): Map<String, List<String>>? = try {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(grant.second, folderDocumentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        val rows = ArrayList<Triple<String, Long, Long>>()
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(0) ?: continue
            rows += Triple(documentId, cursor.getLong(1), cursor.getLong(2))
        }
        sidecarListing(rows)
    }
} catch (_: SecurityException) {
    val stillHeld = context.contentResolver.persistedUriPermissions.any {
        it.uri == grant.second && it.isReadPermission
    }
    if (!stillHeld) store.forget(grant.first)
    null
} catch (_: Exception) {
    null
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
