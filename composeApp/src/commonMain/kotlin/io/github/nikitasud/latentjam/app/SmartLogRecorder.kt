/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.SmartImpression
import io.github.nikitasud.latentjam.history.SmartImpressionLog
import io.github.nikitasud.latentjam.history.SmartOutcome
import io.github.nikitasud.latentjam.history.SmartOutcomeTracker
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One planned row as the chooser hands it to the impression log. */
class SmartPlanRow(
    val trackId: TrackId,
    /** [io.github.nikitasud.latentjam.smart.ranker.SaltedKey.contentHash] of the track's content key. */
    val trackKey: String,
    val score: Double,
    val probability: Double,
    val tie: Boolean,
    val source: String,
)

/** What [EngineNextTrackChooser] tells the impression log: each plan's rows, and each row it serves. */
interface SmartPlanLog {
    /** A plan of queue [queue] ([plan] = its index in the queue); the first row sits [rankOffset] + 1 rows after the queue's start. */
    suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>)

    suspend fun served(id: TrackId)

    /**
     * PLAN_L1 §2c freshness: the content keys each recent press showed (the first [SHOWN_ROWS] rows of its plan 0),
     * newest first, across every seed: at most [presses] presses from the last [windowMs] before [nowMs], leaving
     * out queue [excludeQueue] (a top-up's own queue).
     */
    suspend fun recentPresses(nowMs: Long, windowMs: Long, presses: Int, excludeQueue: String? = null): List<Set<String>> = emptyList()

    /** Fast 3 freshness.engaged: the content keys of shown tracks the listener then played (outcome PLAYED). */
    suspend fun engagedKeys(): Set<String> = emptySet()

    companion object {
        const val SHOWN_ROWS: Int = 10
    }
}

/**
 * Bridges the chooser and playback to [SmartImpressionLog]: impressions from the chooser's plans, outcomes from
 * [SmartOutcomeTracker] fed with the history recorder's snapshots and finished listens — the same service-side
 * playback state the v4 origin logging classifies from, never a MediaController's view.
 *
 * Follows the "save listening history" setting ([enabled]): with it off nothing is logged, as nothing is played into the
 * history either.
 *
 * Callers never wait on storage: the tracker's bookkeeping is in memory, and every write goes through one queue
 * drained by one coroutine in [scope] (oldest writes dropped if it ever backs up past [WRITE_BUFFER]).
 */
class SmartLogRecorder(
    private val log: SmartImpressionLog,
    scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = ::epochMillis,
) : SmartPlanLog {
    private val mutex = Mutex()
    private val tracker = SmartOutcomeTracker()
    private val writes = Channel<suspend () -> Unit>(WRITE_BUFFER, BufferOverflow.DROP_OLDEST)
    private var lastStart: Long? = null
    private var queueRef: List<TrackDescriptor>? = null
    private var queueIds: Set<TrackId> = emptySet()

    /** Recent presses (plan 0's shown rows), newest first; read back from the log once per process. */
    private class Press(val queue: String, val atMs: Long, val keys: Set<String>)
    private val pressMutex = Mutex()
    private var pressLog: ArrayDeque<Press>? = null
    private val engaged = HashSet<String>()

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The log is best effort; SmartImpressionLog already reports its own failures.
                }
            }
        }
    }

    override suspend fun planned(queue: String, plan: Int, rankOffset: Int, model: String, rows: List<SmartPlanRow>) {
        if (rows.isEmpty() || !enabled()) return
        val at = clock()
        val impressions = rows.mapIndexed { i, r ->
            r.trackId to SmartImpression(at, queue, plan, i + 1, r.trackKey, rankOffset + i + 1, r.score, r.probability, r.tie, r.source, model)
        }
        val outcomes = mutex.withLock { tracker.onPlan(impressions, at) }
        if (plan == 0) {
            val keys = rows.take(SmartPlanLog.SHOWN_ROWS).mapNotNullTo(HashSet()) { it.trackKey.takeIf(String::isNotEmpty) }
            pressMutex.withLock { pressesLoaded().addFirst(Press(queue, at, keys)); trimPresses() }
        }
        val lines = impressions.map { it.second }
        writes.trySend {
            if (outcomes.isNotEmpty()) log.recordOutcomes(outcomes)
            log.recordImpressions(lines)
        }
    }

    override suspend fun served(id: TrackId): Unit = mutex.withLock { tracker.onServed(id) }

    override suspend fun recentPresses(nowMs: Long, windowMs: Long, presses: Int, excludeQueue: String?): List<Set<String>> {
        if (!enabled() || presses <= 0) return emptyList()
        return pressMutex.withLock {
            pressesLoaded().asSequence()
                .filter { it.queue != excludeQueue && it.atMs <= nowMs && nowMs - it.atMs <= windowMs }
                .take(presses).map { it.keys }.toList()
        }
    }

    /** The press list, read from the log on first use (plan-0 rows within the first shown rows, grouped by queue). */
    private suspend fun pressesLoaded(): ArrayDeque<Press> = pressLog ?: run {
        val byQueue = LinkedHashMap<String, Pair<Long, MutableSet<String>>>()
        runCatching {
            log.forEachLine { line ->
                val parsed = SmartImpressionLog.parse(line)
                if (parsed is SmartOutcome && parsed.kind == io.github.nikitasud.latentjam.history.SmartOutcomeKind.PLAYED) engaged += parsed.track
                val i = parsed as? SmartImpression ?: return@forEachLine
                if (i.plan != 0 || i.hop > SmartPlanLog.SHOWN_ROWS || i.track.isEmpty()) return@forEachLine
                val e = byQueue.getOrPut(i.queue) { i.atMs to HashSet() }
                e.second += i.track
            }
        }
        ArrayDeque(byQueue.entries.sortedByDescending { it.value.first }.take(MAX_PRESSES).map { Press(it.key, it.value.first, it.value.second) })
            .also { pressLog = it }
    }

    /** Clear listening history: the presses go with the log. */
    suspend fun forgetPresses(): Unit = pressMutex.withLock { pressLog = ArrayDeque(); engaged.clear() }

    private fun trimPresses() {
        val l = pressLog ?: return
        while (l.size > MAX_PRESSES) l.removeLast()
    }

    /** Every playback snapshot the history recorder sees (~2 per second while playing). */
    suspend fun onNowPlaying(now: NowPlaying) {
        val outcomes = mutex.withLock {
            val start = now.playbackStart
            val userPick = start != null && start.sequence != lastStart && start.cause == StartCause.USER_PICK &&
                start.trackId == now.track?.id
            lastStart = start?.sequence
            if (tracker.openRows == 0 || !enabled()) return
            // Android reuses one immutable queue snapshot between edits: the id set is rebuilt only when it changes.
            if (now.queue !== queueRef) {
                queueRef = now.queue
                queueIds = now.queue.mapTo(HashSet(now.queue.size * 2)) { it.id }
            }
            tracker.onSnapshot(now.track?.id, queueIds, userPick, clock())
        }
        emit(outcomes)
    }

    /** A listen the history recorder just recorded. */
    suspend fun onListen(event: ListenEvent) {
        emit(mutex.withLock { tracker.onListen(event, clock()) })
    }

    override suspend fun engagedKeys(): Set<String> {
        if (!enabled()) return emptySet()
        return pressMutex.withLock { pressesLoaded(); engaged.toSet() }
    }

    private fun emit(outcomes: List<SmartOutcome>) {
        if (outcomes.any { it.kind == io.github.nikitasud.latentjam.history.SmartOutcomeKind.PLAYED }) {
            val played = outcomes.filter { it.kind == io.github.nikitasud.latentjam.history.SmartOutcomeKind.PLAYED }.map { it.track }
            writes.trySend { pressMutex.withLock { engaged += played } }
        }
        if (outcomes.isNotEmpty()) writes.trySend { log.recordOutcomes(outcomes) }
    }

    private companion object {
        const val WRITE_BUFFER = 256
        const val MAX_PRESSES = 32
    }
}

