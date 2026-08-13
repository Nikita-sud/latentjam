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
        runCatching {
            val tracks = AppGraph.library.tracks()
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
        }.getOrNull()
    }
}
