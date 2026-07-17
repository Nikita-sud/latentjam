/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point: a thin shell around the shared [App] composable.
 *
 * Lives in :composeApp's androidMain (not :androidApp) because AGP 9's
 * application plugin cannot host Kotlin alongside the KMP toolchain; the
 * :androidApp packaging shell declares this activity in its manifest by
 * fully-qualified name. All wiring lives in the common [AppGraph].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(engine = AppGraph.engine)
        }
    }
}
