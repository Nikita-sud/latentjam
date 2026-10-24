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
    /** PLAN_LISTENW: how much this listen credits the transition into it (1 = a full listen; models without listenWeight). */
    public val credit: Double = 1.0,
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
 * How far a personal row would pull the queue away from its seed — the FIT arm SOFT(λ = 2) of
 * PLAN_ITEM5 v5 (slots3.py `incoherence` and `build`; docs/habit-slots.md, deviation 4b).
 * Incoherence P is the mean of three parts in [0, 1]: genre family and pack language (0 same,
 * 1 different, [UNKNOWN] when either side has none) and the artist-descriptor distance (0 at cosine
 * ≥ [DESCRIPTOR_NEAR], 1 at ≤ [DESCRIPTOR_FAR], linear between, [UNKNOWN] without both pack
 * descriptors). With [AUDIO_PART] on (off by default: not in the FIT arm) a fourth part joins the
 * mean, the audio distance on the on-device vectors (0 at cosine ≥ the library's 75th percentile of
 * random-pair cosines, 1 at ≤ its 25th, [UNKNOWN] when either track has no vector). A row with
 * transition strength t is kept when t − [LAMBDA]·P > 0 ([pick]).
 *
 * The family is the alias family of the track's first genre tag value ([Genres]' table, which the
 * research's `family()` mirrors); the research read the teacher's genres, which the app does not ship.
 * [audio] returns a track's audio vector or null; it is read only with [AUDIO_PART] on.
 */
