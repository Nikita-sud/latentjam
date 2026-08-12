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

    /** Replaces the local list with [queries], which must be ordered newest first. */
    public suspend fun replace(queries: List<String>)

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
        val replacement = buildList {
            add(trimmed)
            queries.filterTo(this) { !it.equals(trimmed, ignoreCase = true) }
        }.take(cap.coerceAtLeast(0))
        persist(replacement)
        queries.clear()
        queries += replacement
    }

    override suspend fun remove(query: String): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = queries.filterNot { it.equals(query, ignoreCase = true) }
        if (replacement.size == queries.size) return@withLock
        persist(replacement)
        queries.clear()
        queries += replacement
    }

    override suspend fun clear(): Unit = mutex.withLock {
        ensureLoaded()
        val previous = queries.toList()
        queries.clear()
        try {
            store.write(emptyList())
        } catch (failure: Throwable) {
            queries += previous
            throw failure
        }
    }

    override suspend fun replace(queries: List<String>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = normalize(queries)
        store.write(replacement)
        this.queries.clear()
        this.queries += replacement
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = store.read().filter { it.isNotBlank() }
        queries += stored
        loaded = true
    }

    private suspend fun persist(replacement: List<String>) = store.write(replacement)

    private fun normalize(values: List<String>): List<String> {
        val result = mutableListOf<String>()
        values.forEach { value ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty() && result.none { it.equals(trimmed, ignoreCase = true) }) {
                result += trimmed
            }
        }
        return result.take(cap.coerceAtLeast(0))
    }
}
