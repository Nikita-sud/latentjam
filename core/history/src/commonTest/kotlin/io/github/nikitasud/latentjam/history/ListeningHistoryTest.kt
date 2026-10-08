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
import kotlin.test.assertNull
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
        val newer = v4Line().replaceFirst("v4|", "v5|") + "|a field from the future"
        assertEquals(ListenEvent.parse(v4Line()), ListenEvent.parse(newer))
        assertEquals(v4Line(), assertNotNull(ListenEvent.parse(newer)).serialize())
        assertNull(ListenEvent.parse("v5|3432|1|2||1|0"), "too short to hold the fields this build reads")
        assertNull(ListenEvent.parse(v4Line().replaceFirst("v4|", "vx|")), "not a version")
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
}
