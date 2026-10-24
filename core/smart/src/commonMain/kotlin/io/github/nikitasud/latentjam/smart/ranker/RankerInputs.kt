/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.HabitListen
import io.github.nikitasud.latentjam.smart.HabitSlots
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.floor
import kotlin.math.ln

/** The on-device audio index as the ranker reads it: vectors for the tagger and the audio term, neighbours for R1. */
public interface AudioSource {
    public suspend fun vector(id: TrackId): FloatArray?
    public suspend fun neighbours(id: TrackId, k: Int): List<TrackId>

    /** e = the semantic head's energy.high − energy.low on the stored A0 vector (PLAN_AUDIO_ON §8.8); null = unknown. */
    public suspend fun energy(id: TrackId): Double? = null

    /** The vectors of [ids] in one read (absent = not indexed); the default asks [vector] per id. */
    public suspend fun vectors(ids: Collection<TrackId>): Map<TrackId, FloatArray> =
        ids.mapNotNull { id -> vector(id)?.let { id to it } }.toMap()

    /** The e of [ids] in one read (absent = unknown); the default asks [energy] per id. */
    public suspend fun energies(ids: Collection<TrackId>): Map<TrackId, Double> =
        ids.mapNotNull { id -> energy(id)?.let { id to it } }.toMap()

    /** Fast 4: a cached audio head's output (continuity heads, e.g. danceability); null = unknown. */
    public suspend fun head(name: String, id: TrackId): Double? = null

