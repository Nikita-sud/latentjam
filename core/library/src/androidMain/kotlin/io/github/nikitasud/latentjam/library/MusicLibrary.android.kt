/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * [MusicLibrary] backed by Android's [MediaStore].
 *
 * Uses the system media index (fast, no file walking, respects `.nomedia`).
 * Each track's [TrackDescriptor.audioUri] is a `content://` URI the future
 * embedding backend can open for decoding. Genre is left `null` for now:
 * MediaStore models genres as a separate join table and the per-track lookup
 * is costly — it lands together with the real indexing pipeline.
 *
 * Requires `READ_MEDIA_AUDIO` (API 33+) or `READ_EXTERNAL_STORAGE` (≤32);
 * without the grant the query yields no rows and this returns an empty list.
 */
internal class MediaStoreMusicLibrary(
    private val context: Context,
) : MusicLibrary {

    private val visibilityMutex = Mutex()
    private val hiddenFile = java.io.File(context.filesDir, HIDDEN_FILE_NAME)
    private val excludedSourcesFile = java.io.File(context.filesDir, EXCLUDED_SOURCES_FILE_NAME)

    override suspend fun tracks(): List<TrackDescriptor> = withContext(Dispatchers.IO) {
        val hidden = visibilityMutex.withLock { readHiddenIds() }
        val excludedSources = visibilityMutex.withLock { readExcludedSourceIds() }
        queryTracks().filterNot { track ->
            track.id.value in hidden || sourceId(track.folderPath) in excludedSources
        }
    }

    override suspend fun allKnownTracks(): List<TrackDescriptor> = withContext(Dispatchers.IO) {
        queryTracks()
    }

    private fun queryTracks(): List<TrackDescriptor> {
        val tracks = mutableListOf<TrackDescriptor>()
        // GENRE joined into the audio table only since API 30.
        val genreSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.YEAR)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (genreSupported) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )
        } catch (_: SecurityException) {
            // The listener may deliberately continue without MediaStore access, inspect the app,
            // and recover later from Library settings. That is an empty device source, not a
            // broken/infinite-loading library.
            null
        }
        cursor?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val folderColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }
            val genreColumn = if (genreSupported) cursor.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val albumId = cursor.getLong(albumIdColumn)
                tracks += TrackDescriptor(
                    id = TrackId(id.toString()),
                    title = cursor.getString(titleColumn).knownOrNull(),
                    artist = cursor.getString(artistColumn).knownOrNull(),
                    album = cursor.getString(albumColumn).knownOrNull(),
                    genre = if (genreColumn >= 0) cursor.getString(genreColumn).knownOrNull() else null,
                    durationMs = cursor.getLong(durationColumn).takeIf { it > 0 },
                    audioUri = ContentUris
                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        .toString(),
                    artworkUri = albumId.takeIf { it > 0 }?.let { album ->
                        ContentUris.withAppendedId(ALBUM_ART_URI, album).toString()
                    },
                    // MediaStore stores DATE_ADDED in epoch seconds.
                    addedAtMs = cursor.getLong(addedColumn).takeIf { it > 0 }?.times(1000),
                    folderPath = cursor.getString(folderColumn)?.toFolderPath(),
                    year = cursor.getInt(yearColumn).takeIf { it > 0 },
                )
            }
        }
        return tracks
    }

    override suspend fun hide(trackId: TrackId): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.add(trackId.value)) writeHiddenIds(hidden)
        }
    }

    override suspend fun unhide(trackId: TrackId): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.remove(trackId.value)) writeHiddenIds(hidden)
        }
    }

    override suspend fun hiddenTracks(): List<TrackDescriptor> = withContext(Dispatchers.IO) {
        val hidden = visibilityMutex.withLock { readHiddenIds() }
        if (hidden.isEmpty()) emptyList()
        else queryTracks().filter { it.id.value in hidden }
    }

    override suspend fun hiddenTrackIds(): Set<TrackId> = withContext(Dispatchers.IO) {
        visibilityMutex.withLock { readHiddenIds().mapTo(linkedSetOf(), ::TrackId) }
    }

    override suspend fun hasHiddenTracks(): Boolean = withContext(Dispatchers.IO) {
        visibilityMutex.withLock { readHiddenIds().isNotEmpty() }
    }

    override suspend fun unhideAll(): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock { writeHiddenIds(emptySet()) }
    }

    override suspend fun replaceHidden(trackIds: Set<TrackId>): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            writeHiddenIds(trackIds.mapTo(linkedSetOf(), TrackId::value))
        }
    }

    override suspend fun sources(): List<LibrarySource> = withContext(Dispatchers.IO) {
        val excluded = visibilityMutex.withLock { readExcludedSourceIds() }
        queryTracks()
            .groupBy { track -> sourceId(track.folderPath) }
            .map { (id, sourceTracks) ->
                LibrarySource(
                    id = id,
                    name = sourceTracks.firstOrNull()?.folderPath,
                    trackCount = sourceTracks.size,
                    enabled = id !in excluded,
                )
            }
            .sortedWith(
                compareByDescending<LibrarySource> { it.enabled }
                    .thenBy { it.name.orEmpty().lowercase() },
            )
    }

    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            visibilityMutex.withLock {
                val excluded = readExcludedSourceIds().toMutableSet()
                val changed = if (enabled) excluded.remove(sourceId) else excluded.add(sourceId)
                if (changed) writeExcludedSourceIds(excluded)
            }
        }

    private fun readHiddenIds(): Set<String> =
        if (hiddenFile.exists()) hiddenFile.readLines().filter(String::isNotBlank).toSet()
        else emptySet()

    private fun writeHiddenIds(ids: Set<String>) {
        hiddenFile.writeText(ids.sorted().joinToString("\n"))
    }

    private fun readExcludedSourceIds(): Set<String> =
        if (excludedSourcesFile.exists()) {
            excludedSourcesFile.readLines().filter(String::isNotBlank).toSet()
        } else {
            emptySet()
        }

    private fun writeExcludedSourceIds(ids: Set<String>) {
        excludedSourcesFile.writeText(ids.sorted().joinToString("\n"))
    }

    private fun sourceId(folderPath: String?): String = SOURCE_PREFIX + folderPath.orEmpty()

    /** MediaStore reports missing tags as the literal string "<unknown>". */
    private fun String?.knownOrNull(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }

    private fun String.toFolderPath(): String? {
        val normalized = replace('\\', '/').trimEnd('/')
        val folder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            normalized
        } else {
            normalized.substringBeforeLast('/', "")
                .substringAfterLast("/storage/emulated/0/", missingDelimiterValue = normalized)
        }
        return folder.takeIf(String::isNotBlank)
    }

    private companion object {
        /** Base of the classic per-album artwork content URIs. */
        val ALBUM_ART_URI: android.net.Uri = android.net.Uri.parse("content://media/external/audio/albumart")
        const val HIDDEN_FILE_NAME = "hidden_tracks.txt"
        const val EXCLUDED_SOURCES_FILE_NAME = "excluded_music_sources.txt"
        const val SOURCE_PREFIX = "folder:"
    }
}

/** Whole-file rewrite; playlists are few and short. */
internal class FilePlaylistStore(private val context: Context) : PlaylistStore {

    private val file get() = java.io.File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.writeText(lines.joinToString("\n"))
    }

    private companion object {
        const val FILE_NAME = "playlists.txt"
    }
}

public actual fun musicLibraryModule(): Module = module {
    single<MusicLibrary> { MediaStoreMusicLibrary(context = get()) }
    single<PlaylistStore> { FilePlaylistStore(context = get()) }
    single<Playlists> { DefaultPlaylists(store = get()) }
}

public actual fun nowMillis(): Long = System.currentTimeMillis()
