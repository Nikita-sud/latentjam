/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The walk over imported files: each file is read inside its own autorelease pool, and what it read comes out whole. */
@OptIn(ExperimentalForeignApi::class)
class IosImportedScanTest {
    private val manager = NSFileManager.defaultManager

    /** A silent 8 kHz mono WAV of [seconds]: long enough to count as music without a tag. */
    private fun wave(seconds: Int): ByteArray {
        val rate = 8000
        val bytes = ByteArray(44 + rate * 2 * seconds)
        fun word(offset: Int, value: Int, count: Int = 4) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        fun text(offset: Int, value: String) { value.encodeToByteArray().copyInto(bytes, offset) }
        text(0, "RIFF"); word(4, bytes.size - 8); text(8, "WAVE")
        text(12, "fmt "); word(16, 16); word(20, 1, 2); word(22, 1, 2)
        word(24, rate); word(28, rate * 2); word(32, 2, 2); word(34, 16, 2)
        text(36, "data"); word(40, bytes.size - 44)
        return bytes
    }

    private fun withDocuments(block: (String) -> Unit) {
        val root = NSTemporaryDirectory() + "latentjam-scan-test-${NSUUID().UUIDString}"
        assertTrue(manager.createDirectoryAtPath("$root/Album", true, null, null))
        try { block(root) } finally { manager.removeItemAtPath(root, null) }
    }

    private fun write(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        assertTrue(manager.createFileAtPath(path, data, null))
    }

    @Test
    fun anUntaggedFileIsReadWithItsNameLengthAndPlace() = withDocuments { root ->
        val bytes = wave(seconds = 31)
        write("$root/Album/First song.wav", bytes)
        write("$root/Second song.wav", bytes)

        val tracks = IosMusicLibrary().scan(root)

        assertEquals(listOf("First song", "Second song"), tracks.map { it.title })
        val first = tracks.first()
        assertEquals(TrackId("Album/First song.wav"), first.id)
        assertEquals("Album", first.folderPath)
        assertEquals("First song.wav", first.fileName)
        assertEquals(bytes.size.toLong(), first.sizeBytes)
        assertEquals(31_000L, assertNotNull(first.durationMs))
        assertTrue(assertNotNull(first.audioUri).endsWith("/Album/First%20song.wav"), first.audioUri)
        assertTrue(assertNotNull(first.sourceRevision).startsWith("ios-import-v1:${bytes.size}:"), first.sourceRevision)
        assertNull(first.artist)
        assertNull(first.artworkUri)
        assertNull(tracks.last().folderPath)
    }

    @Test
    fun anUnchangedFileIsNotReadAgain() = withDocuments { root ->
        write("$root/Album/First song.wav", wave(seconds = 31))
        val library = IosMusicLibrary()

        val first = library.scan(root).single()

        assertSame(first, library.scan(root).single())
    }
}
