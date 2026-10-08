/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class AudioImportFilesTest {
    private val manager = NSFileManager.defaultManager

    private fun withFiles(block: (String, String) -> Unit) {
        val root = NSTemporaryDirectory() + "latentjam-import-test-${NSUUID().UUIDString}"
        val source = "$root/source"
        val target = "$root/imported"
        assertTrue(manager.createDirectoryAtPath(source, true, null, null))
        assertTrue(manager.createDirectoryAtPath(target, true, null, null))
        try { block(source, target) } finally { manager.removeItemAtPath(root, null) }
    }

    private fun write(path: String, bytes: ByteArray) {
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong())
        }
        assertTrue(manager.createFileAtPath(path, data, null))
    }

    private fun read(path: String): ByteArray {
        val data = checkNotNull(manager.contentsAtPath(path))
        return ByteArray(data.length.toInt()).also { bytes ->
            if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        }
    }

    private fun importFile(source: String, target: String) = importOne(
        manager, NSURL.fileURLWithPath(target, isDirectory = true), NSURL.fileURLWithPath("$source/song.mp3"),
    )

    @Test
    fun shorterMatchingFileKeepsItsBytesAndTheLongerFileGetsANewName() = withFiles { source, target ->
        // MP3 can end at any complete frame. Equal prefixes do not distinguish a trimmed song
        // from an interrupted copy; the importer must preserve either existing file.
        val full = ByteArray(4096) { (it % 251).toByte() }
        val short = full.copyOf(1024)
        write("$source/song.mp3", full)
        write("$target/song.mp3", short)
        assertEquals(ImportOutcome.IMPORTED, importFile(source, target))
        assertContentEquals(short, read("$target/song.mp3"))
        assertContentEquals(full, read("$target/song (2).mp3"))
        assertEquals(ImportOutcome.ALREADY_PRESENT, importFile(source, target))
    }

    @Test
    fun anEmptyExistingFileIsNotClaimedAsAnInterruptedImport() = withFiles { source, target ->
        write("$source/song.mp3", byteArrayOf(1, 2, 3))
        write("$target/song.mp3", byteArrayOf())
        assertEquals(ImportOutcome.IMPORTED, importFile(source, target))
        assertContentEquals(byteArrayOf(), read("$target/song.mp3"))
        assertContentEquals(byteArrayOf(1, 2, 3), read("$target/song (2).mp3"))
    }

    @Test
    fun anExactDuplicateIsSkippedWithoutCreatingAnotherFile() = withFiles { source, target ->
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        write("$source/song.mp3", bytes)
        write("$target/song.mp3", bytes)
        assertEquals(ImportOutcome.ALREADY_PRESENT, importFile(source, target))
        assertEquals(listOf("song.mp3"), manager.contentsOfDirectoryAtPath(target, null))
    }

    @Test
    fun filesOfTheSameSizeWithDifferentBytesStaySeparate() = withFiles { source, target ->
        val old = ByteArray(150_000) { 1 }
        val new = old.copyOf().apply { this[lastIndex] = 2 }
        write("$source/song.mp3", new)
        write("$target/song.mp3", old)
        assertEquals(ImportOutcome.IMPORTED, importFile(source, target))
        assertContentEquals(old, read("$target/song.mp3"))
        assertContentEquals(new, read("$target/song (2).mp3"))
    }

    @Test
    fun aTemporaryFileFromAnotherImportIsPreserved() = withFiles { source, target ->
        write("$source/song.mp3", byteArrayOf(1, 2, 3))
        write("$target/song.mp3.partial", byteArrayOf(4, 5))
        assertEquals(ImportOutcome.IMPORTED, importFile(source, target))
        assertContentEquals(byteArrayOf(4, 5), read("$target/song.mp3.partial"))
        assertEquals(2, manager.contentsOfDirectoryAtPath(target, null)?.size)
    }

    @Test
    fun anUnreadableSourceLeavesExistingFilesAlone() = withFiles { source, target ->
        write("$target/song.mp3", byteArrayOf(1, 2))
        assertEquals(ImportOutcome.FAILED, importFile(source, target))
        assertContentEquals(byteArrayOf(1, 2), read("$target/song.mp3"))
        assertEquals(1, manager.contentsOfDirectoryAtPath(target, null)?.size)
    }
}
