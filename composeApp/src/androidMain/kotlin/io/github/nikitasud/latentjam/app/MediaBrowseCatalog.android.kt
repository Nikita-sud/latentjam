/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import io.github.nikitasud.latentjam.app.shared.R
import io.github.nikitasud.latentjam.playback.MediaBrowseCatalog
import io.github.nikitasud.latentjam.playback.MediaBrowseCollection
import io.github.nikitasud.latentjam.playback.MediaBrowseRegistry
import io.github.nikitasud.latentjam.playback.MediaPlaybackResume
import io.github.nikitasud.latentjam.playback.ShuffleMode
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
            val tracks = AppGraph.library.tracks().filter { !it.audioUri.isNullOrBlank() }
            val byId = tracks.associateBy { it.id.value }
            MediaBrowseCatalog(
                collectionsTitle = appContext.getString(R.string.browse_playlists),
                tracksTitle = appContext.getString(R.string.browse_tracks),
                collections = AppGraph.playlists.all().mapNotNull { playlist ->
                    val members = playlist.trackIds.mapNotNull(byId::get)
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
    MediaBrowseRegistry.resumption = {
        try {
            buildMediaPlaybackResume()?.also { resume ->
                MediaBrowseRegistry.announceActiveResumption(resume)
                // A widget/tile/Auto controller can start playback without ever opening the UI.
                // Connect the app controller too, so it owns queue/source transitions and its
                // state is hydrated if the Activity is opened later.
                AppGraph.appScope.launch {
                    AppGraph.playback.synchronizeWithPlatformSession()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

/** Reuses the exact queue/source fallback rules used by the Compose launch restore. */
private suspend fun buildMediaPlaybackResume(): MediaPlaybackResume? {
    val saved = AppGraph.settings.resumePlayback.value ?: return null
    val library = AppGraph.library.tracks().filter { !it.audioUri.isNullOrBlank() }
    if (library.isEmpty()) return null
    val mode = ShuffleMode.entries.firstOrNull { it.name == saved.shuffleMode }
        ?: ShuffleMode.OFF
    val byId = library.associateBy { it.id.value }
    val playlists = AppGraph.playlists.all()
    val source = resolveResumeSourceQueue(
        saved = saved,
        library = library,
        playlists = playlists,
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
