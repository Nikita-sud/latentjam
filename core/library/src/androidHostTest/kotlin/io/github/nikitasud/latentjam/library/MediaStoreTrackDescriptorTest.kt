/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scan's compact descriptor must read, compare, hash and print as the plain descriptor the scan built before, and
 * stay so through every copy the scan pipeline makes.
 */
class MediaStoreTrackDescriptorTest {

    private data class Row(
        val id: Long, val albumId: Long, val title: String?, val duration: Long?, val added: Long?, val year: Int?,
        val size: Long?, val revSize: Long?, val modified: Long?, val generation: Long?, val track: Int?, val disc: Int?,
    )

    private val rows = listOf(
        Row(12, 7, "Song", 201_000, 1_700_000_000_000, 1999, 4_000_000, 4_000_000, 1_700_000_100, 51_000, 3, 1),
        Row(13, 0, null, null, null, null, null, 0, null, null, null, null),
        Row(99_999, 42, "Ünïcødé", 1, 1, 2025, 1, 1, 1, null, 0, 2),
    )

    /** What queryRawTracks built before the compact descriptor. */
    private fun plain(row: Row): TrackDescriptor = TrackDescriptor(
        id = TrackId(row.id.toString()),
        title = row.title,
        artist = "Artist",
        album = "Album",
        genre = null,
        albumArtist = "Various",
        durationMs = row.duration,
        audioUri = "content://media/external/audio/media/${row.id}",
        artworkUri = MediaStoreArtwork.trackCover(row.id, row.albumId),
        albumArtworkUri = row.albumId.takeIf { it > 0 }?.let(MediaStoreArtwork::albumCover),
        addedAtMs = row.added,
        folderPath = "Music/",
        storageVolume = "external_primary",
        year = row.year,
        sizeBytes = row.size,
        fileName = "song.mp3",
        trackNumber = row.track,
        discNumber = row.disc,
        sourceRevision = androidMediaSourceRevision(row.revSize, row.modified, row.generation),
    )

    private fun compact(row: Row): MediaStoreTrackDescriptor = MediaStoreTrackDescriptor.fromMediaStore(
        sourceId = row.id,
        albumId = row.albumId,
        title = row.title,
        artist = "Artist",
        album = "Album",
        genre = null,
        albumArtist = "Various",
        durationMs = row.duration,
        albumArtworkUri = row.albumId.takeIf { it > 0 }?.let(MediaStoreArtwork::albumCover),
        addedAtMs = row.added,
        folderPath = "Music/",
        storageVolume = "external_primary",
        year = row.year,
        sizeBytes = row.size,
        fileName = "song.mp3",
        trackNumber = row.track,
        discNumber = row.disc,
        revisionSize = row.revSize,
        revisionModified = row.modified,
        revisionGeneration = row.generation,
    )

    private fun same(expected: TrackDescriptor, actual: TrackDescriptor) {
        assertEquals(expected, actual)
        assertEquals(actual, expected)
        assertEquals(expected.hashCode(), actual.hashCode())
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    fun readsAsThePlainDescriptor() {
        for (row in rows) same(plain(row), compact(row))
    }

    @Test
    fun artVersionsAndThePipelinesCopiesStayEqual() {
        for (row in rows) {
            val version = "ab".repeat(32)
            val plainVersioned = plain(row).let { track ->
                track.copy(
                    artworkUri = track.artworkUri?.let { MediaStoreArtwork.versioned(it, version) },
                    albumArtworkUri = track.albumArtworkUri?.let { MediaStoreArtwork.versioned(it, version) },
                )
            }
            val compactVersioned = compact(row).let { track ->
                track.withArtVersion(version, track.albumArtworkUri?.let { MediaStoreArtwork.versioned(it, version) })!!
            }
            same(plainVersioned, compactVersioned)
            assertTrue(compactVersioned is MediaStoreTrackDescriptor)

            // Relocation (identities.resolve), enrichment (GenreEnrichment) and a cover override.
            val copies = listOf<(TrackDescriptor) -> TrackDescriptor>(
                { it.copy(id = TrackId("relocated-${row.id}")) },
                { it.copy(genre = "Jazz", artists = listOf("A", "B"), originalYear = 1970, language = "ro", year = 1971) },
                { it.copy(artworkUri = "file:///covers/x.jpg", albumArtworkUri = it.artworkUri) },
                { it.copy(audioUri = null, sourceRevision = "other") },
                { it.copy(durationMs = Long.MIN_VALUE, trackNumber = Int.MIN_VALUE) },
                { it.copy(sizeBytes = null, title = "Renamed") },
            )
            for (copy in copies) {
                val expected = copy(plainVersioned)
                val actual = copy(compactVersioned)
                same(expected, actual)
                // A second round on top keeps every value too (explicit strings carry over).
                same(expected.copy(title = "again"), actual.copy(title = "again"))
                same(expected.copy(), actual.copy())
            }
            // A cover set by copy cannot take a version; the pipeline then versions by copy.
            val overridden = compactVersioned.copy(artworkUri = "file:///x.jpg") as MediaStoreTrackDescriptor
            assertEquals(null, overridden.withArtVersion(version, null))
        }
    }
}
