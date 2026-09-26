/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class TrackNumbersTest {

    @Test
    fun mediaStoreEncodesTheDiscInTheThousands() {
        assertEquals(TrackPosition(discNumber = null, trackNumber = 7), TrackNumbers.fromMediaStore(7))
        assertEquals(TrackPosition(discNumber = 1, trackNumber = 12), TrackNumbers.fromMediaStore(1012))
        assertEquals(TrackPosition(discNumber = 2, trackNumber = 5), TrackNumbers.fromMediaStore(2005))
        assertEquals(TrackPosition(discNumber = 2, trackNumber = null), TrackNumbers.fromMediaStore(2000))
    }

    @Test
    fun mediaStoreZeroOrNegativeMeansUntagged() {
        assertEquals(TrackPosition(), TrackNumbers.fromMediaStore(0))
        assertEquals(TrackPosition(), TrackNumbers.fromMediaStore(-1))
    }

    @Test
    fun tagValuesKeepTheirLeadingNumber() {
        assertEquals(3, TrackNumbers.parse("3"))
        assertEquals(3, TrackNumbers.parse("03"))
        assertEquals(3, TrackNumbers.parse(" 3/12"))
        assertEquals(3, TrackNumbers.parse("3 of 12"))
    }

    @Test
    fun tagValuesWithoutAUsableNumberAreUntagged() {
        assertNull(TrackNumbers.parse(null))
        assertNull(TrackNumbers.parse(""))
        assertNull(TrackNumbers.parse("0"))
        assertNull(TrackNumbers.parse("A1"))
        assertNull(TrackNumbers.parse("9999"))
        assertNull(TrackNumbers.parse("12345678901"))
    }
}
