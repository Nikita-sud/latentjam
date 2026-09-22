/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackDeleteReportTest {
    @Test fun `partial removal keeps every outcome and requests feedback`() {
        val report = TrackDeleteReport(deleted = 1, denied = 2, failed = 3, cancelled = 4)
        assertEquals(10, report.total)
        assertTrue(report.isPartial)
        assertFalse(report.isSilent)
    }

    @Test fun `cancellation alone is silent but failure is not`() {
        assertTrue(TrackDeleteReport(cancelled = 3).isSilent)
        assertTrue(TrackDeleteReport().isSilent)
        assertFalse(TrackDeleteReport(denied = 1).isSilent)
        assertFalse(TrackDeleteReport(failed = 1).isSilent)
        assertFalse(TrackDeleteReport(deleted = 1).isSilent)
    }

    @Test fun `successful batch uses simple feedback while mixed outcomes retain detail`() {
        assertFalse(TrackDeleteReport(deleted = 5).needsSummary)
        assertTrue(TrackDeleteReport(deleted = 4, failed = 1).needsSummary)
        assertTrue(TrackDeleteReport(deleted = 4, cancelled = 1).needsSummary)
        assertTrue(TrackDeleteReport(denied = 2, failed = 1).needsSummary)
        assertFalse(TrackDeleteReport(denied = 1).needsSummary)
    }

    @Test fun `a fully successful request is not partial`() {
        assertFalse(TrackDeleteReport(deleted = 4).isPartial)
        assertFalse(TrackDeleteReport(failed = 4).isPartial)
    }
}
