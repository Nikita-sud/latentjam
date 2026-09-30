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
 * year the rest agree on. Null, and nothing shown, when no track states a year.
 */
internal fun albumYearLabel(tracks: List<TrackDescriptor>): String? =
    LibraryCatalog.releaseYear(tracks)?.toString()
