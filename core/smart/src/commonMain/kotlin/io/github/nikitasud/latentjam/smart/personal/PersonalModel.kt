/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How a listen started, as the personal evidence reads it (the history's ListenStart, plus the import and unknown cases). */
public enum class PersonalStart { PICK, QUEUED, AUTO, SKIP_NEXT, SKIP_PREVIOUS, REPEAT, IMPORTED, UNKNOWN }

/** Where a pick came from (the history's PickSurface); null on a listen is unknown. */
public enum class PersonalSurface { SEARCH, QUEUE, COLLECTION, FOR_YOU, EXTERNAL }

/**
 * A library track as the personal evidence keys it.
 *
 * @param key the song's version group (remix, slowed and 8D versions share it), so a re-tag or another release keeps the evidence
 * @param artists the track's artist keys (multi-valued)
 * @param album the release's key (album and album artist), null when unknown
 */
public class PersonalTrack(
    public val id: TrackId,
    public val key: String,
    public val artists: List<String>,
    public val album: String? = null,
    public val trackNumber: Int? = null,
    public val discNumber: Int? = null,
    public val durationMs: Long? = null,
)

/**
 * One listen of the history log, oldest first.
 *
 * @param listenedMs the time heard; null for legacy listens and imports
 * @param smartSourced SMART suggested the track: a plan position, a legacy listen in SMART mode, or a For You suggestion
 *   (PERSONALISATION_RULES v5.3–v5.5). Its positive evidence counts only up to a quarter of the listener's own.
 * @param forYou a For You suggestion the listener picked (parent "foryou", no plan position, not queued by hand)
 * @param albumParent the queue was started from an album
 * @param shuffleOff shuffle was off when the track started
 */
public class PersonalListen(
    public val trackId: TrackId,
    public val startedAtMs: Long,
    public val listenedMs: Long?,
    public val completed: Boolean,
    public val skipped: Boolean,
    public val start: PersonalStart,
    public val surface: PersonalSurface? = null,
    public val smartSourced: Boolean = false,
    public val forYou: Boolean = false,
    public val albumParent: Boolean = false,
    public val shuffleOff: Boolean = false,
    /** For legacy listens without a listen time: the furthest position played. */
    public val playedMs: Long = 0L,
    /** SMART's personal term chose this track: accepting it is no evidence (v6), skipping it is a full rejection. */
    public val personalChanged: Boolean = false,
)

/**
 * The listener's personal evidence (PERSONALISATION_RULES v5…v5.5, PLAN_PERSONAL v2): for each pair of songs A → B the
 * decayed strength of the sessions in which B followed A — S⁺ for the positives, the listener's own plus SMART's own up to
 * a quarter of them, and S⁻ for early skips of B offered right after A — with the distinct sessions behind them, the
 * same over artist pairs, the occurrence weights the lift filter reads, and per-song early skips. A cache rebuilt from
 * the history log: nothing is stored, and clearing the history clears it.
 *
 * Packed and bounded: songs are 64-bit key hashes in a sorted array, and each song keeps its [TOP_PER_SONG] strongest
 * successors that can change a term (each artist its [TOP_PER_ARTIST]) in flat half-precision arrays.
 */
public class PersonalModel internal constructor(
    private val songCount: Int,
    private val trackHashes: LongArray,
    private val trackSong: IntArray,
    private val trackArtistStart: IntArray,
    private val trackArtistList: IntArray,
    private val edgeStart: IntArray,
    private val edgeTo: IntArray,
    private val edgePositive: ShortArray,
    /** The listener's own part of [edgePositive] (no SMART-sourced evidence): what may relieve recency. */
    private val edgeOwn: ShortArray,
    private val edgeSessions: ByteArray,
    private val edgeNegative: ShortArray,
    private val edgeNegativeSessions: ByteArray,
    /** The jar b(A → B) (v6, the LB-import fixes): the time-ordered fold of both sources' sessions, decayed to the reference. */
    private val edgeJar: ShortArray,
    /** Distinct local calendar days ([build]'s localDay) of the pair's own sessions, at most 255. */
    private val edgeDays: ByteArray,
    private val edgeWeight: ShortArray,
    private val outWeight: FloatArray,
    private val outEffective: FloatArray,
    private val inWeight: FloatArray,
    private val totalWeight: Float,
    private val earlySkips: FloatArray,
    /** Per song: the decayed strength of the sessions the listener repeated it or went back to it in, and their count. */
    private val affinityStrength: FloatArray,
    private val affinitySessions: ByteArray,
    private val artistStart: IntArray,
    private val artistTo: IntArray,
    private val artistPositive: ShortArray,
    private val artistSessions: ByteArray,
    /** Whether any listen was read: no history means no personal term at all (C). */
    public val isEmpty: Boolean,
) {
    private fun trackIndex(id: TrackId): Int {
        val key = hash(id.value)
        var low = 0
        var high = trackHashes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = trackHashes[mid]
            when {
                value < key -> low = mid + 1
                value > key -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The song index of [id], or −1. */
    public fun songOf(id: TrackId): Int = trackIndex(id).let { if (it < 0) -1 else trackSong[it] }

    /** The artist indices of [id] (none when unknown). */
    public fun artistsOf(id: TrackId): IntArray {
        val t = trackIndex(id)
        if (t < 0) return NO_ARTISTS
        return trackArtistList.copyOfRange(trackArtistStart[t], trackArtistStart[t + 1])
    }

    private fun edge(a: Int, b: Int): Int {
        if (a < 0 || b < 0) return -1
        for (e in edgeStart[a] until edgeStart[a + 1]) if (edgeTo[e] == b) return e
        return -1
    }

    /** S⁺(A → B): decayed session strength of the positives, SMART's own capped at a quarter of the listener's. */
    public fun positive(a: Int, b: Int): Float = edge(a, b).takeIf { it >= 0 }?.let { unhalf(edgePositive[it]) } ?: 0f

    /** Distinct sessions of the listener's own (non-SMART) evidence that A → B appeared in. */
    public fun sessions(a: Int, b: Int): Int = edge(a, b).takeIf { it >= 0 }?.let { edgeSessions[it].toInt() and 0xff } ?: 0

    /** s(A → B) = min(1, S⁺ / 6) once the pair has two sessions of the listener's own, else 0 (a one-off is chance). */
    public fun strength(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        if ((edgeSessions[e].toInt() and 0xff) < MIN_SESSIONS) return 0f
        return min(1f, unhalf(edgePositive[e]) / FULL_STRENGTH)
    }

    /**
     * s of the listener's own evidence alone, min(1, S_non / 6) from two own sessions: the recency brake is never relieved
     * by SMART-sourced evidence (PERSONALISATION_RULES v5.1 item 6).
     */
    public fun ownStrength(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        if ((edgeSessions[e].toInt() and 0xff) < MIN_SESSIONS) return 0f
        return min(1f, unhalf(edgeOwn[e]) / FULL_STRENGTH)
    }

    /** The jar b(A → B) in [JAR_MIN, JAR_MAX] (v6): what the habit term and the relief read. */
    public fun jar(a: Int, b: Int): Float = edge(a, b).takeIf { it >= 0 }?.let { unhalf(edgeJar[it]) } ?: 0f

    /** Distinct calendar days of the pair's own sessions (tier 2 needs [TIER2_DAYS]). */
    public fun days(a: Int, b: Int): Int = edge(a, b).takeIf { it >= 0 }?.let { edgeDays[it].toInt() and 0xff } ?: 0

    /** Distinct sessions that rejected B right after A, from any source. */
    public fun rejectionSessions(a: Int, b: Int): Int =
        edge(a, b).takeIf { it >= 0 }?.let { edgeNegativeSessions[it].toInt() and 0xff } ?: 0

    /** ρ = clamp(b, 0, 6) / 6 once two sessions rejected B after A, else 1: how much of D1's relief and credit survives. */
    public fun jarWeight(a: Int, b: Int): Float =
        if (rejectionSessions(a, b) < MIN_SESSIONS) 1f else jar(a, b).coerceIn(0f, JAR_MAX) / JAR_MAX

    /**
     * The habit's reach before the cluster guard (v6): 2 with [D1_SESSIONS] own sessions on [TIER2_DAYS] calendar days,
     * 1 with [MIN_SESSIONS], else 0. Positive evidence only: skips never lower it.
     */
    public fun tier(a: Int, b: Int): Int {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0
        val sessions = edgeSessions[e].toInt() and 0xff
        return when {
            sessions >= D1_SESSIONS && (edgeDays[e].toInt() and 0xff) >= TIER2_DAYS -> 2
            sessions >= MIN_SESSIONS -> 1
            else -> 0
        }
    }

    /** Track affinity: min(1, decayed sessions with a repeat or a return to the song / 3), from two sessions on. */
    public fun affinity(song: Int): Float {
        if (song < 0 || (affinitySessions[song].toInt() and 0xff) < MIN_SESSIONS) return 0f
        return min(1f, affinityStrength[song] / FULL_AFFINITY)
    }

    /** The artist level (A3): the strongest s over the artist pairs of [from] and [to], each with two own sessions. */
    public fun artistStrength(from: IntArray, to: IntArray): Float {
        if (from.isEmpty() || to.isEmpty()) return 0f
        var best = 0f
        for (x in from) for (e in artistStart[x] until artistStart[x + 1]) {
            if ((artistSessions[e].toInt() and 0xff) >= MIN_SESSIONS && artistTo[e] in to) {
                best = max(best, min(1f, unhalf(artistPositive[e]) / FULL_STRENGTH))
            }
        }
        return best
    }

    public fun artistStrength(from: TrackId, to: TrackId): Float = artistStrength(artistsOf(from), artistsOf(to))

    /** neg(A → B) = min(1, S⁻ / 2) once two sessions of any source rejected B after A, else 0. */
    public fun rejection(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        if ((edgeNegativeSessions[e].toInt() and 0xff) < MIN_SESSIONS) return 0f
        return min(1f, unhalf(edgeNegative[e]) / FULL_REJECTION)
    }

    /**
     * The lift filter: the Wilson lower bound (z [Z]) of the shrunk P(B | A) on the Kish effective count of A's
     * occurrence weights, over B's base rate q_B. A pair at or under its base rate is no habit, however often it played.
     */
    public fun lift(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        val p = unhalf(edgeWeight[e]) / (outWeight[a] + ALPHA)
        val lcb = wilson(p, outEffective[a])
        val q = (inWeight[b] + 1f) / (totalWeight + songCount.coerceAtLeast(1))
        return lcb / q
    }

    /** D1: ≥ 3 own sessions and the lift filter, so a strong habit may relieve its recency penalty. */
    public fun strong(a: Int, b: Int): Boolean = sessions(a, b) >= D1_SESSIONS && lift(a, b) >= LIFT_FILTER

    /** E7 in δ units: 0.1 per decayed early skip of the song, at most 0.5. */
    public fun skipPenalty(song: Int): Float = if (song < 0) 0f else min(E7_CAP, E7 * earlySkips[song])

    /** Bytes the packed arrays retain (the store budget). */
    public fun retainedBytes(): Long =
        8L * trackHashes.size +
            4L * (trackSong.size + trackArtistStart.size + trackArtistList.size + edgeStart.size + edgeTo.size +
                outWeight.size + outEffective.size + inWeight.size + earlySkips.size + artistStart.size + artistTo.size) +
            2L * (edgePositive.size + edgeOwn.size + edgeNegative.size + edgeJar.size + edgeWeight.size + artistPositive.size) +
            4L * affinityStrength.size + affinitySessions.size +
            edgeSessions.size + edgeNegativeSessions.size + edgeDays.size + artistSessions.size

    /** Diagnostics: how many links the store keeps. */
    public fun sizes(): String = "songs=$songCount edges=${edgeTo.size} artistEdges=${artistTo.size} tracks=${trackHashes.size}"

    /** Diagnostics: S⁻(A → B). */
    public fun negative(a: Int, b: Int): Float = edge(a, b).takeIf { it >= 0 }?.let { unhalf(edgeNegative[it]) } ?: 0f

    /** Diagnostics: how many pairs carry two or more own sessions (the owner report). */
    public fun habitPairs(): Int = edgeSessions.count { (it.toInt() and 0xff) >= MIN_SESSIONS }

    public companion object {
        // PLAN_PERSONAL v2 §2–§4, frozen before the v2 tests.
        public const val PICK: Float = 1.0f
        public const val PICK_QUEUE: Float = 0.7f
        public const val PICK_UNKNOWN: Float = 0.85f
        public const val FOR_YOU: Float = 0.35f
        public const val ACCEPT: Float = 0.25f
        public const val SMART_FACTOR: Float = 0.5f
        public const val ALBUM_FACTOR: Float = 0.1f
        public const val ATTENTION_FREE: Int = 5
        public const val ATTENTION_DECAY: Float = 0.7f
        public const val IMPORT: Float = 0.4f
        public const val REPLAY_FACTOR: Float = 0.2f
        public const val SKIP_EARLY: Float = 1.0f
        public const val SKIP_LATE: Float = 0.5f
        public const val ACCIDENTAL_SKIP_MS: Long = 2_000L
        public const val EARLY_SKIP_MS: Long = 10_000L
        public const val SHORT_TRACK_MS: Long = 60_000L
        public const val LONG_TRACK_MS: Long = 20L * 60_000L
        /** E5: a transition needs B to start within this of A's end. */
        public const val TRANSITION_GAP_MS: Long = 15L * 60_000L
        /** A session: events with end-to-start gaps of at most this. */
        public const val SESSION_GAP_MS: Long = 30L * 60_000L
        public const val HALF_LIFE_MS: Long = 120L * DAY_MS_CONST
        public const val SMART_SHARE: Float = 0.25f
        public const val FULL_STRENGTH: Float = 6f
        public const val FULL_REJECTION: Float = 2f
        public const val FULL_AFFINITY: Float = 3f
        public const val MIN_SESSIONS: Int = 2
        public const val D1_SESSIONS: Int = 3
        public const val LIFT_FILTER: Float = 2f
        public const val ALPHA: Float = 4f
        public const val Z: Float = 1.645f
        public const val IMPORT_ANCHOR_CAP: Float = 12f
        public const val E7: Float = 0.1f
        public const val E7_CAP: Float = 0.5f
        public const val TOP_PER_SONG: Int = 16
        public const val TOP_PER_ARTIST: Int = 8
        // PLAN_PERSONAL v6, frozen before the v6 tests.
        /** The jar's bounds: b is clamped to these after every step. */
        public const val JAR_MIN: Float = -1.8f
        public const val JAR_MAX: Float = 6f
        public const val TIER2_DAYS: Int = 2
        // PLAN_PERSONAL v6.1, frozen before the v6.1 tests: the weighted A-day clock.
        /** Weighted A-days per halving: a day the listener picked something else after A moves the clock 1 / 20. */
        public const val CLOCK_A_DAYS: Double = 20.0
        /** Calendar days per halving: an untouched habit halves in two years. */
        public const val CLOCK_CALENDAR_DAYS: Double = 730.0
        public const val FOLLOW_PICK: Double = 1.0
        public const val FOLLOW_CONTINUATION: Double = 0.25
        public const val FOLLOW_IMPORT: Double = 0.4
        internal const val DAY_MS: Long = DAY_MS_CONST
        private val NO_ARTISTS = IntArray(0)

        public val EMPTY: PersonalModel = build(emptyList(), emptyList())

        /** Wilson lower bound of a proportion [p] on [n] effective trials. */
        internal fun wilson(p: Float, n: Float): Float {
            if (n <= 0f || p <= 0f) return 0f
            val pp = p.coerceIn(0f, 1f).toDouble()
            val nn = n.toDouble()
            val z2 = (Z * Z).toDouble()
            val centre = pp + z2 / (2 * nn)
            val margin = Z * sqrt(pp * (1 - pp) / nn + z2 / (4 * nn * nn))
            return ((centre - margin) / (1 + z2 / nn)).toFloat().coerceAtLeast(0f)
        }

        /** 64-bit FNV-1a: keys are stored as hashes, never as strings. */
        internal fun hash(key: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in key) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }

        /**
         * Builds the model from [listens] (oldest first) over [library]; listens of tracks outside it are skipped. [localDay]
         * is the listener's calendar day of a time; [nowMs] the reference evidence decays to (the newest event when null).
         */
        public fun build(
            listens: List<PersonalListen>,
            library: List<PersonalTrack>,
            localDay: (Long) -> Long = ::utcDay,
            nowMs: Long? = null,
        ): PersonalModel = PersonalBuilder(listens, library, localDay, nowMs).build()

        /** The UTC calendar day of [ms]: the default A-day clock's day when no timezone is given (tests). */
        public fun utcDay(ms: Long): Long = floorDiv(ms, DAY_MS)
    }
}

private const val DAY_MS_CONST: Long = 86_400_000L

/**
 * Session id → the best value seen in it, in first-seen order. Most pairs have one session, so the first one is held
 * inline and a map is made only for a second: a long imported history has hundreds of thousands of pairs.
 */
private class SessionBest {
    private var hasFirst = false
    private var firstSession = 0L
    private var firstValue = 0.0
    private var firstAt = 0L
    /** The later sessions: each one's best value and when it first showed this kind (the jar's order). */
    private var rest: LinkedHashMap<Long, Slot>? = null

    private class Slot(var value: Double, val at: Long)

    val size: Int get() = (if (hasFirst) 1 else 0) + (rest?.size ?: 0)

    /** Keeps the session's best [value]; [at] is when the session first showed it. True for a session not seen before. */
    fun best(session: Long, value: Double, at: Long = 0L): Boolean {
        if (!hasFirst) {
            hasFirst = true
            firstSession = session
            firstValue = value
            firstAt = at
            return true
        }
        if (session == firstSession) {
            if (value > firstValue) firstValue = value
            return false
        }
        val map = rest ?: LinkedHashMap<Long, Slot>().also { rest = it }
        val held = map[session]
        if (held == null) {
            map[session] = Slot(value, at)
            return true
        }
        if (value > held.value) held.value = value
        return false
    }

    /** Each session's value with its first-seen time, in first-seen order. */
    inline fun forEachAt(action: (Long, Double, Long) -> Unit) {
        if (hasFirst) action(firstSession, firstValue, firstAt)
        rest?.forEach { (session, slot) -> action(session, slot.value, slot.at) }
    }

    /** The values summed in first-seen order, as `values.sum()` over the map did. */
    fun sum(): Double {
        var total = 0.0
        if (hasFirst) total += firstValue
        rest?.values?.forEach { total += it.value }
        return total
    }

    inline fun forEach(action: (Long, Double) -> Unit) {
        if (hasFirst) action(firstSession, firstValue)
        rest?.forEach { (session, slot) -> action(session, slot.value) }
    }
}

/** Distinct calendar days, held inline for the usual one. */
private class DaySet {
    private var first = Long.MIN_VALUE
    private var more: LinkedHashSet<Long>? = null

    val size: Int get() = (if (first == Long.MIN_VALUE) 0 else 1) + (more?.size ?: 0)

    fun add(day: Long) {
        if (first == Long.MIN_VALUE) first = day
        else if (day != first) (more ?: LinkedHashSet<Long>().also { more = it }) += day
    }
}

/** A pair's evidence while building: per session the best grade of each kind, and the occurrence weights. */
private class PairAcc {
    /** Session id → best (grade × decay) of the listener's own evidence, SMART's own, and rejections. */
    val own = SessionBest()
    val smart = SessionBest()
    val rejected = SessionBest()
    /** Imported session strength, kept apart for the per-anchor cap; null when none, and after its merge into [own]. */
    var imported: SessionBest? = null
    /** Calendar days of the own sessions (device and import), each session's start day once. */
    val days = DaySet()
    var weightOwn = 0.0
    var weightSmart = 0.0
    var squaresOwn = 0.0
    var squaresSmart = 0.0

    fun importedSessions(): SessionBest = imported ?: SessionBest().also { imported = it }
}

private class PersonalBuilder(
    private val listens: List<PersonalListen>,
    private val library: List<PersonalTrack>,
    private val localDay: (Long) -> Long,
    private val nowMs: Long?,
) {
    private val byId = LinkedHashMap<TrackId, PersonalTrack>(library.size * 2)
    private val songOfTrack = LinkedHashMap<TrackId, Int>(library.size * 2)
    private val artistsOfTrack = LinkedHashMap<TrackId, IntArray>(library.size * 2)
    private val pairs = LinkedHashMap<Long, PairAcc>()
    private val artistPairs = LinkedHashMap<Long, PairAcc>()
    private var artistCount = 0
    private var deviceRef = 0L
    private var ref = 0L
    private lateinit var clock: DayClock

    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    fun build(): PersonalModel {
        // Songs in sorted hash order, so indices do not depend on library order (the JVM / iOS golden).
        val hashes = library.map { PersonalModel.hash(it.key) }.distinct().sorted()
        val songIndex = LinkedHashMap<Long, Int>(hashes.size * 2)
        hashes.forEachIndexed { i, h -> songIndex[h] = i }
        val artistHashes = library.flatMap { t -> t.artists.filter(String::isNotEmpty).map(PersonalModel::hash) }.distinct().sorted()
        artistCount = artistHashes.size
        val artistIndex = LinkedHashMap<Long, Int>(artistHashes.size * 2)
        artistHashes.forEachIndexed { i, h -> artistIndex[h] = i }
        for (t in library) {
            byId[t.id] = t
            songOfTrack[t.id] = songIndex.getValue(PersonalModel.hash(t.key))
            artistsOfTrack[t.id] = t.artists.filter(String::isNotEmpty).map { artistIndex.getValue(PersonalModel.hash(it)) }
                .distinct().sorted().toIntArray()
        }
        val songs = hashes.size
        val skips = FloatArray(songs)
        val affinity = LinkedHashMap<Int, LinkedHashMap<Long, Double>>()
        fun liked(song: Int, session: Long, value: Double) {
            val held = affinity.getOrPut(song) { LinkedHashMap() }
            if ((held[session] ?: 0.0) < value) held[session] = value
        }

        // Listens of library tracks; tracks over 20 minutes (sets, podcasts) are no evidence.
        val known = listens.filter { l ->
            val track = byId[l.trackId] ?: return@filter false
            (track.durationMs ?: 0L) <= PersonalModel.LONG_TRACK_MS
        }
        val device = known.filter { it.start != PersonalStart.IMPORTED }
        val imported = known.filter { it.start == PersonalStart.IMPORTED }
        deviceRef = device.maxOfOrNull { it.startedAtMs } ?: 0L
        val importNewest = imported.maxOfOrNull { it.startedAtMs } ?: 0L
        // One reference for both sources (the LB-import fixes, D3): the build's now, or else the newest event of either. An
        // export keeps aging after the import, on the same clock as the listener's own evidence.
        ref = nowMs ?: max(deviceRef, importNewest)
        // E7 early skips and affinity keep the calendar decay (to the newest device event, or the newest import without one);
        // pair evidence decays on its anchor's A-day clock (v6.1), one clock for both sources (D2).
        val affinityRef = if (device.isNotEmpty()) deviceRef else importNewest
        fun decay(at: Long, ref: Long) = 0.5.pow((ref - at).coerceAtLeast(0L).toDouble() / PersonalModel.HALF_LIFE_MS)
        fun song(l: PersonalListen) = songOfTrack.getValue(l.trackId)
        clock = DayClock(localDay, ref)

        // On-device listens: sessions by end-to-start gaps, transitions within 15 minutes of the anchor's end. The walk runs
        // twice: first it reads every anchor's A-days (what followed A, day by day), then it adds the evidence on that clock.
        fun walkDevice(clockPass: Boolean) {
            var session = 0L
            var sessionDay = 0L
            var lastEnd = Long.MIN_VALUE
            var anchor: PersonalListen? = null
            var anchorEnd = Long.MIN_VALUE
            var rejectedRun = false
            var autoStreak = 0
            for (l in device) {
                val heard = l.listenedMs ?: l.playedMs
                if (lastEnd != Long.MIN_VALUE && l.startedAtMs - lastEnd > PersonalModel.SESSION_GAP_MS) {
                    session++
                    anchor = null
                    rejectedRun = false
                    autoStreak = 0
                }
                if (lastEnd == Long.MIN_VALUE || l.startedAtMs - lastEnd > PersonalModel.SESSION_GAP_MS) sessionDay = localDay(l.startedAtMs)
                lastEnd = max(lastEnd, l.startedAtMs + heard)
                val w = decay(l.startedAtMs, affinityRef)
                val a = anchor
                val follows = a != null && l.startedAtMs - anchorEnd <= PersonalModel.TRANSITION_GAP_MS && song(a) != song(l)
                if (l.skipped) {
                    if (heard < PersonalModel.ACCIDENTAL_SKIP_MS) continue
                    if (!clockPass) {
                        skips[song(l)] += w.toFloat()
                        // E4: the first skip after the anchor is the run's one rejection, from any source.
                        if (!rejectedRun && follows) {
                            val grade = if (heard < PersonalModel.EARLY_SKIP_MS) PersonalModel.SKIP_EARLY else PersonalModel.SKIP_LATE
                            val p = pair(a!!.trackId, l.trackId)
                            p.rejected.best(session, grade * clock.factor(song(a), song(l), l.startedAtMs), l.startedAtMs)
                        }
                    }
                    rejectedRun = true
                    continue
                }
                autoStreak = if (l.start == PersonalStart.AUTO) autoStreak + 1 else 0
                // A repeat, a return to the song, or the song again right after itself: affinity for the song, no transition.
                val again = a != null && l.startedAtMs - anchorEnd <= PersonalModel.TRANSITION_GAP_MS && song(a) == song(l)
                val transition = l.start != PersonalStart.REPEAT && l.start != PersonalStart.SKIP_PREVIOUS && follows
                if (clockPass) {
                    if (transition) clock.follow(song(a!!), a.startedAtMs, song(l), followClass(l))
                } else {
                    if (l.start == PersonalStart.REPEAT || l.start == PersonalStart.SKIP_PREVIOUS || again) liked(song(l), session, w)
                    // An accepted pick the personal term made is no evidence (v6): the habit must not feed itself.
                    if (transition && !l.personalChanged) {
                        val grade = deviceGrade(a!!, l, afterRejection = rejectedRun, autoStreak)
                        if (grade > 0.0) {
                            val value = grade * clock.factor(song(a), song(l), l.startedAtMs)
                            add(a.trackId, l.trackId, session, value, l.smartSourced, imported = false, l.startedAtMs, sessionDay)
                        }
                    }
                }
                anchor = l
                anchorEnd = l.startedAtMs + heard
                rejectedRun = false
            }
        }

        // Imports: sessions by end-to-start gaps; a replayed 3-gram is one session (its own id).
        val runs = ArrayList<List<PersonalListen>>()
        run {
            var current = ArrayList<PersonalListen>()
            var end = Long.MIN_VALUE
            for (l in imported) {
                if (end != Long.MIN_VALUE && l.startedAtMs - end > PersonalModel.SESSION_GAP_MS) {
                    if (current.isNotEmpty()) runs += current
                    current = ArrayList()
                }
                current += l
                end = l.startedAtMs + (byId.getValue(l.trackId).durationMs ?: DEFAULT_DURATION_MS)
            }
            if (current.isNotEmpty()) runs += current
        }
        val gramRuns = LinkedHashMap<Triple<Int, Int, Int>, Int>()
        for (run in runs) {
            val seen = HashSet<Triple<Int, Int, Int>>()
            for (i in 2 until run.size) seen += Triple(song(run[i - 2]), song(run[i - 1]), song(run[i]))
            for (g in seen) gramRuns[g] = (gramRuns[g] ?: 0) + 1
        }
        // The same twice: first the clock (an imported follower other than B 0.4, an album's next track a continuation, a
        // run's last track an end), then the evidence on it. A→A in a run is track affinity (rules: repeats, every source).
        fun walkImports(clockPass: Boolean) {
            for ((r, run) in runs.withIndex()) {
                var end = Long.MIN_VALUE
                for (i in run.indices) {
                    val b = run[i]
                    if (i > 0) {
                        val a = run[i - 1]
                        val near = b.startedAtMs - end <= PersonalModel.TRANSITION_GAP_MS
                        val next = albumNext(byId.getValue(a.trackId), byId.getValue(b.trackId))
                        if (near && song(a) == song(b)) {
                            if (!clockPass) liked(song(b), IMPORT_SESSION_BASE + r, PersonalModel.IMPORT * decay(b.startedAtMs, affinityRef))
                        } else if (near && clockPass) {
                            clock.follow(song(a), a.startedAtMs, song(b), if (next) PersonalModel.FOLLOW_CONTINUATION else PersonalModel.FOLLOW_IMPORT)
                        } else if (near) {
                            var grade = PersonalModel.IMPORT.toDouble()
                            if (next) grade *= PersonalModel.ALBUM_FACTOR
                            val before = if (i >= 2) Triple(song(run[i - 2]), song(a), song(b)) else null
                            val after = if (i + 1 < run.size) Triple(song(a), song(b), song(run[i + 1])) else null
                            val gram = before?.takeIf { (gramRuns[it] ?: 0) >= 2 } ?: after?.takeIf { (gramRuns[it] ?: 0) >= 2 }
                            if (gram != null) grade *= PersonalModel.REPLAY_FACTOR
                            // A replayed sequence is one session however often it played: the 3-gram's own id.
                            val sessionId = if (gram != null) GRAM_SESSION_BASE + gramId(gram) else IMPORT_SESSION_BASE + r
                            add(a.trackId, b.trackId, sessionId, grade * clock.factor(song(a), song(b), b.startedAtMs), smart = false,
                                imported = true, b.startedAtMs, localDay(run[0].startedAtMs))
                        }
                    }
                    end = b.startedAtMs + (byId.getValue(b.trackId).durationMs ?: DEFAULT_DURATION_MS)
                }
            }
        }

        walkDevice(clockPass = true)
        walkImports(clockPass = true)
        clock.finish()
        walkDevice(clockPass = false)
        walkImports(clockPass = false)

        // The per-anchor import cap: imported strength summed over A → * at most 12; the same for artist pairs (R2).
        for (table in listOf(pairs, artistPairs)) {
            val importByAnchor = LinkedHashMap<Int, Double>()
            for ((k, p) in table) {
                val a = (k ushr 32).toInt()
                importByAnchor[a] = (importByAnchor[a] ?: 0.0) + (p.imported?.sum() ?: 0.0)
            }
            for ((k, p) in table) {
                val total = importByAnchor[(k ushr 32).toInt()] ?: 0.0
                val scale = if (total > PersonalModel.IMPORT_ANCHOR_CAP) PersonalModel.IMPORT_ANCHOR_CAP / total else 1.0
                p.imported?.forEachAt { sessionId, v, at -> p.own.best(sessionId, v * scale, at) }
                // Nothing reads the imported sessions after the merge.
                p.imported = null
            }
        }
        val affinityStrength = FloatArray(songs)
        val affinitySessions = ByteArray(songs)
        for ((song, sessions) in affinity) {
            affinityStrength[song] = sessions.values.sum().toFloat()
            affinitySessions[song] = sessions.size.coerceAtMost(255).toByte()
        }
        return pack(songs, skips, affinityStrength, affinitySessions, isEmpty = known.isEmpty())
    }

    /**
     * How far a device follower of A moves A's clock for every other B (PLAN_PERSONAL v6.1): the listener's own pick 1;
     * an album, playlist or queue continuation 0.25; shuffle, SMART, For You and the personal term's picks 0.
     */
    private fun followClass(l: PersonalListen): Double = when {
        l.smartSourced || l.forYou || l.personalChanged || l.surface == PersonalSurface.FOR_YOU -> 0.0
        l.start == PersonalStart.PICK -> PersonalModel.FOLLOW_PICK
        l.start == PersonalStart.QUEUED || l.start == PersonalStart.SKIP_NEXT -> PersonalModel.FOLLOW_CONTINUATION
        (l.start == PersonalStart.AUTO || l.start == PersonalStart.UNKNOWN) && l.shuffleOff -> PersonalModel.FOLLOW_CONTINUATION
        else -> 0.0
    }

    /** A 3-gram's stable id (no platform hash). */
    private fun gramId(g: Triple<Int, Int, Int>): Long = ((g.first.toLong() * 1_000_003L + g.second) * 1_000_003L + g.third) and 0xffffffffffL

    private fun add(a: TrackId, b: TrackId, session: Long, value: Double, smart: Boolean, imported: Boolean, at: Long, day: Long) {
        val p = pair(a, b)
        when {
            // A session's day is its start's, counted once (a replayed import 3-gram is one session over many days).
            imported -> if (p.importedSessions().best(session, value, at)) p.days.add(day)
            smart -> p.smart.best(session, value, at)
            else -> if (p.own.best(session, value, at)) p.days.add(day)
        }
        if (smart) {
            p.weightSmart += value
            p.squaresSmart += value * value
        } else {
            p.weightOwn += value
            p.squaresOwn += value * value
        }
        val from = artistsOfTrack.getValue(a)
        val to = artistsOfTrack.getValue(b)
        for (x in from) for (y in to) {
            if (x == y) continue
            val q = artistPairs.getOrPut(key(x, y)) { PairAcc() }
            when {
                imported -> q.importedSessions().best(session, value, at)
                smart -> q.smart.best(session, value)
                else -> q.own.best(session, value)
            }
        }
    }

    /** The positive grade of A → B for an on-device listen B (PLAN_PERSONAL v2 §2), before decay. */
    private fun deviceGrade(a: PersonalListen, b: PersonalListen, afterRejection: Boolean, autoStreak: Int): Double {
        when (b.start) {
            PersonalStart.QUEUED -> return PersonalModel.PICK_QUEUE.toDouble()
            PersonalStart.PICK -> return when {
                b.forYou || b.surface == PersonalSurface.FOR_YOU -> PersonalModel.FOR_YOU.toDouble()
                b.surface == PersonalSurface.QUEUE -> PersonalModel.PICK_QUEUE.toDouble()
                b.surface == null -> PersonalModel.PICK_UNKNOWN.toDouble()
                else -> PersonalModel.PICK.toDouble()
            }
            else -> Unit
        }
        if (!b.completed) return 0.0
        val smart = if (b.smartSourced) PersonalModel.SMART_FACTOR.toDouble() else 1.0
        // A song the listener stopped skipping at: chosen by skipping (E4's positive half).
        if (afterRejection) return PersonalModel.PICK_QUEUE * smart
        val track = byId.getValue(b.trackId)
        val duration = track.durationMs
        val length = if (duration != null && duration > 0) min(1.0, duration.toDouble() / PersonalModel.SHORT_TRACK_MS) else 1.0
        val attention = if (b.start == PersonalStart.AUTO && autoStreak > PersonalModel.ATTENTION_FREE) {
            PersonalModel.ATTENTION_DECAY.toDouble().pow(autoStreak - PersonalModel.ATTENTION_FREE)
        } else {
            1.0
        }
        val album = if (b.shuffleOff && b.albumParent && albumNext(byId.getValue(a.trackId), track)) PersonalModel.ALBUM_FACTOR.toDouble() else 1.0
        return PersonalModel.ACCEPT * smart * length * attention * album
    }

    private fun pair(a: TrackId, b: TrackId): PairAcc =
        pairs.getOrPut(key(songOfTrack.getValue(a), songOfTrack.getValue(b))) { PairAcc() }

    /**
     * The jar b(A → B) (PLAN_PERSONAL v6, the LB-import fixes): every session of either source folded in time order on the
     * anchor's clock, decayed to the build's reference.
     */
    private fun jarOf(k: Long, p: PairAcc): Double {
        val a = (k ushr 32).toInt()
        val bSong = (k and 0xffffffffL).toInt()
        // One jar for both sources (D1): the own steps already hold the imported sessions under the per-anchor cap, so a
        // device skip pushes an imported habit down like a device one.
        val steps = ArrayList<Jar.Step>()
        p.own.forEachAt { _, v, at -> steps += Jar.Step(at, v, Jar.OWN) }
        p.smart.forEachAt { _, v, at -> steps += Jar.Step(at, v, Jar.SMART) }
        p.rejected.forEachAt { _, v, at -> steps += Jar.Step(at, v, Jar.REJECT) }
        return Jar.fold(steps) { clock.factor(a, bSong, it) }.coerceIn(Jar.LOW, Jar.HIGH)
    }

    /** S⁺: the listener's own session strength plus SMART's own, at most a quarter of it (positives only). */
    private fun positive(p: PairAcc): Double {
        val own = p.own.sum()
        return own + min(p.smart.sum(), PersonalModel.SMART_SHARE * own)
    }

    /** The pair's occurrence weight for the lift filter, SMART's own capped like the strength. */
    private fun weightOf(p: PairAcc): Double {
        val cap = if (p.weightSmart > 0.0) min(1.0, PersonalModel.SMART_SHARE * p.weightOwn / p.weightSmart) else 0.0
        return p.weightOwn + cap * p.weightSmart
    }

    private fun pack(songs: Int, skips: FloatArray, affinityStrength: FloatArray, affinitySessions: ByteArray, isEmpty: Boolean): PersonalModel {
        val out = FloatArray(songs)
        val squares = DoubleArray(songs)
        val inbound = FloatArray(songs)
        var total = 0.0
        for ((k, p) in pairs) {
            val a = (k ushr 32).toInt()
            val b = (k and 0xffffffffL).toInt()
            val cap = if (p.weightSmart > 0.0) min(1.0, PersonalModel.SMART_SHARE * p.weightOwn / p.weightSmart) else 0.0
            val w = weightOf(p)
            out[a] += w.toFloat()
            squares[a] += p.squaresOwn + cap * cap * p.squaresSmart
            inbound[b] += w.toFloat()
            total += w
        }
        val effective = FloatArray(songs) { a -> if (squares[a] > 0.0) (out[a].toDouble() * out[a] / squares[a]).toFloat() else 0f }
        // Keep only links that can change a term: two own sessions (habit, D1) or two rejecting sessions.
        val bySource = LinkedHashMap<Int, ArrayList<Pair<Int, PairAcc>>>()
        for ((k, p) in pairs) {
            if (p.own.size < PersonalModel.MIN_SESSIONS && p.rejected.size < PersonalModel.MIN_SESSIONS) continue
            bySource.getOrPut((k ushr 32).toInt()) { ArrayList() } += (k and 0xffffffffL).toInt() to p
        }
        val start = IntArray(songs + 1)
        val kept = ArrayList<Pair<Int, PairAcc>>()
        val keptKeys = ArrayList<Long>()
        for (a in 0 until songs) {
            start[a] = kept.size
            val list = bySource[a] ?: continue
            list.sortWith(compareByDescending<Pair<Int, PairAcc>> { positive(it.second) + it.second.rejected.sum() }.thenBy { it.first })
            for (link in list.take(PersonalModel.TOP_PER_SONG).sortedBy { it.first }) {
                kept += link
                keptKeys += key(a, link.first)
            }
        }
        start[songs] = kept.size
        val artistBySource = LinkedHashMap<Int, ArrayList<Pair<Int, PairAcc>>>()
        for ((k, p) in artistPairs) {
            if (p.own.size < PersonalModel.MIN_SESSIONS) continue
            artistBySource.getOrPut((k ushr 32).toInt()) { ArrayList() } += (k and 0xffffffffL).toInt() to p
        }
        val artistStart = IntArray(artistCount + 1)
        val artistKept = ArrayList<Pair<Int, PairAcc>>()
        for (x in 0 until artistCount) {
            artistStart[x] = artistKept.size
            val list = artistBySource[x] ?: continue
            list.sortWith(compareByDescending<Pair<Int, PairAcc>> { positive(it.second) }.thenBy { it.first })
            artistKept += list.take(PersonalModel.TOP_PER_ARTIST).sortedBy { it.first }
        }
        artistStart[artistCount] = artistKept.size
        val tracks = library.map { PersonalModel.hash(it.id.value) to it.id }.distinctBy { it.first }.sortedBy { it.first }
        val trackArtistStart = IntArray(tracks.size + 1)
        val trackArtistList = ArrayList<Int>()
        for ((i, t) in tracks.withIndex()) {
            trackArtistStart[i] = trackArtistList.size
            trackArtistList += artistsOfTrack.getValue(t.second).toList()
        }
        trackArtistStart[tracks.size] = trackArtistList.size
        return PersonalModel(
            songCount = songs,
            trackHashes = LongArray(tracks.size) { tracks[it].first },
            trackSong = IntArray(tracks.size) { songOfTrack.getValue(tracks[it].second) },
            trackArtistStart = trackArtistStart,
            trackArtistList = trackArtistList.toIntArray(),
            edgeStart = start,
            edgeTo = IntArray(kept.size) { kept[it].first },
            edgePositive = ShortArray(kept.size) { half(positive(kept[it].second).toFloat()) },
            edgeOwn = ShortArray(kept.size) { half(kept[it].second.own.sum().toFloat()) },
            edgeSessions = ByteArray(kept.size) { kept[it].second.own.size.coerceAtMost(255).toByte() },
            edgeNegative = ShortArray(kept.size) { half(kept[it].second.rejected.sum().toFloat()) },
            edgeNegativeSessions = ByteArray(kept.size) { kept[it].second.rejected.size.coerceAtMost(255).toByte() },
            edgeJar = ShortArray(kept.size) { half(jarOf(keptKeys[it], kept[it].second).toFloat()) },
            edgeDays = ByteArray(kept.size) { kept[it].second.days.size.coerceAtMost(255).toByte() },
            edgeWeight = ShortArray(kept.size) { half(weightOf(kept[it].second).toFloat()) },
            outWeight = out,
            outEffective = effective,
            inWeight = inbound,
            totalWeight = total.toFloat(),
            earlySkips = skips,
            affinityStrength = affinityStrength,
            affinitySessions = affinitySessions,
            artistStart = artistStart,
            artistTo = IntArray(artistKept.size) { artistKept[it].first },
            artistPositive = ShortArray(artistKept.size) { half(positive(artistKept[it].second).toFloat()) },
            artistSessions = ByteArray(artistKept.size) { artistKept[it].second.own.size.coerceAtMost(255).toByte() },
            isEmpty = isEmpty,
        )
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 180_000L
        const val IMPORT_SESSION_BASE = 1L shl 40
        const val GRAM_SESSION_BASE = 1L shl 50
    }
}

private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)

/**
 * The jar (PLAN_PERSONAL v6, v6.1): b(A → B) as a time-ordered fold of the pair's sessions. In the natural form each step is
 * b ← clamp(b · 2^(−Δc) ± g, −1.8, 6) on the pair's clock c; here every value is already decayed to the reference, so the
 * same fold is B ← clamp(B ± V, −1.8 w, 6 w) with w the step's own decay to the reference. A SMART positive adds at most a quarter of the own
 * positives so far minus SMART's so far (causal), and rejections are held until the pair's second rejection session.
 */
internal object Jar {
    const val OWN = 0
    const val SMART = 1
    const val REJECT = 2
    /** [PersonalModel.JAR_MIN] and [PersonalModel.JAR_MAX] exactly (a Float −1.8 is −1.79999995). */
    const val LOW = -1.8
    const val HIGH = 6.0

    /** One session's contribution: [value] is its grade decayed to the reference, [atMs] when the session first showed it. */
    class Step(val atMs: Long, val value: Double, val kind: Int)

    /** The fold of [steps] (any order; sorted by time here); [decay] is a time's factor to the reference. Every step is clamped; one jar for both sources (D1). */
    fun fold(steps: List<Step>, decay: (Long) -> Double): Double {
        if (steps.isEmpty()) return 0.0
        val ordered = steps.sortedWith(compareBy<Step> { it.atMs }.thenBy { it.kind })
        val low = LOW
        val high = HIGH
        var b = 0.0
        var own = 0.0
        var smart = 0.0
        var rejections = 0
        var held = 0.0
        for (step in ordered) {
            val w = decay(step.atMs)
            when (step.kind) {
                OWN -> {
                    own += step.value
                    b += step.value
                }
                SMART -> {
                    val added = min(step.value, max(0.0, PersonalModel.SMART_SHARE * own - smart))
                    smart += added
                    b += added
                }
                else -> {
                    rejections++
                    if (rejections < PersonalModel.MIN_SESSIONS) {
                        held += step.value
                        continue
                    }
                    b -= held + step.value
                    held = 0.0
                }
            }
            b = b.coerceIn(low * w, high * w)
        }
        return b
    }
}

/**
 * The A-day clocks (PLAN_PERSONAL v6.1, one for both sources since the LB-import fixes). Per anchor A and pair A → B the clock reads
 * c(t) = elapsed calendar days / 730 + Σ over A's local days up to t of w_d(B) / 20, where w_d(B) is the strongest class
 * among that day's followers of A other than B. Evidence for A → B decays from t to the reference [refMs] (the source's
 * reference) by 2^(−(c(ref) − c(t))): a habit fades on the days the listener plays A and goes elsewhere, and only slowly
 * with the calendar. One clock serves both sources; [follow] in any order, then [finish] before any [factor].
 */
internal class DayClock(private val localDay: (Long) -> Long, private val refMs: Long) {
    private class Anchor {
        /** Follows as they come (two sources, so not in time order): (time, follower, class); emptied by [finish]. */
        var pending = ArrayList<Triple<Long, Int, Double>>()
        /** A's local days with a follower, ascending, and the prefix sums of each day's strongest class. */
        var days = LongArray(0)
        var prefix = DoubleArray(0)
        /** Per follower song: the day positions where it was the strongest, and the cumulative (best − second) there. */
        val corrections = LinkedHashMap<Int, Pair<IntArray, DoubleArray>>()
    }

    private val anchors = LinkedHashMap<Int, Anchor>()

    /** A day's follower [follower] of [anchor] heard at [atMs] (the anchor's start), with its class [weight]; any order. */
    fun follow(anchor: Int, atMs: Long, follower: Int, weight: Double) {
        if (weight <= 0.0) return
        anchors.getOrPut(anchor) { Anchor() }.pending += Triple(atMs, follower, weight)
    }

    fun finish() {
        for (a in anchors.values) {
            // Per day: the strongest follower's class and song, and the strongest class of the others (build phase only).
            val days = ArrayList<Long>()
            val best = ArrayList<Double>()
            val bestSong = ArrayList<Int>()
            val second = ArrayList<Double>()
            var openDay = Long.MIN_VALUE
            val open = LinkedHashMap<Int, Double>()
            fun close() {
                if (open.isEmpty()) return
                var top = -1
                var topWeight = 0.0
                for ((song, w) in open) if (w > topWeight) { top = song; topWeight = w }
                var other = 0.0
                for ((song, w) in open) if (song != top && w > other) other = w
                days += openDay
                best += topWeight
                bestSong += top
                second += other
                open.clear()
            }
            // Device and import follows interleave in time: sorted (stable) before the days are formed.
            for ((atMs, follower, weight) in a.pending.sortedBy { it.first }) {
                val day = localDay(atMs)
                if (day != openDay) {
                    close()
                    openDay = day
                }
                open[follower] = max(open[follower] ?: 0.0, weight)
            }
            a.pending = ArrayList(0)
            close()
            a.days = days.toLongArray()
            a.prefix = DoubleArray(days.size + 1)
            for (i in days.indices) a.prefix[i + 1] = a.prefix[i] + best[i]
            val at = LinkedHashMap<Int, ArrayList<Int>>()
            for (i in days.indices) if (second[i] < best[i]) at.getOrPut(bestSong[i]) { ArrayList() } += i
            for ((song, positions) in at) {
                val cumulative = DoubleArray(positions.size)
                var sum = 0.0
                positions.forEachIndexed { j, i -> sum += best[i] - second[i]; cumulative[j] = sum }
                a.corrections[song] = positions.toIntArray() to cumulative
            }
        }
    }

    /** 2^(−(c(ref) − c(t))) for A → B: the factor evidence of the pair heard at [atMs] keeps at the reference. */
    fun factor(anchor: Int, b: Int, atMs: Long): Double {
        var delta = (refMs - atMs).coerceAtLeast(0L).toDouble() / (PersonalModel.CLOCK_CALENDAR_DAYS * PersonalModel.DAY_MS)
        val a = anchors[anchor]
        if (a != null && a.days.isNotEmpty()) {
            // A-days up to and including the day of [atMs] are already behind it; the rest lie between it and the reference.
            val k = upperBound(a.days, localDay(atMs))
            val total = a.prefix[a.days.size] - correction(a, b, a.days.size)
            val before = a.prefix[k] - correction(a, b, k)
            delta += (total - before) / PersonalModel.CLOCK_A_DAYS
        }
        return 0.5.pow(delta)
    }

    /** The part of A's first [k] day weights that came from [b] itself (B is no "elsewhere" for its own pair). */
    private fun correction(a: Anchor, b: Int, k: Int): Double {
        val (positions, cumulative) = a.corrections[b] ?: return 0.0
        var low = 0
        var high = positions.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (positions[mid] < k) low = mid + 1 else high = mid
        }
        return if (low == 0) 0.0 else cumulative[low - 1]
    }

    private fun upperBound(days: LongArray, day: Long): Int {
        var low = 0
        var high = days.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (days[mid] <= day) low = mid + 1 else high = mid
        }
        return low
    }
}

