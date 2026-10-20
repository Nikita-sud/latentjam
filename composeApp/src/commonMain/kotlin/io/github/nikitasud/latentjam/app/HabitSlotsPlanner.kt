/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.smart.HabitFlow
import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "Learn from my habits" (docs/habit-slots.md): gives tracks that really followed [seed] or its
 * artist the 1-based slots 1, 3, 5, 7 and 9 of SMART's [chain], those whose transition strength
 * outweighs how far they would pull the queue from the seed ([HabitFlow]; without one, every row
 * counts as coherent). Returns [chain] unchanged when none qualifies.
 */
internal suspend fun withHabitSlots(
    history: ListeningHistory,
    seed: TrackDescriptor,
    candidates: List<TrackDescriptor>,
    library: List<TrackDescriptor>,
    chain: List<TrackId>,
    seedMs: Long,
    flow: HabitFlow?,
): List<TrackId> = withContext(Dispatchers.Default) {
    val events = runCatching { history.recentEvents(SMART_HABIT_HISTORY_LIMIT) }.getOrNull()
        ?: return@withContext chain
    val artistKeys = HashMap<TrackId, String>(library.size + 1)
    for (track in library) artistKeys[track.id] = HabitSlots.artistKey(track)
    artistKeys[seed.id] = HabitSlots.artistKey(seed)
    val listens = habitListens(events.asReversed(), artistKeys.keys, seedMs)
    val artistKey = { id: TrackId -> artistKeys[id].orEmpty() }
    val rows = habitRows(listens, seed.id, seedMs, candidates.map(TrackDescriptor::id), artistKey, salt = seedMs)
    if (rows.isEmpty()) return@withContext chain
    val (byTrack, byArtist) = HabitSlots.transitionCounts(listens, seed.id, artistKey)
    val byId = candidates.associateBy(TrackDescriptor::id)
    val score = flow?.incoherence(seed, library)
    // No flow scorer judges nothing: every part unknown, which λ = 2 never lets through.
    val incoherence = rows.associateWith { id -> score?.let { s -> byId[id]?.let { s(it) } } ?: HabitFlow.UNKNOWN }
    val personal = HabitFlow.pick(rows, count = { habitCount(it, byTrack, byArtist) }, incoherence)
    // Aggregate on purpose: no title, artist or id leaves the app through the system log.
    println("SMART: habit slots placed ${personal.size} of ${rows.size} real transitions")
    // As FIT's arm: the chosen rows spread over the first ten slots by their own number (slots.py).
    if (personal.isEmpty()) chain else HabitSlots.interleave(personal, chain, personalSlots = personal.size)
}

/**
 * The listener's plays before [seedMs] of tracks in the library ([libraryIds]), oldest first.
 *
 * Skips are not listens (the reference's ListenBrainz listens have none), and only a pick — the
 * listener started this track (ListenStart.USER_PICK) — counts as what followed the previous
 * listen: a playlist's or album's own order, shuffle draws, skips into the queue, SMART's own
 * plays and listens logged before origins were (legacy lines) teach no habit. This is the
 * Statistics page's play-counting rule (docs/statistics-insights-proposal.md, "Pick", Addendum A).
 */
internal fun habitListens(
    oldestFirst: List<ListenEvent>,
    libraryIds: Set<TrackId>,
    seedMs: Long,
): List<HabitListen> = oldestFirst.mapNotNull { event ->
    if (event.skipped || event.startedAtMs >= seedMs || event.trackId !in libraryIds) return@mapNotNull null
    HabitListen(
        trackId = event.trackId,
        startedAtMs = event.startedAtMs,
        countsAsNext = event.origin?.start == ListenStart.USER_PICK,
    )
}.sortedBy(HabitListen::startedAtMs) // the log is in recording order; a merged or restored one may not be

/**
 * The transition list's rows among [candidates] that really followed the seed or its artist (a count
 * above zero), in the list's order. Rows the reference ranks only by ACT-R or its random key are left
 * to SMART: overall favourites unrelated to the seed made the queue swing between genres on the
 * owner's phone (FIT: genre breaks 28 % vs 16.5 %).
 */
internal fun habitRows(
    listens: List<HabitListen>,
    seed: TrackId,
    seedMs: Long,
    candidates: List<TrackId>,
    artistKey: (TrackId) -> String,
    salt: Long,
): List<TrackId> {
    if (listens.isEmpty()) return emptyList()
    val (byTrack, byArtist) = HabitSlots.transitionCounts(listens, seed, artistKey)
    return HabitSlots.transitionList(
        candidates = candidates.filter { it in byTrack || it in byArtist },
        seed = seed,
        seedMs = seedMs,
        listens = listens,
        artistKey = artistKey,
        tieKey = { saltedTieKey(it, salt) },
    )
}

/** c = track count + [HabitFlow.ARTIST_WEIGHT] · artist count (a direct transition counts in both). */
internal fun habitCount(track: TrackId, byTrack: Map<TrackId, Int>, byArtist: Map<TrackId, Int>): Double =
    (byTrack[track] ?: 0) + HabitFlow.ARTIST_WEIGHT * (byArtist[track] ?: 0)

/** A uniform `[0, 1)` key per track, fixed for one plan ([salt]) and different between plans. */
internal fun saltedTieKey(track: TrackId, salt: Long): Double {
    var z = salt xor (track.value.hashCode().toLong() * -0x61c8864680b583ebL)
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return (z ushr 11).toDouble() / (1L shl 53).toDouble()
}

