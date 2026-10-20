/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.jvm.JvmInline

/**
 * Stable, opaque identifier of a track in the caller's library.
 *
 * The engine never interprets the value; it only uses it as a key. Callers map
 * their own domain identifiers (database row ids, content URIs, file hashes, …)
 * into [TrackId] at the boundary and back out when a result is returned. This
 * is the only "identity" concept shared between the engine and the app, which
 * is what keeps the engine free of any media-library or playback types.
 */
@JvmInline
public value class TrackId(public val value: String)

/**
 * Everything the engine may know about a single track.
 *
 * This is a pure data snapshot — deliberately NOT a reference to any player,
 * media-session, or library entity. All fields except [id] are optional so the
 * caller can provide whatever it has; backends degrade gracefully with less.
 *
 * @property id Stable identifier of the track. Required.
 * @property title Track title tag, if known.
 * @property artist Artist tag, if known.
 * @property album Album tag, if known.
 * @property genre Genre tag, if known.
 * @property durationMs Track duration in milliseconds, if known.
 * @property audioUri Platform-resolvable locator of the raw audio (a
 *   `content://` URI on Android, a file URL on iOS, …). Backends that embed
 *   raw audio (the CNN encoder) use this to decode the waveform; metadata-only
 *   backends ignore it. `null` means "audio not available for this track".
 * @property artworkUri Platform-resolvable locator of the track's cover art,
 *   for UI and media-notification display. The engine ignores it.
 * @property addedAtMs When the track appeared in the device library (epoch
 *   ms), for recency sorting. The engine ignores it.
 * @property folderPath User-facing source folder, relative to shared storage
 *   when the platform exposes one. The engine ignores it; the library browser
 *   uses it to keep Downloads, Music, Telegram, and imported files separate.
 * @property sourceRevision Optional opaque revision of the underlying media bytes. Platform
 *   libraries should change it when content at the same [id]/[audioUri] may have been replaced
 *   (for example MediaStore size + modified generation, or an imported file's size + mtime).
 *   SMART uses it only for cache invalidation and never interprets its value.
 */
public abstract class TrackDescriptor {
    public abstract val id: TrackId
    public abstract val title: String?
    public abstract val artist: String?
    public abstract val album: String?
    public abstract val genre: String?
    public abstract val durationMs: Long?
    public abstract val audioUri: String?
    public abstract val artworkUri: String?
    public abstract val addedAtMs: Long?
    public abstract val folderPath: String?
    /** Release year. Feeds the chain's era penalty, so a missing value simply costs that signal. */
    public abstract val year: Int?
    /** Loudness in `[0, 1]`, or null when unmeasured. Feeds the chain's energy-smoothness term. */
    public abstract val energy: Float?
    public abstract val sourceRevision: String?
    /**
     * Credited individuals read from the file's own tags (the Picard `ARTISTS` convention);
     * [artist] remains the display credit ("X feat. Y"). Empty until tag enrichment has read
     * the file — consumers must treat empty as "unknown", never as "solo".
     */
    public abstract val artists: List<String>
    /** First-release year from the tags; [year] is the edition year the scanner reported. */
    public abstract val originalYear: Int?
    /** File size in bytes as the library source reports it; null when the source has none. */
    public abstract val sizeBytes: Long?
    /** File name with its extension, the honest source of a "FLAC"/"MP3" label; null when unknown. */
    public abstract val fileName: String?
    /**
     * Vocal language as the file's own tags state it (`LANGUAGE`, ID3 `TLAN`): an ISO 639 code or
     * a plain name, uninterpreted. Null when the tags say nothing; consumers may fall back to
     * script detection but must not invent a language from silence.
     */
    public abstract val language: String?
    /** Position on its disc as the tags number it (`TRCK`, `TRACKNUMBER`); null when untagged. */
    public abstract val trackNumber: Int?
    /** Disc of a multi-disc release (`TPOS`, `DISCNUMBER`); null when untagged. */
    public abstract val discNumber: Int?
    /**
     * The album's own artist (`TPE2`, `ALBUMARTIST`, `aART`): "Various Artists" on a compilation.
     * Albums group by it when present, so a compilation's tracks stay one album. Null when untagged.
     */
    public abstract val albumArtist: String?
    /**
     * The album's cover locator when [artworkUri] is this track's own instead: on Android 10+ every
     * song's locator is its own cover (see MediaStoreArtwork), and on any release the one LatentJam
     * saved into it, while MediaStore keeps one cover per album. Album grouping compares this, so songs with
     * covers of their own still make one album. Null when [artworkUri] already is the album's.
     */
    public abstract val albumArtworkUri: String?
    /**
     * The storage volume the file is on, as the platform names it (Android's MediaStore volume:
     * `external_primary` for the device's own storage, a card's lowercase id such as `1a2b-3c4d`).
     * [folderPath] is relative to it, so the folder browser can show each volume as its own root.
     * Null where the platform has a single store (iOS). The engine ignores it.
     */
    public abstract val storageVolume: String?

