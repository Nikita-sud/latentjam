/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeamCoverageIosTest {
    @Test
    fun anInterludeShorterThanTheTrailItMustSoundOverIsMetAtTheFilesOwnEdges() {
        // 20 s of trailing silence, then a 25 s interlude: the overlap is a third of it.
        val anchors = SeamAnchors(outgoingEndMs = 180_000L, incomingStartMs = 0L)
        val overlapMs = overlapLengthMs(12_000L, anchors.outgoingEndMs, 25_000L)
        assertTrue(overlapOutrunsIncoming(anchors, outgoingDurationMs = 200_000L, incomingMs = 25_000L, overlapMs = overlapMs))
    }

    @Test
    fun aLeadingSilenceCountsAgainstTheIncomingTrack() {
        val anchors = SeamAnchors(outgoingEndMs = 195_000L, incomingStartMs = 4_000L)
        assertTrue(overlapOutrunsIncoming(anchors, outgoingDurationMs = 200_000L, incomingMs = 10_000L, overlapMs = 2_000L))
        assertFalse(overlapOutrunsIncoming(anchors, outgoingDurationMs = 200_000L, incomingMs = 12_000L, overlapMs = 2_000L))
    }

    @Test
    fun anOrdinaryTrackCoversItsOverlapAndTheTrail() {
        val anchors = SeamAnchors(outgoingEndMs = 195_000L, incomingStartMs = 500L)
        val overlapMs = overlapLengthMs(12_000L, anchors.outgoingEndMs, 240_000L - 500L)
        assertFalse(overlapOutrunsIncoming(anchors, outgoingDurationMs = 200_000L, incomingMs = 240_000L, overlapMs = overlapMs))
    }

    @Test
    fun aSeamAtTheFilesOwnEdgesAlwaysFitsItsThirdOfTheIncomingTrack() {
        val anchors = SeamAnchors(outgoingEndMs = 200_000L, incomingStartMs = 0L)
        val overlapMs = overlapLengthMs(12_000L, 200_000L, 7_000L)
        assertFalse(overlapOutrunsIncoming(anchors, outgoingDurationMs = 200_000L, incomingMs = 7_000L, overlapMs = overlapMs))
    }
}
