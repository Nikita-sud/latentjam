/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.MetadataRerank
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One listen as the habit list reads it: a play (not a skip) of a track still in the library.
 *
 * @property countsAsNext Whether this listen may be counted as what the listener played *after* the
 *   previous one. The app sets it from the listen's origin (docs/habit-slots.md): only the
 *   listener's own picks count, so a playlist's or album's own order and SMART's own picks never
 *   teach the list a habit. The listen still takes its place in the sequence, the recency model and
 *   the novelty set either way.
 */
public data class HabitListen(
    public val trackId: TrackId,
    public val startedAtMs: Long,
    public val countsAsNext: Boolean = true,
)

/**
 * "After this you usually play…": the listener's own transition list for a seed, and the queue
 * layout that gives it every other one of the first ten slots (SLOTS5).
 *
 * A port of the ListenBrainz evaluation's personal `transition` baseline (score.py `baselines`,
 * `transitions`, `novelty`, `activation`) and of slots.py `interleave` with five personal rows —
 * the layout pre-registered as the W4 candidate. Golden cases from that harness pin the port
 * (HabitSlotsGoldenTest); docs/habit-slots.md lists the app's intended deviations.
 */
public object HabitSlots {
    /** Consecutive listens further apart than this (start to start) are not a transition. */
    public const val PAIR_GAP_MS: Long = 15L * 60L * 1_000L
    /** Listens this recent are not offered again: the listener has just heard them. */
    public const val NOVELTY_WINDOW_MS: Long = 86_400_000L
    /** The seed's own session (by gaps of at most this) is not offered again either. */
    public const val SESSION_GAP_MS: Long = 30L * 60L * 1_000L
    /** ACT-R base-level decay. */
    public const val ACTR_DECAY: Double = 0.5
    /** Personal rows placed, at 1-based slots 1, 3, 5, 7, 9. */
    public const val PERSONAL_SLOTS: Int = 5
    /** The window the personal rows are spread over. */
    public const val SLOT_WINDOW: Int = 10

    /** The artist identity SMART's chain uses for this track (an empty key is no identity). */
    public fun artistKey(track: TrackDescriptor): String =
        MetadataRerank.normalizeArtist(track.artists.firstOrNull() ?: track.artist)

    /**
     * Tracks heard in the [NOVELTY_WINDOW_MS] before [seedMs], or earlier in the seed's session by
     * [SESSION_GAP_MS] gaps. [listens] are oldest first and all before [seedMs].
     */
    public fun novelty(listens: List<HabitListen>, seedMs: Long): Set<TrackId> {
        val out = HashSet<TrackId>()
        var edge = seedMs
        for (index in listens.indices.reversed()) {
            val listen = listens[index]
            val inDay = listen.startedAtMs >= seedMs - NOVELTY_WINDOW_MS
            val inSession = edge - listen.startedAtMs <= SESSION_GAP_MS
            if (!inDay && !inSession) break
            out += listen.trackId
            if (inSession) edge = listen.startedAtMs
        }
        return out
    }

    /** ACT-R base level `ln Σ (t_seed − t_j)^−d`, ages in seconds floored at 1, per heard track. */
    public fun activation(listens: List<HabitListen>, seedMs: Long): Map<TrackId, Double> {
        val sums = LinkedHashMap<TrackId, Double>()
        for (listen in listens) {
            val ageSeconds = maxOf(1.0, (seedMs - listen.startedAtMs).toDouble() / 1000.0)
            sums[listen.trackId] = (sums[listen.trackId] ?: 0.0) + ageSeconds.pow(-ACTR_DECAY)
        }
        return sums.mapValues { (_, sum) -> ln(sum) }
    }

    /**
     * How often [seed] was directly followed by each track, and how often any track of the seed's
     * artist was: consecutive listens at most [PAIR_GAP_MS] apart, repeats of one track skipped,
     * and only pairs whose second listen [HabitListen.countsAsNext].
     */
    public fun transitionCounts(
        listens: List<HabitListen>,
        seed: TrackId,
        artistKey: (TrackId) -> String,
    ): Pair<Map<TrackId, Int>, Map<TrackId, Int>> {
        val byTrack = HashMap<TrackId, Int>()
        val byArtist = HashMap<TrackId, Int>()
        val seedArtist = artistKey(seed)
        for (index in 1 until listens.size) {
            val a = listens[index - 1]
            val b = listens[index]
            if (b.startedAtMs - a.startedAtMs > PAIR_GAP_MS || a.trackId == b.trackId) continue
            if (!b.countsAsNext) continue
            if (a.trackId == seed) byTrack[b.trackId] = (byTrack[b.trackId] ?: 0) + 1
            if (seedArtist.isNotEmpty() && artistKey(a.trackId) == seedArtist) {
                byArtist[b.trackId] = (byArtist[b.trackId] ?: 0) + 1
            }
        }
        return byTrack to byArtist
    }

