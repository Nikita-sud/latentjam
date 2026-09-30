/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A store listing that fails, or flickers, must never read as "no interrupted saves". */
internal class StoreListingTest {
    private val track = CrashHarness.TRACK

    /** A save cut off mid-patch: its record is open, and its saved bytes are the only way back. */
    private fun interrupted(): Pair<FaultFiles, WriteFixtures.Case> {
        val case = WriteFixtures.inPlace.first()
        val files = FaultFiles().apply { put(track, case.original) }
        val dying = object : TargetFile by files.track(track) {
            override fun force() = throw IllegalStateException("process died")
        }
        assertIs<WriteResult.RecoveryPending>(DurableWriter(files.directory, { "w1" }).write(track, dying, case.edits, null))
        assertFalse(files.trackBytes(track).contentEquals(case.original), "the patch is in the file")
        return files to case
    }

    /** [delegate], except that its first [bad] listings read as [listing] gives them. */
    private class Flaky(
        private val delegate: RecoveryDirectory,
        private var bad: Int,
        private val listing: () -> List<String>,
    ) : RecoveryDirectory by delegate {
        override fun names(): List<String> = if (bad-- > 0) listing() else delegate.names()
    }

    @Test
    fun aSweepListsTheStoreOnceSoAListingThatFlickersEmptyDeletesNothing() {
        val (files, case) = interrupted()
        val before = files.storeNames()
        TagRecovery(Flaky(files.directory, bad = 1) { emptyList() }).sweep()
        assertEquals(before, files.storeNames())
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun aSweepWhoseListingFailsDeletesNothing() {
        val (files, case) = interrupted()
        val before = files.storeNames()
        TagRecovery(Flaky(files.directory, bad = 1) { throw IllegalStateException("EMFILE") }).sweep()
        assertEquals(before, files.storeNames())
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun aSaveWhoseStoreCannotBeListedFailsAndLeavesTheInterruptedFileAlone() {
        val (files, case) = interrupted()
        val half = files.trackBytes(track)
        val before = files.storeNames()
        val flaky = Flaky(files.directory, bad = Int.MAX_VALUE) { throw IllegalStateException("EIO") }
        assertIs<WriteResult.Failed>(DurableWriter(flaky, { "w2" }).write(track, files.track(track), case.edits, null))
        assertContentEquals(half, files.trackBytes(track))
        assertEquals(before, files.storeNames())
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
    }
}
