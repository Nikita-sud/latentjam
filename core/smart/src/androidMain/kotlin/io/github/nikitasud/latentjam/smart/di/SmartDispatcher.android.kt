/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.di

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** One low-priority worker: local indexing stays responsive without stealing UI timeslices. */
internal actual fun createPlatformSmartEngineDispatcher(): CoroutineDispatcher =
    lowPrioritySingleThreadDispatcher("smart-engine")

/** Separate so a minute-long cold Map layout cannot queue playback-time engine work behind it. */
internal actual fun createPlatformMapLayoutDispatcher(): CoroutineDispatcher =
    lowPrioritySingleThreadDispatcher("smart-map-layout")

private fun lowPrioritySingleThreadDispatcher(name: String): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            },
            name,
        )
    }.asCoroutineDispatcher()
