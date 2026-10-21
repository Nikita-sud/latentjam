/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class ListeningNormalizationTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 100 * day

    private fun event(
        track: String,
        startedAtMs: Long,
        listenedMs: Long? = 3 * minute,
        playedMs: Long = listenedMs ?: 3 * minute,
        completed: Boolean = true,
        skipped: Boolean = false,
        start: ListenStart? = ListenStart.AUTO_ADVANCE,
        legacy: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(track),
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = skipped,
        listenedMs = listenedMs,
        origin = if (legacy) null else ListenOrigin(start = start),
    )

    @Test
    fun emptyAndOutOfRangeLogsNormalizeToEmpty() {
        assertEquals(0, ListeningNormalization.normalize(emptyList(), now).size)
        val result = ListeningNormalization.normalize(listOf(event("a", -5), event("b", now + 1)), now)
        assertEquals(0, result.size)
        assertNull(result.logStartMs)
    }

    @Test
    fun unmergedEventsAreTheStoresOwnObjectsInStartOrder() {
        val first = event("a", 10 * day)
        val second = event("b", 5 * day)
        val result = ListeningNormalization.normalize(listOf(first, second), now)
        assertSame(second, result.events[0])
        assertSame(first, result.events[1])
        assertEquals(5 * day, result.logStartMs)
    }

    @Test
    fun exactDuplicatesKeepTheCopyThatKnowsItsOrigin() {
        val legacy = event("a", 10 * day, legacy = true)
        val known = event("a", 10 * day, start = ListenStart.USER_PICK)
        val other = event("b", 10 * day)
        val result = ListeningNormalization.normalize(listOf(legacy, known, other), now)
        assertEquals(listOf("a", "b"), result.events.map { it.trackId.value })
        assertSame(known, result.events[0])
    }

    @Test
    fun aResumedHalfJoinsItsListen() {
        // Paused at 1:30 with the app in the background, resumed 20 minutes later from 1:30.
        val first = event("a", 10 * day, listenedMs = 90_000, playedMs = 90_000, completed = false, start = ListenStart.USER_PICK)
        val resumed = event("a", 10 * day + 22 * minute, listenedMs = 110_000, playedMs = 200_000, start = null)
        val result = ListeningNormalization.normalize(listOf(first, resumed), now)
        val joined = result.events.single()
        assertEquals(200_000L, joined.listenedMs)
        assertEquals(200_000L, joined.playedMs)
        assertTrue(joined.completed)
        assertFalse(joined.skipped)
        assertEquals(ListenStart.USER_PICK, joined.origin?.start)
    }

    @Test
    fun aCompletedListenResumedNearItsEndIsNotASecondPlay() {
        val first = event("a", 10 * day, listenedMs = 175_000, playedMs = 175_000, completed = true)
        val resumed = event("a", 10 * day + hour, listenedMs = 20_000, playedMs = 195_000, completed = true, start = null)
        val result = ListeningNormalization.normalize(listOf(first, resumed), now)
        assertEquals(1, result.size)
        assertEquals(195_000L, result.events.single().listenedMs)
    }

    @Test
    fun aRestartFromTheTopIsNotJoined() {
        val first = event("a", 10 * day, listenedMs = 190_000, playedMs = 190_000)
        val restart = event("a", 10 * day + 10 * minute, listenedMs = 190_000, playedMs = 190_000, start = null)
        assertEquals(2, ListeningNormalization.normalize(listOf(first, restart), now).size)
    }

    @Test
    fun aRepeatOrALegacyLineIsNeverJoined() {
        val first = event("a", 10 * day, listenedMs = 60_000, playedMs = 60_000, completed = false)
        val repeat = event("a", 10 * day + 2 * minute, listenedMs = 60_000, playedMs = 120_000, start = ListenStart.REPEAT)
        assertEquals(2, ListeningNormalization.normalize(listOf(first, repeat), now).size)
        val legacy = event("a", 10 * day + 2 * minute, listenedMs = 60_000, playedMs = 120_000, legacy = true)
        assertEquals(2, ListeningNormalization.normalize(listOf(first, legacy), now).size)
    }

    @Test
    fun aResumeAfterMoreThanSixHoursIsNotJoined() {
        val first = event("a", 10 * day, listenedMs = 60_000, playedMs = 60_000, completed = false)
        val late = event("a", 10 * day + 7 * hour, listenedMs = 60_000, playedMs = 120_000, start = null)
        assertEquals(2, ListeningNormalization.normalize(listOf(first, late), now).size)
    }

    @Test
    fun chainsJoinAndMeasureTheGapFromTheJoinedEnd() {
        // Two four-hour pauses: each gap is measured from where the previous half ended.
        val first = event("a", 10 * day, listenedMs = 30_000, playedMs = 30_000, completed = false)
        val second = event("a", 10 * day + 4 * hour, listenedMs = 30_000, playedMs = 60_000, completed = false, start = null)
        val third = event("a", 10 * day + 8 * hour + minute, listenedMs = 30_000, playedMs = 90_000, completed = false, start = null)
        val joined = ListeningNormalization.normalize(listOf(first, second, third), now).events.single()
        assertEquals(90_000L, joined.listenedMs)
        assertFalse(joined.skipped)
    }

    @Test
    fun twoShortHalvesAreNotASkipOnceJoined() {
        val first = event("a", 10 * day, listenedMs = 20_000, playedMs = 20_000, completed = false, skipped = true)
        val resumed = event("a", 10 * day + minute, listenedMs = 20_000, playedMs = 40_000, completed = false, skipped = true, start = null)
        val joined = ListeningNormalization.normalize(listOf(first, resumed), now).events.single()
        assertFalse(joined.skipped)
    }

    @Test
    fun aBackwardSeekInsideTheResumedHalfBlocksTheJoin() {
        // Documented limit: replaying part of the track adds heard time without moving the furthest
        // playhead, so the resumed half no longer looks like it began where the first stopped.
        val first = event("a", 10 * day, listenedMs = 90_000, playedMs = 90_000, completed = false)
        val resumed = event("a", 10 * day + 5 * minute, listenedMs = 150_000, playedMs = 200_000, start = null)
        assertEquals(2, ListeningNormalization.normalize(listOf(first, resumed), now).size)
    }

    @Test
    fun sessionsSplitAfterHalfAnHourMeasuredFromTheEnd() {
        val events = listOf(
            event("a", 10 * day, listenedMs = 3 * minute),
            event("b", 10 * day + 32 * minute, listenedMs = 3 * minute), // 29 min after a ended
            event("c", 10 * day + 70 * minute, listenedMs = 3 * minute), // 35 min after b ended
        )
        val result = ListeningNormalization.normalize(events, now)
        assertEquals(listOf(0, 0, 1), result.sessionIds.toList())
    }

    @Test
    fun anOverlapBreaksTheSessionAndIsNeverJoined() {
        // Two devices' logs merged: b starts a minute into a's three heard minutes.
        val a = event("a", 10 * day, listenedMs = 3 * minute)
        val b = event("b", 10 * day + minute, listenedMs = 3 * minute)
        val result = ListeningNormalization.normalize(listOf(a, b), now)
        assertEquals(listOf(false, true), result.breaksBefore.toList())
        assertEquals(listOf(0, 1), result.sessionIds.toList())

        val again = event("a", 10 * day + minute, listenedMs = 3 * minute, playedMs = 4 * minute, start = null)
        assertEquals(2, ListeningNormalization.normalize(listOf(a, again), now).size)
    }

    @Test
    fun legacyPlayheadOvershootDoesNotBreakSessions() {
        // Legacy lines carry no heard time; their playhead can exceed the wall time to the next start.
        val a = event("a", 10 * day, listenedMs = null, playedMs = 10 * minute, legacy = true)
        val b = event("b", 10 * day + minute, listenedMs = null, playedMs = 3 * minute, legacy = true)
        val result = ListeningNormalization.normalize(listOf(a, b), now)
        assertEquals(listOf(false, false), result.breaksBefore.toList())
        assertEquals(listOf(0, 0), result.sessionIds.toList())
    }

    @Test
    fun overviewOverANormalizedLogWithoutMergesMatchesTheRawOverview() {
        val events = (0 until 40).map { index ->
            event(
                track = "t${index % 7}",
                startedAtMs = 50 * day + index * 7 * hour,
                completed = index % 3 != 0,
                skipped = index % 5 == 0,
                listenedMs = if (index % 5 == 0) 10_000 else 3 * minute,
                start = if (index % 2 == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE,
            )
        }
        val utc = { ms: Long -> LocalTimePoint(ms.floorDiv(day), (ms.mod(day) / hour).toInt()) }
        val raw = ListeningOverviews.summarize(events, { "x" }, 55 * day, now, utc)
        val normalized = ListeningOverviews.summarize(
            ListeningNormalization.normalize(events, now).events, { "x" }, 55 * day, now, utc,
        )
        assertEquals(raw, normalized)
    }
}
