/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * Replaces the complete local log with [events], ordered oldest first.
     *
     * This is intentionally a bulk operation for local backup restore: implementations can write
     * one coherent snapshot instead of exposing a half-restored history after a failed append.
     */
    public suspend fun replace(events: List<ListenEvent>)

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

    override suspend fun record(event: ListenEvent): Unit = mutex.withLock {
        ensureLoaded()
        events += event
        runCatching { store.append(event.serialize()) }
    }

    override suspend fun stats(): Map<TrackId, TrackStats> = mutex.withLock {
        ensureLoaded()
        val aggregates = mutableMapOf<TrackId, TrackStats>()
        for (event in events) {
            val previous = aggregates[event.trackId]
            aggregates[event.trackId] = TrackStats(
                plays = (previous?.plays ?: 0) + 1,
                completions = (previous?.completions ?: 0) + if (event.completed) 1 else 0,
                skips = (previous?.skips ?: 0) + if (event.skipped) 1 else 0,
                totalPlayedMs = (previous?.totalPlayedMs ?: 0) + event.playedMs,
                lastPlayedAtMs = maxOf(previous?.lastPlayedAtMs ?: 0, event.startedAtMs),
            )
        }
        aggregates
    }

    override suspend fun recentEvents(limit: Int): List<ListenEvent> = mutex.withLock {
        ensureLoaded()
        events.takeLast(limit.coerceAtLeast(0)).reversed()
    }

    override suspend fun replace(events: List<ListenEvent>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = events.toList()
        store.replaceAll(replacement.map(ListenEvent::serialize))
        this.events.clear()
        this.events += replacement
    }

    override suspend fun clear(): Unit = mutex.withLock {
        ensureLoaded()
        store.clear()
        events.clear()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val lines = runCatching { store.readAll() }.getOrDefault(emptyList())
        lines.mapNotNullTo(events, ListenEvent::parse)
        loaded = true
    }
}

/** Koin bindings for [ListeningHistory] on this platform. */
public expect fun listeningHistoryModule(): Module

/** Wall-clock epoch milliseconds (no kotlinx-datetime dependency needed yet). */
public expect fun epochMillis(): Long
