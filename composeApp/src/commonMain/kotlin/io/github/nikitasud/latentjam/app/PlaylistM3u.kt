/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

internal const val M3U_MIME_TYPE: String = "audio/x-mpegurl"

/**
 * Lossless local fallback for a track that has neither a usable media locator nor enough metadata
 * to write a filename-like M3U row. Other players may ignore this private URI, but LatentJam can
 * resolve it back to the exact opaque [TrackId] instead of silently dropping the playlist entry.
 */
private const val LATENTJAM_TRACK_ID_PREFIX = "latentjam:track-id:"

/**
 * Private comment paired with a real path when that row has no title to match on import.
 * Standards-compliant players ignore the comment and keep using the path; LatentJam can still
 * recover the exact opaque id on the same device.
 */
private const val LATENTJAM_TRACK_ID_HINT_PREFIX = "#LATENTJAM-TRACK-ID:"

/** Keeps an untrusted M3U from expanding into an unbounded in-memory entry graph. */
internal const val MAX_M3U_ENTRIES: Int = 100_000

/**
 * One line-pair of a parsed M3U: the path plus whatever the optional EXTINF row carried.
 * Everything is best-effort — the format has no guarantees, only conventions.
 */
internal data class M3uEntry(
    val path: String,
    val artist: String?,
    val title: String?,
    val durationSeconds: Int?,
    val localTrackIdHint: TrackId? = null,
)

/**
 * Encodes a playlist as extended M3U (`#EXTM3U`), UTF-8 by contract of the `.m3u8` extension.
 * Tracks with a known absolute path (Android's MediaStore keeps one) export portably. Otherwise a
 * playable URI, `folder/title`, or a private lossless id locator keeps every entry representable.
 *
 * With [relativeTo] — the folder the playlist file is saved in — a real path is written from
 * that folder instead (`../Unsorted/track.mp3`), so a computer that mounts the same music under
 * another root still finds it. A path [relativeM3uPath] cannot express that way stays absolute.
 */
internal fun encodeM3u(
    name: String,
    tracks: List<TrackDescriptor>,
    paths: Map<TrackId, String>,
    relativeTo: String? = null,
): String = buildString {
    append("#EXTM3U\n")
    append("#PLAYLIST:").append(name.toM3uLine()).append('\n')
    for (track in tracks) {
        val seconds = track.durationMs?.let { (it / 1000).toInt() } ?: -1
        val label = listOfNotNull(track.artist, track.title).joinToString(" - ")
        append("#EXTINF:").append(seconds).append(',').append(label.toM3uLine())
        append('\n')
        val title = track.title?.takeIf(String::isM3uLineSafe)
        val metadataLocator = title?.let {
            listOfNotNull(track.folderPath?.takeIf(String::isM3uLineSafe), title).joinToString("/")
        }
        val realPath = paths[track.id]?.takeIf(String::isM3uLineSafe)?.let { path ->
            relativeTo?.let { relativeM3uPath(it, path) }?.anchoredM3uLine() ?: path
        }
        // A real path is the most useful M3U locator for other players, but LatentJam descriptors
        // intentionally do not expose those paths during matching. Without a safe title there is
        // therefore no metadata fallback that can recover this row on re-import. Preserve an
        // exact same-device identity as an ignorable comment while leaving the real path intact.
        if (realPath != null && title == null) {
            append(LATENTJAM_TRACK_ID_HINT_PREFIX)
                .append(track.id.value.encodeHex())
                .append('\n')
        }
        val locator = realPath
            ?: track.audioUri?.takeIf(String::isM3uLineSafe)
            ?: metadataLocator
            ?: LATENTJAM_TRACK_ID_PREFIX + track.id.value.encodeHex()
        append(locator).append('\n')
    }
}

/**
 * Two paths that share fewer leading folders than this are on different volumes as far as a
 * playlist is concerned: `/storage/emulated/0/…` against an SD card's `/storage/1A2B-3C4D/…`.
 */
