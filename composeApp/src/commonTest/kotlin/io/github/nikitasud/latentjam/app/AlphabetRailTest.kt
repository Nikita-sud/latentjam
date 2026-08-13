/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

internal class AlphabetRailTest {

    @Test
    fun bucketsFollowTheListWithFirstRowPerBucket() {
        val rail = railIndexOf(listOf("Abba", "Alla", "Beta", "Царь"))
        assertEquals(listOf("A", "B", "Ц"), rail.buckets)
        assertEquals(listOf(0, 2, 3), rail.startIndexes)
    }

    @Test
    fun digitsAndSymbolsShareTheHashBucket() {
        val rail = railIndexOf(listOf("90s", "!!!", "Alpha"))
        assertEquals(listOf("#", "A"), rail.buckets)
        assertEquals(listOf(0, 2), rail.startIndexes)
    }

    @Test
    fun nullsAtTheEndDoNotDuplicateTheHashBucket() {
        // Digits bucket to "#" at the front; null names sort last and bucket to "#" again.
        // The rail keeps one entry pointing at the FIRST occurrence.
        val rail = railIndexOf(listOf("7 rings", "Alpha", null, null))
        assertEquals(listOf("#", "A"), rail.buckets)
        assertEquals(listOf(0, 1), rail.startIndexes)
    }
}
