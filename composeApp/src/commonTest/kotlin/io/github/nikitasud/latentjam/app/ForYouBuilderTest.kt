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
    ) = ForYouBuilder.build(library, stats, events, now).sections

    private fun page(
        library: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats> = emptyMap(),
        events: List<ListenEvent> = emptyList(),
    ) = ForYouBuilder.build(library, stats, events, now)

    @Test
    fun `a loved track gone quiet is worth revisiting`() {
        // Two, because the hero claims the strongest candidate before the rows are built.
        val best = track("1")
        val second = track("2", artist = "B")
        val result = sections(
            library = listOf(best, second),
            stats = mapOf(
                best.id to stats(plays = 10, last = now - 120 * day),
                second.id to stats(plays = 6, last = now - 120 * day),
            ),
        )
        val section = result.first { it.id == "worth-revisiting" }
        assertEquals(listOf(second.id), section.cards.map { it.track.id })
        assertEquals("6× before", section.cards.first().reason)
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
        val result = page(
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
        val appearances = result.sections.sumOf { section ->
            section.cards.count { it.track.id == shared.id }
        } + if (result.hero?.track?.id == shared.id) 1 else 0
        assertEquals(1, appearances)
    }

    @Test
    fun `found by SMART only counts tracks played through in SMART mode`() {
        // Five: the hero claims one before the rows are built, and the row needs three to appear.
        val library = (1..5).map { track("$it", artist = "Artist$it") }
        val events = library.mapIndexed { index, descriptor ->
            ListenEvent(
                trackId = descriptor.id,
                startedAtMs = now - index * day,
                playedMs = 1000,
                trackDurationMs = 1000,
                // Only the first three qualify: the last was completed under ordinary shuffle.
                completed = true,
                skipped = false,
                shuffleMode = if (index < 4) "SMART" else "ON",
            )
        }
        // The hero takes one track first, so assert on which tracks qualify rather than a raw count.
        val result = page(library = library, events = events)
        val found = result.sections.first { it.id == "found-by-smart" }.cards.map { it.track.id }
        val smartIds = library.take(4).map { it.id }
        assertTrue(found.all { it in smartIds }, "only SMART-completed tracks may appear: $found")
        assertTrue(library[4].id !in found, "a track completed under ordinary shuffle must not appear")
    }

    @Test
    fun `an empty history yields only what needs no history`() {
        val library = listOf(track("1"), track("2", artist = "B"))
        val result = sections(library = library)
        assertEquals(listOf("never-played"), result.map { it.id })
    }

    @Test
    fun `an interrupted track becomes the hero, with its resume point`() {
        val abandoned = track("1")
        val result = page(
            library = listOf(abandoned),
            events = listOf(
                ListenEvent(
                    trackId = abandoned.id,
                    startedAtMs = now - day,
                    playedMs = 90_000,
                    trackDurationMs = 240_000,
                    completed = false,
                    skipped = false,
                ),
            ),
        )
        assertEquals(abandoned.id, result.hero?.track?.id)
        assertEquals(90_000L, result.hero?.resumeAtMs)
    }

    @Test
    fun `a track barely started is not offered as resumable`() {
        val auditioned = track("1")
        val result = page(
            library = listOf(auditioned),
            events = listOf(
                ListenEvent(
                    trackId = auditioned.id,
                    startedAtMs = now - day,
                    // Under the resume floor: this was an audition, not an interruption.
                    playedMs = 20_000,
                    trackDurationMs = 240_000,
                    completed = false,
                    skipped = true,
                ),
            ),
        )
        assertTrue(result.sections.none { it.id == "continue" })
        assertEquals(null, result.hero?.resumeAtMs)
    }

    @Test
    fun `the hero falls back to a forgotten favourite, then to something unheard`() {
        val loved = track("1")
        val withHistory = page(
            library = listOf(loved),
            stats = mapOf(loved.id to stats(plays = 7, last = now - 200 * day)),
        )
        assertEquals("You played this 7 times", withHistory.hero?.kicker)

        val fresh = page(library = listOf(track("2", artist = "B")))
        assertEquals("Never played", fresh.hero?.kicker)
    }

    @Test
    fun `the hero is not repeated in the rows below it`() {
        val loved = track("1")
        val other = track("2", artist = "B")
        val result = page(
            library = listOf(loved, other),
            stats = mapOf(
                loved.id to stats(plays = 9, last = now - 200 * day),
                other.id to stats(plays = 4, last = now - 200 * day),
            ),
        )
        val heroId = result.hero?.track?.id
        assertTrue(result.sections.none { s -> s.cards.any { it.track.id == heroId } })
    }

    @Test
    fun `never played is newest first`() {
        val old = track("1", artist = "A", added = 100)
        val middle = track("2", artist = "B", added = 500)
        val new = track("3", artist = "C", added = 900)
        val result = page(library = listOf(old, new, middle))
        val row = result.sections.first { it.id == "never-played" }.cards.map { it.track.id }
        // Whatever the hero claimed, what remains is still ordered newest first.
        assertEquals(row.sortedByDescending { id -> listOf(old, middle, new).first { it.id == id }.addedAtMs }, row)
    }
}
