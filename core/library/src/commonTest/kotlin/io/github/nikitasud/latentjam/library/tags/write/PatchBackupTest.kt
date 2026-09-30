/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteWrite
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
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
    fun aChangeInsideAWrittenRangeIsExplainedAndRestored() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(4, byteArrayOf(70, 71, 72))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        // Neither the old 5 nor our 71: a write of ours that storage mangled, and ours to undo.
        files.track("t").write(5, byteArrayOf(33))
        assertTrue(backup.explains(files.track("t")))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
    }

    @Test
    fun aGrowthPatchWhoseSecondWriteStartsPastTheOldEndIsExplainedAtEveryStage() {
        val plan = WritePlan.InPlacePatch(
            listOf(ByteWrite(2, byteArrayOf(50)), ByteWrite(24, byteArrayOf(60, 61))),
            newLength = 26,
        )
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        assertTrue(backup.explains(files.track("t"))) // untouched original
        files.track("t").setLength(26) // the extension landed, its data did not
        assertTrue(backup.explains(files.track("t")))
        files.track("t").write(24, byteArrayOf(60)) // torn
        assertTrue(backup.explains(files.track("t")))
        files.track("t").apply { write(2, byteArrayOf(50)); write(24, byteArrayOf(60, 61)) }
        assertTrue(backup.explains(files.track("t")))
        backup.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
    }

    @Test
    fun aShrinkingPatchWhoseTailStartsPastAShortenedFileIsExplained() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, byteArrayOf(50))), newLength = 12)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        files.track("t").apply { write(2, byteArrayOf(50)); setLength(12) }
        assertTrue(backup.explains(files.track("t")))
    }

    private val big = ByteArray(400 * 1024) { (it * 7).toByte() }
    private val bigPlan = WritePlan.InPlacePatch(listOf(ByteWrite(10, byteArrayOf(1, 2))), newLength = big.size.toLong())

    private fun explainsAfterForeignChangeAt(offset: Int): Boolean {
        val files = files(big)
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), bigPlan))
        files.track("t").write(offset.toLong(), byteArrayOf((big[offset] + 1).toByte()))
        return backup.explains(files.track("t"))
    }

    @Test
    fun aForeignChangeInTheHeadWindowOfALargeFileIsCaught() {
        assertFalse(explainsAfterForeignChangeAt(100 * 1024))
    }

    @Test
    fun aForeignChangeInTheTailWindowOfALargeFileIsCaught() {
        assertFalse(explainsAfterForeignChangeAt(big.size - 10))
    }

    @Test
    fun aForeignChangeInTheUncheckedMiddleIsNotSeen() {
        // The accepted limit: only the first 256 KiB and last 64 KiB are checked, so an edit to the
        // audio between them goes unnoticed. Recovery then restores the tag ranges around it.
        assertTrue(explainsAfterForeignChangeAt(300 * 1024))
    }

    @Test
    fun aWriteStraddlingTheHeadWindowEdgeIsHandled() {
        val edge = 256 * 1024
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(edge - 2L, byteArrayOf(1, 2, 3, 4))), newLength = big.size.toLong())
        fun backupOn(files: FaultFiles) = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        val files = files(big)
        val backup = backupOn(files)
        assertTrue(backup.explains(files.track("t")))
        files.track("t").write(edge - 2L, byteArrayOf(1, 2, 3, 4))
        assertTrue(backup.explains(files.track("t")))
        backup.restore(files.track("t"))
        assertContentEquals(big, files.trackBytes("t"))
        files.track("t").write(edge - 3L, byteArrayOf((big[edge - 3] + 1).toByte()))
        assertFalse(backup.explains(files.track("t")))
    }

    @Test
    fun aTruncatedBackupIsRejected() {
        val backup = assertNotNull(PatchBackup.capture(files().track("t"), WritePlan.InPlacePatch(listOf(ByteWrite(0, byteArrayOf(9))), 19)))
        val bytes = backup.encode()
        for (cut in listOf(1, 3, 8, 9, bytes.size / 2, bytes.size - 1)) {
            assertNull(PatchBackup.decode(bytes.copyOf(bytes.size - cut)), "cut $cut")
        }
        assertNull(PatchBackup.decode(ByteArray(0)))
    }

    @Test
    fun aPatchThatKeepsTheLengthHasNoTail() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, byteArrayOf(50, 51))), newLength = 20)
        val files = files()
        val backup = assertNotNull(PatchBackup.capture(files.track("t"), plan))
        assertNull(backup.tail)
        val decoded = assertNotNull(PatchBackup.decode(backup.encode()))
        assertNull(decoded.tail)
        files.track("t").write(2, byteArrayOf(50, 51))
        assertTrue(decoded.explains(files.track("t")))
        decoded.restore(files.track("t"))
        assertContentEquals(original, files.trackBytes("t"))
    }

    @Test
    fun aTailFlagOtherThanZeroOrOneIsRejectedEvenWithAValidChecksum() {
        val plan = WritePlan.InPlacePatch(listOf(ByteWrite(2, byteArrayOf(50))), newLength = 20)
        val bytes = assertNotNull(PatchBackup.capture(files().track("t"), plan)).encode()
        val body = bytes.copyOf(bytes.size - 8)
        body[body.size - 1] = 2 // the tail flag is the last int of a backup without a tail
        val crc = Crc32.of(body)
        val forged = body + ByteArray(8) { (crc ushr (56 - 8 * it)).toByte() }
        assertNull(PatchBackup.decode(forged))
        body[body.size - 1] = 0
        val fine = body + ByteArray(8) { (Crc32.of(body) ushr (56 - 8 * it)).toByte() }
        assertNotNull(PatchBackup.decode(fine))
    }

    @Test
    fun aTailTooBigForOneArrayIsNotCaptured() {
        val huge = object : RandomAccessSource {
            override val length: Long = 3_000_000_000L
            override fun read(offset: Long, count: Int): ByteArray? = ByteArray(count)
        }
        assertNull(PatchBackup.capture(huge, WritePlan.InPlacePatch(emptyList(), newLength = 0)))
    }
}
