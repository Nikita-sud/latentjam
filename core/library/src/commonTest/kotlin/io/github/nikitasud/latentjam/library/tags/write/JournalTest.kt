/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun aJournalWhoseCreationWasNotSyncedIsGoneAfterPowerLoss() {
        val files = FaultFiles()
        files.crashAt = 3 // create, write, force — then the directory sync never happens
        runCatching { Journal(files.directory).append(record(JournalState.PATCH_PREPARED)) }
        files.powerLoss()
        assertTrue(Journal(files.directory).open().isEmpty())
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