public class HabitFlow(
    private val knowledge: ArtistKnowledge?,
    private val audioPart: Boolean = AUDIO_PART,
    private val audio: suspend (TrackId) -> FloatArray? = { null },
) {
    private var anchorsFor: List<TrackDescriptor>? = null
    private var anchors: AudioAnchors? = null

    /** The audio cosines a random pair of this library falls under 25 % and 75 % of the time. */
    public data class AudioAnchors(val low: Double, val high: Double)

    private class Known(val descriptor: FloatArray?, val language: String?, val family: String?, val audio: FloatArray?)

    private suspend fun known(track: TrackDescriptor): Known {
        val primary = track.artists.firstOrNull()
        return Known(
            descriptor = knowledge?.descriptor(track.artist, primary, text = null),
            language = knowledge?.facts(track.artist, primary)?.language,
            family = family(track.genre),
            audio = if (audioPart) audio(track.id) else null,
        )
    }

    /** P(row) for rows after [seed]; [library] is the list the audio anchors are drawn from. Not thread-safe. */
    public suspend fun incoherence(
        seed: TrackDescriptor,
        library: List<TrackDescriptor>,
    ): suspend (TrackDescriptor) -> Double {
        if (audioPart && anchorsFor !== library) {
            anchors = audioAnchors(library)
            anchorsFor = library
        }
        val from = known(seed)
        val bounds = anchors
        return { track ->
            val row = known(track)
            val parts = mutableListOf(
                differs(from.family, row.family),
                differs(from.language, row.language),
                distance(cosine(from.descriptor, row.descriptor), DESCRIPTOR_NEAR, DESCRIPTOR_FAR),
            )
            if (audioPart) parts += bounds?.let { distance(cosine(from.audio, row.audio), it.high, it.low) } ?: UNKNOWN
            parts.average()
        }
    }
    private suspend fun audioAnchors(library: List<TrackDescriptor>): AudioAnchors? {
        if (library.size < 2) return null
        val ordered = library.map(TrackDescriptor::id).sortedBy(TrackId::value)
        val random = kotlin.random.Random(AUDIO_SAMPLE_SEED)
        val vectors = HashMap<TrackId, FloatArray?>()
        suspend fun vector(id: TrackId) = if (id in vectors) vectors[id] else audio(id).also { vectors[id] = it }
        val cosines = ArrayList<Double>(AUDIO_PAIRS)
        var attempts = 0
        while (cosines.size < AUDIO_PAIRS && attempts++ < AUDIO_PAIRS * 10) {
            val a = random.nextInt(ordered.size)
            val b = random.nextInt(ordered.size)
            if (a == b) continue
            cosine(vector(ordered[a]), vector(ordered[b]))?.let(cosines::add)
        }
        if (cosines.size < AUDIO_MIN_PAIRS) return null
        cosines.sort()
        return AudioAnchors(low = percentile(cosines, 0.25), high = percentile(cosines, 0.75))
    }

    public companion object {
        /**
         * How much incoherence costs against transition strength: the λ* = 2 chosen on FIT
         * (slots3_result.json, PLAN_ITEM5 v5). At 1, a top-count row far in genre, descriptor and
         * audio (P ≈ 0.875) still entered and the owner's queue swung between genres.
         */
        public const val LAMBDA: Double = 2.0
        /** A part neither side can judge. */
        public const val UNKNOWN: Double = 0.5
        /**
         * Artist-descriptor cosine from which a row counts as close: the FIT median of real consecutive
         * different-artist pairs (slots3.py `hi`, HI in slots3_result.json).
         */
        public const val DESCRIPTOR_NEAR: Double = 0.7051427364349365
        /** FIT real-sequence 10th percentile of consecutive artist-descriptor cosines (slots2_rule.json). */
        public const val DESCRIPTOR_FAR: Double = 0.6036645770072937
        /** Whether P has the audio part. Off: the FIT arm has none; on is an untested app extension. */
        public const val AUDIO_PART: Boolean = false
        /** Weight of an artist → track transition beside a track → track one (PLAN_ITEM5 v5). */
        public const val ARTIST_WEIGHT: Double = 0.5
        /** Random pairs drawn per library for the audio anchors, from a fixed seed. */
        public const val AUDIO_PAIRS: Int = 2_000
        public const val AUDIO_SAMPLE_SEED: Int = 20_261_019
        /** Fewer judged pairs than this (a library still being analysed) leaves audio unknown. */
        public const val AUDIO_MIN_PAIRS: Int = 50

        /** The alias family of the first genre value, or null when it has none (score.py `family`). */
        public fun family(genre: String?): String? =
            Genres.rawList(genre).firstOrNull()?.let(Genres::aliasFamily)

        /**
         * Rows by score t − [lambda]·P, best first, those above zero, at most [limit]. [rows] are the
         * transition list's rows with a count above zero, in its order (ties keep it);
         * t = ln(1 + c) / ln(1 + max c) with c = track count + [ARTIST_WEIGHT] · artist count.
         */
        public fun pick(
            rows: List<TrackId>,
            count: (TrackId) -> Double,
            incoherence: Map<TrackId, Double>,
            lambda: Double = LAMBDA,
            limit: Int = HabitSlots.PERSONAL_SLOTS,
        ): List<TrackId> {
            val top = rows.maxOfOrNull(count)?.takeIf { it > 0.0 } ?: return emptyList()
            val scored = rows.map { row ->
                row to kotlin.math.ln(1.0 + count(row)) / kotlin.math.ln(1.0 + top) - lambda * (incoherence[row] ?: UNKNOWN)
            }
            return scored.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(limit).map { it.first }
        }

        private fun differs(a: String?, b: String?): Double =
            if (a == null || b == null) UNKNOWN else if (a == b) 0.0 else 1.0

        /** 0 at or above [near], 1 at or below [far], linear between; [UNKNOWN] without a cosine. */
        internal fun distance(cosine: Double?, near: Double, far: Double): Double = when {
            cosine == null -> UNKNOWN
            cosine >= near -> 0.0
            cosine <= far -> 1.0
            else -> (near - cosine) / (near - far)
        }

        internal fun cosine(a: FloatArray?, b: FloatArray?): Double? {
            if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            if (na <= 0.0 || nb <= 0.0) return null
            return (dot / sqrt(na * nb)).takeIf(Double::isFinite)
        }

        /** numpy's default (linear) percentile of an ascending list. */
        internal fun percentile(sorted: List<Double>, q: Double): Double {
            val position = q * (sorted.size - 1)
            val lower = position.toInt()
            val upper = minOf(lower + 1, sorted.lastIndex)
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }
    }
}
