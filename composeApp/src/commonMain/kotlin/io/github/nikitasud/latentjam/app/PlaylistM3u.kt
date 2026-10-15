/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

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
 * Whether an exported playlist can name its tracks relative to its own folder: the platform has
 * to know both the tracks' file paths and the folder the export picker saved to.
 */
internal expect val relativePlaylistPathsAvailable: Boolean

/**
 * [text] in Unicode canonical composition (NFC). macOS hands out file names decomposed (NFD: `é`
 * as `e` + U+0301), while Android's MediaStore and most tag editors keep them composed (U+00E9):
 * the same name, spelled with other code points.
 */
internal expect fun nfcNormalized(text: String): String

/**
 * This text composed, as [nfcNormalized] does it. Text made only of code units below U+0300, the
 * first combining mark, is composed already, so ASCII and Latin-1 skip the platform call.
 */
private fun String.composed(): String = if (all { it < '\u0300' }) this else nfcNormalized(this)

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
 * "Export as M3U" for a list of tracks in the order given: a playlist, or Favorites newest first.
 * The platform's document picker chooses where the file goes, and the file is encoded once it has:
 * with [relativePaths] (where [relativePlaylistPathsAvailable]) a real path is written from the
 * folder the file lands in. A library that cannot name the files leaves metadata rows instead.
 */
internal suspend fun exportTracksAsM3u(
    name: String,
    tracks: List<TrackDescriptor>,
    library: MusicLibrary,
    relativePaths: Boolean,
    exchange: LocalBackupFileExchange,
) {
    val paths = try {
        library.filePaths(tracks.map { it.id })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
    val safeName = name
        .trim()
        .replace('/', '-')
        .replace('\\', '-')
        .take(120)
        .ifEmpty { "playlist" }
    val relative = relativePlaylistPathsAvailable && relativePaths
    // Encoded off the main thread, once the picker has said where the file goes: a relative path
    // is only meaningful from the folder the playlist ends up in.
    exchange.exportTo(
        { destinationDirectory ->
            encodeM3u(
                name = name,
                tracks = tracks,
                paths = paths,
                relativeTo = destinationDirectory.takeIf { relative },
            )
        },
        "$safeName.m3u8",
    )
}

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
        // A real path is the most useful M3U locator for other players, and import finds it again
        // while the file keeps its name. Without a safe title there is no metadata fallback once
        // it does not (renamed, or a track whose file name the library never learned). Preserve
        // an exact same-device identity as an ignorable comment while leaving the real path intact.
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
 * match). The order, strongest evidence first:
 *
 * 1. exact local identity: a `#LATENTJAM-TRACK-ID` hint or a `latentjam:track-id:` locator;
 * 2. an exact media URI;
 * 3. the file path, matched by the longest run of trailing names (see [m3uPathNames]): another
 *    device, another app or a computer names the same file under its own root, so the root is
 *    never compared, but the file name always is;
 * 4. EXTINF artist + title;
 * 5. a file name whose folder disagrees with the playlist's (the row's own folder is elsewhere);
 * 6. the file name stem, then EXTINF title, against tag titles.
 *
 * Every step resolves duplicates by exact duration or not at all, never by library order.
 *
 * One confidence rule holds at every step. Steps 1 and 2, and a path whose folder agrees as well as
 * its file name, *name* a track: that is the file any player would play for the row, and the EXTINF
 * line is only its label (stale, or written from another player's tags), so it is not consulted.
 * Every other match is a *guess* — a file name alone, whether or not the row gives it a folder,
 * or tags — and the row's own EXTINF can rule a guess out:
 *
 * - a duration more than [GUESS_DURATION_SLACK_SECONDS] seconds off the track's rules out any guess;
 * - an artist other than the track's (case aside) rules out a guess made from tags, steps 4 and 6
 *   (a stem read as `Artist - Title` too). A file name is no tag: the file a name points at may
 *   well be tagged differently from the label another player wrote for it.
 *
 * A ruled-out track is not accepted by that step or any later one; the next step may still find
 * another track the row agrees with. Missing evidence contradicts nothing: a row without EXTINF
 * (or with `-1` and a bare title) matches as it always did, and so does a track without a
 * duration or an artist.
 *
 * File and folder names, titles and artists are compared composed ([nfcNormalized]) on both
 * sides, so a playlist written on a Mac finds the files a phone indexed, and the other way round.
 *
 * [filePaths] holds the platform's absolute path per track where it has one (Android's
 * MediaStore does); without it a track's path is its [TrackDescriptor.folderPath] plus
 * [TrackDescriptor.fileName], which is relative to its storage volume.
 */
