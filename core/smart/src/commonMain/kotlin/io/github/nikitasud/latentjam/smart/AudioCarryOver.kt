/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * A verified tag-only write of a track's file. Its audio is proven unchanged, so the audio vector
 * made from [oldRevision] still describes it once the file shows up with a new revision and
 * exactly [newLength] bytes.
 */
public data class AudioCarryOver(val trackId: TrackId, val oldRevision: String?, val newLength: Long)

/** [applied]: vectors re-keyed. [settled]: carry-overs done with, applied or found not to match. */
public data class AudioCarryOverResult(val applied: Set<TrackId>, val settled: Set<TrackId>)
