/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageReorderTest {
    private val layout = PageLayout()
    private val unevenRows = listOf(
        PageReorderItem(StartPage.FOR_YOU, 0, 56),
        PageReorderItem(StartPage.MAP, 56, 160),
        PageReorderItem(StartPage.PLAYLISTS, 216, 56),
    )

    @Test
    fun `a tall text row moves when its center reaches the next slot`() {
        assertEquals(layout, layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, 50f))
        val down = layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, 60f)
        val up = layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, -60f)
        assertEquals(listOf(StartPage.FOR_YOU, StartPage.PLAYLISTS, StartPage.MAP), down.order.take(3))
        assertEquals(listOf(StartPage.MAP, StartPage.FOR_YOU, StartPage.PLAYLISTS), up.order.take(3))
    }

    @Test
    fun `equal distance to slots follows the drag direction symmetrically`() {
        val down = layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, 54f)
        val up = layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, -54f)
        assertEquals(2, down.order.indexOf(StartPage.MAP))
        assertEquals(0, up.order.indexOf(StartPage.MAP))
    }

    @Test
    fun `dragging beyond the viewport only chooses a visible destination`() {
        val visible = listOf(
            PageReorderItem(StartPage.PLAYLISTS, -20, 56),
            PageReorderItem(StartPage.TRACKS, 36, 56),
            PageReorderItem(StartPage.ALBUMS, 92, 56),
        )
        val up = layout.reorderPageAfterDrag(StartPage.TRACKS, layout.order, visible, -10_000f)
        val down = layout.reorderPageAfterDrag(StartPage.TRACKS, layout.order, visible, 10_000f)
        assertEquals(2, up.order.indexOf(StartPage.TRACKS))
        assertEquals(4, down.order.indexOf(StartPage.TRACKS))
    }

    @Test
    fun `a changed order cancels an old drop without overwriting it`() {
        val newer = layout.movePage(StartPage.FOLDERS, Int.MIN_VALUE)
        assertEquals(newer, newer.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, 60f))
    }

    @Test
    fun `a visibility change during the drag survives the move`() {
        val newer = layout.withPageEnabled(StartPage.MAP, true)
        val moved = newer.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, 60f)
        assertEquals(newer.hiddenPages, moved.hiddenPages)
        assertTrue(StartPage.MAP in moved.visiblePages)
        assertEquals(2, moved.order.indexOf(StartPage.MAP))
    }

    @Test
    fun `unmeasured or invalid gestures do not move a page`() {
        assertEquals(layout, layout.reorderPageAfterDrag(StartPage.MAP, layout.order, emptyList(), 60f))
        assertEquals(layout, layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, Float.NaN))
        assertEquals(layout, layout.reorderPageAfterDrag(StartPage.MAP, layout.order, unevenRows, Float.POSITIVE_INFINITY))
    }
}
