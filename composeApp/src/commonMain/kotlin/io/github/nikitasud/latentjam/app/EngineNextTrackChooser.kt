/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NextTrackChooser
import io.github.nikitasud.latentjam.playback.SmartChoice
import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.history.SmartPresses
import io.github.nikitasud.latentjam.smart.ranker.PlanPick
import io.github.nikitasud.latentjam.smart.ranker.SaltedKey
import io.github.nikitasud.latentjam.smart.ranker.SmartRanker
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.continuesSmartPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Abstains when neither the audio nor trusted-metadata path can plan. It never disguises a random
 * pick as SMART — but abstention must not end the session either, so [continuation] supplies a
 * clearly labelled unheard track and the walk resumes as soon as the engine can answer.
 */
class EngineNextTrackChooser(
    private val engine: SimilarityEngine,
    private val history: ListeningHistory,
    /** Live view of the playlists the listener marked "keep together in SMART". */
    private val companionGroups: () -> List<Set<TrackId>> = { emptyList() },
    /** The "Learn from my habits" setting: personal slots in queues SMART starts from a seed. */
    private val learnFromHabits: () -> Boolean = { false },
    /** SMART's library, whose listens the habit list reads (not only what may still be queued). */
    private val library: () -> List<TrackDescriptor> = { emptyList() },
    /** How far a habit row would pull the queue from its seed; null scores every row coherent. */
    private val habitFlow: HabitFlow? = null,
    /** LW2 on the chain's habit list (FB build): pairs rejected twice after the seed are no habit; null = unweighted. */
    private val habitListenWeight: io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight? = null,
    /** The "learned ranker" setting: plans come from [ranker] instead of the engine's chain. */
    private val useRanker: () -> Boolean = { false },
    private val ranker: () -> SmartRanker? = { null },
    /** Whether the ranker's assets passed their manifest; false runs its rule-only path (PLAN_FINAL §7.2). */
    private val rankerHealthy: () -> Boolean = { true },
    /** "Learn from my habits": the ranker's FULL model (the listener's history as features) instead of CORE. */
    private val personalRanker: () -> Boolean = { false },
    /** The persisted press counters; null keeps every start at press 0. */
    private val presses: SmartPresses? = null,
    /** The on-device impression log (local only; nothing in ranking reads it). */
    private val smartLog: SmartPlanLog? = null,
    /** The listener's favourites with their heart times (null = unknown): FULL's is_favourite / favourited_days_ago. */
    private val favourites: suspend () -> Map<TrackId, Long?> = { emptyMap() },
    /** "Use favourites" (nested under "Learn from my habits"); off, the ranker sees no hearts. */
    private val useFavourites: () -> Boolean = { true },
    /** The listener's own playlists (app and imported M3U), each in its order: FULL plans only (fast 3.3). */
    private val userPlaylists: () -> List<List<TrackId>> = { emptyList() },
    /** The artist-variety setting for the learned ranker (ARTIST_VARIETY_RANKER_SCALES; 1 = the tuned values). */
    private val varietyScale: () -> Double = { 1.0 },
    /** Wall time; the offline product-check stand plans at a fixed simulated time. */
    private val clock: () -> Long = ::epochMillis,
) : NextTrackChooser {

    private val mutex = Mutex()
    /** The press of the SMART queue being served: set when a queue starts from its seed, reused by its top-ups. */
    private var queuePress = 0L
    /** Ranker failures and spent budgets in this SMART queue; at [BREAKER_LIMIT] the queue runs rule-only (§7.2). */
    private var queueFailures = 0
    /**
     * Audit 06 #11: "Learn from my habits" and "Use favourites" as they were at the press. Top-ups of one queue keep
     * them, so a queue never mixes CORE and FULL plans (a toggle takes effect at the next press).
     */
    private var queuePersonal = false
    private var queueFavourites = true
    private var queueModeSet = false
    /**
     * Audit 03 #1: the session a top-up continues — the queue's pressed seed and every row served from its plans since,
     * oldest first. A top-up on plan keeps the seed as its anchor and hands these rows to the ranker as its prefix.
     */
    private var queueSeedTrack: TrackDescriptor? = null
    private val servedInQueue = ArrayList<TrackId>()
    /** The impression log's name for the SMART queue being served, and how many plans it has had. */
    private var queueId = ""
    private var plansInQueue = 0
    /** The unserved rest of the current plan, each id with its index in that plan. */
    private var planned = ArrayDeque<IndexedValue<TrackId>>()
    private var expectedNext: TrackId? = null
    /** The queue's last track when [expectedNext] was served: where the queue ends if that row goes. */
    private var servedAfter: TrackId? = null
    /** The queued prefix distinguishes a continuation from a new start at the same track. */
    private var expectedPrecedingIds: List<TrackId> = emptyList()
    private var plannedWithGroups: List<Set<TrackId>> = emptyList()

    /** Artists of the last few continuations, so a run of them cannot become an album dump. */
    private val recentContinuationArtists = ArrayDeque<String>()

    override suspend fun choose(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): SmartChoice? = mutex.withLock {
        val sameQueue = continuesSmartPlan(expectedPrecedingIds, recentIds)
        // Removing the row served last, or moving it up, ends the queue where that row was served
        // from. The rest of the plan still follows there, and a removed row is not served again.
        val onPlan = current.id == expectedNext || current.id == servedAfter
        if (expectedNext != null && (!onPlan || !sameQueue)) {
            // A new queue can start at the expected track too; its preceding tracks identify it,
            // and they survive the listener's edits to the upcoming tracks (continuesSmartPlan).
            planned.clear()
        }
        // Marking or unmarking a playlist takes effect on the NEXT hop, not after the previous
        // plan (up to CHAIN_LENGTH tracks) has run dry — the stale plan is discarded.
        val groups = companionGroups()
        if (groups != plannedWithGroups) {
            plannedWithGroups = groups
            planned.clear()
        }
        suspend fun nextPlanned(): SmartChoice? {
            while (planned.isNotEmpty()) {
                val (index, id) = planned.removeFirst()
                val chosen = candidates.firstOrNull { it.id == id } ?: continue
                smartLog?.served(chosen.id)
                servedInQueue += chosen.id
                expectedNext = chosen.id
                servedAfter = current.id
                expectedPrecedingIds = (recentIds + current.id).takeLast(CHAIN_LENGTH)
                // A slot skipped as ineligible still counts: the position says how far from its
                // seed the plan put this track, which is what drift along a plan is measured by.
                return SmartChoice(chosen, planPosition = index + 1)
            }
            return null
        }
        // Eligibility can change without the playhead moving. Exhaust an obsolete cached plan
        // before deciding whether to replan, so available tracks are considered in this call.
        // On plan: the queue's last row is the one this chooser served last, in the same queue.
        val continuing = recentIds.isNotEmpty() && sameQueue && current.id == expectedNext && queueSeedTrack != null
        nextPlanned()?.let { return@withLock it }
        if (candidates.isNotEmpty()) {
            val started = TimeSource.Monotonic.markNow()
            val events = smartHistoryFor(history, current, clock)
            // A top-up can extend this chooser's plan or one the UI requested directly, even
            // after replacing the previous queue. The engine checks the preceding queued tracks
            // before carrying a walk; an unrelated seed starts fresh there.
            // Top-ups arrive on Main, and the first call loads and checksums ~32 MB of ranker
            // assets (the warm-up starts only seconds after launch): resolve it off Main.
            val learned = if (useRanker()) withContext(Dispatchers.Default) { ranker() } else null
            val healthy = learned != null && withContext(Dispatchers.Default) { rankerHealthy() }
            var picks: List<PlanPick>? = null
            var model = "chain"
            var plan = if (learned != null) {
                if (recentIds.isEmpty()) {
                    queuePress = presses?.let { runCatching { it.next(SaltedKey.contentKey(current)) }.getOrNull() } ?: 0L
                    queueFailures = 0
                    queuePersonal = personalRanker()
                    queueFavourites = useFavourites()
                    queueModeSet = true
                    queueSeedTrack = current
                    servedInQueue.clear()
                } else if (!continuing) {
                    // The listener moved the queue somewhere this plan did not go: that track seeds a fresh chain.
                    queueSeedTrack = current
                    servedInQueue.clear()
                }
                if (!queueModeSet) {
                    // A top-up of a queue this chooser did not start (a restored session): the toggles as they are now.
                    queuePersonal = personalRanker()
                    queueFavourites = useFavourites()
                    queueModeSet = true
                }
                val sessionSeed = if (continuing) queueSeedTrack ?: current else current
                val report = rankerPlan(
                    learned, history, sessionSeed, candidates, library(), RANKER_LENGTH,
                    seedMs = events.last().startedAtMs, press = queuePress, recentIds = recentIds,
                    personal = queuePersonal, ruleOnly = !healthy || queueFailures >= BREAKER_LIMIT,
                    favourites = if (queueFavourites) favourites else NO_FAVOURITES,
                    presses = { window, count ->
                        smartLog?.recentPresses(clock(), window, count, excludeQueue = queueId.takeIf { recentIds.isNotEmpty() })
                            .orEmpty()
                    },
                    engagedKeys = { smartLog?.engagedKeys().orEmpty() },
                    prefix = if (continuing) servedInQueue.toList() else emptyList(),
                    playlists = userPlaylists,
                    varietyScale = varietyScale(),
                    companionGroups = groups,
                )
                if (report == null || report.timedOut) queueFailures++
                picks = report?.picks
                model = report?.model ?: "none"
                report?.ids.orEmpty()
            } else {
                // The chain keeps no ranker session. Left as it was, a ranker turned back on mid-queue
                // would continue a plan from an earlier, unrelated seed (with this queue's rows as its
                // prefix); cleared, its next top-up seeds at the current track, as a restored queue does.
                queueSeedTrack = null
                servedInQueue.clear()
                queueModeSet = false
                engine.continueSmartQueue(
                    current, candidates, CHAIN_LENGTH, events, groups, precedingTrackIds = recentIds,
                )
            }
            // A queue SMART starts from a seed is that seed alone (the controllers replace the
            // queue with the tapped track); top-ups of a running queue keep SMART's own order.
            if (learned == null && recentIds.isEmpty() && plan.isNotEmpty() && learnFromHabits()) {
                plan = withHabitSlots(
                    history, current, candidates, library(), plan, seedMs = events.last().startedAtMs, flow = habitFlow,
                    lw = habitListenWeight, artistKeyOf = engine::artistKeyOf,
                )
            }
            planned = ArrayDeque(plan.withIndex().toList())
            smartLog?.let { log ->
                if (recentIds.isEmpty() || queueId.isEmpty()) {
                    val seedHash = SaltedKey.contentHash(SaltedKey.contentKey(current)).take(12)
                    queueId = "$seedHash.${if (learned != null) queuePress.toString() else "c"}.${clock()}"
                    plansInQueue = 0
                } else {
                    plansInQueue++
                }
                val wanted = plan.toHashSet()
                val keys = HashMap<TrackId, String>(plan.size * 2)
                for (t in candidates) if (t.id in wanted) keys[t.id] = SaltedKey.contentHash(SaltedKey.contentKey(t))
                val rows = plan.mapIndexed { i, id ->
                    val pick = picks?.getOrNull(i)
                    SmartPlanRow(
                        trackId = id,
                        trackKey = keys[id] ?: "",
                        score = pick?.score ?: Double.NaN,
                        probability = pick?.probability ?: 1.0,
                        tie = pick?.tie ?: false,
                        source = pick?.source ?: model,
                    )
                }
                log.planned(queueId, plansInQueue, rankOffset = recentIds.size, model = model, rows = rows)
            }
            // Diagnostics stay aggregate on purpose: no title, artist or track id goes to the
            // system log. On Android that log is plain logcat, which other apps could read on
            // older releases, and the app promises that nothing about what is played leaves it.
            println(
                "SMART: planned ${planned.size} slots ($CHAIN_LENGTH-track chain) from " +
                    "${candidates.size} candidates in " +
                    "${started.elapsedNow().inWholeMilliseconds} ms",
            )
        }

        // At most one new plan per call, including when the engine itself returns no usable ids.
        nextPlanned()?.let { return@withLock it }
        expectedNext = null
        servedAfter = null
        expectedPrecedingIds = emptyList()
        println("SMART: no eligible local plan candidates=${candidates.size}")
        null
    }

    /**
     * Not a recommendation, and never dressed as one: the longest-unheard track still available.
     *
     * The controller asks for this only after [choose] abstained, labels the row, and comes back
     * to [choose] at the very next transition — so this keeps a session alive while the index
     * warms up or a plan momentarily fails, instead of leaving the listener in silence.
     */
    override suspend fun continuation(
        current: TrackDescriptor,
        recentIds: List<TrackId>,
        candidates: List<TrackDescriptor>,
    ): TrackDescriptor? = mutex.withLock {
        val lastPlayedAtMs = runCatching { history.stats() }
            .getOrNull()
            ?.mapValues { (_, stats) -> stats.lastPlayedAtMs }
            .orEmpty()
        val chosen = smartContinuationTrack(
            candidates = candidates,
            lastPlayedAtMs = lastPlayedAtMs,
            avoidArtists = recentContinuationArtists.toSet(),
        ) ?: return@withLock null
        continuationArtistKey(chosen)?.let { artist ->
            recentContinuationArtists.addLast(artist)
            while (recentContinuationArtists.size > CONTINUATION_ARTIST_SPACING) {
                recentContinuationArtists.removeFirst()
            }
        }
        chosen
    }

    private companion object {
        val NO_FAVOURITES: suspend () -> Map<TrackId, Long?> = { emptyMap() }

        /**
         * Tracks planned per chain. Matches the length the chain's weighting was tuned at: long
         * enough for seed gravity and the artist-spacing rules to matter, short enough that the plan
         * still reflects what the listener picked rather than where it drifted.
         */
        const val CHAIN_LENGTH = 12

        /** Ranker failures or spent budgets in one queue before it runs the rule-only path (§7.2: k = 3). */
        const val BREAKER_LIMIT = 3

        /** Tracks the learned ranker plans per call: the 20-row queues it was evaluated on (proto_ranker2.py). */
        const val RANKER_LENGTH = 20

        /** How many continuations must pass before the same artist may continue the queue again. */
        const val CONTINUATION_ARTIST_SPACING = 3
    }
}

