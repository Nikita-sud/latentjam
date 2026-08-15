/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timing rules, pinned. These decide when a section may CLAIM something about the listener
 * ("your mornings", "your X phase"), and a wrong claim reads as broken — so the gates matter as
 * much as the picks.
 */
class ForYouRhythmTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_000_000_000_000L

    private fun track(id: String, artist: String? = "A") =
        TrackDescriptor(id = TrackId(id), title = "T$id", artist = artist)

    private fun event(
        id: String,
        at: Long,
        completed: Boolean = true,
        skipped: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(id),
        startedAtMs = at,
        playedMs = 60_000,
        trackDurationMs = 120_000,
        completed = completed,
        skipped = skipped,
    )

    private fun stats(plays: Int, completions: Int = plays, lastPlayedAtMs: Long = 0) =
        TrackStats(
            plays = plays,
            completions = completions,
            skips = 0,
            totalPlayedMs = plays * 60_000L,
            lastPlayedAtMs = lastPlayedAtMs,
        )

    private fun world(name: String, tracks: List<TrackDescriptor>) =
        LibraryWorld(name = name, tracks = tracks)

    // Fixed epoch-to-hour mapping: hour-of-day is the epoch hour modulo 24, so tests control
    // the daypart by choosing timestamps rather than by mocking a timezone.
    private val hourOf: (Long) -> Int = { ((it / hour) % 24).toInt() }

    private fun atHour(dayIndex: Int, hourOfDay: Int): Long = dayIndex * day + hourOfDay * hour

    @Test
    fun daypartBoundariesMatchThePublishedPhases() {
        assertEquals(ForYouDaypart.NIGHT, ForYouRhythm.daypartOf(0))
        assertEquals(ForYouDaypart.NIGHT, ForYouRhythm.daypartOf(5))
        assertEquals(ForYouDaypart.MORNING, ForYouRhythm.daypartOf(6))
        assertEquals(ForYouDaypart.MORNING, ForYouRhythm.daypartOf(11))
        assertEquals(ForYouDaypart.DAY, ForYouRhythm.daypartOf(12))
        assertEquals(ForYouDaypart.DAY, ForYouRhythm.daypartOf(17))
        assertEquals(ForYouDaypart.EVENING, ForYouRhythm.daypartOf(18))
        assertEquals(ForYouDaypart.EVENING, ForYouRhythm.daypartOf(23))
    }

    @Test
    fun daypartAffinityCountsOnlyItsOwnPhaseAndIgnoresSkips() {
        val events = listOf(
            event("m", atHour(0, 7)),
            event("m", atHour(1, 8)),
            event("e", atHour(0, 20)),
            event("skipped", atHour(2, 7), completed = false, skipped = true),
        )
        val affinity = ForYouRhythm.daypartAffinity(events, ForYouDaypart.MORNING, hourOf)
        assertEquals(setOf(TrackId("m")), affinity.keys)
        assertEquals(2, affinity.getValue(TrackId("m")).plays)
        // Completions count double in the ranking weight.
        assertEquals(4, affinity.getValue(TrackId("m")).weight)
    }

    @Test
    fun daypartRowBlendsProvenWithUnheardFromTheSameWorlds() {
        val proven = (1..8).map { track("p$it") }
        val unheard = (1..6).map { track("u$it") }
        val byId = (proven + unheard).associateBy { it.id }
        val events = proven.flatMapIndexed { index, t ->
            List(index + 1) { n -> event(t.id.value, atHour(n, 7)) }
        }
        val affinity = ForYouRhythm.daypartAffinity(events, ForYouDaypart.MORNING, hourOf)
        val stats = proven.associate { it.id to stats(plays = 3) }
        val row = ForYouRhythm.daypartRow(
            affinity = affinity,
            byId = byId,
            worlds = listOf(world("W", proven.take(2) + unheard)),
            stats = stats,
            used = emptySet(),
            dayIndex = 0,
        )
        assertTrue(row.isNotEmpty())
        val unheardShown = row.count { it.id.value.startsWith("u") }
        assertEquals(ForYouRhythm.DAYPART_FRESH_SLOTS, unheardShown)
        // Sandwich: opens on a proven track, and no two unheard tracks sit together while
        // anchors remain.
        assertTrue(row.first().id.value.startsWith("p"))
        row.zipWithNext().forEachIndexed { index, (a, b) ->
            val bothUnheard = a.id.value.startsWith("u") && b.id.value.startsWith("u")
            if (bothUnheard) {
                // Only permissible once anchors have run out (tail of the list).
                assertTrue(
                    row.drop(index + 1).all { it.id.value.startsWith("u") },
                    "unheard pair before anchors ran out at $index in ${row.map { it.id.value }}",
                )
            }
        }
    }

    @Test
    fun daypartRowRotatesAcrossDays() {
        val proven = (1..20).map { track("p$it") }
        val byId = proven.associateBy { it.id }
        val events = proven.flatMapIndexed { index, t ->
            List(index + 1) { n -> event(t.id.value, atHour(n % 5, 9)) }
        }
        val affinity = ForYouRhythm.daypartAffinity(events, ForYouDaypart.MORNING, hourOf)
        val stats = proven.associate { it.id to stats(plays = 2) }
        val day0 = ForYouRhythm.daypartRow(affinity, byId, emptyList(), stats, emptySet(), 0)
        val day1 = ForYouRhythm.daypartRow(affinity, byId, emptyList(), stats, emptySet(), 1)
        assertTrue(day0 != day1, "the row must not be identical day after day")
    }

    @Test
    fun bingeRequiresVolumeShareAndLift() {
        // A steady long-term artist: high volume every week, no lift — not a phase.
        val steady = (0 until 28).flatMap { d ->
            List(3) { n -> event("steady$n", now - d * day + n * hour) }
        }
        val artists = mapOf<TrackId, String?>(
            TrackId("steady0") to "Steady", TrackId("steady1") to "Steady",
            TrackId("steady2") to "Steady", TrackId("new0") to "New",
            TrackId("new1") to "New", TrackId("new2") to "New", TrackId("new3") to "New",
        )
        assertNull(
            ForYouRhythm.currentBinge(steady, now, { artists[it] }, { it.lowercase() }),
            "constant listening is taste, not a phase",
        )

        // A NEW artist spiking this week: 12 plays, zero baseline — the strongest phase signal.
        val spike = steady + (0 until 12).map { n ->
            event("new${n % 4}", now - (n % 6) * day + n * hour)
        }
        val binge = ForYouRhythm.currentBinge(spike, now, { artists[it] }, { it.lowercase() })
        assertNotNull(binge)
        assertEquals("New", binge.artistName)
    }

    @Test
    fun bingeDeepCutsPreferTheWorldsThePhaseActuallyTouched(): Unit {
        val played = track("played", artist = "Kanno")
        val sameWorld = track("same-world", artist = "Kanno")
        val otherWorld = track("other-world", artist = "Kanno")
        val library = listOf(played, sameWorld, otherWorld)
        val events = listOf(event("played", now - day))
        val cuts = ForYouRhythm.bingeDeepCuts(
            binge = ForYouRhythm.Binge("kanno", "Kanno", 12),
            library = library,
            events = events,
            stats = mapOf(played.id to stats(plays = 12, lastPlayedAtMs = now - day)),
            worlds = listOf(
                world("phase", listOf(played, sameWorld)),
                world("elsewhere", listOf(otherWorld)),
            ),
            nowMs = now,
            used = emptySet(),
            artistKeyOf = { it.lowercase() },
        )
        assertEquals(listOf("same-world", "other-world"), cuts.map { it.id.value })
    }

    @Test
    fun wildcardComesFromALovedDormantWorldAndRotates() {
        val lovedDormant = listOf(
            track("anchor"),
            track("gem1"),
            track("gem2"),
        )
        val recentWorld = listOf(track("recent-fav"), track("recent-gem"))
        val statsMap = mapOf(
            TrackId("anchor") to stats(plays = 9, lastPlayedAtMs = now - 45 * day),
            TrackId("recent-fav") to stats(plays = 9, lastPlayedAtMs = now - 1 * day),
        )
        val worlds = listOf(world("dormant", lovedDormant), world("hot", recentWorld))
        val pick = ForYouRhythm.wildcard(worlds, statsMap, now, emptySet(), dayIndex = 0)
        assertNotNull(pick)
        // Only the dormant loved world qualifies; the hot world was touched yesterday.
        assertTrue(pick.pick.id.value.startsWith("gem"))
        assertEquals("anchor", pick.anchor.id.value)
        val nextDay = ForYouRhythm.wildcard(worlds, statsMap, now + day, emptySet(), dayIndex = 1)
        assertNotNull(nextDay)
        assertTrue(pick.pick.id != nextDay.pick.id, "the wildcard must rotate day to day")
    }

    @Test
    fun wildcardStaysSilentWithoutProvenLove() {
        val worlds = listOf(
            world("visited", listOf(track("once"), track("unheard"))),
        )
        val statsMap = mapOf(
            TrackId("once") to stats(plays = 2, completions = 2, lastPlayedAtMs = now - 60 * day),
        )
        assertNull(ForYouRhythm.wildcard(worlds, statsMap, now, emptySet(), dayIndex = 0))
    }

    @Test
    fun sandwichOpensFamiliarAndSeparatesDiscoveries() {
        val known = (1..3).map { track("k$it") }
        val unknown = (1..3).map { track("n$it") }
        val statsMap = known.mapIndexed { i, t -> t.id to stats(plays = 3 - i) }.toMap()
        val ordered = ForYouRhythm.familiaritySandwich(unknown + known, statsMap)
        assertEquals("k1", ordered.first().id.value)
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue(
                statsMap.containsKey(a.id) || statsMap.containsKey(b.id),
                "two unheard tracks adjacent in ${ordered.map { it.id.value }}",
            )
        }
    }
}
