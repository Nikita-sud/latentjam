/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The queries this device has searched for, newest first — so returning to
 * search offers what you were looking for last time instead of a blank page.
 * Local-only, like every other kind of history here.
 */
public interface RecentSearches {

    /** Up to [limit] most recent queries, newest first. */
    public suspend fun recent(limit: Int = DEFAULT_LIMIT): List<String>

    /** Records a query, moving it to the front if already present. */
    public suspend fun record(query: String)

    /** Forgets one query. */
    public suspend fun remove(query: String)

    /** Forgets all of them. */
    public suspend fun clear()

    public companion object {
        public const val DEFAULT_LIMIT: Int = 8
    }
}

/** Whole-list storage — small enough that rewriting beats appending. */
public interface RecentSearchStore {
    public suspend fun read(): List<String>
    public suspend fun write(queries: List<String>)
}

/**
 * Default [RecentSearches]: case-insensitively de-duplicated, most recent
 * first, capped so the list stays a shortcut rather than an archive.
 */
public class DefaultRecentSearches(
    private val store: RecentSearchStore,
    private val cap: Int = 12,
) : RecentSearches {

    private val mutex = Mutex()
    private var loaded = false
    private val queries = mutableListOf<String>()

    override suspend fun recent(limit: Int): List<String> = mutex.withLock {
        ensureLoaded()
        queries.take(limit.coerceAtLeast(0))
    }

    override suspend fun record(query: String): Unit = mutex.withLock {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withLock
        ensureLoaded()
        queries.removeAll { it.equals(trimmed, ignoreCase = true) }
        queries.add(0, trimmed)
        while (queries.size > cap) queries.removeAt(queries.lastIndex)
        persist()
    }

    override suspend fun remove(query: String): Unit = mutex.withLock {
        ensureLoaded()
        if (queries.removeAll { it.equals(query, ignoreCase = true) }) persist()
    }

    override suspend fun clear(): Unit = mutex.withLock {
        ensureLoaded()
        queries.clear()
        persist()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        runCatching { store.read() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .forEach(queries::add)
        loaded = true
    }

    private suspend fun persist() {
        runCatching { store.write(queries.toList()) }
    }
}
