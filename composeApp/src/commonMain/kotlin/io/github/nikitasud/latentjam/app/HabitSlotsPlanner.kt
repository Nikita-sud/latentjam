/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.epochMillis
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
    /** PLAN_LISTENW: each listen's credit as what followed (null = 1, every model without listenWeight). */
    credit: ((ListenEvent) -> Double)? = null,
): List<HabitListen> = oldestFirst.mapNotNull { event ->
    if (event.skipped || event.startedAtMs >= seedMs || event.trackId !in libraryIds) return@mapNotNull null
    HabitListen(
        trackId = event.trackId,
        startedAtMs = event.startedAtMs,
        countsAsNext = event.origin?.start == ListenStart.USER_PICK,
        credit = credit?.invoke(event) ?: 1.0,
    )
}.sortedBy(HabitListen::startedAtMs) // the log is in recording order; a merged or restored one may not be

/** The ListenBrainz listen threshold's cap (min(D/2, 4 min)). */
private const val LB_LISTEN_CAP_MS: Long = 240_000L

/** PLAN_LISTENW: the ListenBrainz threshold min(D/2, 240 s) of [event] (240 s when the duration is unknown). */
private fun listenThresholdMs(event: ListenEvent): Long =
    event.trackDurationMs?.takeIf { it > 0 }?.let { minOf(it / 2, LB_LISTEN_CAP_MS) } ?: LB_LISTEN_CAP_MS

/**
 * PLAN_LISTENW: how much a play credits the transition into it — 0 up to [zeroMs] heard, 1 for a completed play or from
 * the ListenBrainz threshold, linear in between. An import or a legacy line (no listen time) is neutral: 1.
 */
internal fun listenCredit(event: ListenEvent, zeroMs: Long): Double {
    val heard = event.listenedMs ?: return 1.0
    if (event.completed) return 1.0
    val threshold = listenThresholdMs(event)
    if (heard >= threshold) return 1.0
    if (heard <= zeroMs) return 0.0
    return (heard - zeroMs).toDouble() / (threshold - zeroMs)
}

/**
 * PLAN_LISTENW: pair-level negatives (owner: a skip means "doesn't fit HERE" — it lowers A → B, never B elsewhere). An
 * abandon of B (picked or auto-advanced, not completed, heard under the ListenBrainz threshold) after A, the last
 * listen before it within [HabitSlots.PAIR_GAP_MS], adds σ(([midS] − s) / [tauS]) to A → B. Imports never reject.
 */
internal fun abandonNegatives(
    oldestFirst: List<ListenEvent>,
    libraryIds: Set<TrackId>,
    seedMs: Long,
    lw: io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight,
): Map<Pair<TrackId, TrackId>, Pair<Double, Double>> {
    val events = oldestFirst.filter { it.startedAtMs < seedMs && it.trackId in libraryIds }.sortedBy(ListenEvent::startedAtMs)
    val out = HashMap<Pair<TrackId, TrackId>, Pair<Double, Double>>()
    for (j in 1 until events.size) {
        val b = events[j]
        val heard = b.listenedMs ?: continue
        val start = b.origin?.start
        // PLAN_LISTENW3: a picked rejection weighs 1, an autoplay one lw.autoplayWeight (0 = ignored)
        val weight = when (start) { ListenStart.USER_PICK -> 1.0; ListenStart.AUTO_ADVANCE -> lw.autoplayWeight; else -> 0.0 }
        if (weight <= 0.0 || b.completed || heard >= listenThresholdMs(b)) continue
        val a = (j - 1 downTo 0).firstOrNull { !events[it].skipped }?.let { events[it] }
            ?.takeIf { b.startedAtMs - it.startedAtMs <= HabitSlots.PAIR_GAP_MS && it.trackId != b.trackId } ?: continue
        val n = 1.0 / (1.0 + kotlin.math.exp(-(lw.midS - heard / 1000.0) / lw.tauS))
        val key = a.trackId to b.trackId
        val (sum, count) = out[key] ?: (0.0 to 0.0)
        out[key] = (sum + weight * n) to (count + weight)
    }
    return out
}

