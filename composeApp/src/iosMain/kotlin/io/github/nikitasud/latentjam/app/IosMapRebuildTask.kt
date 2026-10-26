/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGTaskScheduler
import kotlin.concurrent.AtomicInt

/**
 * Registers the Map's deferred rebuild with BGTaskScheduler. Called from the Swift `App.init`, before
 * launch finishes, as BGTaskScheduler requires. A background launch has no UI, so the handler starts
 * [AppGraph] itself (on the main thread, as [AppGraph.start] requires; idempotent with MainViewController).
 */
@OptIn(ExperimentalForeignApi::class)
fun registerMapRebuildTask() {
    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(MAP_REBUILD_TASK, usingQueue = null) { task ->
        val processing = task as? BGProcessingTask ?: return@registerForTaskWithIdentifier
        val expired = AtomicInt(0)
        val job = AppGraph.appScope.launch(Dispatchers.Main) {
            AppGraph.start()
            val outcome = withContext(Dispatchers.Default) {
                MapRebuild.run(isActive = { expired.value == 0 }, trigger = "bgtask")
            }
            val done = outcome == MapRebuild.Outcome.DONE || outcome == MapRebuild.Outcome.NOT_NEEDED
            // An unfinished analysis or an expired run asks again; the old map stays as it was.
            if (!done) scheduleMapRebuild()
            processing.setTaskCompletedWithSuccess(done)
        }
        // A cancelled job never reaches the completion above: report it here, and ask again.
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                scheduleMapRebuild()
                processing.setTaskCompletedWithSuccess(false)
            }
        }
        processing.expirationHandler = {
            expired.value = 1
            job.cancel()
        }
    }
}

