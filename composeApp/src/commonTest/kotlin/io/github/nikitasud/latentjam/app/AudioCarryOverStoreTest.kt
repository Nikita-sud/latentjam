/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.AudioCarryOver
import io.github.nikitasud.latentjam.smart.AudioCarryOverResult
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AudioCarryOverStoreTest {

    private var payload: String? = null
    private fun store() = AudioCarryOverStore({ payload }, { payload = it })

    private val a = AudioCarryOver(TrackId("a|\n1"), "rev|1", 10)
    private val b = AudioCarryOver(TrackId("b"), null, 20)

    @Test
    fun carryOversSurviveARestart() = runTest {
        store().add(listOf(a, b))
        assertEquals(setOf(a, b), store().pending().toSet())
    }

    @Test
    fun aSettledCarryOverIsDroppedAndAnUnsettledOneAfterThreeSyncs() = runTest {
        val store = store()
        store.add(listOf(a, b))
        store.settle(listOf(a, b), AudioCarryOverResult(applied = setOf(a.trackId), settled = setOf(a.trackId)))
        assertEquals(listOf(b), store.pending())
        repeat(2) { store.settle(listOf(b), AudioCarryOverResult(emptySet(), emptySet())) }
        assertEquals(emptyList(), store.pending())
    }

    @Test
    fun aNewerSaveOfTheSameTrackReplacesItsCarryOver() = runTest {
        val store = store()
        store.add(listOf(a))
        val newer = a.copy(oldRevision = "rev|2", newLength = 11)
        store.add(listOf(newer))
        // The sync offered the old one; settling it must not drop the newer save's carry-over.
        store.settle(listOf(a), AudioCarryOverResult(emptySet(), setOf(a.trackId)))
        assertEquals(listOf(newer), store.pending())
    }

    @Test
    fun onlyFilesSavedWithAKnownLengthCarryOver() {
        val tracks = listOf(
            TrackDescriptor(TrackId("1"), audioUri = "k1", sourceRevision = "r1"),
            TrackDescriptor(TrackId("2"), audioUri = "k2", sourceRevision = "r2"),
            TrackDescriptor(TrackId("3"), audioUri = "k3", sourceRevision = "r3"),
        )
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("k1", FileWriteStatus.SAVED, newLength = 100),
                TagSaveEntry("k2", FileWriteStatus.UNCHANGED),
                TagSaveEntry("k3", FileWriteStatus.REFUSED, problem = TagProblem.DAMAGED),
            ),
        )
        assertEquals(listOf(AudioCarryOver(TrackId("1"), "r1", 100)), audioCarryOversOf(tracks, result) { it.audioUri })
    }
}
