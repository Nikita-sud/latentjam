/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.m3u_import_playlists_partial
import io.github.nikitasud.latentjam.app.generated.resources.settings_library_manage_failed
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_imported_all
import io.github.nikitasud.latentjam.app.generated.resources.snack_m3u_imported_partial
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** What one M3U file came to against the library. */
internal class M3uImport(
    /** The nonblank `#PLAYLIST:` name, or the selected file name without its M3U extension. */
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
    displayName: String? = null,
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
            name = parseM3uName(text) ?: m3uFileName(displayName),
            entryCount = entries.size,
            matchedEntryCount = matched.count { it != null },
            tracks = matched.filterNotNull().distinctBy { it.id },
        )
    }
}

/**
 * [importM3u] for every picked document, in order. The library's paths are asked for once for all
 * of them, and only when a file has rows: on Android each ask is a full MediaStore query.
 */
internal suspend fun importM3uFiles(
    documents: List<ImportedLocalDocument>,
    library: List<TrackDescriptor>,
    filePaths: suspend (List<TrackId>) -> Map<TrackId, String>,
): List<M3uImport> {
    var paths: Map<TrackId, String>? = null
    val pathsOnce: suspend (List<TrackId>) -> Map<TrackId, String> = { ids ->
        paths ?: try {
            filePaths(ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // As in importM3u: a failed lookup costs the volume part of path matching, once.
            emptyMap()
        }.also { paths = it }
    }
    return documents.map { document -> importM3u(document.text, library, document.displayName, pathsOnce) }
}

/** How [createM3uPlaylists] went: the playlists made, and whether it stopped on a failure. */
internal class M3uPlaylistsCreated(val created: Int, val failed: Boolean)

/**
 * One playlist per file of [offer] that found tracks, in order, through [create]. A failure stops
 * the run but keeps what was made: the count says how far it got.
 */
internal suspend fun createM3uPlaylists(
    offer: M3uImportOffer,
    fallbackName: String,
    create: suspend (name: String, ids: List<TrackId>) -> Unit,
): M3uPlaylistsCreated {
    var created = 0
    for (import in offer.playlists) {
        try {
            create(import.name ?: fallbackName, import.tracks.map { it.id })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            println("Playlists: could not create an imported playlist: $failure")
            return M3uPlaylistsCreated(created, failed = true)
        }
        created++
    }
    return M3uPlaylistsCreated(created, failed = false)
}

/**
 * The snackbar after [createM3uPlaylists]: [m3uImportedMessage] when every playlist was made, the
 * failure when none was, and the failure with how many were made when it stopped part-way.
 */
internal suspend fun m3uPlaylistsCreatedMessage(offer: M3uImportOffer, result: M3uPlaylistsCreated): String =
    when {
        !result.failed -> m3uImportedMessage(offer)
        result.created == 0 -> getString(Res.string.settings_library_manage_failed)
        // A dedicated line, not "try again": importing the same files again would make the first
        // playlists a second time.
        else -> getString(Res.string.m3u_import_playlists_partial, result.created, offer.playlists.size)
    }

/** Keep dots and other extensions in the title; only an M3U suffix is format metadata. */
private fun m3uFileName(displayName: String?): String? {
    val fileName = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim() ?: return null
    val name = when {
        fileName.endsWith(".m3u8", ignoreCase = true) -> fileName.dropLast(5)
        fileName.endsWith(".m3u", ignoreCase = true) -> fileName.dropLast(4)
        else -> fileName
    }
    return name.trim().ifEmpty { null }
}

/**
 * The snackbar after playlists were created from [offer]: how many tracks they hold, or, when
 * rows found nothing, how many of the files' rows came in, so a partial playlist never passes for
 * the whole file.
 */
internal suspend fun m3uImportedMessage(offer: M3uImportOffer): String =
    if (offer.matchedEntryCount == offer.entryCount) {
        val tracks = offer.playlists.sumOf { it.tracks.size }
        getPluralString(Res.plurals.snack_m3u_imported_all, tracks, tracks)
    } else {
        getPluralString(
            Res.plurals.snack_m3u_imported_partial,
            offer.entryCount,
            offer.matchedEntryCount,
            offer.entryCount,
        )
    }
