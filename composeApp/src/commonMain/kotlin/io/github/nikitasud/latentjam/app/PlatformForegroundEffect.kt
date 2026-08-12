/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/**
 * Runs [onReturn] every time the app comes back to the foreground — and NOT at launch.
 *
 * For state that goes stale while the app sits in the background: the music library after the
 * user downloads a track in a browser, listening figures after a widget-driven play. Launch
 * work stays in launch effects; both platforms report an activation at startup too, and this
 * effect swallows that one (see [ForegroundReturns]).
 */
@Composable
expect fun PlatformForegroundEffect(onReturn: () -> Unit)

/** Distinguishes the launch activation from genuine background→foreground returns. */
internal class ForegroundReturns {
    private var launched = false

    /** Returns true when this activation is a return rather than the launch. */
    fun onActivated(): Boolean {
        if (!launched) {
            launched = true
            return false
        }
        return true
    }
}
