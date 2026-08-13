/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** The kinds of playlist the app derives rather than the user curating. */
public enum class AutoPlaylistKind {
    FAVORITES,
    RECENTLY_ADDED,
    MOST_PLAYED,
    RECENTLY_PLAYED,
    NEVER_PLAYED,
    REDISCOVER,
}

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

    /** Plays that qualify an unhearted track as once-loved for [AutoPlaylistKind.REDISCOVER]. */
    private const val REDISCOVER_MIN_PLAYS = 3

    /** How long a once-loved track must rest before resurfacing counts as rediscovery. */
    private const val REDISCOVER_REST_MS = 45L * 24 * 60 * 60 * 1000

    public fun build(
        tracks: List<TrackDescriptor>,
        playCounts: Map<TrackId, Int>,
        lastPlayedAtMs: Map<TrackId, Long>,
        /** Hearted track ids in the order the store keeps them (newest first). */
        favorites: List<TrackId> = emptyList(),
        /** Current wall time; null omits the playlists that need an age judgment. */
        nowMs: Long? = null,
    ): List<AutoPlaylist> {
        val byId = tracks.associateBy { it.id }

        // The store's order IS the playlist's order; ids whose tracks left the device drop out.
        val hearted = favorites.mapNotNull { byId[it] }

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

        // An invitation, not an archive: newest unheard music first, so the list leads with the
        // tracks most likely added on purpose and never reached.
        val neverPlayed = tracks
            .filter { (playCounts[it.id] ?: 0) == 0 }
            .sortedByDescending { it.addedAtMs ?: Long.MIN_VALUE }

        // Once-loved (hearted, or played enough to prove it) and then genuinely rested. Loved
        // order outranks staleness so the list opens with the strongest memory, and a track the
        // listener never actually played cannot be "forgotten" no matter how long it sat.
        val rediscover = if (nowMs == null) {
            emptyList()
        } else {
            val heartedIds = favorites.toSet()
            tracks
                .filter { track ->
                    val lastPlayed = lastPlayedAtMs[track.id] ?: 0L
                    val plays = playCounts[track.id] ?: 0
                    val loved = track.id in heartedIds || plays >= REDISCOVER_MIN_PLAYS
                    loved && lastPlayed > 0 && nowMs - lastPlayed >= REDISCOVER_REST_MS
                }
                .sortedWith(
                    compareByDescending<TrackDescriptor> { playCounts[it.id] ?: 0 }
                        .thenBy { lastPlayedAtMs[it.id] ?: 0L },
                )
                .take(PLAYED_LIMIT)
        }

        return listOf(
            AutoPlaylist(AutoPlaylistKind.FAVORITES, hearted),
            AutoPlaylist(AutoPlaylistKind.RECENTLY_ADDED, recentlyAdded),
            AutoPlaylist(AutoPlaylistKind.MOST_PLAYED, mostPlayed),
            AutoPlaylist(AutoPlaylistKind.RECENTLY_PLAYED, recentlyPlayed),
            AutoPlaylist(AutoPlaylistKind.NEVER_PLAYED, neverPlayed),
            AutoPlaylist(AutoPlaylistKind.REDISCOVER, rediscover),
        ).filter { it.tracks.isNotEmpty() }
    }
}
