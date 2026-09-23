/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@Composable
internal actual fun PlatformThemeEffect(darkTheme: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    DisposableEffect(activity, view, darkTheme) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: activity?.window ?: return@DisposableEffect onDispose { }
        fun applySystemBarTheme() {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        // System-bar appearance may be reset when returning from Settings or a picker without
        // changing any Compose state. Use the live rendered theme, not the
        // persisted launch preference, at both lifecycle return and subsequent focus gain.
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) applySystemBarTheme()
        }
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) applySystemBarTheme()
        }
        lifecycle?.addObserver(lifecycleObserver)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusObserver)
        applySystemBarTheme()
        onDispose {
            lifecycle?.removeObserver(lifecycleObserver)
            view.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(focusObserver)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal actual fun fullscreenDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)
