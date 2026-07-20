/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
 */
internal class AndroidIndexingNotifier(private val context: Context) : IndexingNotifier {

    override fun show(title: String, text: String, done: Int, total: Int) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, IndexingService::class.java).apply {
                putExtra(IndexingService.EXTRA_TITLE, title)
                putExtra(IndexingService.EXTRA_TEXT, text)
                putExtra(IndexingService.EXTRA_DONE, done)
                putExtra(IndexingService.EXTRA_TOTAL, total)
            },
        )
    }

    override fun finish() {
        context.stopService(Intent(context, IndexingService::class.java))
    }
}

/**
 * Holds the process in the foreground while the library is analysed, and owns
 * the progress notification.
 *
 * Started fresh for every update rather than bound: each `startForegroundService`
 * delivers a new intent to the running instance, so progress arrives through
 * [onStartCommand] and there is no binder lifecycle to get wrong.
 */
class IndexingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0

        ensureChannel(title)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            // Determinate: the whole point is that the user can see how much is
            // left, and an indeterminate spinner answers none of the questions
            // they walked away wondering about.
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .setOngoing(true)
            // Silent: this fires once per chunk, and a library of several hundred
            // tracks would otherwise buzz dozens of times.
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        // Not sticky: if the process dies mid-analysis there is nothing to
        // resume from here — the engine persists at chunk granularity and the
        // app restarts the walk itself.
        return START_NOT_STICKY
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
    private fun ensureChannel(title: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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

    internal companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        private const val CHANNEL_ID = "library_analysis"
        private const val NOTIFICATION_ID = 4201
    }
}

actual fun indexingNotifierModule(): Module = module {
    single<IndexingNotifier> { AndroidIndexingNotifier(context = get()) }
}
