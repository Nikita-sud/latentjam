/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.Id3TestTags
import io.github.nikitasud.latentjam.library.tags.TestFrame
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import scan between launches: a new [IosMusicLibrary] on the same folders is the next cold start. It opens only
 * the files that changed, and what it hands out for the others is what a fresh read gives, the track id first of all
 * (playlists and the listening history are keyed by it).
 */
@OptIn(ExperimentalForeignApi::class)
class IosImportScanCacheTest {
    private val manager = NSFileManager.defaultManager
    private val root = NSTemporaryDirectory().trimEnd('/') + "/lj-scan-cache-" + NSUUID().UUIDString
    private val documents = "$root/Documents"
    private val caches = "$root/Caches"

    init {
        assertTrue(manager.createDirectoryAtPath("$documents/Album", true, null, null))
        assertTrue(manager.createDirectoryAtPath(caches, true, null, null))
    }

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(root, null)
    }

    /** A cold start of build [build] with its cache folder at [caches]. */
    private fun launch(caches: String = this.caches, build: String = "7") = IosMusicLibrary(caches = { caches }, build = build)

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

    /** A tagged MP3 with a cover: an ID3v2.3 tag and fifty silent frames (417 bytes each at 128 kbit/s, 44.1 kHz). */
    private fun coveredMp3(): ByteArray {
        val tag = Id3TestTags.build(
            3,
            listOf(
                TestFrame("TIT2", Id3TestTags.latin1Body("Covered")),
                TestFrame("TPE1", Id3TestTags.latin1Body("Artist")),
                TestFrame("TALB", Id3TestTags.latin1Body("Album")),
                TestFrame("TRCK", Id3TestTags.latin1Body("3/9")),
                TestFrame("TYER", Id3TestTags.latin1Body("1999")),
                Id3TestTags.artFrame(2_000),
            ),
        )
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(413)
        var audio = ByteArray(0)
        repeat(50) { audio += frame }
        return tag + audio
    }

    private fun write(path: String, bytes: ByteArray) {
        assertTrue(bytes.toNSData().writeToFile(path, true), path)
    }

    private fun threeFiles() {
        write("$documents/Album/First song.wav", wave(seconds = 31))
        write("$documents/Second song.wav", wave(seconds = 32))
        write("$documents/Album/Covered.mp3", coveredMp3())
    }

    private fun List<TrackDescriptor>.titled(title: String): TrackDescriptor = single { it.title == title }

    private fun pathOf(fileUri: String?): String = assertNotNull(fileUri).removePrefix("file://")

    @Test
    fun theNextLaunchOpensNoFileAndHandsOutTheSameTracks() {
        threeFiles()
        val first = launch()
        val read = first.scan(documents)
        assertEquals(3, first.filesRead)
        assertEquals(listOf("Covered", "First song", "Second song"), read.map { it.title })

        val second = launch()
        val stored = second.scan(documents)

        assertEquals(0, second.filesRead)
        assertEquals(read.map { it.id }, stored.map { it.id })
        // Every field: TrackDescriptor compares them all.
        assertEquals(read, stored)
        val covered = stored.titled("Covered")
        assertEquals(TrackId("Album/Covered.mp3"), covered.id)
        assertEquals("Artist", covered.artist)
        assertEquals(3, covered.trackNumber)
        assertEquals(1999, covered.year)
        assertTrue(manager.fileExistsAtPath(pathOf(covered.artworkUri)), covered.artworkUri)
    }

    @Test
    fun aStoredScanGivesWhatAFreshReadGives() {
        threeFiles()
        launch().scan(documents)
        val stored = launch().scan(documents)

        // Another build does not trust the stored scan: this is every file read anew.
        val other = launch(build = "8")
        val fresh = other.scan(documents)

        assertEquals(3, other.filesRead)
        assertEquals(fresh, stored)
        assertEquals(fresh.map { it.toString() }, stored.map { it.toString() })
        // And the new build's own scan is stored in its place.
        val again = launch(build = "8")
        assertEquals(fresh, again.scan(documents))
        assertEquals(0, again.filesRead)
    }

    @Test
    fun onlyTheFilesThatChangedAreOpenedAgain() {
        threeFiles()
        val before = launch().scan(documents)

        // One grows, one keeps its size and gets another date, one is left alone.
        write("$documents/Second song.wav", wave(seconds = 40))
        val redated = NSDate.dateWithTimeIntervalSince1970(1_600_000_000.0)
        assertTrue(
            manager.setAttributes(
                mapOf<Any?, Any?>(NSFileModificationDate to redated),
                ofItemAtPath = "$documents/Album/First song.wav",
                error = null,
            ),
        )
        val second = launch()
        val after = second.scan(documents)

        assertEquals(2, second.filesRead)
        assertEquals(40_000L, after.titled("Second song").durationMs)
        assertEquals(1_600_000_000_000L, after.titled("First song").modifiedAtMs)
        assertTrue(assertNotNull(after.titled("First song").sourceRevision).endsWith(":1600000000000"))
        assertEquals(before.titled("Covered"), after.titled("Covered"))
        assertEquals(before.map { it.id }, after.map { it.id })

        // What was read is stored: the launch after that opens nothing.
        val third = launch()
        assertEquals(after, third.scan(documents))
        assertEquals(0, third.filesRead)
    }

    @Test
    fun aStoredScanCutInHalfIsIgnoredAndEveryFileIsRead() {
        threeFiles()
        val read = launch().scan(documents)
        val file = "$caches/${ImportScanCache.FILE_NAME}"
        val whole = assertNotNull(manager.contentsAtPath(file)).toByteArray()
        assertNotNull(ImportScanCache.decode("7", whole))
        write(file, whole.copyOf(whole.size / 2))

        val second = launch()
        val after = second.scan(documents)

        assertEquals(3, second.filesRead)
        assertEquals(read, after)
        // The scan left a whole file behind again.
        assertEquals(3, assertNotNull(ImportScanCache.decode("7", manager.contentsAtPath(file)!!.toByteArray())).size)
    }

    @Test
    fun aFileWhoseCoverLeftTheCacheFolderIsReadAgainAndOnlyThatOne() {
        threeFiles()
        val read = launch().scan(documents)
        val cover = pathOf(read.titled("Covered").artworkUri)
        assertTrue(cover.startsWith("$caches/artwork-"), cover)
        assertTrue(manager.removeItemAtPath(cover, null))

        val second = launch()
        val after = second.scan(documents)

        assertEquals(1, second.filesRead)
        assertEquals(read, after)
        assertTrue(manager.fileExistsAtPath(cover))
    }

    @Test
    fun aContainerThatMovedKeepsEveryIdAndPointsIntoTheNewFolders() {
        threeFiles()
        val read = launch().scan(documents)
        // An update or a restore puts the app's container under another path; the files keep their dates.
        val moved = "$root-moved"
        assertTrue(manager.moveItemAtPath(root, moved, null))
        try {
            val second = launch(caches = "$moved/Caches")
            val after = second.scan("$moved/Documents")

            assertEquals(0, second.filesRead)
            assertEquals(read.map { it.id }, after.map { it.id })
            assertEquals(
                fileUrl("$moved/Documents/Album/First song.wav").absoluteString,
                after.titled("First song").audioUri,
            )
            val cover = pathOf(after.titled("Covered").artworkUri)
            assertTrue(cover.startsWith("$moved/Caches/artwork-"), cover)
            assertTrue(manager.fileExistsAtPath(cover))
            // Nothing but the two locators differs.
            assertEquals(
                read.map { it.copy(audioUri = null, artworkUri = null) },
                after.map { it.copy(audioUri = null, artworkUri = null) },
            )
        } finally {
            manager.removeItemAtPath(moved, null)
        }
    }

    @Test
    fun aDeletedFileLeavesTheStoredScan() {
        threeFiles()
        launch().scan(documents)
        assertTrue(manager.removeItemAtPath("$documents/Second song.wav", null))

        val second = launch()
        val after = second.scan(documents)

        assertEquals(0, second.filesRead)
        assertEquals(listOf("Covered", "First song"), after.map { it.title })
        val stored = ImportScanCache.decode("7", manager.contentsAtPath("$caches/${ImportScanCache.FILE_NAME}")!!.toByteArray())
        assertEquals(setOf("Album/Covered.mp3", "Album/First song.wav"), assertNotNull(stored).map { it.relativePath }.toSet())
    }

    @Test
    fun aLibraryWithNoCacheFolderStillScans() {
        threeFiles()
        val library = IosMusicLibrary(caches = { null }, build = "7")

        val tracks = library.scan(documents)

        assertEquals(3, tracks.size)
        assertNull(tracks.titled("Covered").artworkUri)
        assertFalse(manager.fileExistsAtPath("$caches/${ImportScanCache.FILE_NAME}"))
    }
}
