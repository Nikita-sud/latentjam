/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * `associateBy { it.id }` for a library-sized list, without a map entry and a boxed id per song: the list itself plus
 * a compact id → position table (~20 bytes a song instead of ~56). Reads like the LinkedHashMap it replaces: keys in
 * first-occurrence order, the last song of a repeated id as its value. The list must not change afterwards.
 */
public fun <T : TrackDescriptor> List<T>.associateById(): Map<TrackId, T> =
    TrackLookup(this, positions(this)) { it }

/** [associateById] keyed by the id's string, for maps keyed that way (the playback controller's). */
public fun <T : TrackDescriptor> List<T>.associateByIdValue(): Map<String, T> =
    TrackLookup(this, positions(this)) { it.value }

private fun positions(tracks: List<TrackDescriptor>): OrderedIdMap {
    val positions = OrderedIdMap()
    for ((index, track) in tracks.withIndex()) positions.put(track.id, index)
    return positions
}

private class TrackLookup<K, T : TrackDescriptor>(
    private val tracks: List<T>,
    private val positions: OrderedIdMap,
    private val keyOf: (TrackId) -> K,
) : AbstractMap<K, T>() {
    override val size: Int get() = positions.size

    private fun idOf(key: Any?): TrackId? = when (key) {
        is TrackId -> key
        is String -> TrackId(key)
        else -> null
    }?.takeIf { keyOf(it) == key }

    override fun containsKey(key: K): Boolean = idOf(key)?.let { it in positions } == true

    override fun get(key: K): T? = idOf(key)?.let { id -> positions[id]?.let(tracks::get) }

    override val entries: Set<Map.Entry<K, T>>
        get() = object : AbstractSet<Map.Entry<K, T>>() {
            override val size: Int get() = positions.size
            override fun iterator(): Iterator<Map.Entry<K, T>> {
                val slots = positions.slots()
                return object : Iterator<Map.Entry<K, T>> {
                    override fun hasNext(): Boolean = slots.hasNext()
                    override fun next(): Map.Entry<K, T> {
                        val slot = slots.nextInt()
                        return Entry(keyOf(TrackId(positions.keyAt(slot)!!)), tracks[positions.valueAt(slot)])
                    }
                }
            }
        }

    private class Entry<K, T>(override val key: K, override val value: T) : Map.Entry<K, T> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
    }
}

/**
 * `map { it.id }` for a library-sized list: the ids' own strings in one array (4 bytes a song) instead of a boxed
 * TrackId each (~20). Equal, element for element, to the list `map` returns; it does not keep the descriptors.
 */
public fun List<TrackDescriptor>.idList(): List<TrackId> {
    val values = arrayOfNulls<String>(size)
    var index = 0
    for (track in this) values[index++] = track.id.value
    @Suppress("UNCHECKED_CAST")
    return TrackIdList(values as Array<String>)
}

private class TrackIdList(private val values: Array<String>) : AbstractList<TrackId>(), RandomAccess {
    override val size: Int get() = values.size
    override fun get(index: Int): TrackId = TrackId(values[index])
}

/** Position of each id in a library-sized list (the last one for a repeated id), compactly; see [idIndex]. */
public class TrackIdIndex internal constructor(private val positions: OrderedIdMap) {
    public val size: Int get() = positions.size

    /** The list position of [id], or -1. */
    public fun positionOf(id: String): Int = positions[TrackId(id)] ?: -1
}

/** A [TrackIdIndex] of this list: ~20 bytes a song instead of a map entry, a boxed key and a boxed Int. */
public fun List<TrackDescriptor>.idIndex(): TrackIdIndex = TrackIdIndex(positions(this))
