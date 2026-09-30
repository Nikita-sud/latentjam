/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertEquals(emptySet(), copyOver.storeNames())
        val atomic = setUp(case, FaultFiles(storeCapacity = room))
        assertIs<WriteResult.Saved>(writer(atomic, atomic = true).write(track, atomic.track(track), case.edits, null))
        assertContentEquals(WriteFixtures.expected(case), atomic.trackBytes(track))
        assertEquals(emptySet(), atomic.storeNames())
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
        // Room for the file's own bytes, not for its growth: the copy lands that much, then fails.
        val limit = case.original.size.toLong()
        assertTrue(assertIs<WritePlan.StreamingRewrite>(WriteFixtures.plan(case)).newLength > limit)
        val full = VolumeFullAt(files.track(track), limit)
        assertIs<WriteResult.Failed>(writer(files, atomic = false).write(track, full, case.edits, targetFreeBytes = null))
        // The copy really was cut short over the track, so the original came back from the backup.
        assertFalse(assertNotNull(full.cutShort).contentEquals(case.original))
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
            assertEquals(emptySet(), files.storeNames(), "atomic=$atomic")
        }
    }

    @Test
    fun aReplaceWhoseResultCannotBeOpenedIsFinishedByRecovery() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val renaming = files.replacer()
        val openFails = AtomicReplacer { key, stagedName ->
            renaming.replace(key, stagedName).close()
            throw IllegalStateException("the replaced file could not be opened")
        }
        val result = DurableWriter(files.directory, { "w1" }, openFails).write(track, files.track(track), case.edits, null)
        assertEquals(WriteResult.RecoveryPending("w1"), result)
        assertEquals(listOf(TagRecovery.Outcome.COMPLETED), CrashHarness.recoverAll(files, atomic = true))
        assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aCopyOverWhoseReplacingRecordCannotBeWrittenIsFinishedByRecovery() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val result = DurableWriter(NoSecondRecord(files.directory), { "w1" }).write(track, files.track(track), case.edits, null)
        assertEquals(WriteResult.RecoveryPending("w1"), result)
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(listOf(TagRecovery.Outcome.COMPLETED), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(WriteFixtures.expected(case), files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun powerLossAnywhereDuringACopyOverLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = false)
    }

    @Test
    fun powerLossAnywhereDuringAnAtomicReplaceLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.everywhere(case, atomic = true)
    }

    @Test
    fun aProcessKilledAnywhereInACopyOverAndThenAPowerLossLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.afterProcessDeath(case, atomic = false)
    }

    @Test
    fun aProcessKilledAnywhereInAnAtomicReplaceAndThenAPowerLossLeavesTheOriginalOrTheEdit() {
        for (case in WriteFixtures.rewrites) CrashHarness.afterProcessDeath(case, atomic = true)
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

    /**
     * A track whose volume has no room past [limit] bytes: a write reaching past it lands up to the
     * limit and then fails. [cutShort] is the file as the first such write left it.
     */
    private class VolumeFullAt(private val delegate: TargetFile, private val limit: Long) : TargetFile by delegate {
        var cutShort: ByteArray? = null
            private set

        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            if (offset + count <= limit) return delegate.write(offset, bytes, from, count)
            val fits = maxOf(0L, limit - offset).toInt()
            if (fits > 0) delegate.write(offset, bytes, from, fits)
            if (cutShort == null) cutShort = delegate.read(0, delegate.length.toInt())
            throw StorageFullException("volume full")
        }
    }

    /** A store where a journal takes its first record but refuses every later one. */
    private class NoSecondRecord(private val delegate: RecoveryDirectory) : RecoveryDirectory by delegate {
        override fun open(name: String): TargetFile? {
            val file = delegate.open(name) ?: return null
            if (!name.endsWith(Journal.SUFFIX)) return file
            return object : TargetFile by file {
                override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) =
                    throw IllegalStateException("the journal could not be written")
            }
        }
    }
}
