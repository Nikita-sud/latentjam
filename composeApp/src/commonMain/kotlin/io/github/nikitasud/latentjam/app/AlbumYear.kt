/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * The year an album card and the album page header show: [LibraryCatalog.releaseYear], the same
 * year the Year sort files the album under. A 2011 remaster of a 1973 album therefore reads "1973"
 * right where the sort puts it, and one bonus track tagged with a reissue year no longer hides the
 * year the rest agree on.
 *
 * Shown only when at least half of the tracks that carry a year agree on it. The sort has to put
 * every album somewhere, so on a compilation of songs from many years it falls back to the earliest
 * single vote; a 2005 best-of would then read "1971", which it is not, so it shows no year instead.
 * Null, and nothing shown, also when no track states a year.
 */
internal fun albumYearLabel(tracks: List<TrackDescriptor>): String? {
    val year = LibraryCatalog.releaseYear(tracks) ?: return null
    val years = tracks.mapNotNull { it.originalYear ?: it.year }
    return if (years.count { it == year } * 2 >= years.size) year.toString() else null
}
