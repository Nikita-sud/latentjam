/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.UIKit.UIUserInterfaceStyle

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformThemeEffect(darkTheme: Boolean) {
    SideEffect {
        UIApplication.sharedApplication.keyWindow?.apply {
            overrideUserInterfaceStyle = if (darkTheme) {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            }
            rootViewController?.setNeedsStatusBarAppearanceUpdate()
        }
    }
}
