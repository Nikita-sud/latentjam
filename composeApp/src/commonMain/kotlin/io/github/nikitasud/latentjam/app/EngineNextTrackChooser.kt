/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adapts the similarity engine into the playback layer's [NextTrackChooser] port — the ONLY place
 * the two meet, which is what keeps :core:smart and :core:playback mutually ignorant of each other.
 *
 * SMART is a *walk*, not a series of independent picks: the engine plans a chain of
 * [CHAIN_LENGTH] tracks from a seed, and that plan is what keeps a queue coherent — it tracks how
 * far it has drifted from the seed, which artists and titles it has spent, and how much the energy
 * jumped last hop. Asking for one nearest neighbour at a time throws all of that away.
 *
 * So the chain is planned once and then served in order. It is replanned when it runs out, or when
 * playback lands somewhere the plan did not predict (the user skipped, or picked a track by hand) —
 * that track becomes the seed of the next chain, which is what the user just asked for.
 *
 * Abstains (returns null, letting the controller fall back to a random pick) whenever the engine
 * cannot plan — not indexed yet, models unavailable — so SMART is always safe to leave switched on.
 */
class EngineNextTrackChooser(
    private val engine: SimilarityEngine,
) : NextTrackChooser {

    private val mutex = Mutex()
    private var planned = ArrayDeque<TrackId>()
    private var expectedNext: TrackId? = null

    override suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor? = mutex.withLock {
        if (expectedNext != null && expectedNext != current.id) {
            // Playback went somewhere the plan did not predict; the current track is the new intent.
            planned.clear()
        }
        if (planned.isEmpty()) {
            planned = ArrayDeque(engine.smartQueue(current, candidates, CHAIN_LENGTH))
            println("SMART: planned ${planned.size} tracks from ${current.title ?: current.id.value}")
        }

        // A planned track can vanish from the candidate pool (played meanwhile, or removed).
        while (planned.isNotEmpty()) {
            val next = planned.removeFirst()
            val chosen = candidates.firstOrNull { it.id == next }
            if (chosen != null) {
                expectedNext = chosen.id
                return@withLock chosen
            }
        }
        expectedNext = null
        println("SMART: no plan available — random fallback")
        null
    }

    private companion object {
        /**
         * Tracks planned per chain. Matches the length the chain's weighting was tuned at: long
         * enough for seed gravity and the artist-spacing rules to matter, short enough that the plan
         * still reflects what the listener picked rather than where it drifted.
         */
        const val CHAIN_LENGTH = 12
    }
}
