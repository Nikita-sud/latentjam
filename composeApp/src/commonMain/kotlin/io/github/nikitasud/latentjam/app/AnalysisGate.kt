/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * What the device reports that decides whether the audio pass runs now (docs/analysis-094-plan.md
 * §4 1e-min, the state table). Platforms without a signal leave its default, which never makes the
 * pass wait.
 */
data class DeviceConditions(
    val screenOn: Boolean = true,
    val playing: Boolean = false,
    /** 0–100, or null when unknown. */
    val batteryPercent: Int? = null,
    val plugged: Boolean = true,
    /** `PowerManager.THERMAL_STATUS_*` (0 = none … 6 = shutdown), or null below API 29. */
    val thermalStatus: Int? = null,
    /** `PowerManager.getThermalHeadroom(10)`; NaN when the device gives none. */
    val thermalHeadroom: Float = Float.NaN,
    /**
     * The device's own headroom for the hot status (API 35+ `getThermalHeadroomThresholds()`); NaN where it gives
     * none, and the fixed default applies. A device whose HAL throttles early reaches the default long before it is
     * hot: the emulator's headroom sits near 0.9 at 30 °C.
     */
    val hotHeadroomThreshold: Float = Float.NaN,
    /** Whether the thermal status has ever left NONE in this process: a device with a thermal HAL. */
    val thermalStatusSeen: Boolean = false,
    /** From `ACTION_BATTERY_CHANGED`, in °C; null when unknown. */
    val batteryTemperatureC: Float? = null,
    /** Battery Saver: the pass goes on, slower (2e, "soft first"), never waits for it. */
    val powerSave: Boolean = false,
)

/** Where the audio pass stands, for the notification and the in-app display. */
enum class AnalysisPhase {
    RUNNING,

    /** Battery at or below 15 % and unplugged (row 2). */
    WAITING_BATTERY,

    /** Too hot (row 3). */
    WAITING_HOT,

    /** Screen off, not playing, and the wake-lock budget spent or the index too large to keep (row 6). */
    WAITING_SCREEN,

    /** The user paused; the pass stops at the next chunk boundary. */
    PAUSING,
}

/** One row of the state table: what the pass does and whether it holds a wake lock. */
data class GateDecision(val phase: AnalysisPhase?, val wakeLock: Boolean) {
    /** Null phase: the user paused, and the pass stops (row 1). */
    val stops: Boolean get() = phase == null
    val runs: Boolean get() = phase == AnalysisPhase.RUNNING
}

/**
 * The state table, rows read top to bottom, first match wins. Stateful only for the hysteresis of
 * rows 2 and 3: a heat wait ends after the device has stayed cool for [COOL_FOR_MS], and a battery
 * wait ends on power or above [BATTERY_RESUME_PERCENT].
 *
 * Nothing here stops the pass on its own: every row but the user's pause is a wait that ends by
 * itself, as 0.9.3's pass resumed on screen-on.
 */
