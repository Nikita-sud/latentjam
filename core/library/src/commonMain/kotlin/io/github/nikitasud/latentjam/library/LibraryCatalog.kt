/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * An album as grouped from track metadata.
 *
 * @property key Stable grouping key. The artwork URI doubles as an album-id
 *   proxy when present (it is derived from MediaStore's ALBUM_ID on Android),
 *   which keeps two same-named albums by different artists apart; the
 *   `album::artist` fallback covers artwork-less tracks. A first-class album
 *   entity arrives with the own-scanner roadmap item.
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

/**
 * The whole library, grouped for browsing. Pure derivation from the flat
 * track list — no platform types, trivially testable.
 *
 * Sorting: albums by title, artists/genres by name (case-insensitive),
 * unknown (null) buckets last; an album's tracks by title until real track
 * numbers arrive with the own scanner.
 */
public data class LibraryCatalog(
    public val songs: List<TrackDescriptor>,
    public val albums: List<AlbumGroup>,
    public val artists: List<ArtistGroup>,
    public val genres: List<GenreGroup>,
) {
    public companion object {

        public fun build(tracks: List<TrackDescriptor>): LibraryCatalog {
            val albums = tracks
                .groupBy { track -> track.artworkUri ?: "${track.album}::${track.artist}" }
                .map { (key, grouped) ->
                    AlbumGroup(
                        key = key,
                        title = grouped.firstNotNullOfOrNull { it.album },
                        artist = grouped.firstNotNullOfOrNull { it.artist },
                        artworkUri = grouped.firstNotNullOfOrNull { it.artworkUri },
                        tracks = grouped.sortedBy { it.title?.lowercase() ?: "￿" },
                    )
                }
                .sortedBy { it.title?.lowercase() ?: "￿" }

            val albumCountByArtist = albums
                .groupingBy { it.artist }
                .eachCount()

            val artists = tracks
                .groupBy { it.artist }
                .map { (name, grouped) ->
                    ArtistGroup(
                        name = name,
                        tracks = grouped.sortedBy { it.title?.lowercase() ?: "￿" },
                        albumCount = albumCountByArtist[name] ?: 0,
                    )
                }
                .sortedBy { it.name?.lowercase() ?: "￿" }

            val genres = tracks
                .groupBy { it.genre }
                .map { (name, grouped) ->
                    GenreGroup(
                        name = name,
                        tracks = grouped.sortedBy { it.title?.lowercase() ?: "￿" },
                    )
                }
                .sortedBy { it.name?.lowercase() ?: "￿" }

            return LibraryCatalog(
                songs = tracks,
                albums = albums,
                artists = artists,
                genres = genres,
            )
        }
    }
}
