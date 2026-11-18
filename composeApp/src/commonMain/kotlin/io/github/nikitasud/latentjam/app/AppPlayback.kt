// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.smart.associateByIdValue
import androidx.compose.runtime.*
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.allowed
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.first

/** One unconditional composition owner for launch restore and external playback requests. */
@Composable
internal fun PlaybackRestoreEffect(
    playback: PlaybackController,
    library: MusicLibrary,
    /** The library as last scanned, with whether that scan can vouch for what it does not show. */
    request: AutomaticIndexingRequest?,
    playlistsState: PlaylistsState,
    externalAudioOpener: ExternalAudioOpener?,
    onOpenPlayer: () -> Unit,
    onUnavailable: () -> Unit,
) {
    val tracks = request?.tracks
    val authoritative = request?.librarySnapshotAuthoritative == true
    var resumeAttempted by remember { mutableStateOf(false) }
    // The lock player's LatentJam button, after the unlock.
    val openRequested by PlayerOpenRequest.pending.collectAsState()
    LaunchedEffect(openRequested) {
        if (openRequested) {
            PlayerOpenRequest.pending.value = false
            onOpenPlayer()
        }
    }
    val externalAudioRequest = externalAudioOpener?.pending?.collectAsState()?.value
    LaunchedEffect(tracks, playlistsState.playlistsLoaded, externalAudioRequest) {
        if (resumeAttempted || externalAudioOpener?.pending?.value != null) return@LaunchedEffect
        val loaded = tracks ?: return@LaunchedEffect
        // Restore may asynchronously top up a short SMART future. Wait until its marked
        // playlist policy has been hydrated so those new rows honor the saved preference.
        if (!playlistsState.playlistsLoaded) return@LaunchedEffect
        // An empty snapshot is the pre-permission state, not a library: consuming the one
        // restore attempt on it would silently skip the restore the first REAL load earns.
        if (loaded.isEmpty()) return@LaunchedEffect
        resumeAttempted = true
        val saved = AppGraph.settings.resumePlayback.value ?: return@LaunchedEffect
        // A widget, Quick Settings, Bluetooth, or Android Auto may have resumed the platform
        // session before this composition existed. Import that state before deciding whether
        // launch restore is needed; replacing it here would pause live playback.
        playback.synchronizeWithPlatformSession()
        if (externalAudioOpener?.pending?.value != null) return@LaunchedEffect
        // Something already sounding (an external controller, a fast user) wins outright.
        if (!shouldApplySavedPlaybackAfterPlatformSync(playback.state.value)) {
            return@LaunchedEffect
        }
        restoreSavedQueue(playback, saved, loaded, authoritative, playlistsState)
    }

    // A restore from a scan that could not see every song gave the player the rows it found and kept the stored
    // queue ([HeldResumeQueue]). The first scan that can vouch for itself settles it.
    val held by resumeQueueHold.collectAsState()
    val latestRequest by rememberUpdatedState(request)
    LaunchedEffect(authoritative, held, playlistsState.playlistsLoaded) {
        val hold = held ?: return@LaunchedEffect
        if (latestRequest?.librarySnapshotAuthoritative != true || !playlistsState.playlistsLoaded) return@LaunchedEffect
        settleHeldResumeQueue(playback, hold, { latestRequest }, playlistsState)
    }

    ExternalAudioOpenEffect(
        opener = externalAudioOpener,
        library = library,
        tracks = tracks,
        playbackReady = playlistsState.playlistsLoaded,
        userPlaybackRevision = { playback.userPlaybackRevision },
        onBegin = { resumeAttempted = true },
        onPlay = { track ->
            AppGraph.queueSource.value = null
            PersonalSignals.pick(PickSurface.EXTERNAL)
            playback.play(listOf(track), 0)
            onOpenPlayer()
        },
        onUnavailable = {
            onUnavailable()
        },
    )

}

/**
 * Puts the saved session [saved] into the player, paused, from the library [loaded]. When that scan cannot vouch
 * for what it does not show ([authoritative] false) and rows of the stored queue are missing from it, the player
 * gets the rows that are there and the stored queue is kept ([resumeQueueHold]).
 */