/**
 * The personal history a plan reads (HabitSlotsPlanner and the stand's habit check share it): listens with their
 * credits, vc21's extra transitions (credited with B's play under listenWeight) and the pair negatives.
 */
internal fun personalHistoryOf(
    events: List<ListenEvent>,
    libraryIds: Set<TrackId>,
    seedMs: Long,
    favourites: Map<TrackId, Long?>,
    skipPairs: Boolean,
    unskippedOnly: Boolean,
    lw: io.github.nikitasud.latentjam.smart.ranker.RankerModel.ListenWeight?,
    graph: io.github.nikitasud.latentjam.smart.ranker.TransGraph? = null,
    artistKey: (TrackId) -> String,
): io.github.nikitasud.latentjam.smart.ranker.PersonalHistory {
    val credit = lw?.let { w -> { e: ListenEvent -> listenCredit(e, w.zeroMs) } }
    val extras = if (skipPairs) habitSkipTransitionEvents(events, libraryIds, seedMs, unskippedOnly) else emptyList()
    // PLAN_LISTENW2: the pair's rejection count (the abandons abandonNegatives counts, early ones included) sets its weight
    val rejections = if (lw != null && lw.pairMode != "all") abandonNegatives(events, libraryIds, seedMs, lw) else emptyMap()
    val pairWeight: ((TrackId, TrackId) -> Double)? = when (lw?.pairMode) {
        "gate2" -> { a, b -> if ((rejections[a to b]?.second ?: 0.0) >= 2.0 - 1e-9) 1.0 else 0.0 }
        "shrink" -> { a, b -> (rejections[a to b]?.second ?: 0.0).let { r -> r / (r + lw.kappa) } }
        else -> null
    }
    return io.github.nikitasud.latentjam.smart.ranker.PersonalHistory(
        habitListens(events, libraryIds, seedMs, credit), seedMs, favourites,
        extraTransitions = extras.map { it.first to it.second },
        extraCredits = credit?.let { c -> extras.map { c(it.third) } },
        pairNegatives = if (lw != null && lw.negWeight > 0.0) rejections.ifEmpty { abandonNegatives(events, libraryIds, seedMs, lw) } else emptyMap(),
        listenWeight = lw,
        pairWeight = pairWeight,
        graph = graph,
        artistKey = artistKey,
    )
}

/**
 * vc21 (the owner's deliberate A → B tests, read from the phone's log): extra transitions [habitListens] misses, for B a
 * pick (ListenStart.USER_PICK):
 * - A → B when A is B's immediate predecessor, A was skipped and B started at most [HabitSlots.PAIR_GAP_MS] after A;
 * - A → B when A is the last real play before B (at least [BRIDGE_PLAY_MS] heard and not skipped), every play between
 *   them was skipped or shorter than that (quick skips, brief auto-advances), and B started at most [BRIDGE_GAP_MS]
 *   after A ended.
 * A pair [habitListens] already counts (B's previous real listen, both not skipped) is left out.
 * [unskippedOnly] (PLAN_NB narrow arm): only pairs into a B that was not itself skipped.
 */
internal fun habitSkipTransitions(
    oldestFirst: List<ListenEvent>,
    libraryIds: Set<TrackId>,
    seedMs: Long,
    unskippedOnly: Boolean = false,
): List<Pair<TrackId, TrackId>> = habitSkipTransitionEvents(oldestFirst, libraryIds, seedMs, unskippedOnly).map { it.first to it.second }

