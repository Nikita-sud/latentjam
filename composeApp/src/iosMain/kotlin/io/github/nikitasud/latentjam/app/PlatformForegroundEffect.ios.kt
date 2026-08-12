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
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification

@Composable
actual fun PlatformForegroundEffect(onReturn: () -> Unit) {
    val currentOnReturn by rememberUpdatedState(onReturn)
    val gate = remember { ForegroundReturns() }
    DisposableEffect(Unit) {
        // didBecomeActive also fires at launch; the gate keeps this returns-only.
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (gate.onActivated()) currentOnReturn()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
}
