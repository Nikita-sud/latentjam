/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android [IndexingNotifier], backed by a foreground service.
 *
 * The service is the point. A progress bar posted from a plain background
 * coroutine would be a lie: analysis runs on [AppGraph.appScope], which
 * outlives composition but not the process, and Android is free to kill a
 * backgrounded app within seconds. The bar would freeze partway and never
 * move again, which is worse than never showing one. Declaring the work
 * foreground is what makes the number it displays true.
 *
 * `dataSync` rather than `mediaPlayback`: the app may well be analysing while
 * nothing is playing, and borrowing the playback type for unrelated work is
 * exactly what the typed-service rules exist to stop.
 *
 * Starts, updates and stops are decided by [AnalysisForegroundGate].
 */
internal class AndroidIndexingNotifier(context: Context) : IndexingNotifier {

    private val context = context.applicationContext

    private val gate = AnalysisForegroundGate(
        object : AnalysisForegroundGate.Platform {
            override fun requestStart() {
                ContextCompat.startForegroundService(context, Intent(context, IndexingService::class.java))
            }

            override fun post(content: AnalysisForegroundGate.Content) {
                // Only the notification is updated: a start command from a backgrounded app throws once
                // the foreground state is gone, and is not needed while it lasts.
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                manager.notify(IndexingService.NOTIFICATION_ID, analysisNotification(context, content))
            }
        },
    ).also { IndexingService.gate = it }

    override fun open(): IndexingSession {
        val session = gate.open()
        return object : IndexingSession {
            override fun show(title: String, text: String, done: Int, total: Int) =
                session.show(AnalysisForegroundGate.Content(title, text, done, total))

            override fun close() = session.close()
        }
    }
}

/**
 * Holds the process in the foreground while the library is analysed, and owns
 * the progress notification.
 *
 * Started once per foreground stretch; progress then arrives through the notification manager.
 */
class IndexingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stop = {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            Unit
        }
        val activeGate = gate
        try {
            if (activeGate == null) {
                // No run in this process asked for the service (the notifier was never created).
                try {
                    startForegroundWith(AnalysisForegroundGate.Content("", "", 0, 0))
                } finally {
                    stop()
                }
            } else {
                activeGate.onStartCommand(startForeground = ::startForegroundWith, stop = stop)
            }
        } catch (failure: RuntimeException) {
            // The system refused the foreground start (a background start past its exemption). The
            // run cannot keep its promise to finish, so it ends now, as onTimeout ends it.
            println("SMART: analysis foreground start refused: $failure")
            AppGraph.cancelAutomaticIndexing()
        }
        // Not sticky: if the process dies mid-analysis there is nothing to
        // resume from here — the engine persists at chunk granularity and the
        // app restarts the walk itself.
        return START_NOT_STICKY
    }

    private fun startForegroundWith(content: AnalysisForegroundGate.Content) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            analysisNotification(this, content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    /**
     * Android 15 caps data-sync foreground services at six background hours in a rolling day.
     * Once that budget expires the service has only a few seconds to stop or the system reports an
     * ANR. Cancel the process-lifetime worker as well as this Service: leaving inference running
     * after foreground protection is revoked would make the progress notification disappear while
     * the advertised work continued unreliably in a killable background process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val stop = {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        gate?.onTimeout(stop) ?: stop()
        AppGraph.cancelAutomaticIndexing()
    }

    override fun onDestroy() {
        gate?.onDestroy()
        super.onDestroy()
    }

    internal companion object {
        const val NOTIFICATION_ID = 4201

        /** The process's notifier gate; null until the notifier exists. */
        @Volatile
        var gate: AnalysisForegroundGate? = null
    }
}

private const val CHANNEL_ID = "library_analysis"

/** The ongoing progress notification, built the same way for the start and for every update. */
internal fun analysisNotification(context: Context, content: AnalysisForegroundGate.Content): Notification {
    ensureChannel(context, content.title)
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(content.title)
        .setContentText(content.text)
        // The progress row itself is useful navigation: one tap returns to the existing app
        // task instead of opening a duplicate Activity.
        .setContentIntent(appLaunchPendingIntent(context))
        // Determinate: the whole point is that the user can see how much is
        // left, and an indeterminate spinner answers none of the questions
        // they walked away wondering about.
        .setProgress(content.total.coerceAtLeast(1), content.done, content.total <= 0)
        .setOngoing(true)
        // Silent: this fires once per chunk, and a library of several hundred
        // tracks would otherwise buzz dozens of times.
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

/**
 * Names the channel from the caller's already-localised title.
 *
 * The app's strings live in Compose resources across eighteen locales, and
 * those resolve through a suspend call that a Service cannot make while
 * building a notification. Adding a parallel Android `res/` string would
 * mean maintaining the same text twice in eighteen files. Since this
 * channel carries exactly one kind of notification, its title is a fair
 * name for it.
 *
 * Known cost: Android caches the channel name at creation, so switching
 * device language leaves it stale in system settings until reinstall.
 */
private fun ensureChannel(context: Context, title: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    // A blank title (a stray start with no run behind it) would name the channel for good.
    if (title.isBlank()) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            title.ifBlank { CHANNEL_ID },
            // LOW: progress worth glancing at, never worth interrupting for.
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

private fun appLaunchPendingIntent(context: Context): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(
        context,
        IndexingService.NOTIFICATION_ID,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

actual fun indexingNotifierModule(): Module = module {
    single<IndexingNotifier> { AndroidIndexingNotifier(context = get()) }
}
