/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The named groups a region may take its name from, as [LibraryWorlds.namedAfterGroups] should
 * see them: the listener's playlists and the library's albums, each counted over the tracks that
 * actually exist.
 *
 * Built in one place so the app and the map replay over a device dump name regions identically.
 *
 * @property playlists the listener's playlists, reduced to tracks in the library; empty ones dropped.
 * @property albums albums with a title and at least two tracks in the library, reduced likewise.
 */
public class WorldNamingGroups private constructor(
    public val playlists: List<Pair<String, Set<TrackId>>>,
    public val albums: List<Pair<String, Set<TrackId>>>,
) {
    public companion object {

        /**
         * @param library ids of the tracks the library holds now. A playlist stores ids only, so it
         *   keeps entries for files that are gone: on a real device 592 of them, "Anime" listing
         *   459 tracks of which 161 existed. Counted as members they shrank every overlap
         *   ([LibraryWorlds.namedAfterGroups] ranks by Jaccard), so they are dropped here.
         * @param playlists name and stored track ids of each playlist, in the order to prefer them.
         * @param albums title and tracks of each album as the library groups them.
         */
        public fun of(
            library: Set<TrackId>,
            playlists: List<Pair<String, Collection<TrackId>>>,
            albums: List<Pair<String?, List<TrackDescriptor>>>,
        ): WorldNamingGroups = WorldNamingGroups(
            playlists = playlists.mapNotNull { (name, ids) ->
                val present = ids.filterTo(HashSet()) { it in library }
                if (name.isBlank() || present.isEmpty()) null else name to present
            },
            albums = albums.mapNotNull { (title, tracks) ->
                if (title.isNullOrBlank()) return@mapNotNull null
                val present = tracks.filter { it.id in library }.distinctBy { it.id }
                if (present.size < 2) return@mapNotNull null
                title to present.mapTo(HashSet()) { it.id }
            },
        )
    }
}