/** [habitSkipTransitions] with B's play (PLAN_LISTENW credits the pair with it). */
internal fun habitSkipTransitionEvents(
    oldestFirst: List<ListenEvent>,
    libraryIds: Set<TrackId>,
    seedMs: Long,
    unskippedOnly: Boolean = false,
): List<Triple<TrackId, TrackId, ListenEvent>> {
    val events = oldestFirst.filter { it.startedAtMs < seedMs && it.trackId in libraryIds }.sortedBy(ListenEvent::startedAtMs)
    val out = ArrayList<Triple<TrackId, TrackId, ListenEvent>>()
    for (j in 1 until events.size) {
        val b = events[j]
        if (b.origin?.start != ListenStart.USER_PICK || (unskippedOnly && b.skipped)) continue
        // What habitListens already pairs: B's previous non-skipped listen, when B itself is not skipped.
        val normal = if (b.skipped) null else (j - 1 downTo 0).firstOrNull { !events[it].skipped }?.let { events[it] }
            ?.takeIf { b.startedAtMs - it.startedAtMs <= HabitSlots.PAIR_GAP_MS }?.trackId
        val found = LinkedHashSet<TrackId>()
        val p = events[j - 1]
        if (p.skipped && b.startedAtMs - p.startedAtMs <= HabitSlots.PAIR_GAP_MS) found += p.trackId
        var i = j - 1
        while (i >= 0 && (events[i].skipped || events[i].effectiveListenedMs < BRIDGE_PLAY_MS)) i--
        if (i >= 0) {
            val a = events[i]
            val end = a.startedAtMs + a.effectiveListenedMs
            if (b.startedAtMs - end <= BRIDGE_GAP_MS) found += a.trackId
        }
        for (x in found) if (x != b.trackId && x != normal) out += Triple(x, b.trackId, b)
    }
    return out
}

/** vc21: a play shorter than this (or skipped) bridges A → B; B within [BRIDGE_GAP_MS] of A's end. */
internal const val BRIDGE_PLAY_MS: Long = 45_000L
internal const val BRIDGE_GAP_MS: Long = 3L * 60L * 1_000L

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

/** The per-press diagnostics log (Android external files dir): `adb pull` reaches it without run-as. */
internal const val SMART_PRESS_DIAG = "smart_press_diag.jsonl"

private fun pressDiagnostics(
    seed: TrackDescriptor,
    report: io.github.nikitasud.latentjam.smart.ranker.PlanReport,
    seedMs: Long,
    press: Long,
    personal: Boolean,
    ruleOnly: Boolean,
    varietyScale: Double,
    playlists: () -> List<List<TrackId>>,
    listens: Int,
): String = buildString {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val a = report.audio
    append("{\"atMs\":").append(epochMillis()).append(",\"seedMs\":").append(seedMs).append(",\"seed\":").append(q(seed.id.value))
    append(",\"press\":").append(press).append(",\"personal\":").append(personal).append(",\"ruleOnly\":").append(ruleOnly)
    append(",\"model\":").append(q(report.model)).append(",\"varietyScale\":").append(varietyScale)
    append(",\"playlists\":").append(if (personal) playlists().size else 0).append(",\"listens\":").append(listens)
    append(",\"poolSize\":").append(report.poolSize).append(",\"fromModel\":").append(report.fromModel)
    append(",\"fromLadder\":").append(report.fromLadder).append(",\"timedOut\":").append(report.timedOut).append(",\"imputed\":").append(report.imputed)
    append(",\"audio\":{\"seedVector\":").append(a.seedVector).append(",\"seedEnergy\":").append(a.seedEnergy)
    append(",\"poolVectors\":").append(a.poolVectors).append(",\"poolEnergies\":").append(a.poolEnergies)
    append(",\"r1\":").append(a.r1Neighbours).append(",\"timeouts\":[").append(a.timedOut.sorted().joinToString(",") { q(it) }).append("]}")
    append(",\"sources\":{").append(report.sourceCounts.entries.joinToString(",") { (k, v) -> "${q(k)}:$v" }).append("}")
    append(",\"stats\":{").append(report.stats.entries.joinToString(",") { (k, v) -> "${q(k)}:$v" }).append("}")
    append(",\"picks\":[").append(report.ids.joinToString(",") { q(it.value) }).append("]")
    append(",\"pickSources\":[").append(report.picks.joinToString(",") { q(it.source) }).append("]}")
}

