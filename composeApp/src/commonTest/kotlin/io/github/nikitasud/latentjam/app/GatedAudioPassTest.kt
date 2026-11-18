/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.EngineError
import io.github.nikitasud.latentjam.smart.IndexReport
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class GatedAudioPassTest {

    private class Device : AnalysisDevice {
        val state = MutableStateFlow(DeviceConditions(screenOn = true, batteryPercent = 80, plugged = false))
        override val conditions: StateFlow<DeviceConditions> get() = state
        var held = false
        override fun refresh() = Unit
        override fun holdWakeLock(timeoutMs: Long) {
            held = true
        }
        override fun releaseWakeLock() {
            held = false
        }
    }

    private class Engine(tracks: List<TrackDescriptor>, val device: Device) : AudioPassEngine {
        val indexed = HashSet<TrackId>()
        val staged = ArrayList<List<TrackId>>()
        var persists = 0
        var onStage: (Int) -> Unit = {}

        var onPrepare: () -> Unit = {}
        override suspend fun prepare() = onPrepare()

        /** What AppGraph does with each chunk's report (the retry ledger); none by default. */
        var settle: suspend (List<TrackDescriptor>, IndexReport) -> IndexReport = { _, report -> report }
        /** The staged chunk (1-based) the engine reports as stalled, with [stalledErrors]; 0 is none. */
        var stalledAt = 0
        var stalledErrors: Map<TrackId, EngineError> = emptyMap()

        /** Tracks whose analysis times out; with any, [stage] counts timeouts in a row as the engine's lane does. */
        var hung: Set<TrackId> = emptySet()
        val attempts = ArrayList<TrackId>()
        private var timeoutsInARow = 0
        val remembered = mapOf(tracks.last().id to EngineError.InvalidAudio())
        override suspend fun missingFromIndex(ids: List<TrackId>) = ids.count { it !in indexed && it !in remembered }
        override suspend fun stage(chunk: List<TrackDescriptor>, upcoming: List<TrackDescriptor>): IndexReport {
            staged += chunk.map { it.id }
            if (hung.isNotEmpty()) return settle(chunk, stageWithHungTracks(chunk))
            val todo = chunk.filter { it.id !in indexed && it.id !in remembered }
            indexed += todo.map { it.id }
            onStage(staged.size)
            if (staged.size == stalledAt) {
                return IndexReport(indexed = 0, failed = stalledErrors.size, errors = stalledErrors, stalled = true)
            }
            return IndexReport(indexed = todo.size, failed = 0, errors = emptyMap())
        }
        private fun stageWithHungTracks(chunk: List<TrackDescriptor>): IndexReport {
            val errors = LinkedHashMap<TrackId, EngineError>()
            var analysed = 0
            for (track in chunk) {
                if (track.id in indexed || track.id in remembered) continue
                attempts += track.id
                if (track.id in hung) {
                    errors[track.id] = EngineError.AudioUnavailable("Analysis timed out after 90 s")
                    if (++timeoutsInARow == 3) {
                        timeoutsInARow = 0
                        return IndexReport(indexed = analysed, failed = errors.size, errors = errors, stalled = true)
                    }
                } else {
                    indexed += track.id
                    analysed++
                    timeoutsInARow = 0
                }
            }
            return IndexReport(indexed = analysed, failed = errors.size, errors = errors)
        }

        override suspend fun persist() {
            persists++
        }
        override fun envelopeRefused() = false
        override suspend fun rememberedFailures(tracks: List<TrackDescriptor>) = remembered
        override val trackChanges: Flow<Any?> = MutableStateFlow(0)
        override val signals: Flow<Any?> get() = device.state
    }

    private val tracks = (0 until 20).map { TrackDescriptor(id = TrackId("t$it"), audioUri = "file:///t$it") }
    private val device = Device()
    private val engine = Engine(tracks, device)
    private val store = InMemoryAnalysisControlStore()
    private val playing = MutableStateFlow(false)
    private var wall = 5_000_000_000L
    private val control = AnalysisRunControl(device, store, playing, wallClockMs = { wall }, monotonicMs = { wall })
    private val state = MutableStateFlow(AutomaticIndexingState(trackIds = tracks.map { it.id }))
    private val shows = ArrayList<String>()
    private var firstShows = 0

    private fun TestScope.pass() = GatedAudioPass(
        engine = engine,
        control = control,
        state = state,
        scope = backgroundScope,
        show = { text, _, label -> shows += text + (if (label != null) " [${label}]" else "") },
        notices = { notice ->
            when (notice) {
                is AnalysisNotice.Waiting -> "waiting ${notice.phase}"
                AnalysisNotice.PauseAction -> "Pause"
                else -> notice.toString()
            }
        },
        progressText = { done, total, _ -> "$done of $total" },
        onFirstShow = { firstShows++ },
        nowMs = { wall },
        elapsedMs = { wall },
        yieldMs = 0,
    )

    private suspend fun GatedAudioPass.go(gated: Boolean = true, shown: Boolean = true) = run(
        order = tracks,
        allIds = tracks.map { it.id },
        total = tracks.size,
        progress = AnalysisProgress(tracks.size).apply { started(tracks.size) },
        failures = LinkedHashMap(),
        eta = IndexingEta(wall),
        gatedAtStart = gated,
        shownAtStart = shown,
    )

    @Test
    fun aContinueDuringStepsOneToThreeShowsThePassBeforeItsFirstChunk() = runTest {
        // Paused at the start: not shown, state stopped by the user, as a cold start while paused leaves it.
        control.setUserPaused(true)
        state.value = state.value.copy(running = false, stopReason = AnalysisStopReason.USER)
        // The user continued before the first chunk (the pass is live, so no relaunch happens).
        control.setUserPaused(false)
        val pass = pass()
        assertEquals(AudioPassEnd.COMPLETED, pass.go(shown = false))
        assertTrue(pass.shown)
        assertEquals(1, firstShows)
        assertEquals("0 of 20 [Pause]", shows.first())
        assertTrue(state.value.running)
        assertNull(state.value.stopReason)
        assertEquals(listOf(8, 8, 4), engine.staged.map { it.size })
    }

    private suspend fun GatedAudioPass.goWith(
        failures: MutableMap<TrackId, EngineError> = LinkedHashMap(),
        inFlight: InFlightMarker = InFlightMarker,
        order: List<TrackDescriptor> = tracks,
    ) = run(
        order = order, allIds = tracks.map { it.id }, total = tracks.size,
        progress = AnalysisProgress(tracks.size).apply { started(tracks.size) },
        failures = failures, eta = IndexingEta(wall), gatedAtStart = true, shownAtStart = true,
        inFlight = inFlight,
    )

    @Test
    fun threeHungFilesInARowDoNotEndThePass() = runTest {
        // 0.9.5: each hung file cost its 90 s and the rest was analysed in the same pass.
        engine.hung = tracks.take(3).mapTo(HashSet()) { it.id }
        val failures = LinkedHashMap<TrackId, EngineError>()
        assertEquals(AudioPassEnd.COMPLETED, pass().goWith(failures))
        // Everything but the three and the remembered failure; the three were tried once each.
        assertEquals(tracks.drop(3).dropLast(1).map { it.id }.toSet(), engine.indexed)
        assertEquals(tracks.take(3).map { it.id }, engine.attempts.filter { it in engine.hung })
        assertEquals(engine.hung, failures.keys)
        assertEquals<Map<TrackId, EngineError>>(failures, state.value.failures)
    }

    @Test
    fun runsOfHungFilesWithAnalysedTracksBetweenDoNotEndThePass() = runTest {
        engine.hung = (tracks.subList(1, 4) + tracks.subList(9, 12)).mapTo(HashSet()) { it.id }
        assertEquals(AudioPassEnd.COMPLETED, pass().goWith())
        assertEquals(tracks.size - 6 - 1, engine.indexed.size)
    }

    @Test
    fun aPassWhoseTracksOnlyTimeOutEndsAtItsSecondStallInARow() = runTest {
        // The storage or the extractor service stopped answering: nothing is analysed between two stalls.
        engine.hung = tracks.mapTo(HashSet()) { it.id }
        val failures = LinkedHashMap<TrackId, EngineError>()
        val ends = ArrayList<Boolean>()
        val marker = object : InFlightMarker {
            override fun end(chunk: List<TrackDescriptor>, completed: Boolean) {
                ends += completed
            }
        }
        val ended = runCatching { pass().goWith(failures, marker) }.exceptionOrNull()
        assertTrue(ended is AnalysisPassStalled)
        assertEquals(tracks.take(6).map { it.id }, engine.attempts)
        // What the two stalls reported is out, and the chunk they cut short keeps its crash suspects.
        assertEquals(tracks.take(6).map { it.id }.toSet(), failures.keys)
        assertEquals<Map<TrackId, EngineError>>(failures, state.value.failures)
        assertEquals(listOf(false), ends)
        assertFalse(device.held)
        assertFalse(state.value.pausable)
    }

    @Test
    fun aStallAfterAnalysedTracksIsWalkedPastAndAStallRightAfterItEndsThePass() = runTest {
        // t0..t4 are analysed, then six in a row time out.
        engine.hung = tracks.subList(5, 11).mapTo(HashSet()) { it.id }
        val ended = runCatching { pass().goWith() }.exceptionOrNull()
        assertTrue(ended is AnalysisPassStalled)
        assertEquals(tracks.take(11).map { it.id }, engine.attempts)
    }

    @Test
    fun aStallHoldsItsTracksSoThatRetryAfterAStoppedPassGetsPastThem() = runTest {
        // Six hung files first: the pass stops at its second stall. With the ledger as AppGraph settles it, the six
        // wait out their hour; the recovery Retry that follows a stall keeps them waiting, and the next pass in the
        // same process analyses everything else.
        val ledger = AnalysisRetryLedger(null) { wall }
        val settling = AnalysisRetrySettling(ledger, stillMissing = { it.id !in engine.indexed }, remember = {}, save = {})
        engine.hung = tracks.take(6).mapTo(HashSet()) { it.id }
        engine.settle = settling::settle
        settling.passStarted()
        assertTrue(runCatching { pass().goWith() }.exceptionOrNull() is AnalysisPassStalled)
        assertEquals(6, tracks.count(ledger::holding))

        // Retry (the recovery one, "retry everything") pressed on the stopped pass.
        ledgerKeeps(tracks.map { it.id }, recoveryRetry = true, librarySnapshotAuthoritative = true, afterStall = true)
            ?.let(ledger::prune)
        assertEquals(6, tracks.count(ledger::holding))
        settling.passStarted()
        engine.attempts.clear()
        assertEquals(AudioPassEnd.COMPLETED, pass().goWith(order = tracks.filterNot(ledger::holding)))
        assertEquals(tracks.drop(6).dropLast(1).map { it.id }, engine.attempts)
        assertEquals(tracks.size - 6 - 1, engine.indexed.size)
    }

    @Test
    fun aRecoveryRetryThatFollowsNoStallStillClearsTheLedger() {
        val ids = tracks.map { it.id }
        assertEquals(emptyList(), ledgerKeeps(ids, recoveryRetry = true, librarySnapshotAuthoritative = true))
        assertEquals(ids, ledgerKeeps(ids, recoveryRetry = true, librarySnapshotAuthoritative = true, afterStall = true))
        // A partial library after a stall leaves the ledger as it is, as without a Retry.
        assertNull(ledgerKeeps(ids, recoveryRetry = true, librarySnapshotAuthoritative = false, afterStall = true))
    }

    @Test
    fun theChunkInFlightIsMarkedOnlyWhileItIsAnalysedAndSuspectsGoFirstAlone() = runTest {
        val log = ArrayList<String>()
        val marker = object : InFlightMarker {
            override fun begin(chunk: List<TrackDescriptor>) {
                log += "begin " + chunk.joinToString(",") { it.id.value }
            }
            override fun end(chunk: List<TrackDescriptor>, completed: Boolean) {
                log += "end " + chunk.size + " " + completed
            }
        }
        // t19 is a remembered failure: once the rest of its chunk is analysed, that chunk has no work.
        engine.indexed += tracks.subList(16, 19).map { it.id }
        engine.onStage = { chunk -> if (chunk == 3) error("the engine failed in this chunk") }
        val ended = runCatching {
            pass().run(
                order = tracks, allIds = tracks.map { it.id }, total = tracks.size,
                progress = AnalysisProgress(tracks.size).apply { started(tracks.size) },
                failures = LinkedHashMap(), eta = IndexingEta(wall), gatedAtStart = true, shownAtStart = true,
                alone = setOf(tracks[9].id), inFlight = marker,
            )
        }.exceptionOrNull()
        assertTrue(ended is IllegalStateException)
        assertEquals(listOf(listOf("t9"), tracks.take(8).map { it.id.value }), engine.staged.take(2).map { c -> c.map { it.value } })
        assertEquals(
            listOf(
                "begin t9", "end 1 true",
                "begin t0,t1,t2,t3,t4,t5,t6,t7", "end 8 true",
                // The chunk the engine failed in is no longer in flight either.
                "begin t8,t10,t11,t12,t13,t14,t15,t16", "end 8 false",
            ),
            log,
        )
    }

    @Test
    fun aChunkThatRunsOutOfMemoryIsReportedAsCrashedAndTheErrorGoesOn() = runTest {
        val log = ArrayList<String>()
        val marker = object : InFlightMarker {
            override fun begin(chunk: List<TrackDescriptor>) {
                log += "begin " + chunk.size
            }
            override fun crashed(chunk: List<TrackDescriptor>) {
                log += "crashed " + chunk.joinToString(",") { it.id.value }
            }
            override fun end(chunk: List<TrackDescriptor>, completed: Boolean) {
                log += "end " + completed
            }
        }
        engine.onStage = { chunk -> if (chunk == 2) throw OutOfMemoryError() }
        val ended = runCatching { pass().goWith(inFlight = marker) }.exceptionOrNull()
        assertTrue(ended is OutOfMemoryError)
        assertEquals(
            listOf("begin 8", "end true", "begin 8", "crashed t8,t9,t10,t11,t12,t13,t14,t15", "end false"),
            log,
        )
    }

    @Test
    fun anErrorThatIsNotMemoryIsNoCrashedChunk() = runTest {
        var crashed = 0
        val marker = object : InFlightMarker {
            override fun crashed(chunk: List<TrackDescriptor>) {
                crashed++
            }
        }
        engine.onStage = { throw Glitch() }
        assertTrue(runCatching { pass().goWith(inFlight = marker) }.exceptionOrNull() is Glitch)
        assertEquals(0, crashed)
    }

    @Test
    fun theModelIsLoadedBeforeTheFirstChunkIsMarkedAndOnlyWhenThereIsWork() = runTest {
        val log = ArrayList<String>()
        val marker = object : InFlightMarker {
            override fun begin(chunk: List<TrackDescriptor>) {
                log += "begin"
            }
        }
        engine.onPrepare = { log += "prepare" }
        // The first chunk is analysed already: nothing is loaded for it.
        engine.indexed += tracks.take(8).map { it.id }
        assertEquals(AudioPassEnd.COMPLETED, pass().goWith(inFlight = marker))
        assertEquals(listOf("prepare", "begin", "begin"), log)

        // A library with nothing to analyse loads no model.
        log.clear()
        assertEquals(AudioPassEnd.COMPLETED, pass().goWith(inFlight = marker))
        assertEquals(emptyList(), log)
    }

    /** What the platform's OutOfMemoryError is to common code, which cannot name it: an [Error] of that name. */
    private class OutOfMemoryError : Error("out of memory")

    private class Glitch : Error("a bug, not memory")

    @Test
    fun aChunkWithNothingToAnalyseIsNotMarked() = runTest {
        val begun = ArrayList<Int>()
        var ended = 0
        engine.indexed += tracks.take(8).map { it.id }
        val marker = object : InFlightMarker {
            override fun begin(chunk: List<TrackDescriptor>) {
                begun += chunk.size
            }
            override fun end(chunk: List<TrackDescriptor>, completed: Boolean) {
                ended++
            }
        }
        val pass = pass()
        assertEquals(
            AudioPassEnd.COMPLETED,
            pass.run(
                order = tracks, allIds = tracks.map { it.id }, total = tracks.size,
                progress = AnalysisProgress(tracks.size).apply { started(tracks.size - 8) },
                failures = LinkedHashMap(), eta = IndexingEta(wall), gatedAtStart = true, shownAtStart = true,
                inFlight = marker,
            ),
        )
        assertEquals(listOf(8, 4), begun)
        assertEquals(3, ended)
    }

    @Test
    fun aPausedStartStopsAtTheFirstBoundaryWithoutShowing() = runTest {
        control.setUserPaused(true)
        val pass = pass()
        assertEquals(AudioPassEnd.PAUSED, pass.go(shown = false))
        assertTrue(engine.staged.isEmpty())
        assertTrue(shows.isEmpty())
        assertFalse(pass.shown)
    }

    @Test
    fun aPauseMidPassSaysPausingAndStopsAtTheNextBoundary() = runTest {
        engine.onStage = { chunk -> if (chunk == 1) control.setUserPaused(true) }
        val pass = pass()
        assertEquals(AudioPassEnd.PAUSED, pass.go())
        assertEquals(1, engine.staged.size)
        assertEquals("waiting PAUSING", shows.last())
        assertEquals(AnalysisPhase.PAUSING, state.value.phase)
        assertNull(state.value.etaMinutes)
        assertFalse(device.held)
    }

    @Test
    fun anUngatedPassIgnoresThePause() = runTest {
        control.setUserPaused(true)
        assertEquals(AudioPassEnd.COMPLETED, pass().go(gated = false))
        assertEquals(3, engine.staged.size)
    }

    @Test
    fun aScreenOffWaitShowsWhyAndResumesWhenTheScreenComesOn() = runTest {
        store.wakeLockSpans = encodeWakeLockSpans(listOf(wall - WakeLockBudget.BUDGET_MS to WakeLockBudget.BUDGET_MS))
        val spent = AnalysisRunControl(device, store, playing, wallClockMs = { wall }, monotonicMs = { wall })
        engine.onStage = { chunk -> if (chunk == 1) device.state.value = device.state.value.copy(screenOn = false) }
        val pass = GatedAudioPass(
            engine = engine,
            control = spent,
            state = state,
            scope = backgroundScope,
            show = { text, _, _ -> shows += text },
            notices = { notice -> if (notice is AnalysisNotice.Waiting) "waiting ${notice.phase}" else "Pause" },
            progressText = { done, total, _ -> "$done of $total" },
            nowMs = { wall },
            elapsedMs = { wall },
            yieldMs = 0,
        )
        val result = async { pass.go() }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(AnalysisPhase.WAITING_SCREEN, state.value.phase)
        assertEquals("waiting WAITING_SCREEN", shows.last())
        assertNull(state.value.progressAt)
        assertTrue(engine.persists > 0, "a wait flushes first")
        assertFalse(device.held)
        device.state.value = device.state.value.copy(screenOn = true)
        runCurrent()
        assertEquals(AudioPassEnd.COMPLETED, result.await())
        assertNull(state.value.phase)
        assertTrue(shows.contains("8 of 20"))
    }

    @Test
    fun aScreenOffPassHoldsTheWakeLockOnlyWhileItRuns() = runTest {
        device.state.value = device.state.value.copy(screenOn = false)
        var heldDuring = false
        engine.onStage = {
            wall += 1_000
            heldDuring = heldDuring || device.held
        }
        assertEquals(AudioPassEnd.COMPLETED, pass().go())
        assertTrue(heldDuring)
        assertFalse(device.held)
        assertEquals(1, decodeWakeLockSpans(store.wakeLockSpans).size)
        assertNull(store.wakeLockHeldSince)
    }

    @Test
    fun aStoppedPassStillListsTheRememberedFailures() = runTest {
        control.setUserPaused(true)
        val pass = pass()
        val failures = LinkedHashMap<TrackId, EngineError>()
        pass.go(shown = false)
        assertEquals(setOf(tracks.last().id), pass.publishedFailures(tracks, failures).keys)
    }

    @Test
    fun aProcessThatDiedHoldingTheLockStillPaysForIt() {
        store.wakeLockHeldSince = wall - 60 * 60_000
        store.wakeLockRenewedAt = wall - 30 * 60_000
        AnalysisRunControl(device, store, playing, wallClockMs = { wall }, monotonicMs = { wall })
        // Held from its start until the last renewal's 5 min timeout.
        assertEquals(listOf(wall - 60 * 60_000 to 35 * 60_000L), decodeWakeLockSpans(store.wakeLockSpans))
        assertNull(store.wakeLockHeldSince)
    }

    @Test
    fun restartRulesPerStopReason() {
        var now = 0L
        val rules = AnalysisRestartRules { now }
        assertTrue(rules.allowed(null))
        assertTrue(rules.allowed(AnalysisStopReason.TIMEOUT))
        assertTrue(rules.allowed(AnalysisStopReason.SHOW_FAILED))
        assertFalse(rules.allowed(AnalysisStopReason.USER))
        rules.failed()
        now = 59_999
        assertFalse(rules.allowed(AnalysisStopReason.EXCEPTION))
        now = 60_000
        assertTrue(rules.allowed(AnalysisStopReason.EXCEPTION))
        rules.failed()
        now += 9 * 60_000
        assertFalse(rules.allowed(AnalysisStopReason.EXCEPTION))
        now += 60_000
        assertTrue(rules.allowed(AnalysisStopReason.EXCEPTION))
        rules.failed()
        now += 60 * 60_000
        assertFalse(rules.allowed(AnalysisStopReason.EXCEPTION), "then only the next cold start")
        rules.reset()
        rules.failed()
        now += 60_000
        assertTrue(rules.allowed(AnalysisStopReason.EXCEPTION), "a Retry or a completed pass starts over")
    }
}
