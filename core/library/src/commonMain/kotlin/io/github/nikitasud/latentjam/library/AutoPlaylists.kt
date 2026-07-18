/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** The kinds of playlist the app derives rather than the user curating. */
public enum class AutoPlaylistKind { RECENTLY_ADDED, MOST_PLAYED, RECENTLY_PLAYED }

/**
 * A playlist computed from library and listening data, never stored.
 *
 * Carries a [kind] and no title: the words belong to the UI layer, which turns
 * the kind into a heading in the reader's language.
 */
public data class AutoPlaylist(
    public val kind: AutoPlaylistKind,
    public val tracks: List<TrackDescriptor>,
)

/**
 * Derives the playlists the app offers without being asked.
 *
 * Play data arrives as plain maps so this stays independent of the history
 * module — and trivially testable. Empty ones are dropped: an auto playlist
 * with nothing in it is noise, not a feature.
 */
public object AutoPlaylists {

    private const val PLAYED_LIMIT = 100

    public fun build(
        tracks: List<TrackDescriptor>,
        playCounts: Map<TrackId, Int>,
        lastPlayedAtMs: Map<TrackId, Long>,
    ): List<AutoPlaylist> {
        val byId = tracks.associateBy { it.id }

        val recentlyAdded = tracks
            .filter { it.addedAtMs != null }
            .sortedByDescending { it.addedAtMs ?: 0L }

        val mostPlayed = playCounts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .mapNotNull { byId[it.key] }
            .take(PLAYED_LIMIT)

        val recentlyPlayed = lastPlayedAtMs.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .mapNotNull { byId[it.key] }
            .take(PLAYED_LIMIT)

        return listOf(
            AutoPlaylist(AutoPlaylistKind.RECENTLY_ADDED, recentlyAdded),
            AutoPlaylist(AutoPlaylistKind.MOST_PLAYED, mostPlayed),
            AutoPlaylist(AutoPlaylistKind.RECENTLY_PLAYED, recentlyPlayed),
        ).filter { it.tracks.isNotEmpty() }
    }
}
