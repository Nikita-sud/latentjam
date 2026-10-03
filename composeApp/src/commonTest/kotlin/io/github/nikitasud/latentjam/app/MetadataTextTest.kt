/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataTextTest {

    @Test
    fun metadataTakesItsDirectionFromItsOwnText() {
        for (layout in LayoutDirection.entries) {
            assertEquals(TextDirection.Content, TextStyle().forMetadata(layout).textDirection)
        }
    }

    @Test
    fun startAndEndStayOnTheLayoutsEdges() {
        // With Content direction, Start would follow the text: a Latin title on a Hebrew screen
        // would jump to the left edge. Metadata stays lined up with the screen instead.
        fun aligned(align: TextAlign, layout: LayoutDirection) =
            TextStyle(textAlign = align).forMetadata(layout).textAlign

        assertEquals(TextAlign.Right, aligned(TextAlign.Unspecified, LayoutDirection.Rtl))
        assertEquals(TextAlign.Right, aligned(TextAlign.Start, LayoutDirection.Rtl))
        assertEquals(TextAlign.Left, aligned(TextAlign.End, LayoutDirection.Rtl))
        assertEquals(TextAlign.Left, aligned(TextAlign.Unspecified, LayoutDirection.Ltr))
        assertEquals(TextAlign.Left, aligned(TextAlign.Start, LayoutDirection.Ltr))
        assertEquals(TextAlign.Right, aligned(TextAlign.End, LayoutDirection.Ltr))
        for (layout in LayoutDirection.entries) {
            assertEquals(TextAlign.Center, aligned(TextAlign.Center, layout))
            assertEquals(TextAlign.Justify, aligned(TextAlign.Justify, layout))
        }
    }

    @Test
    fun firstStrongCharacterDecidesTheReadingDirection() {
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("100 Rock Hits"))
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("Boney M."))
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("1-01 Dancing Queen.flac"))
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("Кино"))
        assertEquals(LayoutDirection.Rtl, firstStrongDirection("100 שירים"))
        assertEquals(LayoutDirection.Rtl, firstStrongDirection("(עומר אדם)"))
        // Arabic-Indic digits carry no direction of their own; the letters after them do.
        assertEquals(LayoutDirection.Rtl, firstStrongDirection("١٢٣ فيروز"))
        assertEquals(LayoutDirection.Rtl, firstStrongDirection("‏1999"))
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("‎1999"))
        assertNull(firstStrongDirection("1999"))
        assertNull(firstStrongDirection("— 21 —"))
        assertNull(firstStrongDirection(""))
    }

    @Test
    fun isolatedMetadataIsWrappedInFirstStrongIsolates() {
        assertEquals("⁨Boney M.⁩", isolate("Boney M."))
        assertEquals("", isolate(""))
        assertEquals(
            "⁨ABBA⁩ · ⁨1976⁩",
            joinIsolated(listOf("ABBA", "1976")),
        )
        assertEquals("⁨A⁩ — ⁨B⁩", joinIsolated(listOf("A", "B"), " — "))
    }
}
