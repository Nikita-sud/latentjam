/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory [SmartLogStore] with the file stores' contract (whole lines kept, the tail trimmed on a line boundary). */
internal class MemorySmartLogStore(var failing: Boolean = false) : SmartLogStore {
    val bytes = ArrayList<Byte>()
    var trims = 0

    override suspend fun append(text: String) {
        check(!failing) { "disk full" }
        if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) bytes += '\n'.code.toByte()
        bytes += text.encodeToByteArray().toList()
    }

    override suspend fun size(): Long = bytes.size.toLong()

    override suspend fun keepTail(maxBytes: Long) {
        trims++
        if (bytes.size <= maxBytes) return
        var start = (bytes.size - maxBytes).toInt()
        while (start < bytes.size && bytes[start - 1] != '\n'.code.toByte()) start++
        val kept = bytes.subList(start, bytes.size).toList()
        bytes.clear()
        bytes += kept
    }

    override suspend fun forEachLine(line: (String) -> Unit) {
        val text = bytes.toByteArray().decodeToString()
        val parts = text.split('\n')
        // The last part is empty after a final line feed, or a torn line: left out either way.
        parts.dropLast(1).forEach(line)
    }

    override suspend fun clear() = bytes.clear()
}

class SmartImpressionLogTest {
    private fun impression(hop: Int, track: String = "00112233aabbccdd", source: String = "R2") = SmartImpression(
        atMs = 1_000L + hop, queue = "q1.3.1700", plan = 0, hop = hop, track = track, rank = hop, score = 0.25 * hop,
        probability = 1.0 / 3, tie = true, source = source, model = "core+lfm",
    )

    @Test
    fun linesRoundTrip() {
        val i = impression(2)
        assertEquals(i, SmartImpressionLog.parse(SmartImpressionLog.serialize(i)))
        val ladder = i.copy(score = Double.NaN, probability = 1.0, tie = false, source = "ladder:repeat")
        val back = SmartImpressionLog.parse(SmartImpressionLog.serialize(ladder)) as SmartImpression
        assertTrue(back.score.isNaN())
        assertEquals(ladder.copy(score = 0.0), back.copy(score = 0.0))
        val o = SmartOutcome(5L, "q1.3.1700", 1, 4, "ff", SmartOutcomeKind.SKIPPED, 12_000L, 0.05)
        assertEquals(o, SmartImpressionLog.parse(SmartImpressionLog.serialize(o)))
        val u = SmartOutcome(5L, "q", 0, 1, "ff", SmartOutcomeKind.UNREACHED)
        assertEquals(u, SmartImpressionLog.parse(SmartImpressionLog.serialize(u)))
        assertNull(SmartImpressionLog.parse("si1|torn"))
        assertNull(SmartImpressionLog.parse("v4|something else"))
    }

    @Test
    fun fieldsCannotBreakALine() {
        val line = SmartImpressionLog.serialize(impression(1, source = "R|2\nx", track = "é"))
        assertEquals(12, line.split('|').size)
        assertTrue('\n' !in line)
        assertEquals("R_2_x", (SmartImpressionLog.parse(line) as SmartImpression).source)
    }

    @Test
    fun theLogStaysUnderItsCapKeepingTheNewestWholeLines() = runTest {
        val store = MemorySmartLogStore()
        val log = SmartImpressionLog(store, capBytes = 4_000, keepBytes = 3_000)
        repeat(200) { log.recordImpressions(listOf(impression(it + 1))) }
        assertTrue(store.size() <= 4_000, "size ${store.size()}")
        assertTrue(store.trims > 0)
        val lines = ArrayList<String>()
        log.forEachLine { lines += it }
        val hops = lines.map { (SmartImpressionLog.parse(it) as SmartImpression).hop }
        assertEquals(200, hops.last(), "the newest line is kept")
        assertEquals((hops.first()..200).toList(), hops, "a contiguous, whole-line tail")
    }

    @Test
    fun aFailingStoreNeverThrows() = runTest {
        val store = MemorySmartLogStore(failing = true)
        val log = SmartImpressionLog(store)
        log.recordImpressions(listOf(impression(1)))
        log.recordOutcomes(listOf(SmartOutcome(1, "q", 0, 1, "t", SmartOutcomeKind.PLAYED)))
        assertEquals(0L, store.size())
    }
}

