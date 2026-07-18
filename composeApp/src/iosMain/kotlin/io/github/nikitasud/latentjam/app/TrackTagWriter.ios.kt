/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Unavailable on iOS, and reported as such rather than silently doing nothing.
 *
 * Not blocked by the tag writer: that lives in `:core:library` commonMain and
 * already builds and runs for the iOS targets. What is missing is a file to
 * point it at. The iOS [io.github.nikitasud.latentjam.library.MusicLibrary] is
 * still a stub returning no tracks, so there is nothing here to edit — and when
 * it lands, its two candidate sources differ on whether editing is even
 * possible. Files the app owns in its own Documents directory are writable with
 * `FileManager` and need no consent step at all; items from the Apple Music
 * library via MPMediaQuery are not writable by an app under any circumstances.
 *
 * So this actual waits on that decision. When the first source is local files,
 * the implementation is `Id3Tags.buildUpdate` against an `NSFileHandle` and no
 * new platform machinery beyond it.
 */
@Composable
actual fun rememberTagWriter(
    onOutcome: (TagWriteOutcome) -> Unit,
): (TrackDescriptor, TagEdits) -> Unit = { _, _ -> onOutcome(TagWriteOutcome.Unavailable) }
