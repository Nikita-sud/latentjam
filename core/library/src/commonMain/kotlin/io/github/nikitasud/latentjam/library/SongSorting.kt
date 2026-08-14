/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** How the songs list is ordered. */
public enum class SongSort {
    TITLE,
    ARTIST,
    RECENT,
}

/** Direction applied to the selected [SongSort]. */
public enum class SongSortDirection {
    ASCENDING,
    DESCENDING;

    public fun toggled(): SongSortDirection = when (this) {
        ASCENDING -> DESCENDING
        DESCENDING -> ASCENDING
    }
}

/** The direction listeners expect when choosing a sort for the first time. */
public val SongSort.defaultDirection: SongSortDirection
    get() = when (this) {
        SongSort.TITLE, SongSort.ARTIST -> SongSortDirection.ASCENDING
        SongSort.RECENT -> SongSortDirection.DESCENDING
    }

/** A run of tracks sharing one index bucket (initial letter, or a date band). */
public data class SongSection(
    public val bucket: String,
    public val tracks: List<TrackDescriptor>,
)

/**
 * Ordering and index-bucketing for the songs list — pure, testable, and
 * shared by the list, its sticky headers, and its A–Z rail.
 *
 * Leading punctuation is ignored when sorting and bucketing, so
 * "(I Just) Died in Your Arms" files under I rather than "(".
 */
public object SongSorting {

    /**
     * Sorts a plain track list. Newly selected recency defaults to newest first;
     * the alphabetical sorts default to A–Z. [direction] can reverse either order.
     *
     * The alphabetical orders key each track ONCE up front rather than from inside the
     * comparator. [sortKey] allocates (it trims and lowercases), and a comparator runs
     * O(n log n) times — on a thousand-track library that is ~13k throwaway strings per sort,
     * paid on the main thread because the songs list keys its sections off this. Recency needs
     * no such treatment: its key is a field read.
     *
     * Kotlin's sort is stable, so tracks whose selected metadata is equal keep their source order.
     */
    public fun sort(
        tracks: List<TrackDescriptor>,
        sort: SongSort,
        direction: SongSortDirection = sort.defaultDirection,
    ): List<TrackDescriptor> = when (sort) {
        SongSort.TITLE -> tracks
            .map { KeyedTrack(it, sortKey(it.title)) }
            .sortedWith(keyedTrackComparator(direction))
            .map { it.track }

        SongSort.ARTIST -> tracks
            .map { KeyedTrack(it, sortKey(it.artist), sortKey(it.title)) }
            .sortedWith(keyedTrackComparator(direction, compareSecondary = true))
            .map { it.track }

        SongSort.RECENT -> tracks.sortedWith(recentTrackComparator(direction))
    }

    /** A track carrying its precomputed sort keys, so the comparator only compares. */
    private class KeyedTrack(
        val track: TrackDescriptor,
        val primary: String,
        val secondary: String = "",
    )

    /**
     * Missing metadata stays last in both directions. Simply reversing the ascending list would
     * put blank titles/artists above real names, which makes Z–A useful only for pristine tags.
     */
    private fun keyedTrackComparator(
        direction: SongSortDirection,
        compareSecondary: Boolean = false,
    ): Comparator<KeyedTrack> = Comparator { left, right ->
        val primary = compareSortKeys(left.primary, right.primary, direction)
        if (primary != 0) {
            primary
        } else if (compareSecondary) {
            compareSortKeys(left.secondary, right.secondary, direction)
        } else {
            0
        }
    }

    private fun recentTrackComparator(
        direction: SongSortDirection,
    ): Comparator<TrackDescriptor> = Comparator { left, right ->
        val leftAdded = left.addedAtMs
        val rightAdded = right.addedAtMs
        when {
            leftAdded == null && rightAdded == null -> 0
            leftAdded == null -> 1
            rightAdded == null -> -1
            direction == SongSortDirection.ASCENDING -> leftAdded.compareTo(rightAdded)
            else -> rightAdded.compareTo(leftAdded)
        }
    }

    private fun compareSortKeys(
        left: String,
        right: String,
        direction: SongSortDirection,
    ): Int {
        val leftMissing = left == LAST_SORT_KEY
        val rightMissing = right == LAST_SORT_KEY
        return when {
            leftMissing && rightMissing -> 0
            leftMissing -> 1
            rightMissing -> -1
            direction == SongSortDirection.ASCENDING -> left.compareTo(right)
            else -> right.compareTo(left)
        }
    }

    /**
     * Sorted tracks grouped into index buckets. [SongSort.RECENT] returns a
     * single unlabeled section — recency has no meaningful alphabet.
     */
    public fun sections(
        tracks: List<TrackDescriptor>,
        sort: SongSort,
        direction: SongSortDirection = sort.defaultDirection,
    ): List<SongSection> {
        val sorted = sort(tracks, sort, direction)
        if (sort == SongSort.RECENT) {
            return if (sorted.isEmpty()) emptyList() else listOf(SongSection("", sorted))
        }
        val label: (TrackDescriptor) -> String? =
            if (sort == SongSort.ARTIST) ({ it.artist }) else ({ it.title })
        return sorted
            .groupBy { bucket(label(it)) }
            .map { (bucket, grouped) -> SongSection(bucket, grouped) }
    }

    /** Case-folded key with leading punctuation stripped; blanks sort last. */
    public fun sortKey(value: String?): String =
        (value ?: "")
            .trimStart { !it.isLetterOrDigit() }
            .lowercase()
            .ifEmpty { LAST_SORT_KEY }

    /** Index bucket: uppercase initial, "#" for digits, or "?" when no indexable name exists. */
    public fun bucket(value: String?): String {
        val first = value?.firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    private const val LAST_SORT_KEY: String = "￿"
}