    /** The stored metadata-text vectors of [ids] (the cold fix's semChain); empty without a text index. */
    public suspend fun textVectors(ids: Collection<TrackId>): Map<TrackId, FloatArray> = emptyMap()

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
public class PersonalHistory(
    listens: List<HabitListen>,
    seedMs: Long,
    /** The listener's favourites, each with its heart time (null = unknown); empty without "Learn from my habits". */
    private val favourites: Map<TrackId, Long?> = emptyMap(),
    /** vc20: A → B picks the listens leave out (A or B skipped; habitSkipTransitions), counted as transitions too. */
    extraTransitions: List<Pair<TrackId, TrackId>> = emptyList(),
    /** PLAN_LISTENW: each extra transition's credit (B's listen credit), parallel to [extraTransitions]; null = 1 each. */
    extraCredits: List<Double>? = null,
    /** PLAN_LISTENW: pair-level negatives A → B (summed σ weight, number of rejections) from abandons of B after A. */
    private val pairNegatives: Map<Pair<TrackId, TrackId>, Pair<Double, Int>> = emptyMap(),
    /** PLAN_LISTENW: the listen weighting (null = counts as before: every listen 1, no negatives). */
    private val listenWeight: RankerModel.ListenWeight? = null,
    /** PLAN_LISTENW2: how much of the weighting each pair A → B gets (null = all of it, PLAN_LISTENW). */
    private val pairWeight: ((TrackId, TrackId) -> Double)? = null,
    /** PLAN_TRANSGRAPH: the personal transition graph (FULL models with a transGraph block); null = transition counts. */
    private val graph: TransGraph? = null,
    artistKey: (TrackId) -> String,
) {
    private val counts = HashMap<TrackId, Int>()
    private val lastAt = HashMap<TrackId, Long>()
    /** The position of each track's last play in the log (the ladder's recency order, independent of the clock). */
    private val lastIndex = HashMap<TrackId, Int>()
    private val activation: Map<TrackId, Double> = HabitSlots.activation(listens, seedMs)
    // Credit-weighted transition counts (every credit is 1 without listenWeight: the counts are the old integers).
    private val fromTrack = HashMap<TrackId, HashMap<TrackId, Double>>()
    private val fromArtist = HashMap<String, HashMap<TrackId, Double>>()
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
            val c = credited(a.trackId, b.trackId, b.credit)
            fromTrack.getOrPut(a.trackId) { HashMap() }.let { it[b.trackId] = (it[b.trackId] ?: 0.0) + c }
            val key = artistKey(a.trackId)
            if (key.isNotEmpty()) fromArtist.getOrPut(key) { HashMap() }.let { it[b.trackId] = (it[b.trackId] ?: 0.0) + c }
        }
        extraTransitions.forEachIndexed { k, (a, b) ->
            if (a == b) return@forEachIndexed
            val w = credited(a, b, extraCredits?.get(k) ?: 1.0)
            fromTrack.getOrPut(a) { HashMap() }.let { it[b] = (it[b] ?: 0.0) + w }
            val key = artistKey(a)
            if (key.isNotEmpty()) fromArtist.getOrPut(key) { HashMap() }.let { it[b] = (it[b] ?: 0.0) + w }
        }
    }

    public val isEmpty: Boolean get() = counts.isEmpty()

    /** Listens in the log (the ladder's repeat order counts after them). */
    public val size: Int = listens.size

    private val plays: List<HabitListen> = listens

    /**
     * PLAN_DECODER2 §7 (reference.pre_session_share): the seed artist's share of the plays BEFORE the current session
     * (plays chained back from [seedMs] with gaps ≤ [HabitSlots.SESSION_GAP_MS]); null with fewer than [minPlays] such
     * plays or no seed artist.
     */
    public fun preSessionShare(seedMs: Long, artistOf: (TrackId) -> String, seedArtist: String, minPlays: Int): Double? {
        if (seedArtist.isEmpty()) return null
        var edge = seedMs
        var i = plays.size
        while (i > 0 && edge - plays[i - 1].startedAtMs <= HabitSlots.SESSION_GAP_MS) {
            i--
            edge = plays[i].startedAtMs
        }
        if (i < minPlays) return null
        var same = 0
        for (j in 0 until i) if (artistOf(plays[j].trackId) == seedArtist) same++
        return same.toDouble() / i
    }

    public fun row(track: TrackId, anchor: TrackId, anchorArtist: String): PersonalRow {
        val heard = track in counts
        return PersonalRow(
            heard = heard,
            count = counts[track] ?: 0,
            // A clock or timezone change can date a listen after the seed: the age is clamped at 0 (PLAN_FINAL §1.2).
            ageSeconds = lastAt[track]?.let { maxOf(0.0, (seedAt - it) / 1000.0) } ?: Double.POSITIVE_INFINITY,
            activation = activation[track] ?: Double.NaN,
            transTrack = whole(fromTrack[anchor]?.get(track) ?: 0.0),
            transArtist = whole(if (anchorArtist.isEmpty()) 0.0 else fromArtist[anchorArtist]?.get(track) ?: 0.0),
            transValue = graph?.value(anchor, track) ?: Double.NaN,
            transTrackNats = if (graph == null) listenNats(anchor, track) else Double.NaN,
            transArtistCredit = if (listenWeight == null || anchorArtist.isEmpty()) Double.NaN else fromArtist[anchorArtist]?.get(track) ?: 0.0,
        )
    }

    /** Whether the listener has hearted anything (the plan's favourites; empty without "Use favourites"). */
    public val hasFavourites: Boolean get() = favourites.isNotEmpty()

    /** [track]'s ACT-R activation, or null when never heard (R6's order). */
    public fun activationOf(track: TrackId): Double? = activation[track]

    /** Whether the listener hearted [track]. */
    public fun isFavourite(track: TrackId): Boolean = track in favourites

    /** Days since [track] was hearted, clamped at 0 (a clock change can date a heart after the seed); NaN when unknown. */
    public fun favouritedDaysAgo(track: TrackId): Double =
        favourites[track]?.let { maxOf(0.0, (seedAt - it) / 86_400_000.0) } ?: Double.NaN

    /** Heard tracks by ACT-R activation, best first (FULL's R6). */
    public fun favourites(): List<TrackId> =
        activation.entries.sortedWith(compareByDescending<Map.Entry<TrackId, Double>> { it.value }.thenComparator { a, b ->
            SaltedKey.idOrder.compare(a.key.value, b.key.value)
        }).map { it.key }

    /**
     * PLAN_LISTENW: the trans_track column in nats — ln(1 + credit) − negWeight · ln 4 · the smoothed rejection rate (one
     * rejection counting half); NaN without listenWeight (the integer count column is used). With every credit 1 and no
     * negative it is ln(1 + count), bit for bit.
     */
    private fun listenNats(anchor: TrackId, track: TrackId): Double {
        val lw = listenWeight ?: return Double.NaN
        val p = fromTrack[anchor]?.get(track) ?: 0.0
        var v = ln(1.0 + p)
        if (lw.negWeight > 0.0) pairNegatives[anchor to track]?.let { (n0, events) ->
            // PLAN_LISTENW2: the pair weight replaces the "one rejection counts half" rule
            val w = pairWeight?.invoke(anchor, track)
            val n = if (w == null) n0 else n0 * w
            v -= lw.negWeight * ln(4.0) * n / (n + p + 2.0) * (if (w == null) minOf(1.0, events / 2.0) else 1.0)
        }
        return v
    }

    /** PLAN_LISTENW2: a listen's credit for A → B under the pair's weight w: 1 − w · (1 − credit) (w = 1 without pairWeight). */
    private fun credited(a: TrackId, b: TrackId, credit: Double): Double =
        pairWeight?.let { 1.0 - it(a, b) * (1.0 - credit) } ?: credit

    /** A credit sum as the old integer count (exact when every credit is 1). */
    private fun whole(x: Double): Int = floor(x + 1e-9).toInt()

    /** How often [follower] was picked right after [track] (credit-weighted under listenWeight, rounded down). */
    public fun followCount(track: TrackId, follower: TrackId): Int = whole(fromTrack[track]?.get(follower) ?: 0.0)

    /** What followed [track] (FULL's R7), most often first. */
    public fun followers(track: TrackId): List<TrackId> =
        fromTrack[track].orEmpty().entries.filter { it.value > 0.0 }.sortedWith(compareByDescending<Map.Entry<TrackId, Double>> { it.value }.thenComparator { a, b ->
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
    override suspend fun energy(id: TrackId): Double? = engine()?.energy(id)
    override suspend fun vectors(ids: Collection<TrackId>): Map<TrackId, FloatArray> = engine()?.audioRows(ids)?.vectors.orEmpty()
    override suspend fun energies(ids: Collection<TrackId>): Map<TrackId, Double> = engine()?.audioRows(ids)?.energies.orEmpty()
    override suspend fun textVectors(ids: Collection<TrackId>): Map<TrackId, FloatArray> {
        val wanted = ids.toHashSet()
        return engine()?.metadataVectors().orEmpty().filterKeys { it in wanted }
    }
}
