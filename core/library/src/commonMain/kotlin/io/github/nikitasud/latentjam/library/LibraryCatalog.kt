/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.GenreTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * An album as grouped from track metadata.
 *
 * @property key Stable grouping key derived from normalized album metadata.
 *   Artwork is used only to disambiguate same-named albums by different
 *   artists. This matters on iOS, where extracted artwork has historically
 *   had a per-track file URI even when every track belongs to one album.
 */
public data class AlbumGroup(
    public val key: String,
    public val title: String?,
    public val artist: String?,
    public val artworkUri: String?,
    public val tracks: List<TrackDescriptor>,
)

/** An artist as grouped from track metadata (single-string artist for now). */
public data class ArtistGroup(
    public val name: String?,
    public val tracks: List<TrackDescriptor>,
    public val albumCount: Int,
)

/** A genre as grouped from track metadata (null = untagged tracks). */
public data class GenreGroup(
    public val name: String?,
    public val tracks: List<TrackDescriptor>,
)

/** A storage/source folder. [path] is the stable grouping key; [name] is its last segment. */
public data class FolderGroup(
    public val path: String,
    public val name: String,
    public val tracks: List<TrackDescriptor>,
)

/**
 * The whole library, grouped for browsing. Pure derivation from the flat
 * track list — no platform types, trivially testable.
 *
 * Sorting: albums by title, artists/genres/folders by name (case-insensitive),
 * unknown (null) buckets last; an album's tracks in release order (see [inAlbumOrder]),
 * every other group's tracks by title.
 */