class SmartOutcomeTrackerTest {
    private fun row(id: String, hop: Int, queue: String = "q", plan: Int = 0) = TrackId(id) to SmartImpression(
        0, queue, plan, hop, "k$id", hop, 1.0, 1.0, false, "whole", "m",
    )

    private fun listen(id: String, skipped: Boolean, played: Long = 30_000, duration: Long? = 200_000) =
        ListenEvent(TrackId(id), 0, played, duration, completed = !skipped, skipped = skipped, listenedMs = played)

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

    @Test
    fun playedAndSkippedComeFromTheListen() {
        val t = SmartOutcomeTracker()
        assertTrue(t.onPlan(listOf(row("a", 1), row("b", 2)), 0).isEmpty())
        t.onServed(a)
        assertTrue(t.onSnapshot(TrackId("seed"), setOf(TrackId("seed"), a), false, 1).isEmpty())
        assertTrue(t.onSnapshot(a, setOf(TrackId("seed"), a), false, 2).isEmpty())
        val skipped = t.onListen(listen("a", skipped = true, played = 10_000), 3).single()
        assertEquals(SmartOutcomeKind.SKIPPED, skipped.kind)
        assertEquals(10_000L, skipped.elapsedMs)
        assertEquals(0.05, skipped.fraction)
        assertEquals(1, skipped.hop)
        t.onServed(b)
        t.onSnapshot(b, setOf(a, b), false, 4)
        val played = t.onListen(listen("b", skipped = false, played = 200_000), 5).single()
        assertEquals(SmartOutcomeKind.PLAYED, played.kind)
        assertEquals(1.0, played.fraction)
        assertEquals(0, t.openRows)
    }

    @Test
    fun aListenOfATrackThatNeverStartedFromThePlanIsNotItsOutcome() {
        val t = SmartOutcomeTracker()
        t.onPlan(listOf(row("a", 1)), 0)
        assertTrue(t.onListen(listen("a", skipped = false), 1).isEmpty())
        assertEquals(1, t.openRows)
    }

    @Test
    fun aServedRowTakenOutOfTheQueueIsRemoved() {
        val t = SmartOutcomeTracker()
        t.onPlan(listOf(row("a", 1), row("b", 2)), 0)
        t.onServed(a)
        // Served but not yet appended: the queue does not hold it yet, which is not a removal.
        assertTrue(t.onSnapshot(c, setOf(c), false, 1).isEmpty())
        t.onSnapshot(c, setOf(c, a), false, 2)
        val removed = t.onSnapshot(c, setOf(c), false, 3).single()
        assertEquals(SmartOutcomeKind.REMOVED, removed.kind)
        assertEquals("ka", removed.track)
    }

    @Test
    fun otherMusicChosenByTheListenerReplacesWhatWaited() {
        val t = SmartOutcomeTracker()
        t.onPlan(listOf(row("a", 1), row("b", 2), row("c", 3)), 0)
        t.onServed(a)
        t.onSnapshot(a, setOf(a), false, 1)
        val out = t.onSnapshot(TrackId("other"), setOf(TrackId("other")), userPick = true, atMs = 2)
        assertEquals(listOf("kb", "kc"), out.map { it.track })
        assertTrue(out.all { it.kind == SmartOutcomeKind.REPLACED })
        // The track that was playing still gets its own outcome from its listen.
        assertEquals(SmartOutcomeKind.PLAYED, t.onListen(listen("a", skipped = false), 3).single().kind)
    }

    @Test
    fun aNewerPlanSupersedesWhatWasNeverServed() {
        val t = SmartOutcomeTracker()
        t.onPlan(listOf(row("a", 1), row("b", 2)), 0)
        t.onServed(a)
        val out = t.onPlan(listOf(row("c", 1, plan = 1)), 1)
        assertEquals(listOf("kb"), out.map { it.track })
        assertEquals(SmartOutcomeKind.UNREACHED, out.single().kind)
        // A new queue drops the old queue's served rows that never started too.
        val next = t.onPlan(listOf(row("x", 1, queue = "q2")), 2)
        assertEquals(setOf("ka", "kc"), next.map { it.track }.toSet())
    }

    @Test
    fun openRowsAreBounded() {
        val t = SmartOutcomeTracker(maxRows = 10)
        t.onPlan((1..40).map { row("t$it", it) }, 0)
        assertEquals(10, t.openRows)
    }
}
