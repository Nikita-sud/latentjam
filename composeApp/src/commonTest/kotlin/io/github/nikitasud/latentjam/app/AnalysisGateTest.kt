/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisGateTest {

    private val awake = DeviceConditions(screenOn = true, batteryPercent = 80, plugged = false, thermalStatus = 0)
    private val asleep = awake.copy(screenOn = false)

    private fun AnalysisGateRules.at(
        conditions: DeviceConditions,
        nowMs: Long = 0,
        paused: Boolean = false,
        budgetLeft: Boolean = true,
        refused: Boolean = false,
    ) = decide(paused, conditions, budgetLeft, refused, nowMs)

    @Test
    fun theRowsInOrder() {
        val rules = AnalysisGateRules()
        assertEquals(GateDecision(null, false), rules.at(awake, paused = true))
        assertEquals(GateDecision(AnalysisPhase.RUNNING, false), rules.at(awake))
        assertEquals(GateDecision(AnalysisPhase.RUNNING, false), rules.at(asleep.copy(playing = true)))
        assertEquals(GateDecision(AnalysisPhase.RUNNING, true), rules.at(asleep))
        assertEquals(GateDecision(AnalysisPhase.WAITING_SCREEN, false), rules.at(asleep, budgetLeft = false))
        assertEquals(GateDecision(AnalysisPhase.WAITING_SCREEN, false), rules.at(asleep, refused = true))
        // A refused envelope never stops a pass that the screen or playback keeps awake.
        assertEquals(GateDecision(AnalysisPhase.RUNNING, false), rules.at(awake, refused = true, budgetLeft = false))
    }

    @Test
    fun theUsersPauseWinsOverEveryWait() {
        val rules = AnalysisGateRules()
        assertTrue(rules.at(awake.copy(batteryPercent = 5, thermalStatus = 4), paused = true).stops)
    }

    @Test
    fun lowBatteryWaitsUntilPowerOrTwentyPercent() {
        val rules = AnalysisGateRules()
        assertEquals(AnalysisPhase.RUNNING, rules.at(awake.copy(batteryPercent = 16)).phase)
        assertEquals(AnalysisPhase.WAITING_BATTERY, rules.at(awake.copy(batteryPercent = 15)).phase)
        assertEquals(AnalysisPhase.WAITING_BATTERY, rules.at(awake.copy(batteryPercent = 20)).phase)
        assertEquals(AnalysisPhase.RUNNING, rules.at(awake.copy(batteryPercent = 21)).phase)
        assertEquals(AnalysisPhase.WAITING_BATTERY, rules.at(awake.copy(batteryPercent = 10)).phase)
        assertEquals(AnalysisPhase.RUNNING, rules.at(awake.copy(batteryPercent = 10, plugged = true)).phase)
    }

    @Test
    fun heatWaitsUntilCoolForTwoMinutes() {
        val rules = AnalysisGateRules()
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(awake.copy(thermalStatus = 2), nowMs = 0).phase)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(awake.copy(thermalStatus = 1), nowMs = 1_000).phase)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(awake.copy(thermalStatus = 1), nowMs = 120_999).phase)
        // Warm again restarts the clock.
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(awake.copy(thermalStatus = 2), nowMs = 121_000).phase)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(awake.copy(thermalStatus = 1), nowMs = 122_000).phase)
        assertEquals(AnalysisPhase.RUNNING, rules.at(awake.copy(thermalStatus = 1), nowMs = 242_000).phase)
    }

    @Test
    fun headroomAloneMakesItHotAndItsExitNeedsTheHysteresis() {
        val rules = AnalysisGateRules()
        val withHal = awake.copy(thermalStatusSeen = true, hotHeadroomThreshold = 0.85f)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(withHal.copy(thermalHeadroom = 0.9f), nowMs = 0).phase)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(withHal.copy(thermalHeadroom = 0.8f), nowMs = 200_000).phase)
        rules.at(withHal.copy(thermalHeadroom = 0.7f), nowMs = 300_000)
        assertEquals(AnalysisPhase.RUNNING, rules.at(withHal.copy(thermalHeadroom = 0.7f), nowMs = 420_000).phase)
    }

    @Test
    fun theDevicesOwnThresholdReplacesTheDefault() {
        // The emulator: headroom 0.9 with its MODERATE threshold at 0.967 is not hot.
        val emulator = awake.copy(thermalStatusSeen = true, thermalHeadroom = 0.9f, hotHeadroomThreshold = 0.9667f)
        assertEquals(AnalysisPhase.RUNNING, AnalysisGateRules().at(emulator).phase)
        // Below API 35 (no threshold) a resting 0.9 with status NONE is not heat; with LIGHT it is.
        assertEquals(AnalysisPhase.RUNNING, AnalysisGateRules().at(emulator.copy(hotHeadroomThreshold = Float.NaN)).phase)
        assertEquals(
            AnalysisPhase.WAITING_HOT,
            AnalysisGateRules().at(emulator.copy(hotHeadroomThreshold = Float.NaN, thermalStatus = 1)).phase,
        )
        assertEquals(AnalysisPhase.WAITING_HOT, AnalysisGateRules().at(emulator.copy(thermalHeadroom = 0.97f)).phase)
    }

    @Test
    fun aStatusWaitWithNanHeadroomLeavesOnTheStatus() {
        val rules = AnalysisGateRules()
        val noHeadroom = awake.copy(thermalStatusSeen = true)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(noHeadroom.copy(thermalStatus = 3), nowMs = 0).phase)
        rules.at(noHeadroom.copy(thermalStatus = 0), nowMs = 1_000)
        assertEquals(AnalysisPhase.RUNNING, rules.at(noHeadroom.copy(thermalStatus = 0), nowMs = 121_000).phase)
    }

    @Test
    fun batteryTemperatureStandsInWithoutAThermalHal() {
        val old = awake.copy(thermalStatus = null)
        val rules = AnalysisGateRules()
        assertEquals(AnalysisPhase.RUNNING, rules.at(old.copy(batteryTemperatureC = 41.9f)).phase)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(old.copy(batteryTemperatureC = 42f), nowMs = 0).phase)
        rules.at(old.copy(batteryTemperatureC = 39f), nowMs = 1_000)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(old.copy(batteryTemperatureC = 39f), nowMs = 200_000).phase)
        rules.at(old.copy(batteryTemperatureC = 37f), nowMs = 201_000)
        assertEquals(AnalysisPhase.RUNNING, rules.at(old.copy(batteryTemperatureC = 37f), nowMs = 321_000).phase)

        // API 29+ without a HAL: status stuck at NONE and NaN headroom, so the battery decides.
        val noHal = awake.copy(thermalStatus = 0, thermalStatusSeen = false, batteryTemperatureC = 43f)
        assertEquals(AnalysisPhase.WAITING_HOT, AnalysisGateRules().at(noHal).phase)
        // With a HAL the battery temperature is not read.
        assertEquals(AnalysisPhase.RUNNING, AnalysisGateRules().at(noHal.copy(thermalStatusSeen = true)).phase)
    }

    @Test
    fun theBudgetCountsTheLastDayAndTheHeldLock() {
        val day = WakeLockBudget.WINDOW_MS
        val budget = WakeLockBudget()
        val now = 10 * day
        budget.record(startWallMs = now - day - 60_000, lengthMs = 30 * 60_000, nowWallMs = now)
        // Only the last 29 minutes of that span lie inside the window.
        assertEquals(29 * 60_000L, budget.usedMs(now))
        budget.record(startWallMs = now - 3_600_000, lengthMs = 60 * 60_000, nowWallMs = now)
        assertTrue(budget.left(now))
        assertFalse(budget.left(now, heldSinceWallMs = now - 60_000))
        assertTrue(budget.left(now + day))
        assertEquals(budget.persisted, decodeWakeLockSpans(encodeWakeLockSpans(budget.persisted)))
    }

    @Test
    fun aClockMovedBackKeepsTheSpentBudget() {
        val budget = WakeLockBudget()
        budget.record(startWallMs = 1_000_000, lengthMs = WakeLockBudget.BUDGET_MS, nowWallMs = 1_000_000 + WakeLockBudget.BUDGET_MS)
        assertFalse(budget.left(nowWallMs = 10_000))
    }

    @Test
    fun damagedSpansAreDropped() {
        assertEquals(listOf(5L to 7L), decodeWakeLockSpans("x;5:7;9:-1;3:;:4;1:2:3"))
        assertEquals(emptyList(), decodeWakeLockSpans(null))
    }

    @Test
    fun withoutTheDevicesThresholdAHeatWaitEndsWhenTheStatusReturnsToNone() {
        val rules = AnalysisGateRules()
        val api34 = awake.copy(thermalStatusSeen = true, thermalHeadroom = 0.9f)
        assertEquals(AnalysisPhase.WAITING_HOT, rules.at(api34.copy(thermalStatus = 1), nowMs = 0).phase)
        rules.at(api34.copy(thermalStatus = 0), nowMs = 1_000)
        assertEquals(AnalysisPhase.RUNNING, rules.at(api34.copy(thermalStatus = 0), nowMs = 122_000).phase)
    }
}
