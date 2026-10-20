/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** OrderedIdMap must behave as the LinkedHashMap<TrackId, Int> it replaces: values, order, removals, holes. */
internal class OrderedIdMapTest {

    @Test
    fun matchesALinkedHashMapThroughPutsReplacementsAndRemovals() {
        val random = Random(20261020)
        val map = OrderedIdMap()
        val reference = LinkedHashMap<TrackId, Int>()
        repeat(60_000) { step ->
            val id = TrackId("t${random.nextInt(4_000)}")
            when (random.nextInt(10)) {
                in 0..5 -> assertEquals(reference.put(id, step), map.put(id, step))
                in 6..8 -> assertEquals(reference.remove(id), map.remove(id))
                else -> {
                    assertEquals(reference[id], map[id])
                    assertEquals(id in reference, id in map)
                }
            }
            if (step % 5_000 == 0) {
                // Removing most entries forces compaction of the holes.
                reference.keys.filter { random.nextInt(4) != 0 }.forEach { assertEquals(reference.remove(it), map.remove(it)) }
            }
            assertEquals(reference.size, map.size)
        }
        val order = ArrayList<Pair<TrackId, Int>>()
        map.forEachEntry { id, value, _ -> order += id to value }
        assertEquals(reference.entries.map { it.key to it.value }, order)
        val slots = ArrayList<String>()
        val iterator = map.slots()
        while (iterator.hasNext()) slots += map.keyAt(iterator.nextInt())!!
        assertEquals(reference.keys.map(TrackId::value), slots)
        map.clear()
        assertEquals(0, map.size)
        assertEquals(null, map[TrackId("t1")])
    }
}
