/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cover handed to the media session never exceeds what the platform itself keeps (issue #37). */
class SessionCoverLimitTest {
    @Test
    fun aPlatformConfiguredBelowMedia3sFloorDecidesTheSize() {
        // Media3 allows at least 320 dp (880 px at 2.75x); this system keeps 256 dp (704 px) and would scale the cover.
        assertEquals(704, sessionCoverLimit(media3Limit = 880, platformLimit = 704))
    }

    @Test
    fun theUsualSystemKeepsMedia3sLimit() {
        assertEquals(880, sessionCoverLimit(media3Limit = 880, platformLimit = 880))
        // A system that keeps more than Media3 asks for still gets Media3's size: the decode stays bounded.
        assertEquals(880, sessionCoverLimit(media3Limit = 880, platformLimit = 1280))
    }

    @Test
    fun aSystemThatDoesNotSayLeavesMedia3sLimit() {
        assertEquals(880, sessionCoverLimit(media3Limit = 880, platformLimit = 0))
        assertEquals(880, sessionCoverLimit(media3Limit = 880, platformLimit = -1))
    }
}
