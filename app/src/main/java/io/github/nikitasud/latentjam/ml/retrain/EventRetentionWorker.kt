/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * EventRetentionWorker.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.nikitasud.latentjam.ml.retrain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Periodic prune of old [io.github.nikitasud.latentjam.ml.data.ListeningEventEntity] rows. Bounds
 * the table so a long-running install can't accumulate years of events — at ~50 events/day a
 * daily-driver crosses 18k rows in a year. Without bounds the table feeds RetrainObserver's
 * countFlow, which triggers retrains based on a strictly-growing count.
 *
 * Cutoff is `now - retentionDays * 24h`. Rows with `startedAtMs < cutoff` are deleted.
 * `retentionDays = 0` is the disable signal — the worker logs and returns success.
 */
@HiltWorker
class EventRetentionWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val listeningEventDao: ListeningEventDao,
    private val mlSettings: MlSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = mlSettings.eventRetentionDays
        if (days <= 0) {
            Timber.v("EventRetentionWorker: retention disabled (days=%d)", days)
            return Result.success()
        }
        val cutoffMs = System.currentTimeMillis() - days * 86_400_000L
        val deleted = listeningEventDao.deleteOlderThan(cutoffMs)
        Timber.i(
            "EventRetentionWorker: pruned %d events older than %d days (cutoff=%d)",
            deleted,
            days,
            cutoffMs,
        )
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "latentjam-event-retention"

        /**
         * Schedule the periodic prune. Idempotent — safe to call from `Application.onCreate` on
         * every launch; WorkManager dedupes by [WORK_NAME] under [ExistingPeriodicWorkPolicy.KEEP].
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<EventRetentionWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
