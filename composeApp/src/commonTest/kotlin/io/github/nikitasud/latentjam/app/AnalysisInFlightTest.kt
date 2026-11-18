/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A file that ends the process while it is analysed must not be analysed again at every launch. */
class AnalysisInFlightTest {

    private val tracks = (0 until 24).map { TrackDescriptor(id = TrackId("t$it"), audioUri = "file:///t$it", durationMs = 200_000) }
    private val poison = tracks[3]
    private val store = InMemoryAnalysisControlStore()
    private var now = 1_000_000L
    private val ledger = AnalysisRetryLedger(null) { now }
    private val analysed = HashSet<TrackId>()
    private val failed = HashSet<TrackId>()
    private var processes = 0

    /**
     * One launch: the pass as AppGraph runs it, over the tracks that still need analysis. Returns false when the
     * process ended in the chunk that held [poison] (or, with [killAtChunk], in that chunk, as a restart would).
     */
    private fun launch(exit: (Int) -> LastExit, killAtChunk: Int? = null): Boolean {
        val pid = ++processes
        val inFlight = AnalysisInFlight(store, pid = { pid }, exitOf = exit)
        val charged = inFlight.charged(tracks, authoritative = true)
        val exhausted = charged.filter(ledger::timedOut)
        failed += exhausted.map { it.id }
        inFlight.forget(exhausted.map { it.id })
        val order = tracks.filterNot { ledger.holding(it) || it.id in failed }
        for ((index, chunk) in analysisChunks(order, inFlight.alone).withIndex()) {
            val todo = chunk.filter { it.id !in analysed }
            if (todo.isNotEmpty()) inFlight.begin(chunk)
            if (index == killAtChunk || poison in todo) return false
            analysed += todo.map { it.id }
            todo.forEach { ledger.forget(it.id) }
            inFlight.end(chunk, completed = true)
        }
        return true
    }

    private val crashed: (Int) -> LastExit = { LastExit.CRASH }
    private val unknown: (Int) -> LastExit = { LastExit.UNKNOWN }

    @Test
    fun `a file that crashes the process is found and left out after three crashes`() {
        assertFalse(launch(crashed), "the chunk with the file ends the process")
        // The chunk's tracks are analysed one by one now: the others get through, the file ends the process alone.
        assertFalse(launch(crashed))
        assertEquals(setOf(TrackId("t0"), TrackId("t1"), TrackId("t2")), analysed)
        // Its second attempt: it waits an hour, and the pass finishes without it.
        assertTrue(launch(crashed))
        assertEquals(tracks.size - 1, analysed.size)
        assertTrue(ledger.holding(poison))
        // After the hour it is tried once more, alone, and then remembered as failed.
        now += 61 * 60_000
        assertFalse(launch(crashed))
        assertTrue(launch(crashed))
        assertEquals(setOf(poison.id), failed)
        assertTrue(launch(crashed))
        assertEquals(tracks.size - 1, analysed.size)
        assertEquals(null, store.crashSuspects)
    }

    @Test
    fun `a process that ends mid-chunk for another reason costs nothing`() {
        assertFalse(launch({ LastExit.OTHER }, killAtChunk = 0))
        assertFalse(launch({ LastExit.OTHER }, killAtChunk = 0))
        assertFalse(launch({ LastExit.OTHER }, killAtChunk = 0))
        assertEquals(0, ledger.size)
        assertEquals(null, store.crashSuspects)
    }

    @Test
    fun `with no word on the end one dirty launch costs nothing and two in a row are a crash`() {
        assertFalse(launch(unknown))
        assertEquals(0, ledger.size, "a restart or a kill looks the same")
        // The same chunk in flight at the next launch too.
        assertFalse(launch(unknown))
        assertEquals(0, ledger.size)
        // Now its tracks have an attempt each and go one by one: three get through before the file.
        assertFalse(launch(unknown))
        assertEquals(5, ledger.size)
        assertEquals(5, AnalysisInFlight(store).alone.size)
        // The file itself must be in flight twice in a row before its second attempt, which makes it wait.
        assertFalse(launch(unknown))
        assertTrue(launch(unknown))
        assertTrue(ledger.holding(poison))
        assertEquals(tracks.size - 1, analysed.size)
    }

    @Test
    fun `with no word on the end two dirty launches on different chunks cost nothing`() {
        // Chunk 0 is in flight when the first process ends; the second gets past it and ends in chunk 1.
        assertEquals(emptySet(), settleInFlight(ids(0..7), emptySet(), emptySet(), LastExit.UNKNOWN).charged)
        val second = settleInFlight(ids(8..15), seenBefore = ids(0..7), suspectsBefore = emptySet(), exit = LastExit.UNKNOWN)
        assertEquals(emptySet(), second.charged)
        assertEquals(ids(8..15), second.seen)
    }

    @Test
    fun `a clean end forgets what was seen and keeps the suspects`() {
        val verdict = settleInFlight(emptySet(), seenBefore = ids(0..7), suspectsBefore = ids(2..3), exit = LastExit.UNKNOWN)
        assertEquals(emptySet(), verdict.charged)
        assertEquals(emptySet(), verdict.seen)
        assertEquals(ids(2..3), verdict.suspects)
    }

    @Test
    fun `the marker is read once per process and names the process that wrote it`() {
        val first = AnalysisInFlight(store, pid = { 41 }, exitOf = { error("no marker yet") })
        assertEquals(emptyList(), first.charged(tracks, authoritative = true))
        first.begin(tracks.take(8))
        assertEquals("41\n" + tracks.take(8).joinToString("\n") { it.id.value }, store.inFlight)

        var asked = -1
        val second = AnalysisInFlight(store, pid = { 42 }, exitOf = { asked = it; LastExit.CRASH })
        assertEquals(tracks.take(8), second.charged(tracks, authoritative = true))
        assertEquals(41, asked)
        assertEquals(null, store.inFlight)
        assertEquals(emptyList(), second.charged(tracks, authoritative = true), "a later pass of the same process")
    }

    @Test
    fun `suspects that left the library are dropped only by a whole library`() {
        store.crashSuspects = "t1\ngone"
        assertEquals(setOf(TrackId("t1"), TrackId("gone")), AnalysisInFlight(store).also { it.charged(tracks.take(4), authoritative = false) }.alone)
        assertEquals(setOf(TrackId("t1")), AnalysisInFlight(store).also { it.charged(tracks, authoritative = true) }.alone)
        assertEquals("t1", store.crashSuspects)
    }

    @Test
    fun `suspects are analysed first and alone`() {
        val chunks = analysisChunks(tracks, alone = setOf(tracks[9].id, tracks[2].id))
        assertEquals(listOf(listOf("t2"), listOf("t9")), chunks.take(2).map { c -> c.map { it.id.value } })
        assertEquals(listOf(8, 8, 6), chunks.drop(2).map { it.size })
        assertEquals(tracks.chunked(8), analysisChunks(tracks, alone = emptySet()))
    }

    private fun ids(range: IntRange) = range.mapTo(LinkedHashSet()) { TrackId("t$it") }
}
