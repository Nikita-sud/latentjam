/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class SongSortingTest {

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = null,
        addedAtMs: Long? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = artist,
        addedAtMs = addedAtMs,
    )

    @Test
    fun titleSortIgnoresLeadingPunctuation() {
        val sorted = SongSorting.sort(
            listOf(
                track("1", title = "Zebra"),
                track("2", title = "(I Just) Died in Your Arms"),
                track("3", title = "Africa"),
            ),
            SongSort.TITLE,
        )
        assertContentEquals(
            listOf("Africa", "(I Just) Died in Your Arms", "Zebra"),
            sorted.map { it.title },
        )
    }

    @Test
    fun artistSortIsCaseInsensitiveThenByTitle() {
        val sorted = SongSorting.sort(
            listOf(
                track("1", title = "B side", artist = "queen"),
                track("2", title = "A side", artist = "Queen"),
                track("3", title = "Only", artist = "ABBA"),
            ),
            SongSort.ARTIST,
        )
        assertContentEquals(listOf("3", "2", "1"), sorted.map { it.id.value })
    }

    @Test
    fun recentSortIsNewestFirstWithUnknownsLast() {
        val sorted = SongSorting.sort(
            listOf(
                track("old", addedAtMs = 1_000),
                track("unknown"),
                track("new", addedAtMs = 9_000),
            ),
            SongSort.RECENT,
        )
        assertContentEquals(listOf("new", "old", "unknown"), sorted.map { it.id.value })
    }

    @Test
    fun titleSortCanRunDescendingWithoutPromotingMissingTitles() {
        val sorted = SongSorting.sort(
            listOf(
                track("alpha", title = "Alpha"),
                track("unknown"),
                track("zebra", title = "Zebra"),
                track("middle", title = "Middle"),
            ),
            SongSort.TITLE,
            SongSortDirection.DESCENDING,
        )

        assertContentEquals(
            listOf("zebra", "middle", "alpha", "unknown"),
            sorted.map { it.id.value },
        )
    }

    @Test
    fun artistSortCanRunDescendingWithDescendingTitleTieBreaks() {
        val sorted = SongSorting.sort(
            listOf(
                track("queen-a", title = "A side", artist = "Queen"),
                track("unknown", title = "Tagged title"),
                track("abba", title = "Only", artist = "ABBA"),
                track("queen-b", title = "B side", artist = "queen"),
            ),
            SongSort.ARTIST,
            SongSortDirection.DESCENDING,
        )

        assertContentEquals(
            listOf("queen-b", "queen-a", "abba", "unknown"),
            sorted.map { it.id.value },
        )
    }

    @Test
    fun recentSortCanRunOldestFirstWithoutPromotingUnknownDates() {
        val sorted = SongSorting.sort(
            listOf(
                track("new", addedAtMs = 9_000),
                track("unknown"),
                track("old", addedAtMs = 1_000),
            ),
            SongSort.RECENT,
            SongSortDirection.ASCENDING,
        )

        assertContentEquals(listOf("old", "new", "unknown"), sorted.map { it.id.value })
    }

    @Test
    fun eachSortHasAListenerFriendlyDefaultDirection() {
        assertEquals(SongSortDirection.ASCENDING, SongSort.TITLE.defaultDirection)
        assertEquals(SongSortDirection.ASCENDING, SongSort.ARTIST.defaultDirection)
        assertEquals(SongSortDirection.DESCENDING, SongSort.RECENT.defaultDirection)
        assertEquals(SongSortDirection.DESCENDING, SongSortDirection.ASCENDING.toggled())
        assertEquals(SongSortDirection.ASCENDING, SongSortDirection.DESCENDING.toggled())
    }

    @Test
    fun sectionsBucketByInitialIncludingNonLatin() {
        val sections = SongSorting.sections(
            listOf(
                track("1", title = "Africa"),
                track("2", title = "Кафель"),
                track("3", title = "90"),
                track("4", title = "Anthem"),
            ),
            SongSort.TITLE,
        )
        assertEquals(listOf("#", "A", "К"), sections.map { it.bucket })
        assertEquals(2, sections.first { it.bucket == "A" }.tracks.size)
    }

    @Test
    fun unknownTitlesStayAtTheEndInTheirOwnBucket() {
        val sections = SongSorting.sections(
            listOf(
                track("unknown"),
                track("number", title = "7 rings"),
                track("alpha", title = "Alpha"),
                track("punctuation", title = "!!!"),
            ),
            SongSort.TITLE,
        )

        assertEquals(listOf("#", "A", "?"), sections.map { it.bucket })
        assertEquals(
            listOf("number", "alpha", "unknown", "punctuation"),
            sections.flatMap { section -> section.tracks.map { it.id.value } },
        )
    }

    @Test
    fun artistSectionsBucketByArtistInitial() {
        val sections = SongSorting.sections(
            listOf(track("1", title = "Zebra", artist = "ABBA")),
            SongSort.ARTIST,
        )
        assertEquals(listOf("A"), sections.map { it.bucket })
    }

    @Test
    fun descendingSectionsAndAlphabetRailBucketsShareTheSameOrder() {
        val sections = SongSorting.sections(
            listOf(
                track("a", title = "Alpha"),
                track("z", title = "Zebra"),
                track("m", title = "Middle"),
            ),
            SongSort.TITLE,
            SongSortDirection.DESCENDING,
        )

        assertEquals(listOf("Z", "M", "A"), sections.map { it.bucket })
    }

    @Test
    fun recentSectionsAreASingleUnlabeledRun() {
        val sections = SongSorting.sections(
            listOf(track("1", addedAtMs = 5), track("2", addedAtMs = 9)),
            SongSort.RECENT,
        )
        assertEquals(1, sections.size)
        assertEquals("", sections.single().bucket)
        assertEquals(2, sections.single().tracks.size)
        assertEquals(emptyList(), SongSorting.sections(emptyList(), SongSort.RECENT))
    }
}
