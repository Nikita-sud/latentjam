/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

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
 * Abstains when neither the audio nor trusted-metadata path can plan. The controller leaves the
 * queue short in that case; it never disguises a random pick as SMART.
 */
class EngineNextTrackChooser(
    private val engine: SimilarityEngine,
    private val history: ListeningHistory,
    /** Live view of the playlists the listener marked "keep together in SMART". */
    private val companionGroups: () -> List<Set<TrackId>> = { emptyList() },
) : NextTrackChooser {

    private val mutex = Mutex()
    private var planned = ArrayDeque<TrackId>()
    private var expectedNext: TrackId? = null
    private var plannedWithGroups: List<Set<TrackId>> = emptyList()

    override suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor? = mutex.withLock {
        if (expectedNext != null && expectedNext != current.id) {
            // Playback went somewhere the plan did not predict; the current track is the new intent.
            planned.clear()
        }
        // Marking or unmarking a playlist takes effect on the NEXT hop, not after the previous
        // plan (up to CHAIN_LENGTH tracks) has run dry — the stale plan is discarded.
        val groups = companionGroups()
        if (groups != plannedWithGroups) {
            plannedWithGroups = groups
            planned.clear()
        }
        if (planned.isEmpty()) {
            val started = TimeSource.Monotonic.markNow()
            planned = ArrayDeque(
                engine.smartQueue(
                    current,
                    candidates,
                    CHAIN_LENGTH,
                    smartHistoryFor(history, current),
                    companionGroups(),
                ),
            )
            println(
                "SMART: planned ${planned.size} tracks from " +
                    "${current.title ?: current.id.value} in ${started.elapsedNow().inWholeMilliseconds} ms",
            )
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
        println("SMART: no local plan available — waiting for index")
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

/** Projects the private append-only history log into SMART's model-only value type. */
internal suspend fun smartHistoryFor(
    history: ListeningHistory,
    current: TrackDescriptor,
): List<SmartHistoryEvent> {
    val observed = history.recentEvents(SMART_HISTORY_LIMIT).asReversed().map { event ->
        val fraction = event.trackDurationMs
            ?.takeIf { it > 0 }
            ?.let { (event.playedMs.toDouble() / it).toFloat().coerceIn(0f, 1f) }
            ?: when {
                event.completed -> 1f
                event.skipped -> 0f
                else -> 0.5f
            }
        SmartHistoryEvent(
            trackId = event.trackId,
            startedAtMs = event.startedAtMs,
            playedFraction = fraction,
            completed = event.completed,
            skipped = event.skipped,
        )
    }
    // The history recorder emits only when playback leaves a track. Queue planning happens while
    // [current] is still playing, so add it as the latest positive intent explicitly.
    val now = maxOf(epochMillis(), (observed.lastOrNull()?.startedAtMs ?: Long.MIN_VALUE) + 1)
    return observed + SmartHistoryEvent(
        trackId = current.id,
        startedAtMs = now,
        playedFraction = 1f,
        completed = true,
        skipped = false,
    )
}

private const val SMART_HISTORY_LIMIT = 10_000