private suspend fun restoreSavedQueue(
    playback: PlaybackController,
    saved: ResumePlayback,
    loaded: List<TrackDescriptor>,
    authoritative: Boolean,
    playlistsState: PlaylistsState,
    /** False when a hold is being settled: the saved session's writer ends it once the player has the new queue. */
    assignHold: Boolean = true,
) {
    // The mode is the more important half — restore it even when the track is gone
    // (deleted, SD card unmounted): SMART being on is what the user asked to keep.
    // While SMART is switched off a session saved in SMART comes back in plain order, with its rows as plain rows.
    val storedMode = ShuffleMode.entries.firstOrNull { it.name == saved.shuffleMode }
    val savedMode = storedMode?.allowed(AppGraph.settings.smartEnabled.value)
    val smartKept = savedMode == storedMode
    savedMode?.let { playback.setShuffleMode(it) }
    // Prefer the queue that was ACTUALLY playing — a playlist stays that playlist across
    // a restart. Tracks deleted meanwhile drop out; if the saved track itself is gone or
    // no queue was saved, fall back to wrapping the track in the whole library.
    val byId = loaded.associateByIdValue()
    val resolvedSavedQueue = saved.queueTrackIds.mapIndexedNotNull { persistedIndex, id ->
        byId[id]?.let { persistedIndex to it }
    }
    val savedQueue = resolvedSavedQueue.map { it.second }
    val savedQueueIndex = resolvedSavedQueue.indexOfFirst { (persistedIndex, track) ->
        persistedIndex == saved.queueIndex && track.id.value == saved.trackId
    }.takeIf { it >= 0 } ?: savedQueue.indexOfFirst { it.id.value == saved.trackId }
    // Oversized sources are omitted from frequently-written preferences. Tracks rebuild
    // from the library; a stable playlist id rebuilds current membership. A deleted
    // playlist resolves to known-empty, never to the generated SMART tail.
    val savedSourceQueue = resolveResumeSourceQueue(
        saved = saved,
        library = loaded,
        playlists = playlistsState.playlists,
        playlistsAvailable = playlistsState.playlistsAvailableForSourceRestore,
        playlistOrders = AppGraph.settings.playlistSortOrders.value,
        plays = playsForResume(saved, AppGraph.settings.playlistSortOrders.value),
    )
    // Assigned either way: a hold left by an earlier restore does not describe this one.
    if (assignHold) {
        resumeQueueHold.value = if (savedQueueIndex < 0) null else {
            heldResumeQueue(saved, resolvedSavedQueue.map { it.first }, byId::containsKey, authoritative)
        }
    }
    if (savedQueueIndex >= 0) {
        playback.restoreQueue(
            tracks = savedQueue,
            startIndex = savedQueueIndex,
            positionMs = saved.positionMs,
            // Null is the legacy state (no source was ever persisted); an empty v2 source
            // is meaningful when every original playlist row was deleted while a
            // generated SMART current row survived.
            sourceTracks = savedSourceQueue,
            smartContinuationIds = if (smartKept) saved.smartContinuationIds.mapTo(HashSet(), ::TrackId) else emptySet(),
            smartPlanPositions = if (smartKept) saved.smartPlanPositions.mapKeys { (id, _) -> TrackId(id) } else emptyMap(),
        )
    }
    val index = loaded.indexOfFirst { it.id.value == saved.trackId }
    if (savedQueueIndex < 0 && index >= 0) {
        val fallbackSource = savedSourceQueue ?: loaded
        val fallback = fallbackResumeQueue(
            mode = savedMode,
            current = loaded[index],
            source = fallbackSource,
        )
        playback.restoreQueue(
            tracks = fallback.liveQueue,
            startIndex = fallback.currentIndex,
            positionMs = saved.positionMs,
            sourceTracks = fallbackSource,
        )
    }
    if (savedQueueIndex >= 0 || index >= 0) {
        // The restored player keeps saying where its queue came from. An unknown kind
        // from another build degrades to no label, same as the mode above.
        AppGraph.queueSource.value = saved.savedQueueSource()
    }
}

/**
 * Settles [hold] against the first scan that can vouch for itself ([request]). Rows that scan shows and the player
 * lacks are put back by restoring the stored queue again at the player's own track and position; with none to put
 * back the missing rows are gone, and the queue is stored as it is from then on. It waits for a pause, since a
 * restore loads its queue paused, and does nothing once the listener has another queue.
 */
private suspend fun settleHeldResumeQueue(
    playback: PlaybackController,
    hold: HeldResumeQueue,
    request: () -> AutomaticIndexingRequest?,
    playlistsState: PlaylistsState,
) {
    val now = playback.state.first { !it.isPlaying }
    val current = request()?.takeIf { it.librarySnapshotAuthoritative } ?: return
    if (resumeQueueHold.value !== hold) return
    val liveIds = now.queue.map { it.id.value }
    val source = AppGraph.queueSource.value
    val saved = now.track?.let { track ->
        hold.storedFor(
            ResumePlayback(
                trackId = track.id.value,
                shuffleMode = now.shuffleMode.name,
                positionMs = now.positionMs.coerceAtLeast(0L),
                sourceKind = source?.kind?.name,
                sourceName = source?.name,
                sourceReference = source?.reference,
                queueTrackIds = liveIds,
                queueIndex = now.queueIndex,
                smartContinuationIds = now.smartContinuationIds.mapTo(HashSet()) { it.value },
                smartPlanPositions = now.smartPlanPositions.mapKeys { (id, _) -> id.value },
            ),
        )
    }
    val known = current.tracks.mapTo(HashSet()) { it.id.value }
    if (saved == null || !regainsRows(saved, known, liveIds, now.sourceQueue.map { it.id.value })) {
        resumeQueueHold.compareAndSet(hold, null)
        return
    }
    // The hold is left as it is: a player that takes the queue shows other rows, which ends the hold where the
    // session is written; one that declines (the listener pressed play meanwhile) still needs it.
    restoreSavedQueue(playback, saved, current.tracks, authoritative = true, playlistsState, assignHold = false)
}

/** Whether a library holding [known] has a row of [saved]'s queue or source that the player ([liveIds], [liveSourceIds]) lacks. */
internal fun regainsRows(
    saved: ResumePlayback,
    known: Set<String>,
    liveIds: List<String>,
    liveSourceIds: List<String>,
): Boolean {
    val live = liveIds.toHashSet()
    val liveSource = liveSourceIds.toHashSet()
    return saved.queueTrackIds.any { it in known && it !in live } ||
        saved.sourceQueueTrackIds.any { it in known && it !in liveSource }
}
