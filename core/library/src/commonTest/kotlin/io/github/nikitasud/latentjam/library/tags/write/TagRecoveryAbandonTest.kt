/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertEquals

internal class TagRecoveryAbandonTest {

    @Test
    fun anAbandonedSaveLeavesNoOpenRecordAndNoFiles() {
        val files = FaultFiles()
        val record = JournalRecord("w1", "gone.mp3", JournalState.PATCH_PREPARED, originalLength = 10, finalLength = 10)
        files.directory.create(record.patchName).use { it.write(0, ByteArray(4)) }
        Journal(files.directory).append(record)
        val other = record.copy(writeId = "w2", target = "kept.mp3")
        Journal(files.directory).append(other)
        val recovery = TagRecovery(files.directory)

        recovery.abandon(record)

        assertEquals(listOf(other), recovery.pending())
        assertEquals(setOf(other.journalName), files.storeNames())
    }
}
