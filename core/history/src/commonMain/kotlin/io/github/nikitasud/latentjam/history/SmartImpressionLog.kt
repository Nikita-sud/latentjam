/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The impression log's file: append-only lines, read and trimmed by streaming so a log at its cap is never held on the
 * heap. Platform stores sit beside the listening log in private storage; nothing reads them but this app.
 */
public interface SmartLogStore {
    /** Appends [text] (whole lines, each ending in a line feed) durably. */
    public suspend fun append(text: String)

    /** The file's size in bytes; 0 when it does not exist. */
    public suspend fun size(): Long

    /**
     * Drops the oldest lines so that at most [maxBytes] remain, starting on a line boundary, by streaming the kept tail
     * into a replacement file (atomic rename).
     */
    public suspend fun keepTail(maxBytes: Long)

    /** Calls [line] for every complete line, oldest first, reading the file in chunks. */
    public suspend fun forEachLine(line: (String) -> Unit)

    public suspend fun clear()
}

/**
 * One track SMART offered: queue [queue] (a hash of the seed's content key and press), its [plan]-th plan in that queue
 * (0 = the plan from the seed), position [hop] in that plan (1 = first), [track] (a 64-bit hash of the track's §7.2b
 * content key, hex), [rank] (its row in the queue, the seed being row 0), the ranker's [score], the decoder's choice
 * [probability] (the propensity: 1 / the near-best set's size) and [tie], the [source] that proposed it and the
 * [model] that planned it.
 */
public data class SmartImpression(
    public val atMs: Long,
    public val queue: String,
    public val plan: Int,
    public val hop: Int,
    public val track: String,
    public val rank: Int,
    public val score: Double,
    public val probability: Double,
    public val tie: Boolean,
    public val source: String,
    public val model: String,
)

/** What became of an offered track. */
public enum class SmartOutcomeKind {
    /** Played to its end or, skipped late, counted as a listen (the history's rule). */
    PLAYED,

    /** Skipped: [SmartOutcome.elapsedMs] and [SmartOutcome.fraction] say when. */
    SKIPPED,

    /** Taken out of the queue before it played. */
    REMOVED,

    /** The listener started other music before it played. */
    REPLACED,

    /** Never served: a newer plan superseded it, or the queue ended first. */
    UNREACHED,
}

/** The listener hearted ([favourite] true) or un-hearted [track] (content-key hash, empty when not in the library). */
public data class SmartFavoriteEvent(
    public val atMs: Long,
    public val track: String,
    public val favourite: Boolean,
)

/** The outcome of the impression ([queue], [plan], [hop], [track]). */
public data class SmartOutcome(
    public val atMs: Long,
    public val queue: String,
    public val plan: Int,
    public val hop: Int,
    public val track: String,
    public val kind: SmartOutcomeKind,
    public val elapsedMs: Long? = null,
    public val fraction: Double? = null,
)

/**
 * SMART's on-device impression and outcome log: every track a SMART plan offered, with its score, propensity and
 * source, and what became of it. Local only — never uploaded, never read by ranking (yet); it exists so offline,
 * propensity-weighted evaluation of later rankers is possible on the listener's own device.
 *
 * Bounded: when the file passes [capBytes] the oldest lines are dropped down to [keepBytes], streamed, never loaded.
 * Every write is best effort: a failing store costs log lines, never a queue.
 *
 * Lines: `si1|at|queue|plan|hop|track|rank|score|probability|tie|source|model` and
 * `so1|at|queue|plan|hop|track|OUTCOME|elapsedMs|fraction` (empty = unknown, NaN score = none), and the listener's
 * hearts `sf1|at|track|1` (favourite) / `sf1|at|track|0` (removed).
 */