/** Plays handed to the ranker as the album-session context (it reads only the last two within 30 min). */
private const val RECENT_PLAYS = 8

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
    /** Content keys of shown tracks the listener then played (SmartPlanLog.engagedKeys). */
    engagedKeys: suspend () -> Set<String> = { emptySet() },
    /** Audit 03 #1: the rows a top-up continues after [seed], oldest first (empty = a fresh plan). */
    prefix: List<TrackId> = emptyList(),
    /** Fast 3.3: the listener's own playlists (app and imported M3U), read only for [personal] plans. */
    playlists: () -> List<List<TrackId>> = { emptyList() },
    /** The artist-variety setting as the ranker's multiplier on its artist terms. */
    varietyScale: Double = 1.0,
): io.github.nikitasud.latentjam.smart.ranker.PlanReport? = withContext(Dispatchers.Default) {
    try {
        val scope = library.ifEmpty { candidates }
        val libraryIds = scope.mapTo(HashSet(scope.size + candidates.size)) { it.id }
        candidates.mapTo(libraryIds) { it.id }
        val events = runCatching { history.recentEvents(SMART_HABIT_HISTORY_LIMIT) }.getOrNull().orEmpty()
        val listens = habitListens(events.asReversed(), libraryIds, seedMs)
        val novel = HabitSlots.novelty(listens, seedMs)
        // Fast track 2 soft novelty: ms since each novelty id's last play (read only by a model with softNovelty).
        val lastPlay = HashMap<TrackId, Long>(novel.size * 2)
        for (l in listens) if (l.trackId in novel) lastPlay[l.trackId] = maxOf(lastPlay[l.trackId] ?: Long.MIN_VALUE, l.startedAtMs)
        val noveltyAges = lastPlay.mapValues { (_, at) -> maxOf(0L, seedMs - at) }
        val artistKeys = HashMap<TrackId, String>(scope.size + 1)
        for (t in scope) artistKeys[t.id] = HabitSlots.artistKey(t)
        // Hearts count only for the FULL model ("Learn from my habits").
        val personalHistory = personalHistoryOf(
            events.asReversed(), libraryIds, seedMs, favourites = if (personal) favourites() else emptyMap(),
            skipPairs = ranker.skipPairsFor(personal), unskippedOnly = ranker.skipPairsUnskippedOnlyFor(personal),
            lw = ranker.listenWeightFor(personal),
            // PLAN_TRANSGRAPH: a FULL model with a transGraph block reads every play (skips and listen time included).
            graph = ranker.transGraphFor(personal)?.let { cfg ->
                io.github.nikitasud.latentjam.smart.ranker.TransGraph(
                    events.asReversed().filter { it.startedAtMs < seedMs && it.trackId in libraryIds }.map {
                        io.github.nikitasud.latentjam.smart.ranker.PlayEvent(
                            it.trackId, it.startedAtMs, it.effectiveListenedMs, it.trackDurationMs ?: 0L, it.skipped, it.origin?.start?.name,
                            // An import (no origin, no listen time, no skip) — what a ListenBrainz importer writes.
                            imported = it.origin == null && it.listenedMs == null && !it.skipped,
                        )
                    },
                    seedMs, cfg,
                )
            },
        ) { artistKeys[it].orEmpty() }
        val fits = io.github.nikitasud.latentjam.smart.ranker.rankerFitsInHeap(minOf(candidates.size, 2_000))
        // PLAN_L1 §2c freshness: what recent presses showed (any seed), as candidate ids.
        val freshness = ranker.freshnessFor(personal, ruleOnly || !fits)
        // Fast 3 freshness.engaged = exempt: what the listener played after being shown it keeps no impression weight.
        val engaged = if (freshness?.engagedExempt == true) {
            val keys = engagedKeys()
            if (keys.isEmpty()) emptySet() else candidates.filterTo(ArrayList()) { contentHashOf(it) in keys }.mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }
        val impressions = freshness?.let { f ->
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
                ruleOnly = ruleOnly || !fits, impressions = impressions, excludeAgeMs = noveltyAges, engaged = engaged,
                seedMs = seedMs, prefix = prefix, playlists = if (personal) playlists() else emptyList(),
                varietyScale = varietyScale,
                // §19 AS: the session's plays before the press (the ranker reads the last two within 30 min).
                recentPlays = listens.takeLast(RECENT_PLAYS).map { it.trackId to it.startedAtMs },
            ),
        )
        // Test builds: one aggregate line per press in the app's external files dir (ids only; nothing leaves the phone).
        appendDebugLine(SMART_PRESS_DIAG, pressDiagnostics(seed, report, seedMs, press, personal, ruleOnly || !fits, varietyScale, playlists, listens.size))
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