    /**
     * The transition list: [candidates] minus [seed] and the [novelty] set, ranked by how often the
     * seed led to the track, then how often the seed's artist did, then ACT-R activation (unheard
     * last), then [tieKey] (lower first). [listens] are the listener's plays before [seedMs],
     * oldest first, already limited to tracks in the library.
     */
    public fun transitionList(
        candidates: List<TrackId>,
        seed: TrackId,
        seedMs: Long,
        listens: List<HabitListen>,
        artistKey: (TrackId) -> String,
        tieKey: (TrackId) -> Double,
        limit: Int = Int.MAX_VALUE,
    ): List<TrackId> {
        val novel = novelty(listens, seedMs)
        val activation = activation(listens, seedMs)
        val (byTrack, byArtist) = transitionCounts(listens, seed, artistKey)
        val rows = candidates.filter { it != seed && it !in novel }
        // Primitive comparisons throughout: Double.compareTo would order −0.0 before 0.0, which the
        // reference's tuple sort treats as equal.
        val comparator = Comparator<TrackId> { x, y ->
            val tx = byTrack[x] ?: 0
            val ty = byTrack[y] ?: 0
            if (tx != ty) return@Comparator if (tx > ty) -1 else 1
            val ax = byArtist[x] ?: 0
            val ay = byArtist[y] ?: 0
            if (ax != ay) return@Comparator if (ax > ay) -1 else 1
            val ex = activation[x]
            val ey = activation[y]
            if (ex != null || ey != null) {
                if (ey == null) return@Comparator -1
                if (ex == null) return@Comparator 1
                if (ex > ey) return@Comparator -1
                if (ex < ey) return@Comparator 1
            }
            val kx = tieKey(x)
            val ky = tieKey(y)
            when {
                kx < ky -> -1
                kx > ky -> 1
                else -> 0
            }
        }
        return rows.sortedWith(comparator).take(limit)
    }

    /**
     * SLOTS5: the first [PERSONAL_SLOTS] [personal] rows at 1-based slots 1, 3, 5, 7, 9; [chain]
     * fills the other slots of the first [SLOT_WINDOW] in its own order without repeating a placed
     * row, and the rest of [chain] follows. Fewer personal rows leave their slots to the chain.
     */
    public fun interleave(
        personal: List<TrackId>,
        chain: List<TrackId>,
        personalSlots: Int = PERSONAL_SLOTS,
    ): List<TrackId> {
        if (personalSlots <= 0) return chain
        val slots = arrayOfNulls<TrackId>(SLOT_WINDOW)
        val placed = LinkedHashSet<TrackId>()
        for (k in 0 until minOf(personalSlots, personal.size)) {
            slots[(k * SLOT_WINDOW) / personalSlots] = personal[k]
            placed += personal[k]
        }
        val rest = chain.asSequence().filter { it !in placed }.iterator()
        for (i in slots.indices) {
            if (slots[i] == null && rest.hasNext()) slots[i] = rest.next()
        }
        val head = slots.filterNotNull()
        val used = head.toHashSet()
        return head + chain.filter { it !in used }
    }
}

/**
 * Whether a personal row keeps the queue's flow from its seed — the research's coherence filter
 * (slots2.py `coherent`, threshold frozen in slots2_rule.json), made strict for the test build: a row
 * is coherent only when both tracks are KNOWN to the artist knowledge pack (both have a descriptor;
 * the adapter's guesses do not count) and their artist descriptors' cosine is at least
 * [DESCRIPTOR_FLOOR], their genre families agree when both have one, and their pack languages agree
 * when both have one. Unknown is not coherent. Without the pack nothing is.
 *
 * The genre family is the alias family of the track's first genre tag value ([Genres]' table, the
 * table the research's `family()` mirrors); the research read it from the teacher's genres, which the
 * app does not ship (docs/habit-slots.md).
 */
public class HabitCoherence(private val knowledge: ArtistKnowledge?) {

    private class Known(val descriptor: FloatArray, val language: String?, val family: String?)

    private fun known(track: TrackDescriptor): Known? {
        val pack = knowledge ?: return null
        val raw = pack.descriptor(track.artist, track.artists.firstOrNull(), text = null) ?: return null
        var norm = 0.0
        for (value in raw) norm += value.toDouble() * value
        if (norm <= 0.0 || !norm.isFinite()) return null
        val scale = 1.0 / sqrt(norm)
        val descriptor = FloatArray(raw.size) { (raw[it] * scale).toFloat() }
        return Known(descriptor, pack.facts(track.artist, track.artists.firstOrNull())?.language, family(track.genre))
    }

    /** A predicate for rows after [seed]; the seed is looked up once. */
    public fun from(seed: TrackDescriptor): (TrackDescriptor) -> Boolean {
        val anchor = known(seed) ?: return { false }
        return predicate@{ row ->
            val other = known(row) ?: return@predicate false
            if (anchor.family != null && other.family != null && anchor.family != other.family) return@predicate false
            if (anchor.language != null && other.language != null && anchor.language != other.language) return@predicate false
            if (anchor.descriptor.size != other.descriptor.size) return@predicate false
            var dot = 0.0
            for (i in anchor.descriptor.indices) dot += anchor.descriptor[i].toDouble() * other.descriptor[i]
            dot >= DESCRIPTOR_FLOOR
        }
    }

    public companion object {
        /** FIT real-sequence 10th percentile of consecutive artist-descriptor cosines (slots2_rule.json). */
        public const val DESCRIPTOR_FLOOR: Double = 0.6036645770072937

        /** The alias family of the first genre value, or null when it has none (score.py `family`). */
        public fun family(genre: String?): String? =
            Genres.rawList(genre).firstOrNull()?.let(Genres::aliasFamily)
    }
}
