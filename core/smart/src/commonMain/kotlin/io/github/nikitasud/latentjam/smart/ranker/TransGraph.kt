/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** One play as the personal transition graph reads it (the history log's v4 fields; [start] = ListenStart's name). */
public class PlayEvent(
    public val trackId: TrackId,
    public val startedAtMs: Long,
    public val playedMs: Long,
    public val durationMs: Long,
    public val skipped: Boolean,
    public val start: String?,
    /** An imported listen (e.g. a ListenBrainz export): accepted by definition, no listen time, skip or start reason. */
    public val imported: Boolean = false,
)

/**
 * The listener's personal transition graph (research SIGNALS_REVIEW.md §4, experiment PLAN_TRANSGRAPH.md), FULL only:
 * each play of B gives a listen-time positive and an early-skip negative, weighted by how B started and forgotten with a
 * half-life; positives go to the previous [RankerModel.TransGraph.window] accepted tracks of the session (1/x), negatives
 * to the immediate predecessor. [value] turns A → B into a bounded nudge input in [−1, 1]: κ · Dirichlet share − w · the
 * smoothed rejection rate. Variant "B" uses hard thresholds, "C" drops the start-reason multipliers, "A2" adds a 2-hop
 * walk, "D" is a session-kNN (votes of past sessions in which B followed A, no negatives).
 */
public class TransGraph(plays: List<PlayEvent>, seedMs: Long, private val cfg: RankerModel.TransGraph) {
    private val pos = HashMap<TrackId, HashMap<TrackId, Double>>()
    private val neg = HashMap<TrackId, HashMap<TrackId, Double>>()
    private val negCount = HashMap<TrackId, HashMap<TrackId, Int>>()
    private val posTotal = HashMap<TrackId, Double>()

    init {
        val halfLifeMs = cfg.halfLifeDays * 86_400_000.0
        var context = ArrayDeque<TrackId>()
        var prevEnd = Long.MIN_VALUE
        var tracks = 0
        var skips = 0
        var autoRun = 0
        val sessions = ArrayList<MutableList<Pair<TrackId, Double>>>()
        for (p in plays.filter { it.startedAtMs < seedMs }.sortedBy { it.startedAtMs }) {
            if (prevEnd == Long.MIN_VALUE || p.startedAtMs - prevEnd > cfg.gapMs) {
                context = ArrayDeque(); tracks = 0; skips = 0; autoRun = 0; sessions.add(ArrayList())
            }
            prevEnd = p.startedAtMs + p.playedMs
            val decay = 2.0.pow(-maxOf(0L, seedMs - p.startedAtMs) / halfLifeMs)
            val s = p.playedMs / 1000.0
            val d = if (p.durationMs > 0) p.durationMs / 1000.0 else maxOf(s, 1.0)
            val f = min(1.0, s / d)
            val threshold = min(d / 2.0, 240.0)
            var positive: Double
            var negative: Double
            if (p.imported) {
                // Missing fields are neutral: an imported listen passed the export's acceptance rule (½ track or 4 min),
                // so it counts as that rule's positive (0.8, §4.1) and never as a rejection; it weighs importWeight.
                positive = 0.8
                negative = 0.0
            } else if (cfg.variant == "B") {
                positive = if (s >= threshold) 1.0 else 0.0
                negative = if (p.skipped && s < 30.0) 1.0 else 0.0
            } else {
                positive = 0.6 * min(1.0, s / threshold) + 0.4 * f
                negative = if (p.skipped && f < 0.85) 1.0 / (1.0 + exp(-(30.0 - s) / 7.0)) else 0.0
                val rho = (skips + 1.0) / (tracks + 2.0)
                negative *= 1.0 - 0.5 * rho
            }
            autoRun = if (p.start == "AUTO_ADVANCE") autoRun + 1 else 0
            var oPos = 1.0
            var oNeg = 1.0
            if (p.imported) { oPos = cfg.importWeight; oNeg = 0.0 }
            else if (cfg.variant != "C") when (p.start) {
                "USER_PICK" -> { oPos = 1.0; oNeg = 0.3 }
                "AUTO_ADVANCE" -> { oPos = 0.4 * (0.4 + 0.6 / (1.0 + exp((autoRun - 8.0) / 2.0))); oNeg = 1.0 }
                "SKIP_NEXT" -> { oPos = 0.8; oNeg = 0.6 }
            }
            val r = positive - negative
            context.forEachIndexed { k, a ->
                if (a != p.trackId && r > 0.0) add(pos, a, p.trackId, decay * oPos * r / (k + 1))
            }
            context.firstOrNull()?.let { a ->
                if (a != p.trackId && r < 0.0 && oNeg > 0.0) {
                    add(neg, a, p.trackId, decay * oNeg * -r)
                    negCount.getOrPut(a) { HashMap() }.let { it[p.trackId] = (it[p.trackId] ?: 0) + 1 }
                }
            }
            tracks++
            if (p.skipped) skips++
            if (negative < 0.5) {
                context.addFirst(p.trackId)
                while (context.size > cfg.window) context.removeLast()
                sessions.last().add(p.trackId to decay)
            }
        }
        if (cfg.variant == "D") {
            pos.clear()
            for (session in sessions) for (i in session.indices) for (j in i + 1 until minOf(session.size, i + 6)) {
                val (a, _) = session[i]; val (b, w) = session[j]
                if (a != b) add(pos, a, b, w / (j - i))
            }
        }
        for ((a, m) in pos) posTotal[a] = m.values.sum()
    }

    private fun add(map: HashMap<TrackId, HashMap<TrackId, Double>>, a: TrackId, b: TrackId, w: Double) {
        map.getOrPut(a) { HashMap() }.let { it[b] = (it[b] ?: 0.0) + w }
    }

    private fun share(a: TrackId, b: TrackId): Double = (pos[a]?.get(b) ?: 0.0) / ((posTotal[a] ?: 0.0) + cfg.mu)

    /** A → B as a nudge input in [−1, 1] (0 = the graph knows nothing about the pair). */
    public fun value(anchor: TrackId, track: TrackId): Double {
        var s = share(anchor, track)
        if (cfg.variant == "A2") pos[anchor]?.keys?.forEach { c -> if (c != track) s += 0.5 * share(anchor, c) * share(c, track) }
        val n = if (cfg.variant == "D") 0.0 else neg[anchor]?.get(track) ?: 0.0
        // One rejection counts half until it repeats (a single mood skip must not cost a good track much).
        val events = negCount[anchor]?.get(track) ?: 0
        val negr = n / (n + (pos[anchor]?.get(track) ?: 0.0) + 2.0) * minOf(1.0, events / 2.0)
        return (cfg.kappa * s - cfg.negWeight * negr).coerceIn(-1.0, 1.0)
    }
}
