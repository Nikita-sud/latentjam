/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * TrackId → Int in insertion order, as a `LinkedHashMap<TrackId, Int>` keeps it (a replaced id keeps its place, a
 * removed one leaves the order), in four arrays instead of an entry, a boxed TrackId and a boxed Int per song: ~16
 * bytes a song instead of ~70. Ids are kept as their strings, the ones the caller's TrackIds already hold.
 *
 * Slots are appended in insertion order; a removal leaves a hole that iteration skips, and the arrays are rebuilt once
 * holes outnumber live entries. Lookup is open addressing over slot numbers. Not thread-safe.
 */
internal class OrderedIdMap {
    private var keys = arrayOfNulls<String>(INITIAL)
    private var values = IntArray(INITIAL)
    /** Slots written so far, holes included. */
    private var used = 0
    var size: Int = 0
        private set

    /** Open-addressing table of slot + 1; 0 is empty. A slot whose key is null is a removed entry (keep probing). */
    private var table = IntArray(INITIAL * 2)

    operator fun get(id: TrackId): Int? {
        val slot = find(id.value)
        return if (slot < 0) null else values[slot]
    }

    operator fun contains(id: TrackId): Boolean = find(id.value) >= 0

    /** Sets [id]'s value, keeping its place when present; returns the previous value or null. */
    fun put(id: TrackId, value: Int): Int? {
        val key = id.value
        val slot = find(key)
        if (slot >= 0) {
            val previous = values[slot]
            values[slot] = value
            return previous
        }
        if (used == keys.size) grow()
        val newSlot = used++
        keys[newSlot] = key
        values[newSlot] = value
        size++
        insert(key, newSlot)
        if (used * 2 > table.size) rehash(table.size * 2)
        return null
    }

    /** Removes [id]; returns its value or null. */
    fun remove(id: TrackId): Int? {
        val slot = find(id.value)
        if (slot < 0) return null
        keys[slot] = null
        size--
        val previous = values[slot]
        if (used - size > maxOf(size, INITIAL)) compact()
        return previous
    }

    fun clear() {
        keys = arrayOfNulls(INITIAL)
        values = IntArray(INITIAL)
        table = IntArray(INITIAL * 2)
        used = 0
        size = 0
    }

    /** Visits every entry in order. [action] may change the visited entry's value through [setValue] only. */
    inline fun forEachEntry(action: (id: TrackId, value: Int, slot: Int) -> Unit) {
        for (slot in 0 until slotCount) {
            val key = keyAt(slot) ?: continue
            action(TrackId(key), valueAt(slot), slot)
        }
    }

    val slotCount: Int get() = used
    fun keyAt(slot: Int): String? = keys[slot]
    fun valueAt(slot: Int): Int = values[slot]
    fun setValue(slot: Int, value: Int) {
        values[slot] = value
    }

    /** An iterator over live slots, in order. */
    fun slots(): IntIterator = object : IntIterator() {
        private var next = advance(0)
        private fun advance(from: Int): Int {
            var slot = from
            while (slot < used && keys[slot] == null) slot++
            return slot
        }
        override fun hasNext(): Boolean = next < used
        override fun nextInt(): Int {
            if (next >= used) throw NoSuchElementException()
            val slot = next
            next = advance(slot + 1)
            return slot
        }
    }

    /** The key instance stored for [key], or null when absent. */
    fun storedKey(key: String): String? {
        val slot = find(key)
        return if (slot < 0) null else keys[slot]
    }

    /**
     * Replaces each stored key by the equal instance [canonical] gives, so that tables filled from a checkpoint hold
     * the library's own id strings instead of copies. Equal strings hash alike, so lookups are unchanged.
     */
    fun shareKeys(canonical: (String) -> String?) {
        for (slot in 0 until used) {
            val key = keys[slot] ?: continue
            val shared = canonical(key) ?: continue
            if (shared !== key && shared == key) keys[slot] = shared
        }
    }

    private fun find(key: String): Int {
        val mask = table.size - 1
        var at = mix(key.hashCode()) and mask
        while (true) {
            val entry = table[at]
            if (entry == 0) return -1
            val slot = entry - 1
            val stored = keys[slot]
            if (stored != null && stored == key) return slot
            at = (at + 1) and mask
        }
    }

    private fun insert(key: String, slot: Int) {
        val mask = table.size - 1
        var at = mix(key.hashCode()) and mask
        while (table[at] != 0) at = (at + 1) and mask
        table[at] = slot + 1
    }

    private fun grow() {
        val capacity = keys.size * 2
        keys = keys.copyOf(capacity)
        values = values.copyOf(capacity)
    }

    private fun rehash(capacity: Int) {
        table = IntArray(capacity)
        for (slot in 0 until used) keys[slot]?.let { insert(it, slot) }
    }

    /** Drops the holes, keeping order. */
    private fun compact() {
        var write = 0
        for (slot in 0 until used) {
            val key = keys[slot] ?: continue
            keys[write] = key
            values[write] = values[slot]
            write++
        }
        for (slot in write until used) keys[slot] = null
        used = write
        var capacity = INITIAL * 2
        while (capacity < used * 2 + 2) capacity *= 2
        rehash(capacity)
        if (keys.size > INITIAL && used * 4 < keys.size) {
            val shrunk = maxOf(INITIAL, used * 2)
            keys = keys.copyOf(shrunk)
            values = values.copyOf(shrunk)
        }
    }

    private companion object {
        const val INITIAL = 16

        /** Spreads String hashes over the table's low bits. */
        fun mix(hash: Int): Int = hash xor (hash ushr 16) * -0x61c88647
    }
}
