/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Learn from my habits" (docs/habit-slots.md): gives the listener's own top transition rows for
 * [seed] the 1-based slots 1, 3, 5, 7 and 9 of SMART's [chain]. Returns [chain] unchanged when the
 * history holds nothing the list can rank.
 */
internal suspend fun withHabitSlots(
    history: ListeningHistory,
    seed: TrackDescriptor,
    candidates: List<TrackDescriptor>,
    library: List<TrackDescriptor>,
    chain: List<TrackId>,
    seedMs: Long,
): List<TrackId> = withContext(Dispatchers.Default) {
    val events = runCatching { history.recentEvents(SMART_HABIT_HISTORY_LIMIT) }.getOrNull()
        ?: return@withContext chain
    val artistKeys = HashMap<TrackId, String>(library.size + 1)
    for (track in library) artistKeys[track.id] = HabitSlots.artistKey(track)
    artistKeys[seed.id] = HabitSlots.artistKey(seed)
    val listens = habitListens(events.asReversed(), artistKeys.keys, seedMs)
    val personal = habitRows(
        listens = listens,
        seed = seed.id,
        seedMs = seedMs,
        candidates = candidates.map(TrackDescriptor::id),
        artistKey = { artistKeys[it].orEmpty() },
        salt = seedMs,
    )
    if (personal.isEmpty()) return@withContext chain
    // Aggregate on purpose: no title, artist or id leaves the app through the system log.
    println("SMART: habit slots placed ${personal.size} of ${HabitSlots.PERSONAL_SLOTS}")
    HabitSlots.interleave(personal, chain)
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
 * The top [HabitSlots.PERSONAL_SLOTS] rows of the transition list among [candidates] the listener
 * has heard in [listens]. Unheard rows, which the reference ranks last by a random key, are left to
 * SMART: a sparse history must not put random tracks in the personal slots.
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
    val heard = listens.mapTo(HashSet()) { it.trackId }
    return HabitSlots.transitionList(
        candidates = candidates.filter { it in heard },
        seed = seed,
        seedMs = seedMs,
        listens = listens,
        artistKey = artistKey,
        tieKey = { saltedTieKey(it, salt) },
        limit = HabitSlots.PERSONAL_SLOTS,
    )
}

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