class AnalysisGateRules(
    /** Hot from this `THERMAL_STATUS_*` up (MODERATE). */
    private val hotStatus: Int = 2,
    /** Hot above this headroom. */
    private val hotHeadroom: Float = 0.85f,
) {
    private var lowBattery = false
    private var hot = false
    private var coolSinceMs: Long? = null

    fun decide(
        userPaused: Boolean,
        conditions: DeviceConditions,
        budgetLeft: Boolean,
        envelopeRefused: Boolean,
        nowMs: Long,
    ): GateDecision {
        updateBattery(conditions)
        updateHeat(conditions, nowMs)
        return when {
            userPaused -> GateDecision(phase = null, wakeLock = false)
            lowBattery -> GateDecision(AnalysisPhase.WAITING_BATTERY, wakeLock = false)
            hot -> GateDecision(AnalysisPhase.WAITING_HOT, wakeLock = false)
            conditions.screenOn || conditions.playing -> GateDecision(AnalysisPhase.RUNNING, wakeLock = false)
            budgetLeft && !envelopeRefused -> GateDecision(AnalysisPhase.RUNNING, wakeLock = true)
            else -> GateDecision(AnalysisPhase.WAITING_SCREEN, wakeLock = false)
        }
    }

    private fun updateBattery(conditions: DeviceConditions) {
        val percent = conditions.batteryPercent ?: run {
            lowBattery = false
            return
        }
        lowBattery = if (lowBattery) {
            !conditions.plugged && percent <= BATTERY_RESUME_PERCENT
        } else {
            !conditions.plugged && percent <= BATTERY_WAIT_PERCENT
        }
    }

    private fun updateHeat(conditions: DeviceConditions, nowMs: Long) {
        val status = conditions.thermalStatus
        val headroom = conditions.thermalHeadroom
        val hotHeadroom = conditions.hotHeadroomThreshold.takeUnless { it.isNaN() } ?: hotHeadroom
        // Battery temperature stands in where there is no thermal HAL: below API 29, or a device whose
        // headroom is NaN and whose status has never left NONE.
        val useTemperature = status == null || (headroom.isNaN() && !conditions.thermalStatusSeen)
        val temperature = conditions.batteryTemperatureC
        val tooHot = (status != null && status >= hotStatus) ||
            (!headroom.isNaN() && headroom > hotHeadroom) ||
            (useTemperature && temperature != null && temperature >= HOT_TEMPERATURE_C)
        if (!hot) {
            if (tooHot) {
                hot = true
                coolSinceMs = null
            }
            return
        }
        // A NaN headroom satisfies the exit test, which then rests on the status alone, or on the
        // battery temperature when there is no status to read.
        val cool = (status == null || status <= hotStatus - 1) &&
            (headroom.isNaN() || headroom < hotHeadroom - HEADROOM_HYSTERESIS) &&
            (!useTemperature || temperature == null || temperature < COOL_TEMPERATURE_C)
        if (!cool) {
            coolSinceMs = null
            return
        }
        val since = coolSinceMs ?: nowMs.also { coolSinceMs = it }
        if (nowMs - since >= COOL_FOR_MS) {
            hot = false
            coolSinceMs = null
        }
    }

    companion object {
        const val BATTERY_WAIT_PERCENT = 15
        const val BATTERY_RESUME_PERCENT = 20
        const val HOT_TEMPERATURE_C = 42f
        const val COOL_TEMPERATURE_C = 38f
        const val HEADROOM_HYSTERESIS = 0.1f
        const val COOL_FOR_MS = 2 * 60_000L
    }
}

/**
 * Our own wake-lock time in the last 24 hours, capped at [BUDGET_MS] (90 min). Spans are kept as
 * wall-clock start and length, so the window survives a reboot; a span whose start lies in the
 * future (the clock was moved back) is kept until the clock passes it.
 *
 * Charging counts: Google's sources disagree on whether time on charge is exempt, and this build
 * cannot tell its install channel apart.
 */
class WakeLockBudget(spans: List<Pair<Long, Long>> = emptyList()) {
    private val spans = ArrayList(spans)

    /** Spans as (wall-clock start ms, length ms), for persisting. */
    val persisted: List<Pair<Long, Long>> get() = spans.toList()

    fun usedMs(nowWallMs: Long, heldSinceWallMs: Long? = null): Long {
        prune(nowWallMs)
        val held = heldSinceWallMs?.let { (nowWallMs - it).coerceAtLeast(0) } ?: 0L
        return spans.sumOf { (start, length) -> overlap(start, length, nowWallMs) } + held
    }

    fun left(nowWallMs: Long, heldSinceWallMs: Long? = null): Boolean = usedMs(nowWallMs, heldSinceWallMs) < BUDGET_MS

    fun record(startWallMs: Long, lengthMs: Long, nowWallMs: Long) {
        if (lengthMs > 0) spans += startWallMs to lengthMs
        prune(nowWallMs)
    }

    private fun overlap(start: Long, length: Long, now: Long): Long {
        val windowStart = now - WINDOW_MS
        val from = maxOf(start, windowStart)
        val to = minOf(start + length, now)
        return if (start > now) length else (to - from).coerceAtLeast(0)
    }

    private fun prune(now: Long) {
        spans.removeAll { (start, length) -> start <= now && start + length <= now - WINDOW_MS }
        // A clock far in the past must not keep spans forever: one window's worth is ever kept.
        while (spans.size > MAX_SPANS) spans.removeAt(0)
    }

    companion object {
        const val BUDGET_MS = 90 * 60_000L
        const val WINDOW_MS = 24 * 60 * 60_000L
        private const val MAX_SPANS = 512
    }
}

/** "start:length;…", for a settings string. */
internal fun encodeWakeLockSpans(spans: List<Pair<Long, Long>>): String =
    spans.joinToString(";") { (start, length) -> "$start:$length" }

internal fun decodeWakeLockSpans(value: String?): List<Pair<Long, Long>> =
    value.orEmpty().split(';').mapNotNull { span ->
        val (start, length) = span.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val s = start.toLongOrNull() ?: return@mapNotNull null
        val l = length.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        s to l
    }
