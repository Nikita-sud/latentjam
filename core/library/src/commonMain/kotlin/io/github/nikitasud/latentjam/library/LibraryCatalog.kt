/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

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
 * unknown (null) buckets last; an album's tracks by title until real track
 * numbers arrive with the own scanner.
 */
public data class LibraryCatalog(
    public val songs: List<TrackDescriptor>,
    public val albums: List<AlbumGroup>,
    public val artists: List<ArtistGroup>,
    public val genres: List<GenreGroup>,
    public val folders: List<FolderGroup>,
) {
    public companion object {

        public fun build(tracks: List<TrackDescriptor>): LibraryCatalog {
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

            val albums = tracks
                .albumGroups()
                .map { (identity, grouped) ->
                    AlbumGroup(
                        key = identity.stableKey(),
                        title = grouped.firstNotNullOfOrNull { it.album },
                        artist = grouped.firstNotNullOfOrNull { it.artist },
                        artworkUri = grouped.firstNotNullOfOrNull { it.artworkUri },
                        tracks = grouped.byTitle(),
                    )
                }
                .sortedByKey { it.title?.lowercase() ?: UNKNOWN_LAST }

            val albumCountByArtist = albums
                .groupingBy { it.artist }
                .eachCount()

            val artists = tracks
                .groupBy { it.artist }
                .map { (name, grouped) ->
                    ArtistGroup(
                        name = name,
                        tracks = grouped.byTitle(),
                        albumCount = albumCountByArtist[name] ?: 0,
                    )
                }
                .sortedByKey { it.name?.lowercase() ?: UNKNOWN_LAST }

            val genres = tracks
                .groupBy { it.genre }
                .map { (name, grouped) ->
                    GenreGroup(
                        name = name,
                        tracks = grouped.byTitle(),
                    )
                }
                .sortedByKey { it.name?.lowercase() ?: UNKNOWN_LAST }

            val folders = tracks
                .groupBy { it.folderPath?.trim('/') ?: DEFAULT_FOLDER }
                .map { (path, grouped) ->
                    FolderGroup(
                        path = path,
                        name = path.substringAfterLast('/'),
                        tracks = grouped.byTitle(),
                    )
                }
                .map { Triple(it, it.name.lowercase(), it.path.lowercase()) }
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
