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
     * Sorts a plain track list. Recency descending; the rest alphabetical.
     *
     * The alphabetical orders key each track ONCE up front rather than from inside the
     * comparator. [sortKey] allocates (it trims and lowercases), and a comparator runs
     * O(n log n) times — on a thousand-track library that is ~13k throwaway strings per sort,
     * paid on the main thread because the songs list keys its sections off this. Recency needs
     * no such treatment: its key is a field read.
     *
     * Both paths sort with the same comparator over the same keys as before, and Kotlin's sort
     * is stable, so the resulting order is byte-for-byte the one this always produced.
     */
    public fun sort(tracks: List<TrackDescriptor>, sort: SongSort): List<TrackDescriptor> = when (sort) {
        SongSort.TITLE -> tracks
            .map { KeyedTrack(it, sortKey(it.title)) }
            .sortedBy { it.primary }
            .map { it.track }

        SongSort.ARTIST -> tracks
            .map { KeyedTrack(it, sortKey(it.artist), sortKey(it.title)) }
            .sortedWith(compareBy({ it.primary }, { it.secondary }))
            .map { it.track }

        SongSort.RECENT -> tracks.sortedByDescending { it.addedAtMs ?: 0L }
    }

    /** A track carrying its precomputed sort keys, so the comparator only compares. */
    private class KeyedTrack(
        val track: TrackDescriptor,
        val primary: String,
        val secondary: String = "",
    )

    /**
     * Sorted tracks grouped into index buckets. [SongSort.RECENT] returns a
     * single unlabeled section — recency has no meaningful alphabet.
     */
    public fun sections(tracks: List<TrackDescriptor>, sort: SongSort): List<SongSection> {
        val sorted = sort(tracks, sort)
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
            .ifEmpty { "￿" }

    /** Index bucket: uppercase initial, "#" for digits, or "?" when no indexable name exists. */
    public fun bucket(value: String?): String {
        val first = value?.firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }
}