private const val MIN_SHARED_PATH_SEGMENTS = 2

/**
 * [target] as seen from [playlistDirectory] — `../Unsorted/track.mp3` from `Music/Playlists`,
 * `track.mp3` from the track's own folder — or null when the caller should keep the absolute
 * path. Both must be absolute POSIX paths; repeated slashes, `.` and `..` are resolved first.
 *
 * Paths on different volumes get null rather than a `../../..` climb over the volume root: that
 * climb only resolves on this phone, and other devices are the reason for a relative playlist.
 * Names are kept raw, as the absolute rows are; an M3U line is not a URL.
 */
internal fun relativeM3uPath(playlistDirectory: String, target: String): String? {
    val directory = playlistDirectory.absolutePathSegments() ?: return null
    val file = target.absolutePathSegments() ?: return null
    val shared = directory.zip(file).takeWhile { (inDirectory, inFile) -> inDirectory == inFile }.size
    // A target that is the folder itself, or one of its parents, names no file below it.
    if (shared < MIN_SHARED_PATH_SEGMENTS || shared == file.size) return null
    return (List(directory.size - shared) { ".." } + file.drop(shared)).joinToString("/")
}

/** The `.` and `..` resolved names of an absolute path, or null for a relative one. */
private fun String.absolutePathSegments(): List<String>? {
    if (!startsWith('/')) return null
    val segments = mutableListOf<String>()
    for (segment in split('/')) {
        when (segment) {
            "", "." -> Unit
            // `/..` is the root itself, so a climb past it stays there.
            ".." -> segments.removeLastOrNull()
            else -> segments += segment
        }
    }
    return segments
}

/** The `#PLAYLIST:` name, when the file carries one. */
internal fun parseM3uName(text: String): String? = text.withoutBom().lineSequence()
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
    var pendingLocalTrackIdHint: TrackId? = null
    for (raw in text.withoutBom().lineSequence()) {
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
            line.startsWith(LATENTJAM_TRACK_ID_HINT_PREFIX, ignoreCase = true) -> {
                // A malformed later hint clears an earlier one instead of accidentally lending
                // stale identity to the next locator row.
                pendingLocalTrackIdHint = line
                    .drop(LATENTJAM_TRACK_ID_HINT_PREFIX.length)
                    .decodeHex()
                    ?.let(::TrackId)
            }
            line.startsWith("#") -> Unit
            else -> {
                // Fail the whole parse rather than returning a plausible-looking truncated list.
                if (entries.size == MAX_M3U_ENTRIES) return emptyList()
                entries += M3uEntry(
                    path = line,
                    artist = pendingArtist,
                    title = pendingTitle,
                    durationSeconds = pendingDuration,
                    localTrackIdHint = pendingLocalTrackIdHint,
                )
                pendingArtist = null
                pendingTitle = null
                pendingDuration = null
                pendingLocalTrackIdHint = null
            }
        }
    }
    return entries
}

/**
 * Resolves parsed entries against the library, one result per entry (null = no confident
 * match). File paths from another device rarely survive verbatim, so matching first honours exact
 * local identity/URI and EXTINF artist+title, then falls back to unique filename/title matches.
 */
