/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class CollectionRailTest {

    private fun track(id: String, title: String, artwork: String? = "cover:$id") =
        TrackDescriptor(id = TrackId(id), title = title, artworkUri = artwork)

    @Test
    fun titleRailOffsetsAnchorsPastTheActionsRow() {
        val selection = CollectionSelection(
            title = "Album",
            subtitle = null,
            artworkUri = null,
            tracks = listOf(
                track("a", "Alpha"),
                track("b1", "Beta"),
                track("b2", "Bravo"),
            ),
            railMode = CollectionRailMode.TRACK_TITLES,
            routeId = "album:test",
        )

        val presentation = collectionRailPresentation(selection)

        assertContentEquals(listOf("A", "B"), presentation.rail.buckets)
        assertContentEquals(listOf(1, 2), presentation.rail.startIndexes)
        assertEquals(4, presentation.artworkKeys.size)
        assertEquals(null, presentation.artworkKeys.first())
    }

    @Test
    fun sectionRailTargetsHeadersWithActionsAndPriorTracksAccountedFor() {
        val alpha = CollectionSection("Alpha", listOf(track("a1", "One"), track("a2", "Two")))
        val beta = CollectionSection("Beta", listOf(track("b", "Three")))
        val selection = CollectionSelection(
            title = "Artist",
            subtitle = null,
            artworkUri = null,
            tracks = alpha.tracks + beta.tracks,
            sections = listOf(alpha, beta),
            railMode = CollectionRailMode.SECTION_TITLES,
            routeId = "artist:test",
        )

        val presentation = collectionRailPresentation(selection)

        assertContentEquals(listOf("A", "B"), presentation.rail.buckets)
        // actions@0, A header@1, A tracks@2..3, B header@4
        assertContentEquals(listOf(1, 4), presentation.rail.startIndexes)
        assertEquals(6, presentation.artworkKeys.size)
    }

    @Test
    fun unknownSectionKeepsQuestionMarkRailBucketAfterLocalization() {
        val known = CollectionSection("Zulu", listOf(track("z", "One")))
        val unknown = CollectionSection(
            title = "Unknown album",
            tracks = listOf(track("u", "Two")),
            railTitle = null,
        )
        val selection = CollectionSelection(
            title = "Artist",
            subtitle = null,
            artworkUri = null,
            tracks = known.tracks + unknown.tracks,
            sections = listOf(known, unknown),
            railMode = CollectionRailMode.SECTION_TITLES,
            routeId = "artist:unknown",
        )

        val presentation = collectionRailPresentation(selection)

        assertContentEquals(listOf("Z", "?"), presentation.rail.buckets)
        assertContentEquals(listOf(1, 3), presentation.rail.startIndexes)
    }

    @Test
    fun reconciliationKeepsFlatQueueAndSectionsInLockstep() {
        val alpha = CollectionSection("Alpha", listOf(track("a1", "One"), track("a2", "Two")))
        val beta = CollectionSection("Beta", listOf(track("b", "Three")))
        val selection = CollectionSelection(
            title = "Artist",
            subtitle = null,
            artworkUri = null,
            tracks = alpha.tracks + beta.tracks,
            sections = listOf(alpha, beta),
            railMode = CollectionRailMode.SECTION_TITLES,
            routeId = "artist:reconcile",
        )

        val twoSections = selection.filterTracksForCollection { it.id.value != "a2" }!!
        assertContentEquals(listOf("a1", "b"), twoSections.tracks.map { it.id.value })
        assertContentEquals(
            twoSections.tracks,
            twoSections.sections.orEmpty().flatMap { it.tracks },
        )
        assertEquals(CollectionRailMode.SECTION_TITLES, twoSections.railMode)

        val collapsed = selection.filterTracksForCollection { it.id.value == "b" }!!
        assertNull(collapsed.sections)
        assertContentEquals(listOf("b"), collapsed.tracks.map { it.id.value })
        assertEquals(CollectionRailMode.TRACK_TITLES, collapsed.railMode)
        assertNull(selection.filterTracksForCollection { false })
    }

    @Test
    fun manualCollectionOrderNeverGetsAnImplicitAlphabetRail() {
        val presentation = collectionRailPresentation(
            CollectionSelection(
                title = "Playlist",
                subtitle = null,
                artworkUri = null,
                tracks = listOf(track("z", "Zulu"), track("a", "Alpha")),
            ),
        )

        assertEquals(emptyList(), presentation.rail.buckets)
        assertEquals(emptyList(), presentation.artworkKeys)
    }
}
