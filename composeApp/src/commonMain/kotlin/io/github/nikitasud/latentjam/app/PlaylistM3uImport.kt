/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_imported_all
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_imported_partial
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString

/** What one M3U file came to against the library. */
internal class M3uImport(
    /** The file's own `#PLAYLIST:` name, when it carries one. */
    val name: String?,
    /** Every track row of the file, repeats included. */
    val entryCount: Int,
    /** The rows that found their track; [entryCount] minus this many are not in the library. */
    val matchedEntryCount: Int,
    /**
     * The found tracks in file order, each once: a playlist holds a song once (`DefaultPlaylists`
     * drops a repeat on create and on add), so a file that lists one twice gives one row.
     */
    val tracks: List<TrackDescriptor>,
)

/**
 * Parses and matches an M3U [text] against [library] off the main thread. [filePaths] is the
 * library's absolute-path lookup, asked only when the file has rows; a lookup that fails costs
 * only the volume part of path matching, never the import.
 */
internal suspend fun importM3u(
    text: String,
    library: List<TrackDescriptor>,
    filePaths: suspend (List<TrackId>) -> Map<TrackId, String>,
): M3uImport {
    val entries = withContext(Dispatchers.Default) { parseM3u(text) }
    if (entries.isEmpty()) return M3uImport(name = null, entryCount = 0, matchedEntryCount = 0, tracks = emptyList())
    val paths = try {
        filePaths(library.map { it.id })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
    return withContext(Dispatchers.Default) {
        val matched = matchM3uEntries(entries, library, paths)
        M3uImport(
            name = parseM3uName(text),
            entryCount = entries.size,
            matchedEntryCount = matched.count { it != null },
            tracks = matched.filterNotNull().distinctBy { it.id },
        )
    }
}

/**
 * The snackbar after a playlist was created from [import]: how many tracks it holds, or, when
 * rows found nothing, how many of the file's rows came in, so a partial playlist never passes for
 * the whole file.
 */
internal suspend fun m3uImportedMessage(import: M3uImport): String =
    if (import.matchedEntryCount == import.entryCount) {
        getPluralString(Res.plurals.snack_m3u_imported_all, import.tracks.size, import.tracks.size)
    } else {
        getPluralString(
            Res.plurals.snack_m3u_imported_partial,
            import.entryCount,
            import.matchedEntryCount,
            import.entryCount,
        )
    }
