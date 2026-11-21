/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ListeningHistoryTest {

    private class FakeStore(initial: List<String> = emptyList()) : HistoryStore {
        val lines = initial.toMutableList()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun clear() { lines.clear() }
    }

    private fun event(
        id: String,
        startedAt: Long,
        played: Long = 60_000,
        completed: Boolean = false,
        skipped: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(id),
        startedAtMs = startedAt,
        playedMs = played,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = skipped,
        shuffleMode = "SMART",
    )

    /** One half of a playback of a 200-second track: [listened] ms heard, ending with the playhead at [played]. */
    private fun half(
        id: String,
        startedAt: Long,
        listened: Long,
        played: Long,
        start: ListenStart?,
        completed: Boolean = false,
    ) = ListenEvent(
        trackId = TrackId(id),
        startedAtMs = startedAt,
        playedMs = played,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = !completed && listened < 30_000,
        shuffleMode = "OFF",
        listenedMs = listened,
        origin = ListenOrigin(start = start),
    )

    @Test
    fun aPlaybackPausedInTheBackgroundAndResumedIsOnePlayAndNoSkip() = runTest {
        // Paused after 10 s, the app left; ten minutes later it plays on from 0:10 to the end.
        val first = half("a", 1_000_000, listened = 10_000, played = 10_000, start = ListenStart.USER_PICK)
        val resumed = half("a", 1_600_000, listened = 190_000, played = 200_000, start = null, completed = true)
        assertTrue(first.skipped)
        val store = FakeStore()
        val history = DefaultListeningHistory(store)
        history.record(first)
        assertEquals(1, history.stats().getValue(TrackId("a")).skips)
        history.record(resumed)

        val expected = TrackStats(
            plays = 1, completions = 1, skips = 0, totalPlayedMs = 200_000,
            lastPlayedAtMs = 1_600_000, listens = 1, lastListenedAtMs = 1_600_000,
        )
        assertEquals(expected, history.stats().getValue(TrackId("a")))
        // The log keeps both lines, and a later launch counts them the same way.
        assertEquals(2, store.lines.size)
        assertEquals(listOf(first, resumed), history.allEvents())
        assertEquals(expected, DefaultListeningHistory(FakeStore(store.lines)).stats().getValue(TrackId("a")))
        // Readers that take a line for a listen get the one listen.
        val listen = history.recentListens(10).single()
        assertEquals(200_000L, listen.listenedMs)
        assertTrue(listen.completed)
        assertFalse(listen.skipped)
        assertEquals(ListenStart.USER_PICK, listen.origin?.start)
        // It ends where the resumed half ended (1,600,000 + 190,000), so the track after it still follows it.
        assertEquals(1_590_000L, listen.startedAtMs)
        assertEquals(1_790_000L, listen.startedAtMs + listen.listenedMs!!)
    }

    @Test
    fun aResumedPlaybackAbandonedEarlyStaysOneSkip() = runTest {
        val first = half("a", 1_000_000, listened = 5_000, played = 5_000, start = ListenStart.AUTO_ADVANCE)
        val resumed = half("a", 1_100_000, listened = 5_000, played = 10_000, start = null)
        val history = DefaultListeningHistory(FakeStore())
        history.record(first)
        history.record(resumed)
        val stats = history.stats().getValue(TrackId("a"))
        assertEquals(1, stats.plays)
        assertEquals(1, stats.skips)
        assertEquals(0, stats.listens)
        assertEquals(0L, stats.lastListenedAtMs)
        assertEquals(10_000L, stats.totalPlayedMs)
    }

    @Test
    fun aSecondPlayOfTheSameTrackIsStillASecondPlay() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        // Played from the top again: a start of its own, or a playhead back at the beginning.
        history.record(half("a", 1_000_000, listened = 60_000, played = 60_000, start = ListenStart.USER_PICK))
        history.record(half("a", 1_100_000, listened = 60_000, played = 60_000, start = ListenStart.USER_PICK))
        history.record(half("a", 1_200_000, listened = 60_000, played = 60_000, start = null))
        // Another track between the halves: the line before is not the listen this one would continue.
        history.record(half("b", 1_300_000, listened = 40_000, played = 40_000, start = ListenStart.SKIP_NEXT))
        history.record(half("a", 1_400_000, listened = 100_000, played = 160_000, start = null))
        assertEquals(4, history.stats().getValue(TrackId("a")).plays)
        assertEquals(5, history.recentListens(10).size)
    }

    @Test
    fun countersAfterARestoreAndAClearFollowTheSameRule() = runTest {
        val first = half("a", 1_000_000, listened = 10_000, played = 10_000, start = ListenStart.USER_PICK)
        val resumed = half("a", 1_600_000, listened = 190_000, played = 200_000, start = null, completed = true)
        val history = DefaultListeningHistory(FakeStore())
        history.replace(listOf(first, resumed))
        assertEquals(1, history.stats().getValue(TrackId("a")).plays)
        history.clear()
        // The cleared log has no listen for a resumed line to continue.
        history.record(resumed)
        assertEquals(1, history.stats().getValue(TrackId("a")).plays)
        assertEquals(1, history.stats().getValue(TrackId("a")).completions)
    }

    @Test
    fun aLoadedLogKeepsOneIdPerSongAndEveryLineInOrder() = runTest {
        val lines = listOf(event("a", 1), event("b", 2), event("a", 3)).map(ListenEvent::serialize)
        val history = DefaultListeningHistory(FakeStore(lines))
        val events = history.allEvents()
        assertEquals(listOf(1L, 2L, 3L), events.map { it.startedAtMs })
        assertEquals(listOf("a", "b", "a"), events.map { it.trackId.value })
        assertSame(events[0].trackId.value, events[2].trackId.value)
        assertEquals(2, history.stats().getValue(TrackId("a")).plays)
    }

    @Test
    fun aFailedRewriteLeavesTheLogInMemoryAsItWas() = runTest {
        val store = object : HistoryStore {
            val lines = mutableListOf(event("a", 1).serialize())
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines.toList()
            override suspend fun replaceAll(lines: List<String>) = error("disk full")
            override suspend fun clear() { lines.clear() }
        }
        val history = DefaultListeningHistory(store)
        assertFailsWith<IllegalStateException> { history.replace(listOf(event("b", 2))) }
        assertEquals(listOf("a"), history.allEvents().map { it.trackId.value })
        assertEquals(setOf(TrackId("a")), history.stats().keys)
    }

    @Test
    fun skippedStartsAreStartsButNotListens() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 1, played = 3_000, skipped = true))
        history.record(event("1", startedAt = 2, played = 3_000, skipped = true))
        history.record(event("1", startedAt = 3, completed = true))

        val stats = history.stats().getValue(TrackId("1"))
        assertEquals(3, stats.plays)
        assertEquals(1, stats.listens)
        assertEquals(2, stats.skips)
    }

    @Test
    fun aSkippedStartDoesNotMoveTheLastListen() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 10, completed = true))
        history.record(event("1", startedAt = 20, played = 3_000, skipped = true))
        history.record(event("2", startedAt = 30, played = 2_000, skipped = true))

        val stats = history.stats()
        assertEquals(20, stats.getValue(TrackId("1")).lastPlayedAtMs)
        assertEquals(10, stats.getValue(TrackId("1")).lastListenedAtMs)
        assertEquals(0, stats.getValue(TrackId("2")).lastListenedAtMs, "only skipped: never listened")
    }

    @Test
    fun theFirstListenIsTheEarliestPlausibleStartWhereverItSits() = runTest {
        val floor = ListeningNormalization.PLAUSIBLE_START_MS
        val history = DefaultListeningHistory(FakeStore())
        assertEquals(null, history.firstListenAtMs())
        history.record(event("1", startedAt = 3, completed = true))
        assertEquals(null, history.firstListenAtMs(), "a listen from an unset clock is not a start")
        history.record(event("1", startedAt = floor + 500, completed = true))
        // Merged from a backup: older, though logged later.
        history.record(event("2", startedAt = floor + 100, completed = true))
        assertEquals(floor + 100, history.firstListenAtMs())
        // Scrobbled in 2016 and imported: the importer's fingerprint, not an unset clock.
        val scrobbled = ListenEvent(TrackId("3"), 1_451_606_400_000L, 200_000L, 200_000L, completed = true, skipped = false)
        history.record(scrobbled)
        assertEquals(1_451_606_400_000L, history.firstListenAtMs())
    }

    @Test
    fun listensAreRebuiltFromAnExistingLog() = runTest {
        val store = FakeStore(
            listOf(
                event("1", startedAt = 1, played = 3_000, skipped = true).serialize(),
                event("1", startedAt = 2, completed = true).serialize(),
            ),
        )
        val stats = DefaultListeningHistory(store).stats().getValue(TrackId("1"))
        assertEquals(2, stats.plays)
        assertEquals(1, stats.listens)
    }

    @Test
    fun serializationRoundTrips() {
        val original = event("42", startedAt = 1_234, completed = true)
        assertEquals(original, ListenEvent.parse(original.serialize()))
        // Null duration + null mode round-trip too.
        val bare = ListenEvent(TrackId("7"), 5, 10, null, false, true, null)
        assertEquals(bare, ListenEvent.parse(bare.serialize()))
        val opaque = event("folder/Earth|Wind,曲.mp3", startedAt = 9)
            .copy(shuffleMode = "mode|future")
        assertEquals(opaque, ListenEvent.parse(opaque.serialize()))
    }

    @Test
    fun legacyV1LinesStillLoad() {
        assertEquals(
            ListenEvent(TrackId("42"), 1, 2, null, completed = true, skipped = false, shuffleMode = "SMART"),
            ListenEvent.parse("v1|42|1|2||1|0|SMART"),
        )
    }

    // Hand-encoded fixtures: hex("42") = 3432, hex("SMART") = 534d415254,
    // hex("playlist:x") = 706c61796c6973743a78.
    private fun v4Line(
        start: String = "SKIP_NEXT",
        planPosition: String = "4",
        parent: String = "706c61796c6973743a78",
    ) = "v4|3432|1|2||1|0|534d415254|2|$start|$planPosition|$parent"

    @Test
    fun legacyV2AndV3LinesLoadWithAnUnknownOrigin() {
        assertEquals(
            ListenEvent(TrackId("42"), 1, 2, 3, completed = false, skipped = true, shuffleMode = "SMART"),
            ListenEvent.parse("v2|3432|1|2|3|0|1|534d415254"),
        )
        val v3 = assertNotNull(ListenEvent.parse("v3|3432|1|2|3|0|1||5"))
        assertEquals(
            ListenEvent(TrackId("42"), 1, 2, 3, completed = false, skipped = true, listenedMs = 5),
            v3,
        )
        assertNull(v3.origin, "nothing about how a legacy listen began was ever recorded")
    }

    @Test
    fun aV4LineCarriesTheOriginRecordInItsDocumentedLayout() {
        val event = ListenEvent(
            TrackId("42"), 1, 2, null, completed = true, skipped = false, shuffleMode = "SMART",
            listenedMs = 2,
            origin = ListenOrigin(ListenStart.SKIP_NEXT, smartPlanPosition = 4, parentId = "playlist:x"),
        )
        assertEquals(event, ListenEvent.parse(v4Line()))
        assertEquals(v4Line(), event.serialize())
    }

    @Test
    fun originRecordsRoundTrip() {
        val picked = event("folder/Earth|Wind,曲.mp3", startedAt = 1_234, completed = true).copy(
            listenedMs = 190_000,
            origin = ListenOrigin(ListenStart.USER_PICK, parentId = "folder:/Music/Earth|Wind/曲"),
        )
        assertEquals(picked, ListenEvent.parse(picked.serialize()))
        val planned = event("7", startedAt = 9).copy(
            origin = ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 12),
        )
        assertEquals(planned, ListenEvent.parse(planned.serialize()))
        // Recorded live with nothing known is still distinct from a legacy event (origin == null).
        val unknown = event("8", startedAt = 10).copy(origin = ListenOrigin())
        assertEquals(unknown, ListenEvent.parse(unknown.serialize()))
    }

    @Test
    fun aStartThisBuildDoesNotKnowReadsAsUnknownWithoutLosingTheListen() {
        val parsed = assertNotNull(ListenEvent.parse(v4Line(start = "SOMETHING_NEWER")))
        assertEquals(ListenOrigin(start = null, smartPlanPosition = 4, parentId = "playlist:x"), parsed.origin)
    }

    @Test
    fun aLineFromANewerFormatKeepsTheFieldsThisBuildKnows() {
        // Later versions only append fields; a downgrade must not drop what a newer build recorded.
        val newer = v4Line().replaceFirst("v4|", "v6|") + "|SEARCH|1|a field from the future"
        val read = assertNotNull(ListenEvent.parse(newer))
        assertEquals(ListenEvent.parse(v4Line())?.origin?.copy(pickSurface = PickSurface.SEARCH, personalChanged = true), read.origin)
        assertEquals(v4Line().replaceFirst("v4|", "v5|") + "|SEARCH|1", read.serialize())
        assertNull(ListenEvent.parse("v6|3432|1|2||1|0"), "too short to hold the fields this build reads")
        assertNull(ListenEvent.parse(v4Line().replaceFirst("v4|", "vx|")), "not a version")
    }

    @Test
    fun aPickSurfaceAndThePersonalFlagRoundTripAsV5AndOlderBuildsReadV4() {
        val base = assertNotNull(ListenEvent.parse(v4Line()))
        val event = base.copy(origin = base.origin?.copy(pickSurface = PickSurface.FOR_YOU, personalChanged = false))
        val line = event.serialize()
        assertTrue(line.startsWith("v5|"), line)
        assertEquals(event, ListenEvent.parse(line))
        // Without either field the line stays v4.
        assertEquals(v4Line(), base.serialize())
        // A surface this build does not know reads as unknown; a corrupt flag drops the line.
        assertEquals(null, ListenEvent.parse(line.replace("|FOR_YOU|", "|SOMEWHERE_NEW|"))?.origin?.pickSurface)
        assertNull(ListenEvent.parse(line.substringBeforeLast('|') + "|x"))
    }

    @Test
    fun malformedOriginFieldsMakeAV4LineCorrupt() {
        assertNotNull(ListenEvent.parse(v4Line()))
        assertNull(ListenEvent.parse(v4Line(planPosition = "0")), "plan positions count from 1")
        assertNull(ListenEvent.parse(v4Line(planPosition = "-2")))
        assertNull(ListenEvent.parse(v4Line(planPosition = "x")))
        assertNull(ListenEvent.parse(v4Line(parent = "706")), "odd-length hex")
        assertNull(ListenEvent.parse(v4Line(parent = "zz")))
        assertNull(ListenEvent.parse(v4Line().substringBeforeLast('|')), "a v4 line has twelve fields")
        assertNull(ListenEvent.parse(v4Line() + "|"))
        assertNull(ListenEvent.parse(v4Line().replaceFirst("v4|", "v3|")), "a v3 line has no origin")
    }

    @Test
    fun aFailedAppendDoesNotPublishAnInMemoryEvent() = runTest {
        val store = object : HistoryStore {
            override suspend fun append(line: String): Unit = error("disk full")
            override suspend fun readAll(): List<String> = emptyList()
            override suspend fun clear() = Unit
        }
        val history = DefaultListeningHistory(store)

        assertFailsWith<IllegalStateException> { history.record(event("x", startedAt = 1)) }
        assertEquals(emptyMap(), history.stats())
    }

    @Test
    fun aFailedInitialReadIsRetried() = runTest {
        val persisted = event("existing", startedAt = 1)
        val store = object : HistoryStore {
            var fail = true
            override suspend fun append(line: String) = Unit
            override suspend fun readAll(): List<String> {
                if (fail) error("transient read")
                return listOf(persisted.serialize())
            }
            override suspend fun clear() = Unit
        }
        val history = DefaultListeningHistory(store)

        assertFailsWith<IllegalStateException> { history.stats() }
        store.fail = false
        assertEquals(1, history.stats()[persisted.trackId]?.plays)
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() = runTest {
        val store = FakeStore(
            initial = listOf(
                event("1", startedAt = 100).serialize(),
                "garbage|line",
                "v9|future|format|x|x|x|x|x",
                event("1", startedAt = 200, completed = true).serialize(),
            ),
        )
        val history = DefaultListeningHistory(store)
        val stats = history.stats()
        assertEquals(1, stats.size)
        assertEquals(2, stats[TrackId("1")]?.plays)
    }

    @Test
    fun negativeNumbersAndInvalidFlagsAreCorrupt() {
        val valid = event("valid", startedAt = 1).serialize()
        assertNull(ListenEvent.parse(valid.replace("|1|60000|", "|-1|60000|")))
        assertNull(ListenEvent.parse(valid.replace("|60000|200000|", "|-1|200000|")))
        assertNull(ListenEvent.parse(valid.replace("|200000|0|", "|-1|0|")))
        assertNull(ListenEvent.parse("v1|track|1|2||maybe|0|SMART"))
    }

    @Test
    fun statsAggregateAcrossEvents() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100, played = 30_000, skipped = true))
        history.record(event("1", startedAt = 200, played = 190_000, completed = true))
        history.record(event("2", startedAt = 300, played = 60_000))

        val stats = history.stats()
        val one = stats[TrackId("1")]!!
        assertEquals(2, one.plays)
        assertEquals(1, one.completions)
        assertEquals(1, one.skips)
        assertEquals(220_000, one.totalPlayedMs)
        assertEquals(200, one.lastPlayedAtMs)
        assertEquals(1, stats[TrackId("2")]?.plays)
    }

    @Test
    fun statsSnapshotsStayImmutableWhileCachedAggregateAdvances() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100, played = 30_000, skipped = true))

        val firstSnapshot = history.stats()
        history.record(event("1", startedAt = 200, played = 190_000, completed = true))
        val secondSnapshot = history.stats()

        assertEquals(1, firstSnapshot.getValue(TrackId("1")).plays)
        assertEquals(30_000, firstSnapshot.getValue(TrackId("1")).totalPlayedMs)
        assertEquals(2, secondSnapshot.getValue(TrackId("1")).plays)
        assertEquals(220_000, secondSnapshot.getValue(TrackId("1")).totalPlayedMs)
    }

    @Test
    fun replacingHistoryRebuildsTheCachedAggregate() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("old", startedAt = 100))

        history.replace(
            listOf(
                event("new", startedAt = 200, played = 10_000),
                event("new", startedAt = 300, played = 20_000, completed = true),
            ),
        )

        assertEquals(setOf(TrackId("new")), history.stats().keys)
        assertEquals(2, history.stats().getValue(TrackId("new")).plays)
        assertEquals(30_000, history.stats().getValue(TrackId("new")).totalPlayedMs)
    }

    /** The transformation the app's local backup MERGE uses: union without duplicates, oldest first. */
    private fun mergeChronologically(
        existing: List<ListenEvent>,
        imported: List<ListenEvent>,
    ): List<ListenEvent> = (existing + imported).distinct().sortedBy(ListenEvent::startedAtMs)

    @Test
    fun mergeFoldsImportedEventsInAndPersistsTheWholeLog() = runTest {
        val store = FakeStore(initial = listOf(event("existing", startedAt = 100).serialize()))
        val history = DefaultListeningHistory(store)

        val held = history.merge(
            imported = listOf(event("early", startedAt = 50), event("middle", startedAt = 200)),
            maxEvents = 10,
            combine = ::mergeChronologically,
        )

        assertEquals(3, held, "the merge reports the log it wrote")
        assertEquals(
            listOf("early", "existing", "middle"),
            history.allEvents().map { it.trackId.value },
            "an imported listen older than the stored ones still lands in chronological position",
        )
        // The merged log is what a restart reads back, not only what this process remembers.
        assertEquals(
            listOf("early", "existing", "middle"),
            DefaultListeningHistory(store).allEvents().map { it.trackId.value },
        )
    }

    @Test
    fun mergeRefusesToExceedTheEventLimitAndLeavesTheLogAlone() = runTest {
        val existing = event("existing", startedAt = 100)
        val store = FakeStore(initial = listOf(existing.serialize()))
        val history = DefaultListeningHistory(store)

        assertFailsWith<IllegalArgumentException> {
            history.merge(
                imported = listOf(event("imported", startedAt = 200)),
                maxEvents = 1,
                combine = ::mergeChronologically,
            )
        }

        assertEquals(listOf(existing), history.allEvents(), "a refused merge publishes nothing")
        assertEquals(listOf(existing.serialize()), store.lines, "a refused merge writes nothing")
    }

    /**
     * The window a read-then-replace pair leaves open: a listen recorded after the log was read and
     * before the replacement is written used to be overwritten by the snapshot built from that
     * earlier read. The store below suspends inside the merge's write, where the recorder now waits
     * for the same lock instead of slipping between the two calls.
     */
    @Test
    fun aListenRecordedWhileAMergeWritesIsNotLost() = runTest {
        val existing = event("existing", startedAt = 100)
        val imported = event("imported", startedAt = 200)
        val late = event("late", startedAt = 300)
        val writing = CompletableDeferred<Unit>()
        val store = object : HistoryStore {
            val lines = mutableListOf(existing.serialize())
            private var held = false
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines.toList()
            override suspend fun replaceAll(lines: List<String>) {
                this.lines.clear()
                this.lines += lines
                if (!held) {
                    held = true
                    writing.await()
                }
            }
            override suspend fun clear() { lines.clear() }
        }
        val history = DefaultListeningHistory(store)
        // Load the log first: an unloaded one would parse on Dispatchers.Default, and the merge
        // below has to reach its store write on the first dispatch for this test to be repeatable.
        assertEquals(listOf(existing), history.allEvents())

        val merging = launch { history.merge(listOf(imported), 10, ::mergeChronologically) }
        val recording = launch { history.record(late) }
        runCurrent()
        writing.complete(Unit)
        merging.join()
        recording.join()

        assertEquals(
            listOf(existing, imported, late),
            history.allEvents(),
            "the listen recorded while the merge held the lock must survive it",
        )
        assertEquals(
            history.allEvents().map(ListenEvent::serialize),
            store.lines,
            "the written log and the in-memory log describe the same history",
        )
    }

    @Test
    fun aMergeCancelledWhileWritingLeavesOneCoherentLog() = runTest {
        val existing = event("existing", startedAt = 100)
        val imported = event("imported", startedAt = 200)
        val writing = CompletableDeferred<Unit>()
        val store = object : HistoryStore {
            val lines = mutableListOf(existing.serialize())
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines.toList()
            override suspend fun replaceAll(lines: List<String>) {
                this.lines.clear()
                this.lines += lines
                writing.await()
            }
            override suspend fun clear() { lines.clear() }
        }
        val history = DefaultListeningHistory(store)
        // Load the log first, so the merge is already inside its store write after runCurrent().
        assertEquals(listOf(existing), history.allEvents())

        val merging = launch { history.merge(listOf(imported), 10, ::mergeChronologically) }
        runCurrent()
        merging.cancel() // The user leaves the restore screen while the section is being written.
        writing.complete(Unit)
        merging.join()

        assertTrue(merging.isCancelled, "the caller still learns that the merge was cancelled")
        assertEquals(
            listOf(existing, imported),
            history.allEvents(),
            "the persisted log and the in-memory log agree after a cancelled write",
        )
        assertEquals(history.allEvents().map(ListenEvent::serialize), store.lines)
    }

    @Test
    fun statsPlayedDurationSaturatesInsteadOfOverflowing() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100, played = Long.MAX_VALUE))
        history.record(event("1", startedAt = 200, played = 1))

        assertEquals(Long.MAX_VALUE, history.stats()[TrackId("1")]?.totalPlayedMs)
    }

    @Test
    fun recordPersistsAndReloadsThroughStore() = runTest {
        val store = FakeStore()
        DefaultListeningHistory(store).record(event("9", startedAt = 1))

        // Fresh instance over the same store = process restart.
        val reloaded = DefaultListeningHistory(store)
        assertEquals(1, reloaded.stats()[TrackId("9")]?.plays)
        assertEquals(TrackId("9"), reloaded.recentEvents(5).single().trackId)
    }

    @Test
    fun recentEventsNewestFirst() = runTest {
        val history = DefaultListeningHistory(FakeStore())
        history.record(event("1", startedAt = 100))
        history.record(event("2", startedAt = 200))
        history.record(event("3", startedAt = 300))
        assertEquals(
            listOf("3", "2"),
            history.recentEvents(2).map { it.trackId.value },
        )
        assertNull(history.recentEvents(0).firstOrNull())
        assertEquals(
            listOf("1", "2", "3"),
            history.allEvents().map { it.trackId.value },
            "the full-log API is complete and chronological",
        )
    }

    @Test
    fun clearRemovesInMemoryAndPersistedHistory() = runTest {
        val store = FakeStore(
            initial = listOf(
                event("1", startedAt = 100).serialize(),
                event("2", startedAt = 200).serialize(),
            ),
        )
        val history = DefaultListeningHistory(store)

        history.clear()

        assertEquals(emptyMap(), history.stats())
        assertEquals(emptyList(), history.recentEvents(10))
        assertEquals(emptyList(), store.lines)

        // A fresh instance must also observe an empty log after a process restart.
        val reloaded = DefaultListeningHistory(store)
        assertEquals(emptyMap(), reloaded.stats())
        assertEquals(emptyList(), reloaded.recentEvents(10))
    }

    @Test
    fun aClearCancelledWhileTheLogIsDeletedLeavesNoListensInMemory() = runTest {
        val deleting = CompletableDeferred<Unit>()
        val store = object : HistoryStore {
            val lines = mutableListOf(event("1", startedAt = 100).serialize())
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines.toList()
            override suspend fun clear() {
                lines.clear()
                deleting.await()
            }
        }
        val history = DefaultListeningHistory(store)
        // Load the log first, so the clear is already inside its store call after runCurrent().
        assertEquals(1, history.allEvents().size)

        val clearing = launch { history.clear() }
        runCurrent()
        clearing.cancel() // The listener leaves the Privacy screen while the log is being deleted.
        deleting.complete(Unit)
        clearing.join()

        assertEquals(emptyList(), store.lines)
        assertEquals(emptyList(), history.allEvents(), "a deleted log is not served from memory")
        assertEquals(emptyMap(), history.stats())
    }

    @Test
    fun anImportIsToldByTheImportersFingerprintAlone() {
        val imported = ListenEvent(TrackId("a"), 1_700_000_000_000L, 200_000L, 200_000L, completed = true, skipped = false)
        assertTrue(imported.isImported)
        // Unknown length: played 0, not completed.
        assertTrue(ListenEvent(TrackId("a"), 1_700_000_000_000L, 0L, null, completed = false, skipped = false).isImported)
        // An origin with every field unknown is no origin.
        assertTrue(imported.copy(origin = ListenOrigin()).isImported)
        // Anything the player would have written breaks it.
        assertFalse(imported.copy(listenedMs = 200_000L).isImported)
        assertFalse(imported.copy(startedAtMs = 1_700_000_000_123L).isImported)
        assertFalse(imported.copy(playedMs = 150_000L).isImported)
        assertFalse(imported.copy(skipped = true, completed = false).isImported)
        assertFalse(imported.copy(origin = ListenOrigin(ListenStart.USER_PICK)).isImported)
        assertFalse(imported.copy(completed = false).isImported)
    }

    // Issue #40: merging duplicate copies of one recording keeps the listens of the copies that go.

    @Test
    fun theListensOfMergedCopiesBecomeTheKeptCopysAndNothingElseChanges() = runTest {
        val store = FakeStore()
        val history = DefaultListeningHistory(store)
        val log = listOf(
            event("kept", 1_000, completed = true),
            event("copy", 2_000, played = 20_000, skipped = true),
            event("other", 3_000),
            event("copy2", 4_000, completed = true),
            event("copy", 5_000, completed = true),
        )
        log.forEach { history.record(it) }

        assertEquals(3, history.moveListens(from = setOf(TrackId("copy"), TrackId("copy2")), to = TrackId("kept")))

        // Every listen is still there, at its place, with its own time and length; only the track changed.
        val expected = log.map { if (it.trackId.value == "other") it else it.copy(trackId = TrackId("kept")) }
        assertEquals(expected, history.allEvents())
        assertEquals(expected.map(ListenEvent::serialize), store.lines)
        val stats = history.stats()
        assertEquals(setOf(TrackId("kept"), TrackId("other")), stats.keys)
        val kept = stats.getValue(TrackId("kept"))
        assertEquals(4, kept.plays)
        assertEquals(3, kept.completions)
        assertEquals(1, kept.skips)
        assertEquals(5_000, kept.lastPlayedAtMs)
        // A new process reads the same log.
        assertEquals(stats, DefaultListeningHistory(store).stats())
    }

    @Test
    fun movingListensAgainChangesNothingAndWritesNothing() = runTest {
        var rewrites = 0
        val backing = FakeStore()
        val store = object : HistoryStore by backing {
            override suspend fun replaceAll(lines: List<String>) {
                rewrites++
                backing.replaceAll(lines)
            }
        }
        val history = DefaultListeningHistory(store)
        history.record(event("kept", 1_000))
        history.record(event("copy", 2_000))

        assertEquals(1, history.moveListens(setOf(TrackId("copy")), TrackId("kept")))
        assertEquals(1, rewrites)
        val after = history.allEvents()
        // A merge repeated after an interruption, a copy that was never played, the kept copy named among the
        // copies, no copies at all: none of them touches the log.
        assertEquals(0, history.moveListens(setOf(TrackId("copy")), TrackId("kept")))
        assertEquals(0, history.moveListens(setOf(TrackId("never-played")), TrackId("kept")))
        assertEquals(0, history.moveListens(setOf(TrackId("kept")), TrackId("kept")))
        assertEquals(0, history.moveListens(emptySet(), TrackId("kept")))
        assertEquals(1, rewrites)
        assertEquals(after, history.allEvents())
    }

    @Test
    fun listensMoveInALogLongerThanABackupHolds() = runTest {
        // Later plays are never refused, so a log can be longer than any bound a restore sets; moving listens
        // adds none and must not be refused for it.
        val store = FakeStore(List(40) { event(if (it % 2 == 0) "kept" else "copy", 1_000L + it).serialize() })
        val history = DefaultListeningHistory(store)
        assertEquals(20, history.moveListens(setOf(TrackId("copy")), TrackId("kept")))
        assertEquals(40, history.stats().getValue(TrackId("kept")).plays)
    }

    @Test
    fun aListenRecordedWhileListensMoveIsKept() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backing = FakeStore()
        val store = object : HistoryStore by backing {
            override suspend fun replaceAll(lines: List<String>) {
                gate.await()
                backing.replaceAll(lines)
            }
        }
        val history = DefaultListeningHistory(store)
        history.record(event("copy", 1_000))
        val moving = launch { history.moveListens(setOf(TrackId("copy")), TrackId("kept")) }
        runCurrent()
        val recording = launch { history.record(event("other", 2_000)) }
        runCurrent()
        gate.complete(Unit)
        moving.join()
        recording.join()
        assertEquals(listOf("kept", "other"), history.allEvents().map { it.trackId.value })
        assertEquals(listOf("kept", "other"), DefaultListeningHistory(backing).allEvents().map { it.trackId.value })
    }
}
