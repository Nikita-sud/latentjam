/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerControlsFitTest {
    // The title, the artist, the source chip, a gap, the seek bar, the transport, a gap, next up, a gap.
    private val heights = listOf(32, 24, 28, 8, 62, 56, 12, 36, 6)
    private val parts = listOf(
        null, PlayerControlsPart.ARTIST, PlayerControlsPart.SOURCE, PlayerControlsPart.GAPS, null, null,
        PlayerControlsPart.NEXT_UP, PlayerControlsPart.NEXT_UP, PlayerControlsPart.GAPS,
    )
    private val all = List(9) { true }
    private fun without(vararg rows: Int) = all.mapIndexed { row, _ -> row !in rows }

    private fun shown(available: Int) = fittedPlayerControls(heights, parts, available)

    @Test
    fun aWindowTallEnoughShowsEveryRow() {
        assertEquals(all, shown(heights.sum()))
        assertEquals(all, shown(1_000))
    }

    @Test
    fun aShorterWindowGivesUpWhatComesNextFirst() {
        // One pixel short of everything: the next-up row goes with the gap that set it off, nothing else.
        assertEquals(without(6, 7), shown(heights.sum() - 1))
        assertEquals(without(6, 7), shown(heights.sum() - 48))
    }

    @Test
    fun thenTheSourceThenTheGapsAndTheArtistLast() {
        val withoutNextUp = heights.sum() - 48
        assertEquals(without(6, 7, 2), shown(withoutNextUp - 1))
        val withoutSource = withoutNextUp - 28
        assertEquals(without(6, 7, 2), shown(withoutSource))
        assertEquals(without(6, 7, 2, 3, 8), shown(withoutSource - 1))
        val withoutGaps = withoutSource - 14
        assertEquals(without(6, 7, 2, 3, 8), shown(withoutGaps))
        assertEquals(without(6, 7, 2, 3, 8, 1), shown(withoutGaps - 1))
    }

    @Test
    fun theTitleTheSeekBarAndTheTransportAreNeverLeftOut() {
        // Nothing fits: the rows that must stay are all there, and the caller scrolls.
        assertEquals(without(6, 7, 2, 3, 8, 1), shown(10))
        assertEquals(without(6, 7, 2, 3, 8, 1), shown(0))
    }

    @Test
    fun aCoverAboveTheControlsGoesBeforeAnyRowOfThem() {
        // A cover of 180 and its gap above the nine rows, in a window that has room for the rows alone.
        val column = listOf(180, 10) + heights
        val columnParts = listOf(PlayerControlsPart.COVER, PlayerControlsPart.COVER) + parts
        val shown = fittedPlayerControls(column, columnParts, heights.sum())
        assertEquals(listOf(false, false) + all, shown)
        // With room for everything the cover stays.
        assertEquals(List(11) { true }, fittedPlayerControls(column, columnParts, column.sum()))
    }

    @Test
    fun rowsWithNothingToGiveUpAreAllShownWhateverTheWindow() {
        assertEquals(listOf(true, true), fittedPlayerControls(listOf(40, 40), listOf(null, null), 10))
        assertEquals(emptyList(), fittedPlayerControls(emptyList(), emptyList(), 10))
    }
}
