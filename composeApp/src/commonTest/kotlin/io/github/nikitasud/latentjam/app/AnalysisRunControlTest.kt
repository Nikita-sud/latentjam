/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AnalysisRunControlTest {

    private class FakeDevice : AnalysisDevice {
        val state = MutableStateFlow(DeviceConditions(screenOn = true, batteryPercent = 80, plugged = false))
        override val conditions: StateFlow<DeviceConditions> get() = state
        var held = false
        var holds = 0
        override fun refresh() = Unit
        override fun holdWakeLock(timeoutMs: Long) {
            held = true
            holds++
        }
        override fun releaseWakeLock() {
            held = false
        }
    }

    private val device = FakeDevice()
    private val store = InMemoryAnalysisControlStore()
    private val playing = MutableStateFlow(false)
    private var wall = 1_000_000_000L
    private val control = AnalysisRunControl(device, store, playing, wallClockMs = { wall }, monotonicMs = { wall })

    private suspend fun await(phases: MutableList<AnalysisPhase>, waits: IntArray) = control.awaitRunnable(
        envelopeRefused = { false },
        onPhase = { phases += it },
        onWaitStart = { waits[0]++ },
    )

    @Test
    fun screenOffTakesTheWakeLockAndItsTimeIsBudgeted() = runTest {
        device.state.value = device.state.value.copy(screenOn = false)
        assertTrue(await(ArrayList(), IntArray(1)))
        assertTrue(device.held)
        wall += 10 * 60_000
        control.release()
        assertFalse(device.held)
        assertEquals(listOf(1_000_000_000L to 600_000L), decodeWakeLockSpans(store.wakeLockSpans))
    }

    @Test
    fun aSpentBudgetWaitsUntilTheScreenComesOn() = runTest {
        store.wakeLockSpans = encodeWakeLockSpans(listOf(wall - WakeLockBudget.BUDGET_MS to WakeLockBudget.BUDGET_MS))
        val budgeted = AnalysisRunControl(device, store, playing, wallClockMs = { wall }, monotonicMs = { wall })
        device.state.value = device.state.value.copy(screenOn = false)
        val phases = ArrayList<AnalysisPhase>()
        val waits = IntArray(1)
        val result = async {
            budgeted.awaitRunnable(envelopeRefused = { false }, onPhase = { phases += it }, onWaitStart = { waits[0]++ })
        }
        runCurrent()
        assertFalse(result.isCompleted)
        assertFalse(device.held)
        // The minute tick re-checks without a new event and keeps waiting.
        advanceTimeBy(AnalysisRunControl.RECHECK_MS + 1)
        assertFalse(result.isCompleted)
        device.state.value = device.state.value.copy(screenOn = true)
        runCurrent()
        assertTrue(result.await())
        assertEquals(1, waits[0])
        assertEquals(AnalysisPhase.WAITING_SCREEN, phases.first())
        assertEquals(AnalysisPhase.RUNNING, phases.last())
        assertFalse(device.held)
    }

    @Test
    fun thePauseEndsAWait() = runTest {
        device.state.value = device.state.value.copy(batteryPercent = 10)
        val result = async { await(ArrayList(), IntArray(1)) }
        runCurrent()
        assertFalse(result.isCompleted)
        control.setUserPaused(true)
        runCurrent()
        assertFalse(result.await())
        assertTrue(store.userPaused)
    }

    @Test
    fun aMidChunkScreenOffTakesTheLockAtOnceAndPlaybackDropsIt() = runTest {
        assertTrue(await(ArrayList(), IntArray(1)))
        assertFalse(device.held)
        device.state.value = device.state.value.copy(screenOn = false)
        control.onSignal(envelopeRefused = false)
        assertTrue(device.held)
        control.renew()
        assertEquals(2, device.holds)
        playing.value = true
        control.onSignal(envelopeRefused = false)
        assertFalse(device.held)
    }

    @Test
    fun aRefusedEnvelopeTakesNoWakeLock() = runTest {
        device.state.value = device.state.value.copy(screenOn = false)
        control.onSignal(envelopeRefused = true)
        assertFalse(device.held)
        assertEquals(AnalysisPhase.WAITING_SCREEN, control.decide(envelopeRefused = true).phase)
    }
}