internal fun matchM3uEntries(
    entries: List<M3uEntry>,
    library: List<TrackDescriptor>,
    filePaths: Map<TrackId, String> = emptyMap(),
): List<TrackDescriptor?> {
    fun norm(value: String?): String? = value
        ?.trim()
        ?.composed()
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
    val byFileName = HashMap<String, MutableList<LibraryPath>>()
    for (track in library) {
        val names = libraryPathNames(track, filePaths[track.id]) ?: continue
        byFileName.getOrPut(names.last().lowercase()) { mutableListOf() } += LibraryPath(track, names)
    }

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

    /**
     * The library files sharing the most names with [names] counted from the file upwards, or
     * null when no file has its name. Names compare ignoring case (SD cards and computers do);
     * exact case only breaks a tie.
     */
    fun rankPath(names: List<String>): RankedPath? {
        val candidates = byFileName[names.last().lowercase()] ?: return null
        var bestShared = 0
        var bestExact = -1
        val best = mutableListOf<LibraryPath>()
        for (candidate in candidates) {
            val shared = sharedTrailingNames(names, candidate.names, ignoreCase = true)
            // Case folding can disagree with lowercase() on a few letters; such a file shares
            // nothing, not even its name.
            if (shared == 0) continue
            val exact = sharedTrailingNames(names, candidate.names, ignoreCase = false)
            if (shared > bestShared || (shared == bestShared && exact > bestExact)) {
                best.clear()
                bestShared = shared
                bestExact = exact
            }
            if (shared == bestShared && exact == bestExact) best += candidate
        }
        return if (best.isEmpty()) null else RankedPath(names, best, bestShared, bestExact)
    }

    /**
     * Whether [entry]'s own EXTINF rules [track] out as a guess: a duration off by more than the
     * slack, or, for a guess made [fromTags], an artist other than the track's.
     */
    fun contradicts(entry: M3uEntry, track: TrackDescriptor, fromTags: Boolean): Boolean {
        val listedSeconds = entry.durationSeconds
        val trackMs = track.durationMs
        if (listedSeconds != null && trackMs != null &&
            abs(trackMs / 1_000L - listedSeconds) > GUESS_DURATION_SLACK_SECONDS
        ) {
            return true
        }
        if (!fromTags) return false
        val listedArtist = norm(entry.artist) ?: return false
        val trackArtist = norm(track.artist) ?: return false
        return listedArtist != trackArtist
    }

    /**
     * The one file [ranked] points at for [entry], or null when its best files tie or the row
     * rules out a file that only shares its name.
     */
    fun matchPath(entry: M3uEntry, ranked: RankedPath): PathMatch? {
        val track = resolve(ranked.best.map { it.track }, entry.durationSeconds) ?: return null
        val chosen = ranked.best.first { it.track.id == track.id }
        // Only the file name agrees: a guess ("01 Intro.mp3" is in every album), which the row's
        // duration can rule out.
        if (ranked.shared == 1 && contradicts(entry, track, fromTags = false)) return null
        // Both sides name a folder above the file, and the folders differ: the same file name in
        // another place, so stronger tag evidence is heard first.
        val elsewhere = ranked.shared == 1 && ranked.names.size > 1 && chosen.names.size > 1
        return PathMatch(track, elsewhere)
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

        // The path as written, or percent-decoded where that fits a library file better: some
        // exporters escape plain paths as if they were URIs ("Test%20Tones/Song.mp3"). A tie
        // keeps the name as written, which is how a file can really be called "a%20b.mp3".
        val pathNames = m3uPathNames(entry.path)
        val pathMatch = listOfNotNull(
            pathNames,
            m3uPathNames(entry.path, decode = true)?.takeIf { it != pathNames },
        )
            .mapNotNull(::rankPath)
            .maxWithOrNull(compareBy<RankedPath> { it.shared }.thenBy { it.exact })
            ?.let { matchPath(entry, it) }
        if (pathMatch != null && !pathMatch.elsewhere) return@map pathMatch.track

        // EXTINF supplied both fields explicitly. Honour that exact pair before considering the
        // filename: a library can legitimately contain many songs called "Intro".
        val entryArtist = norm(entry.artist)
        val entryTitle = norm(entry.title)
        if (entryArtist != null && entryTitle != null) {
            byArtistTitle[entryArtist to entryTitle]?.let { candidates ->
                // Identical tags the duration cannot split: the file name may still point at
                // one of them, but never at an unrelated track.
                val track = resolve(candidates, entry.durationSeconds)
                    ?: pathMatch?.track?.takeIf { matched -> candidates.any { it.id == matched.id } }
                    ?: return@map null
                if (!contradicts(entry, track, fromTags = true)) return@map track
            }
        }
        if (pathMatch != null) return@map pathMatch.track

        val stem = norm(
            (pathNames?.last() ?: entry.path.substringAfterLast('/').substringAfterLast('\\'))
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
        val titleSteps = listOf(
            stem?.let(byTitle::get),
            stemPair?.let(byArtistTitle::get),
            entryTitle?.let(byTitle::get),
        )
        for (candidates in titleSteps) {
            if (candidates == null) continue
            // A tie ends the search rather than letting a weaker step guess; a ruled-out track
            // leaves the next step free to find another.
            val track = resolve(candidates, entry.durationSeconds) ?: return@map null
            if (!contradicts(entry, track, fromTags = true)) return@map track
        }
        null
    }
}

/**
 * How far apart, in whole seconds, an EXTINF duration and a track may be before a guessed track
 * (see [matchM3uEntries]) is taken for another song. Exporters round or truncate.
 */
private const val GUESS_DURATION_SLACK_SECONDS = 2

/** A library track with the names of its file path, the last one being the file name. */
private class LibraryPath(val track: TrackDescriptor, val names: List<String>)

/** The library files that share the most trailing [names] with one M3U row: [shared] of them. */
private class RankedPath(
    val names: List<String>,
    val best: List<LibraryPath>,
    val shared: Int,
    /** How many of the [shared] names agree in case too; a tie on [shared] goes to more. */
    val exact: Int,
)

/** [track] found by its file path; [elsewhere] when only the file name agreed, not its folder. */
private class PathMatch(val track: TrackDescriptor, val elsewhere: Boolean)

/**
 * The names of an M3U row's file path, from its first folder down to the file, or null when the
 * row names no file: a `content://` or web URI, or nothing but slashes and dots.
 *
 * Exporters write one file many ways: `/storage/emulated/0/Music/A/Song.mp3` on the phone itself,
 * `/storage/1A2B-3C4D/Music/A/Song.mp3` on an SD card, `/sdcard/Music/A/Song.mp3` in older apps,
 * `D:\Music\A\Song.mp3` on Windows, `../A/./Song.mp3` relative to the playlist, and
 * `file:///storage/emulated/0/Music/A/My%20Song.mp3` as a URI. Matching compares names from the
 * file upwards, so every one of them comes down to `[…, Music, A, Song.mp3]`: backslashes are
 * separators, `.` is dropped, `..` climbs where it can and is ignored where the path starts with
 * it (the playlist's own folder is unknown here), and the legacy aliases of the phone's own
 * storage read as `/storage/emulated/0`. A `file:` URI is percent-decoded, being a URI; [decode]
 * decodes a plain path too, and a malformed escape then means null rather than a guess. A name a
 * Mac wrote decomposed comes back composed ([nfcNormalized]), as library names are compared.
 */
internal fun m3uPathNames(line: String, decode: Boolean = false): List<String>? {
    var path = line.trim()
    val scheme = URI_SCHEME.find(path)?.value
    if (scheme != null && scheme.equals("file:", ignoreCase = true)) {
        path = path.drop(scheme.length)
        // file:///p and file://localhost/p name the same local file as file:/p; any other
        // authority is another machine.
        if (path.startsWith("//")) {
            val authorityEnd = path.indexOf('/', startIndex = 2)
            if (authorityEnd < 0) return null
            val authority = path.substring(2, authorityEnd)
            if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            path = path.substring(authorityEnd)
        }
        path = path.percentDecoded() ?: path
    } else if (scheme != null && path.startsWith("//", startIndex = scheme.length)) {
        // content://, http://, …: a locator, not a file path.
        return null
    } else if (decode) {
        path = path.percentDecoded() ?: return null
    }
    val names = filePathNames(path) ?: return null
    return if (path.startsWith('/') || path.startsWith('\\')) names.withCanonicalStorageRoot() else names
}

/**
 * [track]'s file path as names: its platform's [absolutePath] when known, otherwise its folder
 * (relative to its storage volume) and file name. Null for a track with no file name, like a
 * song of the iOS Music library.
 */
private fun libraryPathNames(track: TrackDescriptor, absolutePath: String?): List<String>? {
    absolutePath
        ?.takeIf { it.startsWith('/') }
        ?.let(::filePathNames)
        ?.let { return it.withCanonicalStorageRoot() }
    val fileName = track.fileName?.let(::filePathNames)?.singleOrNull() ?: return null
    return track.folderPath?.let(::filePathNames).orEmpty() + fileName
}

/**
 * The names of a slash- or backslash-separated path with `.` and `..` resolved, composed
 * ([nfcNormalized]) whichever way the path spelled them; null if none.
 */
private fun filePathNames(path: String): List<String>? {
    val names = mutableListOf<String>()
    for (name in path.replace('\\', '/').composed().split('/')) {
        when (name) {
            "", "." -> Unit
            ".." -> names.removeLastOrNull()
            else -> names += name
        }
    }
    return names.takeIf { it.isNotEmpty() }
}

/**
 * The phone's own shared storage under the one name MediaStore reports today. `/sdcard`,
 * `/mnt/sdcard`, `/storage/self/primary` and `/storage/emulated/legacy` all point there, and a
 * playlist written with one of them should not lose to a same-named copy on an SD card.
 */
private fun List<String>.withCanonicalStorageRoot(): List<String> {
    val aliasLength = when {
        startsWith("sdcard") -> 1
        startsWith("mnt", "sdcard") -> 2
        startsWith("storage", "self", "primary") -> 3
        startsWith("storage", "emulated", "legacy") -> 3
        else -> return this
    }
    return listOf("storage", "emulated", "0") + drop(aliasLength)
}

private fun List<String>.startsWith(vararg prefix: String): Boolean =
    size > prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** How many names [a] and [b] share counting from their ends. */
private fun sharedTrailingNames(a: List<String>, b: List<String>, ignoreCase: Boolean): Int {
    var shared = 0
    while (shared < a.size && shared < b.size &&
        a[a.lastIndex - shared].equals(b[b.lastIndex - shared], ignoreCase = ignoreCase)
    ) {
        shared++
    }
    return shared
}

/** `scheme:` at the head of a URI. Two characters at least: `C:` is a Windows drive. */
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]+:")

