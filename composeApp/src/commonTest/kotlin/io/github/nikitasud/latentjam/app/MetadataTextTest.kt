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
    fun supplementaryPlaneCharactersAreReadAsWholeCodePoints() {
        assertEquals(LayoutDirection.Rtl, firstStrongDirection("\uD802\uDD00 2020")) // Phoenician alf
        assertEquals(LayoutDirection.Ltr, firstStrongDirection("\uD83D\uDE00 abc")) // emoji, then Latin
        assertNull(firstStrongDirection("\uD83D\uDE00 2020"))
        assertEquals(LayoutDirection.Ltr, lastStrongDirection("שיר abc \uD83D\uDE00"))
        assertEquals(LayoutDirection.Rtl, lastStrongDirection("abc \uD802\uDD00."))
    }

    @Test
    fun aPieceAgainstTheContextIsEmbeddedAndFencedWithContextMarks() {
        // The review's case: a Hebrew album with a year inside an English line.
        assertEquals("$LRM${RLE}שירים 2020$PDF$LRM", bidiWrap("שירים 2020", LayoutDirection.Ltr))
        assertEquals("$RLM${LRE}Boney M.$PDF$RLM", bidiWrap("Boney M.", LayoutDirection.Rtl))
        assertEquals("$RLM${LRE}100 Rock Hits$PDF$RLM", bidiWrap("100 Rock Hits", LayoutDirection.Rtl))
        // No strong character counts as left-to-right, as in android.text.BidiFormatter.
        assertEquals("$RLM${LRE}2020$PDF$RLM", bidiWrap("2020", LayoutDirection.Rtl))
    }

    @Test
    fun aPieceAlongTheContextStaysBareUnlessItsEndLeansTheOtherWay() {
        assertEquals("Boney M.", bidiWrap("Boney M.", LayoutDirection.Ltr))
        assertEquals("שירים 2020", bidiWrap("שירים 2020", LayoutDirection.Rtl))
        assertEquals("2020", bidiWrap("2020", LayoutDirection.Ltr))
        // Starts left-to-right but ends in Hebrew: a mark keeps what follows from attaching.
        assertEquals("ABBA — שיר$LRM", bidiWrap("ABBA — שיר", LayoutDirection.Ltr))
        assertEquals("שיר — ABBA$RLM", bidiWrap("שיר — ABBA", LayoutDirection.Rtl))
        assertEquals("", bidiWrap("", LayoutDirection.Rtl))
    }

    @Test
    fun joinedFactsAreWrappedOneByOne() {
        assertEquals(
            "$RLM${LRE}ABBA$PDF$RLM · $RLM${LRE}1976$PDF$RLM",
            joinBidiWrapped(listOf("ABBA", "1976"), LayoutDirection.Rtl),
        )
        assertEquals(
            "Boney M. · $LRM${RLE}שירים 2020$PDF$LRM",
            joinBidiWrapped(listOf("Boney M.", "שירים 2020"), LayoutDirection.Ltr),
        )
        assertEquals("A — B", joinBidiWrapped(listOf("A", "B"), LayoutDirection.Ltr, " — "))
    }

    private companion object {
        const val LRM = "\u200E"
        const val RLM = "\u200F"
        const val LRE = "\u202A"
        const val RLE = "\u202B"
        const val PDF = "\u202C"
    }
}