public class SmartImpressionLog(
    private val store: SmartLogStore,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
    private val keepBytes: Long = DEFAULT_KEEP_BYTES,
) {
    private val mutex = Mutex()

    init {
        require(keepBytes in 1..capBytes)
    }

    public suspend fun recordImpressions(impressions: List<SmartImpression>): Unit = write(impressions.map { serialize(it) })

    public suspend fun recordOutcomes(outcomes: List<SmartOutcome>): Unit = write(outcomes.map { serialize(it) })

    public suspend fun recordFavorites(events: List<SmartFavoriteEvent>): Unit = write(events.map { serialize(it) })

    /** Streams every line, oldest first (the debug export). */
    public suspend fun forEachLine(line: (String) -> Unit): Unit = mutex.withLock {
        runCatching { store.forEachLine(line) }
    }

    public suspend fun clear(): Unit = mutex.withLock { runCatching { store.clear() } }

    private suspend fun write(lines: List<String>) {
        if (lines.isEmpty()) return
        mutex.withLock {
            runCatching {
                store.append(lines.joinToString(separator = "\n", postfix = "\n"))
                if (store.size() > capBytes) store.keepTail(keepBytes)
            }.onFailure { println("SMART log: write failed (${it::class.simpleName}); the queue is unaffected") }
        }
    }

    public companion object {
        public const val DEFAULT_CAP_BYTES: Long = 4L * 1024 * 1024
        public const val DEFAULT_KEEP_BYTES: Long = 3L * 1024 * 1024
        private const val IMPRESSION = "si1"
        private const val OUTCOME = "so1"
        private const val FAVORITE = "sf1"

        public fun serialize(f: SmartFavoriteEvent): String =
            listOf(FAVORITE, f.atMs.toString(), token(f.track), if (f.favourite) "1" else "0").joinToString("|")

        public fun serialize(i: SmartImpression): String = listOf(
            IMPRESSION, i.atMs.toString(), token(i.queue), i.plan.toString(), i.hop.toString(), token(i.track),
            i.rank.toString(), number(i.score), number(i.probability), if (i.tie) "1" else "0", token(i.source), token(i.model),
        ).joinToString("|")

        public fun serialize(o: SmartOutcome): String = listOf(
            OUTCOME, o.atMs.toString(), token(o.queue), o.plan.toString(), o.hop.toString(), token(o.track), o.kind.name,
            o.elapsedMs?.toString() ?: "", o.fraction?.let(::number) ?: "",
        ).joinToString("|")

        /** A parsed line: a [SmartImpression], a [SmartOutcome], a [SmartFavoriteEvent], or null for a torn or foreign one. */
        public fun parse(line: String): Any? = runCatching {
            val p = line.split('|')
            when {
                p[0] == IMPRESSION && p.size == 12 -> SmartImpression(
                    p[1].toLong(), p[2], p[3].toInt(), p[4].toInt(), p[5], p[6].toInt(), p[7].toDouble(),
                    p[8].toDouble(), p[9] == "1", p[10], p[11],
                )
                p[0] == OUTCOME && p.size == 9 -> SmartOutcome(
                    p[1].toLong(), p[2], p[3].toInt(), p[4].toInt(), p[5], SmartOutcomeKind.valueOf(p[6]),
                    p[7].takeIf(String::isNotEmpty)?.toLong(), p[8].takeIf(String::isNotEmpty)?.toDouble(),
                )
                p[0] == FAVORITE && p.size == 4 && (p[3] == "1" || p[3] == "0") ->
                    SmartFavoriteEvent(p[1].toLong(), p[2], p[3] == "1")
                else -> null
            }
        }.getOrNull()

        /** Identifiers and labels are ASCII tokens; anything that could break a line or a field becomes '_'. */
        private fun token(s: String): String = buildString(s.length) {
            for (c in s) append(if (c == '|' || c == '\n' || c == '\r' || c.code < 0x20 || c.code > 0x7e) '_' else c)
        }

        private fun number(x: Double): String = if (x.isNaN()) "NaN" else x.toString()
    }
}
