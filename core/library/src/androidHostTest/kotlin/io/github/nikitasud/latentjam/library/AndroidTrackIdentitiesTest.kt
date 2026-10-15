/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.TrackIdentityTags
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.IOException
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
        fun fresh() = AndroidTrackIdentities({ disk.payload }, disk::save) { reads++; reader(it) }
        var registry = fresh()
        fun scan(vararg tracks: TrackDescriptor, present: Set<String>? = tracks.map { it.id.value }.toSet(), storage: Boolean = true) =
            registry.resolve(tracks.toList(), present, storage)
        fun remember() = registry.remember()
        fun restart() { registry = fresh() }
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
}
