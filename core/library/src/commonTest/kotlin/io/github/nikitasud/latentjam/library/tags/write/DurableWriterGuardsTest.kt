/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.WritePlans
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** What a save does when its store, its id, its replacer or the file itself misbehaves. */
internal class DurableWriterGuardsTest {
    private val track = CrashHarness.TRACK

    private fun setUp(case: WriteFixtures.Case) = FaultFiles().apply { put(track, case.original) }

    @Test
    fun aJournalThatCannotBeWrittenOrRemovedStillLeavesARecoverableStore() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        val writer = DurableWriter(JournalFaults(files.directory), { "w1" })
        assertIs<WriteResult.Failed>(writer.write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        // The record could be neither completed nor removed. Its patch file must still be there.
        assertEquals(listOf(TagRecovery.Outcome.ROLLED_BACK), CrashHarness.recoverAll(files, atomic = false))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aWriteIdWithADotIsRejected() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        assertFailsWith<IllegalArgumentException> {
            DurableWriter(files.directory, { "w.1" }).write(track, files.track(track), case.edits, null)
        }
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun aWriteIdAlreadyInTheStoreIsRefusedAndItsSaveLeftAlone() {
        val case = WriteFixtures.inPlace.first()
        val files = setUp(case)
        Journal(files.directory).append(JournalRecord("w1", "another track", JournalState.PATCH_PREPARED, 10, 10))
        val journal = files.directory.open("w1.journal")!!.use { FileOps.readAll(it) }
        assertIs<WriteResult.Failed>(DurableWriter(files.directory, { "w1" }).write(track, files.track(track), case.edits, null))
        assertContentEquals(journal, files.directory.open("w1.journal")!!.use { FileOps.readAll(it) })
        assertEquals(setOf("w1.journal"), files.storeNames())
        assertContentEquals(case.original, files.trackBytes(track))
    }

    @Test
    fun anAtomicReplaceRefusedBeforeTheRenameKeepsTheOriginalAndNothingElse() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val refusing = AtomicReplacer { _, _ -> throw IllegalStateException("rename refused") }
        val writer = DurableWriter(files.directory, { "w1" }, refusing)
        assertIs<WriteResult.Failed>(writer.write(track, files.track(track), case.edits, null))
        assertContentEquals(case.original, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    @Test
    fun aFileThatChangesLengthBeforeItIsPatchedIsNotTouched() {
        val case = WriteFixtures.inPlace.first()
        val dryRun = setUp(case).let { files ->
            GrowingFile(files.track(track), growAt = 0).also {
                assertIs<WriteResult.Saved>(DurableWriter(files.directory, { "w1" }).write(track, it, case.edits, null))
            }
        }
        val reads = assertNotNull(dryRun.readsBeforeFirstWrite)
        val grown = case.original + GROWTH
        val grownEdit = WritePlans.applyInMemory(grown, TagCodecs.plan(ByteArraySource(grown), case.edits))!!
        var refused = 0
        // Another app appends a byte at every length read the save makes before it writes.
        for (growAt in 1..reads) {
            val files = setUp(case)
            val result = DurableWriter(files.directory, { "w1" }).write(track, GrowingFile(files.track(track), growAt), case.edits, null)
            val now = files.trackBytes(track)
            when (result) {
                is WriteResult.Saved -> assertContentEquals(grownEdit, now, "grown at length read $growAt")
                is WriteResult.Failed -> {
                    refused++
                    assertContentEquals(grown, now, "grown at length read $growAt")
                }
                else -> fail("grown at length read $growAt: $result")
            }
            assertEquals(emptySet(), files.storeNames(), "grown at length read $growAt")
        }
        assertTrue(refused > 0)
    }

    @Test
    fun aFileThatGrowsWhileItIsBackedUpIsNotReplaced() {
        val case = WriteFixtures.rewrites.first()
        val files = setUp(case)
        val growing = GrowingFile(files.track(track), growWhen = { "w1.backup" in files.storeNames() })
        assertIs<WriteResult.Failed>(DurableWriter(files.directory, { "w1" }).write(track, growing, case.edits, null))
        assertContentEquals(case.original + GROWTH, files.trackBytes(track))
        assertEquals(emptySet(), files.storeNames())
    }

    /** A journal whose force and delete both fail, as on a store that turned read-only mid-save. */
    private class JournalFaults(private val delegate: RecoveryDirectory) : RecoveryDirectory by delegate {
        override fun create(name: String): TargetFile {
            val file = delegate.create(name)
            if (!name.endsWith(".journal")) return file
            return object : TargetFile by file {
                override fun force() = throw IllegalStateException("the journal could not be forced")
            }
        }

        override fun delete(name: String) {
            if (name.endsWith(".journal")) throw IllegalStateException("the journal could not be deleted")
            delegate.delete(name)
        }
    }

    /**
     * Another app appends [GROWTH] to the file at the [growAt]th read of its length (0 = never), or at
     * the first read when [growWhen] holds. Remembers how many length reads came before the first write.
     */
    private class GrowingFile(
        private val delegate: TargetFile,
        private val growAt: Int = 0,
        private val growWhen: () -> Boolean = { false },
    ) : TargetFile by delegate {
        private var lengthReads = 0
        private var grown = false
        var readsBeforeFirstWrite: Int? = null
            private set

        override val length: Long
            get() {
                lengthReads++
                if (!grown && (lengthReads == growAt || growWhen())) {
                    grown = true
                    delegate.write(delegate.length, GROWTH)
                    delegate.force()
                }
                return delegate.length
            }

        override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
            if (readsBeforeFirstWrite == null) readsBeforeFirstWrite = lengthReads
            delegate.write(offset, bytes, from, count)
        }
    }

    private companion object {
        val GROWTH = byteArrayOf(9)
    }
}
