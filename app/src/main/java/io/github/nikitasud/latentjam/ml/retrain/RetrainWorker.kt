/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * RetrainWorker.kt is part of LatentJam.
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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import timber.log.Timber

/**
 * v1 stub for the on-device fine-tune pass. The data plumbing is fully in place — we know how many
 * events we have, what their schema is, and we hold a Room DAO that the next PR can iterate over to
 * build TrainPair tuples. This worker writes a Timber marker so we can verify the trigger fires
 * under realistic conditions without standing up the ORT Training artifacts yet.
 */
@HiltWorker
class RetrainWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val listeningEventDao: ListeningEventDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val total = listeningEventDao.count()
        val triggerCount = inputData.getInt(KEY_TRIGGER_COUNT, total)
        Timber.i(
            "RetrainWorker(stub): would fine-tune predictor on %d events (trigger=%d)",
            total,
            triggerCount,
        )
        // TODO(retrain-v2): generate ORT Training artifacts from scoring_v1.pt offline,
        // ship them as additional assets, and run an OrtTrainingSession here.
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "latentjam-retrain"
        const val KEY_TRIGGER_COUNT = "trigger_count"

        fun enqueue(context: Context, eventCount: Int) {
            val request =
                OneTimeWorkRequestBuilder<RetrainWorker>()
                    .setInputData(Data.Builder().putInt(KEY_TRIGGER_COUNT, eventCount).build())
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(true)
                            .setRequiresDeviceIdle(true)
                            .build()
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
