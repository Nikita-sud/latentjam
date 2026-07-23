/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.cleanGenre
import io.github.nikitasud.latentjam.library.tags.parseYear
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.AVFoundation.AVMetadataCommonKeyAlbumName
import platform.AVFoundation.AVMetadataCommonKeyArtist
import platform.AVFoundation.AVMetadataCommonKeyArtwork
import platform.AVFoundation.AVMetadataCommonKeyCreationDate
import platform.AVFoundation.AVMetadataCommonKeyTitle
import platform.AVFoundation.AVMetadataIdentifierID3MetadataContentType
import platform.AVFoundation.AVMetadataIdentifierID3MetadataRecordingTime
import platform.AVFoundation.AVMetadataIdentifierID3MetadataYear
import platform.AVFoundation.AVMetadataIdentifieriTunesMetadataPredefinedGenre
import platform.AVFoundation.AVMetadataIdentifieriTunesMetadataReleaseDate
import platform.AVFoundation.AVMetadataIdentifieriTunesMetadataUserGenre
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.commonKey
import platform.AVFoundation.commonMetadata
import platform.AVFoundation.dataValue
import platform.AVFoundation.duration
import platform.AVFoundation.metadata
import platform.AVFoundation.metadataItemsFromArray
import platform.AVFoundation.stringValue
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.MediaPlayer.MPMediaItem
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusDenied
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusRestricted
import platform.MediaPlayer.MPMediaQuery
import kotlin.coroutines.resume

/**
 * [MusicLibrary] over both the device Music library and the app's Documents folder.
 *
 * `MPMediaQuery` is the first-open path an iPhone user expects: downloaded and
 * synced Music.app tracks are already on the device. FairPlay items do not hand
 * raw samples to the audio encoder, so they use the on-device metadata/text
 * path while remaining playable through `MPMusicPlayerController`. Owned files
 * with an asset URL, and files copied into LatentJam through Files.app, retain
 * full waveform embeddings.
 *
 * ### Identity
 * [TrackId] is the path RELATIVE to Documents, never the absolute one: iOS
 * rewrites the container UUID in `/var/mobile/Containers/Data/Application/…`
 * across reinstalls and some OS updates, so absolute paths would orphan every
 * history row and index entry the first time that happened.
 */
internal class IosMusicLibrary : MusicLibrary {

    /** Guards [cache] — [tracks] may be called from several screens at once. */
    private val scanMutex = Mutex()

    /** Prevents concurrent first screens from presenting the privacy prompt more than once. */
    private val authorizationMutex = Mutex()

    /** Serializes the small app-only visibility file. */
    private val visibilityMutex = Mutex()

    /** Relative path → last scan result, reused while size and mtime agree. */
    private val cache = mutableMapOf<String, CachedTrack>()

    private data class CachedTrack(
        val modifiedAtMs: Long,
        val sizeBytes: Long,
        val descriptor: TrackDescriptor,
    )

    private data class SourceSnapshot(
        val documents: List<TrackDescriptor>,
        val device: List<TrackDescriptor>,
    )

    override suspend fun tracks(): List<TrackDescriptor> {
        val hidden = hiddenTrackIdValues()
        val excludedSources = excludedSourceIds()
        val snapshot = sourceSnapshot()
        val documents = snapshot.documents.filterNot { sourceId(it) in excludedSources }
        val device = if (DEVICE_MUSIC_SOURCE_ID in excludedSources) emptyList() else snapshot.device
        return mergeSources(documents, device)
            .filterNot { it.id.value in hidden }
            .sortedBy { it.title?.lowercase() ?: "" }
    }

    override suspend fun allKnownTracks(): List<TrackDescriptor> {
        val snapshot = sourceSnapshot()
        return mergeSources(snapshot.documents, snapshot.device)
            .sortedBy { it.title?.lowercase() ?: "" }
    }

