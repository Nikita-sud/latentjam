/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseYearTest {

    @Test
    fun aReleaseDateGivesTheYearMusicFilesTheSongUnder() {
        val components = NSDateComponents().apply {
            year = 1985
            month = 6
            day = 15
            hour = 12
        }
        assertEquals(1985, releaseYear(NSCalendar.currentCalendar.dateFromComponents(components)))
    }

    @Test
    fun aMissingOrPlaceholderDateHasNoYear() {
        assertNull(releaseYear(null))
        // Year one of the calendar: what an unset date turns into.
        assertNull(releaseYear(NSDate.dateWithTimeIntervalSince1970(-62_135_596_800.0)))
    }
}
