/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.write.JournalRecord
import io.github.nikitasud.latentjam.library.tags.write.JournalState
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class InterruptedSavesTest {

    private fun record(id: String, target: String, state: JournalState) = JournalRecord(id, target, state, 10, 10)

    @Test
    fun eachWaitingFileIsNamedOnceAndMarkedWhenItIsBeingRepaired() {
        val tracks = listOf(
            TrackDescriptor(TrackId("1"), title = "Zebra", audioUri = "content://media/external/audio/media/1"),
            TrackDescriptor(TrackId("2"), title = "Apple", audioUri = "content://media/external/audio/media/2"),
        )
        val saves = interruptedSavesOf(
            listOf(
                record("w1", "content://media/external/audio/media/1", JournalState.PATCH_PREPARED),
                record("w2", "content://media/external/audio/media/2", JournalState.REPLACING),
                record("w3", "content://media/external/audio/media/2", JournalState.REPLACING),
                record("w4", "Music/gone.flac", JournalState.REPLACE_PREPARED),
            ),
            tracks,
        ) { it.audioUri }
        assertEquals(listOf("Apple", "gone.flac", "Zebra"), saves.map { it.label })
        assertEquals(listOf(true, false, false), saves.map { it.underRepair })
    }

    @Test
    fun aFileUnderRepairCanBeForgottenOnlyAfterAFinishCouldNotFinishIt() {
        val records = listOf(
            record("w1", "patched", JournalState.ROLLING_BACK),
            record("w2", "replacing", JournalState.REPLACING),
        )
        val before = interruptedSavesOf(records, emptyList()) { null }.associate { it.label!! to it.forgettable }
        assertEquals(mapOf("patched" to true, "replacing" to false), before)
        val after = interruptedSavesOf(records, emptyList(), couldNotFinish = setOf("replacing")) { null }
        assertEquals(listOf(true, true), after.map { it.forgettable })
    }

    @Test
    fun aFileAFinishFoundNotThereSaysSo() {
        val records = listOf(
            record("w1", "gone", JournalState.PATCH_PREPARED),
            record("w2", "here", JournalState.PATCH_PREPARED),
        )
        val saves = interruptedSavesOf(records, emptyList(), couldNotFinish = setOf("gone", "here"), missing = setOf("gone")) { null }
        assertEquals(mapOf("gone" to true, "here" to false), saves.associate { it.label!! to it.missing })
    }

    @Test
    fun aFileTheLibraryNoLongerHoldsIsNamedByWhereItWasNeverByItsId() {
        val records = listOf(
            JournalRecord("w1", "content://media/external/audio/media/339", JournalState.PATCH_PREPARED, 10, 10, path = "/storage/emulated/0/Music/rec2.flac"),
            // From before records kept the path: nothing names it, and a bare id would mean nothing.
            record("w2", "content://media/external/audio/media/340", JournalState.PATCH_PREPARED),
        )
        val saves = interruptedSavesOf(records, emptyList()) { it.audioUri }
        assertEquals(listOf("rec2.flac", null), saves.map { it.label })
    }

    @Test
    fun aSongTheLibraryStillHoldsKeepsItsTitleOverThePath() {
        val track = TrackDescriptor(TrackId("1"), title = "Daddy Cool", audioUri = "content://media/external/audio/media/1")
        val records = listOf(
            JournalRecord("w1", "content://media/external/audio/media/1", JournalState.PATCH_PREPARED, 10, 10, path = "/Music/01.flac"),
        )
        assertEquals("Daddy Cool", interruptedSavesOf(records, listOf(track)) { it.audioUri }.single().label)
    }
}
