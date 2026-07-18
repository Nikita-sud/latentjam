/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
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
        playlists: List<Playlist> = emptyList(),
    ) = ForYouBuilder.build(library, stats, events, now, playlists = playlists)

    private fun track(id: String, artist: String, album: String) =
        TrackDescriptor(id = TrackId(id), title = "T$id", artist = artist, album = album)

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
        val section = result.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
        assertEquals(listOf(second.id), section.cards.map { it.track.id })
        assertEquals(ForYouCaption.PlayedBefore(6), section.cards.first().caption)
    }

    @Test
    fun `a track played last month is not forgotten`() {
        val recent = track("1")
        val result = sections(
            library = listOf(recent),
            stats = mapOf(recent.id to stats(plays = 10, last = now - 30 * day)),
        )
        // 30 days is an ordinary gap; surfacing it would claim an absence the listener never felt.
        assertTrue(result.none { it.kind == ForYouSectionKind.WORTH_REVISITING })
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
        assertTrue(result.none { it.kind == ForYouSectionKind.WORTH_REVISITING })
    }

    @Test
    fun `one artist cannot own a row`() {
        val library = (1..6).map { track("$it", artist = "Same") }
        val result = sections(
            library = library,
            stats = library.associate { it.id to stats(plays = 5, last = now - 200 * day) },
        )
        val section = result.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
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
        val found = result.sections
            .first { it.kind == ForYouSectionKind.FOUND_BY_SMART }
            .cards.map { it.track.id }
        val smartIds = library.take(4).map { it.id }
        assertTrue(found.all { it in smartIds }, "only SMART-completed tracks may appear: $found")
        assertTrue(library[4].id !in found, "a track completed under ordinary shuffle must not appear")
    }

    @Test
    fun `an empty history yields only what needs no history`() {
        val library = listOf(track("1"), track("2", artist = "B"))
        val result = sections(library = library)
        assertEquals(listOf(ForYouSectionKind.NEVER_PLAYED), result.map { it.kind })
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
        assertTrue(result.sections.none { it.kind == ForYouSectionKind.CONTINUE })
        assertEquals(null, result.hero?.resumeAtMs)
    }

    @Test
    fun `the hero falls back to a forgotten favourite, then to something unheard`() {
        val loved = track("1")
        val withHistory = page(
            library = listOf(loved),
            stats = mapOf(loved.id to stats(plays = 7, last = now - 200 * day)),
        )
        assertEquals(ForYouKicker.PlayedTimes(7), withHistory.hero?.kicker)

        val fresh = page(library = listOf(track("2", artist = "B")))
        assertEquals(ForYouKicker.NeverPlayed, fresh.hero?.kicker)
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
        val row = result.sections
            .first { it.kind == ForYouSectionKind.NEVER_PLAYED }
            .cards.map { it.track.id }
        // Whatever the hero claimed, what remains is still ordered newest first.
        assertEquals(row.sortedByDescending { id -> listOf(old, middle, new).first { it.id == id }.addedAtMs }, row)
    }

    @Test
    fun `a dormant playlist is offered as one card, not as its tracks`() {
        val members = (1..4).map { track("$it", artist = "Artist$it") }
        val result = page(
            library = members,
            stats = members.associate { it.id to stats(plays = 6, last = now - 200 * day) },
            playlists = listOf(Playlist(id = "p1", name = "Think", trackIds = members.map { it.id.value })),
        )
        val row = result.sections.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
        val collections = row.cards.mapNotNull { it.collection }
        assertEquals(listOf("Think"), collections.map { it.title })
        // The caption counts what actually went quiet, not the playlist's length: the hero claimed
        // one of the four before the row was built. It carries the number, not a rendered string,
        // so the UI can pick the right plural form for it.
        assertEquals(
            ForYouCaption.TrackCount(3),
            row.cards.first { it.collection != null }.caption,
        )
        // The tracks it absorbed must not also stand alone beneath it.
        val loose = row.cards.filter { it.collection == null }.map { it.track.id }
        assertTrue(loose.none { id -> members.any { it.id == id } }, "absorbed tracks reappeared: $loose")
    }

    @Test
    fun `two dormant tracks are coincidence, not a playlist worth offering`() {
        val members = (1..2).map { track("$it", artist = "Artist$it") }
        val result = page(
            library = members,
            stats = members.associate { it.id to stats(plays = 6, last = now - 200 * day) },
            playlists = listOf(Playlist(id = "p1", name = "Think", trackIds = members.map { it.id.value })),
        )
        val row = result.sections.firstOrNull { it.kind == ForYouSectionKind.WORTH_REVISITING }
        assertTrue(row?.cards?.all { it.collection == null } ?: true, "two tracks should not collapse")
    }

    @Test
    fun `an album collapses when enough of it went quiet`() {
        // Four: the hero claims one, and three must remain for the album to be worth offering.
        val members = (1..4).map { track("$it", artist = "A", album = "Gold") }
        val result = page(
            library = members,
            stats = members.associate { it.id to stats(plays = 6, last = now - 200 * day) },
        )
        val row = result.sections.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
        assertEquals(listOf("Gold"), row.cards.mapNotNull { it.collection }.map { it.title })
    }

    @Test
    fun `a world is one card, standing for the whole region`() {
        val members = (1..12).map { track("$it", artist = "Artist$it") }
        val result = ForYouBuilder.build(
            library = members,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Hard Rock", members)),
        )
        val row = result.sections.first { it.kind == ForYouSectionKind.WORLDS }
        assertEquals(1, row.cards.size)
        assertEquals("Hard Rock", row.cards.single().collection?.title)
        assertEquals(ForYouCaption.TrackCount(12), row.cards.single().caption)
    }

    @Test
    fun `the world the listener actually lives in comes first`() {
        val quiet = (1..5).map { track("q$it", artist = "Quiet$it") }
        val loved = (1..5).map { track("l$it", artist = "Loved$it") }
        val result = ForYouBuilder.build(
            library = quiet + loved,
            // The regions are found without reference to listening; which one leads is not.
            stats = loved.associate { it.id to stats(plays = 20, last = now - day) },
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Quiet", quiet), LibraryWorld("Loved", loved)),
        )
        val row = result.sections.first { it.kind == ForYouSectionKind.WORLDS }
        assertEquals(listOf("Loved", "Quiet"), row.cards.mapNotNull { it.collection?.title })
    }

    @Test
    fun `a world does not retire its members from the rows below it`() {
        val members = (1..6).map { track("$it", artist = "Artist$it") }
        val result = ForYouBuilder.build(
            library = members,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Disco", members)),
        )
        // A world's pool is the whole library, so consuming it would starve every later row for the
        // sake of a rule about the same album showing up three times.
        val neverPlayed = result.sections.first { it.kind == ForYouSectionKind.NEVER_PLAYED }
        assertTrue(neverPlayed.cards.isNotEmpty(), "the worlds row swallowed the library")
    }

    @Test
    fun `a world whose centre is already on the page shows the next track in, and starts there`() {
        val members = (1..6).map { track("$it", artist = "Artist$it") }
        val result = ForYouBuilder.build(
            library = members,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Disco", members)),
        )
        val heroId = result.hero?.track?.id
        assertEquals(members.first().id, heroId, "the fixture needs the medoid to be the hero")

        val card = result.sections.first { it.kind == ForYouSectionKind.WORLDS }.cards.single()
        // Not the medoid, because the hero already showed that cover — but still the most central
        // track left, taken from the ordering rather than chosen some other way.
        assertEquals(members[1].id, card.track.id)
        // And the list starts on the same record the card shows. A card that pictures one track and
        // plays another is the smallest possible way to look broken.
        assertEquals(card.track.id, card.collection?.tracks?.first()?.id)
        assertEquals(members.size, card.collection?.tracks?.size)
    }

    @Test
    fun `no worlds, no row`() {
        val result = page(library = listOf(track("1"), track("2", artist = "B")))
        assertTrue(result.sections.none { it.kind == ForYouSectionKind.WORLDS })
    }

    @Test
    fun `a playlist claims its tracks before the album does`() {
        // Same tracks belong to both; the playlist is deliberate, the shared album is incidental.
        val members = (1..4).map { track("$it", artist = "A", album = "Gold") }
        val result = page(
            library = members,
            stats = members.associate { it.id to stats(plays = 6, last = now - 200 * day) },
            playlists = listOf(Playlist(id = "p1", name = "Think", trackIds = members.map { it.id.value })),
        )
        val row = result.sections.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
        assertEquals(listOf("Think"), row.cards.mapNotNull { it.collection }.map { it.title })
    }
}
