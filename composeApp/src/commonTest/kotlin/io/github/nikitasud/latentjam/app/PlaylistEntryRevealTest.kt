/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaylistEntryRevealTest {
    private val tracks = listOf("z", "a", "m").map { TrackDescriptor(TrackId(it)) }
    private val playlist = CollectionSelection(
        title = "Manual order", subtitle = null, artworkUri = null,
        tracks = tracks, playlistId = "test",
    )

    @Test
    fun manualOrderIsPreservedAndTheCoverOccupiesTheFirstItem() {
        assertEquals(1, playlistEntryTrackIndex(playlist, TrackId("z")))
        assertEquals(2, playlistEntryTrackIndex(playlist, TrackId("a")))
        assertEquals(3, playlistEntryTrackIndex(playlist, TrackId("m")))
    }

    @Test
    fun automaticPlaylistsAlsoRevealTheirCurrentTrack() {
        assertEquals(3, playlistEntryTrackIndex(
            playlist.copy(playlistId = null, routeId = "auto:recent"), TrackId("m"),
        ))
    }

    @Test
    fun otherCollectionsKeepTheirExistingOpeningBehavior() {
        for (route in listOf("album:test", "artist:test", "genre:test", "folder:test")) {
            assertNull(playlistEntryTrackIndex(
                playlist.copy(playlistId = null, routeId = route), TrackId("a"),
            ))
        }
    }

    @Test
    fun missingPlaybackOrMissingMembershipDoesNotChooseAnotherTrack() {
        assertNull(playlistEntryTrackIndex(playlist, null))
        assertNull(playlistEntryTrackIndex(playlist, TrackId("absent")))
        assertNull(playlistEntryTrackIndex(playlist.copy(tracks = emptyList()), TrackId("a")))
    }

    @Test
    fun sectionHeadersAreIncludedInTheTargetIndex() {
        val grouped = playlist.copy(sections = listOf(
            CollectionSection("First", tracks.take(1)),
            CollectionSection("Rest", tracks.drop(1)),
        ))
        assertEquals(2, playlistEntryTrackIndex(grouped, TrackId("z")))
        assertEquals(4, playlistEntryTrackIndex(grouped, TrackId("a")))
        assertEquals(5, playlistEntryTrackIndex(grouped, TrackId("m")))
    }

    @Test
    fun fullyVisibleTrackNeedsNoScrollIncludingAtTheReadableBoundary() {
        assertTrue(playlistEntryRowVisible(20, 72, 0, 600, 100))
        assertTrue(playlistEntryRowVisible(428, 72, 0, 600, 100))
    }

    @Test
    fun rowsClippedByTheHeaderOrPlayerStillNeedRevealing() {
        assertFalse(playlistEntryRowVisible(-1, 72, 0, 600, 100))
        assertFalse(playlistEntryRowVisible(450, 72, 0, 600, 100))
        assertFalse(playlistEntryRowVisible(550, 72, 0, 600, 100))
    }

    @Test
    fun viewportCoordinatesAndVeryLargeTextAreHandled() {
        assertTrue(playlistEntryRowVisible(20, 120, 20, 160, 20))
        assertTrue(playlistEntryRowVisible(20, 180, 20, 160, 20))
        assertFalse(playlistEntryRowVisible(40, 180, 20, 160, 20))
        assertFalse(playlistEntryRowVisible(0, 72, 0, 80, 100))
        assertFalse(playlistEntryRowVisible(0, 0, 0, 600, 100))
    }
}
