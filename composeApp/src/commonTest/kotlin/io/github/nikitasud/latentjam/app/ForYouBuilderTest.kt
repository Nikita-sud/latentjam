/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection rules, pinned. These are the parts that decide what a listener is shown, and they
 * are easy to break silently — a wrong comparison produces a page that still looks plausible.
 */
class ForYouBuilderTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun track(id: String, artist: String = "A", added: Long = 0) =
        TrackDescriptor(id = TrackId(id), title = "T$id", artist = artist, addedAtMs = added)

    private fun stats(plays: Int, completions: Int = plays, skips: Int = 0, last: Long) =
        TrackStats(
            plays = plays,
            completions = completions,
            skips = skips,
            totalPlayedMs = 0,
            lastPlayedAtMs = last,
        )

    private fun sections(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats> = emptyMap(),
        events: List<ListenEvent> = emptyList(),
    ) = ForYouBuilder.build(library, stats, events, now)

    @Test
    fun `a loved track gone quiet is worth revisiting`() {
        val loved = track("1")
        val result = sections(
            library = listOf(loved),
            stats = mapOf(loved.id to stats(plays = 10, last = now - 120 * day)),
        )
        val section = result.first { it.id == "worth-revisiting" }
        assertEquals(listOf(loved.id), section.cards.map { it.track.id })
        assertEquals("10× before", section.cards.first().reason)
    }

    @Test
    fun `a track played last month is not forgotten`() {
        val recent = track("1")
        val result = sections(
            library = listOf(recent),
            stats = mapOf(recent.id to stats(plays = 10, last = now - 30 * day)),
        )
        // 30 days is an ordinary gap; surfacing it would claim an absence the listener never felt.
        assertTrue(result.none { it.id == "worth-revisiting" })
    }

    @Test
    fun `a track skipped more than finished is not resurfaced`() {
        val disliked = track("1")
        val result = sections(
            library = listOf(disliked),
            stats = mapOf(
                disliked.id to stats(plays = 10, completions = 2, skips = 8, last = now - 200 * day),
            ),
        )
        assertTrue(result.none { it.id == "worth-revisiting" })
    }

    @Test
    fun `one artist cannot own a row`() {
        val library = (1..6).map { track("$it", artist = "Same") }
        val result = sections(
            library = library,
            stats = library.associate { it.id to stats(plays = 5, last = now - 200 * day) },
        )
        val section = result.first { it.id == "worth-revisiting" }
        assertEquals(ForYouBuilder.MAX_PER_ARTIST, section.cards.size)
    }

    @Test
    fun `a track shown in one section is not repeated in another`() {
        val shared = track("1")
        val result = sections(
            library = listOf(shared),
            stats = mapOf(shared.id to stats(plays = 5, last = now - 200 * day)),
            events = listOf(
                ListenEvent(
                    trackId = shared.id,
                    startedAtMs = now - 400 * day,
                    playedMs = 1000,
                    trackDurationMs = 1000,
                    completed = true,
                    skipped = false,
                    shuffleMode = "SMART",
                ),
            ),
        )
        val appearances = result.sumOf { section -> section.cards.count { it.track.id == shared.id } }
        assertEquals(1, appearances)
    }

    @Test
    fun `found by SMART only counts tracks played through in SMART mode`() {
        val library = (1..4).map { track("$it", artist = "Artist$it") }
        val events = library.mapIndexed { index, descriptor ->
            ListenEvent(
                trackId = descriptor.id,
                startedAtMs = now - index * day,
                playedMs = 1000,
                trackDurationMs = 1000,
                // Only the first three qualify: the last was completed under ordinary shuffle.
                completed = true,
                skipped = false,
                shuffleMode = if (index < 3) "SMART" else "ON",
            )
        }
        val section = sections(library = library, events = events).first { it.id == "found-by-smart" }
        assertEquals(3, section.cards.size)
    }

    @Test
    fun `an empty history yields only what needs no history`() {
        val library = listOf(track("1"), track("2", artist = "B"))
        val result = sections(library = library)
        assertEquals(listOf("never-played"), result.map { it.id })
    }

    @Test
    fun `never played is newest first`() {
        val old = track("1", artist = "A", added = 100)
        val new = track("2", artist = "B", added = 900)
        val section = sections(library = listOf(old, new)).first { it.id == "never-played" }
        assertEquals(listOf(new.id, old.id), section.cards.map { it.track.id })
    }
}
