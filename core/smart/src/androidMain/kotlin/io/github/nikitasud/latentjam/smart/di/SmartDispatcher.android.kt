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
    Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            },
            "smart-engine",
        )
    }.asCoroutineDispatcher()
