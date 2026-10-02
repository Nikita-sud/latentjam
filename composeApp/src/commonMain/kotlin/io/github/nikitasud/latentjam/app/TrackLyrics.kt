/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Reads the track's lyrics, fully offline: those embedded in its own file, and a `.lrc` file
 * beside it (on Android 10+ only inside folders granted in Settings), chosen by
 * [io.github.nikitasud.latentjam.library.tags.SidecarLyrics.choose]. A sidecar problem of any kind
 * falls back to the embedded lyrics and is never reported.
 *
 * Returns null when neither source has lyrics. Search opts into read failures
 * so a temporarily unreadable file cannot be cached as having no lyrics. Main-safe: implementations do their IO off the caller's thread.
 */
@Composable
internal expect fun rememberLyricsReader(reportReadFailures: Boolean = false): suspend (TrackDescriptor) -> Lyrics?

/**
 * For each track, a short token that changes whenever a `.lrc` file the [rememberLyricsReader]
 * could read for it appears, changes or goes away — the candidates' sizes and modification
 * times, never their contents, and never anything read from the audio file. A track with no
 * such file, or none that can be seen (Android 10+ with no folder granted, songs from the iOS
 * Music library), maps to "". A track whose files could not be looked up this time is left out:
 * unknown, which is not the same as "none". A failure of the whole lookup throws.
 *
 * The lyrics search index takes these for the whole library once per pass, so it is one batch
 * call rather than a per-track one: implementations answer from as few system queries as the
 * platform allows, off the caller's thread, and never block composition — only the returned
 * function does any work.
 */
@Composable
internal expect fun rememberSidecarFingerprints(): suspend (List<TrackDescriptor>) -> Map<TrackId, String>
