/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteWrite
import io.github.nikitasud.latentjam.library.tags.WritePlan
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PatchBackupTest {
    private val original = ByteArray(20) { it.toByte() }

    private fun files(bytes: ByteArray = original) = FaultFiles().apply { put("t", bytes) }

    @Test
    fun aShrinkingPatchRestoresRangesAndTheCutTail() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, byteArrayOf(50, 51))), newLength = 16)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").apply { write(2, byteArrayOf(50, 51)); setLength(16) }
        assertTrue(backup.explains(files.track("t")))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
        assertTrue(backup.matches(files.track("t")))
    }

    @Test
    fun aGrowingPatchSavesOnlyBytesThatExisted() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(18, byteArrayOf(1, 1, 1, 1))), newLength = 22)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        assertEquals(2, backup.ranges.single().bytes.size)
        files.track("t").write(18, byteArrayOf(1, 1, 1, 1))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
    }

    @Test
    fun itSurvivesEncodingAndRejectsACorruptCopy() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(0, byteArrayOf(9))), newLength = 19)
        val backup = assertNotNull(PatchBackup.capture(files().track("t"), plan))
        val bytes = backup.encode()
        val decoded = assertNotNull(PatchBackup.decode(bytes))
        assertEquals(backup.originalLength, decoded.originalLength)
        assertEquals(backup.newLength, decoded.newLength)
        assertContentEquals(backup.tail!!.bytes, decoded.tail!!.bytes)
        assertContentEquals(backup.writes.single().bytes, decoded.writes.single().bytes)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertNull(PatchBackup.decode(bytes))
    }

    @Test
    fun aTornPatchIsExplainedButAForeignChangeIsNot() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(4, byteArrayOf(70, 71, 72))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").write(4, byteArrayOf(70)) // torn after one byte
        assertTrue(backup.explains(files.track("t")))
        files.track("t").write(10, byteArrayOf(99)) // another app's edit, outside our ranges
        assertFalse(backup.explains(files.track("t")))
    }

    @Test
    fun aForeignChangeInsideAWrittenRangeIsNotExplained() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(4, byteArrayOf(70, 71, 72))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").write(5, byteArrayOf(33)) // neither the old 5 nor our 71
        assertFalse(backup.explains(files.track("t")))
    }
}
