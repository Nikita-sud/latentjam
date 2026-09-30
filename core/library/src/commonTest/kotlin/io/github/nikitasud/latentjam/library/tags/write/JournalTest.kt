/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JournalTest {
    private fun record(state: JournalState, id: String = "w1") =
        JournalRecord(id, "content://media/external/audio/media/7\tweird\nname%", state, 100, 120, 5, 6, atomic = true)

    @Test
    fun theLatestRecordWinsAndSurvivesAReopen() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.REPLACE_PREPARED))
        journal.append(record(JournalState.REPLACING))
        assertEquals(record(JournalState.REPLACING), Journal(files.directory).latest("w1"))
        assertEquals(listOf(record(JournalState.REPLACING)), Journal(files.directory).open())
    }

    @Test
    fun finishedWritesAreNotOpen() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        journal.append(record(JournalState.DONE))
        assertTrue(journal.open().isEmpty())
    }

    @Test
    fun aTornRecordIsIgnoredAndDoesNotSwallowTheNextOne() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        // A record torn after 10 bytes by a power loss.
        val file = files.directory.open("w1.journal")!!
        file.write(file.length, "v1\tw1\tcont".encodeToByteArray())
        file.force()
        assertEquals(JournalState.PATCH_PREPARED, journal.latest("w1")?.state)
        journal.append(record(JournalState.ROLLED_BACK))
        assertEquals(JournalState.ROLLED_BACK, Journal(files.directory).latest("w1")?.state)
    }

    @Test
    fun aCorruptedLineIsIgnored() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        val file = files.directory.open("w1.journal")!!
        val bytes = file.read(0, file.length.toInt())!!
        bytes[5] = (bytes[5] + 1).toByte()
        file.write(0, bytes)
        assertNull(journal.latest("w1"))
    }

    /** The mutating operations a first append performs: create, write, force, then the directory sync. */
    private fun firstAppendOperations(): Int {
        val files = FaultFiles()
        Journal(files.directory).append(record(JournalState.PATCH_PREPARED))
        return files.operations
    }

    @Test
    fun aCompleteAppendSurvivesPowerLoss() {
        val files = FaultFiles()
        Journal(files.directory).append(record(JournalState.PATCH_PREPARED))
        files.powerLoss()
        assertEquals(JournalState.PATCH_PREPARED, Journal(files.directory).latest("w1")?.state)
    }

    @Test
    fun aSecondRecordSurvivesPowerLossOnceAppendReturns() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        journal.append(record(JournalState.REPLACING))
        files.powerLoss()
        assertEquals(JournalState.REPLACING, Journal(files.directory).latest("w1")?.state)
    }

    @Test
    fun aCrashAtTheDirectorySyncLosesTheNewJournal() {
        val operations = firstAppendOperations()
        assertEquals(4, operations)
        val files = FaultFiles()
        files.crashAt = operations // the directory sync itself
        assertFailsWith<PowerLoss> { Journal(files.directory).append(record(JournalState.PATCH_PREPARED)) }
        files.powerLoss()
        assertTrue(Journal(files.directory).open().isEmpty())
        assertTrue("w1.journal" !in files.storeNames())
    }

    @Test
    fun aCrashAtTheForceLosesTheRecord() {
        val files = FaultFiles()
        files.crashAt = firstAppendOperations() - 1 // the force, before the directory is synced
        assertFailsWith<PowerLoss> { Journal(files.directory).append(record(JournalState.PATCH_PREPARED)) }
        files.powerLoss()
        assertNull(Journal(files.directory).latest("w1"))
    }

    @Test
    fun aRetriedFirstAppendStillSyncsItsDirectoryEntry() {
        val files = FaultFiles(storeCapacity = 0)
        assertFailsWith<StorageFullException> { Journal(files.directory).append(record(JournalState.PATCH_PREPARED)) }
        files.storeCapacity = Long.MAX_VALUE
        Journal(files.directory).append(record(JournalState.PATCH_PREPARED))
        files.powerLoss()
        assertEquals(JournalState.PATCH_PREPARED, Journal(files.directory).latest("w1")?.state)
    }

    @Test
    fun forgetDeletesTheWritesJournal() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.DONE))
        journal.forget("w1")
        assertTrue("w1.journal" !in files.storeNames())
    }
}
