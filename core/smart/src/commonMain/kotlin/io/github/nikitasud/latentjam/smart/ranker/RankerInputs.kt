/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackId

/** The on-device audio index as the ranker reads it: vectors for the tagger and the audio term, neighbours for R1. */
public interface AudioSource {
    public suspend fun vector(id: TrackId): FloatArray?
    public suspend fun neighbours(id: TrackId, k: Int): List<TrackId>

    public companion object {
        /** No audio index (tests, a cold engine). */
        public val NONE: AudioSource = object : AudioSource {
            override suspend fun vector(id: TrackId): FloatArray? = null
            override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = emptyList()
        }
    }
}

/**
 * The listener's own history at the seed, for the FULL model's PERSONAL features and retrievers and for the ladder's
 * recency rung. [listens] are plays (skips dropped), oldest first, before [seedMs]. A pair a → b counts as a
 * transition when the two listens are at most 15 minutes apart, differ, and b [HabitListen.countsAsNext] — the
 * listener's own pick (the Statistics page's rule; the research counted every pair, it had no origins).
 */
public class PersonalHistory(listens: List<HabitListen>, seedMs: Long, artistKey: (TrackId) -> String) {
    private val counts = HashMap<TrackId, Int>()
    private val lastAt = HashMap<TrackId, Long>()
    /** The position of each track's last play in the log (the ladder's recency order, independent of the clock). */
    private val lastIndex = HashMap<TrackId, Int>()
    private val activation: Map<TrackId, Double> = HabitSlots.activation(listens, seedMs)
    private val fromTrack = HashMap<TrackId, HashMap<TrackId, Int>>()
    private val fromArtist = HashMap<String, HashMap<TrackId, Int>>()
    private val seedAt = seedMs

    init {
        listens.forEachIndexed { i, l ->
            counts[l.trackId] = (counts[l.trackId] ?: 0) + 1
            lastAt[l.trackId] = l.startedAtMs
            lastIndex[l.trackId] = i
        }
        for (i in 1 until listens.size) {
            val a = listens[i - 1]
            val b = listens[i]
            if (b.startedAtMs - a.startedAtMs > HabitSlots.PAIR_GAP_MS || a.trackId == b.trackId || !b.countsAsNext) continue
            fromTrack.getOrPut(a.trackId) { HashMap() }.let { it[b.trackId] = (it[b.trackId] ?: 0) + 1 }
            val key = artistKey(a.trackId)
            if (key.isNotEmpty()) fromArtist.getOrPut(key) { HashMap() }.let { it[b.trackId] = (it[b.trackId] ?: 0) + 1 }
        }
    }

    public val isEmpty: Boolean get() = counts.isEmpty()

    public fun row(track: TrackId, anchor: TrackId, anchorArtist: String): PersonalRow {
        val heard = track in counts
        return PersonalRow(
            heard = heard,
            count = counts[track] ?: 0,
            ageSeconds = lastAt[track]?.let { (seedAt - it) / 1000.0 } ?: Double.POSITIVE_INFINITY,
            activation = activation[track] ?: Double.NaN,
            transTrack = fromTrack[anchor]?.get(track) ?: 0,
            transArtist = if (anchorArtist.isEmpty()) 0 else fromArtist[anchorArtist]?.get(track) ?: 0,
        )
    }

    /** Heard tracks by ACT-R activation, best first (FULL's R6). */
    public fun favourites(): List<TrackId> =
        activation.entries.sortedWith(compareByDescending<Map.Entry<TrackId, Double>> { it.value }.thenComparator { a, b ->
            SaltedKey.idOrder.compare(a.key.value, b.key.value)
        }).map { it.key }

    /** What followed [track] (FULL's R7), most often first. */
    public fun followers(track: TrackId): List<TrackId> =
        fromTrack[track].orEmpty().entries.sortedWith(compareByDescending<Map.Entry<TrackId, Int>> { it.value }.thenComparator { a, b ->
            SaltedKey.idOrder.compare(a.key.value, b.key.value)
        }).map { it.key }

    /** The log position of [track]'s last play, or null when never played (the ladder's last rung). */
    public fun lastPlayIndex(track: TrackId): Int? = lastIndex[track]

    public companion object {
        public val EMPTY: PersonalHistory = PersonalHistory(emptyList(), 0L) { "" }
    }
}

/** The similarity engine's audio index as an [AudioSource]; resolved lazily, so the ranker never waits on DI order. */
public class EngineAudioSource(private val engine: () -> io.github.nikitasud.latentjam.smart.SimilarityEngine?) : AudioSource {
    override suspend fun vector(id: TrackId): FloatArray? = engine()?.embedding(id)
    override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = engine()?.audioNeighbours(id, k).orEmpty().map { it.trackId }
}
