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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class AlbumArtVersionsTest {

    private val art = "content://media/external/audio/albumart/12"

    private fun track(id: String, revision: String, artwork: String? = art) =
        TrackDescriptor(TrackId(id), artworkUri = artwork, sourceRevision = revision)

    @Test
    fun everyTrackOfAnAlbumSharesItsArtworkUri() {
        val versioned = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        assertEquals(1, versioned.map { it.artworkUri }.distinct().size)
        assertTrue(versioned.first().artworkUri!!.startsWith("$art?v="))
    }

    @Test
    fun albumArtVersionChangesWhenAnyTrackOfTheAlbumChanges() {
        val before = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        val after = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b-edited")))
        assertNotEquals(before.first().artworkUri, after.first().artworkUri)
    }

    @Test
    fun theVersionDoesNotDependOnTrackOrder() {
        val one = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        val other = withAlbumArtVersions(listOf(track("2", "b"), track("1", "a")))
        assertEquals(one.first().artworkUri, other.first().artworkUri)
    }

    @Test
    fun distinctMediaStoreRevisionsWithTheSameStringHashInvalidateArtwork() {
        val before = "android-mediastore-v1:2354898:1792139421:200584"
        val after = "android-mediastore-v1:19416011:1792173056:752108"
        assertEquals(before.hashCode(), after.hashCode(), "regression fixture must collide")
        assertNotEquals(
            withAlbumArtVersions(listOf(track("1", before))).single().artworkUri,
            withAlbumArtVersions(listOf(track("1", after))).single().artworkUri,
        )
    }

    @Test
    fun exchangingTwoTracksRevisionsInvalidatesTheAlbum() {
        val before = withAlbumArtVersions(listOf(track("1", "a"), track("2", "b")))
        val after = withAlbumArtVersions(listOf(track("1", "b"), track("2", "a")))
        assertNotEquals(before.first().artworkUri, after.first().artworkUri)
    }

    @Test
    fun addingATrackWithNoRevisionChangesTheAlbumsMembership() {
        val first = track("1", "a")
        val unknown = track("2", "b").copy(sourceRevision = null)
        assertNotEquals(
            withAlbumArtVersions(listOf(first)).first().artworkUri,
            withAlbumArtVersions(listOf(first, unknown)).first().artworkUri,
        )
    }

    @Test
    fun otherAlbumsKeepTheirVersionAndArtworklessTracksStayAsTheyAre() {
        val other = "content://media/external/audio/albumart/13"
        val before = withAlbumArtVersions(listOf(track("1", "a"), track("3", "c", other), track("4", "d", null)))
        val after = withAlbumArtVersions(listOf(track("1", "a-edited"), track("3", "c", other), track("4", "d", null)))
        assertEquals(before[1].artworkUri, after[1].artworkUri)
        assertNull(after[2].artworkUri)
    }

    @Test
    fun aSongsOwnCoverCarriesItsAlbumsVersionAndFallsBackToTheVersionedAlbumCover() {
        fun song(id: Long, revision: String) = TrackDescriptor(
            TrackId(id.toString()),
            artworkUri = MediaStoreArtwork.trackCover(id, albumId = 12),
            albumArtworkUri = MediaStoreArtwork.albumCover(12),
            sourceRevision = revision,
        )
        val versioned = withAlbumArtVersions(listOf(song(1, "a"), song(2, "b")))
        val album = versioned.first().albumArtworkUri!!
        assertTrue(album.startsWith("$art?v="))
        assertEquals(listOf(album, album), versioned.map { it.albumArtworkUri })
        assertEquals(2, versioned.map { it.artworkUri }.distinct().size)
        versioned.forEach { track ->
            assertTrue(track.artworkUri!!.endsWith("&v=" + album.substringAfter("?v=")))
            assertEquals(album, MediaStoreArtwork.albumFallback(track.artworkUri!!))
        }
        val edited = withAlbumArtVersions(listOf(song(1, "a"), song(2, "b-edited")))
        assertNotEquals(versioned.first().artworkUri, edited.first().artworkUri, "a sibling's edit can change the fallback")
    }
    @Test
    fun aSongWithoutAnAlbumStillInvalidatesItsEmbeddedCoverAfterRetagging() {
        fun song(revision: String) = TrackDescriptor(
            TrackId("42"),
            artworkUri = MediaStoreArtwork.trackCover(42, albumId = 0),
            sourceRevision = revision,
        )
        val before = withAlbumArtVersions(listOf(song("old"))).single()
        val after = withAlbumArtVersions(listOf(song("new"))).single()
        assertNotEquals(before.artworkUri, after.artworkUri)
        assertEquals("content://media/external/audio/media/42", MediaStoreArtwork.audioFallback(after.artworkUri!!))
        assertNull(MediaStoreArtwork.albumFallback(after.artworkUri!!))
    }

}
