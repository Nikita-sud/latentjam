/*
 * Copyright (c) 2026 LatentJam Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.github.nikitasud.latentjam.ml.encoder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.ml.MlSettings
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingDao
import io.github.nikitasud.latentjam.ml.data.TrackEmbeddingEntity
import io.github.nikitasud.latentjam.music.MusicRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import timber.log.Timber

/**
 * Background pass that embeds every song in the device library that doesn't yet have a
 * cached embedding under the current model version. Foreground service so the OS lets the
 * pass survive screen-off; constrained to non-low-battery.
 */
@HiltWorker
class EmbeddingWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: MusicRepository,
    private val trackEmbeddingDao: TrackEmbeddingDao,
    private val embeddingExtractor: EmbeddingExtractor,
    private val mlSettings: MlSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val library = musicRepository.library ?: run {
            Timber.d("EmbeddingWorker: library not loaded yet, retrying later")
            return@withContext Result.retry()
        }
        val version = mlSettings.embeddingVersion
        val knownUids: Set<Music.UID> = trackEmbeddingDao.knownUids(version).toSet()
        val pending: List<Song> = library.songs.filter { it.uid !in knownUids }
        if (pending.isEmpty()) {
            Timber.d("EmbeddingWorker: nothing to embed (version=%s)", version)
            return@withContext Result.success()
        }

        Timber.i("EmbeddingWorker: embedding %d/%d tracks", pending.size, library.songs.size)
        val startedAtMs = System.currentTimeMillis()
        // On Android 14+ a DATA_SYNC foreground service can only be started while the
        // app is in the foreground; otherwise the OS throws
        // ForegroundServiceStartNotAllowedException. Treat the notification as a UX
        // nice-to-have rather than a precondition for embedding — if it fails we still
        // run, just without a progress bar.
        var foregroundOk = trySetForeground(makeForegroundInfo(0, pending.size, startedAtMs))

        var successCount = 0
        for ((idx, song) in pending.withIndex()) {
            if (isStopped) {
                Timber.d("EmbeddingWorker: stopped after %d/%d", idx, pending.size)
                return@withContext Result.retry()
            }
            try {
                val result = embeddingExtractor.embedWithFeatures(song.uri)
                trackEmbeddingDao.upsert(
                    TrackEmbeddingEntity(
                        songUid = song.uid,
                        modelVersion = version,
                        embedding = floatsToBytes(result.embedding),
                        embeddedAtMs = System.currentTimeMillis(),
                        tempo = result.features.bpm,
                        energy = result.features.energy,
                    )
                )
                successCount++
            } catch (e: Exception) {
                Timber.w(e, "EmbeddingWorker: failed to embed %s", song.uid)
            }
            if (foregroundOk &&
                ((idx + 1) % PROGRESS_REPORT_EVERY == 0 || idx == pending.size - 1)
            ) {
                foregroundOk = trySetForeground(
                    makeForegroundInfo(idx + 1, pending.size, startedAtMs)
                )
            }
        }
        val totalMs = System.currentTimeMillis() - startedAtMs
        Timber.i(
            "EmbeddingWorker: embedded %d/%d tracks in %s (%d ms/track avg)",
            successCount,
            pending.size,
            formatDuration(totalMs),
            if (successCount > 0) totalMs / successCount else 0L,
        )
        Result.success()
    }

    /**
     * Attempt to promote this worker to a foreground service. Returns false on
     * `ForegroundServiceStartNotAllowedException` (Android 14+ background-start
     * restriction) — caller can keep working without a notification.
     */
    private suspend fun trySetForeground(info: ForegroundInfo): Boolean = try {
        setForeground(info)
        true
    } catch (e: Throwable) {
        Timber.w("EmbeddingWorker: setForeground denied (%s); running headless", e.message)
        false
    }

    /**
     * Build the foreground notification with live progress + elapsed time + ETA.
     *
     * Notification text format (Android wraps long lines automatically):
     *
     *   "47 / 562 · 320 ms/track · 0:15 elapsed · ETA 2:44"
     *
     * - elapsed is measured from the start of *this* worker run (not from the first
     *   library scan), so retries restart the timer cleanly.
     * - ETA is `(total - progress) * avg-ms-per-track` rounded to seconds; displays "—"
     *   while we don't have any successful embeds to base the estimate on.
     */
    private fun makeForegroundInfo(progress: Int, total: Int, startedAtMs: Long): ForegroundInfo {
        ensureChannel()
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val msPerTrack = if (progress > 0) elapsedMs / progress else 0L
        val etaMs = if (progress > 0 && progress < total)
            (total - progress).toLong() * msPerTrack
        else 0L

        val parts = mutableListOf<String>()
        parts.add("$progress / $total")
        if (msPerTrack > 0) parts.add("${msPerTrack} ms/track")
        parts.add("${formatDuration(elapsedMs)} elapsed")
        if (etaMs > 0) parts.add("ETA ${formatDuration(etaMs)}")
        val contentText = parts.joinToString(" · ")

        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_latentjam_24)
                .setContentTitle(applicationContext.getString(R.string.info_app_name))
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setProgress(total, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** "0:42", "1:23", "12:05". Compact for the notification line. */
    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ML processing", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun floatsToBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    companion object {
        const val WORK_NAME = "latentjam-embedding"
        const val NOTIFICATION_ID = 0xE3B0
        const val CHANNEL_ID = "latentjam-ml"
        const val PROGRESS_REPORT_EVERY = 4

        // android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1.
        // Inlined to avoid an Android API 29-only import condition; the value is
        // stable in the platform.
        const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1

        fun enqueueIfPending(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
