/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point, called from the iosApp Swift shell
 * (`MainViewControllerKt.MainViewController()`): starts the shared [AppGraph]
 * (no platform bindings needed on iOS) and hosts the shared [App] composable.
 */
fun MainViewController(): UIViewController {
    AppGraph.start()
    return ComposeUIViewController {
        App(engine = AppGraph.engine, library = AppGraph.library)
    }
}
