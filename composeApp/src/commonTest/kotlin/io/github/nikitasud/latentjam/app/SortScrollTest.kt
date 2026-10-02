/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class SortScrollTest {

    @Test
    fun aChosenOrderScrollsOnceWhenTheListShowsIt() {
        val scroll = PendingSortScroll<String>()
        scroll.request("year")
        assertFalse(scroll.takeIfShown("title"))
        assertTrue(scroll.takeIfShown("year"))
        assertFalse(scroll.takeIfShown("year"))
    }

    @Test
    fun anOrderNobodyChoseNeverScrolls() {
        // A restored setting, a rescan or a return to the tab shows an order without a request.
        val scroll = PendingSortScroll<String>()
        assertFalse(scroll.takeIfShown("year"))
        assertFalse(scroll.takeIfShown(null))
    }

    @Test
    fun theLatestChoiceWins() {
        val scroll = PendingSortScroll<String>()
        scroll.request("year")
        scroll.request("artist")
        assertFalse(scroll.takeIfShown("year"))
        assertTrue(scroll.takeIfShown("artist"))
    }

    @Test
    fun aSmallLibraryIsResortedInTheTapsOwnFrame() {
        assertTrue(albumResortRunsInline(0))
        assertTrue(albumResortRunsInline(30))
        assertTrue(albumResortRunsInline(INLINE_ALBUM_RESORT_LIMIT))
        assertFalse(albumResortRunsInline(INLINE_ALBUM_RESORT_LIMIT + 1))
        assertFalse(albumResortRunsInline(5_000))
    }
}
