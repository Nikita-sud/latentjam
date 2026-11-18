/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.EmbeddedTagFacts
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Reads the tag facts the system scanner loses — every genre, the credited-artists list, the
 * original release year — from the track's own file, fully offline.
 *
 * Null means "could not read" (no reader for the container, unreadable file); an empty facts
 * value means "read fine, nothing there". The distinction matters to the cache: a definite
 * empty is remembered so the file is not re-read every launch, while an unreadable file stays
 * eligible for a retry. Implementations do their IO off the caller's thread.
 */
internal expect suspend fun readEmbeddedFacts(track: TrackDescriptor): EmbeddedTagFacts?

/**
 * [artworkUri] without any album cover it would fall back to, for a song whose file names no album
 * (see [GenreEnrichment]); unchanged where a platform's locators have no such fallback. With no
 * album to share a cover's version with, the locator follows the song's own file [revision].
 */
internal expect fun ownCoverOnly(artworkUri: String, revision: String?): String

/**
 * Whether the platform's scanner reports the name of a song's folder as its album when the file
 * names none (Android's MediaStore). Where it does not, an album always comes from the file.
 */
internal expect val scannerNamesAlbumsAfterFolders: Boolean
