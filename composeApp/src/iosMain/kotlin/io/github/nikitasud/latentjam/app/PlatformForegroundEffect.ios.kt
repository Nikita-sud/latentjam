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
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

@Composable
actual fun PlatformForegroundEffect(onLeave: () -> Unit, onReturn: () -> Unit) {
    val currentOnReturn by rememberUpdatedState(onReturn)
    val currentOnLeave by rememberUpdatedState(onLeave)
    val gate = remember { ForegroundReturns() }
    DisposableEffect(Unit) {
        // didBecomeActive also fires at launch; the gate keeps this returns-only.
        val activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (gate.onActivated()) currentOnReturn()
        }
        val backgroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            currentOnLeave()
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(activeObserver)
            NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
        }
    }
}