    private suspend fun sourceSnapshot(): SourceSnapshot {
        val canReadDeviceLibrary = mediaLibraryAuthorized()
        val device = if (canReadDeviceLibrary) {
            // MediaPlayer's controller is explicitly main-thread-only. Keeping its query here as
            // well avoids relying on undocumented cross-thread behavior of the returned items.
            withContext(Dispatchers.Main) { scanDeviceLibrary() }
        } else {
            emptyList()
        }
        val documents = withContext(Dispatchers.Default) {
            scanMutex.withLock { IosPaths.documents()?.let(::scan).orEmpty() }
        }
        return SourceSnapshot(documents = documents, device = device)
    }

    override suspend fun hide(trackId: TrackId): Unit = withContext(Dispatchers.Default) {
        visibilityMutex.withLock {
            val hidden = readHiddenTrackIds().toMutableSet()
            if (hidden.add(trackId.value)) writeHiddenTrackIds(hidden)
        }
    }

    override suspend fun unhide(trackId: TrackId): Unit = withContext(Dispatchers.Default) {
        visibilityMutex.withLock {
            val hidden = readHiddenTrackIds().toMutableSet()
            if (hidden.remove(trackId.value)) writeHiddenTrackIds(hidden)
        }
    }

    override suspend fun hiddenTracks(): List<TrackDescriptor> {
        val hidden = hiddenTrackIdValues()
        if (hidden.isEmpty()) return emptyList()
        val snapshot = sourceSnapshot()
        return mergeSources(snapshot.documents, snapshot.device)
            .filter { it.id.value in hidden }
            .sortedBy { it.title?.lowercase() ?: "" }
    }

    override suspend fun hiddenTrackIds(): Set<TrackId> = withContext(Dispatchers.Default) {
        visibilityMutex.withLock { readHiddenTrackIds().mapTo(linkedSetOf(), ::TrackId) }
    }

    override suspend fun hasHiddenTracks(): Boolean = withContext(Dispatchers.Default) {
        visibilityMutex.withLock { readHiddenTrackIds().isNotEmpty() }
    }

    override suspend fun unhideAll(): Unit = withContext(Dispatchers.Default) {
        visibilityMutex.withLock { writeHiddenTrackIds(emptySet()) }
    }

    override suspend fun replaceHidden(trackIds: Set<TrackId>): Unit = withContext(Dispatchers.Default) {
        visibilityMutex.withLock {
            writeHiddenTrackIds(trackIds.mapTo(linkedSetOf(), TrackId::value))
        }
    }

    override suspend fun sources(): List<LibrarySource> {
        val excluded = excludedSourceIds()
        val snapshot = sourceSnapshot()
        val imported = snapshot.documents
            .groupBy(::sourceId)
            .map { (id, tracks) ->
                LibrarySource(
                    id = id,
                    name = tracks.firstOrNull()?.folderPath,
                    trackCount = tracks.size,
                    enabled = id !in excluded,
                )
            }
        val device = if (snapshot.device.isEmpty() && !mediaLibraryAuthorizedWithoutPrompt()) {
            emptyList()
        } else {
            listOf(
                LibrarySource(
                    id = DEVICE_MUSIC_SOURCE_ID,
                    name = null,
                    trackCount = snapshot.device.size,
                    enabled = DEVICE_MUSIC_SOURCE_ID !in excluded,
                ),
            )
        }
        return (device + imported).sortedWith(
            compareByDescending<LibrarySource> { it.enabled }
                .thenBy { it.name.orEmpty().lowercase() },
        )
    }

