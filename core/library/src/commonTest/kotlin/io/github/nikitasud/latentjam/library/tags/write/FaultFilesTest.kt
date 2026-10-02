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
import kotlin.test.assertTrue

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

    @Test
    fun keepingEveryPendingByteLandsATrailingLengthChange() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2, 3, 4))
        files.track("t").apply { write(0, byteArrayOf(9)); setLength(2) }
        files.powerLoss(keep = files.pendingBytes())
        assertContentEquals(byteArrayOf(9, 2), files.trackBytes("t"))
    }

    @Test
    fun aLengthChangeAfterATornWriteDoesNotLand() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2, 3, 4))
        files.track("t").apply { write(0, byteArrayOf(8, 9)); setLength(2) }
        files.powerLoss(keep = 1)
        assertContentEquals(byteArrayOf(8, 2, 3, 4), files.trackBytes("t"))
    }

    @Test
    fun aBareLengthChangeCanLand() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2, 3, 4))
        files.track("t").setLength(2)
        files.powerLoss(keep = files.pendingBytes())
        assertContentEquals(byteArrayOf(1, 2), files.trackBytes("t"))
    }

    @Test
    fun aSubsetKeepsALaterWriteWithoutTheEarlierOne() {
        val files = FaultFiles()
        files.put("t", ByteArray(4))
        files.track("t").apply { write(0, byteArrayOf(1)); write(2, byteArrayOf(7)) }
        files.powerLoss { name, index -> name == "t" && index == 1 }
        assertContentEquals(byteArrayOf(0, 0, 7, 0), files.trackBytes("t"))
    }

    @Test
    fun aLandedExtensionWithoutItsDataReadsAsZeros() {
        val files = FaultFiles()
        files.put("t", byteArrayOf(1, 2))
        files.track("t").apply { setLength(4); write(2, byteArrayOf(7, 8)) }
        files.powerLoss { _, index -> index == 0 }
        assertContentEquals(byteArrayOf(1, 2, 0, 0), files.trackBytes("t"))
    }

    @Test
    fun aSubsetSelectsPerStoreFileToo() {
        val files = FaultFiles()
        files.directory.create("a").apply { write(0, byteArrayOf(1)); force() }
        files.directory.create("b").apply { write(0, byteArrayOf(2)); force() }
        files.directory.sync()
        files.directory.open("a")!!.write(0, byteArrayOf(5))
        files.directory.open("b")!!.write(0, byteArrayOf(6))
        files.powerLoss { name, _ -> name == "b" }
        assertContentEquals(byteArrayOf(1), files.directory.open("a")!!.read(0, 1))
        assertContentEquals(byteArrayOf(6), files.directory.open("b")!!.read(0, 1))
    }

    private fun scenario(seed: Long): List<Byte> {
        val files = FaultFiles()
        files.put("t", ByteArray(8))
        files.track("t").apply {
            write(0, byteArrayOf(1, 2, 3)); write(4, byteArrayOf(4, 5)); setLength(12); write(8, byteArrayOf(6, 7, 8, 9))
        }
        files.powerLossSubset(seed)
        return files.trackBytes("t").toList()
    }

    @Test
    fun aSeededSubsetIsRepeatableAndVaried() {
        assertEquals(scenario(3), scenario(3))
        val outcomes = (0L until 60).map { scenario(it) }.toSet()
        assertTrue(outcomes.size > 5, "only ${outcomes.size} outcomes")
    }

    @Test
    fun everySeededOutcomeHoldsOnlyOldOrNewBytes() {
        for (seed in 0L until 200) {
            val bytes = scenario(seed)
            val allowed = listOf(setOf<Byte>(0, 1), setOf<Byte>(0, 2), setOf<Byte>(0, 3))
            for (i in 0 until 3) assertTrue(bytes[i] in allowed[i], "seed $seed byte $i = ${bytes[i]}")
        }
    }

    @Test
    fun aPowerLossClearsWhatWasPending() {
        val files = FaultFiles()
        files.put("t", ByteArray(2))
        files.track("t").write(0, byteArrayOf(1))
        files.powerLossSubset(1)
        assertEquals(0, files.pendingBytes())
    }
}
