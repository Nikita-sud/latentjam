/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class JvmWriteFilesTest {
    private val root: File = Files.createTempDirectory("lj-write").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aChannelFileReadsWritesGrowsAndShrinks() {
        val file = File(root, "t").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        ChannelTargetFile.open(file).use {
            it.write(2, byteArrayOf(9, 9, 9))
            assertEquals(5, it.length)
            it.setLength(3)
            it.setLength(6)
            it.force()
            assertContentEquals(byteArrayOf(1, 2, 9, 0, 0, 0), it.read(0, 6))
            assertNull(it.read(4, 3))
        }
    }

    @Test
    fun theDirectoryCreatesListsAndDeletes() {
        var syncs = 0
        val directory = FileRecoveryDirectory(File(root, "store")) { syncs++ }
        directory.create("a.patch").use { it.write(0, byteArrayOf(1)) }
        directory.create("a.patch").use { assertEquals(0, it.length) }
        assertEquals(listOf("a.patch"), directory.names())
        directory.sync()
        directory.delete("a.patch")
        assertTrue(directory.names().isEmpty())
        assertNull(directory.open("a.patch"))
        assertEquals(1, syncs)
        assertTrue(directory.freeBytes() > 0)
    }

    @Test
    fun aRealFileIsSavedDurablyInPlaceAndByRewrite() {
        val store = FileRecoveryDirectory(File(root, "store")) {}
        var n = 0
        val writer = DurableWriter(store, { "w${++n}" })
        for (case in WriteFixtures.inPlace + WriteFixtures.rewrites) {
            val file = File(root, "track").apply { writeBytes(case.original) }
            val result = ChannelTargetFile.open(file).use { writer.write(file.path, it, case.edits, root.usableSpace) }
            assertTrue(result is WriteResult.Saved, "${case.name}: $result")
            assertContentEquals(WriteFixtures.expected(case), file.readBytes(), case.name)
            assertTrue(store.names().isEmpty(), case.name)
        }
    }

    @Test
    fun theStoreRefusesNamesThatEscapeOrHide() {
        val directory = FileRecoveryDirectory(File(root, "store")) {}
        assertFailsWith<IllegalArgumentException> { directory.create("../x") }
        assertFailsWith<IllegalArgumentException> { directory.delete(".") }
        assertFailsWith<IllegalArgumentException> { directory.delete("..") }
        assertFailsWith<IllegalArgumentException> { directory.open(".hidden") }
        directory.create("a-1_b.patch").close()
        assertEquals(listOf("a-1_b.patch"), directory.names())
    }

    @Test
    fun noSpaceIsRecognisedInEveryMessageTheOsGives() {
        for (message in listOf(
            "No space left on device",
            "pwrite failed: ENOSPC (No space left on device)",
            "write failed: EDQUOT (Disk quota exceeded)",
            "Disk quota exceeded",
        )) {
            assertTrue(ChannelTargetFile.isNoSpace(IOException(message)), message)
        }
        assertFalse(ChannelTargetFile.isNoSpace(IOException("Input/output error")))
        assertFalse(ChannelTargetFile.isNoSpace(IOException()))
    }

    @Test
    fun aFileOverOneDescriptorWithSeparateReadAndWriteChannelsWorksAsOnAndroid() {
        val file = File(root, "t").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        // The Android shape: one descriptor, a read channel from an input stream and a write channel from an output stream.
        val access = RandomAccessFile(file, "rw")
        val reader = FileInputStream(access.fd).channel
        val writer = FileOutputStream(access.fd).channel
        var closes = 0
        val target = ChannelTargetFile(reader, writer) {
            closes++
            reader.close()
            writer.close()
            access.close()
        }
        target.use {
            it.write(2, byteArrayOf(9, 9, 9))
            assertEquals(5, it.length)
            assertContentEquals(byteArrayOf(1, 2, 9, 9, 9), it.read(0, 5))
            it.setLength(3)
            it.setLength(6)
            it.force()
            assertContentEquals(byteArrayOf(1, 2, 9, 0, 0, 0), it.read(0, 6))
            assertNull(it.read(4, 3))
        }
        target.close()
        assertEquals(1, closes)
        assertFalse(reader.isOpen)
        assertFalse(writer.isOpen)
        assertContentEquals(byteArrayOf(1, 2, 9, 0, 0, 0), file.readBytes())
    }
}
