/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

internal const val M3U_MIME_TYPE: String = "audio/x-mpegurl"

/**
 * One line-pair of a parsed M3U: the path plus whatever the optional EXTINF row carried.
 * Everything is best-effort — the format has no guarantees, only conventions.
 */
internal data class M3uEntry(
    val path: String,
    val artist: String?,
    val title: String?,
    val durationSeconds: Int?,
)

/**
 * Encodes a playlist as extended M3U (`#EXTM3U`), UTF-8 by contract of the `.m3u8` extension.
 * Tracks with a known absolute path (Android's MediaStore keeps one) export portably; the rest
 * fall back to `folder/title`, which at least survives a round-trip through [matchM3uEntries].
 */
internal fun encodeM3u(
    name: String,
    tracks: List<TrackDescriptor>,
    paths: Map<TrackId, String>,
): String = buildString {
    append("#EXTM3U\n")
    append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
    for (track in tracks) {
        val seconds = track.durationMs?.let { (it / 1000).toInt() } ?: -1
        val label = listOfNotNull(track.artist, track.title).joinToString(" - ")
        append("#EXTINF:").append(seconds).append(',').append(label.replace('\n', ' '))
        append('\n')
        val path = paths[track.id]
            ?: listOfNotNull(track.folderPath, track.title).joinToString("/")
        append(path.replace('\n', ' ')).append('\n')
    }
}

/** The `#PLAYLIST:` name, when the file carries one. */
internal fun parseM3uName(text: String): String? = text.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.startsWith("#PLAYLIST:", ignoreCase = true) }
    ?.substringAfter(':')
    ?.trim()
    ?.ifEmpty { null }

/** Parses extended or plain M3U text into entries; comment lines it does not know are skipped. */
internal fun parseM3u(text: String): List<M3uEntry> {
    val entries = mutableListOf<M3uEntry>()
    var pendingArtist: String? = null
    var pendingTitle: String? = null
    var pendingDuration: Int? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                val body = line.substringAfter(':')
                val meta = body.substringAfter(',', missingDelimiterValue = "").trim()
                pendingDuration = body.substringBefore(',').trim().toIntOrNull()
                    ?.takeIf { it > 0 }
                // "Artist - Title" is convention, not contract; a label without the separator
                // is a title with no artist.
                val separator = meta.indexOf(" - ")
                if (separator > 0) {
                    pendingArtist = meta.take(separator).trim().ifEmpty { null }
                    pendingTitle = meta.drop(separator + 3).trim().ifEmpty { null }
                } else {
                    pendingArtist = null
                    pendingTitle = meta.ifEmpty { null }
                }
            }
            line.startsWith("#") -> Unit
            else -> {
                entries += M3uEntry(
                    path = line,
                    artist = pendingArtist,
                    title = pendingTitle,
                    durationSeconds = pendingDuration,
                )
                pendingArtist = null
                pendingTitle = null
                pendingDuration = null
            }
        }
    }
    return entries
}

/**
 * Resolves parsed entries against the library, one result per entry (null = no confident
 * match). File paths from another device rarely survive verbatim, so matching goes by what
 * does survive: the filename stem, then the EXTINF title, then "artist - title" as a stem.
 */
internal fun matchM3uEntries(
    entries: List<M3uEntry>,
    library: List<TrackDescriptor>,
): List<TrackDescriptor?> {
    fun norm(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    val byTitle = HashMap<String, TrackDescriptor>()
    val byArtistTitle = HashMap<String, TrackDescriptor>()
    for (track in library) {
        norm(track.title)?.let { if (it !in byTitle) byTitle[it] = track }
        val pair = listOfNotNull(norm(track.artist), norm(track.title))
        if (pair.size == 2) {
            val key = pair.joinToString(" - ")
            if (key !in byArtistTitle) byArtistTitle[key] = track
        }
    }

    return entries.map { entry ->
        val stem = norm(
            entry.path
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.'),
        )
        stem?.let { byTitle[it] ?: byArtistTitle[it] }
            ?: norm(entry.title)?.let { byTitle[it] }
            ?: listOfNotNull(norm(entry.artist), norm(entry.title))
                .takeIf { it.size == 2 }
                ?.let { byArtistTitle[it.joinToString(" - ")] }
    }
}
