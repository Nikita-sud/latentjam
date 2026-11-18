/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryScan
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Whether [scan] must leave the library on screen ([shown]) as it is.
 *
 * A source that gave no answer (Android's provider returned no cursor, or refused the query) reports no rows.
 * Published, that emptied the library on screen and SMART's with it, for music that is all still there. An
 * incomplete scan that WAS answered is published as before, empty or not: it is what can be seen now (the last
 * song deleted with a card out, or on iOS with the Music library on and holding nothing), and keeping the old
 * rows instead would show songs that are gone.
 */
internal fun keepsShownLibrary(scan: LibraryScan, shown: List<TrackDescriptor>?): Boolean =
    !scan.answered && scan.tracks.isEmpty() && !shown.isNullOrEmpty()
