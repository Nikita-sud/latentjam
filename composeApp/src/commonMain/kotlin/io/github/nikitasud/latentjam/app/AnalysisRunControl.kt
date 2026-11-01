/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The device signals and the wake lock the analysis gate needs from the platform. */
internal interface AnalysisDevice {
    /** Changes on every system event the table reads; playback is added by the caller. */
    val conditions: StateFlow<DeviceConditions>

    /** Re-reads the polled values (thermal headroom), at most once a second. */
    fun refresh()

    /** Holds the CPU awake for at most [timeoutMs]; a second call renews it. */
    fun holdWakeLock(timeoutMs: Long)

    fun releaseWakeLock()
}

/** No signals, no wake lock: the pass always runs unless the user paused it. */
internal object NoAnalysisDevice : AnalysisDevice {
    override val conditions: StateFlow<DeviceConditions> = MutableStateFlow(DeviceConditions())
    override fun refresh() = Unit
    override fun holdWakeLock(timeoutMs: Long) = Unit
    override fun releaseWakeLock() = Unit
}

/** Why the last audio pass stopped before completing; persisted with it. */
enum class AnalysisStopReason(internal val persisted: String) {
    USER("user"),
    TIMEOUT("timeout-6h"),
    SHOW_FAILED("show-failed"),
    EXCEPTION("exception"),
    ;

    internal companion object {
        fun fromPersisted(value: String?): AnalysisStopReason? = entries.firstOrNull { it.persisted == value }
    }
}

/** The analysis controls that outlive the process. */
internal interface AnalysisControlStore {
    var userPaused: Boolean
    var stopReason: AnalysisStopReason?
    var pendingContinue: Boolean
    var lastPassComplete: Boolean
    var wakeLockSpans: String?
}

internal class InMemoryAnalysisControlStore : AnalysisControlStore {
    override var userPaused = false
    override var stopReason: AnalysisStopReason? = null
    override var pendingContinue = false
    override var lastPassComplete = false
    override var wakeLockSpans: String? = null
}

/**
 * Applies the state table to a running audio pass (docs/analysis-094-plan.md §4 1e-min): waits,
 * the user's pause, and the wake lock with its daily budget.
 */
internal class AnalysisRunControl(
    private val device: AnalysisDevice,
    private val store: AnalysisControlStore,
    private val playing: StateFlow<Boolean>,
    private val wallClockMs: () -> Long,
    private val monotonicMs: () -> Long,
) {
    private val rules = AnalysisGateRules()
    private val budget = WakeLockBudget(decodeWakeLockSpans(store.wakeLockSpans))
    private val lock = Mutex()
    private var heldSinceWallMs: Long? = null

    private val mutableUserPaused = MutableStateFlow(store.userPaused)

    /** The user's pause, persisted; only Continue, Rebuild or a finished pass clear it. */
    val userPaused: StateFlow<Boolean> = mutableUserPaused.asStateFlow()

    fun setUserPaused(paused: Boolean) {
        store.userPaused = paused
        mutableUserPaused.value = paused
    }

    /** The table's row for now. [envelopeRefused]: the audio index is too large to keep. */
    suspend fun decide(envelopeRefused: Boolean): GateDecision = lock.withLock {
        device.refresh()
        rules.decide(
            userPaused = mutableUserPaused.value,
            conditions = device.conditions.value.copy(playing = playing.value),
            budgetLeft = budget.left(wallClockMs(), heldSinceWallMs),
            envelopeRefused = envelopeRefused,
            nowMs = monotonicMs(),
        )
    }

    /**
     * Suspends while the table says wait, re-checking on every signal change and once a minute.
     * Returns false when the user paused. [onPhase] sees each row; [onWaitStart] runs once, when a
     * wait begins (the pass flushes its pending work there).
     */
    suspend fun awaitRunnable(
        envelopeRefused: () -> Boolean,
        onPhase: suspend (AnalysisPhase) -> Unit,
        onWaitStart: suspend () -> Unit,
    ): Boolean {
        var waiting = false
        while (true) {
            val decision = decide(envelopeRefused())
            applyWakeLock(decision.wakeLock)
            val phase = decision.phase ?: return false
            onPhase(phase)
            if (decision.runs) return true
            if (!waiting) {
                waiting = true
                onWaitStart()
            }
            val seen = Triple(device.conditions.value, playing.value, mutableUserPaused.value)
            withTimeoutOrNull(RECHECK_MS) {
                combine(device.conditions, playing, mutableUserPaused, ::Triple).first { it != seen }
            }
        }
    }

    /**
     * Mid-chunk signal change (the screen went off, playback stopped): takes or drops the wake lock
     * at once, so the CPU cannot sleep before the next chunk boundary. Waits and stops still take
     * effect only at a boundary.
     */
    suspend fun onSignal(envelopeRefused: Boolean) {
        val decision = decide(envelopeRefused)
        if (decision.runs) applyWakeLock(decision.wakeLock)
    }

    /** Renews a held wake lock; called per track. */
    suspend fun renew() = lock.withLock {
        if (heldSinceWallMs != null) device.holdWakeLock(WAKE_LOCK_TIMEOUT_MS)
    }

    /** The pass ended or the foreground state was lost. */
    suspend fun release() = applyWakeLock(false)

    private suspend fun applyWakeLock(hold: Boolean) = lock.withLock {
        val since = heldSinceWallMs
        if (hold) {
            if (since == null) heldSinceWallMs = wallClockMs()
            device.holdWakeLock(WAKE_LOCK_TIMEOUT_MS)
        } else if (since != null) {
            device.releaseWakeLock()
            heldSinceWallMs = null
            val now = wallClockMs()
            budget.record(startWallMs = since, lengthMs = now - since, nowWallMs = now)
            store.wakeLockSpans = encodeWakeLockSpans(budget.persisted)
        }
    }

    companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 5 * 60_000L
        const val RECHECK_MS = 60_000L
    }
}

/** This platform's [AnalysisDevice]. */
internal expect fun analysisDevice(): AnalysisDevice

/** This platform's [AnalysisControlStore]. */
internal expect fun analysisControlStore(): AnalysisControlStore