/**
 * `%XX` escapes decoded as UTF-8, or null when an escape is malformed. Escaped bytes that are not
 * UTF-8 come from an older tool's ANSI code page (`%E9` for é) and are read as Windows-1252.
 * `+` stays a plus: it means a space only in form data, never in a path.
 */
private fun String.percentDecoded(): String? {
    if ('%' !in this) return this
    val source = encodeToByteArray()
    val decoded = ByteArray(source.size)
    var size = 0
    var index = 0
    while (index < source.size) {
        val byte = source[index]
        if (byte == '%'.code.toByte()) {
            if (index + 2 >= source.size) return null
            val high = hexDigit(source[index + 1])
            val low = hexDigit(source[index + 2])
            if (high < 0 || low < 0) return null
            decoded[size++] = (high * 16 + low).toByte()
            index += 3
        } else {
            decoded[size++] = byte
            index++
        }
    }
    return runCatching {
        decoded.decodeToString(0, size, throwOnInvalidSequence = true)
    }.getOrElse { windows1252Escapes() }
}

/** Only the escapes are code-page bytes; literal characters stay as written. */
private fun String.windows1252Escapes(): String = buildString {
    var index = 0
    while (index < this@windows1252Escapes.length) {
        val char = this@windows1252Escapes[index]
        if (char == '%') {
            val byte = this@windows1252Escapes.substring(index + 1, index + 3).toInt(16)
            append(byteArrayOf(byte.toByte()).decodeUtf8OrWindows1252())
            index += 3
        } else {
            append(char)
            index++
        }
    }
}

private fun hexDigit(byte: Byte): Int = when (val char = byte.toInt().toChar()) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
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
