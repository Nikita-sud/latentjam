/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** How a listen started, as the personal evidence reads it (the history's ListenStart, plus the legacy and import cases). */
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
 * @param smartPlaced SMART planned the track (the listen's plan position)
 * @param personalChanged the personal term changed that planned pick
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
    public val smartPlaced: Boolean = false,
    public val personalChanged: Boolean = false,
    public val albumParent: Boolean = false,
    public val shuffleOff: Boolean = false,
    /** For legacy listens without a listen time: the furthest position played. */
    public val playedMs: Long = 0L,
)

/**
 * The listener's personal evidence (PERSONALISATION_RULES.md, PLAN_PERSONAL.md): graded transitions A → B between version
 * groups, their rejections, the artist-level links, and per-song recency, skips and affinity. A cache rebuilt from the
 * history log: nothing here is stored, and clearing the history clears it.
 *
 * Packed and bounded: songs and artists are 64-bit key hashes in sorted arrays, and each song keeps its [TOP_PER_SONG]
 * strongest successors (each artist its [TOP_PER_ARTIST]) in flat arrays. Weights are decayed to each source's own
 * newest listen ([HALF_LIFE_MS]), so an old import is not zeroed by on-device listening and the cache needs no clock.
 */
public class PersonalModel internal constructor(
    private val songHashes: LongArray,
    /** Library tracks by the hash of their id, sorted, with each one's song and artists (CSR). */
    private val trackHashes: LongArray,
    private val trackSong: IntArray,
    private val trackArtistStart: IntArray,
    private val trackArtistList: IntArray,
    private val edgeStart: IntArray,
    private val edgeTo: IntArray,
    /** Half-precision weights ([half]): raw positive, countable, far-countable and negative. */
    private val edgeWeight: ShortArray,
    private val edgeCountable: ShortArray,
    private val edgeFar: ShortArray,
    private val edgeNegative: ShortArray,
    private val edgeSessions: ByteArray,
    private val edgeFarSessions: ByteArray,
    /** Days since the epoch of the last far-countable session; 0 = none. */
    private val edgeFarDay: ShortArray,
    private val outWeight: FloatArray,
    private val outCountable: FloatArray,
    private val outFar: FloatArray,
    private val inWeight: FloatArray,
    private val totalWeight: Float,
    /** Minutes since the epoch of the song's latest listen; [Int.MIN_VALUE] = never. */
    private val lastListenMinute: IntArray,
    private val earlySkips: FloatArray,
    private val affinity: FloatArray,
    private val artistStart: IntArray,
    private val artistTo: IntArray,
    private val artistWeight: FloatArray,
    private val artistOut: FloatArray,
    private val artistIn: FloatArray,
    private val artistTotal: Float,
    /** Whether any listen was read: no history means no personal term at all (C1). */
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

    /**
     * habit_track(A → B) in [0, 1]: smoothed P(B|A) times its clipped log lift over B's base rate (A1, A2), on countable
     * evidence: SMART's own placements count up to [SMART_SHARE] of the listener's own, so SMART never teaches itself.
     */
    public fun habit(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        return habitOf(unhalf(edgeCountable[e]), outCountable[a], inWeight[b], totalWeight, songHashes.size)
    }

    /** Diagnostics and tests: A → B's countable weight, which the habit reads. */
    public fun countableWeight(a: Int, b: Int): Float = edge(a, b).takeIf { it >= 0 }?.let { unhalf(edgeCountable[it]) } ?: 0f

    /** habit at the artist level (A3): the strongest link from one of [from]'s artists to one of [to]'s. */
    public fun artistHabit(from: IntArray, to: IntArray): Float {
        if (from.isEmpty() || to.isEmpty()) return 0f
        var best = 0f
        for (x in from) for (e in artistStart[x] until artistStart[x + 1]) {
            if (artistTo[e] in to) {
                best = max(best, habitOf(artistWeight[e], artistOut[x], artistIn[artistTo[e]], artistTotal, artistIn.size))
            }
        }
        return best
    }

    public fun artistHabit(from: TrackId, to: TrackId): Float = artistHabit(artistsOf(from), artistsOf(to))

    /** Rejection of B after A, in [0, 1): early skips of B offered right after A (E4). */
    public fun rejection(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        val n = unhalf(edgeNegative[e])
        return if (n <= 0f) 0f else n / (n + unhalf(edgeWeight[e]) + ALPHA)
    }

    /** The Wilson lower bound of P(B|A) on countable evidence (SMART's own capped at [SMART_SHARE]). */
    public fun confidence(a: Int, b: Int): Float {
        val e = edge(a, b).takeIf { it >= 0 } ?: return 0f
        return wilson(unhalf(edgeCountable[e]), outCountable[a])
    }

    /** Distinct sessions with countable evidence for A → B. */
    public fun sessions(a: Int, b: Int): Int = edge(a, b).takeIf { it >= 0 }?.let { edgeSessions[it].toInt() and 0xff } ?: 0

    /** D1: a strong, repeated link, for which recency is relieved by its confidence. */
    public fun strong(a: Int, b: Int): Boolean = sessions(a, b) >= D1_SESSIONS && confidence(a, b) >= D1_LCB

    /** D2 far habits from A at [nowMs], best first: on-device, non-SMART evidence only, repeated and recent. */
    public fun farHabits(a: Int, nowMs: Long): IntArray {
        if (a < 0) return IntArray(0)
        val nowDay = (nowMs / DAY_MS).toInt()
        val out = ArrayList<Pair<Int, Float>>()
        for (e in edgeStart[a] until edgeStart[a + 1]) {
            if ((edgeFarSessions[e].toInt() and 0xff) < D2_SESSIONS) continue
            val farDay = edgeFarDay[e].toInt() and 0xffff
            if (farDay == 0 || nowDay - farDay > D2_RECENT_DAYS) continue
            val lcb = wilson(unhalf(edgeFar[e]), outFar[a])
            if (lcb >= D2_LCB) out += edgeTo[e] to lcb
        }
        return out.sortedByDescending { it.second }.map { it.first }.toIntArray()
    }

    /** g(age) of the song's latest non-skipped listen at [nowMs] (O3): 1 this session down to 0 after 90 days. */
    public fun recency(song: Int, nowMs: Long): Float {
        if (song < 0) return 0f
        val last = lastListenMinute[song]
        if (last == Int.MIN_VALUE) return 0f
        val age = (nowMs - last.toLong() * MINUTE_MS).coerceAtLeast(0L)
        return when {
            age <= SESSION_GAP_MS -> 1f
            age < DAY_MS -> 0.60f
            age < 7 * DAY_MS -> 0.26f
            age < 30 * DAY_MS -> 0.097f
            age < 90 * DAY_MS -> 0.036f
            else -> 0f
        }
    }

    /** The song's track prior in δ units (O2 + E7 + affinity), within [−0.5, +0.15]. */
    public fun trackPrior(song: Int, hearted: Boolean): Float {
        val skips = if (song >= 0) earlySkips[song] else 0f
        val liked = if (song >= 0) min(affinity[song], 3f) else 0f
        return ((if (hearted) HEART else 0f) + AFFINITY * liked - SKIP * skips).coerceIn(PRIOR_MIN, PRIOR_MAX)
    }

    /** Bytes the packed arrays retain (the store budget). */
    public fun retainedBytes(): Long =
        8L * (songHashes.size + trackHashes.size) +
            4L * (trackSong.size + trackArtistStart.size + trackArtistList.size + edgeStart.size + edgeTo.size +
                outWeight.size + outCountable.size + outFar.size + inWeight.size + lastListenMinute.size + earlySkips.size +
                affinity.size + artistStart.size + artistTo.size + artistWeight.size + artistOut.size + artistIn.size) +
            2L * (edgeWeight.size + edgeCountable.size + edgeFar.size + edgeNegative.size + edgeFarDay.size) +
            edgeSessions.size + edgeFarSessions.size

    /** Diagnostics: how many links the store keeps. */
    public fun sizes(): String = "songs=${songHashes.size} edges=${edgeTo.size} artistEdges=${artistTo.size} tracks=${trackHashes.size}"

    /** Diagnostics and tests: A → B's decayed positive weight (the stored links only). */
    public fun weight(a: Int, b: Int): Float = edge(a, b).takeIf { it >= 0 }?.let { unhalf(edgeWeight[it]) } ?: 0f

    public companion object {
        // PLAN_PERSONAL.md §2–§4, frozen before any test.
        public const val PICK: Float = 1.0f
        public const val PICK_QUEUE: Float = 0.7f
        public const val PICK_UNKNOWN: Float = 0.85f
        public const val FOR_YOU_FACTOR: Float = 0.5f
        public const val ACCEPT: Float = 0.25f
        public const val SMART_FACTOR: Float = 0.5f
        public const val ALBUM_FACTOR: Float = 0.1f
        public const val ATTENTION_FREE: Int = 5
        public const val ATTENTION_DECAY: Float = 0.7f
        public const val IMPORT: Float = 0.4f
        public const val REPLAY_FACTOR: Float = 0.2f
        public const val SKIP_EARLY: Float = -1.0f
        public const val SKIP_LATE: Float = -0.5f
        public const val ACCIDENTAL_SKIP_MS: Long = 2_000L
        public const val EARLY_SKIP_MS: Long = 10_000L
        public const val SHORT_TRACK_MS: Long = 60_000L
        public const val LONG_TRACK_MS: Long = 20L * 60_000L
        public const val SESSION_GAP_MS: Long = 15L * 60_000L
        public const val HALF_LIFE_MS: Long = 120L * DAY_MS_CONST
        public const val IMPORT_CAP_PICKS: Int = 300
        public const val ALPHA: Float = 4f
        public const val LIFT_MAX: Float = 3f
        public const val Z: Float = 1.645f
        public const val SMART_SHARE: Float = 0.25f
        public const val D1_LCB: Float = 0.2f
        public const val D1_SESSIONS: Int = 3
        public const val D2_LCB: Float = 0.3f
        public const val D2_SESSIONS: Int = 6
        public const val D2_RECENT_DAYS: Int = 60
        public const val TOP_PER_SONG: Int = 16
        public const val TOP_PER_ARTIST: Int = 8
        public const val HEART: Float = 0.15f
        public const val AFFINITY: Float = 0.05f
        public const val SKIP: Float = 0.1f
        public const val PRIOR_MIN: Float = -0.5f
        public const val PRIOR_MAX: Float = 0.15f
        internal const val DAY_MS: Long = DAY_MS_CONST
        private const val MINUTE_MS = 60_000L
        private val NO_ARTISTS = IntArray(0)

        public val EMPTY: PersonalModel = build(emptyList(), emptyList())

        internal fun habitOf(w: Float, out: Float, inbound: Float, total: Float, keys: Int): Float {
            if (w <= 0f) return 0f
            val p = w / (out + ALPHA)
            val q = (inbound + 1f) / (total + keys.coerceAtLeast(1))
            val lift = ln((p / q).toDouble()).toFloat().coerceIn(0f, LIFT_MAX)
            return p * lift / LIFT_MAX
        }

        internal fun wilson(hits: Float, n: Float): Float {
            if (n <= 0f || hits <= 0f) return 0f
            val p = (hits / n).coerceIn(0f, 1f).toDouble()
            val nn = n.toDouble()
            val z2 = (Z * Z).toDouble()
            val centre = p + z2 / (2 * nn)
            val margin = Z * sqrt(p * (1 - p) / nn + z2 / (4 * nn * nn))
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

        /** Builds the model from [listens] (oldest first) over [library]; listens of tracks outside it are skipped. */
        public fun build(listens: List<PersonalListen>, library: List<PersonalTrack>): PersonalModel =
            PersonalBuilder(listens, library).build()
    }
}

private const val DAY_MS_CONST: Long = 86_400_000L

/** One A → B pair while building. */
private class EdgeAcc {
    var device = 0.0
    var smart = 0.0
    var imported = 0.0
    var far = 0.0
    var negative = 0.0
    var sessions = 0
    var lastSession = -1L
    var farSessions = 0
    var lastFarSession = -1L
    var lastFarMs = Long.MIN_VALUE
}

private class PersonalBuilder(private val listens: List<PersonalListen>, private val library: List<PersonalTrack>) {
    private val byId = HashMap<TrackId, PersonalTrack>(library.size * 2)
    private val songIndex = HashMap<Long, Int>()
    private val songOfTrack = HashMap<TrackId, Int>(library.size * 2)
    private val artistIndex = HashMap<Long, Int>()
    private val artistsOfTrack = HashMap<TrackId, IntArray>(library.size * 2)
    private val edges = HashMap<Long, EdgeAcc>()
    private val artistEdges = HashMap<Long, DoubleArray>()

    fun build(): PersonalModel {
        // Songs in sorted hash order, so indices do not depend on library order (the JVM / iOS golden).
        val hashes = library.map { PersonalModel.hash(it.key) }.distinct().sorted()
        hashes.forEachIndexed { i, h -> songIndex[h] = i }
        val artistHashes = library.flatMap { t -> t.artists.filter(String::isNotEmpty).map(PersonalModel::hash) }.distinct().sorted()
        artistHashes.forEachIndexed { i, h -> artistIndex[h] = i }
        for (t in library) {
            byId[t.id] = t
            songOfTrack[t.id] = songIndex.getValue(PersonalModel.hash(t.key))
            artistsOfTrack[t.id] = t.artists.filter(String::isNotEmpty).map { artistIndex.getValue(PersonalModel.hash(it)) }
                .distinct().sorted().toIntArray()
        }
        val songs = hashes.size
        val lastListen = LongArray(songs) { Long.MIN_VALUE }
        val skips = FloatArray(songs)
        val affinity = FloatArray(songs)

        val known = listens.filter { byId[it.trackId] != null }
        val device = known.filter { it.start != PersonalStart.IMPORTED }
        val imported = known.filter { it.start == PersonalStart.IMPORTED }
        val deviceRef = device.maxOfOrNull { it.startedAtMs } ?: 0L
        val importRef = imported.maxOfOrNull { it.startedAtMs } ?: 0L
        fun decay(at: Long, ref: Long) = 0.5.pow((ref - at).coerceAtLeast(0L).toDouble() / PersonalModel.HALF_LIFE_MS)

        for (l in known) {
            if (l.skipped) continue
            val s = songOfTrack.getValue(l.trackId)
            if (l.startedAtMs > lastListen[s]) lastListen[s] = l.startedAtMs
        }

        // On-device listens: picks, acceptances, skip-then-pick, repeats.
        var session = 0L
        var lastEnd = Long.MIN_VALUE
        var anchor: PersonalListen? = null
        var anchorEnd = Long.MIN_VALUE
        var rejected: PersonalListen? = null
        var autoStreak = 0
        for (l in device) {
            val track = byId.getValue(l.trackId)
            val duration = track.durationMs
            if (duration != null && duration > PersonalModel.LONG_TRACK_MS) continue
            val heard = l.listenedMs ?: l.playedMs
            if (lastEnd != Long.MIN_VALUE && l.startedAtMs - lastEnd > PersonalModel.SESSION_GAP_MS) {
                session++
                anchor = null
                rejected = null
                autoStreak = 0
            }
            lastEnd = max(lastEnd, l.startedAtMs + heard)
            val song = songOfTrack.getValue(l.trackId)
            val w = decay(l.startedAtMs, deviceRef)
            if (l.skipped) {
                if (heard < PersonalModel.ACCIDENTAL_SKIP_MS) continue
                skips[song] += w.toFloat()
                val a = anchor
                if (rejected == null && a != null && l.startedAtMs - anchorEnd <= PersonalModel.SESSION_GAP_MS &&
                    songOfTrack.getValue(a.trackId) != song
                ) {
                    rejected = l
                    val grade = if (heard < PersonalModel.EARLY_SKIP_MS) PersonalModel.SKIP_EARLY else PersonalModel.SKIP_LATE
                    edgeOf(a.trackId, l.trackId).negative += -grade * w
                }
                continue
            }
            autoStreak = if (l.start == PersonalStart.AUTO) autoStreak + 1 else 0
            val a = anchor
            if (l.start == PersonalStart.REPEAT || l.start == PersonalStart.SKIP_PREVIOUS) {
                affinity[song] += w.toFloat()
            } else if (a != null && l.startedAtMs - anchorEnd <= PersonalModel.SESSION_GAP_MS) {
                if (songOfTrack.getValue(a.trackId) == song) {
                    affinity[song] += w.toFloat()
                } else {
                    val grade = deviceGrade(a, l, afterRejection = rejected != null, autoStreak)
                    if (grade > 0.0) {
                        val e = edgeOf(a.trackId, l.trackId)
                        val value = grade * w
                        val ownPick = l.smartPlaced || l.surface == PersonalSurface.FOR_YOU
                        if (ownPick) e.smart += value else e.device += value
                        if (!ownPick && e.lastSession != session) {
                            e.sessions++
                            e.lastSession = session
                        }
                        // D2 counts the listener's own picks on this device only.
                        val pick = l.start == PersonalStart.PICK || l.start == PersonalStart.QUEUED
                        if (pick && !ownPick) {
                            e.far += value
                            if (e.lastFarSession != session) {
                                e.farSessions++
                                e.lastFarSession = session
                            }
                            e.lastFarMs = max(e.lastFarMs, l.startedAtMs)
                        }
                        addArtists(a.trackId, l.trackId, value)
                    }
                }
            }
            anchor = l
            anchorEnd = l.startedAtMs + heard
            rejected = null
        }

        // Imports: scrobbles of unknown source, counted by session; album order and replayed sequences discounted.
        val importRuns = ArrayList<List<PersonalListen>>()
        run {
            var run = ArrayList<PersonalListen>()
            var end = Long.MIN_VALUE
            for (l in imported) {
                val duration = byId.getValue(l.trackId).durationMs
                if (duration != null && duration > PersonalModel.LONG_TRACK_MS) continue
                if (end != Long.MIN_VALUE && l.startedAtMs - end > PersonalModel.SESSION_GAP_MS) {
                    if (run.isNotEmpty()) importRuns += run
                    run = ArrayList()
                }
                run += l
                end = l.startedAtMs + (duration ?: DEFAULT_DURATION_MS)
            }
            if (run.isNotEmpty()) importRuns += run
        }
        val gramSessions = HashMap<Triple<Int, Int, Int>, Int>()
        for (run in importRuns) {
            val seen = HashSet<Triple<Int, Int, Int>>()
            for (i in 2 until run.size) seen += gram(run, i - 2)
            for (g in seen) gramSessions[g] = (gramSessions[g] ?: 0) + 1
        }
        val importValues = ArrayList<Triple<TrackId, TrackId, Double>>()
        for ((r, run) in importRuns.withIndex()) {
            for (i in 1 until run.size) {
                val a = run[i - 1]
                val b = run[i]
                val sa = songOfTrack.getValue(a.trackId)
                val sb = songOfTrack.getValue(b.trackId)
                if (sa == sb) continue
                var grade = PersonalModel.IMPORT.toDouble()
                if (albumNext(byId.getValue(a.trackId), byId.getValue(b.trackId))) grade *= PersonalModel.ALBUM_FACTOR
                val replayed = (i >= 2 && (gramSessions[gram(run, i - 2)] ?: 0) >= 2) ||
                    (i + 1 < run.size && (gramSessions[gram(run, i - 1)] ?: 0) >= 2)
                if (replayed) grade *= PersonalModel.REPLAY_FACTOR
                importValues += Triple(a.trackId, b.trackId, grade * decay(b.startedAtMs, importRef))
                val e = edgeOf(a.trackId, b.trackId)
                if (e.lastSession != r.toLong() + IMPORT_SESSION_BASE) {
                    e.sessions++
                    e.lastSession = r.toLong() + IMPORT_SESSION_BASE
                }
            }
        }
        val importTotal = importValues.sumOf { it.third }
        val cap = PersonalModel.IMPORT_CAP_PICKS * PersonalModel.PICK_UNKNOWN.toDouble()
        val importScale = if (importTotal > cap) cap / importTotal else 1.0
        for ((a, b, value) in importValues) {
            edgeOf(a, b).imported += value * importScale
            addArtists(a, b, value * importScale)
        }
        return pack(hashes.toLongArray(), lastListen, skips, affinity, isEmpty = known.isEmpty())
    }

    /** The positive grade of A → B for an on-device listen B (PLAN_PERSONAL §2), before decay. */
    private fun deviceGrade(a: PersonalListen, b: PersonalListen, afterRejection: Boolean, autoStreak: Int): Double {
        if (b.personalChanged) return 0.0
        fun pickGrade(): Double = when {
            b.start == PersonalStart.QUEUED || b.surface == PersonalSurface.QUEUE -> PersonalModel.PICK_QUEUE.toDouble()
            b.surface == PersonalSurface.FOR_YOU -> (PersonalModel.PICK_QUEUE * PersonalModel.FOR_YOU_FACTOR).toDouble()
            b.surface == null -> PersonalModel.PICK_UNKNOWN.toDouble()
            else -> PersonalModel.PICK.toDouble()
        }
        if (b.start == PersonalStart.PICK || b.start == PersonalStart.QUEUED) return pickGrade()
        if (!b.completed) return 0.0
        val smart = if (b.smartPlaced) PersonalModel.SMART_FACTOR.toDouble() else 1.0
        // A song the listener stopped skipping at: chosen by skipping, E2 strength (E4's positive half).
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

    private fun gram(run: List<PersonalListen>, i: Int) =
        Triple(songOfTrack.getValue(run[i].trackId), songOfTrack.getValue(run[i + 1].trackId), songOfTrack.getValue(run[i + 2].trackId))

    private fun edgeOf(a: TrackId, b: TrackId): EdgeAcc {
        val key = (songOfTrack.getValue(a).toLong() shl 32) or songOfTrack.getValue(b).toLong()
        return edges.getOrPut(key) { EdgeAcc() }
    }

    private fun addArtists(a: TrackId, b: TrackId, value: Double) {
        val from = artistsOfTrack.getValue(a)
        val to = artistsOfTrack.getValue(b)
        if (from.isEmpty() || to.isEmpty()) return
        val share = value / (from.size * to.size)
        for (x in from) for (y in to) {
            if (x == y) continue
            artistEdges.getOrPut((x.toLong() shl 32) or y.toLong()) { DoubleArray(1) }[0] += share
        }
    }

    private fun pack(hashes: LongArray, lastListen: LongArray, skips: FloatArray, affinity: FloatArray, isEmpty: Boolean): PersonalModel {
        val songs = hashes.size
        val out = FloatArray(songs)
        val outCountable = FloatArray(songs)
        val outFar = FloatArray(songs)
        val inbound = FloatArray(songs)
        var total = 0.0
        val bySource = HashMap<Int, ArrayList<Pair<Int, EdgeAcc>>>()
        for ((key, e) in edges) {
            val a = (key ushr 32).toInt()
            val b = (key and 0xffffffffL).toInt()
            val positive = e.device + e.smart + e.imported
            out[a] += positive.toFloat()
            outCountable[a] += countable(e).toFloat()
            outFar[a] += e.far.toFloat()
            // The base rate the lift divides by, on the same countable evidence as the habit.
            inbound[b] += countable(e).toFloat()
            total += countable(e)
            // A link of SMART's own placements alone changes no term (the habit, D1, D2 and rejections read countable
            // evidence), so the store keeps only links with the listener's own evidence or a rejection.
            if (countable(e) > 0.0 || e.negative > 0.0) bySource.getOrPut(a) { ArrayList() } += b to e
        }
        val start = IntArray(songs + 1)
        val kept = ArrayList<Pair<Int, EdgeAcc>>()
        for (a in 0 until songs) {
            start[a] = kept.size
            val list = bySource[a] ?: continue
            // The strongest successors by weight, rejections included (a rejection is evidence too).
            list.sortWith(compareByDescending<Pair<Int, EdgeAcc>> { it.second.device + it.second.smart + it.second.imported + it.second.negative }.thenBy { it.first })
            kept += list.take(PersonalModel.TOP_PER_SONG).sortedBy { it.first }
        }
        start[songs] = kept.size
        val artists = artistIndex.size
        val artistOut = FloatArray(artists)
        val artistIn = FloatArray(artists)
        var artistTotal = 0.0
        val artistBySource = HashMap<Int, ArrayList<Pair<Int, Double>>>()
        for ((key, v) in artistEdges) {
            val x = (key ushr 32).toInt()
            val y = (key and 0xffffffffL).toInt()
            artistOut[x] += v[0].toFloat()
            artistIn[y] += v[0].toFloat()
            artistTotal += v[0]
            artistBySource.getOrPut(x) { ArrayList() } += y to v[0]
        }
        val artistStart = IntArray(artists + 1)
        val artistKept = ArrayList<Pair<Int, Double>>()
        for (x in 0 until artists) {
            artistStart[x] = artistKept.size
            val list = artistBySource[x] ?: continue
            list.sortWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            artistKept += list.take(PersonalModel.TOP_PER_ARTIST).sortedBy { it.first }
        }
        artistStart[artists] = artistKept.size
        val tracks = library.map { PersonalModel.hash(it.id.value) to it.id }.distinctBy { it.first }.sortedBy { it.first }
        val trackArtistStart = IntArray(tracks.size + 1)
        val trackArtistList = ArrayList<Int>()
        for ((i, t) in tracks.withIndex()) {
            trackArtistStart[i] = trackArtistList.size
            trackArtistList += artistsOfTrack.getValue(t.second).toList()
        }
        trackArtistStart[tracks.size] = trackArtistList.size
        return PersonalModel(
            songHashes = hashes,
            trackHashes = LongArray(tracks.size) { tracks[it].first },
            trackSong = IntArray(tracks.size) { songOfTrack.getValue(tracks[it].second) },
            trackArtistStart = trackArtistStart,
            trackArtistList = trackArtistList.toIntArray(),
            edgeStart = start,
            edgeTo = IntArray(kept.size) { kept[it].first },
            edgeWeight = ShortArray(kept.size) { kept[it].second.let { e -> half((e.device + e.smart + e.imported).toFloat()) } },
            edgeCountable = ShortArray(kept.size) { half(countable(kept[it].second).toFloat()) },
            edgeFar = ShortArray(kept.size) { half(kept[it].second.far.toFloat()) },
            edgeNegative = ShortArray(kept.size) { half(kept[it].second.negative.toFloat()) },
            edgeSessions = ByteArray(kept.size) { kept[it].second.sessions.coerceAtMost(255).toByte() },
            edgeFarSessions = ByteArray(kept.size) { kept[it].second.farSessions.coerceAtMost(255).toByte() },
            edgeFarDay = ShortArray(kept.size) { kept[it].second.lastFarMs.let { ms -> if (ms == Long.MIN_VALUE) 0 else (ms / PersonalModel.DAY_MS).toInt().toShort() } },
            outWeight = out,
            outCountable = outCountable,
            outFar = outFar,
            inWeight = inbound,
            totalWeight = total.toFloat(),
            lastListenMinute = IntArray(songs) { if (lastListen[it] == Long.MIN_VALUE) Int.MIN_VALUE else (lastListen[it] / 60_000L).toInt() },
            earlySkips = skips,
            affinity = affinity,
            artistStart = artistStart,
            artistTo = IntArray(artistKept.size) { artistKept[it].first },
            artistWeight = FloatArray(artistKept.size) { artistKept[it].second.toFloat() },
            artistOut = artistOut,
            artistIn = artistIn,
            artistTotal = artistTotal.toFloat(),
            isEmpty = isEmpty,
        )
    }

    /** Countable positives (loop guard): the listener's own and imported evidence, SMART's own up to [PersonalModel.SMART_SHARE] of it. */
    private fun countable(e: EdgeAcc): Double {
        val own = e.device + e.imported
        return own + min(e.smart, PersonalModel.SMART_SHARE * own)
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 180_000L
        const val IMPORT_SESSION_BASE = 1L shl 40
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

/**
 * What one SMART plan personalises with: the [model], the plan's clock, whether "Learn from my habits" is on (off, only
 * recency applies), the listener's hearts and own playlists, and where to report picks the personal term changed.
 */
public class PersonalInputs(
    public val model: PersonalModel,
    public val nowMs: Long,
    public val habits: Boolean,
    public val hearts: Set<TrackId> = emptySet(),
    public val playlists: List<Set<TrackId>> = emptyList(),
    public val onPersonalChanged: (Set<TrackId>) -> Unit = {},
)

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

/** Research: every hop the personal stage decided, as (the pick's core gap to the best, in δ; a far habit; changed). */
public object PersonalDiagnostics {
    public var sink: ((gapOverDelta: Float, far: Boolean, changed: Boolean) -> Unit)? = null

    /** Research: each hop's δ and every scored candidate's core gap to the best, in δ. */
    public var hop: ((delta: Float, gaps: Map<TrackId, Float>) -> Unit)? = null
}
