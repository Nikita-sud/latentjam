/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DurableWriterRewriteTest {
    private val track = CrashHarness.TRACK

    private fun setUp(case: WriteFixtures.Case, files: FaultFiles = FaultFiles()) =
        files.apply { put(track, case.original) }

    private fun writer(files: FaultFiles, atomic: Boolean) =
        DurableWriter(files.directory, { "w1" }, if (atomic) files.replacer() else null)

    @Test
    fun everyRewriteSavesTheEditBothWays() {
        for (atomic in listOf(false, true)) for (case in WriteFixtures.rewrites) {
            val plan = WriteFixtures.plan(case)
            assertIs<WritePlan.StreamingRewrite>(plan, case.name)
            val files = setUp(case)
            val result = writer(files, atomic).write(track, files.track(track), case.edits, targetFreeBytes = null)
            assertEquals(WriteResult.Saved(plan.newLength, rewritten = true), result, "${case.name}, atomic=$atomic")
            assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track), case.name)
            assertEquals(emptySet(), files.storeNames(), case.name)
        }
    }

    @Test
    fun aCopyOverNeedsRoomForTheNewFileAndABackupButAnAtomicReplaceOnlyForTheNewFile() {
        val case = WriteFixtures.rewrites.first()
        val plan = assertIs<WritePlan.StreamingRewrite>(WriteFixtures.plan(case))
        val room = plan.newLength + 16L * 1024 * 1024 + case.original.size / 2
        val copyOver = setUp(case, FaultFiles(storeCapacity = room))
        assertEquals(WriteResult.NotEnoughSpace, writer(copyOver, atomic = false).write(track, copyOver.track(track), case.edits, null))
        assertContentEquals(case.original, copyOver.trackBytes(track))
        val atomic = setUp(case, FaultFiles(storeCapacity = room))
        assertIs<WriteResult.Saved>(writer(atomic, atomic = true).write(track, atomic.track(track), case.edits, null))
    }

    @Test
    fun aStoreThatFillsWhileStagingTouchesNothing() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case, FaultFiles(storeCapacity = 100).apply { reportedFree = Long.MAX_VALUE })
        assertEquals(WriteResult.NotEnoughSpace, writer(files, atomic = false).write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aVolumeThatFillsDuringTheCopyOverPutsTheOriginalBack() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val full = VolumeFullAt(files.track(track), limit = case.original.size.toLong())
        assertIs<WriteResult.Failed>(writer(files, atomic = false).write(track, full, case.edits, targetFreeBytes = null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aDamagedStagedCopyIsNotFinishedButTheBackupIsPutBack() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case).apply { crashAtTrackWrite = 1 }
        runCatching { writer(files, atomic = false).write(track, files.track(track), case.edits, null) }
        files.powerLoss(keep = 100) // the first 100 bytes of the copy landed: neither original nor edit
        files.directory.open("w1.staged")!!.apply { write(0, byteArrayOf(0x55)); force() }
        assertEquals(listOf(TagRecovery.Outcome.RESTORED), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    /**
     * The crash suites never reach the backup: the staged copy is forced before `REPLACE_PREPARED`,
     * so recovery always finishes forward. Here the staged copy is damaged, so the backup must go
     * back, and power is lost again at every step of that restore.
     */
    @Test
    fun aRestoreFromTheBackupCutOffAnywhereStillEndsWhole() {
        val firstLosses = listOf<Pair<String, (FaultFiles) -> Unit>>(
            "nothing landed" to { it.powerLoss { _, _ -> false } },
            "keep 100" to { it.powerLoss(keep = 100) },
        ) + (0L until 16).map { seed -> "subset $seed" to { files: FaultFiles -> files.powerLossSubset(seed) } }
        for (case in WriteFixtures.rewrites) for ((firstLabel, firstLoss) in firstLosses) {
            var recoveryCrash = 1
            while (true) {
                val secondLosses = listOf<Pair<String, (FaultFiles) -> Unit>>(
                    "nothing landed" to { it.powerLoss { _, _ -> false } },
                    "keep 0" to { it.powerLoss(0) },
                    "everything landed" to { it.powerLoss(Long.MAX_VALUE) },
                    "subset $recoveryCrash" to { it.powerLossSubset(recoveryCrash.toLong()) },
                )
                var crashed = false
                for ((secondLabel, secondLoss) in secondLosses) {
                    val files = setUp(case).apply { crashAtTrackWrite = 1 }
                    runCatching { writer(files, atomic = false).write(track, files.track(track), case.edits, null) }
                    firstLoss(files)
                    files.directory.open("w1.staged")!!.apply { write(0, byteArrayOf(0x55)); force() }
                    files.crashAt = recoveryCrash
                    val first = try {
                        CrashHarness.recoverAll(files, atomic = false)
                    } catch (_: PowerLoss) {
                        null
                    }
                    crashed = first == null
                    val where = "${case.name}: $firstLabel, recovery crash $recoveryCrash" + if (crashed) ", $secondLabel" else ""
                    val outcomes = first ?: run {
                        secondLoss(files)
                        CrashHarness.recoverAll(files, atomic = false)
                    }
                    assertTrue(TagRecovery.Outcome.STUCK !in outcomes && TagRecovery.Outcome.FOREIGN !in outcomes, "$where → $outcomes")
                    // The edit only when the whole copy landed before the first power loss.
                    CrashHarness.assertWhole(files, case.original, WriteFixtures.expected(case), where)
                    if (!crashed) break
                }
                if (!crashed) break
                recoveryCrash++
            }
        }
    }

    @Test
    fun aFileChangedByAnotherAppBeforeTheReplaceIsLeftAlone() {
        for (atomic in listOf(false, true)) {
            val case = WriteFixtures.rewrites.first()
            val files = setUp(case)
            // Stop right after REPLACE_PREPARED is journaled: the track is still untouched.
            files.crashAt = operationsUntilPrepared(case, atomic)
            runCatching { writer(files, atomic).write(track, files.track(track), case.edits, null) }
            files.powerLoss()
            // Another app changes the file's last byte.
            files.track(track).apply { write(case.original.size - 1L, byteArrayOf((case.original.last() + 1).toByte())); force() }
            val changed = files.trackBytes(track)
            assertEquals(listOf(TagRecovery.Outcome.FOREIGN), CrashHarness.recoverAll(files, atomic), "atomic=$atomic")
            assertContentEquals(changed, files.trackBytes(track))
        }
    }

    @Test
    fun powerLossAnywhereDuringACopyOverLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = false)
    }

    @Test
    fun powerLossAnywhereDuringAnAtomicReplaceLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = true)
    }

    /** The operation count at which REPLACE_PREPARED is on storage, found by replaying the save. */
    private fun operationsUntilPrepared(case: WriteFixtures.Case, atomic: Boolean): Int {
        var n = 1
        while (true) {
            val files = setUp(case).apply { crashAt = n }
            runCatching { writer(files, atomic).write(track, files.track(track), case.edits, null) }
            files.powerLoss()
            if (Journal(files.directory).open().any { it.state == JournalState.REPLACE_PREPARED }) return n
            n++
        }
    }

    /** A track whose volume has no room past [limit] bytes. */
    private class VolumeFullAt(private val delegate: TargetFile, private val limit: Long) : TargetFile by delegate {
        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            if (offset + count > limit) throw StorageFullException("volume full")
            delegate.write(offset, bytes, from, count)
        }
    }
}
