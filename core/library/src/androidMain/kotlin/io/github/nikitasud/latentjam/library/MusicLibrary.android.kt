/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.CoverPicture
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TextRepair
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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

    /**
     * Whether MediaStore has an album-artist column. It is public from API 30, but the scanner filled
     * `album_artist` long before. Below 30 it is used only when the provider actually has it;
     * otherwise tag enrichment reads the file (spec §3.5). A query naming a missing column throws.
     *
     * Only a definitive answer is remembered: a scan before the storage grant throws a
     * SecurityException or gets no cursor, and that must not read as "no column" for the life of
     * the process. Such a scan goes without the column and probes again at the next one.
     */
    @Volatile
    private var albumArtistColumnKnown: Boolean? = null

    private fun albumArtistColumn(): Boolean {
        albumArtistColumnKnown?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            albumArtistColumnKnown = true
            return true
        }
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(ALBUM_ARTIST), "0", null, null,
            )
            if (cursor == null) {
                false
            } else {
                cursor.close()
                albumArtistColumnKnown = true
                true
            }
        } catch (_: IllegalArgumentException) {
            albumArtistColumnKnown = false
            false
        } catch (_: android.database.sqlite.SQLiteException) {
            albumArtistColumnKnown = false
            false
        } catch (_: Exception) {
            false
        }
    }
    private val hiddenFile = java.io.File(context.filesDir, HIDDEN_FILE_NAME)
    private val excludedSourcesFile = java.io.File(context.filesDir, EXCLUDED_SOURCES_FILE_NAME)
    private val coverOverrides = TrackCoverOverrides(
        directory = java.io.File(context.filesDir, TRACK_COVERS_DIRECTORY),
        readCover = ::readEmbeddedCover,
    )

    override suspend fun scan(): LibraryScan = withContext(Dispatchers.IO) {
        val hidden = visibilityMutex.withLock { readHiddenIds() }
        val excludedSources = visibilityMutex.withLock { readExcludedSourceIds() }
        val snapshot = queryTracks()
        snapshot.copy(
            tracks = snapshot.tracks.filterNot { track ->
                track.id.value in hidden || sourceId(track.folderPath) in excludedSources
            },
        )
    }

    override suspend fun tracks(): List<TrackDescriptor> = scan().tracks

    override suspend fun allKnownTracks(): List<TrackDescriptor> =
        withContext(Dispatchers.IO) { queryTracks().tracks }

    private fun queryTracks(): LibraryScan = mediaStoreLibraryScan {
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
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.GENERATION_MODIFIED)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (genreSupported) add(MediaStore.Audio.Media.GENRE)
            if (albumArtistColumn()) add(ALBUM_ARTIST)
        }.toTypedArray()
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        ) ?: return@mediaStoreLibraryScan null
        cursor.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val generationColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENERATION_MODIFIED)
            } else {
                -1
            }
            val folderColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }
            val genreColumn = if (genreSupported) cursor.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val albumArtistIndex = cursor.getColumnIndex(ALBUM_ARTIST)
            // MediaProvider makes a song's own cover from Android 10 on. Older releases answer it with
            // the album's cached cover, or with nothing before one is cached, so there a song keeps
            // its album's, exactly as before.
            val ownCovers = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val position = if (trackColumn >= 0 && !cursor.isNull(trackColumn)) {
                    TrackNumbers.fromMediaStore(cursor.getInt(trackColumn))
                } else {
                    TrackPosition()
                }
                tracks += TrackDescriptor(
                    id = TrackId(id.toString()),
                    title = cursor.getString(titleColumn).knownTagOrNull(),
                    artist = cursor.getString(artistColumn).knownTagOrNull(),
                    album = cursor.getString(albumColumn).knownTagOrNull(),
                    genre = if (genreColumn >= 0) cursor.getString(genreColumn).knownTagOrNull() else null,
                    albumArtist = if (albumArtistIndex >= 0) cursor.getString(albumArtistIndex).knownTagOrNull() else null,
                    durationMs = cursor.getLong(durationColumn).takeIf { it > 0 },
                    audioUri = ContentUris
                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        .toString(),
                    // The song's own cover; its album's, which grouping compares, is the fallback.
                    artworkUri = albumId.takeIf { it > 0 }?.let { album ->
                        if (ownCovers) MediaStoreArtwork.trackCover(id, album) else MediaStoreArtwork.albumCover(album)
                    },
                    albumArtworkUri = albumId.takeIf { it > 0 && ownCovers }?.let(MediaStoreArtwork::albumCover),
                    // MediaStore stores DATE_ADDED in epoch seconds.
                    addedAtMs = cursor.getLong(addedColumn).takeIf { it > 0 }?.times(1000),
                    folderPath = mediaStoreFolderPath(
                        cursor.getString(folderColumn),
                        relativePath = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
                    ),
                    year = cursor.getInt(yearColumn).takeIf { it > 0 },
                    sizeBytes = cursor.getLong(sizeColumn).takeIf { it > 0 },
                    fileName = if (nameColumn >= 0) cursor.getString(nameColumn).knownOrNull() else null,
                    trackNumber = position.trackNumber,
                    discNumber = position.discNumber,
                    sourceRevision = androidMediaSourceRevision(
                        sizeBytes = cursor.getLong(sizeColumn).takeIf { it >= 0 },
                        modifiedAtSeconds = cursor.getLong(modifiedColumn).takeIf { it > 0 },
                        generationModified = if (generationColumn >= 0) {
                            cursor.getLong(generationColumn).takeIf { it > 0 }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        // Only a complete scan reaches here: an incomplete one returned or threw above, and must
        // not read as "these songs are gone" to the overrides.
        coverOverrides.apply(withAlbumArtVersions(tracks))
    }

    override val recordsCovers: Boolean get() = true

    override suspend fun coverSaved(trackIds: Collection<TrackId>, cover: CoverEdit): Boolean =
        withContext(Dispatchers.IO) {
            // A cover that cannot be kept costs only the song's own cover: it shows its album's.
            // A failed write left the index as it was, so nothing a scan shows changed.
            runCatching {
                // MediaProvider (Android 11+) regenerates the album's art from the changed file, so
                // the album's other songs are pinned to their own pictures first: read once each,
                // here only, and never when the save covered the whole album.
                val siblings = if (cover == CoverEdit.Keep) emptyList() else albumSiblings(trackIds, albumRows())
                coverOverrides.record(trackIds, cover, siblings, ::readOwnCover)
            }.getOrDefault(false)
        }

    /** (id, album id) of every song, for [albumSiblings]; none when MediaStore cannot be asked. */
    private fun albumRows(): List<Pair<TrackId, Long>> = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(TrackId(cursor.getLong(idColumn).toString()) to cursor.getLong(albumColumn))
            }
        }
    }.getOrNull().orEmpty()

    /** The picture song [id]'s own file holds, for pinning it; null when none or unreadable. */
    private fun readOwnCover(id: TrackId): CoverPicture? = try {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.value.toLong())
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            // Not closed: closing a stream over this descriptor closes the descriptor itself.
            TagCodecs.readCover(ChannelSource(FileInputStream(it.fileDescriptor).channel))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A sibling that cannot be read keeps its album's art, as before this save.
        null
    }

    override suspend fun coverKept(saved: Collection<TrackDescriptor>): Unit =
        withContext(Dispatchers.IO) {
            // Not trusting an entry costs one read of its file at the next scan, nothing more.
            runCatching { coverOverrides.kept(saved) }
        }

    /** The cover [track]'s file holds now, for re-checking its override after the file changed. */
    private fun readEmbeddedCover(track: TrackDescriptor): FileCover = try {
        val uri = track.audioUri?.takeIf(String::isNotBlank)?.let(Uri::parse)
        val descriptor = uri?.let { context.contentResolver.openFileDescriptor(it, "r") }
        descriptor?.use {
            // Not closed: closing a stream over this descriptor closes the descriptor itself, which
            // is the ParcelFileDescriptor's job.
            val snapshot = TagCodecs.read(ChannelSource(FileInputStream(it.fileDescriptor).channel))
            if (snapshot == null) FileCover.Unrecognised else FileCover.Read(snapshot.cover?.crc32)
        } ?: FileCover.Unreadable
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // A file another app wrote (a huge picture, a pathologically nested tag) must not fail the
        // scan: its override is kept and checked again next time.
        FileCover.Unreadable
    }

    override suspend fun hide(trackId: TrackId): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.add(trackId.value)) writeHiddenIds(hidden)
        }
    }

    override suspend fun hide(trackIds: Collection<TrackId>): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.addAll(trackIds.map(TrackId::value))) writeHiddenIds(hidden)
        }
    }

    override suspend fun unhide(trackId: TrackId): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.remove(trackId.value)) writeHiddenIds(hidden)
        }
    }

    override suspend fun unhide(trackIds: Collection<TrackId>): Unit = withContext(Dispatchers.IO) {
        visibilityMutex.withLock {
            val hidden = readHiddenIds().toMutableSet()
            if (hidden.removeAll(trackIds.map(TrackId::value).toSet())) writeHiddenIds(hidden)
        }
    }

    override suspend fun hiddenTracks(): List<TrackDescriptor> = withContext(Dispatchers.IO) {
        val hidden = visibilityMutex.withLock { readHiddenIds() }
        if (hidden.isEmpty()) emptyList()
        else queryTracks().tracks.filter { it.id.value in hidden }
    }

    override suspend fun filePaths(ids: List<TrackId>): Map<TrackId, String> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyMap()
            val wanted = ids.mapTo(HashSet(), TrackId::value)
            val paths = LinkedHashMap<TrackId, String>(ids.size)
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val dataColumn = cursor.getColumnIndex(
                        @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
                    )
                    if (dataColumn < 0) return@use
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn).toString()
                        if (id !in wanted) continue
                        cursor.getString(dataColumn)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { paths[TrackId(id)] = it }
                    }
                }
            }
            paths
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
        queryTracks().tracks
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
        hiddenFile.atomicReplaceText(ids.sorted().joinToString("\n"))
    }

    private fun readExcludedSourceIds(): Set<String> =
        if (excludedSourcesFile.exists()) {
            excludedSourcesFile.readLines().filter(String::isNotBlank).toSet()
        } else {
            emptySet()
        }

    private fun writeExcludedSourceIds(ids: Set<String>) {
        excludedSourcesFile.atomicReplaceText(ids.sorted().joinToString("\n"))
    }

    private fun sourceId(folderPath: String?): String = SOURCE_PREFIX + folderPath.orEmpty()

    /** MediaStore reports missing tags as the literal string "<unknown>". */
    private fun String?.knownOrNull(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }

    /**
     * As [knownOrNull], plus mojibake repair.
     *
     * MediaStore's title/artist/album/genre columns are UTF-8 bytes read back
     * as if they were Latin-1 whenever the underlying tag reader on the
     * device made that mistake, so "üß" comes back as "Ã¼ÃŸ". File names are
     * deliberately left to [knownOrNull] alone: they are not tag text, and
     * "repairing" one could point at the wrong file.
     */
    private fun String?.knownTagOrNull(): String? = knownOrNull()?.let(TextRepair::repair)

    private companion object {
        /** MediaStore.Audio.AudioColumns.ALBUM_ARTIST, whose constant is hidden below API 30. */
        const val ALBUM_ARTIST = "album_artist"
        const val HIDDEN_FILE_NAME = "hidden_tracks.txt"
        const val EXCLUDED_SOURCES_FILE_NAME = "excluded_music_sources.txt"
        const val SOURCE_PREFIX = "folder:"
        const val TRACK_COVERS_DIRECTORY = "track-covers"
    }
}

