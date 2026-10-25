/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSDate
import platform.Foundation.NSTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals

/** The arithmetic [localTimePoint] gives exactly what the calendar it replaced gave. */
class IosLocalTimePointTest {

    private fun calendarPoint(epochMs: Long): LocalTimePoint {
        val date = NSDate(timeIntervalSinceReferenceDate = epochMs / 1000.0 - NSTimeIntervalSince1970)
        val calendar = NSCalendar.currentCalendar
        val zoneOffsetMs = calendar.timeZone.secondsFromGMTForDate(date) * 1000L
        return LocalTimePoint(
            epochDay = (epochMs + zoneOffsetMs).floorDiv(86_400_000L),
            hourOfDay = calendar.component(NSCalendarUnitHour, fromDate = date).toInt(),
        )
    }

    @Test
    fun everyHourOfTwoYearsMatchesTheCalendar() {
        // 2024 and 2025 hour by hour, plus a quarter past each: both DST changes of the local zone.
        val start = 1_704_067_200_000L
        val hour = 3_600_000L
        var instant = start
        while (instant < start + 731 * 24 * hour) {
            assertEquals(calendarPoint(instant), localTimePoint(instant), "at $instant")
            assertEquals(calendarPoint(instant + 15 * 60_000L), localTimePoint(instant + 15 * 60_000L))
            instant += hour
        }
    }

    @Test
    fun oldAndOddInstantsMatchTheCalendar() {
        for (instant in listOf(0L, 1L, -1L, 86_399_999L, 946_684_799_999L, 1_234_567_890_123L, 4_102_444_800_000L)) {
            assertEquals(calendarPoint(instant), localTimePoint(instant), "at $instant")
        }
    }
}
