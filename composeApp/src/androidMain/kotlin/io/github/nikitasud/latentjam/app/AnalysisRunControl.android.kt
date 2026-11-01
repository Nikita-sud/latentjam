/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal actual fun analysisDevice(): AnalysisDevice = AndroidAnalysisDevice

internal actual fun analysisControlStore(): AnalysisControlStore = PreferencesAnalysisControlStore

internal actual fun analysisElapsedMs(): Long = SystemClock.elapsedRealtime()

/**
 * Screen, power, battery and thermal signals for the state table, and the analysis wake lock.
 * Registered once for the process, on first use; the receivers cost nothing while nothing changes.
 */
private object AndroidAnalysisDevice : AnalysisDevice {
    private val context: Context get() = AndroidAppContext.value
    private val power by lazy { context.getSystemService(PowerManager::class.java) }

    private val state = MutableStateFlow(DeviceConditions())
    private var registered = false
    private var lastHeadroomReadMs = 0L

    override val conditions: StateFlow<DeviceConditions>
        get() {
            register()
            return state
        }

    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatentJam:analysis").apply { setReferenceCounted(false) }
    }

    /** Short locks so the CPU cannot sleep between a screen-off and the pass taking its own lock. */
    private val eventLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LatentJam:analysis-event").apply { setReferenceCounted(false) }
    }

    @Synchronized
    private fun register() {
        if (registered) return
        registered = true
        val status = thermalStatus()
        state.value = DeviceConditions(
            screenOn = power.isInteractive,
            thermalStatus = status,
            thermalStatusSeen = status != null && status != 0,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                // Only where the CPU could sleep before the pass takes its own lock, and only while a pass listens.
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                    -> if (passListening) eventLock.acquire(EVENT_LOCK_MS)
                }
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> state.update { it.copy(screenOn = false) }
                    Intent.ACTION_SCREEN_ON -> state.update { it.copy(screenOn = true) }
                    Intent.ACTION_POWER_CONNECTED -> state.update { it.copy(plugged = true) }
                    Intent.ACTION_POWER_DISCONNECTED -> state.update { it.copy(plugged = false) }
                    Intent.ACTION_BATTERY_CHANGED -> state.update { it.withBattery(intent) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // The sticky battery broadcast is delivered at once, so the first read already has it.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { sticky -> state.update { it.withBattery(sticky) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.addThermalStatusListener { status ->
                state.update { it.copy(thermalStatus = status, thermalStatusSeen = it.thermalStatusSeen || status != 0) }
            }
        }
    }

    private fun DeviceConditions.withBattery(intent: Intent): DeviceConditions {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return copy(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            batteryTemperatureC = if (tenths != Int.MIN_VALUE) tenths / 10f else null,
        )
    }

    private fun thermalStatus(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else null

    @Synchronized
    override fun refresh() {
        register()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.elapsedRealtime()
        // The system returns NaN to callers that read more than once a second.
        if (now - lastHeadroomReadMs < 1_000) return
        lastHeadroomReadMs = now
        val headroom = runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_S) }.getOrDefault(Float.NaN)
        val threshold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { power.thermalHeadroomThresholds[HOT_STATUS] }.getOrNull() ?: Float.NaN
        } else {
            Float.NaN
        }
        state.update { it.copy(thermalHeadroom = headroom, hotHeadroomThreshold = threshold, thermalStatus = thermalStatus()) }
    }

    /** A gated pass is running; set by the first wake-lock decision and cleared at release. */
    @Volatile
    private var passListening = false

    override fun holdWakeLock(timeoutMs: Long) {
        passListening = true
        wakeLock.acquire(timeoutMs)
    }

    override fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun listen(active: Boolean) {
        passListening = active
    }

    private const val EVENT_LOCK_MS = 3_000L
    private const val HEADROOM_FORECAST_S = 10

    /** The status the table calls hot (AnalysisGateRules' default, MODERATE). */
    private const val HOT_STATUS = PowerManager.THERMAL_STATUS_MODERATE
}

/** Plain preferences: the service reads them too, with no composition or coroutine in reach. */
private object PreferencesAnalysisControlStore : AnalysisControlStore {
    private val preferences by lazy {
        AndroidAppContext.value.getSharedPreferences("analysis_control", Context.MODE_PRIVATE)
    }

    // commit(), not apply(): Continue and Pause must be durable before the tap is handled.
    override var userPaused: Boolean
        get() = preferences.getBoolean(KEY_PAUSED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PAUSED, value).commit()
        }
    override var stopReason: AnalysisStopReason?
        get() = AnalysisStopReason.fromPersisted(preferences.getString(KEY_STOP_REASON, null))
        set(value) {
            preferences.edit().putString(KEY_STOP_REASON, value?.persisted).commit()
        }
    override var pendingContinue: Boolean
        get() = preferences.getBoolean(KEY_PENDING_CONTINUE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PENDING_CONTINUE, value).commit()
        }
    override var lastPassComplete: Boolean
        get() = preferences.getBoolean(KEY_LAST_COMPLETE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_LAST_COMPLETE, value).apply()
        }
    override var wakeLockSpans: String?
        get() = preferences.getString(KEY_SPANS, null)
        set(value) {
            preferences.edit().putString(KEY_SPANS, value).apply()
        }
    override var wakeLockHeldSince: Long?
        get() = preferences.getLong(KEY_HELD_SINCE, -1L).takeIf { it >= 0 }
        set(value) {
            preferences.edit().putLong(KEY_HELD_SINCE, value ?: -1L).apply()
        }
    override var wakeLockRenewedAt: Long?
        get() = preferences.getLong(KEY_RENEWED_AT, -1L).takeIf { it >= 0 }
        set(value) {
            preferences.edit().putLong(KEY_RENEWED_AT, value ?: -1L).apply()
        }
    override var retryLedger: String?
        get() = preferences.getString(KEY_RETRY_LEDGER, null)
        set(value) {
            preferences.edit().putString(KEY_RETRY_LEDGER, value).apply()
        }

    private const val KEY_PAUSED = "userPaused"
    private const val KEY_STOP_REASON = "stopReason"
    private const val KEY_PENDING_CONTINUE = "pendingContinue"
    private const val KEY_LAST_COMPLETE = "lastPassComplete"
    private const val KEY_SPANS = "wakeLockSpans"
    private const val KEY_HELD_SINCE = "wakeLockHeldSince"
    private const val KEY_RENEWED_AT = "wakeLockRenewedAt"
    private const val KEY_RETRY_LEDGER = "retryLedger"
}
