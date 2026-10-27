// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.*
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** One unconditional composition owner for launch restore and external playback requests. */
@Composable
internal fun PlaybackRestoreEffect(
    playback: PlaybackController,
    library: MusicLibrary,
    tracks: List<TrackDescriptor>?,
    playlistsState: PlaylistsState,
    externalAudioOpener: ExternalAudioOpener?,
    onOpenPlayer: () -> Unit,
    onUnavailable: () -> Unit,
) {
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
        // The mode is the more important half — restore it even when the track is gone
        // (deleted, SD card unmounted): SMART being on is what the user asked to keep.
        val savedMode = ShuffleMode.entries.firstOrNull { it.name == saved.shuffleMode }
        savedMode?.let { playback.setShuffleMode(it) }
        // Prefer the queue that was ACTUALLY playing — a playlist stays that playlist across
        // a restart. Tracks deleted meanwhile drop out; if the saved track itself is gone or
        // no queue was saved, fall back to wrapping the track in the whole library.
        val byId = loaded.associateBy { it.id.value }
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
        if (savedQueueIndex >= 0) {
            playback.restoreQueue(
                tracks = savedQueue,
                startIndex = savedQueueIndex,
                positionMs = saved.positionMs,
                // Null is the legacy state (no source was ever persisted); an empty v2 source
                // is meaningful when every original playlist row was deleted while a
                // generated SMART current row survived.
                sourceTracks = savedSourceQueue,
                smartContinuationIds = saved.smartContinuationIds.mapTo(HashSet(), ::TrackId),
                smartPlanPositions = saved.smartPlanPositions.mapKeys { (id, _) -> TrackId(id) },
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

    ExternalAudioOpenEffect(
        opener = externalAudioOpener,
        library = library,
        tracks = tracks,
        playbackReady = playlistsState.playlistsLoaded,
        userPlaybackRevision = { playback.userPlaybackRevision },
        onBegin = { resumeAttempted = true },
        onPlay = { track ->
            AppGraph.queueSource.value = null
            playback.play(listOf(track), 0)
            onOpenPlayer()
        },
        onUnavailable = {
            onUnavailable()
        },
    )

}