/** On-device album order, or an import's: the same release, the next track (a disc's first after the previous disc). */
internal fun albumNext(a: PersonalTrack, b: PersonalTrack): Boolean {
    val album = a.album ?: return false
    if (album != b.album) return false
    val na = a.trackNumber ?: return false
    val nb = b.trackNumber ?: return false
    val da = a.discNumber ?: 1
    val db = b.discNumber ?: 1
    return (db == da && nb == na + 1) || (db == da + 1 && nb == 1)
}

/** IEEE half precision, round to nearest: the store's link weights (three significant digits are plenty). */
internal fun half(value: Float): Short {
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    val exponent = ((bits ushr 23) and 0xff) - 127 + 15
    var mantissa = bits and 0x7fffff
    if (exponent <= 0) {
        if (exponent < -10) return sign.toShort()
        mantissa = (mantissa or 0x800000) shr (1 - exponent)
        return (sign or ((mantissa + 0x1000) shr 13)).toShort()
    }
    if (exponent >= 31) return (sign or 0x7bff).toShort()
    return ((sign or (exponent shl 10) or (mantissa shr 13)) + ((mantissa shr 12) and 1)).toShort()
}

internal fun unhalf(value: Short): Float {
    val h = value.toInt() and 0xffff
    val sign = if (h and 0x8000 != 0) -1f else 1f
    val exponent = (h ushr 10) and 0x1f
    val mantissa = h and 0x3ff
    return when (exponent) {
        0 -> sign * mantissa * HALF_SUBNORMAL
        else -> Float.fromBits(((h and 0x8000) shl 16) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
    }
}

private const val HALF_SUBNORMAL = 5.9604645e-8f

/**
 * What one SMART plan personalises with ("Learn from my habits" on): the [model], the listener's hearts and own
 * (unmarked) playlists, how recency meets a strong habit ([recency]), and where to report picks the personal term changed.
 */
public class PersonalInputs(
    public val model: PersonalModel,
    public val hearts: Set<TrackId> = emptySet(),
    public val playlists: List<Set<TrackId>> = emptyList(),
    public val recency: RecencyMode = RecencyMode.NOVELTY,
    public val onPersonalChanged: (Set<TrackId>) -> Unit = {},
    /** Research: the artist-level term (A3) on or off; the app always leaves it on. */
    public val artistTerm: Boolean = true,
)

/**
 * How recency meets a strong habit (the owner's D1 seam). [NOVELTY], shipped: recency is a novelty rule, so δ and the
 * mask are measured without it and a D1-strong habit relieves its penalty in proportion to its strength. [CREDIT]: recency
 * stays in the core and a D1-strong habit earns a tie-break credit of at most δ.
 */
public enum class RecencyMode { NOVELTY, CREDIT }

/** Research: every hop the personal stage decided (the pick's mask-core gap to the best in δ, whether it changed). */
public object PersonalDiagnostics {
    public var sink: ((gapOverDelta: Float, changed: Boolean) -> Unit)? = null

    /** Research: each hop's δ, every scored candidate's mask-core gap to the best in δ, and its personal addition. */
    public var hop: ((delta: Float, gaps: Map<TrackId, Float>, additions: Map<TrackId, Float>) -> Unit)? = null

    /** Research (the v6 FMA gate): per staged hop, how many scored candidates pass the cluster guard after the anchor. */
    public var guard: ((passed: Int, total: Int) -> Unit)? = null
}
