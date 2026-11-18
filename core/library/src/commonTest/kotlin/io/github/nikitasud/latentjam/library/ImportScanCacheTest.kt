/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The stored import scan comes back whole or not at all. */
class ImportScanCacheTest {

    private val tagged = ImportScanEntry(
        relativePath = "Альбом/01 Пісня «one»\n.mp3",
        sizeBytes = 7_654_321L,
        modifiedAtMs = 1_763_000_000_123L,
        addedAtMs = 1_700_000_000_000L,
        tags = ImportedFileTags(
            title = "Пісня\tone",
            artist = "Artist feat. Other",
            album = "",
            genre = "Rock; Pop",
            durationMs = 215_000L,
            year = 1999,
            trackNumber = 1,
            discNumber = 2,
            albumArtist = "Various Artists",
            coverFile = "artwork-cbf29ce484222325-1024.img",
        ),
    )

    private val bare = ImportScanEntry(
        relativePath = "untitled.wav",
        sizeBytes = 44L,
        modifiedAtMs = 0L,
        addedAtMs = null,
        tags = ImportedFileTags(
            title = null, artist = null, album = null, genre = null, durationMs = null,
            year = null, trackNumber = null, discNumber = null, albumArtist = null, coverFile = null,
        ),
    )

    private val entries = listOf(tagged, bare, tagged.copy(relativePath = "b.mp3", sizeBytes = Long.MAX_VALUE))

    @Test
    fun everyFieldComesBackAsItWasStored() {
        val decoded = ImportScanCache.decode("42", ImportScanCache.encode("42", entries))

        assertEquals(entries, decoded)
        // An empty tag is not a missing one.
        assertEquals("", assertNotNull(decoded)[0].tags.album)
        assertNull(decoded[1].tags.album)
    }

    @Test
    fun anEmptyScanIsAWholeFileToo() {
        assertEquals(emptyList(), ImportScanCache.decode("42", ImportScanCache.encode("42", emptyList())))
    }

    @Test
    fun aFileCutAnywhereIsIgnoredWhole() {
        val bytes = ImportScanCache.encode("42", entries)

        for (length in 0 until bytes.size) {
            assertNull(ImportScanCache.decode("42", bytes.copyOf(length)), "cut to $length of ${bytes.size} bytes")
        }
    }

    @Test
    fun aFileOfAnotherBuildIsIgnored() {
        val bytes = ImportScanCache.encode("42", entries)

        assertNull(ImportScanCache.decode("43", bytes))
        assertNull(ImportScanCache.decode("", bytes))
    }

    @Test
    fun aDamagedByteAnywhereIsIgnoredWhole() {
        val bytes = ImportScanCache.encode("42", entries)

        for (index in bytes.indices) {
            val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 0x40).toByte() }
            assertNull(ImportScanCache.decode("42", damaged), "byte $index of ${bytes.size}")
        }
    }

    @Test
    fun bytesThatAreNotACacheAreIgnored() {
        assertNull(ImportScanCache.decode("42", ByteArray(0)))
        assertNull(ImportScanCache.decode("42", ByteArray(3)))
        // Four zero bytes are the checksum of nothing: a whole file of no content.
        assertNull(ImportScanCache.decode("42", ByteArray(4)))
        assertNull(ImportScanCache.decode("42", ByteArray(64)))
        assertNull(ImportScanCache.decode("42", "hidden_tracks".encodeToByteArray()))
        // Two whole files one after the other are not one.
        val bytes = ImportScanCache.encode("42", entries)
        assertNull(ImportScanCache.decode("42", bytes + bytes))
    }
}
