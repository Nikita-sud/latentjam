/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DurableWriterPatchTest {
    private val track = CrashHarness.TRACK

    private fun setUp(case: WriteFixtures.Case, files: FaultFiles = FaultFiles()) =
        files.apply { put(track, case.original) }

    private fun writer(files: FaultFiles) = DurableWriter(files.directory, { "w1" })

    @Test
    fun everyInPlaceCaseSavesTheEditAndLeavesNothingBehind() {
        for (case in WriteFixtures.inPlace) {
            val plan = WriteFixtures.plan(case)
            assertIs<WritePlan.InPlacePatch>(plan, case.name)
            val files = setUp(case)
            val result = writer(files).write(track, files.track(track), case.edits, targetFreeBytes = null)
            assertEquals(WriteResult.Saved(plan.newLength, rewritten = false), result, case.name)
            assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track), case.name)
            assertEquals(emptySet(), files.storeNames(), case.name)
        }
    }

    @Test
    fun aSaveThatChangesNothingWritesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        assertEquals(WriteResult.NoChange, writer(files).write(track, files.track(track), TagEdits(title = "Old"), null))
        assertEquals(0, files.operations)
    }

    @Test
    fun anUnsupportedFileIsLeftAsItIs() {
        val files = FaultFiles().apply { put(track, ByteArray(100) { 7 }) }
        assertEquals(
            WriteResult.Refused(TagRefusal.UNSUPPORTED_FORMAT),
            writer(files).write(track, files.track(track), TagEdits(title = "x"), null),
        )
        assertEquals(0, files.operations)
    }

    @Test
    fun noRoomInTheStoreTouchesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case, FaultFiles(storeCapacity = 100))
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aStoreThatFillsDuringTheSaveTouchesNothing() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case, FaultFiles(storeCapacity = 10).apply { reportedFree = Long.MAX_VALUE })
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun noRoomOnTheVolumeForAGrowingPatchTouchesNothing() {
        val case = WriteFixtures.inPlace.single { it.name == "m4a whose last moov grows" }
        val plan = assertIs<WritePlan.InPlacePatch>(WriteFixtures.plan(case))
        assertTrue(plan.newLength > case.original.size)
        val files = setUp(case)
        assertEquals(WriteResult.NotEnoughSpace, writer(files).write(track, files.track(track), case.edits, targetFreeBytes = 0))
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun aWriteThatDoesNotReadBackIsRolledBack() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        val flipping = FlippingFile(files.track(track))
        assertIs<WriteResult.Failed>(writer(files).write(track, flipping, case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aFileWithAnOpenRecordIsNotEditedAgainUntilRecovered() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        assertIs<WriteResult.RecoveryPending>(writer(files).write(track, files.track(track), case.edits, null))
    }

    @Test
    fun recoveryRollsBackAnInterruptedPatch() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun recoveryLeavesAFileSomeoneElseChanged() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = Long.MAX_VALUE)
        // Another tagger rewrites the last byte of the file (audio, outside every range we wrote).
        val last = case.original.size.toLong() - 1
        files.track(track).apply { write(last, byteArrayOf((case.original.last() + 1).toByte())); force() }
        val changed = files.trackBytes(track)
        assertEquals(listOf(TagRecovery.Outcome.FOREIGN), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(changed, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun powerLossDuringTheWritersOwnRollBackIsFinishedByRecovery() {
        val case = WriteFixtures.inPlace.first()
        val plan = assertIs<WritePlan.InPlacePatch>(WriteFixtures.plan(case))
        // The patch fails its read-back and is forced; power is lost at the roll-back's first write.
        val files = setUp(case).apply { crashAtTrackWrite = plan.writes.size + 1 }
        runCatching { writer(files).write(track, FlippingFile(files.track(track)), case.edits, null) }
        files.powerLoss { _, _ -> false }
        assertTrue(!files.trackBytes(track).contentEquals(case.original), "the flipped patch is on storage")
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun recoveryLeavesARollingBackFileSomeoneElseChangedSince() {
        val case = WriteFixtures.inPlace.first()
        val plan = assertIs<WritePlan.InPlacePatch>(WriteFixtures.plan(case))
        val changes = mapOf<String, (TargetFile) -> Unit>(
            // Another tagger rewrote the file with a different layout: here, one byte longer.
            "a different length" to { it.write(it.length, byteArrayOf(1)) },
            // Byte 0 of the ID3 header lies in the head window, outside every range we wrote.
            "a head byte outside our ranges" to { it.write(0, byteArrayOf((case.original[0] + 1).toByte())) },
        )
        for ((name, change) in changes) {
            val files = setUp(case).apply { crashAtTrackWrite = plan.writes.size + 1 }
            runCatching { writer(files).write(track, FlippingFile(files.track(track)), case.edits, null) }
            files.powerLoss { _, _ -> false }
            assertEquals(JournalState.ROLLING_BACK, Journal(files.directory).open().single().state, name)
            files.track(track).apply { change(this); force() }
            val found = files.trackBytes(track)
            assertEquals(listOf(TagRecovery.Outcome.FOREIGN), CrashHarness.recoverAll(files, atomic = false), name)
            assertContentEquals(found, files.trackBytes(track), name)
            assertEquals(emptySet(), files.storeNames(), name)
        }
    }

    @Test
    fun powerLossAnywhereInTheWritersOwnRollBackLeavesTheOriginal() {
        for (case in WriteFixtures.inPlace) CrashHarness.everywhere(case, atomic = false, flip = true)
    }

    @Test
    fun powerLossAnywhereLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.inPlace) CrashHarness.everywhere(case, atomic = false)
    }

    @Test
    fun aProcessKilledAnywhereAndThenAPowerLossLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.inPlace) CrashHarness.afterProcessDeath(case, atomic = false)
    }

    @Test
    fun aProcessKilledAnywhereInTheWritersOwnRollBackAndThenAPowerLossLeavesTheOriginal() {
        for (case in WriteFixtures.inPlace) CrashHarness.afterProcessDeath(case, atomic = false, flip = true)
    }
}
