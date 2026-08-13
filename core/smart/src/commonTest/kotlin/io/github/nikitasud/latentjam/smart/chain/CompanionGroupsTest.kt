/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The companion bonus is the listener's explicit "keep these together" — playlists they marked —
 * expressed as a fixed additive nudge, NOT a fence. It must decide ties toward a marked
 * companion, and it must lose to genuine acoustic distance: a playlist can say "prefer", never
 * "drag anything from anywhere".
 */
internal class CompanionGroupsTest {

    /**
     * Row 0 is the seed. Rows 1 and 2 sit at the SAME cosine to it (identical seed component,
     * orthogonal noise in different dimensions); rows 3..9 are far. Row 3 is far AND grouped.
     */
    private fun library(): List<SmartTrack> {
        fun track(row: Int, seedComponent: Float, noiseDim: Int): SmartTrack {
            val audio = FloatArray(PredictorRuntime.EMBEDDING_DIM)
            audio[0] = seedComponent
            audio[noiseDim] = sqrt(1f - seedComponent * seedComponent)
            return SmartTrack(
                id = TrackId(row.toString()),
                audio = audio,
                meta = TrackMeta("title$row", "artist$row", null, null, null),
            )
        }
        return buildList {
            add(track(0, 1f, 1))
            add(track(1, 0.9f, 100))
            add(track(2, 0.9f, 200))
            add(track(3, 0.2f, 300))
            for (row in 4 until 10) add(track(row, 0.1f, 300 + row))
        }
    }

    private fun firstPick(groups: List<Set<TrackId>>): TrackId {
        val snapshot = requireNotNull(SmartSnapshot.build(library()))
        val chain = SmartChain(snapshot, runtime = null, companionGroups = groups)
        val result = chain.build(seedId = TrackId("0"), length = 1, timeFeatures = FloatArray(5))
        return snapshot.tracks[result.rows.first()].id
    }

    @Test
    fun `no groups keeps the shipped order`() {
        // Rows 1 and 2 tie acoustically; the chain's deterministic scan keeps the earlier row.
        assertEquals(TrackId("1"), firstPick(emptyList()))
    }

    @Test
    fun `a marked companion wins an acoustic tie`() {
        assertEquals(
            TrackId("2"),
            firstPick(listOf(setOf(TrackId("0"), TrackId("2")))),
        )
    }

    @Test
    fun `the bonus never outweighs genuine acoustic distance`() {
        // Row 3 is grouped with the seed but acoustically remote; the nudge must not drag it in.
        assertEquals(
            TrackId("1"),
            firstPick(listOf(setOf(TrackId("0"), TrackId("3")))),
        )
    }
}
