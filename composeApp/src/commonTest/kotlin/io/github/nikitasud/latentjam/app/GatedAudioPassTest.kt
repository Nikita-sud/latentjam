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
        val remembered = mapOf(tracks.last().id to EngineError.InvalidAudio())
        override suspend fun missingFromIndex(ids: List<TrackId>) = ids.count { it !in indexed && it !in remembered }
        override suspend fun stage(chunk: List<TrackDescriptor>, upcoming: List<TrackDescriptor>): IndexReport {
            staged += chunk.map { it.id }
            val todo = chunk.filter { it.id !in indexed && it.id !in remembered }
            indexed += todo.map { it.id }
            onStage(staged.size)
            return IndexReport(indexed = todo.size, failed = 0, errors = emptyMap())
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