/**
 * The impression log as one text for the debug export (product checks only): a header naming the format, then every
 * line, oldest first. Called off the main thread by the export's [LocalBackupFileExchange]; the log is capped
 * ([SmartImpressionLog.DEFAULT_CAP_BYTES]), so the text is bounded too.
 */
internal fun exportSmartLogText(log: SmartImpressionLog): String = kotlinx.coroutines.runBlocking {
    buildString {
        append("# LatentJam SMART impression log; si1 = offered track, so1 = its outcome (see SmartImpressionLog.kt)\n")
        log.forEachLine { append(it).append('\n') }
    }
}

/** What a favourites change added and removed, in [after]'s / [before]'s order. */
internal fun favoriteChanges(before: Collection<TrackId>, after: Collection<TrackId>): Pair<List<TrackId>, List<TrackId>> {
    val was = before.toHashSet()
    val now = after.toHashSet()
    return after.filter { it !in was }.distinct() to before.filter { it !in now }.distinct()
}

/**
 * Follows the favourites through every path that changes them (the heart, the notification, bulk edits, duplicate
 * merges, a backup restore): each durable change is diffed against the last list seen, the heart times are kept in
 * [times] (FULL's favourited_days_ago), and the hearts go to SMART's impression log while [logEnabled] (the history
 * setting). The first list seen is the baseline: it records nothing, and drops times of tracks no longer hearted.
 */
fun CoroutineScope.launchFavoriteRecorder(
    favorites: io.github.nikitasud.latentjam.history.Favorites,
    times: io.github.nikitasud.latentjam.history.FavoriteTimes,
    log: SmartImpressionLog?,
    logEnabled: () -> Boolean,
    trackKey: (List<TrackId>) -> Map<TrackId, String>,
    clock: () -> Long = ::epochMillis,
): kotlinx.coroutines.Job = launch {
    var previous: List<TrackId>? = null
    favorites.revision.collect {
        val current = try {
            favorites.all()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@collect
        }
        val before = previous
        previous = current
        if (before == null) {
            val stale = times.all().keys - current.toHashSet()
            if (stale.isNotEmpty()) times.update(emptyList(), stale, clock())
            return@collect
        }
        val (added, removed) = favoriteChanges(before, current)
        if (added.isEmpty() && removed.isEmpty()) return@collect
        val at = clock()
        times.update(added, removed, at)
        if (log != null && logEnabled()) {
            val keys = trackKey(added + removed)
            log.recordFavorites(
                added.map { io.github.nikitasud.latentjam.history.SmartFavoriteEvent(at, keys[it].orEmpty(), true) } +
                    removed.map { io.github.nikitasud.latentjam.history.SmartFavoriteEvent(at, keys[it].orEmpty(), false) },
            )
        }
    }
}
