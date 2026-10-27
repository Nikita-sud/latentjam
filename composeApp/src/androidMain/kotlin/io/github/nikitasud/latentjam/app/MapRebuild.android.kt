/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Unique work: a request while one is queued keeps the queued one (its constraints still hold). */
internal actual fun scheduleMapRebuild() {
    runCatching {
        val request = OneTimeWorkRequestBuilder<MapRebuildWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(AndroidAppContext.value)
            .enqueueUniqueWork(MapRebuildWorker.NAME, ExistingWorkPolicy.KEEP, request)
    }.onFailure { println("Map rebuild: could not schedule: $it") }
}

/**
 * Debug builds with the refresh override on: `files/map_rebuild_request` (created with `adb shell run-as <pkg>
 * touch …`) enqueues the rebuild at process start and is consumed, so the cold-launch check can force the job
 * (`cmd jobscheduler run -f`) without a library change first.
 */
internal fun scheduleMapRebuildIfRequested(context: Context) {
    if (!mapRefreshOverride()) return
    val request = java.io.File(context.filesDir, "map_rebuild_request")
    if (request.delete()) scheduleMapRebuild()
}

internal actual fun onExternalPower(): Boolean = runCatching {
    val battery = AndroidAppContext.value.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}.getOrDefault(false)

/**
 * Runs [MapRebuild] in the app's process. [LatentJamApplication] has started [AppGraph] before any
 * worker runs. A run stopped by the system (constraints lost, quota) is cancelled at its next poll and
 * retried; one that finds the analysis unfinished is retried with WorkManager's backoff.
 */
internal class MapRebuildWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (MapRebuild.run(isActive = { !isStopped }, trigger = "work#$runAttemptCount")) {
        MapRebuild.Outcome.DONE, MapRebuild.Outcome.NOT_NEEDED -> Result.success()
        MapRebuild.Outcome.NOT_READY, MapRebuild.Outcome.CANCELLED, MapRebuild.Outcome.AGAIN -> Result.retry()
        MapRebuild.Outcome.FAILED -> Result.failure()
    }

    companion object {
        const val NAME: String = "map-rebuild"
    }
}