/** The SMART history window (EngineNextTrackChooser's smartHistoryFor). */
private const val SMART_HABIT_HISTORY_LIMIT = 10_000

/** Freshness window when the model names none (the CORE default, PLAN_L1 §2c). */
private const val DEFAULT_FRESHNESS_WINDOW_MS = 2L * 3_600_000

/** Content key → its hash ([SaltedKey.contentHash]), kept across presses: a 10k library is hashed once, not per press. */
private val contentHashes = HashMap<String, String>()
private val contentHashLock = kotlinx.coroutines.sync.Mutex()

private suspend fun contentHashOf(t: TrackDescriptor): String {
    val key = io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentKey(t)
    return contentHashLock.withLock {
        contentHashes.getOrPut(key) {
            if (contentHashes.size >= 50_000) contentHashes.clear()
            io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentHash(key)
        }
    }
}

/**
 * The learned ranker's plan from [seed] (docs/smart-ranker.md): [candidates] minus what the listener heard in the
 * last 24 hours or earlier in this session (the novelty rule the models trained with), the queue's [recentIds] as the
 * song-version dedup window, the listener's history for FULL ([personal]) and the ladder's recency rung. [ruleOnly]
 * runs PLAN_FINAL §7.2's rule-only path. Returns null when the ranker failed; the caller counts it.
 */
internal suspend fun rankerPlan(
    ranker: io.github.nikitasud.latentjam.smart.ranker.SmartRanker,
    history: ListeningHistory,
    seed: TrackDescriptor,
    candidates: List<TrackDescriptor>,
    library: List<TrackDescriptor>,
    length: Int,
    seedMs: Long,
    press: Long = 0,
    recentIds: List<TrackId> = emptyList(),
    personal: Boolean = false,
    ruleOnly: Boolean = false,
    /** The listener's favourites with their heart times (null = unknown), read only for [personal] plans. */
    favourites: suspend () -> Map<TrackId, Long?> = { emptyMap() },
    /** The recent presses' shown content keys, newest first: (window ms, presses) → sets (SmartPlanLog.recentPresses). */
    presses: suspend (windowMs: Long, count: Int) -> List<Set<String>> = { _, _ -> emptyList() },
): io.github.nikitasud.latentjam.smart.ranker.PlanReport? = withContext(Dispatchers.Default) {
    try {
        val scope = library.ifEmpty { candidates }
        val libraryIds = scope.mapTo(HashSet(scope.size + candidates.size)) { it.id }
        candidates.mapTo(libraryIds) { it.id }
        val events = runCatching { history.recentEvents(SMART_HABIT_HISTORY_LIMIT) }.getOrNull().orEmpty()
        val listens = habitListens(events.asReversed(), libraryIds, seedMs)
        val novel = HabitSlots.novelty(listens, seedMs)
        val artistKeys = HashMap<TrackId, String>(scope.size + 1)
        for (t in scope) artistKeys[t.id] = HabitSlots.artistKey(t)
        // Hearts count only for the FULL model ("Learn from my habits").
        val personalHistory = io.github.nikitasud.latentjam.smart.ranker.PersonalHistory(
            listens, seedMs, favourites = if (personal) favourites() else emptyMap(),
        ) { artistKeys[it].orEmpty() }
        val fits = io.github.nikitasud.latentjam.smart.ranker.rankerFitsInHeap(minOf(candidates.size, 2_000))
        // PLAN_L1 §2c freshness: what recent presses showed (any seed), as candidate ids.
        val impressions = ranker.freshnessFor(personal, ruleOnly || !fits)?.let { f ->
            val sets = presses(f.windowMs ?: DEFAULT_FRESHNESS_WINDOW_MS, f.presses)
            if (sets.isEmpty()) emptyList() else {
                val wanted = sets.flatMapTo(HashSet()) { it }
                val ids = HashMap<String, TrackId>()
                for (t in candidates) {
                    val k = contentHashOf(t)
                    if (k in wanted) ids[k] = t.id
                }
                sets.map { s -> s.mapNotNullTo(HashSet()) { ids[it] } }
            }
        }.orEmpty()
        val report = ranker.plan(
            io.github.nikitasud.latentjam.smart.ranker.PlanRequest(
                seed = seed, library = scope, candidates = candidates, length = length, exclude = novel,
                recentIds = recentIds, press = press, history = personalHistory, personal = personal,
                ruleOnly = ruleOnly || !fits, impressions = impressions,
            ),
        )
        val (withEntity, listed, withLfm) = ranker.entityCoverage
        // Aggregate on purpose: no title, artist or id leaves the app through the system log.
        println(
            "SMART: ranker ${report.model} planned ${report.ids.size} (${report.fromModel} model, ${report.fromLadder} ladder" +
                "${if (report.timedOut) ", budget spent" else ""}) from ${report.poolSize} of ${candidates.size} candidates " +
                "at press $press (${novel.size} recent left out, ${report.imputed} imputed; library tracks with an entity " +
                "$withEntity, a co-listening list $listed, a LightFM vector $withLfm; audio ${report.audio.describe(report.poolSize)})",
        )
        report
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        println("SMART: ranker failed (${failure::class.simpleName}); the queue continues without it")
        null
    }
}
