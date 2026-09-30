/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class FaultFilesTest {
    @Test
    fun unforcedWritesAreLostAndForcedOnesStay() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2, 3))
        files.track("t").apply { write(0, byteArrayOf(9)); force(); write(1, byteArrayOf(8)) }
        files.powerLoss()
        assertContentEquals(byteArrayOf(9, 2, 3), files.trackBytes("t"))
    }

    @Test
    fun aTornWriteKeepsExactlyItsFirstBytes() {
        val files = FaultFiles()
        files.put("t", ByteArray(4))
        files.track("t").write(0, byteArrayOf(1, 2, 3, 4))
        files.powerLoss(keep = 2)
        assertContentEquals(byteArrayOf(1, 2, 0, 0), files.trackBytes("t"))
    }

    @Test
    fun unsyncedDirectoryEntriesVanish() {
        val files = FaultFiles()
        files.directory.create("a").apply { write(0, byteArrayOf(1)); force() }
        files.powerLoss()
        assertNull(files.directory.open("a"))
        files.directory.create("b").apply { write(0, byteArrayOf(1)); force() }
        files.directory.sync()
        files.directory.delete("b")
        files.powerLoss()
        assertContentEquals(byteArrayOf(1), files.directory.open("b")!!.read(0, 1))
    }

    @Test
    fun theNthOperationLosesPower() {
        val files = FaultFiles()
        files.put("t", ByteArray(2))
        files.crashAt = 2
        val file = files.track("t")
        file.write(0, byteArrayOf(1))
        assertFailsWith<PowerLoss> { file.force() }
        assertEquals(2, files.operations)
    }

    @Test
    fun theStoreRefusesToGrowPastItsCapacity() {
        val files = FaultFiles(storeCapacity = 3)
        val file = files.directory.create("a")
        file.write(0, byteArrayOf(1, 2, 3))
        assertFailsWith<StorageFullException> { file.write(3, byteArrayOf(4)) }
    }
}
