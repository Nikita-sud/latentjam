/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the listener marked as favourites, newest first.
 *
 * Deliberately track-id based and device-local, like the rest of this module: hearts are strong
 * personal signal and never leave the phone.
 */
public interface Favorites {

    /** Every favourite, newest first. */
    public suspend fun all(): List<TrackId>

    public suspend fun contains(id: TrackId): Boolean

    /** Flips [id]'s state. @return true when the track is now a favourite. */
    public suspend fun toggle(id: TrackId): Boolean

    /** Replaces the whole list (the restore path), keeping order, dropping duplicates. */
    public suspend fun replace(ids: List<TrackId>)
}

/** Whole-list storage — small enough that rewriting beats appending. */
public interface FavoritesStore {
    public suspend fun read(): List<String>
    public suspend fun write(ids: List<String>)
}

/** Default [Favorites]: serialized on its own mutex, persisted through every mutation. */
public class DefaultFavorites(
    private val store: FavoritesStore,
) : Favorites {

    private val mutex = Mutex()
    private var loaded = false
    private val ids = mutableListOf<TrackId>()

    override suspend fun all(): List<TrackId> = mutex.withLock {
        ensureLoaded()
        ids.toList()
    }

    override suspend fun contains(id: TrackId): Boolean = mutex.withLock {
        ensureLoaded()
        id in ids
    }

    override suspend fun toggle(id: TrackId): Boolean = mutex.withLock {
        ensureLoaded()
        val replacement = if (id in ids) {
            ids.filterNot { it == id }
        } else {
            listOf(id) + ids
        }
        store.write(replacement.map { it.value })
        ids.clear()
        ids += replacement
        id in ids
    }

    override suspend fun replace(ids: List<TrackId>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = ids.distinct()
        store.write(replacement.map { it.value })
        this.ids.clear()
        this.ids += replacement
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        store.read().filter { it.isNotBlank() }.mapTo(ids, ::TrackId)
        loaded = true
    }
}
