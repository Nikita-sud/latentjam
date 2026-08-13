/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MetadataFallbackQueueTest {

    @Test
    fun `cold audio index uses trusted text similarity instead of random order`() {
        val seed = track("seed", "Seed Artist", "Rock")
        val rock = track("rock", "Other Artist", "Rock")
        val dance = track("dance", "Dance Artist", "Dance")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(seed.id, floatArrayOf(1f, 0f, 0f))
            upsert(rock.id, floatArrayOf(0.98f, 0.02f, 0f))
            upsert(dance.id, floatArrayOf(0f, 1f, 0f))
        }

        val queue = MetadataFallbackQueue.build(seed, listOf(dance, rock), 2, index)

        assertEquals(listOf(rock.id, dance.id), queue)
    }

    @Test
    fun `missing trusted seed metadata abstains instead of fabricating a smart queue`() {
        val seed = track("seed", "Seed Artist", null)
        val candidate = track("candidate", "Other Artist", "Rock")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(candidate.id, floatArrayOf(1f, 0f, 0f))
        }

        assertTrue(MetadataFallbackQueue.build(seed, listOf(candidate), 12, index).isEmpty())
    }

    @Test
    fun `artist spacing is retained in metadata-only queues`() {
        val seed = track("seed", "Seed Artist", "Pop")
        val sameArtist = track("same", "Seed Artist", "Pop")
        val other = track("other", "Other Artist", "Pop")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(seed.id, floatArrayOf(1f, 0f, 0f))
            upsert(sameArtist.id, floatArrayOf(1f, 0f, 0f))
            upsert(other.id, floatArrayOf(0.9f, 0.1f, 0f))
        }

        val queue = MetadataFallbackQueue.build(seed, listOf(sameArtist, other), 2, index)

        assertEquals(other.id, queue.first())
    }

    @Test
    fun `a fresh equally relevant track leads one heard today`() {
        val seed = track("seed", "Seed Artist", "Rock")
        val recent = track("recent", "Recent Artist", "Rock")
        val fresh = track("fresh", "Fresh Artist", "Rock")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(seed.id, floatArrayOf(1f, 0f, 0f))
            upsert(recent.id, floatArrayOf(1f, 0f, 0f))
            upsert(fresh.id, floatArrayOf(1f, 0f, 0f))
        }
        val now = 1_000_000_000L
        val history = listOf(
            historyEvent(recent.id, now - 60_000),
            historyEvent(seed.id, now),
        )

        val queue = MetadataFallbackQueue.build(
            seed,
            listOf(recent, fresh),
            2,
            index,
            history,
        )

        assertEquals(fresh.id, queue.first())
    }

    @Test
    fun `a marked companion wins a metadata tie`() {
        val seed = track("seed", "Seed Artist", "Rock")
        val plain = track("plain", "Plain Artist", "Rock")
        val companion = track("companion", "Companion Artist", "Rock")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(seed.id, floatArrayOf(1f, 0f, 0f))
            upsert(plain.id, floatArrayOf(1f, 0f, 0f))
            upsert(companion.id, floatArrayOf(1f, 0f, 0f))
        }

        val queue = MetadataFallbackQueue.build(
            seed = seed,
            library = listOf(plain, companion),
            length = 1,
            textIndex = index,
            companionGroups = listOf(setOf(seed.id, companion.id)),
        )

        assertEquals(listOf(companion.id), queue)
    }

    @Test
    fun `metadata quotas fairly rotate distinct groups and collapse duplicate membership`() {
        val seed = track("seed", "Seed Artist", "Rock")
        val ordinary = (0 until 6).map { row ->
            track("ordinary-$row", "Ordinary Artist $row", "Rock")
        }
        val firstGroup = track("group-a", "Group A Artist", "Dance")
        val secondGroup = track("group-b", "Group B Artist", "Jazz")
        val index = InMemoryVectorIndex(dim = 3).apply {
            upsert(seed.id, floatArrayOf(1f, 0f, 0f))
            ordinary.forEach { upsert(it.id, floatArrayOf(1f, 0f, 0f)) }
            upsert(firstGroup.id, floatArrayOf(0f, 1f, 0f))
            upsert(secondGroup.id, floatArrayOf(0f, 0f, 1f))
        }
        val groupA = setOf(seed.id, firstGroup.id)
        val groupB = setOf(seed.id, secondGroup.id)

        val queue = MetadataFallbackQueue.build(
            seed = seed,
            library = ordinary + firstGroup + secondGroup,
            length = 6,
            textIndex = index,
            // Identical playlists are legal. They express one membership preference, not two
            // quota votes that may starve a different marked playlist.
            companionGroups = listOf(groupA, groupA.toSet(), groupB),
        )

        assertEquals(firstGroup.id, queue[2])
        assertEquals(secondGroup.id, queue[5])
    }

    private fun historyEvent(id: TrackId, at: Long) = SmartHistoryEvent(
        trackId = id,
        startedAtMs = at,
        playedFraction = 1f,
        completed = true,
        skipped = false,
    )

    private fun track(id: String, artist: String, genre: String?): TrackDescriptor =
        TrackDescriptor(
            id = TrackId(id),
            title = "Title $id",
            artist = artist,
            genre = genre,
            year = 2020,
        )
}
