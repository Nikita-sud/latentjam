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

/**
 * [MusicLibrary] over the app's own Documents folder.
 *
 * ### Why files and not MPMediaQuery
 * The device's Apple Music library is reachable through `MPMediaQuery`, but
 * anything bought or streamed from Apple Music is FairPlay-protected and will
 * not hand over raw samples. SMART's audio encoder has to decode a waveform to
 * embed a track, so a DRM-backed source would silently degrade the whole
 * feature to metadata-only. Files the user owns keep every feature working,
 * and `UIFileSharingEnabled` makes "drop music into LatentJam" a normal
 * Files.app drag.
 *
 * ### Identity
 * [TrackId] is the path RELATIVE to Documents, never the absolute one: iOS
 * rewrites the container UUID in `/var/mobile/Containers/Data/Application/…`
 * across reinstalls and some OS updates, so absolute paths would orphan every
 * history row and index entry the first time that happened.
 */
internal class DocumentsMusicLibrary : MusicLibrary {

    /** Guards [cache] — [tracks] may be called from several screens at once. */
    private val scanMutex = Mutex()

    /** Relative path → last scan result, reused while size and mtime agree. */
    private val cache = mutableMapOf<String, CachedTrack>()

    private data class CachedTrack(
        val modifiedAtMs: Long,
        val sizeBytes: Long,
        val descriptor: TrackDescriptor,
    )

    override suspend fun tracks(): List<TrackDescriptor> = withContext(Dispatchers.Default) {
        val documents = IosPaths.documents() ?: return@withContext emptyList()
        scanMutex.withLock { scan(documents) }
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
                found += cached.descriptor
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
            found += descriptor
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
            artworkUri = artwork?.let { cacheArtwork(it, relativePath, sizeBytes) },
            addedAtMs = addedAtMs,
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
     * Keyed by path and size so that re-importing a different file under the
     * same name cannot serve the previous file's artwork.
     */
    private fun cacheArtwork(data: NSData, relativePath: String, sizeBytes: Long): String? {
        val caches = IosPaths.caches() ?: return null
        val key = "${relativePath.hashCode().toUInt().toString(16)}-$sizeBytes"
        val target = IosPaths.child(caches, "artwork-$key.img")
        if (!NSFileManager.defaultManager.fileExistsAtPath(target)) {
            if (!data.writeToFile(target, true)) return null
        }
        return fileUrl(target).absoluteString
    }

    private fun String?.knownOrNull(): String? = this?.takeIf { it.isNotBlank() }

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
    }
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
    single<MusicLibrary> { DocumentsMusicLibrary() }
    single<PlaylistStore> { FilePlaylistStore() }
    single<Playlists> { DefaultPlaylists(store = get()) }
}

public actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
