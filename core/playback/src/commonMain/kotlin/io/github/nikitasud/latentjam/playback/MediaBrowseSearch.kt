/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** Validates and slices Media3's zero-based browse pages without overflowing Int arithmetic. */
internal fun <T> browsePage(items: List<T>, page: Int, pageSize: Int): List<T>? {
    if (page < 0 || pageSize <= 0) return null
    val from = page.toLong() * pageSize.toLong()
    if (from >= items.size) return emptyList()
    val start = from.toInt()
    val end = (from + pageSize).coerceAtMost(items.size.toLong()).toInt()
    return items.subList(start, end)
}

/**
 * Deterministic voice-search ranking shared by search results and Play-from-search resolution.
 * A title request must not lose to an earlier library row whose genre merely contains the words.
 */
internal fun rankMediaSearch(
    tracks: List<TrackDescriptor>,
    query: String,
): List<TrackDescriptor> {
    val needle = normalizedSearchText(query)
    if (needle.isEmpty()) return emptyList()
    return tracks
        .distinctBy { it.id }
        .mapIndexedNotNull { index, track ->
            mediaSearchRank(track, needle)?.let { rank -> RankedTrack(track, rank, index) }
        }
        .sortedWith(compareBy<RankedTrack>({ it.rank }, { it.inputIndex }))
        .map(RankedTrack::track)
}

private data class RankedTrack(
    val track: TrackDescriptor,
    val rank: Int,
    val inputIndex: Int,
)

private fun mediaSearchRank(track: TrackDescriptor, needle: String): Int? {
    val title = normalizedSearchText(track.title)
    val artist = normalizedSearchText(track.artist)
    val album = normalizedSearchText(track.album)
    val genre = normalizedSearchText(track.genre)
    return when {
        title == needle -> 0
        artist.isNotEmpty() && title.isNotEmpty() &&
            ("$artist $title" == needle || "$title $artist" == needle) -> 1
        title.startsWith(needle) -> 2
        needle in title -> 3
        artist == needle -> 4
        artist.startsWith(needle) -> 5
        needle in artist -> 6
        album == needle -> 7
        album.startsWith(needle) -> 8
        needle in album -> 9
        genre == needle -> 10
        genre.startsWith(needle) -> 11
        needle in genre -> 12
        else -> null
    }
}

private fun normalizedSearchText(value: String?): String = value
    ?.trim()
    ?.lowercase()
    ?.split(MediaSearchWhitespace)
    ?.filter(String::isNotEmpty)
    ?.joinToString(" ")
    .orEmpty()

private val MediaSearchWhitespace = Regex("\\s+")
