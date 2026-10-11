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
 * personal signal and leave the phone only in a local backup file the listener writes themselves.
 */
public interface Favorites {

    /** Every favourite, newest first. */
    public suspend fun all(): List<TrackId>

    public suspend fun contains(id: TrackId): Boolean

    /** Flips [id]'s state. @return true when the track is now a favourite. */
    public suspend fun toggle(id: TrackId): Boolean

    /**
     * Hearts every one of [ids] in one durable write. The ones not yet favourites become the newest,
     * in the order given, so a selection or an imported playlist reads at the top of Favorites the
     * way it read where it came from; a track already hearted keeps its place, and a repeat counts
     * once. Nothing is written when every one already is a favourite.
     */
    public suspend fun addAll(ids: List<TrackId>): FavoritesChange

    /** Takes every one of [ids] out of the favourites in one durable write; none, none written. */
    public suspend fun removeAll(ids: Collection<TrackId>): FavoritesChange

    /** Replaces the whole list (the restore path), keeping order, dropping duplicates. */
    public suspend fun replace(ids: List<TrackId>)

    /** Exact ordered compare-and-set, used by duplicate merge without clobbering a newer heart. */
    public suspend fun replaceIfUnchanged(
        expected: List<TrackId>,
        replacement: List<TrackId>,
    ): Boolean
}

/**
 * A bulk edit as it landed: the exact list before and after it. Undo hands both to
 * [Favorites.replaceIfUnchanged], so it restores [before] only while nothing newer has changed the
 * list since.
 */
public data class FavoritesChange(
    val before: List<TrackId>,
    val after: List<TrackId>,
) {
    /** Whether the edit changed anything; an unchanged list has nothing to undo. */
    public val changed: Boolean get() = before != after

    /** How many tracks the edit hearted. */
    public val addedCount: Int get() = (after.size - before.size).coerceAtLeast(0)

    /** How many tracks the edit took out. */
    public val removedCount: Int get() = (before.size - after.size).coerceAtLeast(0)
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

    override suspend fun addAll(ids: List<TrackId>): FavoritesChange = mutex.withLock {
        ensureLoaded()
        val before = this.ids.toList()
        val present = before.toHashSet()
        val fresh = LinkedHashSet<TrackId>()
        for (id in ids) {
            if (id !in present) fresh += id
        }
        if (fresh.isEmpty()) return@withLock FavoritesChange(before, before)
        val after = fresh.toList() + before
        publish(after)
        FavoritesChange(before, after)
    }

    override suspend fun removeAll(ids: Collection<TrackId>): FavoritesChange = mutex.withLock {
        ensureLoaded()
        val before = this.ids.toList()
        val removed = ids.toHashSet()
        val after = before.filterNot { it in removed }
        if (after.size == before.size) return@withLock FavoritesChange(before, before)
        publish(after)
        FavoritesChange(before, after)
    }

    /** Writes [replacement] first, so a failed write leaves memory exactly as it was. */
    private suspend fun publish(replacement: List<TrackId>) {
        store.write(replacement.map { it.value })
        ids.clear()
        ids += replacement
    }

    override suspend fun replace(ids: List<TrackId>): Unit = mutex.withLock {
        ensureLoaded()
        val replacement = ids.distinct()
        store.write(replacement.map { it.value })
        this.ids.clear()
        this.ids += replacement
    }

    override suspend fun replaceIfUnchanged(
        expected: List<TrackId>,
        replacement: List<TrackId>,
    ): Boolean = mutex.withLock {
        ensureLoaded()
        if (ids != expected) return@withLock false
        val normalized = replacement.distinct()
        if (normalized != ids) {
            store.write(normalized.map { it.value })
            ids.clear()
            ids += normalized
        }
        true
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        store.read().filter { it.isNotBlank() }.mapTo(ids, ::TrackId)
        loaded = true
    }
}