    /**
     * A copy with the given fields replaced, as the data class this was gave. A platform's compact descriptor may
     * override it to stay compact; the copy's values are these either way.
     */
    public open fun copy(
        id: TrackId = this.id,
        title: String? = this.title,
        artist: String? = this.artist,
        album: String? = this.album,
        genre: String? = this.genre,
        durationMs: Long? = this.durationMs,
        audioUri: String? = this.audioUri,
        artworkUri: String? = this.artworkUri,
        addedAtMs: Long? = this.addedAtMs,
        folderPath: String? = this.folderPath,
        year: Int? = this.year,
        energy: Float? = this.energy,
        sourceRevision: String? = this.sourceRevision,
        artists: List<String> = this.artists,
        originalYear: Int? = this.originalYear,
        sizeBytes: Long? = this.sizeBytes,
        fileName: String? = this.fileName,
        language: String? = this.language,
        trackNumber: Int? = this.trackNumber,
        discNumber: Int? = this.discNumber,
        albumArtist: String? = this.albumArtist,
        albumArtworkUri: String? = this.albumArtworkUri,
        storageVolume: String? = this.storageVolume,
    ): TrackDescriptor = TrackDescriptor(id, title, artist, album, genre, durationMs, audioUri, artworkUri, addedAtMs, folderPath, year, energy, sourceRevision, artists, originalYear, sizeBytes, fileName, language, trackNumber, discNumber, albumArtist, albumArtworkUri, storageVolume)

