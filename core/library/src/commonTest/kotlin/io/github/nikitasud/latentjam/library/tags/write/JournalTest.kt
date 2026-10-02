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
    fun aRecordKeepsWhereTheFileWasAndOneWithoutItIsWrittenAsBefore() {
        val files = FaultFiles()
        val located = record(JournalState.PATCH_PREPARED).copy(path = "/storage/emulated/0/Music/a\tb%.flac")
        Journal(files.directory).append(located)
        assertEquals(located, Journal(files.directory).latest("w1"))
        // Without a path the line is the version-1 one, which every earlier build reads.
        assertTrue(Journal.encode(record(JournalState.PATCH_PREPARED)).decodeToString().startsWith("v1\t"))
    }

    @Test
    fun aVersionOneLineStillReads() {
        val line = "v1\tw1\tcontent://media/external/audio/media/7\tPATCH_PREPARED\t100\t120\t-1\t-1\tfalse"
        val crc = io.github.nikitasud.latentjam.library.tags.Crc32.of(line.encodeToByteArray()).toString(16)
        val bytes = "$line\t$crc\n".encodeToByteArray()
        val decoded = Journal.decode(bytes, 0, bytes.size - 1)
        assertEquals(JournalRecord("w1", "content://media/external/audio/media/7", JournalState.PATCH_PREPARED, 100, 120), decoded)
        assertNull(decoded?.path)
    }

    @Test
    fun aLineFromALaterVersionIsReadForWhatThisOneKnows() {
        // A later build may add fields; dropping its record would let the sweep delete its backup.
        val line = "v3\tw1\tcontent://media/external/audio/media/7\tREPLACING\t100\t120\t5\t6\ttrue\t/Music/a.flac\tsomething new"
        val crc = io.github.nikitasud.latentjam.library.tags.Crc32.of(line.encodeToByteArray()).toString(16)
        val bytes = "$line\t$crc\n".encodeToByteArray()
        assertEquals(
            JournalRecord("w1", "content://media/external/audio/media/7", JournalState.REPLACING, 100, 120, 5, 6, atomic = true, path = "/Music/a.flac"),
            Journal.decode(bytes, 0, bytes.size - 1),
        )
        val short = "v3\tw1\tk\tREPLACING\t100\t120\t5\t6\ttrue"
        val shortBytes = "$short\t${io.github.nikitasud.latentjam.library.tags.Crc32.of(short.encodeToByteArray()).toString(16)}\n".encodeToByteArray()
        assertNull(Journal.decode(shortBytes, 0, shortBytes.size - 1), "a later version has at least version 2's fields")
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
    fun aRecordMissingOnlyItsNewlineReadsTheSameBeforeAndAfterTheNextAppend() {
        val files = FaultFiles()
        val journal = Journal(files.directory)
        journal.append(record(JournalState.PATCH_PREPARED))
        // Power lost with every byte of the next record on storage but its line end.
        val line = Journal.encode(record(JournalState.ROLLING_BACK))
        val file = files.directory.open("w1.journal")!!
        file.write(file.length, line.copyOf(line.size - 1))
        file.force()
        assertEquals(JournalState.ROLLING_BACK, journal.latest("w1")?.state)
        // The next append ends that line first; the reading must not change under it.
        journal.append(record(JournalState.ABANDONED))
        assertEquals(JournalState.ABANDONED, Journal(files.directory).latest("w1")?.state)
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
