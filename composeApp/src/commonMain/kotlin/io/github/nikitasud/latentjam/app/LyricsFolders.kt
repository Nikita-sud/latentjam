/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** A folder the listener allowed LatentJam to read `.lrc` files from; [id] is opaque here. */
internal data class LyricsFolder(val id: String, val label: String)

/** The folders granted for `.lrc` files, with the system folder picker behind [add]. */
internal class LyricsFolderControls(
    val folders: List<LyricsFolder>,
    val add: () -> Unit,
    val remove: (LyricsFolder) -> Unit,
)

/**
 * Whether lyrics files beside the songs need folders granted one by one. Only Android 10 and
 * newer: scoped storage hides non-media files such as `.lrc` from the media permission. Older
 * Android reads them with the storage permission it already has, and iOS reads them beside the
 * imported file in the app's own Documents — neither shows the setting.
 */
internal expect val lyricsFoldersNeedGrants: Boolean

/**
 * Only called where [lyricsFoldersNeedGrants]. [onRefused] runs on the UI thread when the picker
 * cannot open, or when the picked folder cannot be matched to songs (another app's storage rather
 * than the device's own) or its access cannot be kept.
 */
@Composable
internal expect fun rememberLyricsFolderControls(onRefused: () -> Unit): LyricsFolderControls

/**
 * Everything outside a track's own file that decides its lyrics, as one opaque token: the
 * sidecar lookup's version and, on Android 10+, the granted folders. The lyrics search index
 * folds it into each entry's revision, so a newly granted folder re-reads the songs it had
 * cached as having no lyrics.
 */
@Composable
internal expect fun rememberLyricsSourcesRevision(): String

/** Bumped when the sidecar lookup changes what it can find; stale "no lyrics" entries re-read. */
internal const val LYRICS_SOURCES_VERSION = "lrc1"
