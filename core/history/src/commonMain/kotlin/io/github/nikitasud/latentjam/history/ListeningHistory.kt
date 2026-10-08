/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.module.Module

/**
 * The device's listening record: fuel for the For You page and the future
 * personalized predictor. Local-only by design (privacy-first — events never
 * leave the device).
 */
public interface ListeningHistory {

    /** Appends one finished listening session. */
    public suspend fun record(event: ListenEvent)

    /** Per-track aggregates over the whole log. */
    public suspend fun stats(): Map<TrackId, TrackStats>

    /** The most recent [limit] events, newest first. */
    public suspend fun recentEvents(limit: Int): List<ListenEvent>

    /**
     * Complete local log, oldest first.
     *
     * Statistics needs an honest all-time view and streaks must not silently lose the oldest
     * portion of a long-lived log. The default keeps third-party/test implementations compatible;
     * [DefaultListeningHistory] overrides it to copy its already-loaded list directly rather than
     * requesting an artificial, unbounded "recent" count.
     */
    public suspend fun allEvents(): List<ListenEvent> =
        recentEvents(Int.MAX_VALUE).asReversed()

    /**
     * Replaces the complete local log with [events], ordered oldest first.
     *
     * This is intentionally a bulk operation for local backup restore: implementations can write
     * one coherent snapshot instead of exposing a half-restored history after a failed append.
     */
    public suspend fun replace(events: List<ListenEvent>)

    /**
     * Folds [imported] into the log and persists the result as one indivisible update.
     *
     * A merge is a read-modify-write, and a pair of [recentEvents]/[allEvents] followed by a later
     * [replace] cannot make it safe: a listen recorded between that read and that write is
     * overwritten by the replacement built from the older read. Local backup restore runs while
     * playback keeps recording behind the settings screen, so the window is real. Here [combine] is
     * handed the log as it is at that moment, and the list it returns is written back before any
     * other [record] can run.
     *
     * [combine] receives the current log and [imported], both ordered oldest first, and returns the
     * replacement log in that same order. Implementations call it while holding their own lock, so
     * it has to be a pure, quick transformation: it must not suspend, and it must not call back into
     * this history (a mutex-backed implementation would deadlock).
     *
     * The replacement must not exceed [maxEvents]. An implementation rejects a larger result with
     * [IllegalArgumentException] before touching the store, so the log keeps its previous content
     * rather than silently losing its oldest part.
     *
     * Cancellation is never turned into a merge result. A merge that is already cancelled when it
     * starts changes nothing, and an implementation that owns its store finishes persisting and
     * republishing as one step, so a cancellation arriving during the write still raises
     * `CancellationException` to its caller — once the store and the in-memory log hold the same,
     * fully merged list again.
     *
     * The default implementation delegates to [allEvents] and [replace] and therefore cannot make
     * the read and the write indivisible; it exists so third-party and test implementations stay
     * source-compatible, while [DefaultListeningHistory] overrides it with a single locked update.
     *
     * @return the number of events the log holds after the merge.
     */
    public suspend fun merge(
        imported: List<ListenEvent>,
        maxEvents: Int,
        combine: (existing: List<ListenEvent>, imported: List<ListenEvent>) -> List<ListenEvent>,
    ): Int {
        val merged = combine(allEvents(), imported).toList()
        require(merged.size <= maxEvents) {
            "Merged listening history holds ${merged.size} events, more than the $maxEvents allowed"
        }
        replace(merged)
        return merged.size
    }

    /** Removes the complete local listening history. */
    public suspend fun clear()
}

/**
 * Append-only line store behind [ListeningHistory] — file-backed on Android,
 * private persistent storage on iOS. Called only under the history's own mutex.
 */
public interface HistoryStore {
    public suspend fun append(line: String)
    public suspend fun readAll(): List<String>
    public suspend fun replaceAll(lines: List<String>) {
        clear()
        lines.forEach { append(it) }
    }
    public suspend fun clear()
}

/**
 * Default [ListeningHistory]: loads the log once, keeps events in memory,
 * appends write-through. Corrupt lines are skipped on load, never fatal.
 * A few thousand events a year at ~60 bytes each keeps this trivially small;
 * a queryable store can replace [HistoryStore] behind the same port later.
 */