/**
 * Picks the track that keeps SMART playing when the recommender cannot: the one the listener has
 * not heard, or heard longest ago, skipping [avoidArtists] so consecutive continuations do not
 * walk an album end to end.
 *
 * Deliberately deterministic — never random. Randomness here would be indistinguishable from the
 * shuffle SMART exists to replace, and a listener who reports "it played something odd" deserves
 * an answer that can be reproduced. Library order breaks ties, and the artist filter yields rather
 * than returning nothing when every remaining candidate shares one artist: silence is the worse
 * outcome. `null` means the pool really is empty, which ends the queue honestly.
 */
internal fun smartContinuationTrack(
    candidates: List<TrackDescriptor>,
    lastPlayedAtMs: Map<TrackId, Long>,
    avoidArtists: Set<String>,
): TrackDescriptor? {
    if (candidates.isEmpty()) return null
    // Stable sort: equally unheard candidates keep the order the library handed over.
    val ordered = candidates.sortedBy { track -> lastPlayedAtMs[track.id] ?: Long.MIN_VALUE }
    return ordered.firstOrNull { track ->
        continuationArtistKey(track)?.let { it !in avoidArtists } ?: true
    } ?: ordered.first()
}

/** Null for an unknown artist: those tracks share no artist and must not be spaced as if they did. */
private fun continuationArtistKey(track: TrackDescriptor): String? =
    track.artist?.trim()?.takeIf(String::isNotEmpty)?.lowercase()

