/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class PlaylistPresentationIdentityTest {

    @Test
    fun identityChangesForEveryPlaylistFieldUsedByForYouAndWorldNames() {
        val original = listOf(Playlist(id = "one", name = "Road", trackIds = listOf("a", "b")))
        val identity = original.presentationIdentity()

        assertNotEquals(identity, original.map { it.copy(name = "Night") }.presentationIdentity())
        assertNotEquals(identity, original.map { it.copy(trackIds = listOf("a", "c")) }.presentationIdentity())
        assertNotEquals(identity, original.map { it.copy(id = "two") }.presentationIdentity())
        assertNotEquals(
            identity,
            (original + Playlist(id = "two", name = "Other")).presentationIdentity(),
        )
    }

    @Test
    fun smartOptInAloneDoesNotInvalidatePresentationCaches() {
        val playlist = Playlist(id = "one", name = "Road", trackIds = listOf("a", "b"))

        assertEquals(
            listOf(playlist).presentationIdentity(),
            listOf(playlist.copy(includeInSmart = true)).presentationIdentity(),
        )
    }

    @Test
    fun smartMembershipsIgnoreDisabledAndIneffectivePlaylists() {
        val playlists = listOf(
            Playlist(id = "off", name = "Off", trackIds = listOf("a", "b")),
            Playlist(id = "one", name = "One", trackIds = listOf("a"), includeInSmart = true),
            Playlist(
                id = "on",
                name = "On",
                trackIds = listOf("a", "b", "a"),
                includeInSmart = true,
            ),
            Playlist(
                id = "same-policy",
                name = "Same membership",
                trackIds = listOf("b", "a"),
                includeInSmart = true,
            ),
        )

        assertEquals(
            listOf(linkedSetOf(TrackId("a"), TrackId("b"))),
            playlists.smartCompanionMemberships(),
        )
    }

    @Test
    fun smartMembershipPolicyIsInvariantToPlaylistAndTrackReordering() {
        val first = Playlist(
            id = "first",
            name = "First",
            trackIds = listOf("z", "a"),
            includeInSmart = true,
        )
        val second = Playlist(
            id = "second",
            name = "Second",
            trackIds = listOf("m", "b"),
            includeInSmart = true,
        )

        assertEquals(
            listOf(first, second).smartCompanionMemberships(),
            listOf(
                second.copy(trackIds = second.trackIds.reversed()),
                first.copy(trackIds = first.trackIds.reversed()),
            ).smartCompanionMemberships(),
        )
    }

    @Test
    fun initialPolicyHydrationPreservesSavedFutureButLaterChangesInvalidateIt() {
        val empty = emptyList<Set<TrackId>>()
        val marked = listOf(setOf(TrackId("a"), TrackId("b")))

        assertFalse(
            shouldInvalidateSmartFuture(
                policyInitialized = false,
                previous = empty,
                updated = marked,
            ),
        )
        assertFalse(
            shouldInvalidateSmartFuture(
                policyInitialized = true,
                previous = marked,
                updated = marked,
            ),
        )
        assertTrue(
            shouldInvalidateSmartFuture(
                policyInitialized = true,
                previous = marked,
                updated = empty,
            ),
        )
    }
}
