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

    /** Whether a gated pass listens to the signals (event wake locks are taken only then). */
    fun listen(active: Boolean) = Unit
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

    /** When the current wake lock was taken; set while it is held. */
    var wakeLockHeldSince: Long?

    /** When the held wake lock was last renewed. */
    var wakeLockRenewedAt: Long?
}

internal class InMemoryAnalysisControlStore : AnalysisControlStore {
    override var userPaused = false
    override var stopReason: AnalysisStopReason? = null
    override var pendingContinue = false
    override var lastPassComplete = false
    override var wakeLockSpans: String? = null
    override var wakeLockHeldSince: Long? = null
    override var wakeLockRenewedAt: Long? = null
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
    private val budget = WakeLockBudget(decodeWakeLockSpans(store.wakeLockSpans)).also { budget ->
        // A process that died holding the lock never recorded its span: it lasted until the last renewal's timeout.
        store.wakeLockHeldSince?.let { since ->
            val now = wallClockMs()
            val until = (store.wakeLockRenewedAt ?: since) + WAKE_LOCK_TIMEOUT_MS
            budget.record(since, (minOf(until, now) - since).coerceAtLeast(0), now)
            store.wakeLockSpans = encodeWakeLockSpans(budget.persisted)
            store.wakeLockHeldSince = null
        }
    }
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
            // Read before deciding, so a change during the decision or the flush below still wakes the wait.
            val seen = Triple(device.conditions.value, playing.value, mutableUserPaused.value)
            val decision = decide(envelopeRefused())
            applyWakeLock(decision.wakeLock)
            val phase = decision.phase ?: return false
            onPhase(phase)
            if (decision.runs) return true
            if (!waiting) {
                waiting = true
                onWaitStart()
            }
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
        if (heldSinceWallMs != null) {
            device.holdWakeLock(WAKE_LOCK_TIMEOUT_MS)
            store.wakeLockRenewedAt = wallClockMs()
        }
    }

    /** The pass ended or the foreground state was lost. */
    suspend fun release() {
        applyWakeLock(false)
        device.listen(false)
    }

    /** A gated pass starts listening to the signals. */
    fun listen() = device.listen(true)

    private suspend fun applyWakeLock(hold: Boolean) = lock.withLock {
        val since = heldSinceWallMs
        if (hold) {
            if (since == null) {
                heldSinceWallMs = wallClockMs()
                store.wakeLockHeldSince = heldSinceWallMs
            }
            device.holdWakeLock(WAKE_LOCK_TIMEOUT_MS)
        } else if (since != null) {
            device.releaseWakeLock()
            heldSinceWallMs = null
            val now = wallClockMs()
            budget.record(startWallMs = since, lengthMs = now - since, nowWallMs = now)
            store.wakeLockSpans = encodeWakeLockSpans(budget.persisted)
            store.wakeLockHeldSince = null
        }
    }

    companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 5 * 60_000L
        const val RECHECK_MS = 60_000L
    }
}

/**
 * Whether an automatic call for the same library restarts a pass that stopped (docs/analysis-094-plan.md 1e-min):
 * the 6 h cap and a refused start always, a failure after 1 min and then 10 min (then only at the next cold start), the
 * user's pause never (only Continue does).
 */
internal class AnalysisRestartRules(private val nowMs: () -> Long = ::analysisElapsedMs) {
    private var restarts = 0
    private var failedAtMs: Long? = null

    fun allowed(reason: AnalysisStopReason?): Boolean = when (reason) {
        null, AnalysisStopReason.TIMEOUT, AnalysisStopReason.SHOW_FAILED -> true
        AnalysisStopReason.USER -> false
        AnalysisStopReason.EXCEPTION -> {
            val after = when (restarts) {
                0 -> 60_000L
                1 -> 10 * 60_000L
                else -> null
            }
            val since = failedAtMs
            if (after == null || (since != null && nowMs() - since < after)) {
                false
            } else {
                restarts++
                true
            }
        }
    }

    fun failed() {
        failedAtMs = nowMs()
    }

    /** A Retry or a completed pass starts the backoff over. */
    fun reset() {
        restarts = 0
        failedAtMs = null
    }
}

/** A clock that keeps counting in deep sleep (Android's elapsedRealtime), for the heat wait's hysteresis. */
internal expect fun analysisElapsedMs(): Long

/** This platform's [AnalysisDevice]. */
internal expect fun analysisDevice(): AnalysisDevice

/** This platform's [AnalysisControlStore]. */
internal expect fun analysisControlStore(): AnalysisControlStore
