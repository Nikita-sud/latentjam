/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowsePagesTest {
    @Test
    fun statisticsCanBeTheOnlyPageAndRemainSelectedAfterReordering() {
        val statistics = PageLayout().withPageEnabled(StartPage.STATISTICS, true)
        val reordered = statistics.movePage(StartPage.STATISTICS, Int.MIN_VALUE)

        assertEquals(
            StartPage.STATISTICS,
            resolveActiveBrowsePage(reordered.visiblePages, StartPage.STATISTICS.name, StartPage.TRACKS),
        )
        assertEquals(
            StartPage.STATISTICS,
            resolveActiveBrowsePage(listOf(StartPage.STATISTICS), null, StartPage.STATISTICS),
        )
        assertEquals(
            StartPage.TRACKS,
            resolveActiveBrowsePage(PageLayout().visiblePages, StartPage.STATISTICS.name, StartPage.TRACKS),
        )
    }

    @Test
    fun reorderingAndHidingOtherPagesKeepTheCurrentDestination() {
        val layouts = listOf(
            listOf(StartPage.TRACKS, StartPage.FOR_YOU, StartPage.ALBUMS),
            listOf(StartPage.ALBUMS, StartPage.TRACKS, StartPage.FOR_YOU),
            listOf(StartPage.ALBUMS),
        )
        for (pages in layouts) {
            assertEquals(
                StartPage.ALBUMS,
                resolveActiveBrowsePage(pages, StartPage.ALBUMS.name, StartPage.TRACKS),
            )
        }
    }

    @Test
    fun hidingTheCurrentPageReturnsToTheEnabledStartPage() {
        assertEquals(
            StartPage.TRACKS,
            resolveActiveBrowsePage(
                listOf(StartPage.ALBUMS, StartPage.TRACKS),
                StartPage.MAP.name,
                StartPage.TRACKS,
            ),
        )
    }

    @Test
    fun hidingBothCurrentAndStartPagesUsesTheFirstEnabledPage() {
        assertEquals(
            StartPage.ARTISTS,
            resolveActiveBrowsePage(
                listOf(StartPage.ARTISTS, StartPage.PLAYLISTS),
                StartPage.TRACKS.name,
                StartPage.TRACKS,
            ),
        )
    }

    @Test
    fun unknownRestoredPageDoesNotReinterpretAnOldPosition() {
        assertEquals(
            StartPage.FOLDERS,
            resolveActiveBrowsePage(listOf(StartPage.FOLDERS), "retired_page", StartPage.MAP),
        )
    }
}
