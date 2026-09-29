/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** Imported files and their `.lrc` both live in the app's own Documents; nothing to grant. */
internal actual val lyricsFoldersNeedGrants: Boolean = false

@Composable
internal actual fun rememberLyricsFolderControls(onRefused: () -> Unit): LyricsFolderControls =
    LyricsFolderControls(folders = emptyList(), add = onRefused, remove = {})

@Composable
internal actual fun rememberLyricsSourcesRevision(): String = LYRICS_SOURCES_VERSION
