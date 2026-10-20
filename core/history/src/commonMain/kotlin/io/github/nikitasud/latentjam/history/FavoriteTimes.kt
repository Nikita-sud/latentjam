/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lines of the favourite-times file, beside the favourites. */
public interface FavoriteTimeStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

/**
 * When each current favourite was hearted, for the FULL ranker's `favourited_days_ago` (behind "Learn from my
 * habits"). One line per favourite; a heart removed drops its line. Favourites from before this file existed have no
 * time (unknown), and a restore from a backup dates its hearts to the restore. A damaged file reads as no times.
 */
public class FavoriteTimes(private val store: FavoriteTimeStore) {
    private val mutex = Mutex()
    private var times: MutableMap<TrackId, Long>? = null

    /** The heart time of every favourite that has one. */
    public suspend fun all(): Map<TrackId, Long> = mutex.withLock { HashMap(loaded()) }

    /** [added] were hearted at [atMs], [removed] lost their heart. */
    public suspend fun update(added: Collection<TrackId>, removed: Collection<TrackId>, atMs: Long): Unit = mutex.withLock {
        if (added.isEmpty() && removed.isEmpty()) return
        val map = loaded()
        for (id in removed) map.remove(id)
        for (id in added) map[id] = atMs
        runCatching { store.write(map.entries.map { (id, at) -> "$FORMAT|${id.value.encodeHex()}|$at" }) }
    }

    private suspend fun loaded(): MutableMap<TrackId, Long> {
        times?.let { return it }
        val map = LinkedHashMap<TrackId, Long>()
        for (line in runCatching { store.read() }.getOrDefault(emptyList())) {
            val p = line.split('|')
            if (p.size != 3 || p[0] != FORMAT) continue
            val id = p[1].decodeHex() ?: continue
            val at = p[2].toLongOrNull() ?: continue
            map[TrackId(id)] = at
        }
        times = map
        return map
    }

    private companion object {
        const val FORMAT = "ft1"

        fun String.encodeHex(): String = encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun String.decodeHex(): String? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }
}
