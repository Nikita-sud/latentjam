/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistTrackChange
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException

/** The entries of playlist [playlistId] that are not on this device, as last asked. */
internal data class UnavailablePlaylistEntries(
    val playlistId: String,
    val ids: Set<TrackId>,
)

/** What the open playlist's page says about its entries that are not on this device. */
@Stable
internal class OpenPlaylistAvailability {
    /** The open playlist's entries that are not on this device, as last asked; null when none is asked about. */
    var unavailable by mutableStateOf<UnavailablePlaylistEntries?>(null)

    /** A removal of the shown entries is running; the page offers no second one meanwhile. */
    var removalInFlight by mutableStateOf(false)

    /** Drops what was shown about playlist [playlistId]; what is shown about another stays. */
    fun dismiss(playlistId: String) {
        if (unavailable?.playlistId == playlistId) unavailable = null
    }
}

/**
 * The entries of the open playlist ([openPlaylistId], one of [playlists]) that are not on this
 * device. Asked again whenever its membership or the library changes; while the library cannot
 * vouch for itself (a partial scan, no grant, a card that is out) there are none, so nothing is
 * offered for removal. [library] and [missing] are as for [unavailablePlaylistEntries].
 */
@Composable
internal fun rememberOpenPlaylistAvailability(
    openPlaylistId: String?,
    playlists: List<Playlist>,
    library: AutomaticIndexingRequest?,
    missing: suspend (Collection<TrackId>) -> Set<TrackId>,
): OpenPlaylistAvailability {
    val availability = remember { OpenPlaylistAvailability() }
    val openPlaylistMembers = remember(openPlaylistId, playlists) {
        playlists.firstOrNull { it.id == openPlaylistId }?.trackIds
    }
    LaunchedEffect(openPlaylistId, openPlaylistMembers, library) {
        if (openPlaylistId == null || openPlaylistMembers == null || library == null) {
            availability.unavailable = null
            return@LaunchedEffect
        }
        val unavailable = try {
            unavailablePlaylistEntries(openPlaylistMembers, library, missing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            println("Playlists: could not check for missing tracks: $failure")
            emptySet()
        }
        availability.unavailable = UnavailablePlaylistEntries(openPlaylistId, unavailable)
    }
    return availability
}

/**
 * The entries of a playlist ([members], its stored ids) that are not on this device.
 *
 * A playlist keeps the ids of songs that went away, so one that comes back (a card put back in, a
 * grant given again) returns to its place. Every list, count and queue already shows only the songs
 * present; this names the rest, so the listener can be told and may choose to forget them.
 *
 * Only a library that can vouch for itself is asked: [library] must come from a complete scan with
 * the media grant, and hold songs. Only entries the visible library lacks are candidates; [missing]
 * ([io.github.nikitasud.latentjam.library.MusicLibrary.missingTrackIds]) then keeps those the device
 * proves gone, leaving out hidden songs, songs of a disabled source and storage that is out.
 */
internal suspend fun unavailablePlaylistEntries(
    members: List<String>,
    library: AutomaticIndexingRequest,
    missing: suspend (Collection<TrackId>) -> Set<TrackId>,
): Set<TrackId> {
    if (!library.librarySnapshotAuthoritative || library.tracks.isEmpty()) return emptySet()
    val visible = library.tracks.mapTo(HashSet(library.tracks.size)) { it.id }
    val candidates = members.asSequence()
        .filter(String::isNotBlank)
        .map(::TrackId)
        .filterNot(visible::contains)
        .toCollection(LinkedHashSet())
    if (candidates.isEmpty()) return emptySet()
    return missing(candidates).filterTo(LinkedHashSet()) { it in candidates }
}

/**
 * Forgets the entries of playlist [playlistId] the listener was told are not on this device
 * ([shown]), asking the device again first: storage may have come back since the notice was drawn,
 * and only what is still gone at the moment of the tap is removed. Entries that appeared since are
 * left alone even if they are gone too; the listener was not told about them.
 *
 * @return the change, for Undo through [Playlists.restoreTracksIfUnchanged]; null when nothing was
 *   removed (the playlist is gone, or every shown entry is back).
 */
internal suspend fun removeUnavailablePlaylistEntries(
    playlists: Playlists,
    playlistId: String,
    shown: Set<TrackId>,
    library: AutomaticIndexingRequest,
    missing: suspend (Collection<TrackId>) -> Set<TrackId>,
): PlaylistTrackChange? {
    if (shown.isEmpty()) return null
    val stored = playlists.all().firstOrNull { it.id == playlistId } ?: return null
    val confirmed = unavailablePlaylistEntries(stored.trackIds, library, missing)
        .filterTo(LinkedHashSet()) { it in shown }
    if (confirmed.isEmpty()) return null
    return playlists.removeTracks(playlistId, confirmed)?.takeIf { it.before != it.after }
}
