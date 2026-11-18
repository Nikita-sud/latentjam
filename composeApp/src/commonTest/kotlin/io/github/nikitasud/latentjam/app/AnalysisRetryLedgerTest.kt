/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisRetryLedgerTest {

    private var now = 1_000_000L
    private val track = TrackDescriptor(id = TrackId("a"), audioUri = "file:///a.mp3", durationMs = 200_000)

    @Test
    fun aTimeoutIsRetriedNextPassThenAfterAnHourThenRemembered() {
        val ledger = AnalysisRetryLedger(null) { now }
        assertFalse(ledger.timedOut(track))
        // The next pass may try at once.
        assertFalse(ledger.holding(track))
        assertFalse(ledger.timedOut(track))
        now += 59 * 60_000
        assertTrue(ledger.holding(track))
        now += 60_000
        assertFalse(ledger.holding(track))
        assertTrue(ledger.timedOut(track), "the third timeout is the last")
        assertEquals(0, ledger.size)
    }

    @Test
    fun aPassLearnsWhenItsWaitingTracksMayBeTriedAgain() {
        val ledger = AnalysisRetryLedger(null) { now }
        val other = TrackDescriptor(id = TrackId("b"), audioUri = "file:///b.mp3", durationMs = 100_000)
        assertEquals(null, ledger.nextReleaseInMs(listOf(track, other)), "nothing waits")
        ledger.timedOut(track)
        assertEquals(null, ledger.nextReleaseInMs(listOf(track, other)), "one timeout waits for nothing")
        ledger.timedOut(track)
        assertEquals(60 * 60_000L, ledger.nextReleaseInMs(listOf(track, other)))
        now += 20 * 60_000
        ledger.timedOut(other)
        ledger.timedOut(other)
        // The first to be free decides when the pass is entered again.
        assertEquals(40 * 60_000L, ledger.nextReleaseInMs(listOf(track, other)))
        assertEquals(60 * 60_000L, ledger.nextReleaseInMs(listOf(other)))
        now += 61 * 60_000
        assertEquals(null, ledger.nextReleaseInMs(listOf(track, other)), "the wait is over: the tracks are in the pass")
    }

    @Test
    fun theLedgerSurvivesARestartAndAChangedFileStartsAgain() {
        val ledger = AnalysisRetryLedger(null) { now }
        ledger.timedOut(track)
        ledger.timedOut(track)
        val restored = AnalysisRetryLedger(ledger.encoded()) { now }
        assertTrue(restored.holding(track))
        val replaced = track.copy(durationMs = 201_000)
        assertFalse(restored.holding(replaced))
        assertFalse(restored.timedOut(replaced), "a new file has its own three attempts")
    }

    @Test
    fun aClockMovedBackHoldsNothingAndDamagedLinesAreDropped() {
        val ledger = AnalysisRetryLedger(null) { now }
        ledger.timedOut(track)
        ledger.timedOut(track)
        now -= 10_000_000
        assertFalse(ledger.holding(track))
        val damaged = AnalysisRetryLedger("x\ny\t1\nz\tid\t9\t5\nb\tid\t1\tnope") { now }
        assertEquals(0, damaged.size)
    }

    @Test
    fun forgetAndPrune() {
        val ledger = AnalysisRetryLedger(null) { now }
        ledger.timedOut(track)
        ledger.timedOut(track.copy(id = TrackId("b")))
        ledger.forget(TrackId("a"))
        ledger.prune(listOf(TrackId("c")))
        assertEquals(0, ledger.size)
    }

    @Test
    fun aPassingFailureBetweenTimeoutsKeepsTheCount() {
        // The app forgets an entry only on an answer about the file (analysed, or failed for good), so a forgiven
        // suspension or codec contention between two timeouts leaves the count where it was.
        val ledger = AnalysisRetryLedger(null) { now }
        ledger.timedOut(track)
        ledger.timedOut(track)
        now += 61 * 60_000
        assertTrue(ledger.timedOut(track))
    }

    @Test
    fun onlyAnAuthoritativeLibraryOrTheRecoveryRetryPrunesTheLedger() {
        val ids = listOf(TrackId("a"), TrackId("b"))
        assertEquals(ids, ledgerKeeps(ids, recoveryRetry = false, librarySnapshotAuthoritative = true))
        assertEquals(null, ledgerKeeps(ids, recoveryRetry = false, librarySnapshotAuthoritative = false))
        assertEquals(emptyList(), ledgerKeeps(ids, recoveryRetry = true, librarySnapshotAuthoritative = false))
    }

    @Test
    fun aFailureOnTheFilesOwnDataCountsAsATimeoutDoesAndAPassingOneDoesNot() {
        assertTrue(EngineError.AudioUnavailable("Analysis timed out after 90 s").countsInRetryLedger())
        assertTrue(EngineError.AudioUnavailable("CodecException at 0ms", trackLocal = true).countsInRetryLedger())
        // The card is away, the codecs are busy, the model is loading, the model itself failed: tried again as before.
        assertFalse(EngineError.AudioUnavailable("FileNotFoundException at 0ms").countsInRetryLedger())
        assertFalse(EngineError.AudioUnavailable("Analysis waited 12 s for the model").countsInRetryLedger())
        assertFalse(EngineError.BackendFailure("Embedding failed").countsInRetryLedger())
        assertFalse(EngineError.ModelUnavailable.countsInRetryLedger())
        // Already an answer about the file: the engine remembers it by itself.
        assertFalse(EngineError.InvalidAudio("no PCM").countsInRetryLedger())
    }

    @Test
    fun theMapRebuildDoesNotWaitForATrackTheLedgerHoldsBack() {
        val ledger = AnalysisRetryLedger(null) { now }
        val other = TrackDescriptor(id = TrackId("b"), audioUri = "file:///b.mp3", durationMs = 100_000)
        ledger.timedOut(track)
        // One failure is tried again by the next pass: the rebuild still waits for it.
        assertEquals(listOf(track, other), MapRebuild.awaitedByRebuild(listOf(track, other), AnalysisRetryLedger(ledger.encoded()) { now }::holding))
        ledger.timedOut(track)
        assertEquals(listOf(other), MapRebuild.awaitedByRebuild(listOf(track, other), AnalysisRetryLedger(ledger.encoded()) { now }::holding))
        now += 61 * 60_000
        assertEquals(listOf(track, other), MapRebuild.awaitedByRebuild(listOf(track, other), AnalysisRetryLedger(ledger.encoded()) { now }::holding))
    }
}
