/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.CoverPicture
import io.github.nikitasud.latentjam.library.tags.Crc32
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.io.IOException
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

    /** Each sibling file's own embedded picture, for pinning; a track missing here has none. */
    private val pictures = HashMap<String, CoverPicture>()
    private val pictureReads = ArrayList<String>()

    private fun recordWithSiblings(saved: List<String>, cover: CoverEdit, siblings: List<String>): Boolean =
        overrides().record(saved.map(::TrackId), cover, siblings.map(::TrackId)) { id ->
            pictureReads += id.value
            pictures[id.value]
        }

    /** Set to make reading the index fail as storage can for a moment (EIO, too many open files). */
    private var indexUnavailable = false

    /** The store's clock, which dates when a song went missing from the scans. */
    private var clock = 1_000L

    private fun overrides() = TrackCoverOverrides(
        directory,
        readCover = { track ->
            reads += track.id.value
            embedded[track.id.value] ?: FileCover.Read(null)
        },
        readIndex = { file ->
            if (indexUnavailable) throw IOException("EIO")
            file.readText()
        },
        now = { clock },
    )

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

    /** Records [cover] for [ids] and lets the first scan after the save stamp them at revision r1. */
    private fun saved(cover: CoverEdit, vararg ids: String) {
        overrides().record(ids.map(::TrackId), cover)
        overrides().apply(ids.map { track(it) })
    }

    @Test
    fun aNewCoverIsTheSongsOwnFileAndTheAlbumKeepsItsArt() {
        overrides().record(listOf(TrackId("2")), CoverEdit.Replace(jpeg, "image/jpeg"))
        val applied = overrides().apply(listOf(track("1"), track("2"), track("3")))
        assertEquals(listOf(album, fileUri(jpeg, "jpg"), album), applied.map { it.artworkUri })
        assertEquals(listOf(null, album, null), applied.map { it.albumArtworkUri })
        assertContentEquals(jpeg, File(directory, "%08x.jpg".format(Crc32.of(jpeg))).readBytes())
    }

    @Test
    fun aSongsOwnMediaStoreCoverGivesWayToTheSavedOneAndTheAlbumKeepsItsArt() {
        val own = "content://media/external/audio/media/2/albumart?album=12&v=1f"
        overrides().record(listOf(TrackId("2")), CoverEdit.Replace(jpeg, "image/jpeg"))
        val scanned = listOf(
            track("1", artwork = "content://media/external/audio/media/1/albumart?album=12&v=1f").copy(albumArtworkUri = album),
            track("2", artwork = own).copy(albumArtworkUri = album),
        )
        val applied = overrides().apply(scanned)
        assertEquals(listOf(scanned[0].artworkUri, fileUri(jpeg, "jpg")), applied.map { it.artworkUri })
        // The album's cover, never the song's own MediaStore cover, so grouping still sees one album.
        assertEquals(listOf(album, album), applied.map { it.albumArtworkUri })
    }

    @Test
    fun theOverridesSurviveANewStoreOverTheSameFiles() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(png, "image/png"))
        overrides().record(listOf(TrackId("2")), CoverEdit.Remove)
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
    fun aNewEntryIsStampedWithTheFirstScansRevisionWithoutReadingAnyFile() {
        val ids = List(500) { "$it" }
        overrides().record(ids.map(::TrackId), CoverEdit.Replace(jpeg, "image/jpeg"))
        val applied = overrides().apply(ids.map { track(it, revision = "r2") })
        assertTrue(applied.all { it.artworkUri == fileUri(jpeg, "jpg") })
        assertEquals(emptyList(), reads)
        overrides().apply(ids.map { track(it, revision = "r2") })
        assertEquals(emptyList(), reads, "the stamped revision is trusted, even by a new store")
        overrides().apply(ids.take(1).map { track(it, revision = "r3") } + ids.drop(1).map { track(it, revision = "r2") })
        assertEquals(listOf("0"), reads, "only a known revision that differs is read")
    }

    @Test
    fun aNewRevisionWithTheSameCoverKeepsTheOverride() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        // A title edit elsewhere: the file changes, its cover does not.
        val applied = overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(fileUri(jpeg, "jpg"), applied.single().artworkUri)
        assertEquals(listOf("1"), reads)
        overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1"), reads, "the new revision was recorded")
    }

    @Test
    fun anotherAppsNewCoverDropsTheOverrideAndItsFile() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        embedded["1"] = FileCover.Read(Crc32.of(png))
        val applied = overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(album, applied.single().artworkUri)
        assertNull(applied.single().albumArtworkUri)
        assertEquals(emptyList(), coverFiles())
        overrides().apply(listOf(track("1", revision = "r3")))
        assertEquals(listOf("1"), reads, "a dropped override is not checked again")
    }

    @Test
    fun anotherAppRemovingTheCoverDropsTheOverride() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        embedded["1"] = FileCover.Read(null)
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun aCoverAddedByAnotherAppDropsARemoval() {
        saved(CoverEdit.Remove, "1")
        embedded["1"] = FileCover.Read(null)
        assertNull(overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri, "still no cover")
        embedded["1"] = FileCover.Read(Crc32.of(png))
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r3"))).single().artworkUri)
    }

    @Test
    fun aFileTheTagReadersNoLongerRecogniseDropsTheOverride() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        embedded["1"] = FileCover.Unrecognised
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun aFileThatCannotBeOpenedKeepsTheOverrideAndIsCheckedAgainNextScan() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        embedded["1"] = FileCover.Unreadable
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1", "1"), reads)
    }

    @Test
    fun aSongMissingFromAScanKeepsItsOverrideAndGetsItBackWhenItReturns() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        val absent = overrides().apply(listOf(track("2")))
        assertEquals(listOf(album), absent.map { it.artworkUri })
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
        // Back (the card was mounted after all): its own cover again, trusted at the revision it was checked at.
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1"), track("2"))).single { it.id.value == "1" }.artworkUri)
        assertEquals(emptyList(), reads)
    }

    @Test
    fun anEmptyScanKeepsEveryEntryAndImage() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        saved(CoverEdit.Replace(png, "image/png"), "2")
        saved(CoverEdit.Remove, "3")
        assertEquals(emptyList(), overrides().apply(emptyList()))
        overrides().apply(emptyList())
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg)), "%08x.png".format(Crc32.of(png))), coverFiles())
        val back = overrides().apply(listOf(track("1"), track("2"), track("3")))
        assertEquals(listOf(fileUri(jpeg, "jpg"), fileUri(png, "png"), null), back.map { it.artworkUri })
        assertEquals(emptyList(), reads)
    }

    @Test
    fun aSongThatComesBackAtANewRevisionIsCheckedLikeAnyOther() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        overrides().apply(emptyList())
        embedded["1"] = FileCover.Read(Crc32.of(png))
        assertEquals(album, overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(listOf("1"), reads)
        assertEquals(emptyList(), coverFiles())
    }

    @Test
    fun pastTwoThousandAbsentEntriesTheLongestMissingArePruned() {
        val ids = List(2_002) { "$it" }
        overrides().record(ids.map(::TrackId), CoverEdit.Replace(jpeg, "image/jpeg"))
        clock = 2_000L
        // Two songs go missing first...
        overrides().apply(ids.drop(2).map { track(it) })
        clock = 3_000L
        // ...then every one: 2,002 absent, two past the cap.
        overrides().apply(emptyList())
        val back = overrides().apply(listOf(track("0"), track("1"), track("2"), track("2001")))
        assertEquals(listOf(album, album, fileUri(jpeg, "jpg"), fileUri(jpeg, "jpg")), back.map { it.artworkUri })
    }

    @Test
    fun aMissingImageDropsAnAbsentEntryToo() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        overrides().apply(emptyList())
        File(directory, "%08x.jpg".format(Crc32.of(jpeg))).delete()
        overrides().apply(emptyList())
        assertFalse(File(directory, "overrides.txt").exists())
    }

    @Test
    fun anIndexFromBeforeAbsenceDatesIsStillRead() {
        directory.mkdirs()
        val name = "%08x.jpg".format(Crc32.of(jpeg))
        File(directory, name).writeBytes(jpeg)
        File(directory, "overrides.txt").writeText("track-covers v1\n1\t$name\tr1\n")
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1"))).single().artworkUri)
        assertEquals(emptyList(), reads)
    }

    @Test
    fun aSaveThatKeptTheCoverTrustsItsEntriesAgainSoTheNextScanReadsNothing() {
        val ids = List(300) { "$it" }
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), *ids.toTypedArray())
        // A genre edit of every one of them, as the library held them at r1: each file is now at r2.
        overrides().kept(ids.map { track(it, revision = "r1") })
        val applied = overrides().apply(ids.map { track(it, revision = "r2") })
        assertTrue(applied.all { it.artworkUri == fileUri(jpeg, "jpg") })
        assertEquals(emptyList(), reads)
        overrides().apply(ids.map { track(it, revision = "r2") })
        assertEquals(emptyList(), reads, "stamped at r2")
    }

    @Test
    fun aKeptCoverDoesNotVouchForAnEntryCheckedAtAnotherRevision() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        // The library held the file at r0, not the r1 its entry was checked at: it may have been retagged since.
        overrides().kept(listOf(track("1", revision = "r0"), track("2", revision = "r1")))
        embedded["1"] = FileCover.Read(Crc32.of(jpeg))
        overrides().apply(listOf(track("1", revision = "r2")))
        assertEquals(listOf("1"), reads)
    }

    @Test
    fun recordingSaysWhetherWhatAScanShowsChanged() {
        assertTrue(overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg")))
        overrides().apply(listOf(track("1")))
        assertFalse(overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg")), "the same cover again")
        assertTrue(overrides().record(listOf(TrackId("1"), TrackId("2")), CoverEdit.Replace(jpeg, "image/jpeg")), "a new song")
        assertTrue(overrides().record(listOf(TrackId("1")), CoverEdit.Remove))
        assertFalse(overrides().record(listOf(TrackId("1")), CoverEdit.Keep))
        indexUnavailable = true
        assertFalse(overrides().record(listOf(TrackId("3")), CoverEdit.Remove), "nothing could be kept")
    }

    @Test
    fun aCoverSharedBySeveralSongsStaysWhileOneStillUsesIt() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1", "2")
        embedded["1"] = FileCover.Read(Crc32.of(png))
        embedded["2"] = FileCover.Read(Crc32.of(jpeg))
        val applied = overrides().apply(listOf(track("1", revision = "r2"), track("2", revision = "r2")))
        assertEquals(listOf(album, fileUri(jpeg, "jpg")), applied.map { it.artworkUri })
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
    }

    @Test
    fun aSecondNewCoverReplacesTheFirstAndItsFile() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(png, "image/png"))
        assertEquals(fileUri(png, "png"), overrides().apply(listOf(track("1", revision = "r2"))).single().artworkUri)
        assertEquals(listOf("%08x.png".format(Crc32.of(png))), coverFiles())
        assertEquals(emptyList(), reads, "a new save is trusted, not checked")
    }

    @Test
    fun aMissingCoverFileDropsTheOverride() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        File(directory, "%08x.jpg".format(Crc32.of(jpeg))).delete()
        assertEquals(album, overrides().apply(listOf(track("1"))).single().artworkUri)
    }

    @Test
    fun aSongWithoutAlbumArtGetsNoOverrideSoItsGroupingCannotChange() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        val applied = overrides().apply(listOf(track("1", artwork = null))).single()
        assertNull(applied.artworkUri)
        assertNull(applied.albumArtworkUri)
    }

    @Test
    fun aDamagedIndexIsAnEmptyOneAndItsImagesAreDeleted() {
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        File(directory, "overrides.txt").writeText("not\tan index\n\u0000")
        val tracks = listOf(track("1"))
        assertSame(tracks.single(), overrides().apply(tracks).single())
        assertEquals(emptyList(), coverFiles())
        assertFalse(File(directory, "overrides.txt").exists())
    }

    @Test
    fun anIndexThatCannotBeReadForAMomentKeepsEveryEntryAndImageThroughAScan() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        val before = File(directory, "overrides.txt").readText()
        indexUnavailable = true
        val tracks = listOf(track("1", revision = "r2"), track("2"))
        assertSame(tracks[0], overrides().apply(tracks)[0], "shown as the scan made it for now")
        assertEquals(before, File(directory, "overrides.txt").readText())
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
        assertEquals(emptyList(), reads)
        indexUnavailable = false
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1"))).single().artworkUri)
    }

    @Test
    fun aSaveWhileTheIndexCannotBeReadKeepsEveryOtherEntryAndAddsNothing() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        val before = File(directory, "overrides.txt").readText()
        indexUnavailable = true
        overrides().record(listOf(TrackId("2")), CoverEdit.Replace(png, "image/png"))
        indexUnavailable = false
        assertEquals(before, File(directory, "overrides.txt").readText())
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertEquals(listOf(fileUri(jpeg, "jpg"), album), applied.map { it.artworkUri })
    }

    @Test
    fun aSaveOverACorruptIndexStartsAFreshOne() {
        saved(CoverEdit.Replace(jpeg, "image/jpeg"), "1")
        File(directory, "overrides.txt").writeText("track-covers v1\n1\tnot-a-cover.jpg\t\n")
        overrides().record(listOf(TrackId("2")), CoverEdit.Replace(png, "image/png"))
        assertEquals(listOf("%08x.png".format(Crc32.of(png))), coverFiles())
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertEquals(listOf(album, fileUri(png, "png")), applied.map { it.artworkUri })
    }

    @Test
    fun temporaryFilesACrashLeftAreSweptAtTheNextWrite() {
        directory.mkdirs()
        val leftovers = listOf(File(directory, ".0a1b2c3d.jpg.tmp"), File(directory, ".overrides.txt.tmp"))
        leftovers.forEach { it.writeBytes(byteArrayOf(1)) }
        overrides().record(listOf(TrackId("1")), CoverEdit.Replace(jpeg, "image/jpeg"))
        assertTrue(leftovers.none(File::exists))
        assertEquals(listOf("%08x.jpg".format(Crc32.of(jpeg))), coverFiles())
    }

    @Test
    fun noOverridesMeansNoIndexAndNoReads() {
        overrides().apply(listOf(track("1")))
        assertFalse(File(directory, "overrides.txt").exists())
        assertTrue(reads.isEmpty())
    }

    // ---- siblings: MediaStore regenerates the album's art from an edited file (Android 11+)

    private val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 8, 9)

    @Test
    fun aCoverEditOnOneTrackPinsEverySiblingToItsOwnPicture() {
        pictures["2"] = CoverPicture(png, "image/png")
        pictures["3"] = CoverPicture(gif, "image/gif")
        assertTrue(recordWithSiblings(listOf("1"), CoverEdit.Replace(jpeg, "image/jpeg"), listOf("2", "3")))
        val applied = overrides().apply(listOf(track("1"), track("2"), track("3")))
        assertEquals(listOf(fileUri(jpeg, "jpg"), fileUri(png, "png"), fileUri(gif, "img")), applied.map { it.artworkUri })
        assertEquals(listOf(album, album, album), applied.map { it.albumArtworkUri }, "the album still groups them")
        assertEquals(listOf("2", "3"), pictureReads)
        assertEquals(emptyList(), reads, "trusted at the first scan's revision, like the edited track")
    }

    @Test
    fun aRemovedCoverPinsTheSiblingsToo() {
        pictures["2"] = CoverPicture(png, "image/png")
        recordWithSiblings(listOf("1"), CoverEdit.Remove, listOf("2"))
        val applied = overrides().apply(listOf(track("1"), track("2")))
        assertEquals(listOf(null, fileUri(png, "png")), applied.map { it.artworkUri })
    }

    @Test
    fun aSiblingWithoutAPictureKeepsTheAlbumArt() {
        pictures["2"] = CoverPicture(png, "image/png")
        recordWithSiblings(listOf("1"), CoverEdit.Replace(jpeg, "image/jpeg"), listOf("2", "3"))
        val applied = overrides().apply(listOf(track("1"), track("2"), track("3")))
        assertEquals(album, applied[2].artworkUri)
        assertNull(applied[2].albumArtworkUri)
        assertEquals(listOf("2", "3"), pictureReads)
    }

    @Test
    fun aSiblingThatAlreadyHasItsOwnCoverIsNeitherReadNorChanged() {
        saved(CoverEdit.Replace(png, "image/png"), "2")
        pictures["2"] = CoverPicture(gif, "image/gif")
        recordWithSiblings(listOf("1"), CoverEdit.Replace(jpeg, "image/jpeg"), listOf("2"))
        assertEquals(emptyList(), pictureReads)
        assertEquals(fileUri(png, "png"), overrides().apply(listOf(track("1"), track("2")))[1].artworkUri)
    }

    @Test
    fun aSavedTrackListedAsASiblingIsNotPinned() {
        pictures["1"] = CoverPicture(png, "image/png")
        recordWithSiblings(listOf("1"), CoverEdit.Replace(jpeg, "image/jpeg"), listOf("1"))
        assertEquals(emptyList(), pictureReads)
        assertEquals(fileUri(jpeg, "jpg"), overrides().apply(listOf(track("1"))).single().artworkUri)
    }

    @Test
    fun aPinnedSiblingIsReCheckedByItsPictureWhenItsFileChanges() {
        pictures["2"] = CoverPicture(png, "image/png")
        pictures["3"] = CoverPicture(gif, "image/gif")
        recordWithSiblings(listOf("1"), CoverEdit.Replace(jpeg, "image/jpeg"), listOf("2", "3"))
        overrides().apply(listOf(track("1"), track("2"), track("3")))
        embedded["2"] = FileCover.Read(Crc32.of(png))
        embedded["3"] = FileCover.Read(Crc32.of(jpeg))
        val applied = overrides().apply(listOf(track("1"), track("2", revision = "r2"), track("3", revision = "r2")))
        assertEquals(listOf(fileUri(jpeg, "jpg"), fileUri(png, "png"), album), applied.map { it.artworkUri })
        assertEquals(listOf("2", "3"), reads)
    }

    @Test
    fun anAlbumWideCoverHasNoSiblingsToPin() {
        val album = listOf(TrackId("1") to 7L, TrackId("2") to 7L, TrackId("3") to 7L)
        assertEquals(emptyList(), albumSiblings(album.map { it.first }, album))
    }

    @Test
    fun theSiblingsAreTheOtherTracksOfEachSavedTracksAlbum() {
        val rows = listOf(
            TrackId("1") to 7L, TrackId("2") to 7L, TrackId("3") to 7L,
            TrackId("4") to 8L, TrackId("5") to 8L,
            TrackId("6") to 9L,
            // No album: no album art to regenerate.
            TrackId("7") to 0L, TrackId("8") to 0L,
        )
        assertEquals(listOf("2", "3", "5"), albumSiblings(listOf(TrackId("1"), TrackId("4"), TrackId("7")), rows).map { it.value })
        assertEquals(emptyList(), albumSiblings(listOf(TrackId("6")), rows))
    }
}
