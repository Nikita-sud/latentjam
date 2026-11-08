/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

// iOS lets an app place nothing on the home screen itself; the menu item is hidden there.
internal actual fun playlistShortcutsSupported(): Boolean = false

internal actual suspend fun pinPlaylistShortcut(playlistId: String, name: String, coverUri: String?): Boolean = false

internal actual fun renamePlaylistShortcut(playlistId: String, name: String) = Unit

internal actual fun disablePlaylistShortcut(playlistId: String, message: String) = Unit
