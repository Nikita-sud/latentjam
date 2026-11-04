/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * How the Folders tab orders what it shows. [NAME] orders the folders by name, in either direction, and each folder's
 * tracks in folder order ([LibraryCatalog.FOLDER_TRACK_ORDER]). The date orders keep the folders A–Z, so the tree reads
 * the same way down, and order each folder's own tracks by a file date: newest or oldest first.
 */
public enum class FolderSort {
    NAME,

    /** Date added ([TrackDescriptor.addedAtMs]). */
    RECENT,

    /** Date modified ([TrackDescriptor.modifiedAtMs]). */
    MODIFIED,
}

/** A–Z for names, newest first for dates. */
public val FolderSort.defaultDirection: SongSortDirection
    get() = when (this) {
        FolderSort.NAME -> SongSortDirection.ASCENDING
        FolderSort.RECENT, FolderSort.MODIFIED -> SongSortDirection.DESCENDING
    }

/**
 * The order of one folder's tracks under [this] in [direction]. A date order puts the undated tracks after the dated
 * ones in both directions; tracks of one date, and the undated ones, keep folder order between them.
 */
public fun FolderSort.trackOrder(direction: SongSortDirection): Comparator<TrackDescriptor> = when (this) {
    FolderSort.NAME -> LibraryCatalog.FOLDER_TRACK_ORDER
    FolderSort.RECENT -> byDate(direction) { it.addedAtMs }
    FolderSort.MODIFIED -> byDate(direction) { it.modifiedAtMs }
}

private fun byDate(direction: SongSortDirection, dateOf: (TrackDescriptor) -> Long?): Comparator<TrackDescriptor> =
    Comparator { left, right ->
        val l = dateOf(left)
        val r = dateOf(right)
        val byDate = when {
            l == null && r == null -> 0
            l == null -> 1
            r == null -> -1
            direction == SongSortDirection.ASCENDING -> l.compareTo(r)
            else -> r.compareTo(l)
        }
        if (byDate != 0) byDate else LibraryCatalog.FOLDER_TRACK_ORDER.compare(left, right)
    }
