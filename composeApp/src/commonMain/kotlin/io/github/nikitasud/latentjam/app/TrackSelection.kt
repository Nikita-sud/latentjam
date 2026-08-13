/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Whether a browse group (album, artist, genre, folder) reads as selected: every one of its
 * tracks is in the selection. Each browse dimension partitions the library, so group checkboxes
 * built on this cannot disagree with each other or with the underlying track selection.
 */
internal fun Set<TrackId>.selectsAllOf(tracks: List<TrackDescriptor>): Boolean =
    tracks.isNotEmpty() && tracks.all { it.id in this }

/**
 * Toggles a whole browse group in the track selection: a fully selected group leaves the
 * selection, any other state completes it. Completing (rather than inverting per track) is what
 * a checkbox promises — tapping a half-selected album checks it.
 */
internal fun Set<TrackId>.toggleTracks(tracks: List<TrackDescriptor>): Set<TrackId> {
    val ids = tracks.mapTo(LinkedHashSet()) { it.id }
    return if (selectsAllOf(tracks)) this - ids else this + ids
}
