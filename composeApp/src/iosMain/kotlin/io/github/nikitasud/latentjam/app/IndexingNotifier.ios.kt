/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSRecursiveLock
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Gives an analysis run iOS's finite background-continuation assertion.
 *
 * `beginBackgroundTask` is deliberately acquired on the first progress update, while the app is
 * still active. It lets a run keep making progress when the screen locks or the user briefly
 * switches apps, and requires no notification permission or misleading Background Modes claim.
 * iOS still owns the deadline and may expire the assertion; when it does, the engine's chunk-level
 * persistence remains the recovery contract and the next foreground launch resumes missing work.
 *
 * This is not presented as an Android-style indefinite foreground service. Scheduling a
 * `BGProcessingTask` would not be honest here: the current worker is an in-process coroutine with
 * no durable task payload or cold-launch callback to reconstruct it. Adding a request without that
 * execution path would only queue work that can never run.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosIndexingContinuation : IndexingNotifier {

    private val lock = NSRecursiveLock()
    private var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var runActive = false
    private var expired = false

    override fun show(title: String, text: String, done: Int, total: Int) {
        lock.withLock {
            if (runActive || expired) return
            runActive = true
            task = UIApplication.sharedApplication.beginBackgroundTaskWithName(
                taskName = title.takeIf(String::isNotBlank) ?: DEFAULT_TASK_NAME,
                expirationHandler = ::expire,
            )
        }
    }

    override fun finish() {
        lock.withLock {
            runActive = false
            expired = false
            endTaskLocked()
        }
    }

    private fun expire() {
        lock.withLock {
            expired = true
            endTaskLocked()
        }
    }

    private fun endTaskLocked() {
        val current = task
        if (current == UIBackgroundTaskInvalid) return
        task = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(current)
    }

    private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
        lock()
        return try {
            block()
        } finally {
            unlock()
        }
    }

    private companion object {
        const val DEFAULT_TASK_NAME = "LatentJam library analysis"
    }
}

actual fun indexingNotifierModule(): Module = module {
    single<IndexingNotifier> { IosIndexingContinuation() }
}
