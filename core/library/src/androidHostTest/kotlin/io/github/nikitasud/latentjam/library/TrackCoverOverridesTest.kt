/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class TrackCoverOverridesTest {

    private val root: File = Files.createTempDirectory("lj-covers").toFile()
    private val directory = File(root, "track-covers")
    private val album = "content://media/external/audio/albumart/12?v=1f"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 7)

    /** What each file's tags say its cover is now; a track missing here has none. */
    private val embedded = HashMap<String, FileCover>()
    private val reads = ArrayList<String>()

    private fun overrides() = TrackCoverOverrides(directory) { track ->
        reads += track.id.value
        embedded[track.id.value] ?: FileCover.Read(null)
    }

    private fun track(id: String, revision: String = "r1", artwork: String? = album) =
        TrackDescriptor(TrackId(id), title = "song $id", artworkUri = artwork, sourceRevision = revision)

    private fun fileUri(bytes: ByteArray, extension: String) =
        "file://" + File(directory, "%08x.%s".format(Crc32.of(bytes), extension)).absolutePath

    private fun coverFiles(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { !it.startsWith(".") && it != "overrides.txt" }.sorted()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aNewCoverIsTheSongsOwnFileAndTheAlbumKeepsItsArt() {
        overrides().record(listOf(TrackId("2")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["2"] = FileCover.Read(Crc32.of(jpeg))
        val applied = overrides().apply(listOf(track("1"), track("2"), track("3")))
        assertEquals(listOf(album, fileUri(jpeg, "jpg"), album), applied.map { it.artworkUri })
        assertEquals(listOf(null, album, null), applied.map { it.albumArtworkUri })
        assertContentEquals(jpeg, File(directory, "%08x.jpg".format(Crc32.of(jpeg))).readBytes())
    }

    @Test
    fun theOverridesSurviveANewStoreOverTheSameFiles() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(png, "image/png"))
        overrides().record(listOf(TrackId("2")), CoverEdit.Remove)
        embedded["1"] = FileCover.Read(Crc32.of(png))
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertEquals(listOf(fileUri(png, "png"), null), applied.map { it.artworkUri })
        assertEquals(listOf(album, album), applied.map { it.albumArtworkUri })
    }

    @Test
    fun aRemovedCoverLeavesTheSongWithoutOne() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Remove)
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertNull(applied[0].artworkUri)
        assertEquals(album, applied[0].albumArtworkUri)
        assertEquals(album, applied[1].artworkUri)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun keepingTheCoverRecordsNothingAndOtherSongsAreUntouched() {
        val store = overrides()
        store.record(listOf(TrackId("1")), CoverEdit.Keep)
        val tracks = listOf(track("1"), track("2"))
        val applied = store.apply(tracks)
        assertSame(tracks[0], applied[0])
        assertSame(tracks[1], applied[1])
        assertEquals(emptyList(), reads)
    }

    @Test
    fun aRecordedCoverIsCheckedOnceAgainstTheFileThenTrustedAtItsRevision() {
        val store = overrides()
        store.record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        store.apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1"), reads)
        val again = overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1"), reads, "the same revision is not read again, even by a new store")
        assertEquals(fileUri(jpeg, "jpg"), again.single().artworkUri)
    }

    @Test
    fun aNewRevisionWithTheSameCoverKeepsTheOverride() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        overrides().apply(listOf(track("1", revision = "r2")))
        // A title edit elsewhere: the file changes, its cover does not.
        val applied = overrides().apply(listOf(track("1", revision = "r3")))
        assertEquals(fileUri(jpeg, "jpg"), applied.single().artworkUri)
        assertEquals(listOf("1", "1"), reads)
        overrides().apply(listOf(track("1", revision = "r3")))
        assertEquals(listOf("1", "1"), reads, "the new revision was recorded")
    }

    @Test
    fun anotherAppsNewCoverDropsTheOverrideAndItsFile() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        overrides().apply(listOf(track("1", revision = "r2")))
        embedded["1"] = FileCover.Read(Crc32.of(png))
        val applied = overrides().apply(listOf(track("1", revision = "r3")))
        assertEquals(album, applied.single().artworkUri)
        assertNull(applied.single().albumArtworkUri)
        assertEquals(emptyList(), coverFiles())
        overrides().apply(listOf(track("1", revision = "r4")))
        assertEquals(listOf("1", "1"), reads, "a dropped override is not checked again")
    }

    @Test
    fun anotherAppRemovingTheCoverDropsTheOverride() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(null)
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun aCoverAddedByAnotherAppDropsARemoval() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Remove)
        overrides().apply(listOf(track("1", revision = "r2")))
        embedded["1"] = FileCover.Read(Crc32.of(png))
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r3"))).single().artworkUri)
    }

    @Test
    fun aFileTheTagReadersNoLongerRecogniseDropsTheOverride() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Unrecognised
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun aFileThatCannotBeOpenedKeepsTheOverrideAndIsCheckedAgainNextScan() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Unreadable
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1", "1"), reads)
    }

    @Test
    fun aSongThatIsGoneDropsItsOverrideAndItsFile() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        overrides().apply(listOf(track("2")))
        assertEquals(emptyList(), coverFiles())
        // Back with the same id (a restored file): album art, as for any other song.
        assertEquals(album, overrides().apply(listOf(track("1"))).single().artworkUri)
    }

    @Test
    fun aCoverSharedBySeveralSongsStaysWhileOneStillUsesIt() {
        overrides().record(listOf(TrackId("1"), TrackId("2")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(png))
        embedded["2"] = FileCover.Read(Crc32.of(jpeg))
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertEquals(listOf(album, fileUri(jpeg, "jpg")), applied.map { it.artworkUri })
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
    }

    @Test
    fun aSecondNewCoverReplacesTheFirstAndItsFile() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(png, "image/png"))
        embedded["1"] = FileCover.Read(Crc32.of(png))
        assertEquals(fileUri(png, "png"), overrides().apply(listOf(track("1"))).single().artworkUri)
        assertEquals(listOf("%08x.png".format(Crc32.of(png))), coverFiles())
    }

    @Test
    fun aMissingCoverFileDropsTheOverride() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        File(directory, "%08x.jpg".format(Crc32.of(jpeg))).delete()
        assertEquals(album, overrides().apply(listOf(track("1"))).single().artworkUri)
    }

    @Test
    fun aSongWithoutAlbumArtGetsNoOverrideSoItsGroupingCannotChange() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        val applied = overrides().apply(listOf(track("1", artwork = null))).single()
        assertNull(applied.artworkUri)
        assertNull(applied.albumArtworkUri)
    }

    @Test
    fun aDamagedIndexIsAnEmptyOne() {
        directory.mkdirs()
        File(directory, "overrides.txt").writeText("not\tan index\n\u0000")
        val tracks = listOf(track("1"))
        assertSame(tracks.single(), overrides().apply(tracks).single())
    }

    @Test
    fun noOverridesMeansNoIndexAndNoReads() {
        overrides().apply(listOf(track("1")))
        assertFalse(File(directory, "overrides.txt").exists())
        assertTrue(reads.isEmpty())
    }
}
