/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

internal class CheckpointFanOutTest {

    @Test
    fun everyAttachedOwnerReceivesEachCheckpointAndALateOwnerTheLatest() {
        val fanOut = CheckpointFanOut()
        val first = ArrayList<List<String>>()
        val second = ArrayList<List<String>>()
        fanOut.attach("a") { first += it }
        fanOut.save(listOf("x"))
        fanOut.attach("b") { second += it }
        fanOut.save(listOf("y"))
        fanOut.detach("a")
        fanOut.save(listOf("z"))
        assertEquals(listOf(listOf("x"), listOf("y")), first)
        assertEquals(listOf(listOf("x"), listOf("y"), listOf("z")), second)
    }

    @Test
    fun anOwnerAttachedBeforeAnyCheckpointReceivesNothingYet() {
        val fanOut = CheckpointFanOut()
        val seen = ArrayList<List<String>>()
        fanOut.attach("a") { seen += it }
        assertEquals(emptyList(), seen)
    }
}
