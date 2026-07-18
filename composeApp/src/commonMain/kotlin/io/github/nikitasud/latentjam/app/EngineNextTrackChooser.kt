/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.smart.ListeningContext
import io.github.nikitasud.latentjam.smart.NextTrackResult
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Adapts the similarity engine into the playback layer's [NextTrackChooser]
 * port — the ONLY place the two meet, which is what keeps :core:smart and
 * :core:playback mutually ignorant of each other.
 *
 * Abstains (returns `null`, triggering the controller's random fallback) for
 * every non-[NextTrackResult.Match] outcome — including the current stub
 * backends' `ModelUnavailable` — so SMART mode is safely usable today and
 * upgrades in place the moment the real model lands.
 */
class EngineNextTrackChooser(
    private val engine: SimilarityEngine,
) : NextTrackChooser {

    override suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor? {
        val result = engine.nextTrack(
            ListeningContext(seed = current, recentTrackIds = recentIds),
        )
        val match = result as? NextTrackResult.Match ?: return null
        return candidates.firstOrNull { it.id == match.trackId }
    }
}