public data class LibraryCatalog(
    public val songs: List<TrackDescriptor>,
    public val albums: List<AlbumGroup>,
    public val artists: List<ArtistGroup>,
    public val genres: List<GenreGroup>,
    public val folders: List<FolderGroup>,
) {
    public companion object {

        public fun build(
            tracks: List<TrackDescriptor>,
            isKnownArtist: ((String) -> Boolean)? = null,
        ): LibraryCatalog {
            // Every grouping below orders its tracks by title, and each `sortedBy` used to
            // lowercase inside the comparator — so one title was rebuilt four times over, then
            // again on each of the O(n log n) comparisons. Keying once up front leaves the
            // comparators doing nothing but comparing. Same keys, same stable sort, same order.
            val titleKeys = HashMap<TrackId, String>(tracks.size)
            for (track in tracks) {
                titleKeys[track.id] = track.title?.lowercase() ?: UNKNOWN_LAST
            }

            fun List<TrackDescriptor>.byTitle(): List<TrackDescriptor> =
                map { it to titleKeys.getValue(it.id) }
                    .sortedBy { it.second }
                    .map { it.first }

            // Real albums first: a library assembled from loose downloads is mostly one-track
            // pseudo-albums, and alphabetizing them together drowns the actual albums the tab
            // exists to browse. Two alphabetical blocks — multi-track, then singles.
            val albums = tracks
                .albumGroups()
                .map { (identity, grouped) ->
                    AlbumGroup(
                        key = identity.stableKey(),
                        title = grouped.firstNotNullOfOrNull { it.album },
                        artist = grouped.firstNotNullOfOrNull { it.artist },
                        artworkUri = grouped.firstNotNullOfOrNull { it.artworkUri },
                        tracks = inAlbumOrder(grouped),
                    )
                }
                .map { Triple(it, it.tracks.size < 2, SongSorting.sortKey(it.title)) }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .map { it.first }

            val albumCountByArtist = albums
                .groupingBy { it.artist }
                .eachCount()

            // A collaboration belongs to EVERY credited artist: the tags' ARTISTS list is
            // authoritative when read. Otherwise the display credit is split where an artist
            // list confirms it ([ArtistCredits]); without one, only at semicolons, the list
            // separator by convention, because guessing "feat." or "&" apart blind ruins band
            // names. Casing differences collapse to one group.
            val displayCredits = HashMap<String, List<String>>()
            val artists = tracks
                .flatMap { track ->
                    val credits: List<String?> = track.artists.ifEmpty {
                        val artist = track.artist ?: return@ifEmpty listOf(null)
                        if (isKnownArtist != null) {
                            displayCredits.getOrPut(artist) { ArtistCredits.split(artist, isKnownArtist) }
                        } else {
                            artist.split(';')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .takeIf { it.size > 1 }
                                ?: listOf(artist)
                        }
                    }
                    credits.map { it to track }
                }
                .groupBy { (name, _) -> name?.lowercase() }
                .map { (_, entries) ->
                    val name = entries.first().first
                    ArtistGroup(
                        name = name,
                        tracks = entries.map { it.second }.distinct().byTitle(),
                        albumCount = albumCountByArtist[name] ?: 0,
                    )
                }
                .sortedByKey { SongSorting.sortKey(it.name) }

            // A multi-genre track belongs to EVERY genre it carries: the tags may hold several
            // values (joined "; " canonically), and listing "Dirty Harry" under Trip Hop as well
            // as Electronic is the whole point of reading them. Casing differences collapse to
            // one group named by the first spelling seen.
            val genres = tracks
                .flatMap { track ->
                    val split = GenreTags.split(track.genre)
                    if (split.isEmpty()) listOf(null to track) else split.map { it to track }
                }
                .groupBy { (name, _) -> name?.lowercase() }
                .map { (_, entries) ->
                    GenreGroup(
                        name = entries.first().first,
                        tracks = entries.map { it.second }.byTitle(),
                    )
                }
                .sortedByKey { SongSorting.sortKey(it.name) }

            val folders = tracks
                .groupBy { it.folderPath?.trim('/') ?: DEFAULT_FOLDER }
                .map { (path, grouped) ->
                    FolderGroup(
                        path = path,
                        name = path.substringAfterLast('/'),
                        tracks = grouped.byTitle(),
                    )
                }
                .map {
                    Triple(
                        it,
                        SongSorting.sortKey(it.name),
                        SongSorting.sortKey(it.path),
                    )
                }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .map { it.first }

            return LibraryCatalog(
                songs = tracks,
                albums = albums,
                artists = artists,
                genres = genres,
                folders = folders,
            )
        }

        /**
         * Release order: disc, then track number, as the tags state them. A disc number is
         * assumed to be 1 when only the track is tagged, so a half-tagged set still interleaves
         * correctly. Tracks without a number follow the numbered ones by title, which is also the
         * whole order of an album nobody numbered.
         */
        public fun inAlbumOrder(tracks: List<TrackDescriptor>): List<TrackDescriptor> =
            tracks
                .map { track ->
                    AlbumPlace(
                        track = track,
                        unnumbered = track.trackNumber == null,
                        disc = track.discNumber ?: 1,
                        number = track.trackNumber ?: 0,
                        // The title rail uses this same article/punctuation folding when an
                        // album has no track numbers and falls back to alphabetical order.
                        title = SongSorting.sortKey(track.title),
                    )
                }
                .sortedWith(ALBUM_PLACE_ORDER)
                .map { it.track }

        private class AlbumPlace(
            val track: TrackDescriptor,
            val unnumbered: Boolean,
            val disc: Int,
            val number: Int,
            val title: String,
        )

        private val ALBUM_PLACE_ORDER: Comparator<AlbumPlace> =
            compareBy({ it.unnumbered }, { it.disc }, { it.number }, { it.title })

        /** Sorts by a key computed once per element instead of once per comparison. */
        private inline fun <T> List<T>.sortedByKey(key: (T) -> String): List<T> =
            map { it to key(it) }
                .sortedBy { it.second }
                .map { it.first }

        private const val DEFAULT_FOLDER = "Music"

        /** Sorts after every real name; U+FFFF has no assigned character above it. */
        private const val UNKNOWN_LAST = "￿"

        /** Hoisted: `Regex(...)` compiles a pattern, and this runs once per track. */
        private val WHITESPACE = Regex("\\s+")

        /**
         * Builds album groups without treating a cache-file URI as album identity.
         *
         * One artist + one normalized title is unambiguously one album even if
         * each file exposes a different extracted-art URI. If the same title is
         * owned by multiple artists, shared artwork still joins compilations;
         * otherwise artwork/artist separates genuinely different releases.
         */
        private fun List<TrackDescriptor>.albumGroups(): Map<AlbumIdentity, List<TrackDescriptor>> {
            val result = LinkedHashMap<AlbumIdentity, List<TrackDescriptor>>()
            for ((albumTitle, sameTitle) in groupBy { it.album.normalizedKey() }) {
                if (albumTitle == null) {
                    for ((discriminator, grouped) in sameTitle.groupBy { it.albumDiscriminator() }) {
                        result[AlbumIdentity(albumTitle, discriminator)] = grouped
                    }
                    continue
                }

                val artists = sameTitle.mapNotNull { it.artist.normalizedKey() }.toSet()
                val artwork = sameTitle.mapNotNull { it.artworkUri }.toSet()
                when {
                    artists.size <= 1 -> {
                        result[AlbumIdentity(albumTitle, AlbumDiscriminator.Artist(artists.firstOrNull()))] = sameTitle
                    }
                    artwork.size == 1 -> {
                        result[AlbumIdentity(albumTitle, AlbumDiscriminator.Artwork(artwork.first()))] = sameTitle
                    }
                    else -> {
                        for ((discriminator, grouped) in sameTitle.groupBy { it.albumDiscriminator() }) {
                            result[AlbumIdentity(albumTitle, discriminator)] = grouped
                        }
                    }
                }
            }
            return result
        }

        /** Artwork and artist are different identity domains even when their strings happen to match. */
        private fun TrackDescriptor.albumDiscriminator(): AlbumDiscriminator =
            artworkUri?.let(AlbumDiscriminator::Artwork)
                ?: AlbumDiscriminator.Artist(artist.normalizedKey())

        /**
         * Album grouping is deliberately structural. Concatenating title, artist and artwork with
         * a delimiter makes valid metadata containing that delimiter collide and silently drops a
         * group when the resulting map is built.
         */
        private data class AlbumIdentity(
            val normalizedTitle: String?,
            val discriminator: AlbumDiscriminator,
        ) {
            /** A collision-free public string for Compose keys and other callers. */
            fun stableKey(): String = when (val part = discriminator) {
                is AlbumDiscriminator.Artist ->
                    "album:v2:${normalizedTitle.stableComponent()}:artist:${part.normalizedName.stableComponent()}"
                is AlbumDiscriminator.Artwork ->
                    "album:v2:${normalizedTitle.stableComponent()}:artwork:${part.uri.stableComponent()}"
            }
        }

        private sealed interface AlbumDiscriminator {
            data class Artist(val normalizedName: String?) : AlbumDiscriminator
            data class Artwork(val uri: String) : AlbumDiscriminator
        }

        /** Length-prefixing makes arbitrary punctuation in metadata unambiguous. */
        private fun String?.stableComponent(): String =
            this?.let { "${it.length}:$it" } ?: "null"

        private fun String?.normalizedKey(): String? = this
            ?.filterNot { it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' }
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }
}
