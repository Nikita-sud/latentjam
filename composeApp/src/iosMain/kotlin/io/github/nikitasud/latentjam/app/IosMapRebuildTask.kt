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
import platform.Foundation.NSUserDefaults
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
        val completed = AtomicInt(0)
        // Exactly once, from whichever comes first: the run, its cancellation, or the expiration handler.
        fun complete(success: Boolean) {
            if (!completed.compareAndSet(0, 1)) return
            // An unfinished analysis or an expired run asks again; the old map stays as it was.
            if (!success) scheduleMapRebuild()
            processing.setTaskCompletedWithSuccess(success)
        }
        val job = AppGraph.appScope.launch(Dispatchers.Main) {
            AppGraph.start()
            val outcome = withContext(Dispatchers.Default) {
                MapRebuild.run(isActive = { expired.value == 0 }, trigger = "bgtask")
            }
            complete(outcome == MapRebuild.Outcome.DONE || outcome == MapRebuild.Outcome.NOT_NEEDED)
        }
        // A cancelled job never reaches the completion above: report it here, and ask again.
        job.invokeOnCompletion { cause -> if (cause != null) complete(false) }
        processing.expirationHandler = {
            expired.value = 1
            job.cancel()
            // Not after the job notices: parts of a rebuild do not check for cancellation, and a task still
            // running past its expiration gets the app killed and its background time cut.
            complete(false)
        }
    }
    // Debug check aid: the `map_rebuild_request` user default submits the request at launch, so
    // `_simulateLaunchForTaskWithIdentifier` has one to launch without a library change first.
    val defaults = NSUserDefaults.standardUserDefaults
    if (mapRefreshOverride() && defaults.boolForKey("map_rebuild_request")) {
        defaults.removeObjectForKey("map_rebuild_request")
        scheduleMapRebuild()
    }
}