public class DefaultListeningHistory(
    private val store: HistoryStore,
) : ListeningHistory {

    private val mutex = Mutex()
    private var loaded = false
    private val events = mutableListOf<ListenEvent>()
    /**
     * Updated with [events] under the same mutex so repeated For You/Search/playlist reads do not
     * replay the complete append-only log on their caller's dispatcher. A returned [stats] map is
     * still copied, so callers never observe later records mutating an older snapshot.
     */
    private val aggregates = mutableMapOf<TrackId, TrackStats>()

    override suspend fun record(event: ListenEvent): Unit = mutex.withLock {
        ensureLoaded()
        // Publish only after the append succeeds. Otherwise stats in this process include a
        // session that silently disappears at the next launch.
        store.append(event.serialize())
        events += event
        aggregate(event)
    }

    override suspend fun stats(): Map<TrackId, TrackStats> = mutex.withLock {
        ensureLoaded()
        aggregates.toMap()
    }

    override suspend fun recentEvents(limit: Int): List<ListenEvent> = mutex.withLock {
        ensureLoaded()
        events.takeLast(limit.coerceAtLeast(0)).reversed()
    }

    override suspend fun allEvents(): List<ListenEvent> = mutex.withLock {
        ensureLoaded()
        events.toList()
    }

    override suspend fun replace(events: List<ListenEvent>): Unit = mutex.withLock {
        ensureLoaded()
        writeAll(events.toList())
    }

    override suspend fun merge(
        imported: List<ListenEvent>,
        maxEvents: Int,
        combine: (existing: List<ListenEvent>, imported: List<ListenEvent>) -> List<ListenEvent>,
    ): Int {
        // Read here rather than inside the locked section: cancellation has to be observable there
        // without a suspension point, because an already loaded log has none before the write.
        val caller = coroutineContext
        return mutex.withLock {
            // A cancelled caller must not commit anything.
            caller.ensureActive()
            ensureLoaded()
            // combine runs here, while the lock is held, so the log it reads is the log writeAll
            // persists: a record() issued meanwhile waits for the lock instead of being overwritten
            // by a replacement computed from a log that no longer exists.
            val merged = combine(events.toList(), imported).toList()
            require(merged.size <= maxEvents) {
                "Merged listening history holds ${merged.size} events, more than the $maxEvents allowed"
            }
            // Persisting and republishing are one step. A cancellation arriving in the middle must
            // not leave the store merged while this process keeps serving the previous log — the
            // next merge would fold from that stale list and drop what the store already holds. The
            // write is therefore not abandoned half-way; ensureActive below still reports the
            // cancellation once the log is coherent again.
            withContext(NonCancellable) { writeAll(merged) }
            caller.ensureActive()
            merged.size
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        ensureLoaded()
        store.clear()
        events.clear()
        aggregates.clear()
    }

    /**
     * Publishes [replacement] to the store and then to memory. Called with [mutex] held, and it
     * touches memory only after the store accepted the whole replacement: a failed write leaves the
     * previous log readable rather than a half-restored one.
     */
    private suspend fun writeAll(replacement: List<ListenEvent>) {
        store.replaceAll(replacement.map(ListenEvent::serialize))
        events.clear()
        events += replacement
        aggregates.clear()
        replacement.forEach(::aggregate)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val lines = store.readAll()
        // File stores confine their read to IO, but the continuation resumes on the caller. The
        // first stats()/For You request commonly comes from a Main LaunchedEffect, and parsing plus
        // folding a long-lived history there is avoidable launch jank.
        val restored = withContext(Dispatchers.Default) {
            val parsed = lines.mapNotNull(ListenEvent::parse)
            val folded = mutableMapOf<TrackId, TrackStats>()
            parsed.forEach { folded.aggregate(it) }
            RestoredHistory(parsed, folded)
        }
        events += restored.events
        aggregates.putAll(restored.aggregates)
        loaded = true
    }

    private fun aggregate(event: ListenEvent) {
        aggregates.aggregate(event)
    }

    private data class RestoredHistory(
        val events: List<ListenEvent>,
        val aggregates: Map<TrackId, TrackStats>,
    )
}

private fun MutableMap<TrackId, TrackStats>.aggregate(event: ListenEvent) {
    val previous = this[event.trackId]
    this[event.trackId] = TrackStats(
        plays = (previous?.plays ?: 0) + 1,
        completions = (previous?.completions ?: 0) + if (event.completed) 1 else 0,
        skips = (previous?.skips ?: 0) + if (event.skipped) 1 else 0,
        totalPlayedMs = saturatingDurationAdd(
            previous?.totalPlayedMs ?: 0,
            event.effectiveListenedMs.coerceAtLeast(0),
        ),
        lastPlayedAtMs = maxOf(previous?.lastPlayedAtMs ?: 0, event.startedAtMs),
    )
}

/** Koin bindings for [ListeningHistory] on this platform. */
public expect fun listeningHistoryModule(): Module

/** Wall-clock epoch milliseconds (no kotlinx-datetime dependency needed yet). */
public expect fun epochMillis(): Long
