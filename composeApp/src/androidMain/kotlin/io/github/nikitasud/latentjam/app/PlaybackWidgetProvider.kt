/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import io.github.nikitasud.latentjam.app.shared.R
import io.github.nikitasud.latentjam.playback.PlaybackService

/**
 * Home-screen transport controls: previous / play-pause / next plus a tap-through to the app.
 *
 * Buttons deliver plain `ACTION_MEDIA_BUTTON` key events straight to the Media3 session service —
 * the same path Bluetooth buttons use — so the widget needs no process of its own to stay honest:
 * whatever the session decides is what happens, and the notification remains the live display.
 */
internal class PlaybackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_playback).apply {
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setOnClickPendingIntent(
                R.id.widget_previous,
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            )
            setOnClickPendingIntent(
                R.id.widget_play_pause,
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )
            setOnClickPendingIntent(
                R.id.widget_next,
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT),
            )
        }

    private fun mediaButton(context: Context, keyCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            keyCode,
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
