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

    /** Date added: when the file joined the library ([TrackDescriptor.addedAtMs]). Persisted as "recent". */
    RECENT,

    /** Date modified: when the file last changed ([TrackDescriptor.modifiedAtMs]). */
    MODIFIED,
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
        SongSort.RECENT, SongSort.MODIFIED -> SongSortDirection.DESCENDING
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
 * "(I Just) Died in Your Arms" files under I rather than "(". So is a leading English article:
 * "The Battle of Los Angeles" files under B and "A Perfect Circle" under P, the way record shops
 * and most players shelve them.
 */
public object SongSorting {

    /**
     * Sorts a plain track list. Newly selected recency defaults to newest first;
     * the alphabetical sorts default to A–Z. [direction] can reverse either order.
     *
     * The alphabetical orders key each track ONCE up front rather than from inside the
     * comparator. [sortKey] allocates (it trims and lowercases), and a comparator runs
     * O(n log n) times — on a thousand-track library that is ~13k throwaway strings per sort,
     * paid on the main thread because the songs list keys its sections off this. The date
     * orders key their title tiebreak the same way.
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

        SongSort.RECENT -> byDate(tracks, direction) { it.addedAtMs }
        SongSort.MODIFIED -> byDate(tracks, direction) { it.modifiedAtMs }
    }

    /**
     * [tracks] by the date [dateOf] reads, newest or oldest first. A track whose date is unknown follows every dated one
     * in both directions; tracks of one date, and the undated ones, follow each other A–Z by title (as [SongSort.TITLE]
     * keys it), and then keep the order they came in.
     */
    public fun byDate(
        tracks: List<TrackDescriptor>,
        direction: SongSortDirection,
        dateOf: (TrackDescriptor) -> Long?,
    ): List<TrackDescriptor> = tracks
        .map { DatedTrack(it, dateOf(it), sortKey(it.title)) }
        .sortedWith(datedTrackComparator(direction))
        .map { it.track }

    /** A comparator for [byDate]'s order, for callers that order tracks inside a larger order (a folder's). */
    public fun dateComparator(
        direction: SongSortDirection,
        dateOf: (TrackDescriptor) -> Long?,
    ): Comparator<TrackDescriptor> {
        val dates = datedTrackComparator(direction)
        return Comparator { left, right ->
            dates.compare(DatedTrack(left, dateOf(left), sortKey(left.title)), DatedTrack(right, dateOf(right), sortKey(right.title)))
        }
    }

    private class DatedTrack(val track: TrackDescriptor, val date: Long?, val title: String)

    private fun datedTrackComparator(direction: SongSortDirection): Comparator<DatedTrack> = Comparator { left, right ->
        val l = left.date
        val r = right.date
        val byDate = when {
            l == null && r == null -> 0
            l == null -> 1
            r == null -> -1
            direction == SongSortDirection.ASCENDING -> l.compareTo(r)
            else -> r.compareTo(l)
        }
        if (byDate != 0) byDate else compareSortKeys(left.title, right.title, SongSortDirection.ASCENDING)
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
     * Sorted tracks grouped into index buckets. The date sorts return a
     * single unlabeled section — a date order has no meaningful alphabet.
     */
    public fun sections(
        tracks: List<TrackDescriptor>,
        sort: SongSort,
        direction: SongSortDirection = sort.defaultDirection,
    ): List<SongSection> {
        val sorted = sort(tracks, sort, direction)
        if (sort == SongSort.RECENT || sort == SongSort.MODIFIED) {
            return if (sorted.isEmpty()) emptyList() else listOf(SongSection("", sorted))
        }
        val label: (TrackDescriptor) -> String? =
            if (sort == SongSort.ARTIST) ({ it.artist }) else ({ it.title })
        return sorted
            .groupBy { bucket(label(it)) }
            .map { (bucket, grouped) -> SongSection(bucket, grouped) }
    }

    /** Case-folded key with leading punctuation and article stripped; blanks sort last. */
    public fun sortKey(value: String?): String =
        shelvedName(value)
            .lowercase()
            .ifEmpty { LAST_SORT_KEY }

    /** Index bucket: uppercase initial, "#" for digits, or "?" when no indexable name exists. */
    public fun bucket(value: String?): String {
        val first = shelvedName(value).firstOrNull() ?: return "?"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    /**
     * The part of a name it is shelved under: leading punctuation and one leading article
     * removed. The article must be a whole word followed by more name — "The The" still files
     * under T, "A-ha" under A, and a title that IS an article keeps it.
     */
    internal fun shelvedName(value: String?): String {
        val name = (value ?: "").trimStart { !it.isLetterOrDigit() }
        for (article in ARTICLES) {
            if (name.length <= article.length) continue
            if (!name.startsWith(article, ignoreCase = true)) continue
            if (!name[article.length].isWhitespace()) continue
            val rest = name.substring(article.length).trimStart { !it.isLetterOrDigit() }
            if (rest.isNotEmpty()) return rest
        }
        return name
    }

    /** English only: other languages' articles double as ordinary words too often to strip. */
    private val ARTICLES = listOf("the", "an", "a")

    private const val LAST_SORT_KEY: String = "￿"
}
