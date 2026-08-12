/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun PlatformForegroundEffect(onLeave: () -> Unit, onReturn: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // The latest lambdas without re-registering the observer on every recomposition.
    val currentOnReturn by rememberUpdatedState(onReturn)
    val currentOnLeave by rememberUpdatedState(onLeave)
    val gate = remember { ForegroundReturns() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // ON_RESUME also fires on first launch; the gate keeps this returns-only.
            if (event == Lifecycle.Event.ON_RESUME && gate.onActivated()) currentOnReturn()
            // ON_STOP cannot precede the first ON_RESUME, so it needs no gate.
            if (event == Lifecycle.Event.ON_STOP) currentOnLeave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
