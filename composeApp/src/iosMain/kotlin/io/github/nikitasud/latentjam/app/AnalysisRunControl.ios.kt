/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import platform.Foundation.NSUserDefaults

/** iOS reads no device signals before Slice D: analysis runs while the app may run, unless paused. */
internal actual fun analysisDevice(): AnalysisDevice = NoAnalysisDevice

internal actual fun analysisControlStore(): AnalysisControlStore = UserDefaultsAnalysisControlStore

private val analysisClockStart = kotlin.time.TimeSource.Monotonic.markNow()

internal actual fun analysisElapsedMs(): Long = analysisClockStart.elapsedNow().inWholeMilliseconds

private object UserDefaultsAnalysisControlStore : AnalysisControlStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    override var userPaused: Boolean
        get() = defaults.boolForKey(KEY_PAUSED)
        set(value) = defaults.setBool(value, KEY_PAUSED)
    override var stopReason: AnalysisStopReason?
        get() = AnalysisStopReason.fromPersisted(defaults.stringForKey(KEY_STOP_REASON))
        set(value) = defaults.setObject(value?.persisted, KEY_STOP_REASON)
    override var pendingContinue: Boolean
        get() = defaults.boolForKey(KEY_PENDING_CONTINUE)
        set(value) = defaults.setBool(value, KEY_PENDING_CONTINUE)
    override var lastPassComplete: Boolean
        get() = defaults.boolForKey(KEY_LAST_COMPLETE)
        set(value) = defaults.setBool(value, KEY_LAST_COMPLETE)
    override var wakeLockSpans: String?
        get() = defaults.stringForKey(KEY_SPANS)
        set(value) = defaults.setObject(value, KEY_SPANS)

    // iOS holds no wake lock.
    override var wakeLockHeldSince: Long?
        get() = null
        set(value) = Unit
    override var wakeLockRenewedAt: Long?
        get() = null
        set(value) = Unit

    private const val KEY_PAUSED = "analysis.userPaused"
    private const val KEY_STOP_REASON = "analysis.stopReason"
    private const val KEY_PENDING_CONTINUE = "analysis.pendingContinue"
    private const val KEY_LAST_COMPLETE = "analysis.lastPassComplete"
    private const val KEY_SPANS = "analysis.wakeLockSpans"
}
