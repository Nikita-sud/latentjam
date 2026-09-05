/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageLayoutTest {
    @Test
    fun `new and migrated preferences hide optional Map and Statistics pages`() {
        val defaults = pageLayoutFromPersisted(null)

        assertEquals(setOf(StartPage.MAP, StartPage.STATISTICS), defaults.hiddenPages)
        assertEquals(StartPage.entries.filterNot { it in defaults.hiddenPages }, defaults.visiblePages)
        assertEquals(StartPage.TRACKS, defaults.resolveStartPage(StartPage.TRACKS))
        assertEquals(StartPage.FOR_YOU, defaults.resolveStartPage(StartPage.MAP))
        assertEquals(StartPage.FOR_YOU, defaults.resolveStartPage(StartPage.STATISTICS))
        assertEquals(defaults, pageLayoutFromPersisted("broken"))
    }

    @Test
    fun `adding Statistics preserves old page identities and serialized values`() {
        assertEquals(
            listOf("for_you", "map", "playlists", "tracks", "albums", "artists", "genres", "folders"),
            StartPage.entries.take(8).map { it.persistedValue },
        )
        assertEquals(StartPage.STATISTICS, startPageFromPersisted("statistics"))
    }

    @Test
    fun `existing eight page layouts append Statistics hidden and retain explicit Map opt in`() {
        val legacyOrder = "folders,map,tracks,albums,artists,genres,playlists,for_you"
        val migrated = assertNotNull(decodePageLayout("LJPL1|$legacyOrder|genres,playlists"))

        assertEquals(legacyOrder, migrated.order.dropLast(1).joinToString(",") { it.persistedValue })
        assertEquals(StartPage.STATISTICS, migrated.order.last())
        assertEquals(setOf(StartPage.GENRES, StartPage.PLAYLISTS, StartPage.STATISTICS), migrated.hiddenPages)
        assertTrue(StartPage.MAP in migrated.visiblePages)
        assertEquals(StartPage.FOLDERS, migrated.resolveStartPage(StartPage.STATISTICS))
        assertEquals(migrated, decodePageLayout(encodePageLayout(migrated)))
    }

    @Test
    fun `Statistics can be enabled reordered selected at launch and restored without a format change`() {
        val customized = PageLayout()
            .withPageEnabled(StartPage.STATISTICS, true)
            .movePage(StartPage.STATISTICS, Int.MIN_VALUE)

        assertEquals(StartPage.STATISTICS, customized.visiblePages.first())
        assertEquals(StartPage.STATISTICS, customized.resolveStartPage(StartPage.STATISTICS))
        assertEquals(customized, decodePageLayout(encodePageLayout(customized)))
        assertTrue(encodePageLayout(customized).startsWith("LJPL1|"))
        val hidden = customized.withPageEnabled(StartPage.STATISTICS, false)
        assertEquals(StartPage.FOR_YOU, hidden.resolveStartPage(StartPage.STATISTICS))
        assertEquals(customized, hidden.withPageEnabled(StartPage.STATISTICS, true))
    }

    @Test
    fun `hiding and restoring a page keeps its chosen position`() {
        val ordered = PageLayout().movePage(StartPage.ALBUMS, -4)
        val hidden = ordered.withPageEnabled(StartPage.ALBUMS, false)

        assertEquals(StartPage.ALBUMS, hidden.order.first())
        assertFalse(StartPage.ALBUMS in hidden.visiblePages)
        assertEquals(ordered, hidden.withPageEnabled(StartPage.ALBUMS, true))
    }

    @Test
    fun `all visibility combinations keep a usable destination and protect the last page`() {
        repeat(1 shl StartPage.entries.size) { mask ->
            val hidden = StartPage.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val layout = PageLayout(hiddenPages = hidden).normalized()
            assertTrue(layout.visiblePages.isNotEmpty(), "No visible pages for mask $mask")
            assertEquals(layout, layout.normalized())
            StartPage.entries.forEach { page ->
                assertTrue(layout.resolveStartPage(page) in layout.visiblePages)
                assertTrue(layout.withPageEnabled(page, false).visiblePages.isNotEmpty())
            }
            layout.visiblePages.singleOrNull()?.let { last ->
                assertEquals(layout, layout.withPageEnabled(last, false))
            }
        }
    }

    @Test
    fun `partial duplicated order and all hidden corruption are repaired`() {
        val layout = PageLayout(
            order = listOf(StartPage.ALBUMS, StartPage.ALBUMS),
            hiddenPages = StartPage.entries.toSet(),
        ).normalized()

        assertEquals(StartPage.ALBUMS, layout.order.first())
        assertEquals(StartPage.entries.size, layout.order.size)
        assertEquals(StartPage.entries.toSet(), layout.order.toSet())
        assertEquals(listOf(StartPage.TRACKS), layout.visiblePages)
    }

    @Test
    fun `moving pages clamps at both ends without overflowing`() {
        val layout = PageLayout()
        assertEquals(StartPage.FOLDERS, layout.movePage(StartPage.FOLDERS, Int.MIN_VALUE).order.first())
        assertEquals(StartPage.FOR_YOU, layout.movePage(StartPage.FOR_YOU, Int.MAX_VALUE).order.last())
        assertEquals(layout, layout.movePage(StartPage.FOR_YOU, -1))
        assertEquals(layout, layout.movePage(StartPage.STATISTICS, 1))
        assertEquals(layout, layout.movePage(StartPage.MAP, 0))
    }

    @Test
    fun `codec preserves explicit optional page opt ins and custom order with any visibility selection`() {
        repeat(1 shl StartPage.entries.size) { mask ->
            val layout = PageLayout(
                order = StartPage.entries.reversed(),
                hiddenPages = StartPage.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet(),
            ).normalized()
            assertEquals(layout, decodePageLayout(encodePageLayout(layout)))
        }
    }

    @Test
    fun `codec ignores unknown names and restores omitted pages without duplicates`() {
        val layout = assertNotNull(decodePageLayout("LJPL1|albums,future_page,albums,tracks|future_page,tracks"))

        assertEquals(listOf(StartPage.ALBUMS, StartPage.TRACKS), layout.order.take(2))
        assertEquals(StartPage.entries.size, layout.order.size)
        assertTrue(StartPage.MAP in layout.hiddenPages)
        assertTrue(StartPage.STATISTICS in layout.hiddenPages)
        assertFalse(StartPage.TRACKS in layout.visiblePages)
        assertEquals(StartPage.ALBUMS, layout.resolveStartPage(StartPage.TRACKS))
    }

    @Test
    fun `codec rejects malformed unknown versions and oversized payloads`() {
        listOf("", "LJPL2|tracks|map", "LJPL1|tracks", "LJPL1|tracks|map|extra", "LJPL1||map", "LJPL1|unknown|", "LJPL1|" + "a".repeat(4_096) + "|").forEach { malformed ->
            assertNull(decodePageLayout(malformed))
            assertEquals(PageLayout(), pageLayoutFromPersisted(malformed))
        }
    }
}
