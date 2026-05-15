/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * RetrainObserver.kt is part of LatentJam.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.ListeningEventDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Watches [ListeningEventDao.countFlow] and enqueues a one-shot [RetrainWorker] each time the total
 * event count crosses a multiple of `mlSettings.retrainThreshold`. The trigger is stable:
 * enqueueing the worker uses [androidx.work.ExistingWorkPolicy.KEEP] so duplicate triggers are
 * coalesced.
 */
@Singleton
class RetrainObserver
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val listeningEventDao: ListeningEventDao,
    private val mlSettings: MlSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastTriggerCount: Int = -1

    fun start() {
        scope.launch {
            listeningEventDao.countFlow().distinctUntilChanged().collect { count ->
                val threshold = mlSettings.retrainThreshold
                if (threshold <= 0) return@collect
                val triggers = count / threshold
                if (triggers > 0 && triggers > lastTriggerCount / threshold.coerceAtLeast(1)) {
                    Timber.i("RetrainObserver: count=%d crossed threshold=%d", count, threshold)
                    RetrainWorker.enqueue(context, count)
                }
                lastTriggerCount = count
            }
        }
    }
}
