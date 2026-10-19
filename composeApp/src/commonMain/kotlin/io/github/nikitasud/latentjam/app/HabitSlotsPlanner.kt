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
 * [seed] the 1-based slots 1, 3, 5, 7 and 9 of SMART's [chain], as many as really followed the seed
 * or its artist. Returns [chain] unchanged when none did.
 */
internal suspend fun withHabitSlots(
    history: ListeningHistory,
    seed: TrackDescriptor,
    candidates: List<TrackDescriptor>,
    library: List<TrackDescriptor>,
    chain: List<TrackId>,
    seedMs: Long,
    coherence: (TrackDescriptor) -> (TrackDescriptor) -> Boolean,
): List<TrackId> = withContext(Dispatchers.Default) {
    val events = runCatching { history.recentEvents(SMART_HABIT_HISTORY_LIMIT) }.getOrNull()
        ?: return@withContext chain
    val artistKeys = HashMap<TrackId, String>(library.size + 1)
    for (track in library) artistKeys[track.id] = HabitSlots.artistKey(track)
    artistKeys[seed.id] = HabitSlots.artistKey(seed)
    val listens = habitListens(events.asReversed(), artistKeys.keys, seedMs)
    val byId = candidates.associateBy(TrackDescriptor::id)
    val coherentWithSeed = coherence(seed)
    val personal = habitRows(
        listens = listens,
        seed = seed.id,
        seedMs = seedMs,
        candidates = candidates.map(TrackDescriptor::id),
        artistKey = { artistKeys[it].orEmpty() },
        salt = seedMs,
        coherent = { id -> byId[id]?.let(coherentWithSeed) ?: false },
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
 * The top [HabitSlots.PERSONAL_SLOTS] rows of the transition list among [candidates] that really
 * followed the seed or its artist (a count above zero), in the list's order. Rows the reference
 * ranks only by ACT-R or its random key are left to SMART: overall favourites unrelated to the seed
 * made the queue swing between genres on the owner's phone (FIT: genre breaks 28 % vs 16.5 %).
 * Of those, only rows [coherent] with the seed (HabitCoherence) are placed, deeper ones moving up:
 * real transitions alone still broke the queue's flow on FIT (jumps 6.1 % vs chain 0.6 %).
 * Fewer such rows leave their slots to SMART; none leaves SMART's queue unchanged.
 */
internal fun habitRows(
    listens: List<HabitListen>,
    seed: TrackId,
    seedMs: Long,
    candidates: List<TrackId>,
    artistKey: (TrackId) -> String,
    salt: Long,
    coherent: (TrackId) -> Boolean = { true },
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
    ).asSequence().filter(coherent).take(HabitSlots.PERSONAL_SLOTS).toList()
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
