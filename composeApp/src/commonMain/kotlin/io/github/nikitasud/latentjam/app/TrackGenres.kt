/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Reads every genre embedded in the track's own file (FLAC Vorbis comments, ID3 `TCON`,
 * Ogg/Opus tags), fully offline.
 *
 * Null means "could not read" (no reader for the container, unreadable file); an empty list
 * means "read fine, no genres". The distinction matters to the cache: a definite empty is
 * remembered so the file is not re-read every launch, while an unreadable file stays eligible
 * for a retry. Implementations do their IO off the caller's thread.
 */
internal expect suspend fun readEmbeddedGenres(track: TrackDescriptor): List<String>?
