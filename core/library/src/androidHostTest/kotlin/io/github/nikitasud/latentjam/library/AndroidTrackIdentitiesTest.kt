/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.Mp4Fixtures
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.TrackIdentityTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class AndroidTrackIdentitiesTest {
    private val tags = TrackIdentityTags("7058f71b-a82e-32b4-99fd-15a408ecb732", "b1a9c0e9-d987-4042-ae91-78d6a3267d69")
    private fun track(id: String, revision: String = "v1") = TrackDescriptor(
        id = TrackId(id), title = "Song", artist = "Old artist", album = "Album", durationMs = 180_000,
        audioUri = "content://media/external/audio/media/$id", sourceRevision = revision,
        storageVolume = "external_primary", fileName = "song.m4a", folderPath = "Music/Old artist/",
    )
    private class Disk {
        var payload: String? = null
        var fail = false
        var writes = 0
        fun save(value: String) { if (fail) throw IOException("full"); payload = value; writes++ }
    }
    private class Fixture(val tags: TrackIdentityTags) {
        val disk = Disk()
        var reads = 0
        var reader: (TrackDescriptor) -> TrackIdentityTags? = { tags }
        /** The list of rows not yet checked under the provider's database, as its file holds it: here none are left. */
        var unchecked: String? = UNCHECKED_NONE
        var uncheckedFails = false
        fun fresh() = AndroidTrackIdentities(
            { disk.payload?.let(::StringReader) },
            { contents -> disk.save(StringWriter().also(contents).toString()) },
            readUnchecked = { unchecked },
            writeUnchecked = { if (uncheckedFails) throw IOException("full"); unchecked = it },
        ) { reads++; reader(it) }
        var registry = fresh()
        var rowIds = RowIds.UNVERSIONED
        fun scan(vararg tracks: TrackDescriptor, present: Set<String>? = tracks.map { it.id.value }.toSet(), storage: Boolean = true) =
            registry.resolve(tracks.toList(), present, storage, rowIds)
        fun remember() = registry.remember()
        fun restart() { registry = fresh() }
    }

    @Test fun aRowTheMediaStoreDeliversTwiceIsOneTrack() {
        // A query refilled while the system scanner inserts rows can hand one row over twice: the scan goes on with
        // one copy instead of failing whole.
        val fixture = Fixture(tags)
        val resolved = fixture.scan(track("1"), track("2"), track("1"), present = setOf("1", "2"))
        assertEquals(listOf("1", "2"), resolved.map { it.id.value })
    }

    @Test fun renameAndRetagKeepAllStoredReferencesButUseNewPlaybackAndAnalysisIdentity() {
        val f = Fixture(tags)
        val original = track("10")
        assertEquals(original, f.scan(original).single())
        f.remember()
        f.restart()
        val renamed = track("20", "retagged").copy(artist = "New artist", folderPath = "Music/New artist/", fileName = "renamed.m4a")
        val restored = f.scan(renamed).single()
        assertEquals(original.id, restored.id)
        assertEquals(renamed.audioUri, restored.audioUri)
        assertEquals(renamed.sourceRevision, restored.sourceRevision)
        assertEquals(renamed.artist, restored.artist)
        // Existing playlist/favorite/history keys need no destructive rewrite.
        assertEquals(restored, mapOf(restored.id to restored)[original.id])
        assertEquals(TrackId("10"), f.registry.logicalId("20"))
        assertEquals("20", f.registry.sourceId(TrackId("10")))
        f.restart()
        assertEquals(restored, f.scan(renamed).single())
        assertEquals(TrackId("10"), f.scan(track("30", "again")).single().id)
    }

    @Test fun warmScansDoNotReadTagsOrWriteTheRegistryAgain() {
        val f = Fixture(tags)
        val tracks = (1..500).map { track(it.toString()) }.toTypedArray()
        f.scan(*tracks)
        assertEquals(0, f.reads)
        f.remember()
        assertEquals(500, f.reads)
        assertEquals(2, f.disk.writes, "one baseline and one background write, never one fsync per song")
        f.scan(*tracks); f.remember()
        assertEquals(500, f.reads)
        assertEquals(2, f.disk.writes)
        assertFalse(f.registry.needsRelocationProbe(tracks.toList()))
    }

    @Test fun unchangedMediaStoreIdSurvivesTagChangesWithoutChangingOtherTracks() {
        val f = Fixture(tags)
        f.scan(track("10"), track("11")); f.remember()
        f.reader = { TrackIdentityTags() }
        val updated = track("10", "v2").copy(title = "New title")
        assertEquals(listOf(updated, track("11")), f.scan(updated, track("11")))
    }

    @Test fun absentBaselineAndRecordingOnlyTagsNeverRelink() {
        for (facts in listOf(TrackIdentityTags(), TrackIdentityTags(recording = tags.recording))) {
            val f = Fixture(facts)
            f.scan(track("10")); f.remember()
            assertEquals(TrackId("20"), f.scan(track("20")).single().id)
        }
        val f = Fixture(tags)
        f.scan(track("10")) // moved before the background evidence pass
        assertEquals(TrackId("20"), f.scan(track("20")).single().id)
    }

    @Test fun currentDuplicatesAndHistoricalDuplicatesNeverChooseTheFirstCandidate() {
        val current = Fixture(tags)
        current.scan(track("10")); current.remember()
        assertEquals(listOf(TrackId("20"), TrackId("21")), current.scan(track("20"), track("21")).map { it.id })
        val old = Fixture(tags)
        old.scan(track("10"), track("11")); old.remember()
        assertEquals(TrackId("20"), old.scan(track("20")).single().id)
    }

    @Test fun existingCopyRetainsItsOwnIdentityEvenWhenAnotherCopyDisappears() {
        val f = Fixture(tags)
        f.scan(track("10"), track("20")); f.remember()
        assertEquals(TrackId("20"), f.scan(track("20")).single().id)
    }

    @Test fun albumAndSingleAndIncompatibleFilesNeverMerge() {
        val alternatives = listOf(
            track("20").copy(durationMs = 220_000), track("20").copy(durationMs = null),
            track("20").copy(fileName = "song.mp3"), track("20").copy(storageVolume = "1234-abcd"),
        )
        for (candidate in alternatives) {
            val f = Fixture(tags)
            f.scan(track("10")); f.remember()
            assertEquals(candidate.id, f.scan(candidate).single().id)
        }
        for (newTags in listOf(tags.copy(releaseTrack = "11111111-1111-4111-8111-111111111111"),
            tags.copy(recording = "11111111-1111-4111-8111-111111111111"))) {
            val f = Fixture(tags)
            f.scan(track("10")); f.remember(); f.reader = { newTags }
            assertEquals(TrackId("20"), f.scan(track("20")).single().id)
        }
    }

    @Test fun missingStoragePartialProviderAndStillPresentHiddenRowsVetoRelocation() {
        for (scenario in 0..2) {
            val f = Fixture(tags)
            f.scan(track("10")); f.remember()
            val result = when (scenario) {
                0 -> f.scan(track("20"), storage = false)
                1 -> f.scan(track("20"), present = null)
                else -> f.scan(track("20"), present = setOf("10", "20"))
            }
            assertEquals(TrackId("20"), result.single().id)
        }
    }

    @Test fun unreadablePotentialDuplicateVetoesAMatchAndDoesNotCrashScanning() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        f.reader = { if (it.id.value == "21") throw IOException("unreadable") else tags }
        assertEquals(listOf(TrackId("20"), TrackId("21")), f.scan(track("20"), track("21")).map { it.id })
    }

    @Test fun anUnreadableFileOfAnotherLengthDoesNotStopTheMovedTracksOfItsVolume() {
        // Issue #17: one file whose tags cannot be read (a fragmented download, a damaged file) stopped every
        // relocation on its volume, at every scan, for as long as it stayed in the library. It can only be a second
        // copy of the moved track if it is about as long.
        val f = Fixture(tags)
        val video = track("5").copy(durationMs = 303_000, fileName = "video.mp3")
        f.reader = { if (it.id.value == "5") null else tags }
        f.scan(track("10"), video); f.remember()
        f.restart()
        assertEquals(listOf(TrackId("10"), TrackId("5")), f.scan(track("20"), video).map { it.id })
    }

    @Test fun aFragmentedDownloadInTheLibraryDoesNotStopATaggedFileFromBeingFollowed() {
        // The same with the reader the app uses, on the bytes of two files: an M4A as Picard tags it, and a
        // fragmented one (as video sites serve them), which the reader does not take for complete.
        val tagged = Mp4Fixtures.file(listOf(
            Mp4Fixtures.text("©nam", "Song"),
            Mp4Fixtures.freeform("MusicBrainz Release Track Id", tags.releaseTrack!!),
            Mp4Fixtures.freeform("MusicBrainz Track Id", tags.recording!!),
        ))
        val fragmented = tagged + Mp4Fixtures.leaf("moof", ByteArray(8))
        assertEquals(tags, identityTagsOf(ByteArraySource(tagged)))
        assertEquals(null, identityTagsOf(ByteArraySource(fragmented)))
        val f = Fixture(tags)
        f.reader = { identityTagsOf(ByteArraySource(if (it.id.value == "5") fragmented else tagged)) }
        val video = track("5").copy(durationMs = 303_000)
        f.scan(track("10"), video); f.remember()
        f.restart()
        assertEquals(listOf(TrackId("10"), TrackId("5")), f.scan(track("20"), video).map { it.id })
    }

    @Test fun anUnreadableFileVetoesAMatchOnlyWhenItCouldBeACopyOfThatTrack() {
        // By its length alone: within a second of the moved track (what the match itself asks of the new file), or
        // of no known length. Any format: a readable copy in another format vetoes too.
        val couldBeACopy = listOf(
            track("5").copy(durationMs = 180_900), track("5").copy(durationMs = 179_100),
            track("5").copy(durationMs = null), track("5").copy(durationMs = 0),
            track("5").copy(durationMs = 180_000, fileName = "copy.mp3"),
        )
        val cannotBe = listOf(track("5").copy(durationMs = 181_100), track("5").copy(durationMs = 178_900))
        for ((unread, expected) in couldBeACopy.map { it to "20" } + cannotBe.map { it to "10" }) {
            val f = Fixture(tags)
            f.reader = { if (it.id.value == "5") null else tags }
            f.scan(track("10"), unread); f.remember()
            assertEquals(TrackId(expected), f.scan(track("20"), unread).first().id, "unread file of ${unread.durationMs} ms")
        }
    }

    @Test fun anUnreadableFileOnAnotherVolumeDoesNotVetoAsBefore() {
        val f = Fixture(tags)
        val card = track("5").copy(storageVolume = "1234-abcd")
        f.reader = { if (it.id.value == "5") null else tags }
        f.scan(track("10"), card); f.remember()
        assertEquals(TrackId("10"), f.scan(track("20"), card).first().id)
    }

    @Test fun aFileWhoseTagsDoNotFitInMemoryDoesNotEndThePassAndIsNotReadAtEveryLaunch() {
        val f = Fixture(tags)
        val tracks = (1..5).map { track(it.toString()) }.toTypedArray()
        f.scan(*tracks)
        f.reader = { if (it.id.value == "3") throw OutOfMemoryError("a cover larger than the heap") else tags }
        f.remember()
        assertEquals(5, f.reads)
        // Recorded as read, with no tags: the next launch does not open it again to fail the same way.
        f.restart(); f.scan(*tracks); f.remember()
        assertEquals(5, f.reads)
        // A file that only failed to open is still tried again.
        val more = tracks + track("6")
        f.reader = { if (it.id.value == "6") throw IOException("unreadable") else tags }
        f.scan(*more); f.remember()
        assertEquals(6, f.reads)
        f.scan(*more); f.remember()
        assertEquals(7, f.reads)
    }

    /** A read of a file whose reader asks for [asked] bytes of tag and then has no memory left. */
    private fun outOfMemoryAfter(asked: Int): TrackIdentityTags? =
        identityTagsOf(file) { source ->
            source.read(0, 10)
            source.read(0, asked)
            // Past the end of the file: refused, and nothing is allocated for it.
            source.read(file.length - 5, 4 * 1024 * 1024)
            throw OutOfMemoryError("no room")
        }

    /** A 16 MiB file; no test here looks at its bytes. */
    private val file = object : RandomAccessSource {
        override val length = 16L * 1024 * 1024
        override fun read(offset: Long, count: Int): ByteArray? = if (count <= length - offset) ByteArray(0) else null
    }

    @Test fun aReadThatRanOutOfMemoryOnAnOrdinaryTagIsTriedAgainAtTheNextLaunch() {
        // The heap was full of something else (the analysis, on a small heap) while a file with ordinary tags was
        // read: recorded as "read, no tags", it could never again be relocated by its MusicBrainz id.
        val f = Fixture(tags)
        val tracks = (1..5).map { track(it.toString()) }.toTypedArray()
        f.scan(*tracks)
        f.reader = { if (it.id.value == "3") outOfMemoryAfter(asked = 40 * 1024) else tags }
        f.remember()
        assertEquals(5, f.reads)
        f.restart(); f.scan(*tracks)
        f.reader = { tags }
        f.remember()
        assertEquals(6, f.reads, "the file that could not be read for want of memory is read now")
        f.restart(); f.scan(*tracks); f.remember()
        assertEquals(6, f.reads)
        // A tag that is itself large fails the same way each time, and is still recorded once.
        val g = Fixture(tags)
        g.scan(*tracks)
        g.reader = { if (it.id.value == "3") outOfMemoryAfter(asked = 3 * 1024 * 1024) else tags }
        g.remember()
        g.restart(); g.scan(*tracks); g.remember()
        assertEquals(5, g.reads)
    }

    @Test fun onlyAFailureByMemoryOnASmallReadIsLeftForLater() {
        assertEquals(null, outOfMemoryAfter(asked = 0))
        assertEquals(null, outOfMemoryAfter(asked = 1024 * 1024 - 11))
        assertFailsWith<OutOfMemoryError> { outOfMemoryAfter(asked = 1024 * 1024) }
        // Any other Error is the file's own (a tag nested without end), at whatever size.
        assertFailsWith<StackOverflowError> {
            identityTagsOf(file) { throw StackOverflowError() }
        }
        assertEquals(tags, identityTagsOf(file) { tags })
    }

    @Test fun aPotentialDuplicateTooLargeToReadVetoesAMatchLikeAnyUnreadFile() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        f.reader = { if (it.id.value == "21") throw OutOfMemoryError("a cover larger than the heap") else tags }
        assertEquals(listOf(TrackId("20"), TrackId("21")), f.scan(track("20"), track("21")).map { it.id })
    }

    @Test fun aFailedTagPassWriteChangesNothingAndTheNextPassRetries() {
        val f = Fixture(tags)
        val tracks = (1..20).map { track(it.toString()) }.toTypedArray()
        f.scan(*tracks)
        val baseline = f.disk.payload
        f.disk.fail = true
        assertFailsWith<IOException> { f.remember() }
        assertEquals(baseline, f.disk.payload)
        f.restart()
        f.disk.fail = false
        f.scan(*tracks)
        val readsBefore = f.reads
        f.remember()
        assertEquals(readsBefore + 20, f.reads, "nothing was recorded as read")
        val written = f.disk.payload
        f.restart(); f.scan(*tracks); f.remember()
        assertEquals(written, f.disk.payload)
        assertEquals(readsBefore + 20, f.reads)
    }

    @Test fun cancellationAndFailedCommitDoNotPublishOrPersistARelocation() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        val baseline = f.disk.payload
        assertFailsWith<CancellationException> {
            f.registry.resolve(listOf(track("20")), setOf("20"), true) { throw CancellationException("stop") }
        }
        f.disk.fail = true
        assertFailsWith<IOException> { f.scan(track("20")) }
        assertEquals(baseline, f.disk.payload)
        assertEquals("10", f.registry.sourceId(TrackId("10")))
        f.disk.fail = false
        assertEquals(TrackId("10"), f.scan(track("20")).single().id)
    }

    @Test fun reusedSourceNumberCannotStealTheRelocatedTracksHistory() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        f.scan(track("20"))
        f.reader = { if (it.id.value == "10") TrackIdentityTags() else tags }
        val result = f.scan(track("20"), track("10", "other-file"))
        assertEquals(TrackId("10"), result[0].id)
        assertTrue(result[1].id != result[0].id)
        assertEquals("10", f.registry.sourceId(result[1].id))
        f.restart()
        assertEquals(result, f.scan(track("20"), track("10", "other-file")))
    }

    /** A MediaStore row as the provider reports it: its file's format, length and size:modified:generation. */
    private fun row(id: String, name: String, seconds: Int, size: Long, modified: Long = 1_700_000_000, generation: Long = 7) =
        track(id, androidMediaSourceRevision(size, modified, generation)).copy(fileName = name, durationMs = seconds * 1_000L)

    @Test fun aRowIdHandedToAnotherFileDoesNotKeepTheOldAppId() {
        // MediaStore's database rebuilt (its data cleared, or recovered after damage): row ids are handed out anew.
        for (mode in listOf(RowIds.UNVERSIONED, RowIds.NEW_DATABASE)) {
            val f = Fixture(tags)
            val old = row("10", "old song.mp3", seconds = 200, size = 4_800_000)
            assertEquals(TrackId("10"), f.scan(old).single().id)
            f.remember()
            f.rowIds = mode
            val other = row("10", "another song.flac", seconds = 300, size = 31_000_000, modified = 1_600_000_000, generation = 2)
            val resolved = f.scan(other).single()
            assertTrue(resolved.id != TrackId("10"), "$mode: playlists and history keyed by 10 would attach to another song")
            assertEquals("10", f.registry.sourceId(resolved.id))
            assertEquals(resolved.id, f.registry.logicalId("10"))
            assertEquals(other.audioUri, resolved.audioUri)
            f.restart()
            assertEquals(resolved, f.scan(other).single())
            // The id stays taken: a third file at the row gets one of its own, never the first song's.
            f.rowIds = RowIds.NEW_DATABASE
            val third = f.scan(row("10", "third.flac", seconds = 120, size = 9_000_000, generation = 3)).single()
            assertEquals(3, setOf(TrackId("10"), resolved.id, third.id).size)
        }
    }

    @Test fun aRowThatAppearsAfterTheFirstScanOfANewDatabaseIsCheckedToo() {
        // The app opened while the rebuilt provider was still indexing, or with the card out: that scan held row 10
        // only, and the version was recorded with it. Row 11 arrives later, under a database that is now "the same".
        val first = row("10", "first.mp3", seconds = 200, size = 4_800_000)
        val second = row("11", "second.mp3", seconds = 240, size = 5_700_000)
        fun afterAPartialFirstScan() = Fixture(tags).also { f ->
            f.scan(first, second); f.remember()
            f.rowIds = RowIds.NEW_DATABASE
            assertEquals(TrackId("10"), f.scan(first.copy(sourceRevision = androidMediaSourceRevision(4_800_000, 1_700_000_000, 1))).single().id)
            assertEquals("$UNCHECKED_NONE\n11", f.unchecked)
            f.restart()
            f.rowIds = RowIds.SAME_DATABASE
        }
        // Another file under the old number: it must not inherit what is kept under 11.
        val other = row("11", "another song.flac", seconds = 300, size = 31_000_000, generation = 2)
        val f = afterAPartialFirstScan()
        val resolved = f.scan(first, other)
        assertEquals(TrackId("10"), resolved[0].id)
        assertTrue(resolved[1].id != TrackId("11"), "a row not checked since the rebuild was believed")
        assertEquals("11", f.registry.sourceId(resolved[1].id))
        assertEquals(UNCHECKED_NONE, f.unchecked, "judged once, and then a row of this database like any other")
        // The same file under its old number keeps its id, and from then on an edit in place may change anything.
        val g = afterAPartialFirstScan()
        val rescanned = second.copy(sourceRevision = androidMediaSourceRevision(5_700_000, 1_700_000_000, 2))
        assertEquals(TrackId("11"), g.scan(first, rescanned)[1].id)
        assertEquals(UNCHECKED_NONE, g.unchecked)
        g.restart()
        assertEquals(TrackId("11"), g.scan(first, row("11", "second.flac", seconds = 300, size = 31_000_000, generation = 3))[1].id)
    }

    @Test fun aRowWaitingToBeCheckedIsCheckedWhateverTheScanKnowsOfTheProvider() {
        // The provider could not be asked for its version at this scan: the list still names row 11.
        val f = Fixture(tags)
        f.scan(row("10", "first.mp3", seconds = 200, size = 4_800_000), row("11", "second.mp3", seconds = 240, size = 5_700_000))
        f.unchecked = "$UNCHECKED_NONE\n11"
        f.restart()
        f.rowIds = RowIds.UNVERSIONED
        // Same format and near the same size, another length: the test of an unversioned provider alone lets it pass.
        val resolved = f.scan(row("11", "another.mp3", seconds = 300, size = 6_000_000, generation = 2)).single()
        assertTrue(resolved.id != TrackId("11"))
    }

    @Test fun aRecordedVersionWithNoListOfUncheckedRowsChecksEveryRow() {
        // The list is written before the version, so a version without it means the list was lost (or the build
        // that recorded the version kept none): nothing says which rows were checked.
        for (lost in listOf(null, "", "not the list")) {
            val f = Fixture(tags)
            f.scan(row("10", "first.mp3", seconds = 200, size = 4_800_000), row("11", "second.mp3", seconds = 240, size = 5_700_000))
            f.unchecked = lost
            f.restart()
            f.rowIds = RowIds.SAME_DATABASE
            val resolved = f.scan(row("11", "another song.flac", seconds = 300, size = 31_000_000, generation = 2)).single()
            assertTrue(resolved.id != TrackId("11"), "$lost")
            // Written again from this scan: row 10 was not in it.
            assertEquals("$UNCHECKED_NONE\n10", f.unchecked)
        }
    }

    @Test fun aListOfUncheckedRowsThatCannotBeSavedFailsTheScanAndIsBuiltAgain() {
        val f = Fixture(tags)
        val first = row("10", "first.mp3", seconds = 200, size = 4_800_000)
        f.scan(first, row("11", "second.mp3", seconds = 240, size = 5_700_000))
        f.rowIds = RowIds.NEW_DATABASE
        f.uncheckedFails = true
        // The caller records the version only after a scan that did not fail: this one must.
        assertFailsWith<IOException> { f.scan(first) }
        assertEquals(UNCHECKED_NONE, f.unchecked)
        assertEquals(listOf(first), f.registry.durable(listOf(first), f.rowIds))
        f.uncheckedFails = false
        f.scan(first)
        assertEquals("$UNCHECKED_NONE\n11", f.unchecked)
    }

    @Test fun theProviderVersionIsRecordedOnlyByAScanThatCouldSeeEveryVolume() {
        assertTrue(recordsProviderVersion(RowIds.NEW_DATABASE, durable = true) { false })
        // A card out or being checked: its rows are not in the scan, and the next scan checks again.
        assertFalse(recordsProviderVersion(RowIds.NEW_DATABASE, durable = true) { true })
        // And no other scan has a version to record, or asks the system about its volumes for it.
        assertFalse(recordsProviderVersion(RowIds.NEW_DATABASE, durable = false) { error("not asked") })
        assertFalse(recordsProviderVersion(RowIds.SAME_DATABASE, durable = true) { error("not asked") })
        assertFalse(recordsProviderVersion(RowIds.UNVERSIONED, durable = true) { error("not asked") })
    }

    @Test fun aNewDatabaseKeepsTheIdOfTheSameFileAndOfNoOtherOfItsFormat() {
        val f = Fixture(tags)
        val original = row("10", "song.mp3", seconds = 200, size = 4_800_000)
        val neighbour = row("11", "next.mp3", seconds = 240, size = 5_000_000)
        f.scan(original, neighbour); f.remember()
        f.rowIds = RowIds.NEW_DATABASE
        // A rebuilt database starts its generations again; the files, and here their numbers, are the same.
        val rescanned = listOf(original, neighbour).map { it.copy(sourceRevision = it.sourceRevision!!.substringBeforeLast(':') + ":1") }
        assertEquals(listOf(TrackId("10"), TrackId("11")), f.scan(*rescanned.toTypedArray()).map { it.id })
        // The same numbers for two other files of that format: neither inherits anything.
        val swapped = f.scan(
            row("10", "next.mp3", seconds = 240, size = 5_000_000, generation = 2),
            row("11", "song.mp3", seconds = 200, size = 4_800_000, generation = 3),
        )
        assertTrue(swapped.none { it.id == TrackId("10") || it.id == TrackId("11") })
    }

    @Test fun aFileOfTheSameSizeIsTheSameFileUnderANewProviderVersion() {
        // A provider update: the version changes with its schema, the rows keep their numbers, and it reads every
        // file again. This one's length came out three seconds longer, with a new modified time and generation.
        val original = row("10", "song.mp3", seconds = 200, size = 4_800_000)
        val f = Fixture(tags)
        f.scan(original); f.remember()
        f.rowIds = RowIds.NEW_DATABASE
        val reread = row("10", "song.mp3", seconds = 203, size = 4_800_000, modified = 1_700_009_000, generation = 40)
        assertEquals(reread, f.scan(reread).single())
        // Another size and another length is another file, as before.
        val g = Fixture(tags)
        g.scan(original); g.remember()
        g.rowIds = RowIds.NEW_DATABASE
        val other = row("10", "other.mp3", seconds = 203, size = 4_870_000, modified = 1_700_009_000, generation = 40)
        assertTrue(g.scan(other).single().id != TrackId("10"))
        // And the size says nothing across formats, or where the provider gave none.
        val h = Fixture(tags)
        h.scan(original); h.remember()
        h.rowIds = RowIds.NEW_DATABASE
        assertTrue(h.scan(row("10", "song.flac", seconds = 203, size = 4_800_000, generation = 40)).single().id != TrackId("10"))
        val unsized = track("10", "android-mediastore-v1:-:1700000000:7").copy(fileName = "song.mp3", durationMs = 200_000)
        val i = Fixture(tags)
        i.scan(unsized); i.remember()
        i.rowIds = RowIds.NEW_DATABASE
        val unsizedLater = track("10", "android-mediastore-v1:-:1700009000:40").copy(fileName = "song.mp3", durationMs = 203_000)
        assertTrue(i.scan(unsizedLater).single().id != TrackId("10"))
    }

    @Test fun anEditInPlaceKeepsTheAppId() {
        val original = row("10", "song.mp3", seconds = 200, size = 4_800_000)
        // Retagged: a new revision, the length read a little differently. With a cover: twice the size, the same length.
        val retagged = row("10", "song.mp3", seconds = 200, size = 4_810_000, modified = 1_700_000_500, generation = 9)
            .copy(title = "New title", durationMs = 200_400)
        val withCover = row("10", "song.mp3", seconds = 200, size = 11_000_000, modified = 1_700_000_900, generation = 10)
        // Trimmed in place: the row and most of the bytes stay, the length does not.
        val trimmed = row("10", "song.mp3", seconds = 150, size = 3_600_000, modified = 1_700_001_000, generation = 11)
        for (mode in RowIds.entries) for (edited in listOf(retagged, withCover)) {
            val f = Fixture(tags)
            f.scan(original); f.remember()
            f.rowIds = mode
            assertEquals(edited, f.scan(edited).single(), "$mode")
        }
        for (mode in listOf(RowIds.SAME_DATABASE, RowIds.UNVERSIONED)) {
            val f = Fixture(tags)
            f.scan(original); f.remember()
            f.rowIds = mode
            assertEquals(trimmed, f.scan(trimmed).single(), "$mode")
        }
        // While the database is the same one, a row id names its file whatever became of it.
        val f = Fixture(tags)
        f.scan(original); f.remember()
        f.rowIds = RowIds.SAME_DATABASE
        val converted = row("10", "song.flac", seconds = 300, size = 31_000_000, generation = 12)
        assertEquals(converted, f.scan(converted).single())
    }

    @Test fun rowIdsAreBelievedOnlyUnderTheDatabaseTheRegistryWasSavedWith() {
        assertEquals(RowIds.SAME_DATABASE, rowIds(current = "12:abc", recorded = "12:abc"))
        assertEquals(RowIds.NEW_DATABASE, rowIds(current = "12:def", recorded = "12:abc"))
        // The first scan of a build that records it: nothing says the ids were never renumbered.
        assertEquals(RowIds.NEW_DATABASE, rowIds(current = "12:abc", recorded = null))
        assertEquals(RowIds.UNVERSIONED, rowIds(current = null, recorded = "12:abc"))
        assertEquals(RowIds.UNVERSIONED, rowIds(current = null, recorded = null))
    }

    @Test fun aRegistryThatCannotBeSavedStillServesTheLibraryUnderItsDurableIds() {
        // Storage full and one new file: the registry must be rewritten and cannot be.
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        val baseline = f.disk.payload
        f.disk.fail = true
        val rows = listOf(track("10", "retagged"), track("11"))
        assertFailsWith<IOException> { f.scan(*rows.toTypedArray()) }
        var durable = true
        val scan = mediaStoreLibraryScan(durable = { durable }) { durable = false; f.registry.durable(rows) }
        assertEquals(rows, scan.tracks)
        assertFalse(scan.complete, "ids that are not on storage prove nothing gone")
        assertTrue(scan.answered)
        assertEquals(baseline, f.disk.payload)
        // Nothing was registered meanwhile: the next scan that can be saved does it.
        f.disk.fail = false
        assertEquals(rows, f.scan(*rows.toTypedArray()))
        assertTrue(f.disk.payload != baseline)
    }

    @Test fun anUnsavedRelocationIsServedUnderItsRowAndAReusedIdIsNotServedAtAll() {
        val f = Fixture(tags)
        // Only the song that moves carries the release-track id: a second copy of it would veto the relocation.
        f.reader = { if (it.id.value == "30") TrackIdentityTags() else tags }
        f.scan(track("10"), row("30", "old song.mp3", seconds = 200, size = 4_800_000)); f.remember()
        f.disk.fail = true
        f.rowIds = RowIds.NEW_DATABASE
        // 20 is the song of 10, moved: its old id is not durable for this row yet. 30 now names another file, and
        // "30" is the app id of the song it named before.
        val moved = track("20")
        val reused = row("30", "another song.flac", seconds = 300, size = 31_000_000, generation = 2)
        assertEquals(listOf(moved), f.registry.durable(listOf(moved, reused, moved), f.rowIds))
        assertEquals("10", f.registry.sourceId(TrackId("10")))
        f.disk.fail = false
        val saved = f.scan(moved, reused)
        assertEquals(TrackId("10"), saved[0].id)
        assertTrue(saved[1].id != TrackId("30"))
    }

    @Test fun aDamagedRegistryServesTheRowsUnderTheirOwnNumbersAndIsLeftAlone() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        f.disk.payload = f.disk.payload!! + "x"
        val damaged = f.disk.payload
        f.restart()
        val rows = listOf(track("10"), track("11"))
        assertFailsWith<IOException> { f.scan(*rows.toTypedArray()) }
        assertEquals(rows, f.registry.durable(rows))
        assertEquals(damaged, f.disk.payload)
        assertEquals(0, f.disk.writes - 2, "the baseline and the tag pass, nothing since")
    }

    @Test fun corruptedRegistryIsNeverSilentlyReplaced() {
        val f = Fixture(tags)
        f.scan(track("10")); f.remember()
        f.disk.payload = f.disk.payload!! + "x"
        val damaged = f.disk.payload
        f.restart()
        assertFailsWith<IOException> { f.scan(track("20")) }
        assertEquals(damaged, f.disk.payload)
    }

    @Test fun staleBackgroundReadCannotOverwriteANewerScan() {
        val f = Fixture(tags)
        f.scan(track("10"))
        f.reader = {
            f.scan(track("10", "v2"))
            tags
        }
        f.remember()
        f.reader = { TrackIdentityTags() }
        f.remember()
        assertEquals(TrackId("20"), f.scan(track("20")).single().id)
    }

    private companion object {
        /** The file of rows not yet checked, with none left: its header line alone. */
        const val UNCHECKED_NONE = "latentjam-unchecked-rows-v1"
    }

    /** The registry's text before it was streamed, verbatim: the file on disk must not change. */
    private object OldFormat {
        const val HEADER = "latentjam-track-identities-v1"
        fun hex(value: String): String = buildString(value.length * 2) {
            for (byte in value.encodeToByteArray()) {
                append("0123456789abcdef"[(byte.toInt() and 255) ushr 4])
                append("0123456789abcdef"[byte.toInt() and 15])
            }
        }
        fun unhex(value: String): String =
            ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                .decodeToString(throwOnInvalidSequence = true)
        fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun encode(rows: List<List<String>>): String {
            val body = rows.sortedBy { it[0] }.joinToString("\n") { it.joinToString("|", transform = ::hex) }
            return "$HEADER\n${digest(body)}\n$body"
        }
        fun decode(payload: String): List<List<String>> {
            val header = payload.split('\n', limit = 3)
            check(header.size == 3 && header[0] == HEADER && digest(header[2]) == header[1])
            return header[2].lineSequence().filter(String::isNotEmpty).map { line -> line.split('|').map(::unhex) }.toList()
        }
    }

    @Test fun registryFileStaysByteIdenticalToTheOldFormat() {
        val f = Fixture(tags)
        val tracks = (1..40).map { track(it.toString()).copy(fileName = "Söng $it.FLAC", storageVolume = "1A2B-3C4D") }
        f.scan(*tracks.toTypedArray()); f.remember()
        f.scan(track("relocated-by-volume"))
        val payload = f.disk.payload!!
        val rows = OldFormat.decode(payload)
        assertEquals(41, rows.size)
        assertEquals(payload, OldFormat.encode(rows))
        // And a file the old code wrote reads back the same.
        f.restart()
        assertEquals(TrackId("relocated-by-volume"), f.registry.logicalId("relocated-by-volume"))
        assertEquals("5", f.registry.sourceId(TrackId("5")))
        f.disk.payload = OldFormat.encode(rows)
        f.restart()
        assertEquals(payload, f.disk.payload)
        assertEquals(listOf(track("relocated-by-volume")), f.scan(track("relocated-by-volume")))
    }

    @Test fun emptyAndDamagedRegistriesKeepTheirOldOutcomes() {
        val f = Fixture(tags)
        f.disk.payload = OldFormat.encode(emptyList())
        f.restart()
        assertEquals(listOf(track("1")), f.scan(track("1")))
        for (damaged in listOf("", OldFormat.HEADER, OldFormat.HEADER + "\nabc", OldFormat.encode(emptyList()) + "\n",
            OldFormat.encode(listOf(List(10) { "x" })).replace("|", "||"))) {
            f.disk.payload = damaged
            f.restart()
            assertFailsWith<IOException> { f.scan(track("1")) }
        }
    }
}
