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
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldContent
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldNameSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
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
        // Four, because the hero claims the strongest candidate and the shelf needs three more to
        // establish a pattern rather than making a row from one isolated record.
        val tracks = (1..4).map { track("$it", artist = "Artist$it") }
        val result = sections(
            library = tracks,
            stats = tracks.mapIndexed { index, descriptor ->
                descriptor.id to stats(plays = 10 - index, last = now - 120 * day)
            }.toMap(),
        )
        val section = result.first { it.kind == ForYouSectionKind.WORTH_REVISITING }
        // The hero rotates through the strongest few day by day, so pin the shape rather than
        // one fixed winner: the shelf holds exactly the loved tracks the hero did not claim, in
        // completion order, each captioned with its own play count.
        val heroClaims = tracks.map { it.id }.toSet() - section.cards.map { it.track.id }.toSet()
        assertEquals(1, heroClaims.size)
        assertEquals(
            tracks.map { it.id }.filterNot { it in heroClaims },
            section.cards.map { it.track.id },
        )
        val firstShown = section.cards.first().track.id
        val expectedPlays = 10 - tracks.indexOfFirst { it.id == firstShown }
        assertEquals(ForYouCaption.PlayedBefore(expectedPlays), section.cards.first().caption)
    }

    @Test
    fun `worth revisiting waits for enough dormant evidence`() {
        val tracks = (1..3).map { track("$it", artist = "Artist$it") }
        val result = sections(
            library = tracks,
            stats = tracks.associate { it.id to stats(plays = 8, last = now - 120 * day) },
        )

        // The hero may confidently resurface one track, but two remaining records are not enough
        // to manufacture a whole shelf.
        assertTrue(result.none { it.kind == ForYouSectionKind.WORTH_REVISITING })
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
    fun `an empty history yields only what needs no history`() {
        val library = listOf(track("1"), track("2", artist = "B"))
        val result = sections(library = library)
        assertEquals(listOf(ForYouSectionKind.NEVER_PLAYED), result.map { it.kind })
    }

    @Test
    fun `an interrupted track becomes the hero with its resume point`() {
        val abandoned = track("1")
        val result = page(
            library = listOf(abandoned),
            events = listOf(
                ListenEvent(
                    trackId = abandoned.id,
                    startedAtMs = now - day,
                    playedMs = 90_000,
                    trackDurationMs = 600_000,
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
    fun `an ordinary song restarts instead of becoming continue listening`() {
        val song = track("1")
        val result = page(
            library = listOf(song),
            events = listOf(
                ListenEvent(
                    trackId = song.id,
                    startedAtMs = now - day,
                    playedMs = 90_000,
                    trackDurationMs = 240_000,
                    completed = false,
                    skipped = false,
                ),
            ),
        )
        assertTrue(result.sections.none { it.kind == ForYouSectionKind.CONTINUE })
        assertEquals(null, result.hero?.resumeAtMs)
    }

    @Test
    fun `the hero falls back to a forgotten favourite then to something unheard`() {
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
    fun `first open hero is the newest unheard track`() {
        val old = track("1", artist = "A", added = 100)
        val newest = track("2", artist = "B", added = 900)
        val middle = track("3", artist = "C", added = 500)

        val result = page(library = listOf(old, middle, newest))

        assertEquals(newest.id, result.hero?.track?.id)
        assertEquals(ForYouKicker.NeverPlayed, result.hero?.kicker)
    }

    @Test
    fun `a dormant playlist is offered as one card and not as its tracks`() {
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
    fun `two dormant tracks are coincidence and not a playlist worth offering`() {
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
    fun `a world is one card standing for the whole region`() {
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
    fun `a large region becomes a focused mix rather than a hundred track dump`() {
        val members = (1..120).map { track("$it", artist = "Artist$it") }
        val result = ForYouBuilder.build(
            library = members,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Focused", members)),
        )

        val card = result.sections.first { it.kind == ForYouSectionKind.WORLDS }.cards.single()
        assertEquals(ForYouBuilder.MIX_TRACK_LIMIT, card.collection?.tracks?.size)
        assertEquals(ForYouCaption.TrackCount(ForYouBuilder.MIX_TRACK_LIMIT), card.caption)
    }

    @Test
    fun `a genre mix balances its primary artists and keeps the cover first`() {
        val members = listOf(
            track("e1", artist = "Eminem"),
            track("e2", artist = "Eminem feat. Nate Dogg"),
            track("e3", artist = "Eminem featuring D-12"),
            track("e4", artist = "Eminem"),
            track("p1", artist = "2Pac"),
            track("b1", artist = "The Notorious B.I.G."),
            track("n1", artist = "Nas"),
            track("j1", artist = "Jay-Z"),
        )
        val result = ForYouBuilder.build(
            library = members,
            stats = members.associate { it.id to stats(plays = 1, last = now - day) },
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Rap", members)),
        )

        val tracks = result.sections
            .first { it.kind == ForYouSectionKind.WORLDS }
            .cards.single()
            .collection
            ?.tracks
            .orEmpty()
        assertEquals(members.first().id, tracks.first().id)
        assertEquals(3, tracks.count { it.artist?.startsWith("Eminem") == true })
        assertTrue(tracks.zipWithNext().none { (left, right) ->
            left.artist?.startsWith("Eminem") == true &&
                right.artist?.startsWith("Eminem") == true
        })
    }

    @Test
    fun `generic mix labels are localized and numbered without borrowing a track title`() {
        val first = (1..6).map { track("a$it", artist = "Artist A$it") }
        val second = (1..6).map { track("b$it", artist = "Artist B$it") }
        val result = ForYouBuilder.build(
            library = first + second,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(
                LibraryWorld("ignored title", first, LibraryWorldNameSource.GENERIC),
                LibraryWorld("another ignored title", second, LibraryWorldNameSource.GENERIC),
            ),
            discoveryMixLabel = "Микс открытий",
        )

        val names = result.sections.first { it.kind == ForYouSectionKind.WORLDS }
            .cards.mapNotNull { it.collection?.title }
        assertEquals(listOf("Микс открытий 1", "Микс открытий 2"), names)
    }

    @Test
    fun `novelty and effects are hidden by default and explicit opt in reveals localized titles`() {
        val novelty = (1..5).map { track("n$it", artist = "Creator N$it") }
        val effects = (1..5).map { track("e$it", artist = "Creator E$it") }
        val worlds = listOf(
            LibraryWorld(
                name = "Meme & Viral Audio",
                tracks = novelty,
                nameSource = LibraryWorldNameSource.SEMANTIC,
                content = LibraryWorldContent.NOVELTY,
                semanticTitle = LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO,
            ),
            LibraryWorld(
                name = "Sound Effects",
                tracks = effects,
                nameSource = LibraryWorldNameSource.SEMANTIC,
                content = LibraryWorldContent.SOUND_EFFECTS,
                semanticTitle = LibraryWorldSemanticTitle.SOUND_EFFECTS,
            ),
        )

        val hidden = ForYouBuilder.build(
            library = novelty + effects,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = worlds,
        )
        assertTrue(hidden.sections.none { it.kind == ForYouSectionKind.WORLDS })

        val visible = ForYouBuilder.build(
            library = novelty + effects,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = worlds,
            includeNoveltyMixes = true,
            semanticMixLabels = mapOf(
                LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO to "Мемы и вирусное аудио",
                LibraryWorldSemanticTitle.SOUND_EFFECTS to "Звуковые эффекты",
            ),
        )
        val titles = visible.sections
            .single { it.kind == ForYouSectionKind.WORLDS }
            .cards
            .mapNotNull { it.collection?.title }
            .toSet()

        assertEquals(setOf("Мемы и вирусное аудио", "Звуковые эффекты"), titles)
    }

    @Test
    fun `a trimmed world stays short instead of borrowing unrelated library tracks`() {
        val confidentMembers = (1..11).map { track("core$it", artist = "Core Artist $it") }
        val unrelated = (1..40).map { track("other$it", artist = "Other Artist $it") }
        val result = ForYouBuilder.build(
            library = confidentMembers + unrelated,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Coherent core", confidentMembers)),
        )

        val card = result.sections
            .single { it.kind == ForYouSectionKind.WORLDS }
            .cards
            .single()
        val mixTracks = card.collection?.tracks.orEmpty()

        assertEquals(11, mixTracks.size)
        assertEquals(ForYouCaption.TrackCount(11), card.caption)
        assertEquals(confidentMembers.map { it.id }.toSet(), mixTracks.map { it.id }.toSet())
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
    fun `a completed member leads an equally fresh skipped member`() {
        val hero = track("hero", artist = "Hero")
        val skipped = track("skipped", artist = "Skipped")
        val completed = track("completed", artist = "Completed")
        val result = ForYouBuilder.build(
            library = listOf(hero, skipped, completed),
            stats = mapOf(
                hero.id to stats(plays = 20, completions = 20, last = now - 200 * day),
                skipped.id to stats(plays = 8, completions = 0, skips = 8, last = now - day),
                completed.id to stats(plays = 8, completions = 8, skips = 0, last = now - day),
            ),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Feedback", listOf(skipped, completed))),
        )

        val card = result.sections.first { it.kind == ForYouSectionKind.WORLDS }.cards.single()
        assertEquals(completed.id, card.track.id)
        assertEquals(completed.id, card.collection?.tracks?.first()?.id)
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
    fun `a world whose centre is already on the page shows the next track in and starts there`() {
        val members = (1..6).map { track("$it", artist = "Artist$it") }
        val result = ForYouBuilder.build(
            library = members,
            stats = emptyMap(),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(LibraryWorld("Disco", members)),
        )
        val heroId = result.hero?.track?.id
        assertTrue(members.any { it.id == heroId }, "the fixture needs a member as the hero")

        val card = result.sections.first { it.kind == ForYouSectionKind.WORLDS }.cards.single()
        // Not the hero's track, because that cover is already on the page — but still the most
        // central track left, taken from the ordering rather than chosen some other way.
        assertEquals(members.first { it.id != heroId }.id, card.track.id)
        // And the list starts on the same record the card shows. A card that pictures one track and
        // plays another is the smallest possible way to look broken.
        assertEquals(card.track.id, card.collection?.tracks?.first()?.id)
        assertEquals(members.size, card.collection?.tracks?.size)
    }

    @Test
    fun `a mix prefers an unheard central track over one played this week`() {
        val hero = track("hero", artist = "Hero")
        val recent = track("recent", artist = "Recent")
        val unheard = track("unheard", artist = "Unheard")
        val older = track("older", artist = "Older")
        val world = listOf(recent, unheard, older)
        val result = ForYouBuilder.build(
            library = listOf(hero) + world,
            stats = mapOf(
                hero.id to stats(plays = 20, last = now - 200 * day),
                recent.id to stats(plays = 8, last = now - day),
            ),
            recentEvents = emptyList(),
            nowMs = now,
            excluded = setOf(older.id),
            worlds = listOf(LibraryWorld("Focused mix", world)),
        )

        val card = result.sections.first { it.kind == ForYouSectionKind.WORLDS }.cards.single()
        assertEquals(unheard.id, card.track.id)
        assertEquals(unheard.id, card.collection?.tracks?.first()?.id)
        assertTrue(
            card.collection?.tracks?.none { it.id == older.id } == true,
            "an excluded track remained playable inside the generated mix",
        )
    }

    @Test
    fun `no worlds means no row`() {
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

    @Test
    fun `a young log shrinks the quiet window so the shelf exists from week one`() {
        // The whole log is 20 days old; under a fixed 90-day window this listener would not see
        // the page's flagship section until month four of owning the app.
        val tracks = (1..4).map { track("$it", artist = "Artist$it") }
        val result = sections(
            library = tracks,
            stats = tracks.associate { it.id to stats(plays = 6, last = now - 12 * day) },
        )
        assertTrue(result.any { it.kind == ForYouSectionKind.WORTH_REVISITING })
    }

    @Test
    fun `the page rotates across days but holds still within one`() {
        val tracks = (1..30).map { track("$it", artist = "Artist$it", added = it.toLong()) }
        val sameDay = ForYouBuilder.build(tracks, emptyMap(), emptyList(), now + 60 * 60 * 1000L)
        val today = ForYouBuilder.build(tracks, emptyMap(), emptyList(), now)
        assertEquals(today, sameDay, "two openings on one day must agree")

        val tomorrow = ForYouBuilder.build(tracks, emptyMap(), emptyList(), now + day)
        assertTrue(today != tomorrow, "consecutive days must not show an identical page")
    }

    @Test
    fun `a loved world cannot be outranked off the page by untouched ones`() {
        // Nine untouched worlds win every freshness/exploration term; the tenth holds the only
        // tracks the listener demonstrably loves and must still make the row.
        val lovedTracks = (1..3).map { track("loved$it", artist = "Loved$it") }
        val fillerWorlds = (1..9).map { index ->
            LibraryWorld(
                name = "Filler $index",
                tracks = (1..4).map { track("f$index-$it", artist = "F$index-$it") },
            )
        }
        val lovedWorld = LibraryWorld(name = "Home", tracks = lovedTracks)
        val stats = lovedTracks.associate {
            it.id to stats(plays = 12, completions = 11, last = now - 2 * day)
        }
        val library = fillerWorlds.flatMap { it.tracks } + lovedTracks
        val result = ForYouBuilder.build(
            library = library,
            stats = stats,
            recentEvents = emptyList(),
            nowMs = now,
            worlds = fillerWorlds + lovedWorld,
        )
        val shown = result.sections.first { it.kind == ForYouSectionKind.WORLDS }
            .cards.mapNotNull { it.collection?.title }
        assertTrue("Home" in shown, "the listener's own region fell off the page: $shown")
    }

    @Test
    fun `loved members anchor their mix even when exploration outranks them`() {
        val loved = track("loved", artist = "Loved")
        val fresh = (1..40).map { track("fresh$it", artist = "Fresh$it") }
        val world = LibraryWorld(name = "Region", tracks = fresh + loved)
        val result = ForYouBuilder.build(
            library = fresh + loved,
            stats = mapOf(loved.id to stats(plays = 9, completions = 9, last = now - 40 * day)),
            recentEvents = emptyList(),
            nowMs = now,
            worlds = listOf(world),
        )
        val mix = result.sections.first { it.kind == ForYouSectionKind.WORLDS }
            .cards.single().collection
        assertTrue(
            mix?.tracks?.any { it.id == loved.id } == true,
            "a demonstrably loved member fell out of its own region's mix",
        )
    }
}
