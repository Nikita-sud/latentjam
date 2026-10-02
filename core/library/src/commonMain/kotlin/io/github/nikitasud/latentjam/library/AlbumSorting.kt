/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

/** How a list of albums is ordered — the Albums tab, and an artist's discography. */
public enum class AlbumSort {
    TITLE,
    ARTIST,

    /** Release year, per [LibraryCatalog.releaseYear]. */
    YEAR,

    /** When the album's most recently added track arrived. */
    RECENT,
}

/** The direction listeners expect when choosing a sort for the first time: A–Z, newest first. */
public val AlbumSort.defaultDirection: SongSortDirection
    get() = when (this) {
        AlbumSort.TITLE, AlbumSort.ARTIST -> SongSortDirection.ASCENDING
        AlbumSort.YEAR, AlbumSort.RECENT -> SongSortDirection.DESCENDING
    }

/** A run of albums sharing one index bucket (an initial letter), or the whole list unlabeled. */
public data class AlbumSection(
    public val bucket: String,
    public val albums: List<AlbumGroup>,
)

/**
 * Ordering and index-bucketing for album lists — the album counterpart of [SongSorting], and
 * keyed by the same [SongSorting.sortKey], so "The Wall" files under W here too.
 *
 * The order is total: albums equal on the chosen field fall back to title, then artist, then
 * [AlbumGroup.key], always A–Z whichever way the field runs. Reversing a sort therefore flips the
 * field only — one artist's albums stay in title order under Z–A — and the same library always
 * lays out the same way, whatever order the catalog handed it over in.
 */
public object AlbumSorting {

    public fun sort(
        albums: List<AlbumGroup>,
        sort: AlbumSort,
        direction: SongSortDirection = sort.defaultDirection,
    ): List<AlbumGroup> {
        // Keyed once up front: sortKey allocates, releaseYear walks every track, and a
        // comparator runs O(n log n) times.
        val keyed = albums.map { album ->
            KeyedAlbum(
                album = album,
                title = SongSorting.sortKey(album.title),
                artist = SongSorting.sortKey(album.artist),
                year = if (sort == AlbumSort.YEAR) LibraryCatalog.releaseYear(album.tracks) else null,
                added = if (sort == AlbumSort.RECENT) album.tracks.mapNotNull { it.addedAtMs }.maxOrNull() else null,
            )
        }
        val field: Comparator<KeyedAlbum> = when (sort) {
            AlbumSort.TITLE -> Comparator { left, right -> compareText(left.title, right.title, direction) }
            AlbumSort.ARTIST -> Comparator { left, right -> compareText(left.artist, right.artist, direction) }
            AlbumSort.YEAR -> Comparator { left, right -> compareMissingLast(left.year, right.year, direction) }
            AlbumSort.RECENT -> Comparator { left, right -> compareMissingLast(left.added, right.added, direction) }
        }
        return keyed.sortedWith(field.then(TIE_BREAK)).map { it.album }
    }

    /**
     * Sorted albums grouped into index buckets. Like [SongSorting.sections], only the alphabetical
     * sorts get letters; [AlbumSort.YEAR] and [AlbumSort.RECENT] return a single unlabeled section,
     * so their lists carry no A–Z rail.
     */
    public fun sections(
        albums: List<AlbumGroup>,
        sort: AlbumSort,
        direction: SongSortDirection = sort.defaultDirection,
    ): List<AlbumSection> {
        val sorted = sort(albums, sort, direction)
        val label: (AlbumGroup) -> String? = when (sort) {
            AlbumSort.TITLE -> { album -> album.title }
            AlbumSort.ARTIST -> { album -> album.artist }
            AlbumSort.YEAR, AlbumSort.RECENT ->
                return if (sorted.isEmpty()) emptyList() else listOf(AlbumSection("", sorted))
        }
        return sorted
            .groupBy { SongSorting.bucket(label(it)) }
            .map { (bucket, grouped) -> AlbumSection(bucket, grouped) }
    }

    private class KeyedAlbum(
        val album: AlbumGroup,
        val title: String,
        val artist: String,
        val year: Int?,
        val added: Long?,
    )

    /** Blank names stay last in both directions, as they do in [SongSorting]. */
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

    /** An undated album cannot be placed in either direction, so it goes last in both. */
    private fun <T : Comparable<T>> compareMissingLast(left: T?, right: T?, direction: SongSortDirection): Int =
        when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            direction == SongSortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }

    private val TIE_BREAK: Comparator<KeyedAlbum> =
        compareBy<KeyedAlbum>({ it.title }, { it.artist }, { it.album.key })

    /** What [SongSorting.sortKey] returns for a name with nothing to shelve it under. */
    private val MISSING_TEXT: String = SongSorting.sortKey(null)
}
