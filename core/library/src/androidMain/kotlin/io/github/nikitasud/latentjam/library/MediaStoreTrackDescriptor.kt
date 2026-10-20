/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * A MediaStore song as the scan read it, kept small: three of its strings are formed from numbers when they are
 * read instead of being held, and its numbers are not boxed. Every field reads exactly what the plain descriptor the
 * scan used to build would hold, and equality, hashing and copies are [TrackDescriptor]'s.
 *
 * - [audioUri] is the MediaStore row's content URI (`…/audio/media/<source id>`).
 * - [artworkUri] is the song-cover locator of the source id and album id, carrying its album's version once
 *   [withArtVersion] gave one (see AlbumArtVersions).
 * - [sourceRevision] is [androidMediaSourceRevision] of the size, modification time and generation.
 *
 * A [copy] that changes one of those three to some other string keeps that string as given; one that changes a number
 * to a value the compact form cannot hold returns a plain descriptor. At 50,000 songs the three strings and the boxed
 * numbers were ~15 MB of a 96 MB heap.
 */
internal class MediaStoreTrackDescriptor private constructor(
    override val id: TrackId,
    override val title: String?,
    override val artist: String?,
    override val album: String?,
    override val genre: String?,
    private val duration: Long,
    private val sourceId: Long,
    private val albumId: Long,
    /** The album's cover version (shared by the album's songs), or null before AlbumArtVersions. */
    private val artVersion: String?,
    private val added: Long,
    override val folderPath: String?,
    private val yearValue: Int,
    override val energy: Float?,
    private val revisionSize: Long,
    private val revisionModified: Long,
    private val revisionGeneration: Long,
    override val artists: List<String>,
    private val originalYearValue: Int,
    private val size: Long,
    override val fileName: String?,
    override val language: String?,
    private val trackNo: Int,
    private val discNo: Int,
    override val albumArtist: String?,
    override val albumArtworkUri: String?,
    override val storageVolume: String?,
    /** Strings a copy set to something the numbers do not form (rare: cover overrides). */
    private val explicit: Explicit?,
) : TrackDescriptor() {

    private class Explicit(val audioUri: Any?, val artworkUri: Any?, val sourceRevision: Any?)

    override val durationMs: Long? get() = duration.orNull()
    override val addedAtMs: Long? get() = added.orNull()
    override val year: Int? get() = yearValue.orNull()
    override val originalYear: Int? get() = originalYearValue.orNull()
    override val sizeBytes: Long? get() = size.orNull()
    override val trackNumber: Int? get() = trackNo.orNull()
    override val discNumber: Int? get() = discNo.orNull()

    override val audioUri: String?
        get() = explicit?.audioUri.let { if (it === UNSET || explicit == null) "$SONGS$sourceId" else it as String? }

    override val artworkUri: String?
        get() = explicit?.artworkUri.let {
            if (it === UNSET || explicit == null) {
                val cover = MediaStoreArtwork.trackCover(sourceId, albumId)
                if (artVersion == null) cover else MediaStoreArtwork.versioned(cover, artVersion)
            } else {
                it as String?
            }
        }

    override val sourceRevision: String?
        get() = explicit?.sourceRevision.let {
            if (it === UNSET || explicit == null) {
                androidMediaSourceRevision(revisionSize.orNull(), revisionModified.orNull(), revisionGeneration.orNull())
            } else {
                it as String?
            }
        }

    /**
     * This song with its cover locators at [version]: [artworkUri] versioned, [albumArtworkUri] replaced by the given
     * (shared) versioned album locator. Null when the cover locator was set by a copy (then version it by copy).
     */
    fun withArtVersion(version: String, versionedAlbum: String?): TrackDescriptor? {
        if (explicit != null && explicit.artworkUri !== UNSET) return null
        return build(artVersion = version, albumArtworkUri = versionedAlbum, explicit = explicit)
    }

    override fun copy(
        id: TrackId,
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        durationMs: Long?,
        audioUri: String?,
        artworkUri: String?,
        addedAtMs: Long?,
        folderPath: String?,
        year: Int?,
        energy: Float?,
        sourceRevision: String?,
        artists: List<String>,
        originalYear: Int?,
        sizeBytes: Long?,
        fileName: String?,
        language: String?,
        trackNumber: Int?,
        discNumber: Int?,
        albumArtist: String?,
        albumArtworkUri: String?,
        storageVolume: String?,
    ): TrackDescriptor {
        val representable = durationMs != LONG_NULL && addedAtMs != LONG_NULL && sizeBytes != LONG_NULL &&
            year != INT_NULL && originalYear != INT_NULL && trackNumber != INT_NULL && discNumber != INT_NULL
        if (!representable) {
            return TrackDescriptor(
                id, title, artist, album, genre, durationMs, audioUri, artworkUri, addedAtMs, folderPath, year,
                energy, sourceRevision, artists, originalYear, sizeBytes, fileName, language, trackNumber,
                discNumber, albumArtist, albumArtworkUri, storageVolume,
            )
        }
        // A derived string the copy leaves as it reads stays derived; a changed one is kept as given.
        val kept = explicit
        val newAudio: Any? = if (audioUri == this.audioUri) (if (kept != null) kept.audioUri else UNSET) else audioUri
        val newArtwork: Any? =
            if (artworkUri == this.artworkUri) (if (kept != null) kept.artworkUri else UNSET) else artworkUri
        val newRevision: Any? = if (sourceRevision == this.sourceRevision) {
            if (kept != null) kept.sourceRevision else UNSET
        } else {
            sourceRevision
        }
        val newExplicit = if (newAudio === UNSET && newArtwork === UNSET && newRevision === UNSET) {
            null
        } else {
            Explicit(newAudio, newArtwork, newRevision)
        }
        return MediaStoreTrackDescriptor(
            id, title, artist, album, genre, durationMs.orSentinel(), sourceId, albumId, artVersion,
            addedAtMs.orSentinel(), folderPath, year.orSentinel(), energy, revisionSize, revisionModified,
            revisionGeneration, artists, originalYear.orSentinel(), sizeBytes.orSentinel(), fileName, language,
            trackNumber.orSentinel(), discNumber.orSentinel(), albumArtist, albumArtworkUri, storageVolume, newExplicit,
        )
    }

    private fun build(artVersion: String?, albumArtworkUri: String?, explicit: Explicit?) = MediaStoreTrackDescriptor(
        id, title, artist, album, genre, duration, sourceId, albumId, artVersion, added, folderPath, yearValue,
        energy, revisionSize, revisionModified, revisionGeneration, artists, originalYearValue, size, fileName,
        language, trackNo, discNo, albumArtist, albumArtworkUri, storageVolume, explicit,
    )

    companion object {
        private const val SONGS = "content://media/external/audio/media/"
        private const val LONG_NULL = Long.MIN_VALUE
        private const val INT_NULL = Int.MIN_VALUE
        private val UNSET = Any()

        private fun Long.orNull(): Long? = if (this == LONG_NULL) null else this
        private fun Int.orNull(): Int? = if (this == INT_NULL) null else this
        private fun Long?.orSentinel(): Long = this ?: LONG_NULL
        private fun Int?.orSentinel(): Int = this ?: INT_NULL

        /**
         * The scan's row: [sourceId] and [albumId] are MediaStore's `_ID` and `ALBUM_ID`; the revision numbers are
         * passed as [androidMediaSourceRevision] takes them. [albumArtworkUri] should be shared per album.
         */
        fun fromMediaStore(
            sourceId: Long,
            albumId: Long,
            title: String?,
            artist: String?,
            album: String?,
            genre: String?,
            albumArtist: String?,
            durationMs: Long?,
            albumArtworkUri: String?,
            addedAtMs: Long?,
            folderPath: String?,
            storageVolume: String?,
            year: Int?,
            sizeBytes: Long?,
            fileName: String?,
            trackNumber: Int?,
            discNumber: Int?,
            revisionSize: Long?,
            revisionModified: Long?,
            revisionGeneration: Long?,
        ): MediaStoreTrackDescriptor = MediaStoreTrackDescriptor(
            id = TrackId(sourceId.toString()),
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            duration = durationMs.orSentinel(),
            sourceId = sourceId,
            albumId = albumId,
            artVersion = null,
            added = addedAtMs.orSentinel(),
            folderPath = folderPath,
            yearValue = year.orSentinel(),
            energy = null,
            revisionSize = revisionSize.orSentinel(),
            revisionModified = revisionModified.orSentinel(),
            revisionGeneration = revisionGeneration.orSentinel(),
            artists = emptyList(),
            originalYearValue = INT_NULL,
            size = sizeBytes.orSentinel(),
            fileName = fileName,
            language = null,
            trackNo = trackNumber.orSentinel(),
            discNo = discNumber.orSentinel(),
            albumArtist = albumArtist,
            albumArtworkUri = albumArtworkUri,
            storageVolume = storageVolume,
            explicit = null,
        )
    }
}