/** Positional reads over a `ParcelFileDescriptor`'s channel, for the tag codecs. */
private class ChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val length: Long = channel.size()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset + count > length) return null
        val buffer = ByteBuffer.allocate(count)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) return null
            position += read
        }
        return buffer.array()
    }
}

/**
 * Converts Android's two ambiguous provider outcomes into an explicitly incomplete snapshot.
 * A successfully returned empty list remains complete and can prove that the library is empty.
 */
internal fun mediaStoreLibraryScan(
    query: () -> List<TrackDescriptor>?,
): LibraryScan = try {
    val tracks = query()
    if (tracks == null) LibraryScan(tracks = emptyList(), complete = false)
    else LibraryScan(tracks = tracks, complete = true)
} catch (_: SecurityException) {
    LibraryScan(tracks = emptyList(), complete = false)
}

/** Pure MediaStore path projection, shared with host regressions for removable-volume paths. */
internal fun mediaStoreFolderPath(rawPath: String?, relativePath: Boolean): String? {
    val normalized = rawPath?.replace('\\', '/')?.trimEnd('/').orEmpty()
    if (normalized.isBlank()) return null
    if (relativePath) return normalized

    val parent = normalized.substringBeforeLast('/', "")
    // A file at the root of a volume is in no folder at all: without this the generic cases below
    // turned the mount path into one, and such a file was listed under a folder named "sdcard", or
    // "0" for /storage/emulated/0.
    if (parent.isBlank() || isVolumeRoot(parent)) return null
    val relative = when {
        parent.startsWith("/storage/emulated/0/") -> parent.removePrefix("/storage/emulated/0/")
        parent.startsWith("/sdcard/") -> parent.removePrefix("/sdcard/")
        parent.startsWith("/storage/") -> {
            // `/storage/<volume-id>/…`: remove both the mount root and opaque volume segment.
            parent.removePrefix("/storage/").substringAfter('/', missingDelimiterValue = "")
        }
        else -> parent
    }
    return relative.trim('/').takeIf(String::isNotBlank)
}

/**
 * True for a path that is a storage volume's own mount root rather than a folder inside it:
 * `/storage/<volume>` (removable media, and `/storage/emulated/0`), plus the legacy `/sdcard` and
 * `/mnt/sdcard` aliases that older `DATA` values hold.
 */
private fun isVolumeRoot(parent: String): Boolean = when {
    parent == "/sdcard" || parent == "/mnt/sdcard" || parent == "/storage/emulated/0" -> true
    parent.startsWith("/storage/") -> !parent.removePrefix("/storage/").contains('/')
    else -> false
}

/** Versioned and delimiter-safe because every component is a nullable decimal integer. */
internal fun androidMediaSourceRevision(
    sizeBytes: Long?,
    modifiedAtSeconds: Long?,
    generationModified: Long?,
): String = "android-mediastore-v1:" +
    listOf(sizeBytes, modifiedAtSeconds, generationModified).joinToString(":") { it?.toString() ?: "-" }

/** Whole-file rewrite; playlists are few and short. */
internal class FilePlaylistStore(private val context: Context) : PlaylistStore {

    private val file get() = java.io.File(context.filesDir, FILE_NAME)

    override suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.IO) {
        file.atomicReplaceText(lines.joinToString("\n"))
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
