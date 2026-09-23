/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UINavigationController
import platform.UIKit.UITabBarController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberTrackSharer(): (List<TrackDescriptor>) -> Unit {
    // The Compose host identifies the originating window, including iPad multi-window scenes.
    // A process-wide keyWindow can otherwise select a different scene or no window at all.
    val host = LocalUIViewController.current
    return remember(host) {
        { tracks ->
            val urls = tracks.mapNotNull { it.audioUri?.let(NSURL::URLWithString) }.distinct()
            val presenter = host.view.window?.let { window ->
                (window.rootViewController ?: host).sharePresenter()
            }
            if (urls.isNotEmpty() && presenter != null && presenter !is UIActivityViewController &&
                !presenter.isBeingPresented() && !presenter.isBeingDismissed()
            ) {
                val controller = UIActivityViewController(
                    activityItems = urls,
                    applicationActivities = null,
                )
                // UIKit adapts this to a sheet on phones and requires an anchored popover on
                // iPad. The action can come from several surfaces, so anchor without an arrow
                // to the current presenter's center instead of inventing a button location.
                controller.popoverPresentationController?.apply {
                    val source = presenter.view
                    sourceView = source
                    sourceRect = CGRectMake(
                        CGRectGetMidX(source.bounds),
                        CGRectGetMidY(source.bounds),
                        1.0,
                        1.0,
                    )
                    permittedArrowDirections = 0uL
                }
                presenter.presentViewController(controller, true, null)
            }
        }
    }
}

/** Present above the current native surface instead of beneath an existing modal or container. */
@OptIn(ExperimentalForeignApi::class)
private fun UIViewController.sharePresenter(): UIViewController {
    var current = this
    while (true) {
        val next = current.presentedViewController ?: when (val container = current) {
            is UINavigationController -> container.visibleViewController
            is UITabBarController -> container.selectedViewController
            else -> null
        }
        if (next == null || next === current) return current
        current = next
    }
}
