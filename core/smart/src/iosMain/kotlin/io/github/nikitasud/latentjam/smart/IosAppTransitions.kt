/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.concurrent.Volatile
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The app's moves between foreground and background, for a track's analysis deadline: iOS suspends a
 * backgrounded app, and a deadline whose window spanned that is not the track's fault. Observed from the first
 * read on (an analysis starts in the foreground); written on the main queue, readable from any thread.
 */
internal object IosAppTransitions {
    @Volatile
    private var transitions = 0

    @Volatile
    private var background = false

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            background = true
            transitions++
        }
        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            background = false
            transitions++
        }
    }

    /** How many times the app entered the background or returned from it. */
    val count: Int get() = transitions

    /** True from entering the background until the app is about to return to the foreground. */
    val inBackground: Boolean get() = background
}
