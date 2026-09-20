/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtistNavigationTest {
    @Test
    fun structuredCreditsResolveBothArtistsAndTheirWholeCollections() {
        val collaboration = track("duet", "Alpha feat. Beta", listOf("Alpha", "Beta"))
        val alphaSolo = track("alpha-solo", "Alpha")
        val betaSolo = track("beta-solo", "Beta")
        val catalog = LibraryCatalog.build(listOf(collaboration, alphaSolo, betaSolo))
        val choices = artistDestinations(collaboration, catalog)

        assertEquals(listOf("Alpha", "Beta"), choices.map { it.name })
        assertEquals(setOf(collaboration.id, alphaSolo.id), choices[0].tracks.map { it.id }.toSet())
        assertEquals(setOf(collaboration.id, betaSolo.id), choices[1].tracks.map { it.id }.toSet())
    }

    @Test
    fun semicolonDisplayCreditsUseTheSameGroupsAsTheArtistsTab() {
        val collaboration = track("duet", "Alpha; Beta")
        val choices = artistDestinations(collaboration, LibraryCatalog.build(listOf(collaboration)))
        assertEquals(listOf("Alpha", "Beta"), choices.map { it.name })
    }

    @Test
    fun aSoloArtistWithDifferentCasingStillHasOneDestination() {
        val first = track("first", "ALPHA")
        val playing = track("playing", "alpha")
        val choices = artistDestinations(playing, LibraryCatalog.build(listOf(first, playing)))
        assertEquals(listOf("ALPHA"), choices.map { it.name })
    }

    @Test
    fun queuedMetadataFromBeforeTagEnrichmentUsesTheCurrentCatalogue() {
        val queued = track("duet", "Alpha feat. Beta")
        val enriched = queued.copy(artists = listOf("Alpha", "Beta"))
        val choices = artistDestinations(queued, LibraryCatalog.build(listOf(enriched)))
        assertEquals(listOf("Alpha", "Beta"), choices.map { it.name })
    }

    @Test
    fun aBandNameContainingPunctuationIsNotSplitIntoInventedArtists() {
        val band = track("band", "Earth, Wind & Fire")
        val choices = artistDestinations(band, LibraryCatalog.build(listOf(band)))
        assertEquals(listOf("Earth, Wind & Fire"), choices.map { it.name })
    }

    @Test
    fun aMissingTrackDoesNotNavigateToAnUnrelatedGroupWithMatchingDisplayText() {
        val removed = track("removed", "Alpha")
        val present = track("present", "Alpha")
        assertTrue(artistDestinations(removed, LibraryCatalog.build(listOf(present))).isEmpty())
        assertTrue(artistDestinations(removed, null).isEmpty())
    }

    private fun track(id: String, artist: String, artists: List<String> = emptyList()) =
        TrackDescriptor(id = TrackId(id), title = id, artist = artist, artists = artists)
}
