/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class MediaBrowseSearchTest {
    @Test
    fun exactTitleBeatsEarlierAlbumAndGenreMatches() {
        val albumMatch = track("album", title = "Unrelated", album = "Midnight Drive")
        val genreMatch = track("genre", title = "Elsewhere", genre = "Midnight Drive")
        val titleMatch = track("title", title = "Midnight Drive")

        assertEquals(
            listOf("title", "album", "genre"),
            rankMediaSearch(listOf(albumMatch, genreMatch, titleMatch), "midnight drive")
                .map { it.id.value },
        )
    }

    @Test
    fun artistAndTitlePhraseBeatsPartialTitle() {
        val partial = track("partial", title = "David Bowie Heroes Collection")
        val requested = track("requested", title = "Heroes", artist = "David Bowie")

        assertEquals(
            "requested",
            rankMediaSearch(listOf(partial, requested), "David Bowie Heroes").first().id.value,
        )
    }

    @Test
    fun blankQueryReturnsNothingAndDuplicateIdsAppearOnce() {
        val first = track("same", title = "One")
        val duplicate = track("same", title = "One remaster")

        assertEquals(emptyList(), rankMediaSearch(listOf(first), "   \t"))
        assertEquals(1, rankMediaSearch(listOf(first, duplicate), "one").size)
    }

    @Test
    fun browsePagesValidateBoundsAndDoNotOverflow() {
        val items = listOf(0, 1, 2, 3, 4)
        assertEquals(listOf(2, 3), browsePage(items, page = 1, pageSize = 2))
        assertEquals(emptyList(), browsePage(items, page = Int.MAX_VALUE, pageSize = Int.MAX_VALUE))
        assertNull(browsePage(items, page = -1, pageSize = 2))
        assertNull(browsePage(items, page = 0, pageSize = 0))
    }

    private fun track(
        id: String,
        title: String,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        audioUri = "content://$id",
    )
}