    /** Value equality over every field, exactly as the data class this was compared. */
    final override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackDescriptor) return false
        return id == other.id &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            genre == other.genre &&
            durationMs == other.durationMs &&
            audioUri == other.audioUri &&
            artworkUri == other.artworkUri &&
            addedAtMs == other.addedAtMs &&
            folderPath == other.folderPath &&
            year == other.year &&
            (energy as Any?) == (other.energy as Any?) &&
            sourceRevision == other.sourceRevision &&
            artists == other.artists &&
            originalYear == other.originalYear &&
            sizeBytes == other.sizeBytes &&
            fileName == other.fileName &&
            language == other.language &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            albumArtist == other.albumArtist &&
            albumArtworkUri == other.albumArtworkUri &&
            storageVolume == other.storageVolume
    }

    /** The data class's hash: the same formula, so hashed collections iterate as they did. */
    final override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + (audioUri?.hashCode() ?: 0)
        result = 31 * result + (artworkUri?.hashCode() ?: 0)
        result = 31 * result + (addedAtMs?.hashCode() ?: 0)
        result = 31 * result + (folderPath?.hashCode() ?: 0)
        result = 31 * result + (year?.hashCode() ?: 0)
        result = 31 * result + (energy?.hashCode() ?: 0)
        result = 31 * result + (sourceRevision?.hashCode() ?: 0)
        result = 31 * result + artists.hashCode()
        result = 31 * result + (originalYear?.hashCode() ?: 0)
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (trackNumber?.hashCode() ?: 0)
        result = 31 * result + (discNumber?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (albumArtworkUri?.hashCode() ?: 0)
        result = 31 * result + (storageVolume?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TrackDescriptor(id=${id}, title=${title}, artist=${artist}, album=${album}, genre=${genre}, durationMs=${durationMs}, audioUri=${audioUri}, artworkUri=${artworkUri}, addedAtMs=${addedAtMs}, folderPath=${folderPath}, year=${year}, energy=${energy}, sourceRevision=${sourceRevision}, artists=${artists}, originalYear=${originalYear}, sizeBytes=${sizeBytes}, fileName=${fileName}, language=${language}, trackNumber=${trackNumber}, discNumber=${discNumber}, albumArtist=${albumArtist}, albumArtworkUri=${albumArtworkUri}, storageVolume=${storageVolume})"
}

/** A [TrackDescriptor] with every field given (what `TrackDescriptor(...)` constructed when it was a data class). */
public fun TrackDescriptor(
    id: TrackId,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    genre: String? = null,
    durationMs: Long? = null,
    audioUri: String? = null,
    artworkUri: String? = null,
    addedAtMs: Long? = null,
    folderPath: String? = null,
    year: Int? = null,
    energy: Float? = null,
    sourceRevision: String? = null,
    artists: List<String> = emptyList(),
    originalYear: Int? = null,
    sizeBytes: Long? = null,
    fileName: String? = null,
    language: String? = null,
    trackNumber: Int? = null,
    discNumber: Int? = null,
    albumArtist: String? = null,
    albumArtworkUri: String? = null,
    storageVolume: String? = null,
): TrackDescriptor = FieldTrackDescriptor(id, title, artist, album, genre, durationMs, audioUri, artworkUri, addedAtMs, folderPath, year, energy, sourceRevision, artists, originalYear, sizeBytes, fileName, language, trackNumber, discNumber, albumArtist, albumArtworkUri, storageVolume)

private class FieldTrackDescriptor(
    override val id: TrackId,
    override val title: String?,
    override val artist: String?,
    override val album: String?,
    override val genre: String?,
    override val durationMs: Long?,
    override val audioUri: String?,
    override val artworkUri: String?,
    override val addedAtMs: Long?,
    override val folderPath: String?,
    override val year: Int?,
    override val energy: Float?,
    override val sourceRevision: String?,
    override val artists: List<String>,
    override val originalYear: Int?,
    override val sizeBytes: Long?,
    override val fileName: String?,
    override val language: String?,
    override val trackNumber: Int?,
    override val discNumber: Int?,
    override val albumArtist: String?,
    override val albumArtworkUri: String?,
    override val storageVolume: String?,
) : TrackDescriptor()

/**
 * The listening situation a next-track query is made from.
 *
 * This is the engine-facing projection of "what is happening right now",
 * expressed without any playback-state-machine types: the caller observes its
 * player however it likes and distills the result into this value object.
 *
 * @property seed The track to find a nearest neighbor for — typically the
 *   currently playing track.
 * @property recentTrackIds Recently played tracks, excluded from results to
 *   avoid immediate repeats. Order is irrelevant to the engine.
 * @property excludedTrackIds Additional tracks the caller never wants returned
 *   (e.g. user-banned tracks, tracks already queued).
 */
public data class ListeningContext(
    public val seed: TrackDescriptor,
    public val recentTrackIds: List<TrackId> = emptyList(),
    public val excludedTrackIds: Set<TrackId> = emptySet(),
)

/**
 * One local listening observation supplied to SMART queue planning.
 *
 * This deliberately mirrors only the signals the models consume; it is not a persistence or
 * playback type. Events stay on the device and should be ordered oldest first. [playedFraction]
 * is clamped by the engine, so callers may pass a best estimate when duration metadata is absent.
 */
public data class SmartHistoryEvent(
    public val trackId: TrackId,
    public val startedAtMs: Long,
    public val playedFraction: Float,
    public val completed: Boolean,
    public val skipped: Boolean,
)