internal fun matchM3uEntries(
    entries: List<M3uEntry>,
    library: List<TrackDescriptor>,
): List<TrackDescriptor?> {
    fun norm(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    val byId = library.groupBy { it.id }
    val byLocator = library
        .mapNotNull { track ->
            track.audioUri?.trim()?.takeIf(String::isNotEmpty)?.let { it to track }
        }
        .groupBy(keySelector = { it.first }) { it.second }
    val byTitle = library.mapNotNull { track -> norm(track.title)?.let { it to track } }
        .groupBy(keySelector = { it.first }) { it.second }
    val byArtistTitle = library.mapNotNull { track ->
        val artist = norm(track.artist) ?: return@mapNotNull null
        val title = norm(track.title) ?: return@mapNotNull null
        (artist to title) to track
    }.groupBy(keySelector = { it.first }) { it.second }

    /**
     * Never pick whichever duplicate happened to arrive first. A unique identity wins directly;
     * otherwise an exact whole-second duration may resolve the ambiguity left by identical tags.
     */
    fun resolve(candidates: List<TrackDescriptor>?, durationSeconds: Int?): TrackDescriptor? {
        val distinct = candidates.orEmpty().distinctBy { it.id }
        if (distinct.size == 1) return distinct.single()
        if (durationSeconds == null) return null
        return distinct.filter { candidate ->
            candidate.durationMs?.div(1_000L)?.toInt() == durationSeconds
        }.singleOrNull()
    }

    return entries.map { entry ->
        entry.localTrackIdHint?.let { hintedId ->
            byId[hintedId]?.let { candidates ->
                return@map resolve(candidates, entry.durationSeconds)
            }
        }
        val localId = entry.path
            .takeIf { it.startsWith(LATENTJAM_TRACK_ID_PREFIX, ignoreCase = true) }
            ?.drop(LATENTJAM_TRACK_ID_PREFIX.length)
            ?.decodeHex()
            ?.let(::TrackId)
        if (localId != null) {
            return@map resolve(byId[localId], entry.durationSeconds)
        }

        // An exact URI is identity-like and stronger than filename or tag heuristics.
        byLocator[entry.path]?.let { candidates ->
            return@map resolve(candidates, entry.durationSeconds)
        }

        // EXTINF supplied both fields explicitly. Honour that exact pair before considering the
        // filename: a library can legitimately contain many songs called "Intro".
        val entryArtist = norm(entry.artist)
        val entryTitle = norm(entry.title)
        if (entryArtist != null && entryTitle != null) {
            byArtistTitle[entryArtist to entryTitle]?.let { candidates ->
                return@map resolve(candidates, entry.durationSeconds)
            }
        }

        val stem = norm(
            entry.path
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.'),
        )
        val stemPair = stem?.let { value ->
            val separator = value.indexOf(" - ")
            if (separator > 0) {
                value.take(separator).trim() to value.drop(separator + 3).trim()
            } else {
                null
            }
        }
        stem?.let(byTitle::get)?.let { candidates ->
            return@map resolve(candidates, entry.durationSeconds)
        }
        stemPair?.let(byArtistTitle::get)?.let { candidates ->
            return@map resolve(candidates, entry.durationSeconds)
        }
        entryTitle?.let(byTitle::get)?.let { candidates ->
            return@map resolve(candidates, entry.durationSeconds)
        }
        null
    }
}

private fun String.encodeHex(): String = encodeToByteArray().joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

/**
 * Drops the UTF-8 byte order mark (U+FEFF) from the head of the text.
 *
 * The mark is not whitespace on any platform this ships on, so `trim()` keeps it and the first
 * line stops looking like what it is: `#EXTM3U` behind a BOM is not read as a comment but as a
 * locator, which turned a real header into a phantom entry, and a plain M3U lost its first track
 * to a path that matches nothing. Editors on Windows write the mark, and by definition it can only
 * stand at the head of the text, so that is where it is removed.
 */
private fun String.withoutBom(): String = trimStart('\uFEFF')

private fun String.isM3uLineSafe(): Boolean =
    isNotBlank() && '\n' !in this && '\r' !in this && !trimStart().startsWith('#')

private fun String.toM3uLine(): String = replace('\r', ' ').replace('\n', ' ')

/**
 * Lines are read back trimmed, and one that opens with `#` is a comment, so a relative path whose
 * first name starts with either is anchored as `./#1 Hit.mp3`. Absolute paths open with `/`.
 */
private fun String.anchoredM3uLine(): String =
    if (first().isWhitespace() || first() == '#') "./$this" else this

private fun String.decodeHex(): String? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
}
