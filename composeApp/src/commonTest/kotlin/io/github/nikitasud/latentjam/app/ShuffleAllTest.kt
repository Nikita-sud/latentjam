/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ShuffleAllTest {

    @Test
    fun everyPageThatLeadsIntoTheLibraryCarriesShuffleAll() {
        assertEquals(
            setOf(
                StartPage.FOR_YOU,
                StartPage.PLAYLISTS,
                StartPage.TRACKS,
                StartPage.ALBUMS,
                StartPage.ARTISTS,
                StartPage.GENRES,
                StartPage.FOLDERS,
            ),
            StartPage.entries.filter { it.showsShuffleAll() }.toSet(),
        )
    }

    @Test
    fun statisticsAndTheMapLeaveItOff() {
        assertEquals(false, StartPage.STATISTICS.showsShuffleAll())
        assertEquals(false, StartPage.MAP.showsShuffleAll())
    }
}
