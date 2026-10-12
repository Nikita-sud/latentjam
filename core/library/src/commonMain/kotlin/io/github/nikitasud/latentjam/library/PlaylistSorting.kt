/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** How the tracks on one playlist's page, or on Favorites, are shown. Never written back to the playlist. */
public enum class PlaylistSort {
    /** The playlist's editable playback order, initially the addition or imported file order. */
    CUSTOM,

    /** When each track joined the playlist (or was hearted), independent of manual reordering. */
    RECENT,
    TITLE,
    ARTIST,

    /** Album, then disc and track number within it, so an album plays through in its own order. */
    ALBUM,
    DURATION,
}

/** The direction a newly chosen field starts in: newest first, A–Z, shortest first. */
public val PlaylistSort.defaultDirection: SongSortDirection
    get() = when (this) {
        PlaylistSort.RECENT -> SongSortDirection.DESCENDING
        PlaylistSort.CUSTOM, PlaylistSort.TITLE, PlaylistSort.ARTIST, PlaylistSort.ALBUM,
        PlaylistSort.DURATION -> SongSortDirection.ASCENDING
    }

/** [PlaylistSort.CUSTOM] is the playlist's own order and runs one way only. */
public val PlaylistSort.hasDirection: Boolean
    get() = this != PlaylistSort.CUSTOM

/**
 * Orders a playlist's tracks for its page. Pure: the stored order goes in, the shown order comes out,
 * and nothing is written back, so a sort can always be taken back by choosing [PlaylistSort.CUSTOM].
 *
 * Every order is stable: tracks equal on the chosen field keep the stored order. Title and artist
 * order as the Tracks tab does ([SongSorting]): leading punctuation and an English article are
 * ignored, case is folded, and a missing name (or album, or duration) stays last in both directions.
 */
public object PlaylistSorting {

    /**
     * @param stored the tracks in the order the store keeps them.
     * @param storedNewestFirst whether that order already runs newest first: true for Favorites,
     *   where a heart puts its track on top; false for a playlist, which appends each addition.
     */
    public fun sort(
        stored: List<TrackDescriptor>,
        sort: PlaylistSort,
        direction: SongSortDirection = sort.defaultDirection,
        storedNewestFirst: Boolean = false,
    ): List<TrackDescriptor> = when (sort) {
        PlaylistSort.CUSTOM -> stored
        PlaylistSort.RECENT ->
            if (storedNewestFirst == (direction == SongSortDirection.DESCENDING)) stored else stored.asReversed().toList()
        PlaylistSort.TITLE -> SongSorting.sort(stored, SongSort.TITLE, direction)
        PlaylistSort.ARTIST -> SongSorting.sort(stored, SongSort.ARTIST, direction)
        PlaylistSort.ALBUM -> byAlbum(stored, direction)
        PlaylistSort.DURATION -> stored.sortedWith { left, right ->
            compareMissingLast(left.durationMs, right.durationMs, direction)
        }
    }

    /**
     * Albums run in [direction]; an album's own tracks always run as the album page lists them
     * ([LibraryCatalog.inAlbumOrder]: disc, then track number), the way the album plays. Albums are
     * told apart exactly as the Albums tab tells them apart ([LibraryCatalog.albumsOf]): one title by
     * one album artist (or, untagged, one artist) is one album, one artist's two releases of a title
     * part by folder or year, and same-named albums by different artists ("Greatest Hits") stay apart
     * unless they share their artwork, which keeps an untagged compilation whole. Only their order
     * shelves the title ("The Wall" beside "Wall"): grouping by that key would play two albums as one.
     * Tracks without an album come last, in the stored order.
     */
    private fun byAlbum(stored: List<TrackDescriptor>, direction: SongSortDirection): List<TrackDescriptor> {
        val (titled, untitled) = stored.partition(LibraryCatalog::hasAlbumTitle)
        val albums = LibraryCatalog.albumsOf(titled)
            .map { tracks ->
                val owner = tracks.map { it.albumOwnerKey() }.firstOrNull { it != MISSING_TEXT } ?: MISSING_TEXT
                KeyedAlbum(SongSorting.sortKey(tracks.first().album), owner, tracks)
            }
            .sortedWith { left, right ->
                compareText(left.title, right.title, direction).takeIf { it != 0 }
                    ?: compareText(left.owner, right.owner, direction)
            }
        return albums.flatMap { LibraryCatalog.inAlbumOrder(it.tracks) } + untitled
    }

    /** One album of the playlist, its keys computed once: [SongSorting.sortKey] allocates. */
    private class KeyedAlbum(
        val title: String,
        val owner: String,
        val tracks: List<TrackDescriptor>,
    )

    /** Whose album a track is on, as the catalog reads it: the album artist, else the artist. */
    private fun TrackDescriptor.albumOwnerKey(): String =
        SongSorting.sortKey(albumArtist?.takeIf { it.isNotBlank() } ?: artist)

    private fun compareText(left: String, right: String, direction: SongSortDirection): Int {
        val leftMissing = left == MISSING_TEXT
        val rightMissing = right == MISSING_TEXT
        return when {
            leftMissing && rightMissing -> 0
            leftMissing -> 1
            rightMissing -> -1
            direction == SongSortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }
    }

    private fun <T : Comparable<T>> compareMissingLast(left: T?, right: T?, direction: SongSortDirection): Int =
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            direction == SongSortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }

    /** What [SongSorting.sortKey] returns for a blank name. */
    private val MISSING_TEXT: String = SongSorting.sortKey(null)
}
