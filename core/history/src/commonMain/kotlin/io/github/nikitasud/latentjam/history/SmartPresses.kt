/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lines of the press counter file, beside the history log. */
public interface SmartPressStore {
    public suspend fun read(): List<String>
    public suspend fun write(lines: List<String>)
}

/**
 * How many SMART queues were started from each seed (PLAN_FINAL §1.6 "press", kept with the history log): the ranker's
 * salted pick differs from press to press, so a second start from the same track gives a fresh queue. Keyed by the
 * §7.2b content key, so rescans and indexing keep the count. A missing or damaged file starts every seed at press 0.
 */
public class SmartPresses(private val store: SmartPressStore) {
    private val mutex = Mutex()
    private var counts: MutableMap<String, Long>? = null

    /** The press of a new SMART start from [contentKey] (0 the first time), counted as used. */
    public suspend fun next(contentKey: String): Long = mutex.withLock {
        val map = loaded()
        val press = map[contentKey] ?: 0L
        map[contentKey] = press + 1
        runCatching { store.write(map.entries.sortedBy { it.key }.map { (k, v) -> "$FORMAT|${k.encodeHex()}|$v" }) }
        press
    }

    /** The current count for [contentKey] without using one. */
    public suspend fun peek(contentKey: String): Long = mutex.withLock { loaded()[contentKey] ?: 0L }

    public suspend fun clear(): Unit = mutex.withLock {
        counts = LinkedHashMap()
        runCatching { store.write(emptyList()) }
    }

    private suspend fun loaded(): MutableMap<String, Long> {
        counts?.let { return it }
        val map = LinkedHashMap<String, Long>()
        for (line in runCatching { store.read() }.getOrDefault(emptyList())) {
            val parts = line.split('|')
            if (parts.size != 3 || parts[0] != FORMAT) continue
            val key = parts[1].decodeHex() ?: continue
            val value = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: continue
            map[key] = value
        }
        counts = map
        return map
    }

    private companion object {
        const val FORMAT = "p1"

        fun String.encodeHex(): String = encodeToByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun String.decodeHex(): String? {
            if (length % 2 != 0) return null
            return runCatching {
                ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }.decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
        }
    }
}
