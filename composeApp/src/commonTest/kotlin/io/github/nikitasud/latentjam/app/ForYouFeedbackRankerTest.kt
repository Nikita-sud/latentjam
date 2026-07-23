/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ForYouFeedbackRankerTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val quiet = 90 * day

    @Test
    fun `completed listens beat skips while neutral starts cast no vote`() {
        val completed = stats(plays = 8, completions = 6, skips = 0)
        val skipped = stats(plays = 8, completions = 0, skips = 6)
        val neutral = stats(plays = 100, completions = 0, skips = 0)

        assertTrue(
            ForYouFeedbackRanker.satisfaction(completed) >
                ForYouFeedbackRanker.satisfaction(skipped),
        )
        assertEquals(
            ForYouFeedbackRanker.satisfaction(null),
            ForYouFeedbackRanker.satisfaction(neutral),
        )
    }

    @Test
    fun `raw plays and cluster size cannot dominate world quality`() {
        val largeNeutral = (1..40).map { track("neutral-$it") }
        val focusedCompleted = (1..4).map { track("completed-$it") }
        val neutralStats = largeNeutral.associate { track ->
            track.id to stats(plays = 100, completions = 0, skips = 0)
        }
        val completedStats = focusedCompleted.associate { track ->
            track.id to stats(plays = 3, completions = 3, skips = 0)
        }
        val large = LibraryWorld("Large neutral", largeNeutral)
        val focused = LibraryWorld("Focused completed", focusedCompleted)

        val ranked = ForYouFeedbackRanker.rankWorlds(
            worlds = listOf(large, focused),
            stats = neutralStats + completedStats,
            nowMs = now,
            quietMs = quiet,
        )

        assertEquals(listOf(focused, large), ranked)
    }

    @Test
    fun `no-history ordering is deterministic and preserves centrality and world order`() {
        val firstTracks = (1..12).map { track("first-$it") }
        val secondTracks = (1..4).map { track("second-$it") }
        val worlds = listOf(
            LibraryWorld("First", firstTracks),
            LibraryWorld("Second", secondTracks),
        )

        assertEquals(
            worlds,
            ForYouFeedbackRanker.rankWorlds(worlds, emptyMap(), now, quiet),
        )
        assertEquals(
            worlds,
            ForYouFeedbackRanker.rankWorlds(worlds, emptyMap(), now + day, quiet),
        )
        assertEquals(
            firstTracks,
            ForYouFeedbackRanker.rankTracks(firstTracks, emptyMap(), now, quiet),
        )
    }

    private fun track(id: String) = TrackDescriptor(
        id = TrackId(id),
        title = id,
        artist = id,
    )

    private fun stats(
        plays: Int,
        completions: Int,
        skips: Int,
    ) = TrackStats(
        plays = plays,
        completions = completions,
        skips = skips,
        totalPlayedMs = 0,
        lastPlayedAtMs = now - day,
    )
}
