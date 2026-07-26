/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The genre and date shapes real tags actually carry.
 *
 * Every case here is a form ID3 permits, not a hypothetical: the parenthesised
 * index is what pre-2000 encoders wrote, and the ISO date is what modern ones
 * write into TDRC.
 */
class TagValuesTest {

    @Test
    fun `plain genre text passes through`() {
        assertEquals("Rock", cleanGenre("Rock"))
        assertEquals("Drum & Bass", cleanGenre("Drum & Bass"))
        // A slash is part of the name, not a separator to split on.
        assertEquals("Rock/Pop", cleanGenre("Rock/Pop"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Rock", cleanGenre("  Rock  "))
    }

    @Test
    fun `a parenthesised index yields its refinement`() {
        assertEquals("Rock", cleanGenre("(17)Rock"))
        assertEquals("Blues", cleanGenre("(0)Blues"))
    }

    @Test
    fun `multiple indices still yield the refinement`() {
        // ID3v2_3 allows stacking genre references before the free text.
        assertEquals("Punk", cleanGenre("(17)(18)Punk"))
    }

    @Test
    fun `special codes are named`() {
        assertEquals("Remix", cleanGenre("(RX)"))
        assertEquals("Cover", cleanGenre("(CR)"))
    }

    @Test
    fun `a bare index is dropped rather than shown as a number`() {
        // The ID3v1 table is not carried, and "17" on a track row is worse
        // than no genre at all.
        assertNull(cleanGenre("17"))
        assertNull(cleanGenre("(17)"))
    }

    @Test
    fun `empty and blank genres are absent rather than empty strings`() {
        assertNull(cleanGenre(""))
        assertNull(cleanGenre("   "))
    }

    @Test
    fun `year parses from the shapes date tags take`() {
        assertEquals(1994, parseYear("1994"))
        assertEquals(1994, parseYear("1994-05-01"))
        assertEquals(1994, parseYear("1994/05/01"))
        assertEquals(1994, parseYear("1994 (Remastered)"))
        assertEquals(1994, parseYear("  1994  "))
    }

    @Test
    fun `implausible or partial years are rejected`() {
        assertNull(parseYear(""))
        assertNull(parseYear("94"))
        assertNull(parseYear("unknown"))
        // Five digits is not a year that lost a character; it is corrupt.
        assertNull(parseYear("20255"))
        assertNull(parseYear("-1994"))
    }
}
