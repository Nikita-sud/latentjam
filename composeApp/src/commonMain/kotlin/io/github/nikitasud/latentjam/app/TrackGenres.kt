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
