/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ListeningInsightsTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val today = 1_000L
    private val now = today * day + 20 * hour

    private val utc = { ms: Long -> LocalTimePoint(ms.floorDiv(day), (ms.mod(day) / hour).toInt()) }

    /** Tracks "<artist>-<album>-<n>"; tracks whose id starts with "gone" are no longer in the library. */
    private val catalog = { id: TrackId ->
        if (id.value.startsWith("gone")) {
            null
        } else {
            val parts = id.value.split("-")
            TrackDescriptor(
                id = id,
                title = id.value,
                artist = parts.getOrNull(0)?.uppercase(),
                album = parts.getOrNull(1)?.takeIf { it != "x" },
                genre = if (parts.getOrNull(0) == "notag") null else "Rock",
                year = if (parts.getOrNull(0) == "notag") null else 1994,
                trackNumber = parts.getOrNull(2)?.toIntOrNull(),
            )
        }
    }

    private fun play(
        track: String,
        atMs: Long,
        start: ListenStart? = ListenStart.AUTO_ADVANCE,
        skipped: Boolean = false,
        completed: Boolean = !skipped,
        shuffleMode: String? = null,
        smartPosition: Int? = null,
        legacy: Boolean = false,
        listenedMs: Long = if (skipped) 10_000 else 3 * minute,
        parent: String? = null,
    ) = ListenEvent(
        trackId = TrackId(track),
        startedAtMs = atMs,
        playedMs = listenedMs,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
        listenedMs = if (legacy) null else listenedMs,
        origin = if (legacy) null else ListenOrigin(start = start, smartPlanPosition = smartPosition, parentId = parent),
    )

    private fun dayAt(daysAgo: Long, hourOfDay: Long = 9, minuteOffset: Long = 0) =
        (today - daysAgo) * day + hourOfDay * hour + minuteOffset * minute

    private fun habits(
        events: List<ListenEvent>,
        librarySize: Int = 100,
        playlists: List<StatsPlaylist> = emptyList(),
        personal: Boolean = false,
    ) = ListeningInsights.habits(
        history = ListeningNormalization.normalize(events, now),
        catalog = catalog,
        librarySize = librarySize,
        nowMs = now,
        timePointOf = utc,
        playlists = playlists,
        personal = if (personal) personalModel(events) else null,
    )

    /**
     * SMART's personal evidence over [events], as the app builds it: the played tracks plus 200 never played (a
     * library is far bigger than one listener's few pairs, which is what the lift filter's base rate reads).
     */
    private fun personalModel(events: List<ListenEvent>): PersonalModel {
        val ids = events.map { it.trackId }.distinct() + (0 until 200).map { TrackId("z-fill-$it") }
        val library = ids.mapNotNull(catalog).map { personalTrack(it, listOfNotNull(it.artist)) }
        return PersonalModel.build(events.sortedBy { it.startedAtMs }.map(::personalListen), library, { it.floorDiv(day) }, now)
    }

    /** One session: [from] picked, then [to] started as [start] four minutes later. */
    private fun step(
        daysAgo: Long,
        from: String,
        to: String,
        start: ListenStart = ListenStart.USER_PICK,
        shuffleMode: String? = "ON",
        smartPosition: Int? = null,
        parent: String? = null,
        skipped: Boolean = false,
        hourOfDay: Long = 9,
    ) = listOf(
        play(from, dayAt(daysAgo, hourOfDay), start = ListenStart.USER_PICK, shuffleMode = shuffleMode, parent = parent),
        play(
            to, dayAt(daysAgo, hourOfDay, minuteOffset = 4), start = start, shuffleMode = shuffleMode, smartPosition = smartPosition,
            parent = parent, skipped = skipped, listenedMs = if (skipped) 5_000 else 3 * minute,
        ),
    )

    /** Other listening, so that no song is everybody's successor. */
    private val background = (0 until 12).flatMap { n -> step(n * 3L + 2, "q-bg-$n", "r-bg-$n", hourOfDay = 15) }

    private fun period(events: List<ListenEvent>, sinceDays: Long?) = ListeningInsights.period(
        history = ListeningNormalization.normalize(events, now),
        catalog = catalog,
        sinceMs = sinceDays?.let { now - it * day },
        nowMs = now,
        timePointOf = utc,
    )

    @Test
    fun onRepeatRanksByDaysWithAPlayNotByLoops() {
        val loops = (0 until 12).map { play("a-one-1", dayAt(1, minuteOffset = it * 4L)) } +
            play("a-one-1", dayAt(2)) + play("a-one-1", dayAt(3))
        val steady = (0 until 6).map { play("b-two-1", dayAt(it * 2L)) }
        val third = (0 until 4).map { play("c-three-1", dayAt(it * 3L + 1)) }
        val result = habits(loops + steady + third)
        assertEquals(listOf("b-two-1", "c-three-1", "a-one-1"), result.onRepeat.filterIsInstance<RepeatTrack>().map { it.trackId.value })
        val loop = result.onRepeat.filterIsInstance<RepeatTrack>().last()
        assertEquals(3, loop.days)
        assertEquals(14, loop.plays)
        assertTrue(result.onRepeatArtists.isEmpty())
    }

    @Test
    fun onRepeatNeedsTwoDaysAndKeepsOneTrackPerAlbum() {
        val oneDay = (0 until 5).map { play("a-one-1", dayAt(1, minuteOffset = it * 4L)) }
        val album = (1..3).flatMap { n -> (0 until 3).map { play("b-two-$n", dayAt(it.toLong() + n, minuteOffset = n.toLong())) } }
        val others = listOf("c-three-1", "d-four-1").flatMap { id -> (0 until 3).map { play(id, dayAt(it * 2L)) } }
        val result = habits(oneDay + album + others)
        val ids = result.onRepeat.filterIsInstance<RepeatTrack>().map { it.trackId.value }
        assertFalse("a-one-1" in ids)
        assertEquals(1, ids.count { it.startsWith("b-two") })
    }

    @Test
    fun shuffleHeavyListenersFallBackToArtists() {
        // Forty different tracks of two artists, each played once: no track repeats, artists do.
        val events = (0 until 40).map { n -> play("${if (n % 2 == 0) "a" else "b"}-x-$n", dayAt(n % 20L, minuteOffset = n.toLong())) }
        val result = habits(events)
        assertTrue(result.onRepeat.filterIsInstance<RepeatTrack>().isEmpty())
        assertEquals(listOf("A", "B").toSet(), result.onRepeatArtists.map { it.artist }.toSet())
        assertFalse(result.onRepeatLocked)
    }

    @Test
    fun anOldBigLogWhereNothingRepeatsIsNotLocked() {
        val events = (0 until 400).map { n -> play("u$n-x-$n", dayAt(400L - n)) }
        val result = habits(events)
        assertTrue(result.onRepeat.filterIsInstance<RepeatTrack>().isEmpty() && result.onRepeatArtists.isEmpty())
        assertFalse(result.onRepeatLocked)
        assertFalse(result.slippingAwayLocked)
    }

    @Test
    fun aFreshLogLocksOnRepeatAndSlippingAway() {
        val result = habits(listOf(play("a-one-1", dayAt(2))))
        assertTrue(result.onRepeatLocked)
        assertTrue(result.slippingAwayLocked)
    }

    @Test
    fun streaksCountOnlyDaysWithAPlay() {
        val events = listOf(
            play("a-one-1", dayAt(3)),
            play("a-one-1", dayAt(2), skipped = true),
            play("a-one-1", dayAt(1)),
            play("a-one-1", dayAt(0)),
        )
        val result = habits(events)
        assertEquals(2, result.currentStreakDays)
        assertEquals(2, result.longestStreakDays)
        assertEquals(3, result.playDaysLastWeek)
    }

    @Test
    fun slippingAwayIsAChosenFavouriteNotStartedForTwoMonths() {
        val favourite = (0 until 6).map { play("a-one-1", dayAt(200L - it * 5), start = ListenStart.USER_PICK) }
        val smartLoved = (0 until 8).map {
            play("b-two-1", dayAt(200L - it * 5, minuteOffset = 5), shuffleMode = "SMART", smartPosition = 2)
        }
        val legacySmart = (0 until 8).map {
            play("c-three-1", dayAt(190L - it * 5), shuffleMode = "SMART", legacy = true)
        }
        val repeatOne = (0 until 8).map { play("d-four-1", dayAt(180L - it * 5), start = ListenStart.REPEAT) }
        val skippedRecently = (0 until 6).map { play("e-five-1", dayAt(200L - it * 5), start = ListenStart.USER_PICK) } +
            play("e-five-1", dayAt(3), skipped = true)
        val keepsRecording = (0 until 20).map { play("f-six-$it", dayAt(it.toLong())) }
        val result = habits(favourite + smartLoved + legacySmart + repeatOne + skippedRecently + keepsRecording)
        assertEquals(listOf("a-one-1"), result.slippingAway.map { it.trackId.value })
        assertEquals(6, result.slippingAway.single().chosenPlays)
    }

    @Test
    fun aTrackQueuedByHandInSmartModeIsChosenUnlessSkipped() {
        // Issue #24: Play next in SMART mode reached the log as a plain automatic advance.
        val queued = (0 until 6).map {
            play("a-one-1", dayAt(200L - it * 5), start = ListenStart.QUEUED, shuffleMode = "SMART")
        }
        val queuedThenSkipped = (0 until 6).map {
            play("b-two-1", dayAt(200L - it * 5, minuteOffset = 5), start = ListenStart.QUEUED, shuffleMode = "SMART", skipped = true)
        }
        val smartAdvanced = (0 until 6).map {
            play("c-three-1", dayAt(200L - it * 5, minuteOffset = 10), shuffleMode = "SMART")
        }
        val keepsRecording = (0 until 20).map { play("f-six-$it", dayAt(it.toLong())) }
        val result = habits(queued + queuedThenSkipped + smartAdvanced + keepsRecording)
        assertEquals(listOf("a-one-1"), result.slippingAway.map { it.trackId.value })
        assertEquals(6, result.slippingAway.single().chosenPlays)
        // Queueing is not a pick-after-pick step: those stay the listener's direct picks.
        assertEquals(0, result.pickSteps)
    }

    @Test
    fun slippingAwayWaitsWhileRecordingWasPaused() {
        val favourite = (0 until 6).map { play("a-one-1", dayAt(200L - it * 5), start = ListenStart.USER_PICK) }
        val fewRecentDays = (0 until 5).map { play("f-six-$it", dayAt(it.toLong())) }
        val result = habits(favourite + fewRecentDays)
        assertTrue(result.slippingAway.isEmpty())
        assertFalse(result.slippingAwayLocked)
    }

    @Test
    fun aYearAgoNeedsTheWholeWeekCovered() {
        val yearAgo = (0 until 3).map { play("a-one-1", dayAt(365, minuteOffset = it * 4L)) }
        assertTrue(habits(yearAgo).yearAgo.isEmpty())
        val covered = habits(listOf(play("z-old-1", dayAt(380))) + yearAgo)
        assertEquals(listOf("a-one-1"), covered.yearAgo.map { it.trackId.value })
    }

    @Test
    fun strongLinksCountOwnPicksAcrossSessions() {
        val events = background + (0 until 4).flatMap { step(it * 2L + 1, "a-one-1", "b-two-1") } + listOf(
            // A skipped pick is no link.
            play("a-one-1", dayAt(21)),
            play("d-four-1", dayAt(21, minuteOffset = 4), start = ListenStart.USER_PICK, skipped = true),
        )
        val sequence = habits(events, personal = true).sequences.single()
        assertEquals("a-one-1" to "b-two-1", sequence.from.value to sequence.to.value)
        assertEquals(4, sequence.sessions)
        assertTrue(habits(events).sequences.isEmpty(), "no personal evidence, no links")
    }

    @Test
    fun repeatedAcceptedContinuationsMakeALink() {
        // The owner's case: B played on after A, to the end, in four sessions; nobody picked it by hand.
        val accepted = (0 until 4).flatMap { step(it * 2L + 1, "a-one-1", "b-two-1", start = ListenStart.AUTO_ADVANCE) }
        val sequence = habits(background + accepted, personal = true).sequences.single()
        assertEquals("a-one-1" to "b-two-1", sequence.from.value to sequence.to.value)
        assertEquals(4, sequence.sessions)
        // Below SMART's D1 bar (three sessions) a pair is chance, however it was heard.
        assertTrue(habits(background + accepted.take(4), personal = true).sequences.isEmpty())
    }

    @Test
    fun linksWithinOneSessionDoNotQualify() {
        val events = (0 until 4).flatMap { round ->
            listOf(
                play("a-one-1", dayAt(1, minuteOffset = round * 8L)),
                play("b-two-1", dayAt(1, minuteOffset = round * 8L + 4), start = ListenStart.USER_PICK),
            )
        }
        assertTrue(habits(background + events, personal = true).sequences.isEmpty())
    }

    @Test
    fun smartOnlyLinksAreCapped() {
        val smart = (0 until 8).flatMap {
            step(it * 2L + 1, "a-one-1", "b-two-1", start = ListenStart.AUTO_ADVANCE, shuffleMode = "SMART", smartPosition = 1)
        }
        assertTrue(habits(background + smart, personal = true).sequences.isEmpty(), "SMART's own suggestions are no habit")
        val own = (0 until 2).flatMap { step(it * 2L + 20, "a-one-1", "b-two-1") }
        assertTrue(habits(background + smart + own, personal = true).sequences.isEmpty(), "two own sessions stay below the bar")
        val third = step(30, "a-one-1", "b-two-1")
        assertEquals(3, habits(background + smart + own + third, personal = true).sequences.single().sessions)
    }

    @Test
    fun albumAndPlaylistOrderDoNotFillTheCard() {
        fun inOrder(parent: String, shuffle: String) = (0 until 6).flatMap {
            step(it * 2L + 1, "a-one-1", "a-one-2", start = ListenStart.AUTO_ADVANCE, shuffleMode = shuffle, parent = parent)
        }
        assertTrue(habits(background + inOrder("album:one", "OFF"), personal = true).sequences.isEmpty(), "album order")
        assertTrue(habits(background + inOrder("playlist:p", "OFF"), personal = true).sequences.isEmpty(), "playlist order")
        assertEquals(6, habits(background + inOrder("playlist:p", "ON"), personal = true).sequences.single().sessions, "shuffled")
    }

    @Test
    fun skipsWeakenALink() {
        // C → D has one session more, so it leads until the listener starts skipping D after C.
        val links = (0 until 4).flatMap { step(it * 2L + 20, "a-one-1", "b-two-1") } +
            (0 until 5).flatMap { step(it * 2L + 20, "c-three-1", "d-four-1", hourOfDay = 12) }
        fun skips(n: Int) = (0 until n).flatMap { step(it * 2L + 1, "c-three-1", "d-four-1", skipped = true) }
        assertEquals(listOf("c-three-1", "a-one-1"), habits(background + links, personal = true).sequences.map { it.from.value })
        assertEquals(listOf("c-three-1", "a-one-1"), habits(background + links + skips(1), personal = true).sequences.map { it.from.value }, "one skip is chance")
        assertEquals(listOf("a-one-1", "c-three-1"), habits(background + links + skips(2), personal = true).sequences.map { it.from.value })
        // SMART's jar (PLAN_PERSONAL v6): six early skips empty a full habit; this one held five sessions.
        assertEquals(listOf("a-one-1"), habits(background + links + skips(6), personal = true).sequences.map { it.from.value })
    }

    @Test
    fun sameArtistPicksIgnoreUnknownArtistsAndNeedThirtySteps() {
        val events = (0 until 31).flatMap { n ->
            listOf(
                play("a-one-$n", dayAt(n.toLong())),
                play(if (n % 3 == 0) "b-two-$n" else "a-one-x$n", dayAt(n.toLong(), minuteOffset = 4), start = ListenStart.USER_PICK),
            )
        } + listOf(play("gone-1", dayAt(40)), play("a-one-1", dayAt(40, minuteOffset = 4), start = ListenStart.USER_PICK))
        val result = habits(events)
        assertEquals(31, result.pickSteps)
        assertEquals(20, result.sameArtistPicks)
        assertTrue(result.sameArtistShown)
        assertFalse(habits(events.take(20)).sameArtistShown)
    }

    @Test
    fun aSmartOnlyLegacyLogHasNoPatternsAndNoFavourites() {
        val events = (0 until 600).map { n ->
            play("a-one-${n % 30}", dayAt(300L - n / 2), shuffleMode = "SMART", legacy = true)
        }
        val result = habits(events)
        assertEquals(0, result.pickSteps)
        assertTrue(result.sequences.isEmpty())
        assertTrue(result.slippingAway.isEmpty())
        assertFalse(result.slippingAwayLocked)
    }

    @Test
    fun libraryAndDecadesUseAvailableTracks() {
        val events = listOf(
            play("a-one-1", dayAt(1)),
            play("a-one-1", dayAt(2)),
            play("b-two-1", dayAt(3)),
            play("gone-1", dayAt(4)),
            play("c-three-1", dayAt(5), skipped = true),
        )
        val result = habits(events, librarySize = 50)
        assertEquals(2, result.libraryTracksPlayed)
        assertEquals(50, result.librarySize)
        assertEquals(listOf(DecadePlays(1990, 3)), result.decades)

        val untagged = habits(events + (0 until 5).map { play("notag-x-$it", dayAt(6)) })
        assertTrue(untagged.decades.isEmpty())
    }

    @Test
    fun newAndFamiliarSplitPlaysAndAgreeWithTheCounts() {
        val events = listOf(
            play("a-one-1", dayAt(60)),
            play("b-two-1", dayAt(50), skipped = true), // first event skipped: its first play comes later
            play("a-one-1", dayAt(5)),
            play("b-two-1", dayAt(4)),
            play("b-two-1", dayAt(3)),
            play("c-three-1", dayAt(2)),
            play("gone-1", dayAt(1)),
        )
        val result = period(events, sinceDays = 30)
        assertEquals(3, result.newPlays)
        assertEquals(2, result.familiarPlays)
        assertEquals(3, result.newTracks)
        assertEquals(2, result.newArtists) // b's first play is in the period; the gone track has no artist
        assertEquals(4, result.playedTracks)
        assertEquals(3, result.playedArtists)
        assertTrue(result.burnedIn)
        assertNull(result.previousNewShare) // the previous 30 days hold no play
    }

    @Test
    fun comparisonsWaitForBurnIn() {
        val events = listOf(play("a-one-1", dayAt(40)), play("a-one-1", dayAt(1)))
        assertFalse(period(events, sinceDays = 30).burnedIn)
        assertNull(period(events, sinceDays = 30).previousNewShare)
        assertTrue(period(events, sinceDays = 7).burnedIn)
        assertEquals(20, period(listOf(play("a-one-1", dayAt(19))), sinceDays = 30).logDaysInsidePeriod)
    }

    @Test
    fun stuckArtistsNeedBurnInAndRepeatPlays() {
        val old = play("z-old-1", dayAt(200))
        val stuck = (0 until 6).map { play("n-new-$it", dayAt(20L - it * 3)) }
        val oneDay = (0 until 6).map { play("o-once-$it", dayAt(10, minuteOffset = it * 4L)) }
        assertEquals(listOf("N"), period(listOf(old) + stuck + oneDay, sinceDays = 30).stuckArtists.map { it.name })
        // A young log lists favourites, not discoveries.
        assertTrue(period(stuck, sinceDays = 30).stuckArtists.isEmpty())
    }

    @Test
    fun sessionsSkipSkipOnlyRunsAndBelongToTheirFirstStart() {
        val events = (0 until 3).flatMap { session ->
            (0 until 3).map { play("a-one-$it", dayAt(session.toLong() + 1, minuteOffset = it * 4L)) }
        } + play("b-two-1", dayAt(1, hourOfDay = 15), skipped = true)
        val result = period(events, sinceDays = 7)
        val sessions = assertNotNull(result.sessions)
        assertEquals(3, sessions.sessions)
        assertEquals(3, sessions.medianPlays)
        assertEquals(9 * minute, sessions.medianListenedMs)
        assertNull(period(events.take(6), sinceDays = 7).sessions)
    }

    @Test
    fun hoursMeasureListeningTimeIncludingSkips() {
        val result = period(listOf(play("a-one-1", dayAt(1, hourOfDay = 7)), play("a-one-1", dayAt(1, hourOfDay = 22), skipped = true)), sinceDays = 7)
        assertEquals(3 * minute, result.listenedMsByHour[7])
        assertEquals(10_000L, result.listenedMsByHour[22])
    }

    @Test
    fun albumsAndGenresNeedTagsOnMostPlays() {
        val tagged = (0 until 4).map { play("a-one-$it", dayAt(1, minuteOffset = it * 4L)) }
        val untagged = (0 until 4).map { play("notag-x-$it", dayAt(2, minuteOffset = it * 4L)) }
        val mostlyTagged = period(tagged + untagged.take(2), sinceDays = 7)
        assertEquals(listOf("one"), mostlyTagged.topAlbums?.map { it.name })
        assertEquals("A", mostlyTagged.topAlbums?.single()?.subtitle)
        assertEquals(listOf("Rock"), mostlyTagged.topGenres?.map { it.name })
        assertNull(period(tagged.take(2) + untagged, sinceDays = 7).topAlbums)
    }

    @Test
    fun topArtistsGroupSpellingsByTheEngineKey() {
        val custom = { id: TrackId ->
            TrackDescriptor(id = id, artist = if (id.value == "1") "The  Band" else "the band ")
        }
        val events = listOf(play("1", dayAt(1)), play("2", dayAt(2)))
        val result = ListeningInsights.period(ListeningNormalization.normalize(events, now), custom, null, now, utc)
        assertEquals(listOf(2), result.topArtists.map { it.plays })
    }

    @Test
    fun allTimeActivityPicksItsScaleFromTheLogSpan() {
        assertEquals(ActivityScale.DAY, period(listOf(play("a-one-1", dayAt(30))), sinceDays = null).activityScale)
        val months = period(listOf(play("a-one-1", dayAt(100)), play("a-one-1", dayAt(0))), sinceDays = null)
        assertEquals(ActivityScale.MONTH, months.activityScale)
        assertEquals(0, months.activity.last().ago)
        assertEquals(1, months.activity.last().plays)
        assertTrue(months.activity.size in 4..5)
        val years = period(listOf(play("a-one-1", dayAt(1000)), play("a-one-1", dayAt(0))), sinceDays = null)
        assertEquals(ActivityScale.YEAR, years.activityScale)
        assertEquals(1, years.activity.first().plays)
        assertEquals(1, years.activity.last().plays)
    }

    private val gym = StatsPlaylist("playlist:gym", (1..12).map { TrackId("p$it-x-$it") }.toSet())

    /** A twelve-track playlist played through every other day: each track repeats on its own too. */
    private fun gymDays(days: List<Long>, start: ListenStart = ListenStart.AUTO_ADVANCE, smart: Int? = null) =
        days.flatMap { daysAgo ->
            (1..12).map { n -> play("p$n-x-$n", dayAt(daysAgo, minuteOffset = n * 4L), start = start, smartPosition = smart, parent = "playlist:gym") }
        }

    @Test
    fun aPlaylistOnRepeatIsOneRowInsteadOfItsTracks() {
        val own = (0 until 4).map { play("a-one-1", dayAt(it * 3L + 1)) }
        val result = habits(gymDays(listOf(1, 3, 5, 7)) + own, playlists = listOf(gym))
        val rows = result.onRepeat
        assertEquals(1, rows.filterIsInstance<RepeatPlaylist>().size)
        assertEquals("playlist:gym", (rows.first() as RepeatPlaylist).playlistId)
        assertEquals(4, (rows.first() as RepeatPlaylist).days)
        assertEquals(listOf("a-one-1"), rows.filterIsInstance<RepeatTrack>().map { it.trackId.value })
        // Without the playlist the gym tracks would fill the list (two per unknown-album artist cap aside).
        assertTrue(habits(gymDays(listOf(1, 3, 5, 7)) + own).onRepeat.size > 2)
    }

    @Test
    fun smartPicksAndQueuedTracksDoNotCountForThePlaylist() {
        // SMART started from the playlist draws from the whole library: those plays are SMART's.
        val smart = gymDays(listOf(1, 3, 5, 7), smart = 2)
        assertTrue(habits(smart, playlists = listOf(gym)).onRepeat.none { it is RepeatPlaylist })
        // "Play next" into the playlist's queue keeps its source; a non-member is not the playlist's.
        val queued = (0 until 4).map { play("q-queued-1", dayAt(it * 2L + 1), parent = "playlist:gym") }
        val result = habits(queued, playlists = listOf(gym))
        assertTrue(result.onRepeat.none { it is RepeatPlaylist })
        assertEquals(listOf("Q"), result.onRepeatArtists.map { it.artist })
    }

    @Test
    fun anAbandonedPlaylistGivesAtMostTwoSlippingAwayRows() {
        val old = gymDays((120L..200L step 10).toList(), start = ListenStart.USER_PICK)
        val keepsRecording = (0 until 20).map { play("f-six-$it", dayAt(it.toLong())) }
        val result = habits(old + keepsRecording, playlists = listOf(gym))
        assertEquals(2, result.slippingAway.size)
        assertEquals(5, habits(old + keepsRecording).slippingAway.size)
    }

    @Test
    fun playlistsInYourTopNeedAFifthOfThePlays() {
        val fromGym = gymDays(listOf(1, 2))
        val own = (0 until 40).map { play("a-one-$it", dayAt(3, minuteOffset = it.toLong())) }
        val shown = ListeningInsights.period(ListeningNormalization.normalize(fromGym + own, now), catalog, now - 7 * day, now, utc, playlists = listOf(gym))
        assertEquals(listOf("playlist:gym"), shown.topPlaylists?.map { it.name })
        assertEquals(24, shown.topPlaylists?.single()?.plays)
        val few = ListeningInsights.period(ListeningNormalization.normalize(fromGym.take(5) + own, now), catalog, now - 7 * day, now, utc, playlists = listOf(gym))
        assertNull(few.topPlaylists)
    }

    @Test
    fun genresCountUnderTheGenreTheAppDerives() {
        val tagged = { id: TrackId -> TrackDescriptor(id = id, artist = "A", genre = if (id.value == "1") "Rock; Pop" else "Rock") }
        val events = listOf(play("1", dayAt(1)), play("2", dayAt(2)))
        val history = ListeningNormalization.normalize(events, now)
        val raw = ListeningInsights.period(history, tagged, null, now, utc)
        assertEquals(2, raw.topGenres?.size)
        val split = ListeningInsights.period(history, tagged, null, now, utc, genreOf = { it.genre?.substringBefore(';') })
        assertEquals(listOf("Rock" to 2), split.topGenres?.map { it.name to it.plays })
    }

    @Test
    fun civilMonthsMatchTheCalendar() {
        assertEquals(1970 to 1, ListeningInsights.civilYearMonth(0))
        assertEquals(2000 to 2, ListeningInsights.civilYearMonth(11_016)) // 2000-02-29
        assertEquals(2026 to 10, ListeningInsights.civilYearMonth(20_745)) // 2026-10-19
        assertEquals(1969 to 12, ListeningInsights.civilYearMonth(-1))
    }
}
