/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point, called from the iosApp Swift shell
 * (`MainViewControllerKt.MainViewController()`): a thin shell around the
 * shared [App] composable. All wiring lives in the common [AppGraph].
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    App(engine = AppGraph.engine)
}