/**
 * Plan positions for a SMART plan the app plays as an ordinary queue — a For You or Map journey —
 * numbered as [EngineNextTrackChooser] numbers its own: the first slot after the seed is 1, and a
 * slot the caller could not resolve to a track still takes its place in the count.
 */
internal fun smartPlanPositions(plan: List<TrackId>): Map<TrackId, Int> =
    plan.withIndex().associate { (index, id) -> id to index + 1 }

/** Projects the private append-only history log into SMART's model-only value type. */
internal suspend fun smartHistoryFor(
    history: ListeningHistory,
    current: TrackDescriptor,
    clock: () -> Long = ::epochMillis,
): List<SmartHistoryEvent> = withContext(Dispatchers.Default) {
    // This can project up to 10k persisted events. Queue top-up is launched by the playback
    // controller's main-thread scope, so doing the copy/map before the engine's own dispatcher is
    // entered made a track transition capable of consuming a UI frame on a large history log.
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
    val now = maxOf(clock(), (observed.lastOrNull()?.startedAtMs ?: Long.MIN_VALUE) + 1)
    observed + SmartHistoryEvent(
        trackId = current.id,
        startedAtMs = now,
        playedFraction = 1f,
        completed = true,
        skipped = false,
    )
}

private const val SMART_HISTORY_LIMIT = 10_000
