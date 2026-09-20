/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * The catalogue already resolves individual credits, semicolons and casing. Follow its track
 * memberships instead of interpreting the display credit ("A feat. B") as one artist's name.
 * Track identity also works when a queued snapshot predates the catalogue's tag enrichment.
 */
internal fun artistDestinations(
    track: TrackDescriptor,
    catalog: LibraryCatalog?,
): List<ArtistGroup> = catalog?.artists.orEmpty().filter { group ->
    group.tracks.any { it.id == track.id }
}
