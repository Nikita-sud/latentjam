/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberTrackSharer(): (List<TrackDescriptor>) -> Unit = remember {
    { tracks ->
        val urls = tracks.mapNotNull { it.audioUri?.let(NSURL::URLWithString) }.distinct()
        val presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        if (urls.isNotEmpty() && presenter != null) {
            val controller = UIActivityViewController(
                activityItems = urls,
                applicationActivities = null,
            )
            // Full-screen presentation is valid on both idioms and avoids iPad's requirement for
            // an arbitrary popover anchor — the action applies to a bottom bar, not one tiny view.
            controller.modalPresentationStyle = UIModalPresentationFullScreen
            presenter.presentViewController(controller, true, null)
        }
    }
}
