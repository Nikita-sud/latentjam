/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals

internal class SmartQueueRowsTest {

    @Test
    fun aHandQueuedRowHiddenFromSmartStaysWhenTheLibraryIsSynced() {
        // current, a SMART pick, a track hidden from SMART that the listener queued by hand, a SMART pick now hidden.
        val ids = listOf("current", "pick", "hand-queued-hidden", "pick-now-hidden")
        val eligible = setOf("current", "pick")
        val smartOwned = setOf("pick", "pick-now-hidden")

        assertEquals(
            listOf(3),
            smartRowsToDrop(ids, keepThrough = 0, eligible = { it in eligible }, smartOwned = { it in smartOwned }),
        )
    }

    @Test
    fun whatAlreadyPlayedAndTheCurrentRowAreNeverDropped() {
        val ids = listOf("played-hidden", "current-hidden", "pick-hidden", "pick")
        val smartOwned = ids.toSet()

        assertEquals(
            listOf(2),
            smartRowsToDrop(ids, keepThrough = 1, eligible = { it == "pick" }, smartOwned = { it in smartOwned }),
        )
    }

    @Test
    fun droppedRowsComeLastFirstSoEachIndexStaysValid() {
        val ids = listOf("current", "a", "b", "c", "d")

        assertEquals(
            listOf(4, 2, 1),
            smartRowsToDrop(ids, keepThrough = 0, eligible = { it == "c" || it == "current" }, smartOwned = { true }),
        )
    }

    @Test
    fun anEmptyQueueAndAQueueWithNoCurrentRowDropNothingBeforeTheFirstRow() {
        assertEquals(emptyList(), smartRowsToDrop(emptyList(), keepThrough = -1, eligible = { false }, smartOwned = { true }))
        assertEquals(
            listOf(1),
            smartRowsToDrop(listOf("seed", "pick"), keepThrough = 0, eligible = { false }, smartOwned = { true }),
        )
    }
}