    override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): Unit =
        withContext(Dispatchers.Default) {
            visibilityMutex.withLock {
                val excluded = readExcludedSourceIds().toMutableSet()
                val changed = if (enabled) excluded.remove(sourceId) else excluded.add(sourceId)
                if (changed) writeExcludedSourceIds(excluded)
            }
        }

    private suspend fun hiddenTrackIdValues(): Set<String> = withContext(Dispatchers.Default) {
        visibilityMutex.withLock { readHiddenTrackIds() }
    }

    private suspend fun excludedSourceIds(): Set<String> = withContext(Dispatchers.Default) {
        visibilityMutex.withLock { readExcludedSourceIds() }
    }

    private fun readHiddenTrackIds(): Set<String> = hiddenTrackPath()
        ?.let(::readLinesOrEmpty)
        .orEmpty()
        .mapNotNull(::decodeHexString)
        .toSet()

    private fun writeHiddenTrackIds(ids: Set<String>) {
        hiddenTrackPath()?.let { path ->
            writeText(path, ids.sorted().joinToString("\n", transform = String::encodeHexString))
        }
    }

    private fun hiddenTrackPath(): String? =
        IosPaths.appSupport()?.let { IosPaths.child(it, HIDDEN_FILE_NAME) }

    private fun readExcludedSourceIds(): Set<String> = excludedSourcesPath()
        ?.let(::readLinesOrEmpty)
        .orEmpty()
        .mapNotNull(::decodeHexString)
        .toSet()

    private fun writeExcludedSourceIds(ids: Set<String>) {
        excludedSourcesPath()?.let { path ->
            writeText(path, ids.sorted().joinToString("\n", transform = String::encodeHexString))
        }
    }

    private fun excludedSourcesPath(): String? =
        IosPaths.appSupport()?.let { IosPaths.child(it, EXCLUDED_SOURCES_FILE_NAME) }

    private fun sourceId(track: TrackDescriptor): String =
        IMPORTED_SOURCE_PREFIX + track.folderPath.orEmpty()

    /**
     * A Files import and Music.app can refer to the same recording with unrelated platform IDs.
     * Prefer the app-owned file because it is always decodeable by SMART, and suppress only a
     * strong metadata+duration match so remixes and alternate releases stay separate.
     */
    private fun mergeSources(
        documents: List<TrackDescriptor>,
        device: List<TrackDescriptor>,
    ): List<TrackDescriptor> {
        val documentsByIdentity = documents
            .mapNotNull { track -> duplicateIdentity(track)?.let { it to track } }
            .groupBy({ it.first }, { it.second })
        val uniqueDevice = device.filterNot { candidate ->
            val identity = duplicateIdentity(candidate) ?: return@filterNot false
            documentsByIdentity[identity].orEmpty().any { document ->
                val left = document.durationMs
                val right = candidate.durationMs
                left != null && right != null && kotlin.math.abs(left - right) <= DUPLICATE_DURATION_TOLERANCE_MS
            }
        }
        return (documents + uniqueDevice).distinctBy { it.id }
    }

    private fun duplicateIdentity(track: TrackDescriptor): String? {
        val title = track.title.normalizedIdentityPart() ?: return null
        val artist = track.artist.normalizedIdentityPart() ?: return null
        // Album is deliberately not part of identity: the same recording commonly appears under
        // its original album in one source and a compilation/single in the other. Title + artist
        // + a tight duration match is strong enough while still preserving named remixes.
        return "$title\u001f$artist"
    }

    private fun String?.normalizedIdentityPart(): String? = this
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf(String::isNotBlank)

    /** Requests the system privacy grant once, on the main thread as UIKit expects. */
    private suspend fun mediaLibraryAuthorized(): Boolean = authorizationMutex.withLock {
        withContext(Dispatchers.Main) {
            when (val current = MPMediaLibrary.authorizationStatus()) {
                MPMediaLibraryAuthorizationStatusAuthorized -> true
                MPMediaLibraryAuthorizationStatusDenied,
                MPMediaLibraryAuthorizationStatusRestricted,
                -> false
                else -> suspendCancellableCoroutine { continuation ->
                    MPMediaLibrary.requestAuthorization { status ->
                        if (continuation.isActive) {
                            continuation.resume(status == MPMediaLibraryAuthorizationStatusAuthorized)
                        }
                    }
                }
            }
        }
    }

    private suspend fun mediaLibraryAuthorizedWithoutPrompt(): Boolean = withContext(Dispatchers.Main) {
        MPMediaLibrary.authorizationStatus() == MPMediaLibraryAuthorizationStatusAuthorized
    }

    /** Converts the Music.app library into the same descriptors used by app-owned files. */
    @OptIn(ExperimentalForeignApi::class)
    private fun scanDeviceLibrary(): List<TrackDescriptor> {
        val songs = MPMediaQuery.songsQuery().items.orEmpty()
            .mapNotNull { it as? MPMediaItem }
        // Counts are useful for physical-device diagnosis without exposing private titles.
        println("IOS_LIBRARY: songs=${songs.size}")
        return songs.distinctBy { it.persistentID }
            .map { item ->
                val persistentId = item.persistentID.toString()
                TrackDescriptor(
                    id = TrackId("$MEDIA_ID_PREFIX$persistentId"),
                    title = item.title.knownOrNull() ?: "Unknown title",
                    artist = item.artist.knownOrNull(),
                    album = item.albumTitle.knownOrNull(),
                    genre = item.genre.knownOrNull()?.let(::cleanGenre),
                    durationMs = item.playbackDuration
                        .takeIf { it.isFinite() && it > 0.0 }
                        ?.times(1000.0)
                        ?.toLong(),
                    // Protected downloads intentionally leave this null. The text encoder still
                    // indexes them, and the playback controller resolves the persistent ID.
                    audioUri = item.assetURL?.absoluteString,
                    artworkUri = null,
                    addedAtMs = null,
                    folderPath = "Music",
                    year = null,
                )
            }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun scan(documents: String): List<TrackDescriptor> {
        val manager = NSFileManager.defaultManager
        // enumeratorAtPath recurses and yields paths RELATIVE to the root, which is
        // exactly the identity we want — no string surgery on absolute paths.
        val walker = manager.enumeratorAtPath(documents) ?: return emptyList()
        val found = mutableListOf<TrackDescriptor>()
        val live = mutableSetOf<String>()

        while (true) {
            val entry = walker.nextObject() ?: break
            val relativePath = entry as? String ?: continue
            val extension = relativePath.substringAfterLast('.', "").lowercase()
            if (extension !in AUDIO_EXTENSIONS) continue
            // AppleDouble sidecars ("._Track.mp3") appear whenever a file is copied
            // across a non-native filesystem. They carry the extension but none of
            // the audio, so they would otherwise show up as unplayable rows.
            if (relativePath.substringAfterLast('/').startsWith(".")) continue

            val absolutePath = IosPaths.child(documents, relativePath)
            val attributes = manager.attributesOfItemAtPath(absolutePath, null)
            val sizeBytes = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            // A zero-byte file is an interrupted copy, not a track.
            if (sizeBytes <= 0L) continue
            val modifiedAtMs = (attributes?.get(NSFileModificationDate) as? NSDate)
                ?.timeIntervalSince1970?.times(1000)?.toLong() ?: 0L

            live += relativePath
            val cached = cache[relativePath]
            if (cached != null && cached.sizeBytes == sizeBytes && cached.modifiedAtMs == modifiedAtMs) {
                if (cached.descriptor.isLikelyMusic(relativePath)) found += cached.descriptor
                continue
            }

            val addedAtMs = (attributes?.get(NSFileCreationDate) as? NSDate)
                ?.timeIntervalSince1970?.times(1000)?.toLong()
            val descriptor = read(
                relativePath = relativePath,
                absolutePath = absolutePath,
                sizeBytes = sizeBytes,
                addedAtMs = addedAtMs,
            )
            cache[relativePath] = CachedTrack(modifiedAtMs, sizeBytes, descriptor)
            if (descriptor.isLikelyMusic(relativePath)) found += descriptor
        }

        // Drop entries for files the user has since deleted, so the cache cannot
        // grow without bound across a long session of importing and removing.
        cache.keys.retainAll(live)
        // Title order, case-insensitively, to match the Android library's
        // `TITLE COLLATE NOCASE ASC` so both platforms browse the same way.
        return found.sortedBy { it.title?.lowercase() ?: "" }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun read(
        relativePath: String,
        absolutePath: String,
        sizeBytes: Long,
        addedAtMs: Long?,
    ): TrackDescriptor {
        val url = fileUrl(absolutePath)
        val asset = AVURLAsset(url, null)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var created: String? = null
        var artwork: NSData? = null
        asset.commonMetadata.forEach { raw ->
            val item = raw as? AVMetadataItem ?: return@forEach
            when (item.commonKey) {
                AVMetadataCommonKeyTitle -> title = item.stringValue
                AVMetadataCommonKeyArtist -> artist = item.stringValue
                AVMetadataCommonKeyAlbumName -> album = item.stringValue
                AVMetadataCommonKeyCreationDate -> created = item.stringValue
                AVMetadataCommonKeyArtwork -> artwork = item.dataValue
            }
        }
        // AVFoundation assigns FLAC's vorb/METADATA_BLOCK_PICTURE the common artwork key, but
        // does not include it in commonMetadata. Its dataValue is the raw JPEG/PNG payload, so a
        // second pass over the complete metadata list recovers FLAC/OGG covers without a decoder.
        if (artwork == null) {
            asset.metadata.forEach { raw ->
                val item = raw as? AVMetadataItem ?: return@forEach
                if (item.commonKey == AVMetadataCommonKeyArtwork) {
                    item.dataValue?.let { artwork = it }
                }
            }
        }

        val seconds = CMTimeGetSeconds(asset.duration)
        return TrackDescriptor(
            id = TrackId(relativePath),
            // An untagged file still deserves a readable row, and this is what
            // MediaStore does on Android too, so the platforms agree.
            title = title.knownOrNull()
                ?: asset.rawString("TITLE")
                ?: relativePath.substringAfterLast('/').substringBeforeLast('.'),
            artist = artist.knownOrNull() ?: asset.rawString("ARTIST", "ALBUMARTIST"),
            album = album.knownOrNull() ?: asset.rawString("ALBUM"),
            genre = (asset.firstString(GENRE_IDENTIFIERS) ?: asset.rawString("GENRE"))
                ?.let(::cleanGenre),
            // An indefinite or unreadable CMTime comes back NaN rather than throwing.
            durationMs = seconds.takeIf { !it.isNaN() && it > 0 }?.times(1000)?.toLong(),
            audioUri = url.absoluteString,
            artworkUri = artwork?.let(::cacheArtwork),
            addedAtMs = addedAtMs,
            folderPath = relativePath.substringBeforeLast('/', "").ifBlank { "Imported" },
            year = (asset.firstString(YEAR_IDENTIFIERS) ?: asset.rawString("DATE", "YEAR") ?: created)
                ?.let(::parseYear),
        )
    }

    /**
     * Looks a value up by its RAW tag name, across whatever keyspace the
     * container uses.
     *
     * Needed because AVFoundation only maps some formats into the common
     * keyspace. ID3 arrives as `common[artist]`, but a FLAC's Vorbis comments
     * do not: `commonMetadata` is empty and the same value sits under
     * `vorb/ARTIST`. Without this, every FLAC in a library shows its filename
     * and "Unknown artist" while its tags are sitting right there.
     *
     * Matching on the part after the "/" keeps this keyspace-agnostic, so an
     * ID3 or iTunes file that also lacks a common mapping still resolves.
     */
    private fun AVURLAsset.rawString(vararg names: String): String? {
        metadata.forEach { raw ->
            val item = raw as? AVMetadataItem ?: return@forEach
            val name = item.identifier?.substringAfterLast('/') ?: return@forEach
            if (names.any { it.equals(name, ignoreCase = true) }) {
                item.stringValue.knownOrNull()?.let { return it }
            }
        }
        return null
    }

    /** First non-blank string value across [identifiers], in preference order. */
    private fun AVURLAsset.firstString(identifiers: List<String?>): String? {
        val all = metadata
        identifiers.forEach { identifier ->
            val match = AVMetadataItem
                .metadataItemsFromArray(all, filteredByIdentifier = identifier)
                .firstOrNull() as? AVMetadataItem
            match?.stringValue.knownOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Writes embedded cover art out to a file Coil can load.
     *
     * Keyed by the artwork bytes, rather than the track path. Tracks from one
     * album normally embed the same image; sharing one cache URI lets the
     * common catalogue recognize that album and avoids dozens of duplicate
     * cache files. A changed cover naturally gets a different key.
     */
    private fun cacheArtwork(data: NSData): String? {
        val caches = IosPaths.caches() ?: return null
        var hash = 0xcbf29ce484222325uL
        data.toByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3uL
        }
        val key = "${hash.toString(16)}-${data.length}"
        val target = IosPaths.child(caches, "artwork-$key.img")
        if (!NSFileManager.defaultManager.fileExistsAtPath(target)) {
            if (!data.writeToFile(target, true)) return null
        }
        return fileUrl(target).absoluteString
    }

    private fun String?.knownOrNull(): String? = this?.takeIf { it.isNotBlank() }

    /**
     * Files.app exposes every audio-shaped download, including notification clips and effects.
     * Keep explicitly tagged music regardless of length; otherwise require a song-like duration
     * and reject the conventional non-music folders. This mirrors Android's `IS_MUSIC` boundary
     * without pretending iOS file providers supply an equivalent media classification.
     */
    private fun TrackDescriptor.isLikelyMusic(relativePath: String): Boolean {
        val normalized = "/${relativePath.lowercase().replace('\\', '/')}"
        if (NON_MUSIC_PATH_SEGMENTS.any { segment -> "/$segment/" in normalized }) return false
        val explicitlyTagged = !album.isNullOrBlank() || !genre.isNullOrBlank()
        if (explicitlyTagged) return true
        return durationMs?.let { it >= MIN_UNTAGGED_MUSIC_DURATION_MS } == true
    }

    private companion object {
        /**
         * Container extensions AVFoundation can decode on iOS.
         *
         * Ogg/Opus is included deliberately: CoreAudio gained Ogg container
         * parsing and Opus decoding, and leaving it out silently hid a fifth of
         * a real test library — the kind of gap that looks like "the app lost my
         * music" rather than "that format is unsupported".
         */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "aac", "wav", "aif", "aiff", "aifc", "caf", "flac",
            "opus", "ogg", "oga",
        )

        /** ID3's genre frame is TCON — "ContentType" in AVFoundation's naming. */
        val GENRE_IDENTIFIERS = listOf(
            AVMetadataIdentifierID3MetadataContentType,
            AVMetadataIdentifieriTunesMetadataUserGenre,
            AVMetadataIdentifieriTunesMetadataPredefinedGenre,
        )

        val YEAR_IDENTIFIERS = listOf(
            AVMetadataIdentifierID3MetadataRecordingTime,
            AVMetadataIdentifierID3MetadataYear,
            AVMetadataIdentifieriTunesMetadataReleaseDate,
        )

        /** Namespace shared with the iOS playback controller. */
        const val MEDIA_ID_PREFIX = "ios-media:"
        const val HIDDEN_FILE_NAME = "hidden_tracks.txt"
        const val EXCLUDED_SOURCES_FILE_NAME = "excluded_music_sources.txt"
        const val DEVICE_MUSIC_SOURCE_ID = "ios:music"
        const val IMPORTED_SOURCE_PREFIX = "ios:folder:"
        const val DUPLICATE_DURATION_TOLERANCE_MS = 2_000L

        /** Untagged clips below this are overwhelmingly alerts/effects, not library tracks. */
        const val MIN_UNTAGGED_MUSIC_DURATION_MS = 30_000L

        val NON_MUSIC_PATH_SEGMENTS = setOf(
            "alarms", "notifications", "recordings", "ringtones", "sfx", "sound effects",
            "voice memos",
        )

    }
}

/** Newline-safe storage for arbitrary relative paths and MediaPlayer IDs. */
private fun String.encodeHexString(): String = encodeToByteArray().joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun decodeHexString(value: String): String? {
    if (value.length % 2 != 0) return null
    return runCatching {
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.decodeToString()
    }.getOrNull()
}

/** Whole-file rewrite; playlists are few and short. */
internal class FilePlaylistStore : PlaylistStore {

    private fun path(): String? = IosPaths.appSupport()?.let { IosPaths.child(it, FILE_NAME) }

    override suspend fun read(): List<String> = withContext(Dispatchers.Default) {
        path()?.let(::readLinesOrEmpty) ?: emptyList()
    }

    override suspend fun write(lines: List<String>): Unit = withContext(Dispatchers.Default) {
        path()?.let { writeText(it, lines.joinToString("\n")) }
        Unit
    }

    private companion object {
        const val FILE_NAME = "playlists.txt"
    }
}

public actual fun musicLibraryModule(): Module = module {
    single<MusicLibrary> { IosMusicLibrary() }
    single<PlaylistStore> { FilePlaylistStore() }
    single<Playlists> { DefaultPlaylists(store = get()) }
}

public actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
