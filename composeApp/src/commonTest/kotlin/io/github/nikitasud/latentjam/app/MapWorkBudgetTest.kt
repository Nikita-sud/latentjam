// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MapWorkBudgetTest {

    @Test
    fun aReadingThatFitsIsReturnedWithoutACollection() {
        var collections = 0
        val bytes = mapWorkBytes(required = 100, available = { 150 }, collect = { collections++ })
        assertEquals(150, bytes)
        assertEquals(0, collections)
    }

    @Test
    fun aShortReadingIsTakenAgainAfterOneCollection() {
        val readings = ArrayDeque(listOf(80L, 120L))
        var collections = 0
        val bytes = mapWorkBytes(required = 100, available = { readings.removeFirst() }, collect = { collections++ })
        assertEquals(120, bytes)
        assertEquals(1, collections)
        assertEquals(0, readings.size)
    }
}
