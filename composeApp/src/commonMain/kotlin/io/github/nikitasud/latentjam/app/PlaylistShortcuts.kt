/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Home-screen shortcuts to a user playlist (issue #32). Android pins one through the launcher's own
 * "add to home screen" sheet; iOS has no such API for an app, so the menu item is not offered there.
 *
 * A tap on the shortcut opens the playlist's page; it never starts playback by itself.
 */
internal expect fun playlistShortcutsSupported(): Boolean

/**
 * Asks the launcher to pin a shortcut for the playlist. [coverUri] is the cover the playlist row
 * shows; without one (or when it cannot be read) the app icon is used. Returns false when the
 * launcher refused the request outright; the launcher's own sheet may still be declined later.
 */
internal expect suspend fun pinPlaylistShortcut(playlistId: String, name: String, coverUri: String?): Boolean

/** A renamed playlist's pinned shortcut carries the new name. No-op when none was pinned. */
internal expect fun renamePlaylistShortcut(playlistId: String, name: String)

/** A deleted playlist's pinned shortcut is greyed out by the launcher instead of opening nothing. */
internal expect fun disablePlaylistShortcut(playlistId: String, message: String)

/** A shortcut tap waiting for the UI: the id of the playlist to open, consumed once. */
internal object PlaylistShortcutRequests {
    val pending = MutableStateFlow<String?>(null)
}

/** The shortcut id for a playlist: stable across renames, so a later pin replaces the earlier one. */
internal fun playlistShortcutId(playlistId: String): String = "playlist-$playlistId"
