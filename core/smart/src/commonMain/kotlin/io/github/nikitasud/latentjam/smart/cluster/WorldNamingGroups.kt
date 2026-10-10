/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * The named groups a region may take its name from, as [LibraryWorlds.namedAfterGroups] should
 * see them: the listener's playlists and the library's real albums, each counted over the tracks
 * that actually exist.
 *
 * Built in one place so the app and the map replay over a device dump name regions identically.
 *
 * @property playlists the listener's playlists, reduced to tracks in the library; empty ones dropped.
 * @property albums multi-track albums that look like albums ([looksLikeAlbum]), reduced likewise.
 */
public class WorldNamingGroups private constructor(
    public val playlists: List<Pair<String, Set<TrackId>>>,
    public val albums: List<Pair<String, Set<TrackId>>>,
) {
    public companion object {

        /**
         * The share of an album's tracks one artist must account for before the album may name a
         * region. Half lets a soundtrack by one composer or an album with guest-heavy tracks
         * through, and stops a placeholder: on a real library 65 tracks by dozens of artists
         * carried album "Music" (a downloader's filler, or MediaStore's folder name for a file with
         * no album tag) and named a region of meme clips after it.
         */
        public const val ALBUM_ARTIST_SHARE: Double = 0.5

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
                if (present.size < 2 || !looksLikeAlbum(present)) return@mapNotNull null
                title to present.mapTo(HashSet()) { it.id }
            },
        )

        /**
         * Whether [tracks] read as one artist's release: some artist — the album artist or a
         * track's own artist, a featured guest aside — is credited on at least
         * [ALBUM_ARTIST_SHARE] of them. A compilation credited to "Various Artists" and a bucket of
         * unrelated songs that share a placeholder title both fail; no title list is involved,
         * because the same title can be real ("Music" is also a Madonna album).
         */
        public fun looksLikeAlbum(tracks: List<TrackDescriptor>): Boolean {
            if (tracks.isEmpty()) return false
            val credited = HashMap<String, Int>()
            for (track in tracks) {
                for (credit in track.albumCredits()) credited[credit] = (credited[credit] ?: 0) + 1
            }
            val best = credited.values.maxOrNull() ?: return false
            return best >= tracks.size * ALBUM_ARTIST_SHARE
        }

        /** Each artist a track credits once: album artist, artist, its lead credit and tag list. */
        private fun TrackDescriptor.albumCredits(): Set<String> {
            val credits = LinkedHashSet<String>()
            fun add(name: String?) {
                name.creditKey()?.let(credits::add)
            }
            add(albumArtist)
            artist?.let { display ->
                add(display)
                for (part in display.split(';')) add(part.split(FEATURING, limit = 2).first())
            }
            artists.forEach(::add)
            return credits
        }

        private fun String?.creditKey(): String? = this
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.lowercase()
            ?.takeUnless { it.isEmpty() || it in PLACEHOLDER_ARTISTS }

        private val WHITESPACE = Regex("\\s+")

        /** "A feat. B", "A (ft. B)", "A featuring B": the part before it is the lead credit. */
        private val FEATURING = Regex("\\s+[(\\[]?(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

        /** Credits that name nobody: they cannot make a bucket of songs one artist's album. */
        private val PLACEHOLDER_ARTISTS = setOf(
            "unknown",
            "<unknown>",
            "[unknown]",
            "unknown artist",
            "various",
            "various artists",
            "va",
        )
    }
}
