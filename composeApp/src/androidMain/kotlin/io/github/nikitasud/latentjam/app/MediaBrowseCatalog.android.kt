/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.associateByIdValue
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.nikitasud.latentjam.app.shared.R
import io.github.nikitasud.latentjam.playback.MediaBrowseCatalog
import io.github.nikitasud.latentjam.playback.MediaBrowseCollection
import io.github.nikitasud.latentjam.playback.MediaBrowseRegistry
import io.github.nikitasud.latentjam.playback.MediaPlaybackResume
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.allowed
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Publishes the app's library to external media browsers (Android Auto).
 *
 * The provider runs per browse request against the live library and playlists, so a car screen
 * always sees the current state without any refresh plumbing. Failures collapse to null — an
 * empty browse tree, never a crashed session.
 */
internal fun installMediaBrowseCatalog(context: Context) {
    val appContext = context.applicationContext
    MediaBrowseRegistry.catalog = {
        try {
            val tracks = libraryWithoutScreens()
            val byId = tracks.associateByIdValue()
            val orders = AppGraph.settings.playlistSortOrders.value
            val playlists = AppGraph.playlists.all()
            val plays = playsWhenSortedByPlays(orders, playlists.map { it.id })
            MediaBrowseCatalog(
                collectionsTitle = appContext.getString(R.string.browse_playlists),
                tracksTitle = appContext.getString(R.string.browse_tracks),
                collections = playlists.mapNotNull { playlist ->
                    // In the order the playlist's page shows, so the car plays what the phone shows.
                    val members = playlist.tracksInChosenOrder(playlist.trackIds.mapNotNull(byId::get), orders, plays)
                    if (members.isEmpty()) return@mapNotNull null
                    MediaBrowseCollection(
                        id = "browse-playlist-${playlist.id}",
                        title = playlist.name,
                        tracks = members,
                    )
                },
                tracks = tracks,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
    // A queue from Android Auto, a voice request or another app did not come from what the app last
    // started: keeping that source would label the player and the listening log with it.
    MediaBrowseRegistry.onExternalQueue = {
        AppGraph.queueSource.value = null
        // A car or a voice request can start playback in a process no screen ever opened. As for a resumed queue
        // below, the app's controller connects, so the queue has history, a saved session and SMART's library.
        AppGraph.appScope.launch {
            AppGraph.playback.synchronizeWithPlatformSession()
            supplySmartLibraryQuietly()
        }
    }
    // Settings load their saved queue eagerly from preferences, so this answers without waiting.
    // Without music access the queue cannot be read: the tile then opens the app, which asks again.
    MediaBrowseRegistry.canRestoreSavedQueue = {
        AppGraph.settings.resumePlayback.value != null && hasMusicAccess(appContext)
    }
    MediaBrowseRegistry.resumption = { forPlayback ->
        try {
            buildMediaPlaybackResume()?.also { resume ->
                // A headset, the widget or a voice request resumes the saved queue without the UI,
                // whose launch restore then finds it playing and leaves it be. The saved source
                // still describes this queue; without it the player, the listening log and the
                // next save would all lose where it came from.
                if (AppGraph.queueSource.value == null) {
                    AppGraph.queueSource.value = AppGraph.settings.resumePlayback.value?.savedQueueSource()
                }
                // Only a queue the platform installs is announced: the launch restore yields to an announced
                // resumption, so one that is only read (System UI's boot-time card) or that the widget restores
                // itself would leave the player empty under the saved queue.
                if (!forPlayback) return@also
                MediaBrowseRegistry.announceActiveResumption(resume)
                // A widget/tile/Auto controller can start playback without ever opening the UI.
                // Connect the app controller too, so it owns queue/source transitions and its
                // state is hydrated if the Activity is opened later.
                AppGraph.appScope.launch {
                    AppGraph.playback.synchronizeWithPlatformSession()
                    supplySmartLibraryQuietly()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * [AppGraph.supplySmartLibraryWithoutScreens] for a resume no screen started. A failure costs SMART's wider
 * choice until the app is opened, never the playback that is already under way.
 */
internal suspend fun supplySmartLibraryQuietly() {
    try {
        AppGraph.supplySmartLibraryWithoutScreens()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        println("SMART: no library for a resume without screens: $failure")
    }
}

/**
 * The playable library for what is browsed or resumed with no screen open (Android Auto, a headset button, the
 * widget, the tile): the scan with its remembered tag facts, as the screens publish it. The scan alone still names a
 * folder as the album of a song whose file names none and lends it that folder's cover, so the notification, the
 * lock screen and the car showed a neighbour's cover where the app's own lists showed none (issues #12, #39).
 */
internal suspend fun libraryWithoutScreens(
    scan: suspend () -> List<TrackDescriptor> = { AppGraph.library.tracks() },
    enrich: suspend (List<TrackDescriptor>) -> List<TrackDescriptor> = { GenreEnrichment(AppGraph.settings).apply(it) },
): List<TrackDescriptor> = enrich(scan()).filter { !it.audioUri.isNullOrBlank() }

/** Reuses the exact queue/source fallback rules used by the Compose launch restore. */
private suspend fun buildMediaPlaybackResume(): MediaPlaybackResume? {
    val saved = AppGraph.settings.resumePlayback.value ?: return null
    val library = libraryWithoutScreens()
    if (library.isEmpty()) return null
    // A session saved in SMART is resumed in plain order while SMART is switched off (headset, car, tile, widget).
    val mode = (ShuffleMode.entries.firstOrNull { it.name == saved.shuffleMode } ?: ShuffleMode.OFF)
        .allowed(AppGraph.settings.smartEnabled.value)
    val byId = library.associateByIdValue()
    val playlists = AppGraph.playlists.all()
    val orders = AppGraph.settings.playlistSortOrders.value
    val source = resolveResumeSourceQueue(
        saved = saved,
        library = library,
        playlists = playlists,
        playlistOrders = orders,
        plays = playsForResume(saved, orders),
    )
    val resolvedSavedQueue = saved.queueTrackIds.mapIndexedNotNull { persistedIndex, id ->
        byId[id]?.let { persistedIndex to it }
    }
    val savedQueue = resolvedSavedQueue.map { it.second }
    val savedQueueIndex = resolvedSavedQueue.indexOfFirst { (persistedIndex, track) ->
        persistedIndex == saved.queueIndex && track.id.value == saved.trackId
    }.takeIf { it >= 0 } ?: savedQueue.indexOfFirst { it.id.value == saved.trackId }

    if (savedQueueIndex >= 0) {
        return MediaPlaybackResume(
            tracks = savedQueue,
            startIndex = savedQueueIndex,
            positionMs = saved.positionMs.coerceAtLeast(0L),
            shuffleMode = mode,
            sourceTracks = source,
            // Its rows are plain rows when the saved SMART mode was not kept.
            smartContinuationIds = if (mode.name == saved.shuffleMode) saved.smartContinuationIds.mapTo(HashSet(), ::TrackId) else emptySet(),
            smartPlanPositions = if (mode.name == saved.shuffleMode) saved.smartPlanPositions.mapKeys { (id, _) -> TrackId(id) } else emptyMap(),
        )
    }

    val current = byId[saved.trackId] ?: return null
    val fallbackSource = source ?: library
    val fallback = fallbackResumeQueue(mode = mode, current = current, source = fallbackSource)
    return MediaPlaybackResume(
        tracks = fallback.liveQueue,
        startIndex = fallback.currentIndex,
        positionMs = saved.positionMs.coerceAtLeast(0L),
        shuffleMode = mode,
        sourceTracks = fallbackSource,
    )
}

/** The same permission the app's grant screen asks for ([AndroidAppPermissions]). */
private fun hasMusicAccess(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
